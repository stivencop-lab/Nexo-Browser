package com.nexo.browser.shield

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nexo.browser.TabManager
import com.nexo.browser.engine.chromium.ChromiumEngineSession
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PhantomShield nativo para la variante Chromium.
 *
 * - Red: [NetworkEngine] en `shouldInterceptRequest` (pestañas y service
 *   workers), con las mismas listas que la extensión.
 * - Páginas: los mismos scripts de contenido de la extensión
 *   (`content-script.js`, `cosmetic-filter.js`, `heuristic-vision.js`) con
 *   un `browser.*` mínimo (`phantom-shim.js`) en un mundo aislado cuando el
 *   WebView lo admite, y `page-hooks.js` en el mundo de la página.
 * - Panel: `popup/popup.html` de la extensión en una hoja ([PhantomShieldPanel]).
 *
 * La clasificación con IA, el parcheador anti-bloqueo y la variación de
 * Canvas por extensión quedan para una fase posterior: el panel lo explica.
 */
@SuppressLint("RequiresFeature")
object PhantomShield {
    internal const val ASSET_ROOT = "extensions/phantomshield/"
    internal const val PANEL_HOST = "phantomshield.nexo.invalid"
    internal const val PANEL_ORIGIN = "https://$PANEL_HOST"
    internal const val BASE_URL = "$PANEL_ORIGIN/"
    private const val BRIDGE = "__nexoShield"
    private const val WORLD = "phantomshield"
    private const val PREFS = "nexo_phantomshield"
    private const val KEY_ENABLED = "enabled"
    private const val UPDATE_INTERVAL_MS = 24L * 3600 * 1000
    private const val MAX_LIST_BYTES = 15L * 1024 * 1024
    private const val HIDE_BASE_SELECTOR = "[data-phantomshield-hidden=\"true\"]"
    internal const val AI_UNAVAILABLE = "La IA de PhantomShield llegará en una próxima versión de Nexo Chromium."

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

    /** Estado por pestaña: página principal, bloqueos y marcos que escuchan cambios de ajustes. */
    class ShieldTab internal constructor(val isPrivate: Boolean) {
        val id: Int = nextId.incrementAndGet()
        @Volatile var topUrl: String? = null
            private set
        @Volatile var topHost: String? = null
            private set
        internal val blocked = AtomicInteger(0)
        internal val frames = CopyOnWriteArrayList<WeakReference<JavaScriptReplyProxy>>()
        internal var lastPopupReport = 0L

        internal fun onMainFrame(url: String) {
            topUrl = url
            topHost = UrlParts.host(url)
            blocked.set(0)
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
        runCatching {
            androidx.webkit.ServiceWorkerControllerCompat.getInstance()
                .setServiceWorkerClient(object : androidx.webkit.ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                        interceptWorker(request)
                })
        }
    }

