package com.nexo.browser.engine.gecko

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.nexo.browser.GeckoRuntimeHolder
import com.nexo.browser.NexoGeckoView
import com.nexo.browser.PagePrompts
import com.nexo.browser.SitePermissions
import com.nexo.browser.TranslatorManager
import com.nexo.browser.UrlSafety
import com.nexo.browser.engine.BrowserEngine
import com.nexo.browser.engine.DownloadFetcher
import com.nexo.browser.engine.DownloadResponse
import com.nexo.browser.engine.EngineContextElement
import com.nexo.browser.engine.EngineDownload
import com.nexo.browser.engine.EngineFinder
import com.nexo.browser.engine.EngineLoadRequest
import com.nexo.browser.engine.EngineMedia
import com.nexo.browser.engine.EngineObserver
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineSessionState
import com.nexo.browser.engine.FindResult
import com.nexo.browser.engine.LoadDecision
import com.nexo.browser.engine.MediaEvent
import com.nexo.browser.engine.ProcessExit
import com.nexo.browser.engine.SessionParams
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.IOException
import java.io.InputStream
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Motor Gecko (GeckoView): adapta sus delegados a [EngineObserver]. */
object GeckoEngine : BrowserEngine {
    private val wrappers = WeakHashMap<GeckoSession, GeckoEngineSession>()

    override val displayName: String
        get() = "Gecko ${org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION}"

    override fun createView(context: Context): View = NexoGeckoView(context)

    override fun isStarted(): Boolean = GeckoRuntimeHolder.started()

    override fun shutdownForTransfer(done: () -> Unit) = GeckoRuntimeHolder.shutdownForTransfer(done)

    override fun applyAudioSettings(context: Context) = GeckoRuntimeHolder.applyAudioPrefs(context)

