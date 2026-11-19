package com.nexo.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Traducción completa de páginas en el DOM con restauración instantánea sin recargar.
 *
 * Reutiliza el proveedor configurado en Nexo (Google sin clave, DeepL o modelo de IA)
 * mediante [CaptionProtocol], enviando bloques acotados de texto visible (nunca scripts,
 * código ni campos de formulario/contraseña) y devolviendo cada traducción a su nodo.
 */
object TranslatorManager {
    private const val SCRIPT_ASSET = "nexo/page-translator.js"
    private const val MAX_BATCH_ITEMS = 24
    private const val MAX_BATCH_CHARS = 4000

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var scriptSource: String? = null
    private val translatedTargets = WeakHashMap<EngineSession, String>()
    private val pendingCallbacks = ConcurrentHashMap<String, PendingJob>()

    private data class PendingJob(
        val session: CefriumEngineSession,
        val context: Context,
        val targetLang: String,
        val callback: (Boolean, String) -> Unit
    )

    data class TextSegment(val id: Int, val text: String)

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
        synchronized(translatedTargets) { translatedTargets.remove(session) }
        pendingCallbacks.entries.removeIf { it.value.session === session }
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
        initialize(context)
        val source = scriptSource
        if (source == null) {
            callback(false, "No se pudo cargar el módulo de traducción.")
            return
        }
        val target = resolveTargetLanguage(context)
        val requestId = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        pendingCallbacks[requestId] = PendingJob(session, context.applicationContext, target, callback)

        val token = JSONObject.quote(session.nxrToken)
        val reqIdJson = JSONObject.quote(requestId)
        val extractScript = """
            (function(){
              try {
                $source
                if (!window.__nexoPageTranslator && typeof window.__nexoCreatePageTranslator === 'function') {
                  window.__nexoPageTranslator = window.__nexoCreatePageTranslator(document, window);
                }
                var segs = window.__nexoPageTranslator ? window.__nexoPageTranslator.extractSegments() : [];
                if (typeof window.cefriumQuery === 'function') {
                  window.cefriumQuery({
                    request: JSON.stringify({
                      m: JSON.stringify({
                        id: 1,
                        kind: 'nexo_translate_segments',
                        payload: { token: $token, reqId: $reqIdJson, segments: segs }
                      }),
                      s: -1
                    }),
                    onSuccess: function(){},
                    onFailure: function(){}
                  });
                }
              } catch (_) {}
            })();
        """.trimIndent()
        session.evaluateInMainFrame(extractScript) {}
    }

    /** Restaura el contenido original de los nodos traducidos sin recargar la página. */
    fun restoreActive(@Suppress("UNUSED_PARAMETER") context: Context, callback: (Boolean, String) -> Unit) {
        val session = TabManager.activeTab()?.session as? CefriumEngineSession
        if (session == null) {
            callback(false, "No hay ninguna pestaña activa.")
            return
        }
        synchronized(translatedTargets) { translatedTargets.remove(session) }
        session.evaluateInMainFrame(
            "(function(){try{if(window.__nexoPageTranslator)window.__nexoPageTranslator.restore();}catch(_){}})();"
        ) {
            callback(true, "Idioma original restaurado sin recargar.")
        }
    }

    /** Recibe los segmentos extraídos desde la página por el canal verificado. */
    internal fun onPageSegments(session: CefriumEngineSession, isMainFrame: Boolean, payload: JSONObject?): Boolean {
        if (!isMainFrame || payload == null) return false
        if (payload.optString("token") != session.nxrToken) return false
        val reqId = payload.optString("reqId")
        val job = pendingCallbacks.remove(reqId) ?: return false
        if (job.session !== session) return false

        val arr = payload.optJSONArray("segments")
        val segments = mutableListOf<TextSegment>()
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), 200)) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optInt("i", -1)
                val text = item.optString("t").trim()
                if (id >= 0 && text.isNotEmpty()) segments += TextSegment(id, text)
            }
        }
        if (segments.isEmpty()) {
            main.post { job.callback(false, "No se encontró texto traducible en esta página.") }
            return true
        }

        io.execute {
            val batches = chunkSegments(segments)
            val translatedEntries = JSONArray()
            var failures = 0
            for (batch in batches) {
                val lines = batch.map { it.text }
                val translated = translateBatch(job.context, lines, job.targetLang)
                if (translated != null && translated.size == batch.size) {
                    batch.forEachIndexed { idx, seg ->
                        translatedEntries.put(JSONObject().put("i", seg.id).put("t", translated[idx]))
                    }
                } else {
                    failures++
                }
            }
            if (translatedEntries.length() == 0) {
                main.post { job.callback(false, "No se pudo traducir la página. Revisa tu conexión o proveedor.") }
                return@execute
            }
            val payloadJson = translatedEntries.toString()
            main.post {
                val applyScript = "(function(){try{if(window.__nexoPageTranslator)window.__nexoPageTranslator.applyTranslations($payloadJson);}catch(_){}})();"
                job.session.evaluateInMainFrame(applyScript) {
                    synchronized(translatedTargets) { translatedTargets[job.session] = job.targetLang }
                    val label = CaptionProtocol.languageLabel(job.targetLang)
                    val msg = if (failures == 0) {
                        "Página traducida a $label (toca Traducir otra vez para restaurar)."
                    } else {
                        "Página traducida parcialmente a $label."
                    }
                    job.callback(true, msg)
                }
            }
        }
        return true
    }

    private fun translateBatch(context: Context, lines: List<String>, target: String): List<String>? {
        val provider = BrowserPrefs.translationProvider(context)
        if (provider == BrowserPrefs.TRANSLATE_DEEPL) {
            val key = BrowserPrefs.deeplKey(context)
            if (key.isNotBlank() && CaptionProtocol.deeplSupports(target)) {
                val res = translateWithDeepL(key, lines, target)
                if (res != null) return res
            }
        }
        if (provider == BrowserPrefs.TRANSLATE_AI) {
            val aiProvider = BrowserPrefs.aiTranslateProvider(context)
            val endpoint = BrowserPrefs.aiTranslateEndpoint(context)
            val model = BrowserPrefs.aiTranslateModel(context)
            val key = BrowserPrefs.aiTranslateKey(context)
            if (key.isNotBlank() || aiProvider == "custom") {
                val res = translateWithAi(aiProvider, endpoint, model, key, lines, target)
                if (res != null) return res
            }
        }
        return translateWithGoogle(lines, target)
    }

    private fun translateWithGoogle(lines: List<String>, target: String): List<String>? = runCatching {
        val body = CaptionProtocol.googleBatchBody(lines, target)
            .toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder().url(CaptionProtocol.GOOGLE_BATCH_URL).post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            CaptionProtocol.parseGoogleLines(resp.body?.string().orEmpty(), lines.size)
        }
    }.getOrNull()

    private fun translateWithDeepL(key: String, lines: List<String>, target: String): List<String>? = runCatching {
        val body = CaptionProtocol.deeplBatchBody(lines, target)
            .toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(CaptionProtocol.deeplEndpoint(key))
            .header("Authorization", "DeepL-Auth-Key $key")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            CaptionProtocol.parseDeeplLines(resp.body?.string().orEmpty(), lines.size)
        }
    }.getOrNull()

    private fun translateWithAi(
        provider: String,
        endpoint: String,
        model: String,
        key: String,
        lines: List<String>,
        target: String
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
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            CaptionProtocol.parseAiLines(resp.body?.string().orEmpty(), lines.size)
        }
    }.getOrNull()
}