    /** Service workers de un perfil aislado (privado, Burner). */
    fun attachProfileWorkers(controller: android.webkit.ServiceWorkerController) {
        runCatching {
            controller.setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = interceptWorker(request)
            })
        }
    }

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

    fun setEnabled(value: Boolean) {
        if (!started || enabled == value) return
        enabled = value
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
        // Las páginas abiertas vuelven a pedir su estado, como con un cambio de ajustes.
        broadcast(JSONObject().put("adblockEnabled", JSONObject().put("newValue", settings.adblockEnabled && value)))
    }

    private fun activeTab(): ShieldTab? = (TabManager.activeSession() as? ChromiumEngineSession)?.shield

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

    /** Hilo de red de WebView. `null` = dejar pasar. */
    fun intercept(tab: ShieldTab, request: WebResourceRequest): WebResourceResponse? {
        if (!started) return null
        val url = request.url?.toString() ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (request.isForMainFrame) tab.onMainFrame(url)
        if (!enabled) return null
        val headers = request.requestHeaders.orEmpty()
        val type = ShieldPolicy.inferType(url, request.isForMainFrame, header(headers, "Accept"), header(headers, "Sec-Fetch-Dest"))
        val top = if (request.isForMainFrame) UrlParts.host(url) else tab.topHost
        val decision = runCatching {
            ShieldPolicy.decide(rules.network, settings, popupDomains, url, type, top, tab.topUrl)
        }.getOrDefault(ShieldDecision.Allow)
        return respond(decision, tab)
    }

    private fun interceptWorker(request: WebResourceRequest): WebResourceResponse? {
        if (!started || !enabled) return null
        val url = request.url?.toString() ?: return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val headers = request.requestHeaders.orEmpty()
        val type = ShieldPolicy.inferType(url, false, header(headers, "Accept"), header(headers, "Sec-Fetch-Dest"))
        // Sin pestaña conocida: el propio sitio del worker hace de documento (las reglas de terceros no aplican).
        val decision = runCatching {
            ShieldPolicy.decide(rules.network, settings, popupDomains, url, type, UrlParts.host(url), url)
        }.getOrDefault(ShieldDecision.Allow)
        return respond(decision, null)
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun respond(decision: ShieldDecision, tab: ShieldTab?): WebResourceResponse? = when (decision) {
        ShieldDecision.Allow -> null
        ShieldDecision.Block -> {
            recordBlocked(tab)
            WebResourceResponse("text/plain", "utf-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))
        }
        is ShieldDecision.Surrogate -> {
            recordBlocked(tab)
            val bytes = runCatching { appContext.assets.open(ASSET_ROOT + decision.asset).use { it.readBytes() } }.getOrDefault(ByteArray(0))
            WebResourceResponse("application/javascript", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(bytes))
        }
        ShieldDecision.BlockPage -> {
            recordBlocked(tab)
            WebResourceResponse("text/html", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "default-src 'none'; style-src 'unsafe-inline'"),
                ByteArrayInputStream(BLOCKED_PAGE.toByteArray()))
        }
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

    /** Scripts de contenido y puente en una pestaña nueva (antes de su primera navegación). */
    fun attach(webView: WebView, tab: ShieldTab) {
        if (!started) return
        val listener = WebViewCompat.WebMessageListener { _, message, origin, isMainFrame, reply ->
            onPageMessage(tab, message, origin, isMainFrame, reply)
        }
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, scripts.pageHooks, setOf("*"))
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
                // Mundo aislado: la página no ve ni el puente ni `browser`.
                val world = WebViewCompat.getExecutionWorld(webView, WORLD)
                WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*"), world, listener)
                WebViewCompat.addJavaScriptOnEvent(webView, scripts.content, WebViewCompat.INJECTION_EVENT_DOCUMENT_START, setOf("*"), world)
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // Sin mundos aislados: mismo mundo que la página. El puente solo
                // responde a lo que ya puede ver cualquier página (ver onPageMessage).
                WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*"), listener)
                WebViewCompat.addDocumentStartJavaScript(webView, scripts.content, setOf("*"))
            }
        }
    }

    /** Panel: el puente con todos los permisos, solo para su propio origen. */
    internal fun attachPanel(webView: WebView, onClose: () -> Unit) {
        val listener = WebViewCompat.WebMessageListener { _, message, origin, _, reply ->
            if (origin.toString() == PANEL_ORIGIN) onPanelMessage(message, reply, onClose)
        }
        WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf(PANEL_ORIGIN), listener)
        WebViewCompat.addDocumentStartJavaScript(webView, scripts.panel, setOf(PANEL_ORIGIN))
    }

    internal fun panelSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private class Call(val id: Int, val kind: String, val payload: Any?)

    private fun parse(message: WebMessageCompat): Call? = runCatching {
        val data = message.data ?: return null
        if (data.length > 256 * 1024) return null
        val json = JSONObject(data)
        Call(json.getInt("id"), json.getString("kind"), json.opt("payload"))
    }.getOrNull()

    private fun send(reply: JavaScriptReplyProxy, id: Int, result: Any?, error: String? = null) {
        val json = JSONObject().put("id", id)
        if (error != null) json.put("error", error) else json.put("result", result ?: JSONObject.NULL)
        val text = json.toString()
        val post = { runCatching { reply.postMessage(text) } }
        if (Looper.myLooper() == Looper.getMainLooper()) post() else main.post { post() }
    }

    private fun remember(tab: ShieldTab, reply: JavaScriptReplyProxy) {
        if (tab.frames.any { it.get() === reply }) return
        tab.frames.removeAll { it.get() == null }
        if (tab.frames.size >= 64) tab.frames.removeAt(0)
        tab.frames.add(WeakReference(reply))
    }

    /** Mensajes de las páginas: solo estado de bloqueo y selectores; ni estadísticas ni ajustes. */
    private fun onPageMessage(tab: ShieldTab, message: WebMessageCompat, origin: Uri, isMainFrame: Boolean, reply: JavaScriptReplyProxy) {
        val call = parse(message) ?: return
        remember(tab, reply)
        when (call.kind) {
            "get" -> {
                val all = store.get(JSONObject().apply { PAGE_READABLE.forEach { put(it, defaultSetting(it)) } })
                val requested = requestedKeys(call.payload)
                val result = JSONObject()
                all.keys().forEach { key -> if (requested == null || key in requested) result.put(key, all.get(key)) }
                val defaults = call.payload as? JSONObject
                defaults?.keys()?.forEach { key -> if (!result.has(key)) result.put(key, defaults.get(key)) }
                send(reply, call.id, result)
            }
            "message" -> {
                val payload = call.payload as? JSONObject ?: return send(reply, call.id, null)
                send(reply, call.id, pageMessage(tab, payload, origin, isMainFrame))
            }
            else -> send(reply, call.id, null, "No disponible")
        }
    }

    private fun defaultSetting(key: String): Boolean = key != "privacyEnabled" && key != "antiBlockEnabled"

    private fun requestedKeys(payload: Any?): Set<String>? = when (payload) {
        is String -> setOf(payload)
        is JSONArray -> (0 until payload.length()).map { payload.optString(it) }.toSet()
        is JSONObject -> payload.keys().asSequence().toSet()
        else -> null
    }

    private fun effectiveEnabled(host: String?): Boolean = enabled && settings.isEnabledForHost(host)

    private fun pageMessage(tab: ShieldTab, message: JSONObject, origin: Uri, isMainFrame: Boolean): Any? {
        val topHost = tab.topHost.orEmpty()
        val frameHost = origin.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: topHost
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

    private fun onPanelMessage(message: WebMessageCompat, reply: JavaScriptReplyProxy, onClose: () -> Unit) {
        val call = parse(message) ?: return
        panelFrames.removeAll { it.get() == null }
        if (panelFrames.none { it.get() === reply }) panelFrames.add(WeakReference(reply))
        when (call.kind) {
            "get" -> {
                val keys = call.payload
                val result = store.get(if (keys == null || keys == JSONObject.NULL) null else keys)
                SETTING_KEYS.forEach { key ->
                    if (!result.has(key) && (keys == null || keys == JSONObject.NULL || requestedKeys(keys)?.contains(key) == true)) {
                        result.put(key, defaultSetting(key))
                    }
                }
                send(reply, call.id, result)
            }
            "set" -> {
                val values = call.payload as? JSONObject ?: return send(reply, call.id, null, "Valores no válidos")
                val allowed = JSONObject()
                values.keys().forEach { key -> if (key in PANEL_WRITABLE) allowed.put(key, values.get(key)) }
                store.set(allowed)
                send(reply, call.id, null)
            }
            "remove" -> {
                store.remove(requestedKeys(call.payload).orEmpty().filter { it in PANEL_WRITABLE })
                send(reply, call.id, null)
            }
            "tabs.query" -> {
                val session = TabManager.activeSession() as? ChromiumEngineSession
                val tab = session?.shield
                val url = TabManager.activeTab()?.url.orEmpty()
                val list = JSONArray()
                if (tab != null) list.put(JSONObject().put("id", tab.id).put("url", url).put("active", true).put("incognito", tab.isPrivate))
                send(reply, call.id, list)
            }
            "tabs.reload" -> {
                TabManager.activeSession()?.reload(false)
                send(reply, call.id, null)
            }
            "tabs.sendMessage" -> send(reply, call.id, null, AI_UNAVAILABLE)
            "openOptions" -> send(reply, call.id, null, AI_UNAVAILABLE)
            "message" -> {
                val payload = call.payload as? JSONObject ?: return send(reply, call.id, null)
                panelMessage(payload) { result -> send(reply, call.id, result) }
            }
            else -> send(reply, call.id, null, "No disponible")
        }
    }

    private val panelFrames = CopyOnWriteArrayList<WeakReference<JavaScriptReplyProxy>>()

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
        main.post {
            if (pageChanges.length() > 0) tabs.mapNotNull { it.get() }.forEach { tab ->
                tab.frames.forEach { ref -> ref.get()?.let { runCatching { it.postMessage(pageText) } } }
            }
            panelFrames.forEach { ref -> ref.get()?.let { runCatching { it.postMessage(panelText) } } }
        }
    }

    private class Scripts(private val context: Context) {
        private fun asset(path: String) = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        private val shim = asset("nexo/phantom-shim.js")
        private val manifest = JSONObject(asset(ASSET_ROOT + "manifest.json")).toString()
        private fun ext(path: String) = asset(ASSET_ROOT + path)

        val pageHooks: String = "(function(){try{\n" + ext("content/page-hooks.js") + "\n}catch(_){}})();"

        /** Un solo script: `browser` existe únicamente en esta clausura. */
        val content: String = buildString {
            append("(function(){'use strict';\n")
            append("if (!/^https?:$/.test(location.protocol)) return;\n")
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
