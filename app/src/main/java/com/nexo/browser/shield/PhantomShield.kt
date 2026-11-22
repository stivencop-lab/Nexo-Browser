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
 * - IA (opcional, con la API del usuario): `content/ai-detector.js` en cada
 *   página principal; Nexo decide si el sitio puede usarla ([ShieldAi]) y
 *   pasa los lotes al clasificador original, que corre en un navegador
 *   oculto ([ShieldAiHost]). Los dominios publicitarios aprendidos se cortan
 *   en la red como el resto de reglas.
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
    private const val AI_SCAN_TIMEOUT_MS = 160_000L

    private val SETTING_KEYS = listOf(
        "adblockEnabled", "privacyEnabled", "popupBlockEnabled", "antiBlockEnabled",
        "annoyancesEnabled", "cryptominerEnabled", "gpcEnabled", "queryStrippingEnabled"
    )
    private val PAGE_READABLE = setOf(
        "adblockEnabled", "privacyEnabled", "popupBlockEnabled", "antiBlockEnabled", "gpcEnabled", "annoyancesEnabled"
    )
    private val PANEL_WRITABLE = setOf(
        "adblockEnabled", "privacyEnabled", "popupBlockEnabled", "antiBlockEnabled",
        "protectionLevel", "annoyancesEnabled", "cryptominerEnabled", "gpcEnabled", "queryStrippingEnabled",
        "disabled_domains", "allowed_domains", "user_cosmetic_rules", "aiConfig"
    )
    private const val MAX_TAB_LOG = 40
    private const val MAX_USER_ZAPS_PER_SITE = 30
    private const val MAX_USER_ZAP_SITES = 100
    private const val AI_PAGE_MAX_CALLS = 3
    private const val AI_PAGE_COOLDOWN_MS = 4_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io by lazy { Executors.newSingleThreadScheduledExecutor() }
    private val secureRandom by lazy { java.security.SecureRandom() }
    private lateinit var appContext: Context
    private lateinit var store: ShieldStore
    @Volatile private var started = false
    @Volatile private var enabled = true
    @Volatile private var settings = ShieldSettings()
    @Volatile private var rules: FilterLists.Result = FilterLists.build(FilterLists.Bundled(emptyList(), emptyList(), emptyList(), emptyList()), emptyMap())
    @Volatile private var popupDomains: List<String> = emptyList()
    @Volatile private var trackerDomains: Set<String> = emptySet()
    private var bundled: FilterLists.Bundled? = null
    private val blockedTotal = AtomicLong(0)
    private var persistScheduled = false
    private var updating: Thread? = null
    private val tabs = CopyOnWriteArrayList<WeakReference<ShieldTab>>()
    private val http by lazy {
        OkHttpClient.Builder()
            .dns(com.nexo.browser.NativeImagePolicy.publicDns(okhttp3.Dns.SYSTEM))
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }
    private val scripts by lazy { Scripts(appContext) }

    /** Cierra el host auxiliar de IA si la actividad principal se destruye para no retenerla en memoria. */
    fun onActivityDestroyed() {
        ShieldAiHost.close("actividad cerrada")
    }

    /** `aiConfig` y `aiAdDomains` ya interpretados; se renuevan al cambiar el almacén. */
    @Volatile private var aiConfig: ShieldAi.Config = ShieldAi.Config.OFF
    @Volatile private var learned: ShieldAi.Learned = ShieldAi.Learned.EMPTY

    /** Política de cookies y preferencias de privacidad de Ajustes; las lee el hilo de red. */
    @Volatile var cookieMode: String = BrowserPrefs.COOKIES_BALANCED
    @Volatile private var prefQueryStripping: Boolean = true
    @Volatile private var prefFingerprinting: Boolean = true
    @Volatile private var prefGpc: Boolean = true
    @Volatile private var prefStrictTracking: Boolean = false
    @Volatile private var prefTrackingProtection: Boolean = true

    internal data class BlockedEvent(val host: String, val category: BlockCategory, val type: String, val at: Long)

    /** Estado por pestaña: página principal, bloqueos, registro en vivo y marcos que escuchan cambios de ajustes. */
    class ShieldTab internal constructor(val isPrivate: Boolean) {
        val id: Int = nextId.incrementAndGet()
        internal var session: WeakReference<CefriumEngineSession>? = null
        @Volatile var topUrl: String? = null
            private set
        @Volatile var topHost: String? = null
            private set
        internal val blocked = AtomicInteger(0)
        internal val blockedAds = AtomicInteger(0)
        internal val blockedTrackers = AtomicInteger(0)
        internal val blockedPopups = AtomicInteger(0)
        internal val blockedMiners = AtomicInteger(0)
        internal val blockedThreats = AtomicInteger(0)
        private val logLock = Any()
        private val logRing = ArrayDeque<BlockedEvent>(MAX_TAB_LOG)
        /** Marcos de la pestaña: consultas, ajustes y órdenes de Nexo. */
        val channel = PageChannel()
        internal var lastPopupReport = 0L
        /** Presupuesto de llamadas IA por carga de página (evita abuso por scripts de la web). */
        internal val aiPageCalls = AtomicInteger(0)
        @Volatile internal var lastAiCallAt = 0L
        /** Carga actual (cambia con cada página principal): las respuestas de IA de otra carga se descartan. */
        @Volatile internal var page = 0
        internal var ai = ShieldAi.TabState()
        /** Respuestas que el panel espera de esta pestaña («Analizar con IA»), por id. */
        internal val replies = ConcurrentHashMap<Int, (Any?) -> Unit>()
        internal val replyTimeouts = ConcurrentHashMap<Int, Runnable>()

        /** Respuestas propias pendientes: el hilo de red decide, otro hilo responde. */
        internal val answers = ConcurrentHashMap<String, ShieldDecision>()

        internal fun recordCategory(category: BlockCategory, host: String?, type: String) {
            when (category) {
                BlockCategory.AD, BlockCategory.DNR -> blockedAds.incrementAndGet()
                BlockCategory.TRACKER -> blockedTrackers.incrementAndGet()
                BlockCategory.POPUP -> blockedPopups.incrementAndGet()
                BlockCategory.MINER -> blockedMiners.incrementAndGet()
                BlockCategory.THREAT -> blockedThreats.incrementAndGet()
            }
            val cleanHost = host?.lowercase()?.removeSuffix(".").orEmpty()
            if (cleanHost.isEmpty()) return
            synchronized(logLock) {
                // Evita duplicar la misma entrada consecutiva para mantener el registro legible.
                val last = logRing.lastOrNull()
                if (last != null && last.host == cleanHost && last.category == category && last.type == type) return
                if (logRing.size >= MAX_TAB_LOG) logRing.removeFirst()
                logRing.addLast(BlockedEvent(cleanHost, category, type, System.currentTimeMillis()))
            }
        }

        internal fun resetCounters() {
            blocked.set(0)
            blockedAds.set(0)
            blockedTrackers.set(0)
            blockedPopups.set(0)
            blockedMiners.set(0)
            blockedThreats.set(0)
            synchronized(logLock) { logRing.clear() }
        }

        internal fun snapshotLog(): JSONArray {
            val out = JSONArray()
            val copy = synchronized(logLock) { logRing.toList().asReversed() }
            for (item in copy) {
                out.put(JSONObject()
                    .put("host", item.host)
                    .put("category", item.category.id)
                    .put("type", item.type)
                    .put("at", item.at))
            }
            return out
        }

        internal fun categoriesJson(): JSONObject = JSONObject()
            .put("ads", blockedAds.get())
            .put("trackers", blockedTrackers.get())
            .put("popups", blockedPopups.get())
            .put("miners", blockedMiners.get())
            .put("threats", blockedThreats.get())

        internal fun onMainFrame(url: String) {
            topUrl = url
            topHost = UrlParts.host(url)
            resetCounters()
            answers.clear()
            newPage()
            channel.reset()
        }

        private fun newPage() {
            page++
            aiPageCalls.set(0)
            lastAiCallAt = 0L
            main.post {
                ai = ShieldAi.TabState()
                replyTimeouts.values.forEach { main.removeCallbacks(it) }
                replyTimeouts.clear()
                val stale = replies.values.toList()
                replies.clear()
                stale.forEach { it(JSONObject().put("ok", false).put("error", "La página cambió.")) }
            }
        }

        /** Libera temporizadores, respuestas pendientes y referencias al cerrar la pestaña. */
        internal fun dispose() {
            answers.clear()
            session = null
            synchronized(logLock) { logRing.clear() }
            tabs.removeAll { it.get() == null || it.get() === this }
            main.post {
                replyTimeouts.values.forEach { main.removeCallbacks(it) }
                replyTimeouts.clear()
                val stale = replies.values.toList()
                replies.clear()
                stale.forEach { it(JSONObject().put("ok", false).put("error", "La pestaña se cerró.")) }
            }
        }

        /** Navegación sin petición de red (atrás/adelante en caché, cambios de historial). */
        fun onLocation(url: String) {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                val host = UrlParts.host(url)
                if (host != topHost) { resetCounters(); newPage() }
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
        syncBrowserPrefs(appContext)
        readSettings()
        // Mantener unificados `enabled` y `store["adblockEnabled"]` tras iniciar.
        if (settings.adblockEnabled != enabled) {
            enabled = settings.adblockEnabled
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
        readAi(aiConfig = true, domains = true)
        blockedTotal.set(store.long("blockedCount"))
        // Reglas incluidas ya mismo (protegen desde la primera página); las listas, en segundo plano.
        runCatching {
            val data = BundledData.parse { readAsset(it) }
            bundled = data
            popupDomains = data.popupDomains
            trackerDomains = data.trackerDomains.map { it.lowercase() }.toSet()
            rules = FilterLists.build(data, emptyMap())
        }
        store.addListener { changes -> onStorageChanged(changes) }
        io.execute { reloadLists() }
        io.scheduleWithFixedDelay({ updateIfDue() }, 5, 3600, TimeUnit.SECONDS)
    }

    /** Sincroniza las preferencias de privacidad de Ajustes de Nexo con PhantomShield. */
    fun syncBrowserPrefs(context: Context) {
        val prevPrivacy = effectivePrivacyEnabled()
        val prevGpc = effectiveGpcEnabled()
        val prevLevel = effectiveProtectionLevel()
        cookieMode = BrowserPrefs.cookieMode(context)
        prefQueryStripping = BrowserPrefs.queryStripping(context)
        prefFingerprinting = BrowserPrefs.fingerprinting(context)
        prefGpc = BrowserPrefs.globalPrivacyControl(context)
        prefStrictTracking = BrowserPrefs.strictTracking(context)
        prefTrackingProtection = BrowserPrefs.trackingProtection(context)
        if (!started) return
        if (prevLevel != effectiveProtectionLevel()) clearDecisionCache()
        val changes = JSONObject()
        if (prevPrivacy != effectivePrivacyEnabled()) {
            changes.put("privacyEnabled", JSONObject().put("newValue", effectivePrivacyEnabled()))
        }
        if (prevGpc != effectiveGpcEnabled()) {
            changes.put("gpcEnabled", JSONObject().put("newValue", effectiveGpcEnabled()))
        }
        if (changes.length() > 0) broadcast(changes)
    }

    /** Consulta rápida para MalwareScan: ¿el host figura en la lista de badware/phishing descargada? */
    fun isKnownThreatHost(host: String?): Boolean {
        if (!started || host.isNullOrBlank()) return false
        return ShieldPolicy.isThreatHost(host, rules.threatDomains)
    }

    private fun effectivePrivacyEnabled(): Boolean = settings.privacyEnabled || prefFingerprinting
    private fun effectiveGpcEnabled(): Boolean = settings.gpcEnabled && prefGpc
    private fun effectiveProtectionLevel(): String =
        if (prefStrictTracking && settings.protectionLevel == ShieldSettings.LEVEL_BALANCED) ShieldSettings.LEVEL_STRICT
        else settings.protectionLevel

    /** Peticiones de los service workers de un perfil (no pertenecen a ninguna pestaña); una vez por perfil. */
    fun attachProfile(profile: CefriumProfile) {
        if (!attachedProfiles.add(profile.name + "|" + profile.isIncognito)) return
        runCatching { profile.setResourceInterceptor(Interceptor(null)) }
            .onFailure { attachedProfiles.remove(profile.name + "|" + profile.isIncognito) }
    }

    private val attachedProfiles = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun readAsset(path: String): String =
        appContext.assets.open(ASSET_ROOT + path).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun readUserCosmeticRules(): Map<String, List<String>> {
        val raw = store.get("user_cosmetic_rules").optJSONObject("user_cosmetic_rules") ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        raw.keys().forEach { host ->
            val cleanHost = host.trim().lowercase().removePrefix("www.").trimEnd('.')
            if (cleanHost.isEmpty() || out.size >= MAX_USER_ZAP_SITES) return@forEach
            val arr = raw.optJSONArray(host) ?: return@forEach
            val list = ArrayList<String>()
            for (i in 0 until minOf(arr.length(), MAX_USER_ZAPS_PER_SITE)) {
                val sel = arr.optString(i).trim()
                if (isSafeUserSelector(sel)) list.add(sel)
            }
            if (list.isNotEmpty()) out[cleanHost] = list
        }
        return out
    }

    private fun isSafeUserSelector(selector: String): Boolean =
        selector.isNotEmpty() && selector.length <= 250 &&
            '{' !in selector && '}' !in selector && ';' !in selector &&
            '@' !in selector && "</" !in selector && '\n' !in selector && '\r' !in selector

    private fun readSettings() {
        val storedLevel = store.get("protectionLevel").optString("protectionLevel", ShieldSettings.LEVEL_BALANCED)
        settings = ShieldSettings(
            adblockEnabled = store.boolean("adblockEnabled", true),
            privacyEnabled = store.boolean("privacyEnabled", false),
            popupBlockEnabled = store.boolean("popupBlockEnabled", true),
            antiBlockEnabled = store.boolean("antiBlockEnabled", false),
            disabledDomains = ShieldSettings.normalizeDomains(store.stringList("disabled_domains")),
            protectionLevel = ShieldSettings.normalizeLevel(storedLevel),
            annoyancesEnabled = store.boolean("annoyancesEnabled", true),
            cryptominerEnabled = store.boolean("cryptominerEnabled", true),
            gpcEnabled = store.boolean("gpcEnabled", true),
            queryStrippingEnabled = store.boolean("queryStrippingEnabled", true),
            allowedDomains = ShieldSettings.normalizeDomains(store.stringList("allowed_domains")),
            userCosmeticRules = readUserCosmeticRules()
        )
    }

    private fun readAi(aiConfig: Boolean, domains: Boolean) {
        val stored = com.nexo.browser.ai.AiHub.overlayShieldRead(
            appContext, store.get(JSONArray().put("aiConfig").put("aiAdDomains"))
        )
        if (aiConfig) this.aiConfig = runCatching { ShieldAi.Config.from(stored.opt("aiConfig")) }.getOrDefault(ShieldAi.Config.OFF)
        if (domains) {
            learned = runCatching { ShieldAi.Learned.from(stored.opt("aiAdDomains"), System.currentTimeMillis()) }
                .getOrDefault(ShieldAi.Learned.EMPTY)
            clearDecisionCache()
        }
    }

    private fun reloadLists() {
        val data = bundled ?: return
        val texts = FilterLists.LISTS.mapNotNull { list -> runCatching { store.listText(list.id) }.getOrNull()?.let { list.id to it } }.toMap()
        if (texts.isEmpty()) return
        runCatching { rules = FilterLists.build(data, texts) }
        clearDecisionCache()
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
        if (!url.startsWith("https://", ignoreCase = true)) error("Solo HTTPS permitido para listas")
        val request = Request.Builder().url(url).header("Cache-Control", "no-store").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP error ${response.code}")
            if (!response.request.url.isHttps) error("Redirección insegura rechazada")
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
            "ia=${if (aiConfig.enabled) "activa (${ShieldAiHost.statusSummary()})" else "apagada"} · " +
            "reglas red=${rules.networkCount} cosméticas=${rules.cosmeticCount} · bloqueos=${blockedTotal.get()} · " +
            "caché red=${decisionCacheHits.get()} aciertos/${decisionCacheMisses.get()} fallos · " +
            "listas actualizadas $ago · cookies=$cookieMode"
    }

    fun setEnabled(value: Boolean) {
        if (!started || enabled == value) return
        enabled = value
        clearDecisionCache()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
        // Mantiene sincronizados el interruptor rápido de Nexo y el almacén del panel.
        store.set(JSONObject().put("adblockEnabled", value))
    }

    private fun activeTab(): ShieldTab? = (TabManager.activeSession() as? CefriumEngineSession)?.shield

    fun isSiteDisabled(host: String?): Boolean =
        started && settings.isHostDisabled(host)

    fun setSiteDisabled(host: String, disabled: Boolean) {
        if (!started) return
        val clean = host.trim().lowercase().removePrefix("www.").trimEnd('.')
        if (clean.isEmpty()) return
        val current = ShieldSettings.normalizeDomains(store.stringList("disabled_domains")).toMutableSet()
        if (disabled) current.add(clean) else current.remove(clean)
        store.set(JSONObject().put("disabled_domains", JSONArray(current.sorted())))
    }

    fun tabBlockedCount(tab: ShieldTab? = activeTab()): Int =
        tab?.blocked?.get() ?: 0

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

    private fun recordBlocked(
        tab: ShieldTab?,
        category: BlockCategory = BlockCategory.AD,
        host: String? = null,
        type: String = "other"
    ) {
        tab?.blocked?.incrementAndGet()
        tab?.recordCategory(category, host, type)
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

    /** URL limpia (desenvuelve redirecciones de rastreo y quita parámetros UTM/clic) para una navegación principal. */
    fun cleanNavigation(url: String): String {
        if (!started || !enabled || !prefQueryStripping || !settings.queryStrippingEnabled) return url
        val host = UrlParts.host(url) ?: return url
        if (!settings.isEnabledForHost(host)) return url
        val unwrapped = ShieldPolicy.unwrapBounceRedirect(url) ?: url
        return ShieldPolicy.stripTrackingParams(unwrapped) ?: unwrapped
    }

    private fun effectiveSettings(): ShieldSettings {
        val level = effectiveProtectionLevel()
        val privacy = effectivePrivacyEnabled()
        val gpc = effectiveGpcEnabled()
        return if (level == settings.protectionLevel && privacy == settings.privacyEnabled && gpc == settings.gpcEnabled) {
            settings
        } else {
            settings.copy(protectionLevel = level, privacyEnabled = privacy, gpcEnabled = gpc)
        }
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
            val type = ShieldPolicy.typeFromEngine(request.type?.name.orEmpty(), url)
            val top = request.topLevelUrl?.takeIf { it.isNotEmpty() } ?: tab?.topUrl ?: url
            val document = request.documentUrl?.takeIf { it.isNotEmpty() } ?: top
            val reqHost = UrlParts.host(url)
            if (tab != null && !request.isMainFrame &&
                com.nexo.browser.extensions.nxr.DnrBridge.blocks(url, document, top, type, request.method.orEmpty(), tab.isPrivate)) {
                forget(url)
                recordBlocked(tab, BlockCategory.DNR, reqHost, type)
                return true
            }
            if (!started || !enabled) return forget(url)
            val topHost = UrlParts.host(top)
            val docHost = UrlParts.host(document).orEmpty()
            val cacheKey = if (!request.isMainFrame && url.length <= 320) "$type|${topHost.orEmpty()}|$docHost|$url" else null
            val cached = cacheKey?.let { decisionCache.get(it) }
            val currentRules = rules
            val eff = effectiveSettings()
            val decision = if (cached != null) {
                decisionCacheHits.incrementAndGet()
                cached
            } else {
                if (cacheKey != null) decisionCacheMisses.incrementAndGet()
                val computed = runCatching {
                    ShieldPolicy.decide(currentRules.network, eff, popupDomains, url, type, topHost, document)
                        .let { d ->
                            when {
                                d != ShieldDecision.Allow -> d
                                !request.isMainFrame && eff.cryptominerEnabled &&
                                    !eff.isRequestHostAllowed(reqHost) &&
                                    ShieldPolicy.isCryptominerHost(reqHost, currentRules.minerDomains) -> ShieldDecision.Block
                                !request.isMainFrame && blocksLearned(url, type, top) -> ShieldDecision.Block
                                else -> d
                            }
                        }
                }.getOrDefault(ShieldDecision.Allow)
                if (cacheKey != null) decisionCache.put(cacheKey, computed)
                computed
            }
            return when (decision) {
                ShieldDecision.Allow -> forget(url)
                ShieldDecision.Block -> {
                    forget(url)
                    val category = ShieldPolicy.classifyBlocked(
                        reqHost, url, type, popupDomains, trackerDomains, currentRules.minerDomains, currentRules.threatDomains
                    )
                    recordBlocked(tab, category, reqHost, type)
                    true
                }
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
            val url = request.url ?: return null
            val decision = answers[url] ?: return null
            val category = if (decision === ShieldDecision.BlockPage) BlockCategory.POPUP else BlockCategory.TRACKER
            recordBlocked(tab, category, UrlParts.host(url), if (decision === ShieldDecision.BlockPage) "popup" else "script")
            return respond(decision)
        }
    }

    /** Con "solo propias" o "ninguna": cada cookie pasa por la política de Nexo. */
    private class CookieInterceptor(tab: ShieldTab) : Interceptor(tab) {
        override fun onCanSendCookie(url: String?, name: String?, value: String?): Boolean = cookieAllowed(tab, url)
        override fun onCanSaveCookie(url: String?, name: String?, value: String?): Boolean = cookieAllowed(tab, url)
    }

    internal class DecisionLruCache(private val maxEntries: Int = DECISION_CACHE_MAX) {
        private val map = object : LinkedHashMap<String, ShieldDecision>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ShieldDecision>?): Boolean =
                size > maxEntries
        }

        @Synchronized fun get(key: String): ShieldDecision? = map[key]
        @Synchronized fun put(key: String, value: ShieldDecision) { map[key] = value }
        @Synchronized fun clear() { map.clear() }
        @Synchronized fun size(): Int = map.size
    }

    private const val DECISION_CACHE_MAX = 1024
    private val decisionCache = DecisionLruCache(DECISION_CACHE_MAX)
    private val decisionCacheHits = AtomicLong(0L)
    private val decisionCacheMisses = AtomicLong(0L)
    private val inFlightAiPages = ConcurrentHashMap<String, Boolean>()

    private fun clearDecisionCache() {
        decisionCache.clear()
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

    /** Scripts de sustitución ya leídos (son archivos pequeños). */
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

    /**
     * Dominio publicitario aprendido con la IA: se cortan sus peticiones de
     * terceros (nunca medios, OAuth, CAPTCHA, pagos ni la página principal).
     */
    private fun blocksLearned(url: String, type: String, topUrl: String): Boolean {
        val current = learned
        if (current.blocked.isEmpty() || type == "media") return false
        val host = UrlParts.host(url) ?: return false
        if (settings.isRequestHostAllowed(host)) return false
        val topHost = UrlParts.host(topUrl)
        if (!settings.isEnabledForHost(topHost) || ShieldPolicy.siteOfHost(host) == ShieldPolicy.siteOfHost(topHost)) return false
        if (ShieldPolicy.isProtectedSafetyUrl(url)) return false
        return current.blocks(host)
    }

    /** Ventana nueva que pide [opener]: ¿la corta PhantomShield? (cuenta el bloqueo). */
    fun blocksPopup(opener: ShieldTab, url: String): Boolean {
        if (!started || !enabled) return false
        val blocked = runCatching {
            ShieldPolicy.blocksPopup(rules.network, settings, popupDomains, url, opener.topUrl) ||
                (settings.popupBlockEnabled && blocksLearned(url, "popup", opener.topUrl ?: url))
        }.getOrDefault(false)
        if (blocked) recordBlocked(opener, BlockCategory.POPUP, UrlParts.host(url), "popup")
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
            override fun onQuery(queryId: Long, request: String, origin: String, callback: CefriumBrowser.QueryCallback): Boolean =
                onQuery(queryId, request, origin, true, callback)

            override fun onQuery(queryId: Long, request: String, origin: String, isMainFrame: Boolean, callback: CefriumBrowser.QueryCallback): Boolean {
                if (!isMainFrame) return false
                val parsed = channel.parse(request) ?: return false
                val before = channel.currentSeq()
                val reply = parsed.message?.let { onPanelMessage(it, channel, onClose) }
                callback.success(channel.envelope(reply, parsed.since, before))
                return true
            }
        })
        channel.wake = { main.post { if (!browser.isClosed) runCatching { browser.evaluateJavaScript("window.__nexoWake&&window.__nexoWake()") } } }
    }

    internal fun detachPanel() { panelChannel = null }

    /** Script de inicio del anfitrión de IA ([ShieldAiHost]). */
    internal fun aiHostScript(): String = scripts.aiHost

    // «Una API para todo»: la IA lee la conexión maestra de Nexo AI y nunca guarda su clave en disco.
    internal fun hostGet(keys: Any?): JSONObject = com.nexo.browser.ai.AiHub.overlayShieldRead(
        com.nexo.browser.AppContext.getOrNull(), store.get(if (keys == null || keys == JSONObject.NULL) null else keys))
    internal fun hostSet(values: JSONObject) {
        if (values.length() > 0) store.set(com.nexo.browser.ai.AiHub.stripShieldWrite(com.nexo.browser.AppContext.getOrNull(), values))
    }
    internal fun hostRemove(keys: List<String>) { if (keys.isNotEmpty()) store.remove(keys) }
    internal fun keysOf(payload: Any?): Set<String>? = requestedKeys(payload)

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
        val before = tab.channel.currentSeq()
        val reply = parsed.message?.let { onPageMessage(tab, it, origin, isMainFrame) }
        return tab.channel.envelope(reply, parsed.since, before)
    }

    /** Mensajes de las páginas: solo estado de bloqueo y selectores; ni estadísticas ni ajustes. */
    private fun onPageMessage(tab: ShieldTab, message: String, origin: String, isMainFrame: Boolean): String? {
        val call = parse(message) ?: return null
        if (isMainFrame && tab.topHost == null && (origin.startsWith("http://") || origin.startsWith("https://"))) {
            tab.onLocation(origin)
        }
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
        if (call.kind == "nxr_storage" || call.kind == "nxr_message" || call.kind == "nxr_error") {
            if (session == null) return answer(call.id, null, "Sesión no disponible.")
            val reply = com.nexo.browser.extensions.nxr.NxrRuntime.onPageMessage(
                sessionKey = session.sessionUid.toInt(),
                docGen = session.documentGeneration(),
                isPrivate = session.isPrivate,
                origin = origin,
                isMainFrame = isMainFrame,
                kind = call.kind,
                payload = call.payload as? JSONObject
            )
            return answer(call.id, reply.result, reply.error)
        }
        return when (call.kind) {
            "get" -> {
                val all = store.get(JSONObject().apply { PAGE_READABLE.forEach { put(it, defaultSetting(it)) } })
                // Reflejar también las preferencias globales de privacidad/GPC de Ajustes de Nexo.
                all.put("privacyEnabled", effectivePrivacyEnabled())
                all.put("gpcEnabled", effectiveGpcEnabled())
                val requested = requestedKeys(call.payload)
                val result = JSONObject()
                all.keys().forEach { key -> if (requested == null || key in requested) result.put(key, all.get(key)) }
                val defaults = call.payload as? JSONObject
                defaults?.keys()?.forEach { key -> if (!result.has(key)) result.put(key, defaults.get(key)) }
                answer(call.id, result)
            }
            "message" -> {
                val payload = call.payload as? JSONObject ?: return answer(call.id, null)
                if (payload.optString("type") == "AI_CLASSIFY") {
                    // Respuesta inmediata (p. ej. IA apagada) o más tarde por el canal de la pestaña.
                    var immediate: String? = null
                    var sync = true
                    classify(tab, payload, origin, isMainFrame) { result ->
                        val text = answer(call.id, result)
                        if (sync) immediate = text else tab.channel.push(text)
                    }
                    sync = false
                    return immediate
                }
                answer(call.id, pageMessage(tab, payload, origin, isMainFrame))
            }
            "reply" -> {
                // Respuesta de la página a una petición del panel (solo las que Nexo espera de esta pestaña).
                val reply = call.payload as? JSONObject
                if (isMainFrame && reply != null) {
                    val rid = reply.optInt("rid")
                    tab.replyTimeouts.remove(rid)?.let { main.removeCallbacks(it) }
                    tab.replies.remove(rid)?.invoke(reply.opt("result").takeIf { it != JSONObject.NULL })
                }
                answer(call.id, null)
            }
            else -> answer(call.id, null, "No disponible")
        }
    }

    /** ¿Puede esta pestaña usar IA en [host]? (activa, aceptada, sitio autorizado, no privada, escudo activo). */
    private fun aiAllowed(tab: ShieldTab, host: String?): Boolean =
        !tab.isPrivate && effectiveEnabled(host) && aiConfig.allows(host)

    /** `AI_CLASSIFY` del detector: solo la página principal de un sitio autorizado, con presupuesto y cooldown. */
    private fun classify(tab: ShieldTab, message: JSONObject, origin: String, isMainFrame: Boolean, done: (Any?) -> Unit) {
        val host = tab.topHost
        if (!isMainFrame || host == null || UrlParts.host(origin) != host || !effectiveEnabled(host)) {
            done(JSONObject().put("ok", false).put("error", "Página no autorizada para IA."))
            return
        }
        if (!aiAllowed(tab, host)) {
            done(JSONObject().put("ok", false).put("code", "off").put("error", "IA desactivada o sitio sin autorización."))
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (tab.lastAiCallAt > 0L && now - tab.lastAiCallAt < AI_PAGE_COOLDOWN_MS) {
            done(JSONObject().put("ok", false).put("code", "cooldown").put("error", "En espera por el límite de solicitudes."))
            return
        }
        if (tab.aiPageCalls.get() >= AI_PAGE_MAX_CALLS) {
            done(JSONObject().put("ok", false).put("code", "budget").put("error", "Límite de análisis alcanzado en esta página."))
            return
        }
        val page = tab.page
        val flightKey = "${tab.id}:$page"
        if (inFlightAiPages.putIfAbsent(flightKey, true) != null) {
            done(JSONObject().put("ok", false).put("code", "busy").put("error", "Análisis de IA ya en curso para esta página."))
            return
        }
        tab.lastAiCallAt = now
        tab.aiPageCalls.incrementAndGet()
        val candidates = enrichCandidates(message.optJSONArray("candidates"), tab.topUrl ?: origin)
        val sender = JSONObject().put("tabId", tab.id).put("page", page).put("host", host).put("incognito", tab.isPrivate)
            .put("known", JSONArray(knownAdHosts(candidates)))
        ShieldAiHost.request(JSONObject().put("type", "AI_CLASSIFY").put("candidates", candidates), sender) { result ->
            inFlightAiPages.remove(flightKey)
            val json = result as? JSONObject
            if (tab.page != page) {
                done(JSONObject().put("ok", false).put("code", "changed").put("error", "La página cambió."))
                return@request
            }
            if (!effectiveEnabled(host)) {
                done(JSONObject().put("ok", false).put("error", "Protección desactivada."))
                return@request
            }
            tab.ai.onClassified(json, System.currentTimeMillis())
            json?.remove("learned")
            notifyAiActivity(tab)
            done(json ?: JSONObject().put("ok", false).put("code", "format").put("error", "Respuesta de IA no válida."))
        }
    }

    /**
     * Como `enrichCandidates` de la extensión: un enlace o marco hacia una red
     * que las listas bloquean marca el candidato como anuncio. Las
     * direcciones siguen hasta el anfitrión (aprende dominios con ellas),
     * que las quita antes de llamar al proveedor.
     */
    private fun enrichCandidates(candidates: JSONArray?, documentUrl: String): JSONArray {
        val out = JSONArray()
        if (candidates == null) return out
        val engine = rules.network
        for (i in 0 until minOf(candidates.length(), 12)) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val copy = JSONObject(candidate.toString())
            val signals = copy.optJSONArray("signals")
            val urls = copy.optJSONArray("localUrls")
            if (signals != null && urls != null && (0 until signals.length()).none { signals.opt(it) == "ad-network" }) {
                val adNetwork = (0 until minOf(urls.length(), 4)).any { index ->
                    val url = urls.opt(index) as? String ?: return@any false
                    url.length <= 512 && (url.startsWith("http://") || url.startsWith("https://")) &&
                        (runCatching { engine.shouldBlock(url, "sub_frame", documentUrl, false) }.getOrDefault(false) ||
                            blocksLearned(url, "sub_frame", documentUrl))
                }
                if (adNetwork) signals.put("ad-network")
            }
            out.put(copy)
        }
        return out
    }

    /** Hosts de marcos y enlaces de los candidatos que las listas ya bloquean (no se aprenden). */
    private fun knownAdHosts(candidates: JSONArray): List<String> {
        val hosts = LinkedHashSet<String>()
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            (candidate.opt("localFrame") as? String)?.let { url -> UrlParts.host(url)?.let(hosts::add) }
            candidate.optJSONArray("localUrls")?.let { urls ->
                for (j in 0 until minOf(urls.length(), 4)) (urls.opt(j) as? String)?.let { url -> UrlParts.host(url)?.let(hosts::add) }
            }
        }
        val engine = rules.network
        return hosts.filter { runCatching { engine.isDomainBlocked(it) }.getOrDefault(false) }.take(64)
    }

    /** El panel abierto vuelve a leer la actividad de IA de la pestaña. */
    private fun notifyAiActivity(tab: ShieldTab) {
        panelChannel?.push(JSONObject().put("event", "message")
            .put("message", JSONObject().put("type", "AI_ACTIVITY_UPDATED").put("tabId", tab.id)).toString())
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

    private fun userZapsFor(host: String): List<String> {
        val clean = host.trim().lowercase().removePrefix("www.").trimEnd('.')
        if (clean.isEmpty()) return emptyList()
        return settings.userCosmeticRules[clean].orEmpty()
    }

    private fun saveUserZap(host: String, selector: String): Boolean {
        val clean = host.trim().lowercase().removePrefix("www.").trimEnd('.')
        val sel = selector.trim()
        if (clean.isEmpty() || !isSafeUserSelector(sel)) return false
        val raw = store.get("user_cosmetic_rules").optJSONObject("user_cosmetic_rules")?.let { JSONObject(it.toString()) } ?: JSONObject()
        val arr = raw.optJSONArray(clean) ?: JSONArray()
        for (i in 0 until arr.length()) if (arr.optString(i) == sel) return true
        if (arr.length() >= MAX_USER_ZAPS_PER_SITE) return false
        arr.put(sel)
        raw.put(clean, arr)
        store.set(JSONObject().put("user_cosmetic_rules", raw))
        return true
    }

    private fun clearUserZaps(host: String) {
        val clean = host.trim().lowercase().removePrefix("www.").trimEnd('.')
        if (clean.isEmpty()) return
        val raw = store.get("user_cosmetic_rules").optJSONObject("user_cosmetic_rules")?.let { JSONObject(it.toString()) } ?: return
        if (!raw.has(clean)) return
        raw.remove(clean)
        store.set(JSONObject().put("user_cosmetic_rules", raw))
    }

    private fun pageMessage(tab: ShieldTab, message: JSONObject, origin: String, isMainFrame: Boolean): Any? {
        val topHost = tab.topHost.orEmpty()
        val frameHost = UrlParts.host(origin)?.takeIf { it.isNotEmpty() } ?: topHost
        return when (message.optString("type")) {
            "GET_SITE_STATE" -> {
                val config = aiConfig
                JSONObject().put("ok", true).put("enabled", effectiveEnabled(topHost))
                    .put("privacyEnabled", effectivePrivacyEnabled())
                    .put("gpcEnabled", effectiveGpcEnabled())
                    .put("antiBlockEnabled", settings.antiBlockEnabled)
                    .put("annoyancesEnabled", settings.annoyancesEnabled)
                    .put("aiEnabled", aiAllowed(tab, topHost.takeIf { it.isNotEmpty() }))
                    .put("aiDetail", config.detail).put("aiSensitivity", config.sensitivity).put("host", topHost)
            }
            "GET_COSMETIC" -> {
                if (!effectiveEnabled(topHost)) return disabledCosmetic()
                val result = rules.cosmetic.forHost(frameHost)
                val combined = ArrayList<String>(result.selectors.size + 32)
                combined.add(HIDE_BASE_SELECTOR)
                combined.addAll(result.selectors)
                if (settings.annoyancesEnabled && result.generic) {
                    combined.addAll(ShieldPolicy.ANNOYANCE_COSMETIC_SELECTORS)
                }
                if (isMainFrame) {
                    combined.addAll(userZapsFor(topHost))
                }
                JSONObject().put("ok", true).put("enabled", true).put("generic", result.generic).put("injected", false)
                    .put("selectors", JSONArray(combined))
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
                    recordBlocked(tab, BlockCategory.POPUP, topHost, "popup")
                }
                JSONObject().put("ok", true)
            }
            "ZAP_ELEMENT" -> {
                if (!isMainFrame || topHost.isEmpty() || UrlParts.host(origin) != tab.topHost) {
                    return JSONObject().put("ok", false)
                }
                val selector = message.optString("selector").trim()
                val saved = saveUserZap(topHost, selector)
                if (saved) recordBlocked(tab, BlockCategory.AD, topHost, "cosmetic")
                JSONObject().put("ok", saved)
            }
            "AI_PAGE" -> {
                // El detector informa de lo que realmente ocultó (o de pasadas sin candidatos).
                if (!isMainFrame || tab.isPrivate || UrlParts.host(origin) != tab.topHost) return JSONObject().put("ok", false)
                val delta = tab.ai.onPage(message, System.currentTimeMillis())
                if (delta != 0) tab.blocked.updateAndGet { (it + delta).coerceAtLeast(0) }
                notifyAiActivity(tab)
                JSONObject().put("ok", true)
            }
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
                if (!result.has("protectionLevel") && (keys == null || keys == JSONObject.NULL || requestedKeys(keys)?.contains("protectionLevel") == true)) {
                    result.put("protectionLevel", effectiveProtectionLevel())
                }
                answer(call.id, com.nexo.browser.ai.AiHub.overlayShieldRead(com.nexo.browser.AppContext.getOrNull(), result))
            }
            "set" -> {
                val values = call.payload as? JSONObject ?: return answer(call.id, null, "Valores no válidos")
                val allowed = JSONObject()
                values.keys().forEach { key -> if (key in PANEL_WRITABLE) allowed.put(key, values.get(key)) }
                store.set(com.nexo.browser.ai.AiHub.stripShieldWrite(com.nexo.browser.AppContext.getOrNull(), allowed))
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
            "tabs.sendMessage" -> {
                val request = call.payload as? JSONObject ?: return answer(call.id, null, "Petición no válida")
                val msg = request.optJSONObject("message")
                val tab = tabs.mapNotNull { it.get() }.firstOrNull { it.id == request.optInt("tabId", -1) }
                val type = msg?.optString("type")
                if (tab == null || (type != "AI_SCAN" && type != "START_ZAPPER")) return answer(call.id, null, "No disponible")
                if (type == "START_ZAPPER") {
                    tab.channel.push(JSONObject().put("event", "message").put("message", JSONObject().put("type", "START_ZAPPER")).toString())
                    main.post(onClose)
                    return answer(call.id, JSONObject().put("ok", true))
                }
                askTab(tab, msg) { result -> channel.push(answer(call.id, result)) }
                null
            }
            "openOptions" -> if (com.nexo.browser.PhantomShieldPanel.openOptions()) answer(call.id, null)
                else answer(call.id, null, "No se pudieron abrir los ajustes de IA.")
            "message" -> {
                val payload = call.payload as? JSONObject ?: return answer(call.id, null)
                var immediate: String? = null
                var sync = true
                panelMessage(payload, onClose) { result ->
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

    private fun panelMessage(message: JSONObject, onClose: () -> Unit, done: (Any?) -> Unit) {
        when (message.optString("type")) {
            "GET_STATS" -> {
                val tabId = message.optInt("tabId", -1)
                val tab = tabs.mapNotNull { it.get() }.firstOrNull { it.id == tabId }
                val host = message.optString("host").lowercase()
                val eff = effectiveSettings()
                done(JSONObject().put("ok", true)
                    .put("blockedCount", blockedTotal.get())
                    .put("tabBlocked", tab?.blocked?.get() ?: 0)
                    .put("categories", tab?.categoriesJson() ?: JSONObject())
                    .put("tabLog", tab?.snapshotLog() ?: JSONArray())
                    .put("userZaps", userZapsFor(host).size)
                    .put("allowedDomains", JSONArray(settings.allowedDomains))
                    .put("tabAi", tab?.ai?.takeIf { it.active }?.toJson() ?: JSONObject.NULL)
                    .put("siteDisabled", settings.isHostDisabled(host))
                    .put("rules", JSONObject().put("network", rules.networkCount).put("cosmetic", rules.cosmeticCount)
                        .put("threats", rules.threatDomains.size).put("miners", ShieldPolicy.CRYPTOMINER_HOSTS.size + rules.minerDomains.size))
                    .put("learnedDomains", JSONObject().put("blocked", learned.blocked.size).put("watching", learned.watching))
                    .put("settings", JSONObject()
                        .put("adblockEnabled", settings.adblockEnabled && enabled)
                        .put("privacyEnabled", eff.privacyEnabled)
                        .put("popupBlockEnabled", settings.popupBlockEnabled)
                        .put("antiBlockEnabled", settings.antiBlockEnabled)
                        .put("protectionLevel", eff.protectionLevel)
                        .put("annoyancesEnabled", settings.annoyancesEnabled)
                        .put("cryptominerEnabled", settings.cryptominerEnabled)
                        .put("gpcEnabled", eff.gpcEnabled)
                        .put("queryStrippingEnabled", settings.queryStrippingEnabled && prefQueryStripping)))
            }
            "SET_LEVEL" -> {
                val level = ShieldSettings.normalizeLevel(message.optString("level"))
                val patch = JSONObject()
                    .put("protectionLevel", level)
                    .put("adblockEnabled", true)
                    .put("popupBlockEnabled", true)
                    .put("cryptominerEnabled", true)
                    .put("annoyancesEnabled", true)
                    .put("gpcEnabled", true)
                when (level) {
                    ShieldSettings.LEVEL_BALANCED -> {
                        patch.put("privacyEnabled", false).put("antiBlockEnabled", false)
                    }
                    ShieldSettings.LEVEL_STRICT, ShieldSettings.LEVEL_AGGRESSIVE -> {
                        patch.put("privacyEnabled", true).put("antiBlockEnabled", true)
                    }
                }
                store.set(patch)
                done(JSONObject().put("ok", true).put("level", level))
            }
            "START_ZAPPER" -> {
                val tabId = message.optInt("tabId", -1)
                val tab = tabs.mapNotNull { it.get() }.firstOrNull { it.id == tabId } ?: activeTab()
                if (tab == null) {
                    done(JSONObject().put("ok", false).put("error", "No hay una página web activa."))
                    return
                }
                tab.channel.push(JSONObject().put("event", "message").put("message", JSONObject().put("type", "START_ZAPPER")).toString())
                main.post(onClose)
                done(JSONObject().put("ok", true))
            }
            "CLEAR_SITE_ZAPS" -> {
                val host = message.optString("host")
                clearUserZaps(host)
                done(JSONObject().put("ok", true))
            }
            "ALLOW_DOMAIN" -> {
                val domain = message.optString("domain").trim().lowercase().removePrefix("www.").trimEnd('.')
                if (domain.isNotEmpty() && NetworkEngine.isValidHostname(domain)) {
                    val next = ShieldSettings.normalizeDomains(store.stringList("allowed_domains")).toMutableSet()
                    next.add(domain)
                    store.set(JSONObject().put("allowed_domains", JSONArray(next.sorted())))
                    done(JSONObject().put("ok", true))
                } else {
                    done(JSONObject().put("ok", false).put("error", "Dominio no válido."))
                }
            }
            "UNALLOW_DOMAIN" -> {
                val domain = message.optString("domain").trim().lowercase().removePrefix("www.").trimEnd('.')
                val next = ShieldSettings.normalizeDomains(store.stringList("allowed_domains")).toMutableSet()
                next.remove(domain)
                store.set(JSONObject().put("allowed_domains", JSONArray(next.sorted())))
                done(JSONObject().put("ok", true))
            }
            "RESET_STATS" -> {
                blockedTotal.set(0)
                tabs.mapNotNull { it.get() }.forEach { it.resetCounters(); it.ai = ShieldAi.TabState() }
                store.set(JSONObject().put("blockedCount", 0))
                done(JSONObject().put("ok", true).put("blockedCount", 0))
            }
            "FORCE_UPDATE" -> io.execute {
                val result = runCatching { updateAllLists() }.getOrElse {
                    JSONObject().put("ok", false).put("error", it.message ?: "No se pudieron actualizar las listas")
                }
                main.post { done(result) }
            }
            // Ajustes de IA, memoria y dominios aprendidos: el panel es una página propia (origen exacto).
            "AI_TEST", "AI_MEMORY_LIST", "AI_MEMORY_FORGET", "AI_MEMORY_CLEAR", "AI_MEMORY_EXCLUDE",
            "AI_DOMAINS_LIST", "AI_DOMAINS_FORGET", "AI_DOMAINS_ALLOW", "AI_DOMAINS_UNALLOW", "AI_DOMAINS_CLEAR" ->
                ShieldAiHost.request(message, JSONObject().put("panel", true), done)
            else -> done(null)
        }
    }

    private fun onStorageChanged(changes: JSONObject) {
        if (changes.has("adblockEnabled") || changes.has("privacyEnabled") || changes.has("popupBlockEnabled") ||
            changes.has("antiBlockEnabled") || changes.has("disabled_domains") || changes.has("protectionLevel") ||
            changes.has("annoyancesEnabled") || changes.has("cryptominerEnabled") || changes.has("gpcEnabled") ||
            changes.has("queryStrippingEnabled") || changes.has("allowed_domains") || changes.has("user_cosmetic_rules")) {
            readSettings()
            if (changes.has("adblockEnabled") && enabled != settings.adblockEnabled) {
                enabled = settings.adblockEnabled
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            }
            clearDecisionCache()
        }
        if (changes.has("aiConfig") || changes.has("aiAdDomains")) readAi(changes.has("aiConfig"), changes.has("aiAdDomains"))
        broadcast(changes)
    }

    /**
     * `storage.onChanged` hacia las páginas (solo ajustes legibles; de la IA,
     * solo que cambió), el panel y el anfitrión de IA (todo).
     */
    private fun broadcast(changes: JSONObject) {
        val pageChanges = JSONObject()
        changes.keys().forEach { key ->
            when (key) {
                in PAGE_READABLE -> pageChanges.put(key, changes.get(key))
                // Sin valores: la clave de la API y los dominios nunca llegan a las páginas.
                "disabled_domains", "aiConfig", "user_cosmetic_rules" -> pageChanges.put(key, JSONObject())
            }
        }
        val pageText = JSONObject().put("event", "storage").put("changes", pageChanges).toString()
        val allText = JSONObject().put("event", "storage").put("changes", changes).toString()
        if (pageChanges.length() > 0) tabs.mapNotNull { it.get() }.forEach { tab -> tab.channel.push(pageText) }
        // Memoria de IA olvidada: solo las pestañas de ese sitio (o todas, si se borró entera) vuelven a analizar.
        (changes.optJSONObject("aiMemoryRevision")?.opt("newValue") as? JSONObject)?.let { revision ->
            val host = revision.optString("host").takeIf { it.isNotEmpty() && revision.opt("host") != JSONObject.NULL }
            val text = JSONObject().put("event", "storage").put("changes", JSONObject().put("aiMemoryRevision",
                JSONObject().put("newValue", JSONObject().put("nonce", revision.opt("nonce")).put("host", host ?: JSONObject.NULL)))).toString()
            tabs.mapNotNull { it.get() }.filter { host == null || it.topHost == host }.forEach { it.channel.push(text) }
        }
        panelChannel?.push(allText)
        main.post { ShieldAiHost.storageChanged(allText) }
    }

    /** Pregunta a la página principal de [tab] (p. ej. «Analizar con IA») y espera su respuesta. */
    private fun askTab(tab: ShieldTab, message: JSONObject, done: (Any?) -> Unit) {
        var rid = secureRandom.nextInt(0x7fffffff)
        if (rid <= 0 || tab.replies.containsKey(rid)) rid = (nextReply.incrementAndGet() and 0x7fffffff).coerceAtLeast(1)
        tab.replies[rid] = done
        tab.channel.push(JSONObject().put("event", "message").put("rid", rid).put("message", message).toString())
        val timeout = Runnable {
            tab.replyTimeouts.remove(rid)
            tab.replies.remove(rid)?.invoke(JSONObject().put("ok", false).put("error", "La página no respondió."))
        }
        tab.replyTimeouts[rid] = timeout
        main.postDelayed(timeout, AI_SCAN_TIMEOUT_MS)
    }

    private val nextReply = AtomicInteger(0)

    private class Scripts(private val context: Context) {
        private fun asset(path: String) = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        private val shim = asset("nexo/phantom-shim.js")
        private val port = asset("nexo/cefrium-port.js")
        private val darkEngine = asset("nexo/dark-engine.js")
        private val media = asset("nexo/media-reporter.js")
        private val bridgeShim = asset("nexo/bridge-shim.js")
        private val bridge = asset("nexo/bridge/page-bridge.js")
        private val pipCss = asset("nexo/bridge/pip.css")
        private val hostShim = asset("nexo/ai-host-shim.js")
        private val manifest = JSONObject(asset(ASSET_ROOT + "manifest.json")).toString()
        private fun ext(path: String) = asset(ASSET_ROOT + path)

        val pageHooks: String = "(function(){try{\n" + ext("content/page-hooks.js") + "\n}catch(_){}})();"

        /** Un solo script: `browser` existe únicamente en esta clausura. */
        val content: String = buildString {
            append("(function(){'use strict';\n")
            append("if (!/^https?:$/.test(location.protocol)) return;\n")
            // Nexo Dark Engine temprano: corre en document_start antes de que parsee el CSS de la página.
            append("try {\n").append(darkEngine).append("\n} catch (_) {}\n")
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
            // Detector de IA (solo la página principal; decide Nexo si el sitio puede usarla), tras cargar el documento.
            append("if (window === window.top) {\n")
            append("try {\n").append(ext("data/functional-safety.js")).append("\n} catch (_) {}\n")
            append("const nexoAiDetector = function() { try {\n").append(ext("content/ai-detector.js")).append("\n} catch (_) {} };\n")
            append("if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', nexoAiDetector, { once: true });\n")
            append("else nexoAiDetector();\n}\n")
            append("if (window === window.top && /(^|\\.)(facebook|instagram)\\.com$/.test(location.hostname)) {\n")
            append("try {\n").append(ext("content/heuristic-vision.js")).append("\n} catch (_) {}\n}\n")
            append("})();")
        }

        val panel: String = buildString {
            append(port).append('\n')
            append("nexoCefriumPort(true);\n")
            append(shim).append('\n')
            append("globalThis.browser = nexoPhantomShim(").append(JSONObject.quote(BASE_URL)).append(", ").append(manifest).append(", true);\n")
        }

        /** Anfitrión de IA: su `browser.*` propio y `fetch` por Nexo (solo en `host/ai-host.html`). */
        val aiHost: String = buildString {
            append("if (location.pathname === '/host/ai-host.html') {\n")
            append(port).append('\n')
            append("nexoCefriumPort(true, { alwaysPoll: true, pollMs: 1000 });\n")
            append(hostShim).append('\n')
            append("globalThis.browser = nexoAiHostShim(").append(JSONObject.quote(BASE_URL)).append(", ").append(manifest).append(");\n}\n")
        }
    }

    private const val BLOCKED_PAGE = """<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Ventana bloqueada</title>
<style>body{font:16px/1.5 system-ui,sans-serif;margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center;background:#111418;color:#e6e8ec}
main{max-width:28rem;padding:24px}h1{font-size:1.25rem;margin:0 0 8px}p{margin:0;color:#aab0ba}
@media (prefers-color-scheme: light){body{background:#f6f7f9;color:#14171c}p{color:#55606e}}</style></head>
<body><main><h1>PhantomShield bloqueó esta página</h1><p>Pertenece a una red de ventanas emergentes publicitarias. Puedes cerrar esta pestaña.</p></main></body></html>"""
}
