package com.nexo.browser.engine.chromium

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.PagePrompts
import com.nexo.browser.SitePermissions
import com.nexo.browser.TabManager
import com.nexo.browser.TabRouting
import com.nexo.browser.engine.EngineContextElement
import com.nexo.browser.engine.EngineFinder
import com.nexo.browser.engine.EngineLoadRequest
import com.nexo.browser.shield.PhantomShield
import com.nexo.browser.engine.EngineObserver
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineSessionState
import com.nexo.browser.engine.FindResult
import com.nexo.browser.engine.LoadDecision
import com.nexo.browser.engine.ProcessExit
import com.nexo.browser.engine.SessionParams
import java.io.InputStream

/**
 * Pestaña de Chromium: una WebView con su perfil y sus clientes. Traduce las
 * llamadas de WebView a [EngineObserver], como hace GeckoEngine con Gecko.
 *
 * [adopted]: WebView ya creada por `onCreateWindow` (ventana pedida por otra
 * página), que conserva `window.opener`; [heldUrl] es la dirección que
 * esperaba cargar mientras Nexo decidía si abrirla.
 */
@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
class ChromiumEngineSession internal constructor(
    context: Context,
    internal val params: SessionParams,
    adopted: NexoWebView? = null,
    private var heldUrl: String? = null
) : EngineSession {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Perfil de WebView (`null` = el normal). */
    internal val profileName: String? = ChromiumProfiles.nameFor(params.isPrivate, params.contextId)

    internal val webView: NexoWebView = adopted ?: ChromiumEngine.newWebView(appContext, profileName)

    /** PhantomShield de esta pestaña (página principal y bloqueos). */
    internal val shield: PhantomShield.ShieldTab = PhantomShield.newTab(params.isPrivate)

    override var observer: EngineObserver? = null

    /** Vista que muestra ahora esta pestaña. */
    internal var host: ChromiumPageView? = null

    internal var customView: View? = null
        private set
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var opened = adopted != null
    private var closed = false
    private var suspendMediaWhenInactive = params.suspendMediaWhenInactive
    private var desktopMode = params.desktopMode
    private var mainFrameError = false
    private var sslNoticeFor: String? = null

    override val isOpen: Boolean get() = opened && !closed

    private val stateTask = Runnable { emitState() }

    override val finder: EngineFinder = object : EngineFinder {
        override fun find(term: String, backwards: Boolean, onResult: (FindResult?) -> Unit) {
            findCallback = onResult
            if (term != findTerm) {
                findTerm = term
                webView.findAllAsync(term)
            } else {
                webView.findNext(!backwards)
            }
        }

        override fun clear() {
            findTerm = null
            findCallback = null
            webView.clearMatches()
        }
    }
    private var findTerm: String? = null
    private var findCallback: ((FindResult?) -> Unit)? = null

    init {
        applySettings()
        // Register the native bridge before the first navigation so restored
        // tabs and popup windows receive media/caption controls as well.
        ExtensionManager.bindSession(this)
        PhantomShield.attach(webView, shield)
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.setFindListener { ordinal, count, done ->
            if (done) findCallback?.invoke(FindResult(count > 0, if (count > 0) ordinal + 1 else 0, count))
        }
        webView.setDownloadListener { url, userAgent, disposition, mimeType, _ ->
            observer?.onDownload(
                this,
                ChromiumDownload(url, userAgent, disposition, mimeType, webView.url, profileName)
            )
        }
        webView.setOnLongClickListener { onLongPress() }
        // Una ventana pedida por otra página carga su dirección cuando Nexo la acepta,
        // salvo que la pestaña necesite preparar su red antes (Burner, rutas).
        heldUrl?.let { url ->
            main.post {
                if (!closed && heldUrl == url && !TabRouting.preparing(this) && url != "about:blank") loadUrl(url)
            }
        }
    }

    internal fun open() {
        opened = true
    }

    /** Ajustes de la pestaña según las preferencias (también al cambiarlas). */
    internal fun applySettings() {
        val context = appContext
        val settings = webView.settings
        settings.javaScriptEnabled = BrowserPrefs.javascript(context)
        settings.domStorageEnabled = true
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(true)
        settings.mediaPlaybackRequiresUserGesture = BrowserPrefs.autoplay(context) != BrowserPrefs.AUTOPLAY_ALLOW
        settings.mixedContentMode = if (BrowserPrefs.httpsOnly(context)) WebSettings.MIXED_CONTENT_NEVER_ALLOW
            else WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.safeBrowsingEnabled = BrowserPrefs.safeBrowsing(context)
        settings.textZoom = BrowserPrefs.textScale(context)
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        applyUserAgent()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ATTRIBUTION_REGISTRATION_BEHAVIOR)) {
            WebSettingsCompat.setAttributionRegistrationBehavior(settings, WebSettingsCompat.ATTRIBUTION_BEHAVIOR_DISABLED)
        }
        val mode = BrowserPrefs.cookieMode(context)
        ChromiumProfiles.cookieManager(profileName)?.let { cookies ->
            cookies.setAcceptCookie(mode != BrowserPrefs.COOKIES_NONE)
            cookies.setAcceptThirdPartyCookies(webView, mode == BrowserPrefs.COOKIES_ALL)
        }
    }

    private fun applyUserAgent() {
        val settings = webView.settings
        val base = WebSettings.getDefaultUserAgent(appContext)
        settings.userAgentString = if (desktopMode) UserAgents.desktop(base) else UserAgents.mobile(base)
        settings.loadWithOverviewMode = desktopMode
        ChromiumDownloads.userAgent = UserAgents.mobile(base)
    }

    // --- EngineSession ---

    override fun loadUrl(url: String) {
        heldUrl = null
        webView.loadUrl(PhantomShield.cleanNavigation(upgrade(url)))
    }

    override fun reload(bypassCache: Boolean) {
        if (bypassCache) webView.clearCache(false)
        webView.reload()
    }

    override fun goBack() = webView.goBack()
    override fun goForward() = webView.goForward()

    override fun setActive(active: Boolean) {
        if (closed) return
        if (active) webView.onResume() else if (suspendMediaWhenInactive) webView.onPause()
    }

    override fun setFocused(focused: Boolean) = Unit

    override fun setHighPriority(high: Boolean) {
        runCatching {
            webView.setRendererPriorityPolicy(
                if (high) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_BOUND,
                !high
            )
        }
    }

    override fun setSuspendMediaWhenInactive(suspend: Boolean) {
        suspendMediaWhenInactive = suspend
    }

    override fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        applyUserAgent()
    }

    /** El bloqueo de rastreadores llega con PhantomShield nativo (fase 3). */
    override fun setTrackingProtection(enabled: Boolean) = Unit

    override fun exitFullScreen() {
        val callback = customCallback ?: return
        runCatching { callback.onCustomViewHidden() }
        hideCustomView()
    }

    override fun flushState() {
        main.removeCallbacks(stateTask)
        emitState()
    }

    override fun purgeHistory() = webView.clearHistory()

    override fun restoreState(state: EngineSessionState) {
        val chromium = state as? ChromiumState ?: return
        val restored = chromium.toBundle()?.let { bundle -> runCatching { webView.restoreState(bundle) }.getOrNull() }
        if (restored == null || restored.size == 0) chromium.currentUri?.let { loadUrl(it) }
    }

    override fun saveAsPdf(onResult: (InputStream?, Throwable?) -> Unit) =
        onResult(null, UnsupportedOperationException("WebView guarda PDF con el diálogo de impresión"))

    override fun printWithSystemDialog(activity: Activity, title: String): Boolean {
        val manager = activity.getSystemService(PrintManager::class.java) ?: return false
        return runCatching {
            manager.print(title, webView.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
            true
        }.getOrDefault(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        observer = null
        ExtensionManager.forgetSession(this)
        main.removeCallbacks(stateTask)
        host?.detach(this)
        runCatching { customCallback?.onCustomViewHidden() }
        customView = null
        customCallback = null
        runCatching {
            webView.stopLoading()
            webView.setDownloadListener(null)
            webView.setOnLongClickListener(null)
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.useActivity(null)
            webView.destroy()
        }
    }

    // --- Ayudas ---

    /** Solo HTTPS: las navegaciones principales a http:// pasan a https://. */
    private fun upgrade(url: String): String =
        if (BrowserPrefs.httpsOnly(appContext) && url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    private fun scheduleState() {
        main.removeCallbacks(stateTask)
        main.postDelayed(stateTask, 400L)
    }

    private fun emitState() {
        if (closed) return
        val observer = observer ?: return
        val bundle = Bundle()
        val saved = runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) {
                WebViewCompat.saveState(webView, bundle, ChromiumState.MAX_STATE_BYTES, true)
                true
            } else {
                webView.saveState(bundle) != null
            }
        }.getOrDefault(false)
        observer.onStateChange(this, ChromiumState.of(webView.url, if (saved) bundle else null))
    }

    private fun hideCustomView() {
        val view = customView ?: return
        customView = null
        customCallback = null
        host?.hideFullScreen()
        (view.parent as? ViewGroup)?.removeView(view)
        observer?.onFullScreen(this, false)
    }

    /** Pulsación larga sobre un enlace o una imagen: menú de Nexo. */
    private fun onLongPress(): Boolean {
        val hit = webView.hitTestResult
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                emitContext(hit.extra, null, null)
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                emitContext(null, hit.extra, null)
                true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val image = hit.extra
                val reply = Handler(Looper.getMainLooper()) { message ->
                    val data = message.data
                    emitContext(data.getString("url"), data.getString("src") ?: image, data.getString("title"))
                    true
                }
                webView.requestFocusNodeHref(reply.obtainMessage())
                true
            }
            else -> false
        }
    }

    private fun emitContext(link: String?, image: String?, title: String?) {
        if (link.isNullOrBlank() && image.isNullOrBlank()) return
        observer?.onContextMenu(this, EngineContextElement(link, image, title, null, null))
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val observer = observer ?: return true
            val url = request.url.toString()
            val upgraded = upgrade(url)
            if (upgraded != url) {
                view.loadUrl(upgraded)
                return true
            }
            val engineRequest = EngineLoadRequest(url, request.hasGesture(), isDirectNavigation = false, opensNewWindow = false)
            val cleaned = PhantomShield.cleanNavigation(url)
            return when (observer.onLoadRequest(this@ChromiumEngineSession, engineRequest) { allow -> if (allow && !closed) view.loadUrl(cleaned) }) {
                // Sin parámetros de rastreo (utm_*, gclid, fbclid…): se carga la dirección limpia.
                LoadDecision.DEFAULT, LoadDecision.ALLOW -> if (cleaned != url) { view.loadUrl(cleaned); true } else false
                LoadDecision.DENY, LoadDecision.PENDING -> true
            }
        }

        /** Hilo de red de WebView: reglas de PhantomShield. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            PhantomShield.intercept(shield, request)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            mainFrameError = false
            sslNoticeFor = null
            observer?.onPageStart(this@ChromiumEngineSession, url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            shield.onLocation(url)
            val observer = observer ?: return
            observer.onLocationChange(this@ChromiumEngineSession, url, false)
            observer.onCanGoBack(this@ChromiumEngineSession, view.canGoBack())
            observer.onCanGoForward(this@ChromiumEngineSession, view.canGoForward())
            scheduleState()
        }

        override fun onPageFinished(view: WebView, url: String) {
            observer?.onPageStop(this@ChromiumEngineSession, !mainFrameError)
            scheduleState()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) mainFrameError = true
        }

        /** Certificado no válido: la conexión se cancela siempre (como en Gecko, sin excepción). */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            val host = runCatching { Uri.parse(error.url).host }.getOrNull() ?: return
            if (sslNoticeFor != host && TabManager.activeSession() === this@ChromiumEngineSession) {
                sslNoticeFor = host
                TabManager.notifyMessage("Nexo bloqueó una conexión no segura: el certificado de $host no es válido.")
            }
        }

        /**
         * El proceso que dibuja las páginas terminó. Puede compartirlo más de
         * una pestaña: WebView avisa a cada una y cada una se recupera sola.
         * Devolver true evita que Android cierre Nexo.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val exit = if (detail.didCrash()) ProcessExit.CRASH else ProcessExit.KILL
            main.post { observer?.onProcessExit(this@ChromiumEngineSession, exit) }
            return true
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            PagePrompts.httpAuth(this@ChromiumEngineSession, handler, host, realm)
        }

        override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
            PagePrompts.formResubmission(dontResend, resend)
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            observer?.onProgress(this@ChromiumEngineSession, newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            observer?.onTitle(this@ChromiumEngineSession, title)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            host?.showFullScreen(view)
            observer?.onFullScreen(this@ChromiumEngineSession, true)
        }

        override fun onHideCustomView() = hideCustomView()

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (observer == null) return false
            // Ventanas sin toque del usuario: se bloquean (popunders).
            if (!isUserGesture) {
                PagePrompts.popupBlocked(this@ChromiumEngineSession)
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val hint = view.hitTestResult.takeIf {
                it.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || it.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            }?.extra
            val popup = ChromiumEngine.newWebView(appContext, profileName)
            PendingWindow(this@ChromiumEngineSession, popup, hint).start()
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            observer?.onCloseRequest(this@ChromiumEngineSession)
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
            PagePrompts.alert(this@ChromiumEngineSession, message, result)

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
            PagePrompts.confirm(this@ChromiumEngineSession, message, result)

        override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean =
            PagePrompts.prompt(this@ChromiumEngineSession, message, defaultValue, result)

        override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean =
            PagePrompts.beforeUnload(this@ChromiumEngineSession, result)

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean = PagePrompts.chooseFiles(this@ChromiumEngineSession, filePathCallback, fileChooserParams)

        override fun onPermissionRequest(request: PermissionRequest) {
            SitePermissions.onPermissionRequest(this@ChromiumEngineSession, request)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            SitePermissions.onPermissionRequestCanceled(this@ChromiumEngineSession)
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            SitePermissions.onGeolocation(this@ChromiumEngineSession, origin, callback)
        }

        /** Sin el icono gris de reproducción que WebView pinta antes del primer fotograma. */
        override fun getDefaultVideoPoster(): Bitmap? =
            runCatching { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }.getOrNull()
    }
}

