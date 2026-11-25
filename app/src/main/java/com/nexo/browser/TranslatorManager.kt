package com.nexo.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Traducción completa de páginas en el DOM con restauración instantánea sin recargar.
 *
 * - Extracción de segmentos mediante retorno directo de `evaluateJavaScript` (sin exponer
 *   tokens por `cefriumQuery`).
 * - Aislamiento por generación de documento (`docGen`) y cancelación inmediata de llamadas
 *   OkHttp activas si la pestaña navega, recarga, restaura o se cierra.
 * - Aplicación progresiva por lotes para reducir latencia percibida en páginas largas.
 */
object TranslatorManager {
    private const val SCRIPT_ASSET = "nexo/page-translator.js"
    private const val MAX_BATCH_ITEMS = 24
    private const val MAX_BATCH_CHARS = 4000
    private const val MAX_SEGMENTS = 400
    private const val EXTRACT_TIMEOUT_MS = 2500L
    private const val MAX_RESPONSE_BYTES = 512 * 1024

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val translatorDns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val clean = hostname.lowercase().trim('[', ']')
            return if (clean == "localhost" || clean == "127.0.0.1" || clean == "::1") {
                Dns.SYSTEM.lookup(hostname)
            } else {
                NativeImagePolicy.publicDns(Dns.SYSTEM).lookup(hostname)
            }
        }
    }
    private val http by lazy {
        OkHttpClient.Builder()
            .dns(translatorDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private fun readBounded(body: ResponseBody?): String {
        val stream = body?.byteStream() ?: return ""
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_RESPONSE_BYTES) return ""
                out.write(buf, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    @Volatile private var scriptSource: String? = null
    private val translatedTargets = WeakHashMap<EngineSession, String>()
    private val activeJobs = ConcurrentHashMap<EngineSession, ActiveTranslation>()

    internal class ActiveTranslation(
        val docGen: Long,
        val targetLang: String
    ) {
        private val cancelled = AtomicBoolean(false)
        @Volatile private var call: Call? = null

        val isCancelled: Boolean get() = cancelled.get()

        fun attach(activeCall: Call) {
            call = activeCall
            if (cancelled.get()) activeCall.cancel()
        }

        fun cancel() {
            cancelled.set(true)
            call?.cancel()
        }
    }

    data class TextSegment(val id: Int, val text: String)

    internal data class ExtractionPayload(
        val segments: List<TextSegment>,
        val totalEligible: Int,
        val truncated: Boolean,
        val generation: Int
    )

    fun initialize(context: Context) {
        if (scriptSource == null) {
            scriptSource = runCatching {
                context.applicationContext.assets.open(SCRIPT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
    }

    fun isTranslated(session: EngineSession?): Boolean =
        session != null && synchronized(translatedTargets) { translatedTargets.containsKey(session) }

    fun forget(session: EngineSession) {
        activeJobs.remove(session)?.cancel()
        synchronized(translatedTargets) { translatedTargets.remove(session) }
    }

    internal fun diagnosticsSummary(): String {
        val translatedCount = synchronized(translatedTargets) { translatedTargets.size }
        val activeCount = activeJobs.size
        return "sesiones traducidas=$translatedCount, traducciones activas=$activeCount"
    }

    internal fun parseExtraction(raw: String?): ExtractionPayload? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        val json = runCatching {
            when (val parsed = JSONTokener(trimmed).nextValue()) {
                is JSONObject -> parsed
                is String -> {
                    val inner = parsed.trim()
                    if (inner.startsWith("{")) JSONObject(inner) else null
                }
                else -> null
            }
        }.getOrNull() ?: return null

        val arr = json.optJSONArray("segments")
        val segments = mutableListOf<TextSegment>()
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), MAX_SEGMENTS)) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optInt("i", -1)
                val text = item.optString("t").trim()
                if (id >= 0 && text.isNotEmpty()) segments += TextSegment(id, text)
            }
        }
        val totalEligible = json.optInt("totalEligible", segments.size).coerceAtLeast(segments.size)
        val truncated = json.optBoolean("truncated", totalEligible > segments.size)
        val generation = json.optInt("generation", 0)
        return ExtractionPayload(segments, totalEligible, truncated, generation)
    }

    /** Divide segmentos en lotes respetando cantidad y caracteres máximos. Puro: probado en la JVM. */
    fun chunkSegments(
        segments: List<TextSegment>,
        maxItems: Int = MAX_BATCH_ITEMS,
        maxChars: Int = MAX_BATCH_CHARS
    ): List<List<TextSegment>> {
        if (segments.isEmpty()) return emptyList()
        val batches = mutableListOf<List<TextSegment>>()
        var current = mutableListOf<TextSegment>()
        var chars = 0
        for (seg in segments) {
            val clean = seg.text.replace('\n', ' ').trim()
            if (clean.isEmpty()) continue
            if (current.isNotEmpty() && (current.size >= maxItems || chars + clean.length > maxChars)) {
                batches += current
                current = mutableListOf()
                chars = 0
            }
            current += TextSegment(seg.id, clean)
            chars += clean.length
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    fun resolveTargetLanguage(context: Context): String {
        val pref = BrowserPrefs.captionTranslateTo(context).trim()
        if (pref.isNotEmpty()) return pref
        val sys = Locale.getDefault().language.lowercase()
        return if (CaptionProtocol.TARGET_LANGUAGES.any { it.code == sys }) sys else "es"
    }

    /** Traduce la página activa in-situ o restaura el texto original si ya está traducida. */
    fun translateActive(context: Context, callback: (Boolean, String) -> Unit) {
        val tab = TabManager.activeTab()
        val session = tab?.session as? CefriumEngineSession
        if (tab == null || session == null || BrowserPrefs.isNexoHome(tab.url)) {
            callback(false, "Abre una página web para traducirla.")
            return
        }
        if (isTranslated(session)) {
            restoreActive(context, callback)
            return
        }
        if (tab.networkProfile?.let { it.type != "direct" } == true) {
            callback(false, "La traducción en la nube está desactivada en pestañas con proxy o Tor para proteger tu ruta.")
            return
        }
        if (tab.isPrivate) {
            callback(false, "La traducción en la nube está desactivada en pestañas privadas para no enviar contenido externo.")
            return
        }
        initialize(context)
        val source = scriptSource
        if (source == null) {
            callback(false, "No se pudo cargar el módulo de traducción.")
            return
        }
        val appContext = context.applicationContext
        val target = resolveTargetLanguage(appContext)
        val docGen = session.documentGeneration()
        val job = ActiveTranslation(docGen = docGen, targetLang = target)
        activeJobs.put(session, job)?.cancel()

        val extractScript = """
            (function(){
              try {
                $source
                if (!window.__nexoPageTranslator && typeof window.__nexoCreatePageTranslator === 'function') {
                  window.__nexoPageTranslator = window.__nexoCreatePageTranslator(document, window);
                }
                if (!window.__nexoPageTranslator) return null;
                var meta = typeof window.__nexoPageTranslator.extractSegmentsWithMeta === 'function'
                  ? window.__nexoPageTranslator.extractSegmentsWithMeta()
                  : { segments: window.__nexoPageTranslator.extractSegments(), totalEligible: 0, truncated: false, generation: 0 };
                return JSON.stringify(meta);
              } catch (_) {
                return null;
              }
            })();
        """.trimIndent()

        val settled = AtomicBoolean(false)
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                activeJobs.remove(session, job)
                job.cancel()
                callback(false, "Tiempo agotado al leer el contenido de la página.")
            }
        }
        main.postDelayed(timeout, EXTRACT_TIMEOUT_MS)

        session.evaluateJsonInMainFrame(extractScript) { raw ->
            main.post {
                if (!settled.compareAndSet(false, true)) return@post
                main.removeCallbacks(timeout)
                if (job.isCancelled || session.documentGeneration() != job.docGen) {
                    activeJobs.remove(session, job)
                    return@post
                }
                val extracted = parseExtraction(raw)
                if (extracted == null || extracted.segments.isEmpty()) {
                    activeJobs.remove(session, job)
                    callback(false, "No se encontró texto traducible en esta página.")
                    return@post
                }
                runProgressiveTranslation(appContext, session, job, extracted, callback)
            }
        }
    }

    /** Restaura el contenido original de los nodos traducidos sin recargar la página. */
    fun restoreActive(@Suppress("UNUSED_PARAMETER") context: Context, callback: (Boolean, String) -> Unit) {
        val session = TabManager.activeTab()?.session as? CefriumEngineSession
        if (session == null) {
            callback(false, "No hay ninguna pestaña activa.")
            return
        }
        activeJobs.remove(session)?.cancel()
        synchronized(translatedTargets) { translatedTargets.remove(session) }
        session.evaluateInMainFrame(
            "(function(){try{if(window.__nexoPageTranslator)window.__nexoPageTranslator.restore();}catch(_){}})();"
        ) {
            callback(true, "Idioma original restaurado sin recargar.")
        }
    }

    private fun runProgressiveTranslation(
        appContext: Context,
        session: CefriumEngineSession,
        job: ActiveTranslation,
        extracted: ExtractionPayload,
        callback: (Boolean, String) -> Unit
    ) {
        val batches = chunkSegments(extracted.segments)
        val jsGen = extracted.generation
        io.execute {
            var appliedSegments = 0
            var failures = 0
            for (batch in batches) {
                if (job.isCancelled || session.documentGeneration() != job.docGen) {
                    activeJobs.remove(session, job)
                    return@execute
                }
                val lines = batch.map { it.text }
                val translated = translateBatch(appContext, lines, job.targetLang, job)
                if (job.isCancelled || session.documentGeneration() != job.docGen) {
                    activeJobs.remove(session, job)
                    return@execute
                }
                if (translated != null && translated.size == batch.size) {
                    val batchJson = JSONArray()
                    batch.forEachIndexed { idx, seg ->
                        batchJson.put(JSONObject().put("i", seg.id).put("t", translated[idx]))
                    }
                    appliedSegments += batch.size
                    val payload = batchJson.toString()
                    main.post {
                        if (job.isCancelled || session.documentGeneration() != job.docGen) return@post
                        val applyScript = "(function(){try{if(window.__nexoPageTranslator)window.__nexoPageTranslator.applyTranslations($payload,$jsGen);}catch(_){}})();"
                        session.evaluateInMainFrame(applyScript) {}
                        synchronized(translatedTargets) { translatedTargets[session] = job.targetLang }
                    }
                } else {
                    failures++
                }
            }
            activeJobs.remove(session, job)
            if (job.isCancelled || session.documentGeneration() != job.docGen) return@execute

            main.post {
                if (job.isCancelled || session.documentGeneration() != job.docGen) return@post
                if (appliedSegments == 0) {
                    callback(false, "No se pudo traducir la página. Revisa tu conexión o proveedor.")
                    return@post
                }
                synchronized(translatedTargets) { translatedTargets[session] = job.targetLang }
                val label = CaptionProtocol.languageLabel(job.targetLang)
                val omitted = (extracted.totalEligible - extracted.segments.size).coerceAtLeast(0)
                val msg = when {
                    failures > 0 -> "Página traducida parcialmente a $label ($appliedSegments bloques)."
                    extracted.truncated && omitted > 0 ->
                        "Página traducida a $label ($appliedSegments bloques; $omitted omitidos por límite)."
                    else -> "Página traducida a $label (toca Traducir otra vez para restaurar)."
                }
                callback(true, msg)
            }
        }
    }

    private fun translateBatch(
        context: Context,
        lines: List<String>,
        target: String,
        job: ActiveTranslation
    ): List<String>? {
        val provider = BrowserPrefs.translationProvider(context)
        if (provider == BrowserPrefs.TRANSLATE_DEEPL) {
            val key = BrowserPrefs.deeplKey(context)
            if (key.isNotBlank() && CaptionProtocol.deeplSupports(target)) {
                val res = translateWithDeepL(key, lines, target, job)
                if (res != null) return res
            }
        }
        if (provider == BrowserPrefs.TRANSLATE_AI) {
            // «Una API para todo»: la conexión de Nexo AI si está configurada; si no, la propia.
            val conn = com.nexo.browser.ai.AiHub.translation(context)
            if (conn != null) {
                val res = translateWithAi(conn.provider, conn.endpoint, conn.model, conn.key, lines, target, job)
                if (res != null) return res
            }
        }
        return translateWithGoogle(lines, target, job)
    }

    private const val GOOGLE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    /**
     * Traduce un fragmento seleccionado por el usuario usando el proveedor e idioma destino
     * configurados en Nexo. Devuelve `(textoTraducido, mensajeError)` en el hilo principal.
     */
    fun translateSnippet(
        context: Context,
        text: String,
        onResult: (translated: String?, error: String?) -> Unit
    ) {
        val cleaned = text.trim().take(2000)
        if (cleaned.isEmpty()) {
            main.post { onResult(null, "No hay texto seleccionado para traducir.") }
            return
        }
        val appContext = context.applicationContext
        val target = resolveTargetLanguage(appContext)
        val paragraphs = cleaned.split(Regex("""\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(cleaned) }
        val job = ActiveTranslation(docGen = 0L, targetLang = target)
        io.execute {
            val translated = translateBatch(appContext, paragraphs, target, job)
            main.post {
                if (translated != null && translated.isNotEmpty()) {
                    onResult(translated.joinToString("\n\n"), null)
                } else {
                    onResult(null, "No se pudo traducir la selección. Revisa tu conexión o proveedor.")
                }
            }
        }
    }

    /** Hoja rápida Nexo UI 7.0 para traducir texto seleccionado directamente desde Chromium. */
    fun showSelectionSheet(activity: android.app.Activity, selectedText: String) {
        val clean = selectedText.trim().take(2000)
        if (clean.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        val target = resolveTargetLanguage(activity)
        val targetLabel = CaptionProtocol.languageLabel(target)
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }

        val body = NexoUi.column(activity, horizontalDp = NexoUi.Tokens.SHEET_PADDING)
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            NexoGlass.paperShadow(this, p)
        }
        val origView = android.widget.TextView(activity).apply {
            text = clean
            textSize = 13f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.25f)
        }
        val resultView = android.widget.TextView(activity).apply {
            text = "Traduciendo a $targetLabel…"
            textSize = 16f
            typeface = NexoUi.regular(activity)
            setTextColor(p.text)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(10f), 0, dp(4f))
        }
        card.addView(origView)
        card.addView(NexoUi.divider(activity, 8f))
        card.addView(resultView)
        body.addView(card)

        var translatedResult: String? = null
        lateinit var sheet: android.app.Dialog
        val copyBtn = NexoUi.actionRow(activity, R.drawable.ic_menu_copy, "Copiar traducción") {
            val out = translatedResult ?: return@actionRow
            val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Traducción", out))
            Toastish.show(activity, "Traducción copiada")
            sheet.dismiss()
        }.apply {
            isEnabled = false
            alpha = 0.45f
        }
        val actions = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
            clipToOutline = true
            addView(copyBtn)
        }
        body.addView(actions, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12f) })

        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Traducción ($targetLabel)"), body))
        translateSnippet(activity, clean) { translated, error ->
            if (activity.isFinishing || activity.isDestroyed || !sheet.isShowing) return@translateSnippet
            if (translated != null) {
                translatedResult = translated
                resultView.text = translated
                copyBtn.isEnabled = true
                copyBtn.alpha = 1f
            } else {
                resultView.text = error ?: "No se pudo traducir la selección."
                resultView.setTextColor(p.danger)
            }
        }
    }

    private fun translateWithGoogle(lines: List<String>, target: String, job: ActiveTranslation): List<String>? = runCatching {
        val body = CaptionProtocol.googleBatchBody(lines, target)
            .toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(CaptionProtocol.GOOGLE_BATCH_URL)
            .header("User-Agent", GOOGLE_UA)
            .header("Accept", "application/json, text/plain, */*")
            .post(body)
            .build()
        val call = http.newCall(req)
        job.attach(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) return null
            CaptionProtocol.parseGoogleLines(readBounded(resp.body), lines.size)
        }
    }.getOrNull()

    private fun translateWithDeepL(key: String, lines: List<String>, target: String, job: ActiveTranslation): List<String>? = runCatching {
        val body = CaptionProtocol.deeplBatchBody(lines, target)
            .toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(CaptionProtocol.deeplEndpoint(key))
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(body)
            .build()
        val call = http.newCall(req)
        job.attach(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) return null
            CaptionProtocol.parseDeeplLines(readBounded(resp.body), lines.size)
        }
    }.getOrNull()

    private fun translateWithAi(
        provider: String,
        endpoint: String,
        model: String,
        key: String,
        lines: List<String>,
        target: String,
        job: ActiveTranslation
    ): List<String>? = runCatching {
        val reqSpec = CaptionProtocol.aiRequest(
            provider = provider,
            endpoint = endpoint,
            model = model,
            key = key,
            targetName = CaptionProtocol.LANGUAGE_NAMES[target] ?: target,
            lines = lines,
            context = emptyList()
        )
        if (!CaptionProtocol.isSafeKeyedEndpoint(reqSpec.url)) return null
        val builder = Request.Builder()
            .url(reqSpec.url)
            .post(reqSpec.body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        reqSpec.headers.forEach { (k, v) -> builder.header(k, v) }
        val call = http.newCall(builder.build())
        job.attach(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) return null
            CaptionProtocol.parseAiLines(readBounded(resp.body), lines.size)
        }
    }.getOrNull()
}
