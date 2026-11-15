package com.nexo.browser.engine.chromium

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.WebViewCompat
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.TabManager
import com.nexo.browser.engine.BrowserEngine
import com.nexo.browser.engine.DownloadFetcher
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineSessionState
import com.nexo.browser.engine.SessionParams

/**
 * Motor Chromium: Android System WebView con `androidx.webkit`. Cada pestaña
 * viva es una WebView; las privadas y Burner usan perfiles aislados.
 */
@SuppressLint("RequiresFeature")
object ChromiumEngine : BrowserEngine {
    /** WebView entregada por `onCreateWindow` que la próxima pestaña debe adoptar. */
    internal var adopting: Pair<NexoWebView, String>? = null

    @Volatile private var started = false

    override val displayName: String
        get() = "Chromium WebView " + (runCatching {
            WebViewCompat.getCurrentWebViewPackage(com.nexo.browser.AppContext.get())?.versionName
        }.getOrNull() ?: "")

    override fun initialize(context: Context) {
        started = true
        // Antes de cargar ningún perfil: los privados y Burner de la sesión anterior.
        runCatching { ChromiumProfiles.purgeStale() }
        // Actualización desde Nexo con Gecko: sus cookies antes de abrir la primera pestaña.
        GeckoProfileImport.runOnce(context)
        applySettings(context)
    }

    override fun createView(context: Context): View = ChromiumPageView(context)

    override fun supportsIsolatedTabs(context: Context): Boolean = ChromiumProfiles.supported()

    override fun onPrivateSessionEnded(context: Context) = ChromiumProfiles.endPrivateSession()

    override val downloadFetcher: DownloadFetcher get() = ChromiumDownloads.fetcher

    override fun createSession(context: Context, params: SessionParams): EngineSession {
        started = true
        val adopted = adopting
        adopting = null
        return ChromiumEngineSession(context, params, adopted?.first, adopted?.second)
    }

    override fun open(context: Context, session: EngineSession) {
        (session as ChromiumEngineSession).open()
    }

    override fun parseState(raw: String): EngineSessionState? = ChromiumState.parse(raw)

    /**
     * Sin conexión anticipada: en `androidx.webkit` 1.18.0 `Profile.preconnect`
     * sigue siendo experimental y puede cambiar.
     */
    override fun speculativeConnect(context: Context, url: String) = Unit

    override fun clearAllData(context: Context, onDone: (Boolean, String?) -> Unit) {
        runCatching {
            WebStorage.getInstance().deleteAllData()
            WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
            android.webkit.GeolocationPermissions.getInstance().clearAll()
            // La caché HTTP es del perfil y solo se borra desde una WebView.
            WebView(context.applicationContext).apply { clearCache(true); destroy() }
        }
        CookieManager.getInstance().removeAllCookies { _ ->
            CookieManager.getInstance().flush()
            onDone(true, null)
        }
    }

    override fun clearContextData(context: Context, contextId: String) = ChromiumProfiles.wipeContext(contextId)

    override fun applySettings(context: Context) {
        // Depuración remota solo si el usuario la activó en Ajustes.
        WebView.setWebContentsDebuggingEnabled(BrowserPrefs.remoteDebugging(context))
        TabManager.liveSessions().forEach { (it as? ChromiumEngineSession)?.applySettings() }
    }

    override fun onConfigurationChanged(context: Context, newConfig: Configuration) = Unit

    override fun isStarted(): Boolean = started

    /** WebView no mantiene abiertos los archivos de Nexo que se transfieren. */
    override fun shutdownForTransfer(done: () -> Unit) = done()

    /** WebView nueva con su perfil asignado antes de cualquier otro uso, como exige WebView. */
    internal fun newWebView(context: Context, profileName: String?): NexoWebView {
        val webView = NexoWebView(MutableContextWrapper(context.applicationContext))
        if (profileName != null) {
            ChromiumProfiles.profile(profileName)
            WebViewCompat.setProfile(webView, profileName)
        }
        return webView
    }
}