/**
 * Ventana pedida por una página. WebView exige entregar la WebView nueva en
 * el acto (así conserva `window.opener` para inicios de sesión), pero su
 * dirección se conoce después: se retiene la primera navegación, Nexo aplica
 * su política de ventanas (gesto, confirmación de otro sitio) y solo entonces
 * se convierte en pestaña y carga. Si se rechaza, se destruye.
 */
private class PendingWindow(
    private val opener: ChromiumEngineSession,
    private val popup: NexoWebView,
    private val hint: String?
) {
    private val main = Handler(Looper.getMainLooper())
    private var decided = false
    private val timeout = Runnable { decide(hint ?: "about:blank") }

    fun start() {
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (decided) return false
                decide(request.url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (decided || url == "about:blank") return
                view.stopLoading()
                decide(url)
            }

            /** El proceso de la ventana pendiente terminó: se descarta sin cerrar Nexo. */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                decided = true
                destroy()
                return true
            }
        }
        main.postDelayed(timeout, 1_000L)
    }

    private fun decide(url: String) {
        if (decided) return
        decided = true
        main.removeCallbacks(timeout)
        val observer = opener.observer ?: return destroy()
        // Ventana hacia una red de "popunder" (listas $popup o redes de ventanas emergentes).
        if (PhantomShield.blocksPopup(opener.shield, url)) {
            PagePrompts.popupBlocked(opener)
            return destroy()
        }
        val request = EngineLoadRequest(url, hasUserGesture = true, isDirectNavigation = false, opensNewWindow = true)
        when (observer.onLoadRequest(opener, request) { allow -> if (allow) adopt(url) else destroy() }) {
            LoadDecision.DENY -> destroy()
            LoadDecision.PENDING -> Unit
            LoadDecision.DEFAULT, LoadDecision.ALLOW -> adopt(url)
        }
    }

    private fun adopt(url: String) {
        val observer = opener.observer ?: return destroy()
        ChromiumEngine.adopting = popup to url
        val session = try {
            observer.onNewWindow(opener, url)
        } finally {
            ChromiumEngine.adopting = null
        }
        if (session == null || (session as? ChromiumEngineSession)?.webView !== popup) {
            session?.close()
            destroy()
        }
    }

    private fun destroy() {
        main.removeCallbacks(timeout)
        runCatching { popup.stopLoading(); popup.destroy() }
    }
}

/** Identificación del navegador ante los sitios. */
internal object UserAgents {
    /**
     * Sin las marcas de WebView ("; wv" y "Version/4.0"): algunos sitios
     * (por ejemplo, el inicio de sesión de Google) rechazan las WebView.
     */
    fun mobile(base: String): String =
        base.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")

    fun desktop(base: String): String {
        val chrome = Regex("""Chrome/([\d.]+)""").find(base)?.groupValues?.get(1) ?: "131.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }
}
