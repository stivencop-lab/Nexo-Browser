package com.nexo.browser.shield

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumContentRequest
import com.cefrium.CefriumContentResponse
import com.cefrium.CefriumProfile
import com.cefrium.CefriumResourceInterceptor
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.PageBridge
import com.nexo.browser.TabManager
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PhantomShield nativo sobre Chromium (Cefrium).
 *
 * - Red: [NetworkEngine] en el interceptor de cada navegador y de cada perfil
 *   (service workers), con las mismas listas que la extensión. Chromium da el
 *   tipo exacto de cada petición y la página principal, sin deducirlos.
 * - Páginas: los mismos scripts de contenido de la extensión
 *   (`content-script.js`, `cosmetic-filter.js`, `heuristic-vision.js`) con
 *   un `browser.*` mínimo (`phantom-shim.js`) que habla con Nexo por
 *   `window.cefriumQuery` ([PageChannel], `cefrium-port.js`), y
 *   `page-hooks.js`, todos al inicio de cada documento.
 * - Panel: `popup/popup.html` de la extensión en una hoja ([PhantomShieldPanel]).
 * - Cookies: la política de Ajustes (todas, propias, ninguna) en el mismo
 *   interceptor.
 *
 * La clasificación con IA, el parcheador anti-bloqueo y la variación de
 * Canvas por extensión quedan para una fase posterior: el panel lo explica.
 */
object PhantomShield {
    internal const val ASSET_ROOT = "extensions/phantomshield/"
    internal const val PANEL_HOST = "phantomshield.nexo.invalid"
    internal const val PANEL_ORIGIN = "https://$PANEL_HOST"
    internal const val BASE_URL = "$PANEL_ORIGIN/"
    private const val PREFS = "nexo_phantomshield"
    private const val KEY_ENABLED = "enabled"
    private const val UPDATE_INTERVAL_MS = 24L * 3600 * 1000
    private const val MAX_LIST_BYTES = 15L * 1024 * 1024
    private const val HIDE_BASE_SELECTOR = "[data-phantomshield-hidden=\"true\"]"
    internal const val AI_UNAVAILABLE = "La IA de PhantomShield llegará en una próxima versión de Nexo."

    private val SETTING_KEYS = listOf("adblockEnabled", "privacyEnabled", "popupBlockEnabled", "antiBlockEnabled")
    private val PAGE_READABLE = setOf("adblockEnabled", "privacyEnabled", "popupBlockEnabled")
    private val PANEL_WRITABLE = setOf("adblockEnabled", "privacyEnabled", "popupBlockEnabled", "antiBlockEnabled",
        "disabled_domains", "aiConfig")

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor()
    private lateinit var appContext: Context
    private lateinit var store: ShieldStore
    @Volatile private var started = false
    @Volatile private var enabled = true
    @Volatile private var settings = ShieldSettings()
    @Volatile private var rules: FilterLists.Result = FilterLists.build(FilterLists.Bundled(emptyList(), emptyList(), emptyList(), emptyList()), emptyMap())
    @Volatile private var popupDomains: List<String> = emptyList()
    private var bundled: FilterLists.Bundled? = null
    private val blockedTotal = AtomicLong(0)
    private var persistScheduled = false
    private var updating: Thread? = null
    private val tabs = CopyOnWriteArrayList<WeakReference<ShieldTab>>()
    private val http by lazy {
        OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()
    }
    private val scripts by lazy { Scripts(appContext) }

    /** Política de cookies de Ajustes; la lee el hilo de red. */
    @Volatile var cookieMode: String = BrowserPrefs.COOKIES_BALANCED

    /** Estado por pestaña: página principal, bloqueos y marcos que escuchan cambios de ajustes. */
    class ShieldTab internal constructor(val isPrivate: Boolean) {
        val id: Int = nextId.incrementAndGet()
        internal var session: WeakReference<CefriumEngineSession>? = null
        @Volatile var topUrl: String? = null
            private set
        @Volatile var topHost: String? = null
            private set
        internal val blocked = AtomicInteger(0)
        /** Marcos de la pestaña: consultas, ajustes y órdenes de Nexo. */
        val channel = PageChannel()
        internal var lastPopupReport = 0L

        /** Respuestas propias pendientes: el hilo de red decide, otro hilo responde. */
        internal val answers = ConcurrentHashMap<String, ShieldDecision>()

