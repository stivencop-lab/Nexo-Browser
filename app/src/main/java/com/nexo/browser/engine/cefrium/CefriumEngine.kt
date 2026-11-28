package com.nexo.browser.engine.cefrium

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import com.cefrium.Cefrium
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumProfile
import com.cefrium.CefriumSecureDnsMode
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.TabManager
import com.nexo.browser.ThemeManager
import com.nexo.browser.engine.BrowserEngine
import com.nexo.browser.engine.DownloadFetcher
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineSessionState
import com.nexo.browser.engine.SessionParams
import com.nexo.browser.shield.PhantomShield
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Motor de Nexo: Chromium embebido con Cefrium (no Android System WebView).
 * Cada pestaña viva es un `CefriumBrowser` con su perfil: el normal, el
 * privado (fuera de registro, solo en memoria) o uno por identidad Burner.
 *
 * Cefrium dibuja cada navegador en una superficie ligada a una Activity, así
 * que las pestañas crean su navegador cuando hay una Activity de Nexo (ver
 * [CefriumEngineSession.ensureBrowser]) y lo vuelven a crear, con su historial,
 * si esa Activity se destruye.
 */
object CefriumEngine : BrowserEngine {
    /** Etiqueta de registro del motor de Nexo. */
    internal const val TAG = "NexoEngine"

    /** Navegador entregado por `onCreateWindow` que la próxima pestaña debe adoptar. */
    internal var adopting: Pair<CefriumBrowser, String>? = null

    @Volatile private var started = false
    private var activityRef = WeakReference<Activity>(null)
    private lateinit var appContext: Context

    /** Navegador → pestaña, para los avisos que Cefrium da al que abrió una ventana. */
    private val owners = WeakHashMap<CefriumBrowser, WeakReference<CefriumEngineSession>>()

    override val displayName: String
        get() = "Chromium " + runCatching { Cefrium.getChromiumVersion() }.getOrDefault("") + " (Cefrium)"

