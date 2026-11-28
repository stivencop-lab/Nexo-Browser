package com.nexo.browser.engine.cefrium

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import com.cefrium.Cefrium
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumChromeClient
import com.cefrium.CefriumContentSetting
import com.cefrium.CefriumContextMenu
import com.cefrium.CefriumDownloadClient
import com.cefrium.CefriumFileChooserCallback
import com.cefrium.CefriumFileChooserParams
import com.cefrium.CefriumJsDialogType
import com.cefrium.CefriumLoadError
import com.cefrium.CefriumNavigationClient
import com.cefrium.CefriumNavigationRequest
import com.cefrium.CefriumPermission
import com.cefrium.CefriumPermissionRequest
import com.cefrium.CefriumProfile
import com.cefrium.CefriumRendererTerminationStatus
import com.cefrium.CefriumSslError
import com.cefrium.CefriumUserAgentMetadata
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.ExtensionManager
import com.nexo.browser.PagePrompts
import com.nexo.browser.R
import com.nexo.browser.SitePermissions
import com.nexo.browser.TabManager
import com.nexo.browser.TabRouting
import com.nexo.browser.engine.EngineContextElement
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
import com.nexo.browser.extensions.hub.ExtensionStoreResolver
import com.nexo.browser.shield.PhantomShield
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import kotlin.math.ln

/**
 * Pestaña de Chromium: un `CefriumBrowser` con su perfil y sus clientes.
 * Traduce los avisos de Cefrium a [EngineObserver].
 *
 * El navegador se crea cuando hay una Activity de Nexo donde dibujar
 * ([ensureBrowser]); hasta entonces se guardan la dirección o el historial
 * que tocaba cargar. Si la Activity se destruye, el navegador se vuelve a crear
 * en la siguiente con su historial completo.
 *
 * [adopted]: navegador ya creado por `onCreateWindow` (ventana pedida por otra
 * página), que conserva `window.opener` y carga solo su dirección.
 */