        internal fun onMainFrame(url: String) {
            topUrl = url
            topHost = UrlParts.host(url)
            blocked.set(0)
            channel.reset()
        }

        /** Navegación sin petición de red (atrás/adelante en caché, cambios de historial). */
        fun onLocation(url: String) {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                val host = UrlParts.host(url)
                if (host != topHost) blocked.set(0)
                topUrl = url
                topHost = host
            }
        }

        companion object { private val nextId = AtomicInteger(0) }
    }

    // --- Arranque y listas ---

    fun initialize(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        store = ShieldStore(File(appContext.filesDir, "phantomshield"))
        enabled = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        readSettings()
        blockedTotal.set(store.long("blockedCount"))
        // Reglas incluidas ya mismo (protegen desde la primera página); las listas, en segundo plano.
        runCatching {
            val data = BundledData.parse { readAsset(it) }
            bundled = data
            popupDomains = data.popupDomains
            rules = FilterLists.build(data, emptyMap())
        }
        store.addListener { changes -> onStorageChanged(changes) }
        io.execute { reloadLists() }
        io.scheduleWithFixedDelay({ updateIfDue() }, 5, 3600, TimeUnit.SECONDS)
    }

    /** Peticiones de los service workers de un perfil (no pertenecen a ninguna pestaña); una vez por perfil. */
    fun attachProfile(profile: CefriumProfile) {
        if (!attachedProfiles.add(profile.name + "|" + profile.isIncognito)) return
        runCatching { profile.setResourceInterceptor(Interceptor(null)) }
            .onFailure { attachedProfiles.remove(profile.name + "|" + profile.isIncognito) }
    }

    private val attachedProfiles = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun readAsset(path: String): String =
        appContext.assets.open(ASSET_ROOT + path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun readSettings() {
        settings = ShieldSettings(
            adblockEnabled = store.boolean("adblockEnabled", true),
            privacyEnabled = store.boolean("privacyEnabled", false),
            popupBlockEnabled = store.boolean("popupBlockEnabled", true),
            antiBlockEnabled = store.boolean("antiBlockEnabled", false),
            disabledDomains = ShieldSettings.normalizeDomains(store.stringList("disabled_domains"))
        )
    }

    private fun reloadLists() {
        val data = bundled ?: return
        val texts = FilterLists.LISTS.mapNotNull { list -> runCatching { store.listText(list.id) }.getOrNull()?.let { list.id to it } }.toMap()
        if (texts.isEmpty()) return
        runCatching { rules = FilterLists.build(data, texts) }
    }

    private fun updateIfDue() {
        if (System.currentTimeMillis() - store.long("phantom_last_update") >= UPDATE_INTERVAL_MS) runCatching { updateAllLists() }
    }

    /** Descarga las listas (con espejos), guarda el resultado como la extensión y recarga las reglas. */
    @Synchronized
    internal fun updateAllLists(): JSONArray {
        val results = JSONArray()
        var requiredOk = true
        var anyRequired = false
        for (list in FilterLists.LISTS) {
            var error: String? = null
            var done = false
            for (url in list.urls) {
                val outcome = runCatching { download(url) }
                val text = outcome.getOrNull()
                if (text != null) {
                    store.saveList(list.id, text)
                    done = true
                    break
                }
                error = outcome.exceptionOrNull()?.message ?: "Error desconocido"
            }
            val entry = JSONObject().put("id", list.id).put("name", list.name).put("ok", done)
            if (list.optional) entry.put("optional", true)
            if (!done) entry.put("error", error ?: "Error desconocido")
            results.put(entry)
            if (!list.optional) { anyRequired = true; requiredOk = requiredOk && done }
        }
        val status = JSONObject().put("phantom_last_update_attempt", System.currentTimeMillis()).put("phantom_update_results", results)
        if (anyRequired && requiredOk) status.put("phantom_last_update", System.currentTimeMillis())
        store.set(status)
        reloadLists()
        return results
    }

    private fun download(url: String): String {
        val request = Request.Builder().url(url).header("Cache-Control", "no-store").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP error ${response.code}")
            val body = response.body ?: error("Lista vacía")
            if (body.contentLength() > MAX_LIST_BYTES) error("Lista excede el límite de tamaño")
            val source = body.source()
            val buffer = okio.Buffer()
            while (source.read(buffer, 64 * 1024) != -1L) {
                if (buffer.size > MAX_LIST_BYTES) error("Lista excede el límite de tamaño")
            }
            val text = buffer.readUtf8()
            if (!FilterLists.isValidListText(text)) error("Lista inválida o excede el límite de tamaño")
            return text
        }
    }

    // --- Estado visible ---

    fun isEnabled(): Boolean = enabled

    /** Resumen para el diagnóstico del motor. */
    fun diagnostics(): String {
        if (!started) return "sin iniciar"
        val lastUpdate = store.long("phantom_last_update")
        val ago = if (lastUpdate == 0L) "nunca" else "hace ${(System.currentTimeMillis() - lastUpdate) / 3_600_000} h"
        return "${if (enabled) "activo" else "apagado"} · anuncios=${settings.adblockEnabled} · popups=${settings.popupBlockEnabled} · " +
            "reglas red=${rules.networkCount} cosméticas=${rules.cosmeticCount} · bloqueos=${blockedTotal.get()} · " +
            "listas actualizadas $ago · cookies=$cookieMode"
    }

    fun setEnabled(value: Boolean) {
        if (!started || enabled == value) return
        enabled = value
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
        // Las páginas abiertas vuelven a pedir su estado, como con un cambio de ajustes.
        broadcast(JSONObject().put("adblockEnabled", JSONObject().put("newValue", settings.adblockEnabled && value)))
    }

    private fun activeTab(): ShieldTab? = (TabManager.activeSession() as? CefriumEngineSession)?.shield

    /** Insignia del escudo: bloqueos de la pestaña, "PAUSE" en sitios pausados u "OFF". */
    fun badge(global: Boolean = false): String? {
        if (!started) return null
        if (!enabled || !settings.adblockEnabled) return "OFF"
        if (global) return count(blockedTotal.get())
        val tab = activeTab() ?: return null
        if (settings.isHostDisabled(tab.topHost)) return "PAUSE"
        return count(tab.blocked.get().toLong())
    }

    private fun count(value: Long): String = if (value > 999) "999+" else value.toString()

    private fun recordBlocked(tab: ShieldTab?) {
        tab?.blocked?.incrementAndGet()
        if (tab?.isPrivate == true) return
        blockedTotal.incrementAndGet()
        synchronized(this) {
            if (persistScheduled) return
            persistScheduled = true
        }
        main.postDelayed({
            synchronized(this) { persistScheduled = false }
            store.set(JSONObject().put("blockedCount", blockedTotal.get()))
        }, 2000)
    }

    // --- Red ---

    fun newTab(isPrivate: Boolean): ShieldTab = ShieldTab(isPrivate).also { tab ->
        tabs.removeAll { it.get() == null }
        tabs.add(WeakReference(tab))
    }

    /** URL sin parámetros de rastreo para una navegación principal (o la misma). */
    fun cleanNavigation(url: String): String {
        if (!started || !enabled) return url
        val host = UrlParts.host(url) ?: return url
        if (!settings.isEnabledForHost(host)) return url
        return ShieldPolicy.stripTrackingParams(url) ?: url
    }

    /**
     * Interceptor de red de una pestaña ([tab]) o de los service workers (`null`).
     * Cefrium solo pasa cada cookie por Java si el interceptor implementa el
     * filtro: se usa la variante con filtro únicamente cuando hace falta.
     */
    fun interceptor(tab: ShieldTab?): CefriumResourceInterceptor =
        if (tab != null && filtersCookies()) CookieInterceptor(tab) else Interceptor(tab)

    /** La política de cookies necesita ver cada cookie (todas = la de Chromium, sin filtro). */
    fun filtersCookies(): Boolean = cookieMode == BrowserPrefs.COOKIES_NONE || cookieMode == BrowserPrefs.COOKIES_FIRST_PARTY

    private open class Interceptor(protected val tab: ShieldTab?) : CefriumResourceInterceptor {
        private val answers get() = tab?.answers ?: workerAnswers

        /** Hilo de red, sincrónico: true cancela la petición. */
        override fun shouldBlockRequest(request: CefriumBrowser.ResourceRequest): Boolean {
            val url = request.url ?: return false
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            if (request.isMainFrame) tab?.onMainFrame(url)
            if (!started || !enabled) return forget(url)
            val type = ShieldPolicy.typeFromEngine(request.type?.name.orEmpty(), url)
            val top = request.topLevelUrl?.takeIf { it.isNotEmpty() } ?: tab?.topUrl ?: url
            val document = request.documentUrl?.takeIf { it.isNotEmpty() } ?: top
            val decision = runCatching {
                ShieldPolicy.decide(rules.network, settings, popupDomains, url, type, UrlParts.host(top), document)
            }.getOrDefault(ShieldDecision.Allow)
            return when (decision) {
                ShieldDecision.Allow -> forget(url)
                ShieldDecision.Block -> { forget(url); recordBlocked(tab); true }
                else -> {
                    // Script de sustitución o página de aviso: la responde shouldInterceptRequest.
                    // La respuesta no se consume: dos peticiones a la vez reciben la misma.
                    if (answers.size > MAX_ANSWERS) answers.clear()
                    answers[url] = decision
                    false
                }
            }
        }

        /** La URL ya no lleva respuesta propia (otra decisión, sitio pausado, escudo apagado). */
        private fun forget(url: String): Boolean {
            if (!answers.isEmpty()) answers.remove(url)
            return false
        }

        /** Hilo de fondo: solo responde lo que [shouldBlockRequest] marcó; el resto pasa enseguida. */
        override fun shouldInterceptRequest(request: CefriumContentRequest): CefriumContentResponse? {
            if (answers.isEmpty()) return null
            val decision = answers[request.url ?: return null] ?: return null
            recordBlocked(tab)
            return respond(decision)
        }
    }

    /** Con "solo propias" o "ninguna": cada cookie pasa por la política de Nexo. */
    private class CookieInterceptor(tab: ShieldTab) : Interceptor(tab) {
        override fun onCanSendCookie(url: String?, name: String?, value: String?): Boolean = cookieAllowed(tab, url)
        override fun onCanSaveCookie(url: String?, name: String?, value: String?): Boolean = cookieAllowed(tab, url)
    }

    private const val MAX_ANSWERS = 256
    private val workerAnswers = ConcurrentHashMap<String, ShieldDecision>()

    /** Política de cookies: "propias" compara el sitio de la cookie con el de la página principal. */
    private fun cookieAllowed(tab: ShieldTab?, url: String?): Boolean = when (cookieMode) {
        BrowserPrefs.COOKIES_NONE -> false
        BrowserPrefs.COOKIES_FIRST_PARTY -> {
            val top = tab?.topHost
            top == null || ShieldPolicy.siteOfHost(top) == ShieldPolicy.siteOfHost(url?.let { UrlParts.host(it) })
        }
        else -> true
    }

    /** Scripts de sustitución ya leídos (son cuatro archivos pequeños). */
    private val surrogates = ConcurrentHashMap<String, ByteArray>()

    private fun respond(decision: ShieldDecision): CefriumContentResponse? = when (decision) {
        is ShieldDecision.Surrogate -> {
            val bytes = surrogates.getOrPut(decision.asset) {
                runCatching { appContext.assets.open(ASSET_ROOT + decision.asset).use { it.readBytes() } }.getOrDefault(ByteArray(0))
            }
            CefriumContentResponse("application/javascript", ByteArrayInputStream(bytes))
                .setCharset("utf-8").setContentLength(bytes.size.toLong())
                .setHeader("Cache-Control", "no-store").setHeader("Access-Control-Allow-Origin", "*")
        }
        ShieldDecision.BlockPage -> CefriumContentResponse.ofText("text/html", BLOCKED_PAGE)
            .setCharset("utf-8").setHeader("Cache-Control", "no-store")
            .setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        else -> null
    }

    /** Ventana nueva que pide [opener]: ¿la corta PhantomShield? (cuenta el bloqueo). */
    fun blocksPopup(opener: ShieldTab, url: String): Boolean {
        if (!started || !enabled) return false
        val blocked = runCatching {
            ShieldPolicy.blocksPopup(rules.network, settings, popupDomains, url, opener.topUrl)
        }.getOrDefault(false)
        if (blocked) recordBlocked(opener)
        return blocked
    }

    // --- Scripts y puente ---

    /**
     * Scripts de contenido y puente en un navegador nuevo, antes de su primera
     * navegación. El puente admite todos los orígenes y marcos: igual que con
     * la extensión, Nexo decide en cada mensaje qué puede pedir la página.
     */
    fun attach(browser: CefriumBrowser, tab: ShieldTab) {
        if (!started) return
        runCatching {
            browser.addDocumentStartJavaScript(scripts.pageHooks, setOf("*"))
            browser.addDocumentStartJavaScript(scripts.content, setOf("*"))
        }
        browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.anyOrigin().withSubframes(), object : CefriumBrowser.QueryHandler {
            override fun onQuery(queryId: Long, request: String, origin: String, callback: CefriumBrowser.QueryCallback): Boolean =
                onQuery(queryId, request, origin, true, callback)

            override fun onQuery(queryId: Long, request: String, origin: String, isMainFrame: Boolean, callback: CefriumBrowser.QueryCallback): Boolean {
                callback.success(onPageQuery(tab, request, origin, isMainFrame))
                return true
            }
        })
        tab.channel.wake = {
            main.post { if (!browser.isClosed) runCatching { browser.evaluateJavaScript("window.__nexoWake&&window.__nexoWake()") } }
        }
    }

    /** Panel: el puente con todos los permisos, solo para su propio origen. */
    internal fun attachPanel(browser: CefriumBrowser, onClose: () -> Unit) {
        val channel = PageChannel()
        panelChannel = channel
        browser.addDocumentStartJavaScript(scripts.panel, setOf(PANEL_ORIGIN))
        browser.setQueryHandler(CefriumBrowser.QueryOriginPolicy.exactly(PANEL_ORIGIN), object : CefriumBrowser.QueryHandler {
            override fun onQuery(queryId: Long, request: String, origin: String, callback: CefriumBrowser.QueryCallback): Boolean {
                val parsed = channel.parse(request) ?: return false
                callback.success(channel.envelope(parsed.message?.let { onPanelMessage(it, channel, onClose) }, parsed.since))
                return true
            }
        })
        channel.wake = { main.post { if (!browser.isClosed) runCatching { browser.evaluateJavaScript("window.__nexoWake&&window.__nexoWake()") } } }
    }

    internal fun detachPanel() { panelChannel = null }

    @Volatile private var panelChannel: PageChannel? = null

    /** [frame]: token del marco que envía (puente de Nexo); lo pone la página, solo sirve para ordenar sus marcos. */
    private class Call(val id: Int, val kind: String, val payload: Any?, val frame: String?)

    private fun parse(data: String): Call? = runCatching {
        if (data.length > 512 * 1024) return null
        val json = JSONObject(data)
        val frame = json.optString("frame").takeIf { it.length in 8..64 && it.all { c -> c.isLetterOrDigit() } }
        Call(json.getInt("id"), json.getString("kind"), json.opt("payload"), frame)
    }.getOrNull()

    /** Respuesta del puerto a la llamada [id]: resultado o error, en el formato de `phantom-shim.js`. */
    private fun answer(id: Int, result: Any?, error: String? = null): String {
        val json = JSONObject().put("id", id)
        if (error != null) json.put("error", error) else json.put("result", result ?: JSONObject.NULL)
        return json.toString()
    }

    /** Consulta de un marco de [tab] (hilo principal): la respuesta inmediata y los eventos pendientes. */
    internal fun onPageQuery(tab: ShieldTab, request: String, origin: String, isMainFrame: Boolean): String {
        val parsed = tab.channel.parse(request) ?: return tab.channel.envelope(null, -1)
        val reply = parsed.message?.let { onPageMessage(tab, it, origin, isMainFrame) }
        return tab.channel.envelope(reply, parsed.since)
    }

    /** Mensajes de las páginas: solo estado de bloqueo y selectores; ni estadísticas ni ajustes. */
    private fun onPageMessage(tab: ShieldTab, message: String, origin: String, isMainFrame: Boolean): String? {
        val call = parse(message) ?: return null
        val session = tab.session?.get()
        // Puente de Nexo (mini video, subtítulos, modo nocturno): marco por su token, o por su origen.
        val frame = call.frame ?: if (isMainFrame) "top" else "o:" + (UrlParts.host(origin) ?: "")
        if (call.kind == "bridge") {
            val payload = call.payload as? JSONObject
            if (session != null && payload != null) PageBridge.onMessage(session, frame, isMainFrame, payload)
            return null
        }
        if (call.kind == "bridge_call") {
            val payload = call.payload as? JSONObject
            if (session == null || payload == null) return answer(call.id, null)
            // Respuesta inmediata, o más tarde por el canal (la página la espera por su id).
            var sync = true
            var early: Any? = PageBridge.LATER
            val result = PageBridge.onCall(session, isMainFrame, payload) { value ->
                if (sync) early = value else tab.channel.push(answer(call.id, value))
            }
            sync = false
            if (result !== PageBridge.LATER) return answer(call.id, result)
            return if (early !== PageBridge.LATER) answer(call.id, early) else null
        }
        return when (call.kind) {
            "get" -> {
                val all = store.get(JSONObject().apply { PAGE_READABLE.forEach { put(it, defaultSetting(it)) } })
                val requested = requestedKeys(call.payload)
                val result = JSONObject()
                all.keys().forEach { key -> if (requested == null || key in requested) result.put(key, all.get(key)) }
                val defaults = call.payload as? JSONObject
                defaults?.keys()?.forEach { key -> if (!result.has(key)) result.put(key, defaults.get(key)) }
                answer(call.id, result)
            }
            "message" -> {
                val payload = call.payload as? JSONObject ?: return answer(call.id, null)
                answer(call.id, pageMessage(tab, payload, origin, isMainFrame))
            }
            else -> answer(call.id, null, "No disponible")
        }
    }

    internal fun bindSession(tab: ShieldTab, session: CefriumEngineSession) { tab.session = WeakReference(session) }

    /** Orden de Nexo para los scripts de la pestaña; false si la página aún no tiene el puente. */
    internal fun sendToSession(session: CefriumEngineSession, message: JSONObject): Boolean {
        val channel = session.shield.channel
        channel.push(JSONObject().put("event", "native").put("message", message).toString())
        return channel.connected
    }
    private fun defaultSetting(key: String): Boolean = key != "privacyEnabled" && key != "antiBlockEnabled"

    private fun requestedKeys(payload: Any?): Set<String>? = when (payload) {
        is String -> setOf(payload)
        is JSONArray -> (0 until payload.length()).map { payload.optString(it) }.toSet()
        is JSONObject -> payload.keys().asSequence().toSet()
        else -> null
    }

    private fun effectiveEnabled(host: String?): Boolean = enabled && settings.isEnabledForHost(host)

    private fun pageMessage(tab: ShieldTab, message: JSONObject, origin: String, isMainFrame: Boolean): Any? {
        val topHost = tab.topHost.orEmpty()
        val frameHost = UrlParts.host(origin)?.takeIf { it.isNotEmpty() } ?: topHost
        return when (message.optString("type")) {
            "GET_SITE_STATE" -> JSONObject().put("ok", true).put("enabled", effectiveEnabled(topHost))
                .put("aiEnabled", false).put("aiDetail", "basic").put("aiSensitivity", "balanced").put("host", topHost)
            "GET_COSMETIC" -> {
                if (!effectiveEnabled(topHost)) return disabledCosmetic()
                val result = rules.cosmetic.forHost(frameHost)
                JSONObject().put("ok", true).put("enabled", true).put("generic", result.generic).put("injected", false)
                    .put("selectors", JSONArray(listOf(HIDE_BASE_SELECTOR) + result.selectors))
            }
            "COSMETIC_LOOKUP" -> {
                if (!effectiveEnabled(topHost)) return disabledCosmetic()
                val selectors = rules.cosmetic.lookup(frameHost, strings(message.optJSONArray("classes")), strings(message.optJSONArray("ids")))
                JSONObject().put("ok", true).put("enabled", true).put("generic", true).put("injected", false)
                    .put("selectors", JSONArray(selectors))
            }
            "POPUP_BLOCKED" -> {
                // Lo informa la página: como mucho uno por segundo, para que no infle el contador.
                val now = SystemClock.elapsedRealtime()
                if (isMainFrame && now - tab.lastPopupReport > 1000) {
                    tab.lastPopupReport = now
                    recordBlocked(tab)
                }
                JSONObject().put("ok", true)
            }
            "AI_CLASSIFY", "AI_PAGE" -> JSONObject().put("ok", false).put("error", AI_UNAVAILABLE)
            else -> null
        }
    }

    private fun disabledCosmetic() = JSONObject().put("ok", true).put("enabled", false).put("generic", false)
        .put("injected", false).put("selectors", JSONArray())

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until minOf(array.length(), 2000)).mapNotNull { array.opt(it) as? String }

    /** Mensajes del panel: respuesta inmediata, o `null` si llegará después por [channel]. */
    private fun onPanelMessage(message: String, channel: PageChannel, onClose: () -> Unit): String? {
        val call = parse(message) ?: return null
        return when (call.kind) {
            "get" -> {
                val keys = call.payload
                val result = store.get(if (keys == null || keys == JSONObject.NULL) null else keys)
                SETTING_KEYS.forEach { key ->
                    if (!result.has(key) && (keys == null || keys == JSONObject.NULL || requestedKeys(keys)?.contains(key) == true)) {
                        result.put(key, defaultSetting(key))
                    }
                }
                answer(call.id, result)
            }
            "set" -> {
                val values = call.payload as? JSONObject ?: return answer(call.id, null, "Valores no válidos")
                val allowed = JSONObject()
                values.keys().forEach { key -> if (key in PANEL_WRITABLE) allowed.put(key, values.get(key)) }
                store.set(allowed)
                answer(call.id, null)
            }
            "remove" -> {
                store.remove(requestedKeys(call.payload).orEmpty().filter { it in PANEL_WRITABLE })
                answer(call.id, null)
            }
            "tabs.query" -> {
                val session = TabManager.activeSession() as? CefriumEngineSession
                val tab = session?.shield
                val url = TabManager.activeTab()?.url.orEmpty()
                val list = JSONArray()
                if (tab != null) list.put(JSONObject().put("id", tab.id).put("url", url).put("active", true).put("incognito", tab.isPrivate))
                answer(call.id, list)
            }
            "tabs.reload" -> {
                TabManager.activeSession()?.reload(false)
                answer(call.id, null)
            }
            "tabs.sendMessage" -> answer(call.id, null, AI_UNAVAILABLE)
            "openOptions" -> answer(call.id, null, AI_UNAVAILABLE)
            "message" -> {
                val payload = call.payload as? JSONObject ?: return answer(call.id, null)
                var immediate: String? = null
                var sync = true
                panelMessage(payload) { result ->
                    val text = answer(call.id, result)
                    if (sync) immediate = text else channel.push(text)
                }
                sync = false
                immediate
            }
            "close" -> { main.post(onClose); answer(call.id, null) }
            else -> answer(call.id, null, "No disponible")
        }
    }

    private fun panelMessage(message: JSONObject, done: (Any?) -> Unit) {
        when (message.optString("type")) {
            "GET_STATS" -> {
                val tabId = message.optInt("tabId", -1)
                val tab = tabs.mapNotNull { it.get() }.firstOrNull { it.id == tabId }
                val host = message.optString("host").lowercase()
                done(JSONObject().put("ok", true)
                    .put("blockedCount", blockedTotal.get())
                    .put("tabBlocked", tab?.blocked?.get() ?: 0)
                    .put("tabAi", JSONObject.NULL)
                    .put("siteDisabled", settings.isHostDisabled(host))
                    .put("rules", JSONObject().put("network", rules.networkCount).put("cosmetic", rules.cosmeticCount))
                    .put("learnedDomains", JSONObject().put("blocked", 0).put("watching", 0))
                    .put("settings", JSONObject().put("adblockEnabled", settings.adblockEnabled && enabled)
                        .put("privacyEnabled", settings.privacyEnabled).put("popupBlockEnabled", settings.popupBlockEnabled)
                        .put("antiBlockEnabled", settings.antiBlockEnabled)))
            }
            "RESET_STATS" -> {
                blockedTotal.set(0)
                tabs.mapNotNull { it.get() }.forEach { it.blocked.set(0) }
                store.set(JSONObject().put("blockedCount", 0))
                done(JSONObject().put("ok", true).put("blockedCount", 0))
            }
            "FORCE_UPDATE" -> io.execute {
                val result = runCatching { updateAllLists() }.getOrElse {
                    JSONObject().put("ok", false).put("error", it.message ?: "No se pudieron actualizar las listas")
                }
                main.post { done(result) }
            }
            else -> if (message.optString("type").startsWith("AI_")) done(JSONObject().put("ok", false).put("error", AI_UNAVAILABLE))
                else done(null)
        }
    }

    private fun onStorageChanged(changes: JSONObject) {
        if (changes.has("adblockEnabled") || changes.has("privacyEnabled") || changes.has("popupBlockEnabled") ||
            changes.has("antiBlockEnabled") || changes.has("disabled_domains")) readSettings()
        broadcast(changes)
    }

    /** `storage.onChanged` hacia las páginas (solo ajustes legibles) y el panel (todo salvo listas). */
    private fun broadcast(changes: JSONObject) {
        val pageChanges = JSONObject()
        changes.keys().forEach { key -> if (key in PAGE_READABLE || key == "disabled_domains") pageChanges.put(key, if (key == "disabled_domains") JSONObject() else changes.get(key)) }
        val pageText = JSONObject().put("event", "storage").put("changes", pageChanges).toString()
        val panelText = JSONObject().put("event", "storage").put("changes", changes).toString()
        if (pageChanges.length() > 0) tabs.mapNotNull { it.get() }.forEach { tab -> tab.channel.push(pageText) }
        panelChannel?.push(panelText)
    }

    private class Scripts(private val context: Context) {
        private fun asset(path: String) = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        private val shim = asset("nexo/phantom-shim.js")
        private val port = asset("nexo/cefrium-port.js")
        private val media = asset("nexo/media-reporter.js")
        private val bridgeShim = asset("nexo/bridge-shim.js")
        private val bridge = asset("nexo/bridge/page-bridge.js")
        private val pipCss = asset("nexo/bridge/pip.css")
        private val manifest = JSONObject(asset(ASSET_ROOT + "manifest.json")).toString()
        private fun ext(path: String) = asset(ASSET_ROOT + path)

        val pageHooks: String = "(function(){try{\n" + ext("content/page-hooks.js") + "\n}catch(_){}})();"

        /** Un solo script: `browser` existe únicamente en esta clausura. */
        val content: String = buildString {
            append("(function(){'use strict';\n")
            append("if (!/^https?:$/.test(location.protocol)) return;\n")
            append(port).append('\n')
            append("if (!nexoCefriumPort(window === window.top)) return;\n")
            // Los medios y el puente de Nexo capturan el puerto antes de que el shim de PhantomShield lo retire.
            append(media).append('\n')
            append(bridgeShim).append('\n')
            // Estilos del mini video (pip.css): hoja construida, que la CSP del sitio no bloquea.
            append("try { const s = new CSSStyleSheet(); s.replaceSync(").append(JSONObject.quote(pipCss))
                .append("); document.adoptedStyleSheets = document.adoptedStyleSheets.concat([s]); } catch (_) {}\n")
            // Puente de Nexo (page-bridge.js), con su `browser` propio.
            append("{ const nexoBridge = nexoBridgeShim(globalThis.__nexoShield, window === window.top);\n")
            append("if (nexoBridge) (function(browser){ try {\n").append(bridge).append("\n} catch (_) {} })(nexoBridge); }\n")
            append(shim).append('\n')
            append("const browser = nexoPhantomShim(").append(JSONObject.quote(BASE_URL)).append(", ").append(manifest).append(");\n")
            append("if (!browser) return;\n")
            for (file in listOf("content/content-script.js", "data/cosmetic-rules.js", "content/cosmetic-filter.js")) {
                append("try {\n").append(ext(file)).append("\n} catch (_) {}\n")
            }
            append("if (window === window.top && /(^|\\.)(facebook|instagram)\\.com$/.test(location.hostname)) {\n")
            append("try {\n").append(ext("content/heuristic-vision.js")).append("\n} catch (_) {}\n}\n")
            append("})();")
        }

        val panel: String = buildString {
            append(port).append('\n')
            append("nexoCefriumPort(true);\n")
            append(shim).append('\n')
            append("globalThis.browser = nexoPhantomShim(").append(JSONObject.quote(BASE_URL)).append(", ").append(manifest).append(");\n")
        }
    }

    private const val BLOCKED_PAGE = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Ventana bloqueada</title>
<style>body{font:16px/1.5 system-ui,sans-serif;margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center;background:#111418;color:#e6e8ec}
main{max-width:28rem;padding:24px}h1{font-size:1.25rem;margin:0 0 8px}p{margin:0;color:#aab0ba}
@media (prefers-color-scheme: light){body{background:#f6f7f9;color:#14171c}p{color:#55606e}}</style></head>
<body><main><h1>PhantomShield bloqueó esta página</h1><p>Pertenece a una red de ventanas emergentes publicitarias. Puedes cerrar esta pestaña.</p></main></body></html>"""
}