    override fun initialize(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        // Diagnóstico: `adb logcat -s NexoEngine CefriumBrowser chromium AndroidRuntime`.
        val ready = runCatching { Cefrium.initialize(appContext) }
            .onFailure {
                EngineDiagnostics.initError = "${it.javaClass.simpleName}: ${it.message}"
                EngineDiagnostics.log("Cefrium.initialize lanzó una excepción", it)
            }
            .getOrDefault(false)
        EngineDiagnostics.initialized = ready
        EngineDiagnostics.log("Cefrium ${runCatching { Cefrium.getVersion() }.getOrDefault("?")} " +
            "(Chromium ${runCatching { Cefrium.getChromiumVersion() }.getOrDefault("?")}) iniciado=$ready")
        if (!ready) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                TabManager.notifyMessage("Chromium no pudo iniciarse en este teléfono; las páginas no cargarán. Detalles en el registro (NexoEngine).")
            }, 1500)
        }
        // Antes de abrir ningún perfil: los Burner de la sesión anterior.
        CefriumProfiles.purgeStale()
        // Actualización desde una versión anterior de Nexo: sus cookies antes de abrir la primera pestaña.
        if (ready) UpgradeImport.runOnce(appContext) {
            TabManager.liveSessions().filterIsInstance<CefriumEngineSession>().forEach { it.ensureBrowser() }
        }
        PhantomShield.initialize(appContext)
        applySettings(appContext)
    }

    /** Activity que muestra las pestañas; la vista de páginas la registra. */
    internal fun activity(): Activity? = activityRef.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    internal fun register(activity: Activity) {
        if (activityRef.get() === activity) return
        activityRef = WeakReference(activity)
        EngineDiagnostics.log("Activity registrada: ${activity.javaClass.simpleName}")
        // Pestañas creadas sin Activity (o con una ya destruida): ahora tienen dónde dibujarse.
        TabManager.liveSessions().forEach { (it as? CefriumEngineSession)?.ensureBrowser() }
    }

    internal fun bind(browser: CefriumBrowser, session: CefriumEngineSession) {
        owners[browser] = WeakReference(session)
    }

    internal fun sessionOf(browser: CefriumBrowser?): CefriumEngineSession? =
        browser?.let { owners[it]?.get() }

    override fun createView(context: Context): View {
        (context as? Activity)?.let { register(it) }
        return CefriumPageView(context)
    }

    /** Perfiles propios de Cefrium: privado y Burner siempre aislados. */
    override fun supportsIsolatedTabs(context: Context): Boolean = true

    override fun onPrivateSessionEnded(context: Context) = CefriumProfiles.endPrivateSession()

    override val downloadFetcher: DownloadFetcher get() = CefriumDownloads.fetcher

    override fun createSession(context: Context, params: SessionParams): EngineSession {
        if (!started) initialize(context)
        val adopted = adopting
        adopting = null
        return CefriumEngineSession(context, params, adopted?.first, adopted?.second)
    }

    override fun open(context: Context, session: EngineSession) {
        (session as CefriumEngineSession).open()
    }

    override fun parseState(raw: String): EngineSessionState? = CefriumState.parse(raw)

    /** Cefrium no expone conexión anticipada todavía. */
    override fun speculativeConnect(context: Context, url: String) = Unit

    override fun clearAllData(context: Context, onDone: (Boolean, String?) -> Unit) {
        if (!started) {
            onDone(true, null)
            return
        }
        val profiles = runCatching {
            val all = listOf(CefriumProfile.getDefault(), CefriumProfile.incognito())
            all + TabManager.allTabs().mapNotNull { it.burnerId }.distinct()
                .map { CefriumProfile.get(CefriumProfiles.burnerName(it)) }
        }
        val cacheCleared = TabManager.liveSessions().filterIsInstance<CefriumEngineSession>()
            .map { it.clearCache() }.all { it }
        TabManager.closeAllSessionsKeepingState()
        val targets = profiles.getOrElse {
            onDone(false, "No se pudieron consultar los perfiles del navegador")
            return
        }
        var pending = targets.size
        var success = cacheCleared
        targets.forEach { profile ->
            CefriumProfiles.wipe(profile) { cleared ->
                success = success && cleared
                if (--pending == 0) onDone(success, if (success) null else "No se pudieron borrar todos los datos del motor")
            }
        }
    }

    override fun clearContextData(context: Context, contextId: String) = CefriumProfiles.wipeContext(contextId)

    override fun applySettings(context: Context) {
        if (!started) return
        PhantomShield.syncBrowserPrefs(context)
        applyWebRtcPolicy(context)
        // Depuración remota solo si el usuario la activó en Ajustes.
        runCatching { Cefrium.setRemoteDebuggingEnabled(BrowserPrefs.remoteDebugging(context)) }
        applySecureDns(context)
        TabManager.liveSessions().forEach { (it as? CefriumEngineSession)?.applySettings() }
    }

    /** Limita fugas de IP local por WebRTC cuando la protección estricta o anti-huellas está activa. */
    private fun applyWebRtcPolicy(context: Context) {
        val policy = when {
            BrowserPrefs.strictTracking(context) -> com.cefrium.CefriumWebRtcIpPolicy.DISABLE_NON_PROXIED_UDP
            BrowserPrefs.fingerprinting(context) -> com.cefrium.CefriumWebRtcIpPolicy.PUBLIC_INTERFACE_ONLY
            else -> com.cefrium.CefriumWebRtcIpPolicy.DEFAULT
        }
        runCatching { CefriumProfile.getDefault().setWebRtcIpHandlingPolicy(policy) }
        runCatching { CefriumProfile.incognito().setWebRtcIpHandlingPolicy(policy) }
        CefriumProfiles.syncDefaultWebRtcPolicy(policy)
    }

    /** DNS sobre HTTPS de Chromium: proveedor y modo estricto. */
    private fun applySecureDns(context: Context) {
        val template = when (BrowserPrefs.dnsProvider(context)) {
            BrowserPrefs.DNS_CLOUDFLARE -> "https://chrome.cloudflare-dns.com/dns-query"
            BrowserPrefs.DNS_GOOGLE -> "https://dns.google/dns-query{?dns}"
            BrowserPrefs.DNS_QUAD9 -> "https://dns.quad9.net/dns-query"
            BrowserPrefs.DNS_CUSTOM -> BrowserPrefs.dnsCustom(context).trim().takeIf { it.startsWith("https://") }
            else -> null
        }
        val mode = when {
            template == null -> CefriumSecureDnsMode.OFF
            BrowserPrefs.dnsStrict(context) -> CefriumSecureDnsMode.SECURE
            else -> CefriumSecureDnsMode.AUTOMATIC
        }
        val ok = runCatching { Cefrium.setSecureDns(mode, template) }.getOrDefault(false)
        if (!ok && template != null) {
            runCatching { Cefrium.setSecureDns(CefriumSecureDnsMode.OFF, null) }
            TabManager.notifyMessage("La dirección de DNS sobre HTTPS no es válida: se usa el DNS del sistema.")
        }
    }

    /** MainActivity gestiona la rotación: Cefrium ajusta su superficie solo. */
    override fun onConfigurationChanged(context: Context, newConfig: Configuration) = Unit

    override fun isStarted(): Boolean = started

    /**
     * Cefrium mantiene abiertas sus bases de datos mientras viva un navegador:
     * se cierran todos antes de transferir el perfil.
     */
    override fun shutdownForTransfer(done: () -> Unit) {
        SpareRendererPool.clear()
        TabManager.liveSessions().forEach { (it as? CefriumEngineSession)?.releaseBrowser() }
        done()
    }

    /** MainActivity no es una ComponentActivity: reenvía los resultados a los navegadores. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        TabManager.liveSessions().any { (it as? CefriumEngineSession)?.onActivityResult(requestCode, resultCode, data) == true }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean =
        TabManager.liveSessions().any {
            (it as? CefriumEngineSession)?.onRequestPermissionsResult(requestCode, permissions, grantResults) == true
        }
}