class CefriumEngineSession internal constructor(
    context: Context,
    internal val params: SessionParams,
    adopted: CefriumBrowser? = null,
    adoptedUrl: String? = null
) : EngineSession {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    internal val profile: CefriumProfile = adopted?.profile ?: CefriumProfiles.forSession(params.isPrivate, params.contextId)

    /** PhantomShield de esta pestaña (página principal, bloqueos y puente). */
    internal val shield: PhantomShield.ShieldTab = PhantomShield.newTab(params.isPrivate)
    internal val isPrivate: Boolean = params.isPrivate

    /** Identificador monotónico único por sesión (evita colisiones de identityHashCode). */
    internal val sessionUid: Long = nextSessionUid.incrementAndGet()

    /** Generación monotónica del documento activo; se incrementa en cada `onLoadStart`, `releaseBrowser` y `close`. */
    private val docGen = java.util.concurrent.atomic.AtomicLong(0L)
    internal fun documentGeneration(): Long = docGen.get()
    internal fun currentLocationUrl(): String? = currentUrl
    internal fun isActiveSession(): Boolean = active && !closed
    private var loadStartedAtMs = 0L
    @Volatile internal var lastLoadDurationMs: Long? = null
        private set

    internal var browser: CefriumBrowser? = null
        private set
    private var boundActivity = WeakReference<Activity>(null)

    override var observer: EngineObserver? = null

    /** Vista que muestra ahora esta pestaña. */
    internal var host: CefriumPageView? = null

    private var opened = adopted != null
    private var closed = false
    private var active = false
    private var suspendMediaWhenInactive = params.suspendMediaWhenInactive
    private var desktopMode = params.desktopMode
    private var storeDesktopOptOut = false
    private var appliedDesktop: Boolean? = null
    private var lastUpgradedHttpUrl: String? = null
    internal var navigationIsGet: Boolean = true
        private set
    private val downloads = Downloads()
    private var mainFrameError = false
    private var sslNoticeFor: String? = null
    private var unresponsiveNotified = false
    internal var fullScreen = false
        private set
    private var currentUrl: String? = adoptedUrl

    /** Lo que tocaba hacer antes de tener navegador: cargar una dirección o restaurar un historial. */
    private var pendingUrl: String? = null
    private var pendingState: CefriumState? = null

    /** El interceptor actual filtra cookies (la política de Ajustes lo exige). */
    private var cookieFiltering: Boolean? = null

    /** Medios con sonido de la página (los informa el puente): notificación y segundo plano. */
    private var mediaActive = false
    private var mediaPlaying = false
    private var mediaTitle: String? = null

    /** Devuelve el navegador solo si sigue vivo (Cefrium lanza IllegalStateException tras close()). */
    private fun liveBrowser(): CefriumBrowser? = browser?.takeIf { !closed && !it.isClosed }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Controles de la notificación: Chromium pausa y reanuda en el proceso del
     * navegador, así que funcionan aunque la página esté en segundo plano.
     */
    private val media = object : EngineMedia {
        override val isActive: Boolean get() = mediaActive
        override fun play() { onMain { runCatching { liveBrowser()?.resumeMedia() } } }
        override fun pause() { onMain { runCatching { liveBrowser()?.suspendMedia() } } }
        override fun stop() { onMain { runCatching { liveBrowser()?.suspendMedia() } } }
    }

    override val isOpen: Boolean get() = opened && !closed

    private val stateTask = Runnable { emitState() }

    override val finder: EngineFinder = object : EngineFinder {
        override fun find(term: String, backwards: Boolean, onResult: (FindResult?) -> Unit) {
            val target = liveBrowser() ?: return onResult(null)
            findCallback = onResult
            val next = term == findTerm
            findTerm = term
            runCatching { target.find(term, !backwards, false, next) }.onFailure { onResult(null) }
        }

        override fun clear() {
            findTerm = null
            findCallback = null
            runCatching { liveBrowser()?.stopFinding(true) }
        }
    }
    private var findTerm: String? = null
    // Declaradas antes de `init`: setUp() puede correr desde él.
    /** Scripts de arranque oscuro de este navegador y su contenido (solo se cambian si cambia). */
    private var darkBoot: List<CefriumBrowser.ScriptHandler> = emptyList()
    private var darkBootSignature = ""
    /** Última combinación esquema/lienzo aplicada: no se repite en cada navegación. */
    private var lastDark: Pair<Boolean, Int>? = null

    private var findCallback: ((FindResult?) -> Unit)? = null

    init {
        PhantomShield.attachProfile(profile)
        if (adopted != null) setUp(adopted, CefriumEngine.activity())
    }

    internal fun open() {
        opened = true
        ensureBrowser()
    }

    /**
     * Crea el navegador si falta y hay Activity, o lo vuelve a crear (con su
     * historial) si su Activity se destruyó o el navegador se cerró. Devuelve el navegador vivo.
     */
    internal fun ensureBrowser(): CefriumBrowser? {
        if (closed || !opened || (!isPrivate && UpgradeImport.pending)) return liveBrowser()
        val activity = CefriumEngine.activity() ?: return liveBrowser()
        val current = browser
        if (current != null) {
            if (current.isClosed) {
                EngineDiagnostics.log("navegador cerrado inesperadamente; recreando superficie")
                pendingState = CefriumState.of(currentUrl, null)
                pendingUrl = null
                browser = null
            } else {
                val old = boundActivity.get()
                if (old === activity || (old != null && !old.isDestroyed)) return current
                // La Activity de este navegador ya no existe: otro igual en la nueva.
                EngineDiagnostics.recordSurfaceRebind()
                EngineDiagnostics.log("recreando superficie de pestaña por Activity destruida")
                val navigation = runCatching { current.serializeNavigationState() }.getOrNull()
                pendingState = CefriumState.of(currentUrl, navigation)
                pendingUrl = null
                releaseBrowser()
            }
        }
        val created = runCatching {
            SpareRendererPool.take(activity, profile) ?: CefriumBrowser.createWithSurface(activity, profile)
        }.getOrElse {
            EngineDiagnostics.log("createWithSurface falló (perfil ${profile.name}, privado=${profile.isIncognito})", it)
            TabManager.notifyMessage("Chromium no pudo abrir la pestaña: ${it.message ?: it.javaClass.simpleName}")
            return null
        }
        EngineDiagnostics.log("navegador creado (perfil ${profile.name}, privado=${profile.isIncognito})")
        setUp(created, activity)
        val state = pendingState
        val url = pendingUrl
        pendingState = null
        pendingUrl = null
        when {
            state != null -> applyState(created, state)
            url != null -> {
                applyUserAgent(created, url)
                created.loadUrl(url)
            }
        }
        host?.attachBrowser(this)
        return created
    }

    private fun setUp(target: CefriumBrowser, activity: Activity?) {
        browser = target
        boundActivity = WeakReference(activity)
        CefriumEngine.bind(target, this)
        // Navegador nuevo: nada de lo aplicado al anterior vale para este.
        darkBoot = emptyList()
        darkBootSignature = ""
        lastDark = null
        appliedDesktop = null
        applyCanvasBackground(target)
        // Antes que los scripts de PhantomShield: la política oscura ya está cuando arranca el motor de la página.
        applyDarkBoot(target)
        target.setNavigationClient(Navigation())
        target.setChromeClient(Chrome())
        target.setDownloadClient(downloads)
        runCatching { target.setPrintHandler { handlePrintRequest(target) } }
        runCatching { target.setTextSelectionActionModeCallback(createTextSelectionCallback(target)) }
        cookieFiltering = PhantomShield.filtersCookies()
        target.setResourceInterceptor(PhantomShield.interceptor(shield))
        PhantomShield.attach(target, shield)
        ExtensionManager.bindSession(this)
        applySettings()
        if (active) {
            target.onResume()
            target.setPageVisible(true)
        } else {
            target.setPageVisible(false)
        }
    }

    private fun applyCanvasBackground(target: CefriumBrowser) = applyDarkFor(target, currentUrl)

    /**
     * Esquema de color (`prefers-color-scheme`) y lienzo según el sitio al que
     * se va, antes de que su documento empiece: un sitio excluido no recibe
     * «oscuro» y uno oscuro no pinta blanco mientras carga.
     */
    private fun applyDarkFor(target: CefriumBrowser, url: String?) {
        val policy = runCatching { com.nexo.browser.dark.DarkEngine.policyFor(appContext, url) }.getOrNull() ?: return
        val next = policy.enabled to com.nexo.browser.dark.DarkEngine.canvasColor(policy)
        if (next == lastDark || target.isClosed) return
        lastDark = next
        runCatching { target.setDarkMode(next.first) }
        runCatching { target.setBackgroundColor(next.second) }
    }

    /** Política oscura síncrona para el motor de la página (ver [com.nexo.browser.dark.DarkEngine.bootScripts]). */
    private fun applyDarkBoot(target: CefriumBrowser) {
        val scripts = runCatching { com.nexo.browser.dark.DarkEngine.bootScripts(appContext) }.getOrNull() ?: return
        val signature = scripts.joinToString("\n") { it.script + "@" + it.origins.joinToString(",") }
        if (signature == darkBootSignature || target.isClosed) return
        darkBoot.forEach { runCatching { it.remove() } }
        darkBoot = scripts.mapNotNull { script ->
            runCatching { target.addDocumentStartJavaScript(script.script, script.origins) }
                .onFailure { EngineDiagnostics.log("modo oscuro: script de arranque rechazado", it) }
                .getOrNull()
        }
        darkBootSignature = signature
    }

    /** Restores the current entry and Chromium PageState, including the scroll position. */
    internal fun recreateSurface(expected: CefriumBrowser): Boolean {
        if (liveBrowser() !== expected || host == null) return false
        if (fullScreen) {
            fullScreen = false
            observer?.onFullScreen(this, false)
        }
        val navigation = runCatching { expected.serializeNavigationState() }.getOrNull()
        pendingState = CefriumState.of(currentUrl, navigation)
        pendingUrl = null
        EngineDiagnostics.recordSurfaceRebind()
        releaseBrowser()
        return ensureBrowser() != null
    }

    /** Cierra el navegador sin cerrar la pestaña (Activity destruida, transferencia de perfil). */
    internal fun releaseBrowser() {
        val target = browser ?: return
        browser = null
        docGen.incrementAndGet()
        com.nexo.browser.TranslatorManager.forget(this)
        com.nexo.browser.extensions.nxr.NxrRuntime.forgetSession(this)
        host?.detachBrowser(this, target)
        runCatching {
            if (!target.isClosed) {
                target.stopLoading()
                (target.surfaceContainer.parent as? ViewGroup)?.removeView(target.surfaceContainer)
                target.close()
            }
        }
        downloads.discardAll()
        // Chromium puede dejar vivo el proceso del navegador cerrado: liberarlo (nunca toca uno en uso).
        runCatching { CefriumBrowser.reapUnusedRenderers() }
    }

    /** Ajustes de la pestaña según las preferencias (también al cambiarlas). */
    internal fun applySettings() {
        val target = liveBrowser() ?: return
        val context = appContext
        runCatching {
            target.setJavaScriptEnabled(BrowserPrefs.javascript(context))
            target.setMediaPlaybackRequiresUserGesture(BrowserPrefs.autoplay(context) != BrowserPrefs.AUTOPLAY_ALLOW)
            target.setPullToRefreshEnabled(BrowserPrefs.pullToRefresh(context))
            target.setPinchToZoomEnabled(true)
            applyZoomFor(target, currentUrl)
            if (!isPrivate) com.nexo.browser.SitePermissionStore.applyAllToBrowser(context, target, currentUrl)
            lastDark = null
            applyCanvasBackground(target)
            applyDarkBoot(target)
            applyUserAgent(target)
        }
        // El filtro de cookies cuesta en cada cookie: el interceptor cambia solo si cambia la política.
        val filtering = PhantomShield.filtersCookies()
        if (filtering != cookieFiltering) {
            cookieFiltering = filtering
            runCatching { target.setResourceInterceptor(PhantomShield.interceptor(shield)) }
        }
    }

    private fun applyZoomFor(target: CefriumBrowser, url: String?) {
        if (target.isClosed) return
        val percent = com.nexo.browser.SiteZoomStore.effectivePercent(appContext, url)
        runCatching { target.setZoomLevel(com.nexo.browser.SiteControlModel.zoomPercentToCefriumLevel(percent)) }
    }

    /** Aplica y guarda un nivel de zoom por sitio (75%–200%) en la sesión activa. */
    fun setSiteZoom(url: String?, percent: Int) {
        val host = com.nexo.browser.SiteControlModel.hostOf(url ?: currentUrl)
        if (host.isNotEmpty()) com.nexo.browser.SiteZoomStore.set(appContext, host, percent)
        onMain {
            val target = liveBrowser() ?: return@onMain
            runCatching { target.setZoomLevel(com.nexo.browser.SiteControlModel.zoomPercentToCefriumLevel(percent)) }
        }
    }

    fun isAudioMuted(): Boolean = runCatching { liveBrowser()?.isAudioMuted == true }.getOrDefault(false)

    /** Audio nativo activo en Chromium (incluye iframes de terceros y Web Audio). */
    fun isPlayingAudio(): Boolean = runCatching { liveBrowser()?.isPlayingAudio == true }.getOrDefault(false)

    fun setAudioMuted(muted: Boolean) {
        onMain { runCatching { liveBrowser()?.setAudioMuted(muted) } }
    }

    /** Historial atrás/adelante nativo de esta pestaña, del más antiguo al más reciente. */
    fun navigationEntries(): List<Triple<Boolean, String, String>> = runCatching {
        liveBrowser()?.navigationEntries?.map { entry ->
            Triple(entry.current, entry.url.orEmpty(), entry.title.orEmpty())
        }.orEmpty()
    }.getOrDefault(emptyList())

    /** Salta [offset] pasos atrás (negativo) o adelante (positivo) en el historial nativo de la pestaña. */
    fun goBackOrForward(offset: Int): Boolean {
        if (offset == 0) return false
        val target = liveBrowser() ?: return false
        return runCatching {
            if (!target.canGoBackOrForward(offset)) return false
            target.goBackOrForward(offset)
            true
        }.getOrDefault(false)
    }

    fun setSiteContentSetting(origin: String, permission: CefriumPermission, decision: com.nexo.browser.SitePermissionStore.Decision) {
        com.nexo.browser.SitePermissionStore.set(
            appContext, origin, permission, decision, liveBrowser(),
            com.nexo.browser.SitePermissionStore.scope(isPrivate, params.contextId)
        )
    }

    /** Cuenta las cookies reales almacenadas en el perfil de Cefrium para [url]. */
    fun countSiteCookies(url: String, onDone: (Int?) -> Unit) {
        val origin = com.nexo.browser.SiteControlModel.originOf(url)
        if (origin == null) { onDone(null); return }
        val started = runCatching {
            profile.cookieManager.getCookies(url) { cookies ->
                main.post { onDone(cookies?.size ?: 0) }
            }
        }
        if (started.isFailure) onDone(null)
    }

    /** Consulta cookies y bytes reales de almacenamiento de [url] mediante `CefriumProfile.listSiteData`. */
    fun inspectSiteData(url: String, onDone: (cookieCount: Long?, storageBytes: Long?) -> Unit) {
        val origin = com.nexo.browser.SiteControlModel.originOf(url)
        val host = com.nexo.browser.SiteControlModel.hostOf(url)
        if (origin == null || host.isEmpty()) { onDone(null, null); return }
        val started = runCatching {
            profile.listSiteData { sites ->
                var cookies = 0L
                var bytes = 0L
                var matched = false
                sites?.forEach { entry ->
                    if (com.nexo.browser.SiteControlModel.siteDataMatches(entry.site.orEmpty(), origin, host)) {
                        matched = true
                        cookies += entry.cookieCount.coerceAtLeast(0L)
                        bytes += entry.storageBytes.coerceAtLeast(0L)
                    }
                }
                if (matched && cookies > 0L) {
                    main.post { onDone(cookies, bytes) }
                } else {
                    val fallback = runCatching {
                        profile.cookieManager.getCookies(url) { list ->
                            val liveCookies = (list?.size ?: 0).toLong()
                            main.post { onDone(maxOf(cookies, liveCookies), bytes) }
                        }
                    }
                    if (fallback.isFailure) main.post { onDone(cookies, bytes) }
                }
            }
        }
        if (started.isFailure) {
            countSiteCookies(url) { count -> onDone(count?.toLong(), null) }
        }
    }

    private fun handlePrintRequest(target: CefriumBrowser): Boolean {
        val act = CefriumEngine.activity()
            ?: boundActivity.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
            ?: return false
        val title = runCatching { target.title }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: com.nexo.browser.SiteControlModel.hostOf(currentUrl).ifBlank { "Página de Nexo" }
        return printWithSystemDialog(act, title)
    }

    /** Acciones nativas de Nexo en la barra flotante de selección de texto de Chromium. */
    private fun createTextSelectionCallback(target: CefriumBrowser): ActionMode.Callback =
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (menu.findItem(R.id.nexo_sel_ask_ai) == null) {
                    menu.add(Menu.NONE, R.id.nexo_sel_ask_ai, Menu.CATEGORY_SECONDARY, "Preguntar a Nexo AI")
                }
                if (menu.findItem(R.id.nexo_sel_translate) == null) {
                    menu.add(Menu.NONE, R.id.nexo_sel_translate, Menu.CATEGORY_SECONDARY, "Traducir selección")
                }
                if (menu.findItem(R.id.nexo_sel_search_tab) == null) {
                    menu.add(Menu.NONE, R.id.nexo_sel_search_tab, Menu.CATEGORY_SECONDARY, "Buscar en pestaña nueva")
                }
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                val hasSelection = !closed && !target.isClosed &&
                    runCatching { target.selectedText }.getOrNull()?.trim()?.isNotEmpty() == true
                menu.findItem(R.id.nexo_sel_ask_ai)?.isVisible = hasSelection
                menu.findItem(R.id.nexo_sel_translate)?.isVisible = hasSelection
                menu.findItem(R.id.nexo_sel_search_tab)?.isVisible = hasSelection
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val id = item.itemId
                if (id != R.id.nexo_sel_ask_ai && id != R.id.nexo_sel_translate && id != R.id.nexo_sel_search_tab) {
                    return false
                }
                if (closed || target.isClosed) {
                    mode.finish()
                    return true
                }
                val selected = runCatching { target.selectedText }.getOrNull()?.trim().orEmpty()
                mode.finish()
                if (selected.isEmpty()) return true
                val act = CefriumEngine.activity()
                    ?: boundActivity.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                when (id) {
                    R.id.nexo_sel_ask_ai -> {
                        if (act != null) {
                            val snippet = selected.take(1200)
                            com.nexo.browser.ai.NexoAiSheet.show(
                                act,
                                initialScope = "selection",
                                initialPrompt = "Explica o responde sobre este texto seleccionado:\n\n\"$snippet\""
                            )
                        }
                    }
                    R.id.nexo_sel_translate -> {
                        val tab = TabManager.allTabs().firstOrNull { it.session === this@CefriumEngineSession }
                        if (isPrivate || tab?.networkProfile?.let { it.type != "direct" } == true) {
                            TabManager.notifyMessage("La traducción en la nube está desactivada en pestañas privadas o con proxy.")
                        } else if (act != null) {
                            com.nexo.browser.TranslatorManager.showSelectionSheet(act, selected)
                        }
                    }
                    R.id.nexo_sel_search_tab -> {
                        val tab = TabManager.allTabs().firstOrNull { it.session === this@CefriumEngineSession }
                        val searchUrl = com.nexo.browser.SearchResolver.searchUrl(appContext, selected.take(300))
                        TabManager.createTab(
                            url = searchUrl,
                            isPrivate = isPrivate,
                            activate = true,
                            burnerId = tab?.burnerId,
                            networkProfile = tab?.networkProfile
                        )
                    }
                }
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }

    /** Elimina cookies y datos locales (storage, caché de origen) de [url] en el perfil de Cefrium. */
    fun clearSiteDataAndCookies(url: String, onDone: (Boolean) -> Unit) {
        val origin = com.nexo.browser.SiteControlModel.originOf(url)
        if (origin == null || closed) { onDone(false); return }
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        val timeout = Runnable { if (finished.compareAndSet(false, true)) onDone(false) }
        fun complete(ok: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            main.post { onDone(ok) }
        }
        main.postDelayed(timeout, 10_000)
        runCatching {
            profile.cookieManager.removeCookies(url, null) { count ->
                if (!finished.get()) runCatching {
                    profile.clearSiteData(origin) { complete(count != null && count >= 0) }
                }.onFailure { complete(false) }
            }
        }.onFailure { complete(false) }
    }

    internal fun lastBlockedSslHost(): String? = sslNoticeFor

    internal fun isDesktopPresentation(): Boolean =
        desktopMode || (!storeDesktopOptOut && ExtensionStoreResolver.usesStoreDesktopProfile(currentUrl))

    internal fun restoreScroll(scrollY: Int) {
        runCatching { liveBrowser()?.evaluateJavaScript("window.scrollTo(0, $scrollY)") }
    }

    private fun applyUserAgent(target: CefriumBrowser, destination: String? = currentUrl) {
        val desktop = desktopMode || (!storeDesktopOptOut && ExtensionStoreResolver.usesStoreDesktopProfile(destination))
        if (!target.isClosed && appliedDesktop != desktop) {
            if (desktop) {
                target.setUserAgentOverride(UserAgents.desktop(), UserAgents.desktopMetadata())
            } else {
                target.setUserAgentOverride(null)
            }
            appliedDesktop = desktop
        }
    }

    // --- EngineSession ---

    override fun loadUrl(url: String) {
        if (TabRouting.preparing(this)) return
        navigationIsGet = true
        val cleaned = PhantomShield.cleanNavigation(url)
        val target = upgrade(cleaned)
        lastUpgradedHttpUrl = cleaned.takeIf { it != target }
        currentUrl = target
        if (!ExtensionStoreResolver.usesStoreDesktopProfile(target)) storeDesktopOptOut = false
        val live = ensureBrowser()?.takeIf { !it.isClosed }
        if (live == null) {
            pendingUrl = target
            pendingState = null
        } else {
            // onBeforeNavigate no ve las cargas de la app: el esquema del destino se fija aquí.
            applyDarkFor(live, target)
            runCatching { applyUserAgent(live, target); live.loadUrl(target) }
        }
    }

    override fun reload(bypassCache: Boolean) {
        if (TabRouting.preparing(this)) return
        val target = liveBrowser() ?: return
        if (bypassCache) runCatching { target.clearCache() }
        runCatching { target.reload() }
    }

    override fun stop() {
        runCatching { liveBrowser()?.stopLoading() }
    }

    override fun goBack() {
        if (!TabRouting.preparing(this)) runCatching { liveBrowser()?.goBack() }
    }

    override fun goForward() {
        if (!TabRouting.preparing(this)) runCatching { liveBrowser()?.goForward() }
    }

    override fun setActive(active: Boolean) {
        this.active = active
        if (closed) return
        val target = (if (active) ensureBrowser() else liveBrowser())?.takeIf { !it.isClosed } ?: return
        runCatching {
            when {
                active -> {
                    target.onResume()
                    target.setPageVisible(true)
                }
                suspendMediaWhenInactive -> {
                    target.onPause()
                    target.setPageVisible(false)
                }
                // Con audio de fondo permitido: la página se oculta pero sigue sonando.
                else -> target.setPageVisible(false)
            }
        }
    }

    /**
     * Reenvía `Activity.onPause()` a Cefrium 0.22.0 durante Picture-in-Picture:
     * el SDK mantiene la página visible y reproduciendo en PiP, pausa al cerrar la
     * ventana mini (`onStop`) y recupera la superficie al encender pantalla en Android 10.
     */
    internal fun onHostPauseInPip() {
        if (closed) return
        val target = liveBrowser()?.takeIf { !it.isClosed } ?: return
        runCatching { target.onPause() }
    }

    /** Oculta la superficie sin pausar ni invocar onResume() mientras la Activity pasa a segundo plano. */
    internal fun keepAudioInBackground() {
        this.active = false
        if (closed) return
        val target = liveBrowser()?.takeIf { !it.isClosed } ?: return
        runCatching { target.setPageVisible(false) }
    }

    override fun setFocused(focused: Boolean) {
        runCatching { liveBrowser()?.setFocus(focused) }
    }

    /** Cefrium da a cada pestaña visible su prioridad: nada que pedir. */
    override fun setHighPriority(high: Boolean) = Unit

    override fun setSuspendMediaWhenInactive(suspend: Boolean) {
        suspendMediaWhenInactive = suspend
    }

    override fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        storeDesktopOptOut = !enabled && ExtensionStoreResolver.usesStoreDesktopProfile(currentUrl)
        liveBrowser()?.let { runCatching { applyUserAgent(it) } }
    }

    /** El bloqueo de rastreadores es PhantomShield, en el interceptor de red. */
    override fun setTrackingProtection(enabled: Boolean) = Unit

    override fun exitFullScreen() {
        runCatching { liveBrowser()?.exitFullscreen() }
    }

    override fun flushState() {
        main.removeCallbacks(stateTask)
        emitState()
    }

    /**
     * Olvida el historial atrás/adelante: un navegador nuevo en el mismo
     * perfil que solo conoce la página actual.
     */
    override fun purgeHistory() {
        val target = liveBrowser() ?: return
        val url = currentUrl ?: runCatching { target.url }.getOrNull()
        releaseBrowser()
        pendingState = null
        pendingUrl = url?.takeIf { it.isNotBlank() }
        ensureBrowser()
    }

    override fun restoreState(state: EngineSessionState) {
        if (TabRouting.preparing(this)) return
        val cefrium = state as? CefriumState ?: return
        currentUrl = cefrium.currentUri
        val live = liveBrowser()
        if (live == null) {
            pendingState = cefrium
            pendingUrl = null
            ensureBrowser()
        } else {
            applyState(live, cefrium)
        }
    }

    /** El historial solo se restaura en un navegador sin navegar; si no, se carga la dirección. */
    private fun applyState(target: CefriumBrowser, state: CefriumState) {
        if (target.isClosed || TabRouting.preparing(this)) return
        applyUserAgent(target, state.currentUri)
        val navigation = state.navigation
        val fresh = runCatching { !target.canGoBack() && !target.canGoForward() && target.url.isNullOrEmpty() }.getOrDefault(false)
        if (navigation != null && fresh && runCatching { target.restoreNavigationState(navigation) }.isSuccess) return
        state.currentUri?.let { runCatching { target.loadUrl(it) } }
    }

    override fun saveAsPdf(onResult: (InputStream?, Throwable?) -> Unit) {
        val target = liveBrowser() ?: return onResult(null, IllegalStateException("La pestaña no está abierta"))
        val file = File.createTempFile("nexo-page", ".pdf", appContext.cacheDir)
        runCatching {
            target.printToPdf(file.path) { path, success ->
                if (!success) {
                    file.delete()
                    onResult(null, IllegalStateException("Chromium no pudo generar el PDF"))
                    return@printToPdf
                }
                val stream = object : FileInputStream(path ?: file.path) {
                    override fun close() {
                        super.close()
                        file.delete()
                    }
                }
                onResult(stream, null)
            }
        }.onFailure { err ->
            file.delete()
            onResult(null, err)
        }
    }

    override fun printWithSystemDialog(activity: Activity, title: String): Boolean {
        val target = liveBrowser() ?: return false
        val manager = activity.getSystemService(PrintManager::class.java) ?: return false
        return runCatching {
            manager.print(title, target.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
            true
        }.getOrDefault(false)
    }

    override fun close() {
        if (closed) return
        closed = true
        docGen.incrementAndGet()
        observer = null
        ExtensionManager.forgetSession(this)
        com.nexo.browser.TranslatorManager.forget(this)
        com.nexo.browser.extensions.nxr.NxrRuntime.forgetSession(this)
        shield.dispose()
        main.removeCallbacks(stateTask)
        releaseBrowser()
        host?.detach(this)
    }

    // --- Ayudas ---

    /** Estado de los medios con sonido que informa el puente de la página (hilo principal). */
    internal fun onMediaState(active: Boolean, playing: Boolean, title: String?) {
        val observer = observer ?: return
        if (!active) {
            if (!mediaActive) return
            mediaActive = false
            mediaPlaying = false
            mediaTitle = null
            observer.onMedia(this, MediaEvent.DEACTIVATED, media, null)
            return
        }
        if (!mediaActive) {
            mediaActive = true
            observer.onMedia(this, MediaEvent.ACTIVATED, media, null)
        }
        if (!title.isNullOrBlank() && title != mediaTitle) {
            mediaTitle = title
            observer.onMedia(this, MediaEvent.METADATA, media, title)
        }
        if (playing != mediaPlaying) {
            mediaPlaying = playing
            observer.onMedia(this, if (playing) MediaEvent.PLAY else MediaEvent.PAUSE, media, null)
        }
    }

    /** Ejecuta [script] en el marco principal, fuera de la CSP de la página; [done] en el hilo principal. */
    internal fun evaluateInMainFrame(script: String, done: () -> Unit) {
        onMain {
            val target = liveBrowser()
            if (target == null) {
                done()
                return@onMain
            }
            runCatching { target.evaluateJavaScript(script) { done() } }.onFailure { done() }
        }
    }

    /**
     * Ejecuta [script] en el marco principal y devuelve su valor serializado como JSON directamente
     * a Kotlin mediante `CefriumBrowser.evaluateJavaScript(script, ValueCallback<String>)`, sin
     * exponer mensajes ni tokens en `window.cefriumQuery`.
     */
    internal fun evaluateJsonInMainFrame(script: String, onResult: (String?) -> Unit) {
        onMain {
            val target = liveBrowser()
            if (target == null) {
                onResult(null)
                return@onMain
            }
            runCatching {
                target.evaluateJavaScript(script) { value -> onResult(value) }
            }.onFailure { onResult(null) }
        }
    }

    internal fun clearCache(): Boolean =
        runCatching { liveBrowser()?.clearCache() }.isSuccess

    internal fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        runCatching { liveBrowser()?.onActivityResult(requestCode, resultCode, data) == true }.getOrDefault(false)

    internal fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            liveBrowser()?.onRequestPermissionsResult(requestCode, permissions as Array<String>, grantResults) == true
        }.getOrDefault(false)

    /** Solo HTTPS: las navegaciones principales a http:// pasan a https://, salvo localhost y red local. */
    private fun upgrade(url: String): String {
        if (!BrowserPrefs.httpsOnly(appContext) || !url.startsWith("http://", ignoreCase = true)) return url
        val host = runCatching { java.net.URI(url).host?.lowercase()?.trim('[', ']') }.getOrNull().orEmpty()
        if (isLocalOrPrivateHost(host)) return url
        return "https://" + url.substring(7)
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) return true
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

    private fun scheduleState() {
        main.removeCallbacks(stateTask)
        main.postDelayed(stateTask, 400L)
    }

    private fun emitState() {
        if (closed) return
        val observer = observer ?: return
        val target = liveBrowser() ?: return
        val navigation = runCatching { target.serializeNavigationState() }.getOrNull()
        val url = currentUrl ?: runCatching { target.url }.getOrNull()
        observer.onStateChange(this, CefriumState.of(url, navigation))
    }

    private fun emitContext(link: String?, image: String?, title: String?) {
        if (link.isNullOrBlank() && image.isNullOrBlank()) return
        observer?.onContextMenu(this, EngineContextElement(link, image, title, null, title))
    }

    /** Ventana nueva pedida por esta pestaña: Nexo decide; si no la acepta, se cierra. */
    private fun onPopup(popup: CefriumBrowser, url: String, userGesture: Boolean) {
        fun drop() { main.post { runCatching { if (!popup.isClosed) popup.close() } } }
        val observer = observer ?: return drop()
        // Ventanas sin toque del usuario: se bloquean (popunders).
        if (!userGesture) {
            PagePrompts.popupBlocked(this)
            return drop()
        }
        // Ventana hacia una red de "popunder" (listas $popup o redes de ventanas emergentes).
        if (PhantomShield.blocksPopup(shield, url)) {
            PagePrompts.popupBlocked(this)
            return drop()
        }
        val request = EngineLoadRequest(url, hasUserGesture = true, isDirectNavigation = false, opensNewWindow = true)
        fun adopt() {
            val opener = this.observer ?: return drop()
            CefriumEngine.adopting = popup to url
            val session = try {
                opener.onNewWindow(this, url)
            } finally {
                CefriumEngine.adopting = null
            }
            if (session == null || (session as? CefriumEngineSession)?.browser !== popup) {
                session?.close()
                drop()
            }
        }
        when (observer.onLoadRequest(this, request) { allow -> if (allow && !closed) adopt() else drop() }) {
            LoadDecision.DENY -> drop()
            LoadDecision.PENDING -> Unit
            LoadDecision.DEFAULT, LoadDecision.ALLOW -> adopt()
        }
    }

    private inner class Navigation : CefriumNavigationClient {
        /** Navegación de la página principal: solo HTTPS, decisión de Nexo y URL sin rastreo. True la cancela. */
        override fun onBeforeNavigate(browser: CefriumBrowser, request: CefriumNavigationRequest): Boolean {
            if (TabRouting.preparing(this@CefriumEngineSession)) return true
            if (!request.isForMainFrame) return false
            val url = request.url ?: return false
            // Esquema oscuro del sitio de destino antes de que llegue su documento (también tras redirecciones).
            if (!ExtensionStoreResolver.usesStoreDesktopProfile(url)) storeDesktopOptOut = false
            applyUserAgent(browser, url)
            applyDarkFor(browser, url)
            val observer = observer ?: return true

            val isGet = request.method.equals("GET", ignoreCase = true)
            navigationIsGet = isGet
            val upgraded = upgrade(url)
            if (upgraded != url) {
                if (isGet && (lastUpgradedHttpUrl == null || request.hasGesture())) {
                    lastUpgradedHttpUrl = url
                    main.post { if (!closed && !browser.isClosed) runCatching { browser.loadUrl(upgraded) } }
                } else {
                    main.post { if (!closed) TabManager.notifyMessage("El modo Solo HTTPS bloqueó una navegación por HTTP.") }
                }
                return true
            }

            val engineRequest = EngineLoadRequest(url, request.hasGesture(), isDirectNavigation = false, opensNewWindow = false)
            val cleaned = if (isGet) PhantomShield.cleanNavigation(url) else url
            val decision = observer.onLoadRequest(this@CefriumEngineSession, engineRequest) { allow ->
                if (allow && isGet && !closed && !browser.isClosed) runCatching { browser.loadUrl(cleaned) }
            }
            return when (decision) {
                // Sin parámetros de rastreo (utm_*, gclid, fbclid…): se carga la dirección limpia.
                LoadDecision.DEFAULT, LoadDecision.ALLOW -> if (cleaned != url) {
                    main.post { if (!closed && !browser.isClosed) runCatching { browser.loadUrl(cleaned) } }
                    true
                } else false
                LoadDecision.DENY, LoadDecision.PENDING -> true
            }
        }

        override fun onUrlChanged(browser: CefriumBrowser, url: String?) {
            val value = url ?: return
            currentUrl = value
            applyDarkFor(browser, value)
            applyZoomFor(browser, value)
            if (!isPrivate) com.nexo.browser.SitePermissionStore.applyAllToBrowser(appContext, browser, value)
            shield.onLocation(value)
            if (!browser.isClosed) {
                com.nexo.browser.extensions.nxr.NxrRuntime.onUrlChanged(this@CefriumEngineSession, value)
            }
            val observer = observer ?: return
            observer.onLocationChange(this@CefriumEngineSession, value, false)
            if (!browser.isClosed) {
                observer.onCanGoBack(this@CefriumEngineSession, runCatching { browser.canGoBack() }.getOrDefault(false))
                observer.onCanGoForward(this@CefriumEngineSession, runCatching { browser.canGoForward() }.getOrDefault(false))
            }
            scheduleState()
        }

        override fun onLoadStart(browser: CefriumBrowser, url: String?) {
            host?.onDocumentChanged(browser)
            mainFrameError = false
            sslNoticeFor = null
            unresponsiveNotified = false
            docGen.incrementAndGet()
            loadStartedAtMs = android.os.SystemClock.uptimeMillis()
            EngineDiagnostics.recordDocumentLoad()
            EngineDiagnostics.log("carga iniciada: ${url?.let { com.nexo.browser.shield.UrlParts.host(it) } ?: "?"}")
            // Documento nuevo: sus medios y sus marcos los volverá a informar el puente.
            onMediaState(active = false, playing = false, title = null)
            com.nexo.browser.PageBridge.onNewDocument(this@CefriumEngineSession)
            com.nexo.browser.TranslatorManager.forget(this@CefriumEngineSession)
            com.nexo.browser.extensions.nxr.NxrRuntime.onNewDocument(this@CefriumEngineSession)
            observer?.onPageStart(this@CefriumEngineSession, url ?: currentUrl.orEmpty())
        }

        override fun onLoadingStateChanged(browser: CefriumBrowser, loading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
            val observer = observer ?: return
            observer.onCanGoBack(this@CefriumEngineSession, canGoBack)
            observer.onCanGoForward(this@CefriumEngineSession, canGoForward)
            if (!loading) {
                if (loadStartedAtMs > 0L) {
                    val elapsed = (android.os.SystemClock.uptimeMillis() - loadStartedAtMs).coerceAtLeast(0L)
                    lastLoadDurationMs = elapsed
                    EngineDiagnostics.recordLoadDuration(elapsed)
                    loadStartedAtMs = 0L
                }
                observer.onPageStop(this@CefriumEngineSession, !mainFrameError)
                if (!mainFrameError && !browser.isClosed) {
                    com.nexo.browser.extensions.nxr.NxrRuntime.onDocumentIdle(
                        this@CefriumEngineSession,
                        currentUrl ?: runCatching { browser.url }.getOrNull()
                    )
                }
                scheduleState()
            }
        }

        override fun onProgressChanged(browser: CefriumBrowser, percent: Int) {
            observer?.onProgress(this@CefriumEngineSession, percent)
        }

        override fun onDocumentAvailable(browser: CefriumBrowser) {
            lastUpgradedHttpUrl = null
            host?.onDocumentAvailable(browser)
            observer?.onFirstContentfulPaint(this@CefriumEngineSession)
            if (!browser.isClosed) {
                com.nexo.browser.extensions.nxr.NxrRuntime.onDocumentAvailable(
                    this@CefriumEngineSession,
                    currentUrl ?: runCatching { browser.url }.getOrNull()
                )
            }
        }

        override fun onLoadError(browser: CefriumBrowser, error: CefriumLoadError) {
            mainFrameError = true
            // Solo el host: la URL completa puede llevar datos personales.
            val host = error.failedUrl?.let { com.nexo.browser.shield.UrlParts.host(it) } ?: "?"
            EngineDiagnostics.log("error de carga ${error.errorCode} (${error.description}) en $host")
        }

        override fun onRenderProcessUnresponsive(browser: CefriumBrowser) {
            val host = currentUrl?.let { com.nexo.browser.shield.UrlParts.host(it) } ?: "?"
            EngineDiagnostics.log("pestaña sin respuesta en $host")
            main.post {
                if (closed || this@CefriumEngineSession.browser !== browser) return@post
                if (!unresponsiveNotified && TabManager.activeSession() === this@CefriumEngineSession) {
                    unresponsiveNotified = true
                    TabManager.notifyMessage("La página no responde. Espera un momento o recarga la pestaña.")
                }
            }
        }

        override fun onRenderProcessResponsive(browser: CefriumBrowser) {
            val host = currentUrl?.let { com.nexo.browser.shield.UrlParts.host(it) } ?: "?"
            EngineDiagnostics.log("pestaña volvió a responder en $host")
            main.post { unresponsiveNotified = false }
        }

        /**
         * El proceso que dibuja la página terminó. Puede compartirlo más de una
         * pestaña: Cefrium avisa a cada una y cada una se recupera sola.
         */
        override fun onRenderProcessTerminated(browser: CefriumBrowser, status: CefriumRendererTerminationStatus, errorCode: Int) {
            val exit = when (status) {
                CefriumRendererTerminationStatus.KILLED, CefriumRendererTerminationStatus.OOM -> ProcessExit.KILL
                else -> ProcessExit.CRASH
            }
            EngineDiagnostics.recordRendererExit(exit == ProcessExit.KILL)
            EngineDiagnostics.log("el proceso de la página terminó: $status (código $errorCode)")
            main.post {
                if (this@CefriumEngineSession.browser !== browser) return@post
                host?.onRendererTerminated(browser)
                observer?.onProcessExit(this@CefriumEngineSession, exit)
            }
        }

        /** Certificado no válido: la conexión se cancela siempre, sin excepción. */
        override fun onSslError(browser: CefriumBrowser, error: CefriumSslError, callback: CefriumBrowser.SslErrorCallback) {
            callback.cancel()
            val host = error.url?.let { com.nexo.browser.shield.UrlParts.host(it) } ?: return
            if (sslNoticeFor != host && TabManager.activeSession() === this@CefriumEngineSession) {
                sslNoticeFor = host
                TabManager.notifyMessage("Nexo bloqueó una conexión no segura: el certificado de $host no es válido.")
            }
        }

        override fun onHttpAuthRequest(
            browser: CefriumBrowser, host: String?, realm: String?, port: Int, isProxy: Boolean,
            callback: CefriumBrowser.HttpAuthCallback
        ) {
            PagePrompts.httpAuth(this@CefriumEngineSession, callback, host.orEmpty(), realm.orEmpty())
        }
    }

    private inner class Chrome : CefriumChromeClient {
        override fun onTitleChanged(browser: CefriumBrowser, title: String?) {
            observer?.onTitle(this@CefriumEngineSession, title)
        }

        override fun onFullscreenChanged(browser: CefriumBrowser, fullscreen: Boolean) {
            fullScreen = fullscreen
            observer?.onFullScreen(this@CefriumEngineSession, fullscreen)
        }

        override fun onFindResult(browser: CefriumBrowser, activeMatch: Int, matches: Int) {
            findCallback?.invoke(FindResult(matches > 0, if (matches > 0) activeMatch else 0, matches))
        }

        override fun onJsDialog(
            browser: CefriumBrowser, type: CefriumJsDialogType, message: String?, defaultText: String?,
            callback: CefriumBrowser.JsDialogCallback
        ): Boolean = when (type) {
            CefriumJsDialogType.ALERT -> PagePrompts.alert(this@CefriumEngineSession, message.orEmpty(), callback)
            CefriumJsDialogType.CONFIRM -> PagePrompts.confirm(this@CefriumEngineSession, message.orEmpty(), callback)
            CefriumJsDialogType.PROMPT -> PagePrompts.prompt(this@CefriumEngineSession, message.orEmpty(), defaultText, callback)
        }

        override fun onBeforeUnloadDialog(
            browser: CefriumBrowser, message: String?, isReload: Boolean, callback: CefriumBrowser.JsDialogCallback
        ): Boolean = PagePrompts.beforeUnload(this@CefriumEngineSession, callback)

        override fun onShowFileChooser(
            browser: CefriumBrowser, params: CefriumFileChooserParams, callback: CefriumFileChooserCallback
        ): Boolean = PagePrompts.chooseFiles(this@CefriumEngineSession, callback, params)

        /** Pulsación larga sobre un enlace o una imagen: menú de Nexo. El resto, el de Chromium. */
        override fun onContextMenu(browser: CefriumBrowser, menu: CefriumContextMenu): Boolean {
            val link = menu.linkUrl?.takeIf { it.isNotBlank() }
            val image = menu.sourceUrl?.takeIf { it.isNotBlank() && menu.mediaType == CefriumContextMenu.MediaType.IMAGE }
            if (link == null && image == null) return false
            menu.cancel()
            emitContext(link, image, menu.selectionText?.takeIf { it.isNotBlank() })
            return true
        }

        override fun onCreateWindow(browser: CefriumBrowser, popup: CefriumBrowser, url: String?, userGesture: Boolean): Boolean {
            // Siempre se acepta aquí: devolver false cargaría la dirección en esta pestaña.
            onPopup(popup, url ?: "about:blank", userGesture)
            return true
        }

        override fun onCloseWindow(browser: CefriumBrowser, popup: CefriumBrowser) {
            val session = CefriumEngine.sessionOf(popup) ?: return run { runCatching { if (!popup.isClosed) popup.close() } }
            session.observer?.onCloseRequest(session)
        }

        override fun onPermissionRequest(browser: CefriumBrowser, request: CefriumPermissionRequest) {
            SitePermissions.onPermissionRequest(this@CefriumEngineSession, request)
        }

        override fun onPermissionRequestCanceled(browser: CefriumBrowser, request: CefriumPermissionRequest) {
            SitePermissions.onPermissionRequestCanceled(this@CefriumEngineSession)
        }

        /** El botón Atrás del teclado físico lo gestiona Nexo, no la página. */
        override fun shouldOverrideKeyEvent(browser: CefriumBrowser, event: KeyEvent): Boolean = false
    }

    internal fun startDownload(url: String): Boolean {
        if (TabRouting.preparing(this) || !com.nexo.browser.UrlSafety.isWebUrl(url)) return false
        val target = liveBrowser() ?: return false
        return runCatching { target.startDownload(upgrade(url)) }.isSuccess
    }

    /** Chromium conserva método, credenciales y proxy al guardar la petición original. */
    private data class NativeDownload(
        val file: CefriumFileDownload,
        val progress: com.nexo.browser.ActiveDownload
    )

    private inner class Downloads : CefriumDownloadClient {
        private val files = HashMap<Int, NativeDownload>()

        override fun onBeforeDownload(
            browser: CefriumBrowser, item: CefriumBrowser.DownloadItem, callback: CefriumBrowser.BeforeDownloadCallback
        ) {
            val url = item.url.orEmpty()
            val session = this@CefriumEngineSession
            if (closed || TabRouting.preparing(session) ||
                observer?.allowDownload(session, url, item.suggestedName, item.mimeType) != true
            ) {
                callback.cancel()
                return
            }
            val target = runCatching { CefriumFileDownload.reserve(appContext, item.id) }.getOrElse {
                callback.cancel()
                TabManager.notifyMessage("No se pudo preparar el archivo de descarga.")
                return
            }
            val file = CefriumFileDownload(url, target, item.suggestedName, item.mimeType)
            val scope = com.nexo.browser.SitePermissionStore.scope(isPrivate, params.contextId)
            val progress = com.nexo.browser.DownloadStore.startNative(appContext, item.suggestedName, item.totalBytes, scope) {
                if (!browser.isClosed) browser.cancelDownload(item.id)
            }
            files[item.id] = NativeDownload(file, progress)
            try {
                callback.allow(target.path)
            } catch (error: Exception) {
                files.remove(item.id)
                com.nexo.browser.DownloadStore.finishNative(progress)
                file.discard()
                EngineDiagnostics.log("No se pudo iniciar la descarga de Chromium", error)
                TabManager.notifyMessage("No se pudo iniciar la descarga.")
                return
            }
            TabManager.notifyMessage("Descarga iniciada")
        }

        override fun onDownloadUpdated(browser: CefriumBrowser, item: CefriumBrowser.DownloadItem) {
            if (browser !== this@CefriumEngineSession.browser) return
            val pending = files[item.id] ?: return
            pending.progress.received.set(item.receivedBytes.coerceAtLeast(0))
            when {
                item.isComplete -> {
                    files.remove(item.id)
                    com.nexo.browser.DownloadStore.finishNative(pending.progress)
                    val observer = observer
                    if (observer == null || closed || pending.progress.cancelled) pending.file.discard()
                    else observer.onDownload(this@CefriumEngineSession, pending.file)
                }
                item.isCanceled || item.isInterrupted -> {
                    files.remove(item.id)
                    com.nexo.browser.DownloadStore.finishNative(pending.progress)
                    pending.file.discard()
                    TabManager.notifyMessage(if (item.isCanceled) "Descarga cancelada" else "Se interrumpió la descarga.")
                }
            }
        }

        fun discardAll() {
            files.values.forEach { pending ->
                com.nexo.browser.DownloadStore.finishNative(pending.progress)
                pending.file.discard()
            }
            files.clear()
        }
    }

    companion object {
        private val nextSessionUid = java.util.concurrent.atomic.AtomicLong(0L)
    }
}