    /**
     * Descargas por el ejecutor de red de Gecko: comparte cookies, caché y
     * modo privado con las pestañas. Responde en el hilo que lo llama: se pide
     * desde el principal y se espera aquí.
     */
    override val downloadFetcher: DownloadFetcher = object : DownloadFetcher {
        private val main = Handler(Looper.getMainLooper())

        override fun fetch(url: String, headers: Map<String, String>, referrer: String?, isPrivate: Boolean): DownloadResponse {
            val context = com.nexo.browser.AppContext.get()
            val request = WebRequest.Builder(url)
                .cacheMode(WebRequest.CACHE_MODE_NO_STORE)
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    if (referrer != null && UrlSafety.isWebUrl(referrer)) referrer(referrer)
                }
                .build()
            val flags = if (isPrivate) GeckoWebExecutor.FETCH_FLAGS_PRIVATE else GeckoWebExecutor.FETCH_FLAGS_NONE
            val latch = CountDownLatch(1)
            var result: WebResponse? = null
            var failure: Throwable? = null
            main.post {
                runCatching {
                    GeckoRuntimeHolder.webExecutor(context).fetch(request, flags).accept(
                        { response -> result = response; latch.countDown() },
                        { error -> failure = error; latch.countDown() }
                    )
                }.onFailure { failure = it; latch.countDown() }
            }
            if (!latch.await(25, TimeUnit.SECONDS)) throw IOException("sin respuesta del servidor")
            failure?.let { throw IOException(it.message ?: "error de red", it) }
            val response = result ?: throw IOException("sin respuesta del servidor")
            return response.toDownload()
        }
    }

    override fun createSession(context: Context, params: SessionParams): EngineSession {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(params.isPrivate)
            .contextId(params.contextId)
            .suspendMediaWhenInactive(params.suspendMediaWhenInactive)
            .useTrackingProtection(params.trackingProtection)
            .userAgentMode(userAgentMode(params.desktopMode))
            .viewportMode(viewportMode(params.desktopMode))
            .build()
        val gecko = GeckoSession(settings)
        val session = GeckoEngineSession(gecko)
        wrappers[gecko] = session
        TranslatorManager.bind(session)
        gecko.setPromptDelegate(PagePrompts)
        gecko.setPermissionDelegate(SitePermissions)
        return session
    }

    /** La pestaña de Nexo que corresponde a una sesión de Gecko (mensajes de extensiones, diálogos). */
    fun wrapperOf(gecko: GeckoSession?): GeckoEngineSession? = gecko?.let { wrappers[it] }

    override fun open(context: Context, session: EngineSession) {
        session.gecko.open(GeckoRuntimeHolder.get(context))
    }

    override fun parseState(raw: String): EngineSessionState? =
        runCatching { GeckoSession.SessionState.fromString(raw) }.getOrNull()?.let { GeckoState(it) }

    override fun speculativeConnect(context: Context, url: String) {
        if (!UrlSafety.isWebUrl(url)) return
        runCatching { GeckoRuntimeHolder.webExecutor(context).speculativeConnect(url) }
    }

    override fun clearAllData(context: Context, onDone: (Boolean, String?) -> Unit) {
        GeckoRuntimeHolder.get(context).storageController
            .clearData(StorageController.ClearFlags.ALL)
            .accept({ onDone(true, null) }, { error -> onDone(false, error?.message) })
    }

    override fun clearContextData(context: Context, contextId: String) {
        GeckoRuntimeHolder.get(context).storageController.clearDataForSessionContext(contextId)
    }

    override fun applySettings(context: Context) {
        GeckoRuntimeHolder.applyRuntimePrefs(context)
    }

    override fun onConfigurationChanged(context: Context, newConfig: android.content.res.Configuration) {
        runCatching { GeckoRuntimeHolder.get(context).configurationChanged(newConfig) }
    }

    internal fun userAgentMode(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    internal fun viewportMode(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
}

/** La [GeckoSession] de una pestaña. Solo para funciones propias de Gecko (extensiones, traducción, rutas). */
val EngineSession.gecko: GeckoSession
    get() = (this as GeckoEngineSession).gecko

class GeckoState(val state: GeckoSession.SessionState) : EngineSessionState {
    override val currentUri: String?
        get() = runCatching { state[state.currentIndex].uri }.getOrNull()

    override fun serialize(): String = state.toString()
}

class GeckoDownload(val response: WebResponse) : EngineDownload {
    override val uri: String get() = response.uri
    override fun discard() {
        runCatching { response.body?.close() }
    }

    override fun open(): DownloadResponse = response.toDownload()
}

internal fun WebResponse.toDownload(): DownloadResponse = DownloadResponse(uri, statusCode, headers, body)

private class GeckoMedia(val media: MediaSession) : EngineMedia {
    override val isActive: Boolean get() = media.isActive
    override fun play() = media.play()
    override fun pause() = media.pause()
    override fun stop() = media.stop()
}

class GeckoEngineSession internal constructor(val gecko: GeckoSession) : EngineSession {
    override var observer: EngineObserver? = null
    private val media = WeakHashMap<MediaSession, GeckoMedia>()

    override val isOpen: Boolean get() = gecko.isOpen

    override val finder: EngineFinder = object : EngineFinder {
        override fun find(term: String, backwards: Boolean, onResult: (FindResult?) -> Unit) {
            val finder = gecko.finder
            finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or
                GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
            finder.find(term, if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0).accept(
                { result -> onResult(result?.let { FindResult(it.found, it.current, it.total) }) },
                { onResult(null) }
            )
        }

        override fun clear() = gecko.finder.clear()
    }

    init {
        installDelegates()
    }

    override fun loadUrl(url: String) = gecko.loadUri(url)

    override fun reload(bypassCache: Boolean) {
        if (bypassCache) gecko.reload(GeckoSession.LOAD_FLAGS_BYPASS_CACHE) else gecko.reload()
    }

    override fun goBack() = gecko.goBack()
    override fun goForward() = gecko.goForward()
    override fun setActive(active: Boolean) = gecko.setActive(active)
    override fun setFocused(focused: Boolean) = gecko.setFocused(focused)

    override fun setHighPriority(high: Boolean) =
        gecko.setPriorityHint(if (high) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT)

    override fun setSuspendMediaWhenInactive(suspend: Boolean) =
        gecko.settings.setSuspendMediaWhenInactive(suspend)

    override fun setDesktopMode(enabled: Boolean) {
        gecko.settings.setUserAgentMode(GeckoEngine.userAgentMode(enabled))
        gecko.settings.setViewportMode(GeckoEngine.viewportMode(enabled))
    }

    override fun setTrackingProtection(enabled: Boolean) = gecko.settings.setUseTrackingProtection(enabled)
    override fun exitFullScreen() = gecko.exitFullScreen()
    override fun flushState() = gecko.flushSessionState()
    override fun purgeHistory() = gecko.purgeHistory()

    override fun restoreState(state: EngineSessionState) {
        gecko.restoreState((state as GeckoState).state)
    }

    override fun saveAsPdf(onResult: (InputStream?, Throwable?) -> Unit) {
        runCatching {
            gecko.saveAsPdf().accept({ stream -> onResult(stream, null) }, { error -> onResult(null, error) })
        }.onFailure { onResult(null, it) }
    }

    /** Un cierre intencionado nunca debe disparar la recuperación ni abrir otra ventana. */
    override fun close() {
        observer = null
        gecko.contentDelegate = null
        gecko.navigationDelegate = null
        gecko.setProgressDelegate(null)
        gecko.setMediaSessionDelegate(null)
        gecko.setTranslationsSessionDelegate(null)
        TranslatorManager.forget(this)
        runCatching {
            gecko.setActive(false)
            gecko.close()
        }
    }

    private fun wrap(mediaSession: MediaSession): EngineMedia =
        media.getOrPut(mediaSession) { GeckoMedia(mediaSession) }

    private fun installDelegates() {
        val self = this
        gecko.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                observer?.onProcessExit(self, ProcessExit.CRASH)
            }

            override fun onKill(session: GeckoSession) {
                observer?.onProcessExit(self, ProcessExit.KILL)
            }

            override fun onFirstContentfulPaint(session: GeckoSession) {
                observer?.onFirstContentfulPaint(self)
            }

            override fun onTitleChange(session: GeckoSession, title: String?) {
                observer?.onTitle(self, title)
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement
            ) {
                observer?.onContextMenu(
                    self,
                    EngineContextElement(element.linkUri, element.srcUri, element.title, element.altText, element.linkText)
                )
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val current = observer
                if (current == null) runCatching { response.body?.close() }
                else current.onDownload(self, GeckoDownload(response))
            }

            override fun onCloseRequest(session: GeckoSession) {
                observer?.onCloseRequest(self)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                observer?.onFullScreen(self, fullScreen)
            }

            // window.focus() is not a user tab selection. Background pages,
            // including popunders, must not steal the visible view.
            override fun onFocusRequest(session: GeckoSession) = Unit
        })

        gecko.setMediaSessionDelegate(object : MediaSession.Delegate {
            override fun onActivated(session: GeckoSession, mediaSession: MediaSession) {
                observer?.onMedia(self, MediaEvent.ACTIVATED, wrap(mediaSession), null)
            }

            override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
                observer?.onMedia(self, MediaEvent.DEACTIVATED, wrap(mediaSession), null)
            }

            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                observer?.onMedia(self, MediaEvent.PLAY, wrap(mediaSession), null)
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                observer?.onMedia(self, MediaEvent.PAUSE, wrap(mediaSession), null)
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                observer?.onMedia(self, MediaEvent.STOP, wrap(mediaSession), null)
            }

            override fun onMetadata(session: GeckoSession, mediaSession: MediaSession, meta: MediaSession.Metadata) {
                observer?.onMedia(self, MediaEvent.METADATA, wrap(mediaSession), meta.title)
            }
        })

        gecko.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                observer?.onLocationChange(self, url, hasUserGesture)
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val current = observer ?: return GeckoResult.deny()
                val pending = GeckoResult<AllowOrDeny>()
                var decided = false
                val decide: (Boolean) -> Unit = { allow ->
                    if (!decided) {
                        decided = true
                        pending.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
                    }
                }
                val engineRequest = EngineLoadRequest(
                    uri = request.uri,
                    hasUserGesture = request.hasUserGesture,
                    isDirectNavigation = request.isDirectNavigation,
                    opensNewWindow = request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
                )
                return when (current.onLoadRequest(self, engineRequest, decide)) {
                    LoadDecision.DEFAULT -> null
                    LoadDecision.ALLOW -> GeckoResult.allow()
                    LoadDecision.DENY -> GeckoResult.deny()
                    LoadDecision.PENDING -> pending
                }
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                val created = observer?.onNewWindow(self, uri) ?: return null
                return GeckoResult.fromValue(created.gecko)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                observer?.onCanGoBack(self, canGoBack)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                observer?.onCanGoForward(self, canGoForward)
            }
        })

        gecko.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                observer?.onStateChange(self, GeckoState(sessionState))
            }

            override fun onPageStart(session: GeckoSession, url: String) {
                observer?.onPageStart(self, url)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                observer?.onProgress(self, progress)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                observer?.onPageStop(self, success)
            }
        })
    }
}