/** Identificación del navegador ante los sitios. */
internal object UserAgents {
    private fun chromeVersion(): String =
        runCatching { Cefrium.getChromiumVersion() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "158.0.0.0"

    /** La de Chrome para Android (la propia de Chromium, sin cambios). */
    fun mobile(): String =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeVersion()} Mobile Safari/537.36"

    fun desktop(): String = desktop("Chrome/" + chromeVersion())

    /**
     * Client Hints coherentes con modo escritorio (`Sec-CH-UA-Mobile: ?0`,
     * `Sec-CH-UA-Platform: "Linux"`), para que sitios y tiendas que leen
     * `navigator.userAgentData` no reciban hints vacíos ni de móvil.
     */
    fun desktopMetadata(): CefriumUserAgentMetadata {
        val full = chromeVersion()
        val major = full.substringBefore('.').ifBlank { "158" }
        return CefriumUserAgentMetadata.Builder()
            .addBrand("Chromium", major)
            .addBrand("Google Chrome", major)
            .addBrand("Not_A Brand", "24")
            .setFullVersion(full)
            .setPlatform("Linux")
            .setPlatformVersion("")
            .setArchitecture("x86")
            .setModel("")
            .setMobile(false)
            .setBitness("64")
            .addFormFactor("Desktop")
            .build()
    }

    /** Quita las marcas de WebView ("; wv" y "Version/4.0") de una identificación heredada. */
    fun mobile(base: String): String =
        base.replace("; wv)", ")").replace(Regex("""Version/\d+(\.\d+)* """), "")

    fun desktop(base: String): String {
        val chrome = Regex("""Chrome/([\d.]+)""").find(base)?.groupValues?.get(1) ?: "158.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }
}
