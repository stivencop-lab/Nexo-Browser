package com.nexo.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nexo.browser.engine.EngineContextElement
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineView

class MainActivity :
    androidx.activity.ComponentActivity(),
    TabManager.Listener,
    BrowserMediaController.Listener,
    LiveCaptions.Listener,
    BrowserHost,
    ThemeManager.InsetsOwner {
    private lateinit var captionsButton: Button
    private lateinit var captionView: TextView
    private var lastCaptionMessage: String? = null
    /** Vista de la página (la del motor activo) y su interfaz de motor. */
    private lateinit var pageView: View
    private lateinit var engineView: EngineView
    private lateinit var omniboxCapsule: LinearLayout
    private lateinit var omnibox: EditText
    private lateinit var suggestions: OmniboxSuggestions
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: Button
    private lateinit var aiButton: ImageButton
    private lateinit var nexoAiPill: com.nexo.browser.ai.NexoAiPillView
    private var aiControllerObserver: ((com.nexo.browser.ai.NexoAiModels.ControllerSnapshot) -> Unit)? = null
    /** Resultados del escáner de páginas: aviso discreto en la barra o pantalla de bloqueo. */
    private val malwareObserver = com.nexo.browser.shield.MalwareScan.Observer { tabId, result ->
        if (isFinishing || isDestroyed) return@Observer
        val tab = TabManager.activeTab()?.takeIf { it.id == tabId } ?: return@Observer
        styleTabToolsButton(tab)
        if (result.decision.block && !com.nexo.browser.shield.MalwareWarning.isShowing() &&
            !com.nexo.browser.shield.MalwareScan.isBypassed(result.origin)
        ) {
            com.nexo.browser.shield.MalwareScan.recordBlocked(this)
            com.nexo.browser.shield.MalwareWarning.show(this, tab, result)
        }
    }

    /** Tras cambiar la confianza de un sitio, el botón de herramientas pierde el aviso. */
    internal fun refreshTabTools(tab: BrowserTab) = styleTabToolsButton(tab)
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var compactBackButton: ImageButton
    private lateinit var homeButton: ImageButton
    private lateinit var reloadButton: ImageButton
    private lateinit var newTabButton: ImageButton
    private lateinit var browserDock: GestureDock
    private lateinit var dockGesturePill: View
    private lateinit var clearOmniboxButton: Button
    private lateinit var privateBanner: TextView
    private lateinit var extensionInstallBanner: LinearLayout
    private lateinit var extensionBannerText: TextView
    private lateinit var extensionBannerAction: Button
    private lateinit var extensionBannerDismiss: ImageButton
    private var currentStoreExtension: com.nexo.browser.extensions.hub.ExtensionStoreResolver.ResolvedTarget.StoreExtension? = null
    private val dismissedStoreKeys = HashSet<String>()
    private lateinit var miniPlayerButton: Button
    private lateinit var root: View
    private lateinit var browserFrame: PullRefreshLayout
    private lateinit var startPage: StartPageView
    private var startPageKey = ""

    /**
     * Mini video nativo: rectángulo del video (px de la vista de página) que se
     * amplía para llenar la ventana PiP. Mientras no sea null, la vista de página
     * conserva su tamaño, así la página NO se redimensiona (YouTube pausaba
     * y reorganizaba la página al achicarse) y no hace falta modificar su CSS.
     */
    private var pipCrop: Rect? = null
    private lateinit var topBar: GestureDock
    private lateinit var navBar: LinearLayout
    // Nexo UI 7.0: Liquid Split Chrome.
    private lateinit var statusScrim: View
    private lateinit var topOverlay: LinearLayout
    private lateinit var bottomChrome: LinearLayout
    private var webBackdrop: NexoGlassBackdrop? = null
    private var chromeGlassKey = ""
    private var chromeIslandKey = ""
    private var privateBannerKey = ""
    private val glassSettingsChanged: () -> Unit = {
        invalidateChromeGlassCache()
        styleChromeGlass()
    }
    private lateinit var chromeIsland: View
    private lateinit var chromeCluster: LinearLayout
    private lateinit var miniPill: LinearLayout
    private lateinit var miniLock: ImageView
    private lateinit var miniDomain: TextView
    private lateinit var pillReloadButton: ImageButton
    private var safeTop = 0
    private var safeBottom = 0
    private var imeBottom = 0
    /** Scroll Compacto: el cromo se pliega en una mini-píldora centrada. */
    private var chromeCompact = false
    private lateinit var viewModel: BrowserViewModel
    private var attachedSession: EngineSession? = null
    private var settingsFingerprint = ""
    private var themeKey = ""
    private var mediaNotificationPermissionAsked = false
    private var suppressAutoPipOnce = false
    private var imeVisible = false
    private var dockScrollHidden = false
    private var lastDockToggleMs = 0L
    private var backCallbackRegistered = false
    private val predictiveBackCallback: Any? by lazy {
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback {
                if (!handleBackNavigation()) finish()
            }
        } else null
    }

    // Estado capturado ANTES de que Android/YouTube cambien
    // la Activity de foreground a PiP/background.
    private var pipPlaybackWasActive = false
    private var backgroundPlaybackWasActive = false

    // onStop antes de salir de PiP = el usuario cerró la ventana mini.
    private var activityStopped = false

    // Un sitio puso un elemento (normalmente el video) en pantalla completa.
    private var fullScreenActive = false
    private var keepScreenOnApplied = false
    private var fullscreenMediaOverlay: com.nexo.browser.media.NexoMediaEngine.FullscreenOverlay? = null
    private var pendingPipEntry: Runnable? = null
    private var wasBackgrounded = false

    // Permisos pedidos por páginas web.
    private val permissionCallbacks = mutableMapOf<Int, (Boolean) -> Unit>()
    private var nextPermissionRequest = REQUEST_SITE_PERMISSIONS

    // Buscar en la página.
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private var findGeneration = 0L
    private var findDocument = -1L
    private var findPrivacyScope = ""

    /** Última miniatura automática por pestaña: dirección y momento. */
    private val lastAutoPreview = mutableMapOf<String, Pair<String, Long>>()
    private val idlePreview = Runnable { captureCurrentTabPreview() }

    override val hostActivity: Activity get() = this

    /** Botones reproducir/pausar de la ventana PiP. */
    private val pipControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_PIP_CONTROL) return
            val command = intent.getStringExtra(EXTRA_PIP_COMMAND) ?: return
            val tab = TabManager.activeTab() ?: return

            if (!ExtensionManager.sendPipCommand(tab.session, command)) {
                when (command) {
                    PIP_COMMAND_PLAY -> BrowserMediaController.play(tab.id)
                    PIP_COMMAND_PAUSE -> BrowserMediaController.pause(tab.id)
                }
            }
            // El estado real llega desde la página; refrescar el icono en breve.
            pageView.postDelayed({ updatePipParams() }, 350)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (MigrationMode.enabled(this)) {
            super.onCreate(savedInstanceState)
            startActivity(Intent(this, MigrationActivity::class.java))
            finish()
            return
        }
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // El dock y el campo de dirección deben reacomodarse encima del IME.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        configureSystemInsets()
        // Historial e índice de sugerencias listos sin leer disco en el hilo principal.
        HistoryStore.prewarm(this)
        PerfLog.attach(this)

        CrashLog.consume(this)?.let { crash ->
            // Tras actualizar se muestra el cierre guardado por la versión
            // anterior: indicarlo para no confundirlo con un fallo nuevo.
            val current = CrashLog.isFromCurrentVersion(this, crash)
            AlertDialog.Builder(this)
                .setTitle(
                    if (current) "Nexo detectó un cierre inesperado"
                    else "Cierre de una versión anterior de Nexo"
                )
                .setMessage(
                    if (current) crash.take(8000)
                    else "Este informe es de antes de actualizar; la versión instalada ya puede " +
                        "incluir la corrección.\n\n" + crash.take(8000)
                )
                .setNegativeButton("Cerrar", null)
                .setPositiveButton("Copiar") { _, _ ->
                    copyText("Nexo crash", crash)
                }.show()
        }

        viewModel = BrowserViewModel(applicationContext)
        BrowserHosts.attach(this)
        bindViews()
        BrowserMediaController.attach(this)
        LiveCaptions.attach(this)
        applyTheme()
        configureUi()
        GlassPreferences.addListener(glassSettingsChanged)
        ThemeManager.takeInitialFocus(root)
        TabManager.initialize(this)
        TabManager.attach(this)
        ExtensionManager.attachPromptActivity(this)
        ContextCompat.registerReceiver(
            this,
            pipControlReceiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Al recrear la Activity (rotación, cambio de tema) no repetir la
        // navegación del intent original.
        if (savedInstanceState == null) handleNavigationIntent(intent)
    }

    /**
     * Target SDK 35 dibuja la ventana bajo las barras del sistema. El dock
     * contiene controles interactivos, así que no puede quedar debajo de la
     * barra de navegación ni del teclado: se desplaza con el inset inferior.
     */
    /**
     * Lienzo edge-to-edge: cada capa toma su inset una sola vez. Los recortes laterales van al
     * contenido; la barra de estado al velo y a los avisos superiores; la navegación y el
     * teclado al cromo inferior. La página web empieza bajo la barra de estado (Cefrium no
     * expone insets de viewport) y fluye por detrás del cromo inferior de cristal.
     */
    private fun configureSystemInsets() {
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val wasIme = imeVisible
            imeVisible = ime > 0
            if (imeVisible && !wasIme) { setDockScrollHidden(false); setChromeCompact(false) }
            view.setPadding(safe.left, 0, safe.right, 0)
            safeTop = safe.top
            safeBottom = safe.bottom
            imeBottom = ime
            layoutEdgeToEdge()
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(content)
        // Se llama antes de bindViews(): se busca la vista directamente.
        findViewById<View>(R.id.bottomChrome).addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) layoutEdgeToEdge()
        }
    }

    private val liquidChrome: Boolean
        get() = BrowserPrefs.chromeStyle(this) == BrowserPrefs.CHROME_LIQUID

    /** Reparte insets y huecos entre página, velo y cromo según el modo actual. */
    private fun layoutEdgeToEdge() {
        if (!::bottomChrome.isInitialized || !::startPage.isInitialized || !::nexoAiPill.isInitialized || !::browserFrame.isInitialized) return
        val hideBars = fullScreenActive || isInPictureInPictureMode
        val top = if (hideBars) 0 else safeTop
        val bottomInset = maxOf(safeBottom, imeBottom)
        if (statusScrim.layoutParams.height != top) {
            statusScrim.layoutParams = statusScrim.layoutParams.apply { height = top }
        }
        if (topOverlay.paddingTop != top) topOverlay.setPadding(0, top, 0, 0)
        val bottomPad = if (hideBars) 0 else bottomInset + NexoUi.dp(this, 2f)
        if (bottomChrome.paddingBottom != bottomPad) bottomChrome.setPadding(0, 0, 0, bottomPad)
        val startVisible = startPage.visibility == View.VISIBLE
        // Página: bajo la barra de estado; por detrás del cromo Liquid; sobre el teclado (solo para campos web, no al escribir en omnibox/buscar).
        val nativeEditing = (::omnibox.isInitialized && omnibox.hasFocus()) ||
            (::findInput.isInitialized && findInput.hasFocus())
        val pageTop = if (hideBars || startVisible || liquidChrome) 0 else top
        val pageBottom = when {
            hideBars -> 0
            imeBottom > 0 && !nativeEditing -> imeBottom
            !liquidChrome && bottomChrome.visibility == View.VISIBLE -> bottomChrome.height
            else -> 0
        }
        (browserFrame.layoutParams as android.widget.FrameLayout.LayoutParams).let { lp ->
            if (lp.topMargin != pageTop || lp.bottomMargin != pageBottom) {
                lp.topMargin = pageTop
                lp.bottomMargin = pageBottom
                browserFrame.layoutParams = lp
            }
        }
        // La página de inicio sí llega hasta arriba: reserva su propio aire.
        startPage.setSafeArea(top, if (hideBars) 0 else bottomChrome.height)
        // Píldora AI y chips de video siempre por encima del cromo flotante y coordinados con el plegado.
        val lift = if (hideBars) NexoUi.dp(this, 12f) else (bottomChrome.height - pageBottom).coerceAtLeast(0) + NexoUi.dp(this, 6f)
        (nexoAiPill.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            if (lp.bottomMargin != lift) { lp.bottomMargin = lift; nexoAiPill.layoutParams = lp }
        }
        (findViewById<View>(R.id.videoChips).layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            if (lp.bottomMargin != lift) { lp.bottomMargin = lift; findViewById<View>(R.id.videoChips).layoutParams = lp }
        }
        syncFloatingControls(animated = false)
    }

    /**
     * Alinea suavemente Mini video, CC y la píldora de Nexo AI cuando la barra inferior se pliega
     * al hacer scroll, y compacta la píldora AI para evitar solapamientos en pantallas estrechas.
     */
    private fun syncFloatingControls(animated: Boolean) {
        if (!::bottomChrome.isInitialized || !::nexoAiPill.isInitialized ||
            !::miniPlayerButton.isInitialized || !::captionsButton.isInitialized
        ) return
        val videoChips = findViewById<View>(R.id.videoChips) ?: return
        val hideBars = fullScreenActive || isInPictureInPictureMode
        val collapsed = !hideBars && (chromeCompact || dockScrollHidden)
        val dropPx = when {
            hideBars -> 0f
            chromeCompact && liquidChrome -> {
                val compactHeight = NexoUi.dp(this, 48f) + safeBottom
                (bottomChrome.height - compactHeight).coerceAtLeast(0).toFloat()
            }
            dockScrollHidden && !liquidChrome -> {
                browserDock.height.takeIf { it > 0 }?.toFloat() ?: NexoUi.dp(this, 52f).toFloat()
            }
            else -> 0f
        }
        val chipsVisible = miniPlayerButton.visibility == View.VISIBLE || captionsButton.visibility == View.VISIBLE
        val narrowScreen = resources.displayMetrics.widthPixels < NexoUi.dp(this, 440f)
        val agentLive = com.nexo.browser.ai.NexoAiController.snapshot().activeAgentRequest != null
        nexoAiPill.setCompact(!agentLive && (collapsed || (chipsVisible && narrowScreen)))
        nexoAiPill.setChromeOffsetY(dropPx, animated = animated)

        val targetScale = if (collapsed) 0.92f else 1f
        val targetAlpha = if (collapsed) 0.88f else 1f
        if (animated) {
            NexoMotion.spring(videoChips, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, dropPx)
            NexoMotion.spring(videoChips, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, targetScale)
            NexoMotion.spring(videoChips, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, targetScale)
            NexoMotion.spring(videoChips, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, targetAlpha)
        } else {
            videoChips.translationY = dropPx
            videoChips.scaleX = targetScale
            videoChips.scaleY = targetScale
            videoChips.alpha = targetAlpha
        }
    }

    /** Sustituye el hueco del layout por la vista de página del motor, en su mismo sitio. */
    private fun installPageView(): View {
        val placeholder = findViewById<View>(R.id.pageView)
        val parent = placeholder.parent as android.view.ViewGroup
        val index = parent.indexOfChild(placeholder)
        val view = com.nexo.browser.engine.Engines.current.createView(this)
        view.id = R.id.pageView
        parent.removeViewAt(index)
        parent.addView(view, index, placeholder.layoutParams)
        return view
    }

    private fun bindViews() {
        root = findViewById(R.id.rootView)
        topBar = findViewById(R.id.topBar)
        statusScrim = findViewById(R.id.statusScrim)
        topOverlay = findViewById(R.id.topOverlay)
        bottomChrome = findViewById(R.id.bottomChrome)
        chromeCluster = findViewById(R.id.chromeCluster)
        chromeIsland = findViewById(R.id.chromeIsland)
        miniPill = findViewById(R.id.miniPill)
        miniLock = findViewById(R.id.miniLock)
        miniDomain = findViewById(R.id.miniDomain)
        pillReloadButton = findViewById(R.id.pillReloadButton)
        navBar = findViewById(R.id.navBar)
        browserDock = findViewById(R.id.browserDock)
        dockGesturePill = findViewById(R.id.dockGesturePill)
        pageView = installPageView()
        engineView = pageView as EngineView
        browserFrame = findViewById(R.id.browserFrame)
        startPage = StartPageView(
            this,
            onOpen = { url -> TabManager.navigate(url) },
            onPrivate = { viewModel.newTab(true) },
            onShield = { openShieldPanel() },
            onExtensions = { openScreen(ExtensionManager.storeScreen()) },
            onAction = { action -> startPageAction(action) }
        ).apply { visibility = View.GONE }
        // Encima de la vista de página y debajo del botón "Mini video".
        browserFrame.addView(
            startPage,
            1,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        browserFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (pipCrop != null && isInPictureInPictureMode) applyPipCrop()
        }
        browserFrame.enabledForPage = {
            BrowserPrefs.pullToRefresh(this) && !isInPictureInPictureMode && !fullScreenActive &&
                startPage.visibility != View.VISIBLE && findBar.visibility != View.VISIBLE &&
                TabManager.activeTab()?.let { !BrowserPrefs.isNexoHome(it.url) } == true
        }
        browserFrame.onRefresh = { TabManager.reload() }
        fullscreenMediaOverlay = com.nexo.browser.media.NexoMediaEngine.FullscreenOverlay(
            activity = this,
            hostContainer = browserFrame,
            onOpenPanel = { com.nexo.browser.media.NexoMediaEngine.showSheet(this) { refreshCaptionsUi() } }
        )
        omniboxCapsule = findViewById(R.id.omniboxCapsule)
        omnibox = findViewById(R.id.omnibox)
        progress = findViewById(R.id.pageProgress)
        tabButton = findViewById(R.id.tabButton)
        aiButton = findViewById(R.id.aiButton)
        nexoAiPill = findViewById(R.id.nexoAiPill)
        com.nexo.browser.ai.NexoAiController.initialize(this)
        nexoAiPill.onRestore = {
            ThemeManager.animateAiSparkTap(aiButton)
            com.nexo.browser.ai.NexoAiSheet.show(this)
        }
        nexoAiPill.onDismissPill = {
            com.nexo.browser.ai.NexoAiController.dismissMinimizedPill()
        }
        nexoAiPill.onStopAgent = {
            if (com.nexo.browser.ai.NexoAiController.cancelActiveAgentRequest()) {
                Toast.makeText(this, "Agente de Nexo detenido.", Toast.LENGTH_SHORT).show()
            }
        }
        aiControllerObserver = { snap ->
            if (!isFinishing && !isDestroyed) {
                ThemeManager.styleAiSparkButton(this, aiButton, snap.indicatorState)
                nexoAiPill.render(this, snap)
                syncFloatingControls(animated = false)
            }
        }
        aiControllerObserver?.let { com.nexo.browser.ai.NexoAiController.observe(it) }
        com.nexo.browser.shield.MalwareScan.addObserver(malwareObserver)
        backButton = findViewById(R.id.backButton)
        forwardButton = findViewById(R.id.forwardButton)
        compactBackButton = findViewById(R.id.compactBackButton)
        homeButton = findViewById(R.id.homeButton)
        reloadButton = findViewById(R.id.reloadButton)
        newTabButton = findViewById(R.id.newTabButton)
        clearOmniboxButton = findViewById(R.id.clearOmniboxButton)
        privateBanner = findViewById(R.id.privateBanner)
        extensionInstallBanner = findViewById(R.id.extensionInstallBanner)
        extensionBannerText = findViewById(R.id.extensionBannerText)
        extensionBannerAction = findViewById(R.id.extensionBannerAction)
        extensionBannerDismiss = findViewById(R.id.extensionBannerDismiss)
        miniPlayerButton = findViewById(R.id.miniPlayerButton)
        captionsButton = findViewById(R.id.captionsButton)
        captionView = findViewById(R.id.captionView)
        // Subtítulos arrastrables: se quedan donde el usuario los suelte.
        CaptionsUi.enableDrag(this, captionView) { refreshCaptionsUi() }
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
    }

    private fun configureUi() {
        findViewById<TextView>(R.id.brandText).setOnClickListener { viewModel.goHome() }
        reloadButton.apply {
            setOnClickListener { TabManager.reload() }
            // Mantener pulsado: recarga completa, sin caché.
            setOnLongClickListener {
                TabManager.reload(bypassCache = true)
                Toast.makeText(this@MainActivity, "Recarga completa (sin caché)", Toast.LENGTH_SHORT).show()
                true
            }
        }
        pillReloadButton.apply {
            setOnClickListener { NexoMotion.pulse(it, 0.88f); TabManager.reload() }
            setOnLongClickListener {
                TabManager.reload(bypassCache = true)
                Toast.makeText(this@MainActivity, "Recarga completa (sin caché)", Toast.LENGTH_SHORT).show()
                true
            }
        }
        miniPill.setOnClickListener { focusOmniboxForEditing() }
        omniboxCapsule.setOnClickListener { focusOmniboxForEditing() }
        val suggestionsView = findViewById<View>(R.id.omniboxSuggestions)
        val glassControls = listOf(chromeIsland, tabButton, miniPill, browserDock, findBar, extensionInstallBanner, privateBanner, suggestionsView)
        val allGlassViews = listOf(chromeIsland, tabButton, miniPill, browserDock, findBar, extensionInstallBanner, privateBanner, suggestionsView, captionsButton, miniPlayerButton, nexoAiPill)
        glassControls.forEach { NexoGlass.follow(it, startPage) }
        val cefPage = engineView as? com.nexo.browser.engine.cefrium.CefriumPageView
        if (cefPage != null) {
            webBackdrop = NexoGlassBackdrop(pageView, {
                cefPage.renderingSurface?.takeIf { !immersive && startPage.visibility != View.VISIBLE }
            }) { allGlassViews.forEach { if (it.visibility == View.VISIBLE) it.invalidate() } }
            nexoAiPill.bindWebBackdrop(webBackdrop, pageView)
            cefPage.onBackdropInvalidated = { webBackdrop?.markDirty() }
            cefPage.onRecoveryStateChanged = { recovering -> webBackdrop?.setPaused(recovering) }
            pageView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) webBackdrop?.markDirty()
            }
        }
        backButton.setOnClickListener { TabManager.goBackOrPrevious() }
        backButton.setOnLongClickListener { showTabHistorySheet(backwards = true) }
        compactBackButton.setOnClickListener { TabManager.goBackOrPrevious() }
        compactBackButton.setOnLongClickListener { showTabHistorySheet(backwards = true) }
        forwardButton.setOnClickListener { TabManager.goForward() }
        forwardButton.setOnLongClickListener { showTabHistorySheet(backwards = false) }
        homeButton.setOnClickListener { viewModel.goHome() }
        newTabButton.apply {
            setOnClickListener { viewModel.newTab(false) }
            // Mantener pulsado: pestaña privada.
            setOnLongClickListener { viewModel.newTab(true); true }
        }
        tabButton.setOnClickListener { showTabsDialog() }
        tabButton.setOnLongClickListener { showTabQuickMenu(); true }
        // Liquid Split Chrome: deslizar la píldora cambia de pestaña; hacia arriba abre el deck.
        topBar.apply {
            interceptOverChildren = true
            gesturesEnabled = {
                liquidChrome && BrowserPrefs.dockGestures(this@MainActivity) && !omnibox.hasFocus() && !fullScreenActive && !isInPictureInPictureMode
            }
            onSwipeTab = { offset -> switchAdjacentTab(offset) }
            onSwipeUp = { showTabsDialog() }
        }
        // Gestos de la barra: a los lados cambia de pestaña, hacia arriba abre el selector.
        browserDock.apply {
            gesturesEnabled = {
                BrowserPrefs.dockGestures(this@MainActivity) && !omnibox.hasFocus() && !fullScreenActive && !isInPictureInPictureMode
            }
            onSwipeTab = { offset -> switchAdjacentTab(offset) }
            onSwipeUp = { showTabsDialog() }
        }
        PageBridge.onScrollDirection = { session, scrollingDown, scrollY ->
            if (session === attachedSession) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (session !== attachedSession) return@runOnUiThread
                    TabManager.rememberScroll(session, scrollY)
                    webBackdrop?.markDirty()
                    val hide = NavConfig.shouldAutoHideDock(
                        mode = if (liquidChrome) NavConfig.Mode.CLASSIC else NavConfig.mode(this),
                        autoHideEnabled = BrowserPrefs.autoHideBars(this),
                        scrollingDown = scrollingDown,
                        scrollY = scrollY,
                        isStartPage = startPage.visibility == View.VISIBLE,
                        omniboxFocused = omnibox.hasFocus(),
                        findBarVisible = findBar.visibility == View.VISIBLE,
                        imeVisible = imeVisible,
                        fullScreen = fullScreenActive,
                        isPip = isInPictureInPictureMode
                    )
                    if (liquidChrome) {
                        // Al bajar se pliega; al subir (o volver arriba) se despliega.
                        if (hide) setChromeCompact(true)
                        else if (!scrollingDown || scrollY < NavConfig.SCROLL_HIDE_THRESHOLD_PX) setChromeCompact(false)
                    } else setDockScrollHidden(hide)
                }
            }
        }
        applyNavigationMode()
        findViewById<ImageButton>(R.id.tabToolsButton).apply {
            setOnClickListener {
                // En Inicio la cápsula unificada lleva el emblema Aperture: abre Nexo AI.
                if (TabManager.activeTab()?.let { BrowserPrefs.isNexoHome(it.url) } != false) {
                    ThemeManager.animateAiSparkTap(it)
                    com.nexo.browser.ai.NexoAiSheet.show(this@MainActivity)
                } else SiteControlSheet.show(this@MainActivity)
            }
            // Pulsación larga: el buzón de la identidad actual o herramientas de red/Burner.
            setOnLongClickListener {
                val burner = TabManager.activeTab()?.burnerId
                if (burner != null) BurnerMailbox.show(this@MainActivity, burner) else TabTools.show(this@MainActivity)
                true
            }
        }
        findViewById<Button>(R.id.menuButton).setOnClickListener { showMenu(it) }
        privateBanner.setOnClickListener {
            TabManager.activeTab()?.burnerId?.let { BurnerMailbox.show(this, it) }
        }
        extensionBannerAction.setOnClickListener {
            val target = currentStoreExtension ?: return@setOnClickListener
            val installed = com.nexo.browser.extensions.nxr.NxrRuntime.installed(this)
                .firstOrNull { it.id == target.extensionId || it.storeId == target.extensionId }
            if (installed != null) {
                com.nexo.browser.extensions.nxr.NxrRuntime.showExtensionSheet(
                    activity = this,
                    extId = installed.id,
                    onCheckUpdate = {
                        com.nexo.browser.extensions.hub.ExtensionHubSheets.openSingleUpdateFlow(this, installed) {
                            TabManager.activeTab()?.let { tab -> updateExtensionInstallBanner(tab) }
                        }
                    },
                    onChanged = {
                        TabManager.activeTab()?.let { tab -> updateExtensionInstallBanner(tab) }
                    }
                )
            } else {
                com.nexo.browser.extensions.hub.ExtensionHubSheets.openStoreInstallFlow(this, target) {
                    TabManager.activeTab()?.let { tab -> updateExtensionInstallBanner(tab) }
                }
            }
        }
        extensionBannerDismiss.setOnClickListener {
            currentStoreExtension?.let { dismissedStoreKeys.add("${it.store.name}:${it.extensionId}") }
            extensionInstallBanner.visibility = View.GONE
        }
        miniPlayerButton.setOnClickListener { startMiniPlayer() }
        captionsButton.setOnClickListener { toggleCaptions() }
        captionsButton.setOnLongClickListener {
            CaptionsUi.showSheet(this) { refreshCaptionsUi() }
            true
        }

        clearOmniboxButton.setOnClickListener {
            focusOmniboxForEditing()
            omnibox.setText("")
        }

        suggestions = OmniboxSuggestions(
            activity = this,
            panel = findViewById(R.id.omniboxSuggestions),
            bookmarksProvider = { com.nexo.browser.workspace.BookmarkStore.toCommandCandidates(applicationContext) }
        ) { pick ->
            handleCommandSuggestion(pick)
        }

        omnibox.setOnClickListener { focusOmniboxForEditing() }

        omnibox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearOmniboxButton.visibility =
                    if (omnibox.hasFocus() && !s.isNullOrEmpty()) View.VISIBLE else View.GONE
                refreshSuggestions(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        omnibox.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH || enter
            ) {
                navigateOmnibox()
                true
            } else false
        }

        omnibox.setOnFocusChangeListener { _, focused ->
            val tab = TabManager.activeTab()
            omnibox.typeface = if (focused) ThemeManager.fontRegular(this) else ThemeManager.fontMono(this)
            if (focused) {
                setDockScrollHidden(false)
                setChromeCompact(false)
                styleChromeGlass()
                // Al editar se muestra la URL completa, seleccionada y con teclado en un solo toque.
                // No invocar CefriumBrowser.setFocus(false) aquí: al transferir el foco de vista a
                // omnibox, Chromium ya desenfoca su ContentView; un setFocus(false) asíncrono cerraba
                // el teclado recién abierto por Android.
                tab?.takeUnless { BrowserPrefs.isNexoHome(it.url) }?.let { current ->
                    if (omnibox.text?.toString() != current.url) omnibox.setText(current.url)
                }
                omnibox.selectAll()
                setOmniboxIcon(null)
                clearOmniboxButton.visibility =
                    if (omnibox.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                showKeyboard(omnibox)
            } else {
                omniboxKeyboard.cancel()
                styleChromeGlass()
                clearOmniboxButton.visibility = View.GONE
                suggestions.hide()
                tab?.let { renderOmnibox(it) }
            }
            layoutEdgeToEdge()
            syncPredictiveBack()
        }

        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = findInPage(restart = true)
        })
        findInput.setOnFocusChangeListener { _, focused ->
            if (focused) showKeyboard(findInput)
            else findKeyboard.cancel()
            layoutEdgeToEdge()
        }
        findInput.setOnEditorActionListener { _, _, _ ->
            findInPage()
            true
        }
        findViewById<ImageButton>(R.id.findPrev).setOnClickListener { findInPage(backwards = true) }
        findViewById<ImageButton>(R.id.findNext).setOnClickListener { findInPage() }
        findViewById<ImageButton>(R.id.findClose).setOnClickListener { hideFindBar() }
    }

    /** Expande el cromo si estaba plegado, enfoca la barra de direcciones y abre el teclado en 1 toque. */
    private fun focusOmniboxForEditing() {
        setDockScrollHidden(false)
        setChromeCompact(false)
        if (omnibox.hasFocus()) showKeyboard(omnibox) else omnibox.requestFocus()
    }

    private val omniboxKeyboard by lazy { KeyboardRequest(omnibox) }
    private val findKeyboard by lazy { KeyboardRequest(findInput) }

    private fun showKeyboard(target: EditText) {
        (if (target === omnibox) omniboxKeyboard else findKeyboard).show()
    }

    private inner class KeyboardRequest(private val input: EditText) {
        private val retry = Runnable { request() }
        private val initial = Runnable {
            if (request()) input.postDelayed(retry, 120L)
        }

        fun show() {
            cancel()
            if (!imeVisible) input.post(initial)
        }

        fun cancel() {
            input.removeCallbacks(initial)
            input.removeCallbacks(retry)
        }

        private fun request(): Boolean {
            if (imeVisible || isFinishing || isDestroyed || !input.hasFocus() || !input.isAttachedToWindow) return false
            val keyboard = getSystemService(InputMethodManager::class.java) ?: return false
            keyboard.showSoftInput(input, 0)
            return true
        }
    }

    // ---------------------------------------------------------------
    // Buscar en la página
    // ---------------------------------------------------------------

    private fun showFindBar() {
        if (BrowserPrefs.isNexoHome(TabManager.activeTab()?.url)) {
            Toast.makeText(this, "Abre una página para buscar en ella", Toast.LENGTH_SHORT).show()
            return
        }
        setDockScrollHidden(false)
        setChromeCompact(false)
        findDocument = TabManager.activeTab()?.navigationEpoch ?: -1L
        findBar.visibility = View.VISIBLE
        findCount.text = ""
        if (findInput.hasFocus()) showKeyboard(findInput) else findInput.requestFocus()
        layoutEdgeToEdge()
        if (!findInput.text.isNullOrEmpty()) findInPage(restart = true)
        syncPredictiveBack()
    }

    private fun hideFindBar() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
        findGeneration++
        runCatching { attachedSession?.finder?.clear() }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(findInput.windowToken, 0)
        findInput.clearFocus()
        syncPredictiveBack()
    }

    private fun findInPage(backwards: Boolean = false, restart: Boolean = false) {
        if (findBar.visibility != View.VISIBLE) return
        val session = attachedSession ?: return
        val finder = session.finder
        val generation = ++findGeneration
        val term = findInput.text?.toString().orEmpty()
        if (term.isEmpty()) {
            runCatching { finder.clear() }
            findCount.text = ""
            return
        }
        runCatching {
            // Repetir el mismo texto avanza al siguiente resultado.
            if (restart) finder.clear()
            finder.find(term, backwards) { result ->
                if (generation != findGeneration || attachedSession !== session ||
                    findBar.visibility != View.VISIBLE || isDestroyed) return@find
                val p = ThemeManager.palette(this)
                findCount.text = when {
                    result == null -> ""
                    !result.found -> "0/0"
                    else -> "${result.current}/${result.total}"
                }
                findCount.setTextColor(if (result != null && !result.found) p.error.color else p.muted)
            }
        }
    }

    /**
     * Sugerencias solo mientras se escribe (no al mostrar la dirección actual).
     * El buscador nunca recibe lo escrito en privado, Burner o con proxy.
     */
    private fun refreshSuggestions(text: String?) {
        val tab = TabManager.activeTab()
        if (!omnibox.hasFocus() || text.isNullOrBlank() || text == tab?.url) { suggestions.hide(); return }
        val remoteAllowed = tab != null && !tab.isPrivate && tab.burnerId == null && tab.networkProfile == null
        suggestions.update(text, remoteAllowed)
    }

    private fun navigateOmnibox() {
        val raw = omnibox.text?.toString().orEmpty().trim()
        if (raw.startsWith("@")) {
            val localPick = suggestions.firstLocalAction(raw)
            if (localPick != null) {
                handleCommandSuggestion(localPick)
            } else {
                Toast.makeText(this, "No hay coincidencias locales para $raw", Toast.LENGTH_SHORT).show()
            }
            return
        }
        viewModel.navigate(raw)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(omnibox.windowToken, 0)
        omnibox.clearFocus()
    }

    private fun handleCommandSuggestion(pick: OmniboxSuggestion) {
        if (pick is OmniboxSuggestion.PrefixHint) {
            val text = "${pick.scope.prefix} "
            omnibox.setText(text)
            omnibox.setSelection(text.length)
            return
        }
        when (pick) {
            is OmniboxSuggestion.OpenTab -> TabManager.switchTo(pick.tabId)
            is OmniboxSuggestion.OpenGroup -> TabManager.switchTo(pick.firstTabId)
            is OmniboxSuggestion.Bookmark -> viewModel.navigate(pick.url)
            is OmniboxSuggestion.Shortcut -> viewModel.navigate(pick.url)
            is OmniboxSuggestion.History -> viewModel.navigate(pick.url)
            is OmniboxSuggestion.Search -> viewModel.navigate(SearchResolver.searchUrl(this, pick.query))
            is OmniboxSuggestion.Setting -> {
                suppressAutoPipOnce = true
                startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_QUERY, pick.spec.query))
            }
            is OmniboxSuggestion.Command -> executeCommand(pick.spec.id)
            is OmniboxSuggestion.PrefixHint -> Unit
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(omnibox.windowToken, 0)
        omnibox.clearFocus()
    }

    private fun executeCommand(commandId: String) {
        when (commandId) {
            "new_tab" -> viewModel.newTab(false)
            "private_tab" -> viewModel.newTab(true)
            "burner_tab" -> BurnerMailbox.open(this)
            "translate" -> TranslatorManager.translateActive(this) { _, message ->
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
            "find_in_page" -> showFindBar()
            "downloads" -> openScreen(DownloadsActivity::class.java)
            "history" -> openScreen(HistoryActivity::class.java)
            "settings" -> openScreen(SettingsActivity::class.java)
            "phantom_shield" -> openShieldPanel()
            "nexo_ai" -> com.nexo.browser.ai.NexoAiSheet.show(this)
            "extensions" -> openScreen(ExtensionsActivity::class.java)
            "nav_customize" -> NavCustomizerSheet.show(this) { applyNavigationMode() }
            "tab_tools" -> SiteControlSheet.show(this)
            "reader_mode" -> com.nexo.browser.reader.NexoReader.openActiveTab(this)
            "offline_articles" -> com.nexo.browser.reader.NexoReader.openSavedArticlesSheet(this) { viewModel.navigate(it) }
            "bookmarks" -> openScreen(BookmarksActivity::class.java)
            "bookmark_page" -> toggleCurrentBookmark()
            "workspaces" -> com.nexo.browser.workspace.WorkspaceStore.showSheet(this) { refreshStartPageIfVisible() }
        }
    }

    /** Menú principal: hoja inferior con la página actual y una cuadrícula de acciones. */
    private fun showMenu(@Suppress("UNUSED_PARAMETER") anchor: View) {
        val current = TabManager.activeTab()
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }

        val primaryActions = listOf(
            SheetAction(R.drawable.ic_nav_add, "Nueva") { viewModel.newTab(false) },
            SheetAction(R.drawable.ic_menu_private, "Privada") { viewModel.newTab(true) },
            SheetAction(R.drawable.ic_menu_burner, "Burner") { BurnerMailbox.open(this) }
        )
        val onPage = current != null && !BrowserPrefs.isNexoHome(current.url)
        val reopen = TabManager.lastClosed()
        val actions = listOf(
            SheetAction(R.drawable.ic_menu_bookmark, "Favoritos") { openScreen(BookmarksActivity::class.java) },
            SheetAction(R.drawable.ic_menu_workspace, "Espacios") {
                com.nexo.browser.workspace.WorkspaceStore.showSheet(this) { refreshStartPageIfVisible() }
            },
            SheetAction(R.drawable.ic_menu_history, "Historial") { openScreen(HistoryActivity::class.java) },
            SheetAction(R.drawable.ic_menu_download, "Descargas", active = DownloadStore.activeDownloads().isNotEmpty()) {
                openScreen(DownloadsActivity::class.java)
            },
            SheetAction(
                R.drawable.ic_menu_translate,
                if (TranslatorManager.isTranslated(current?.session)) "Restaurar original" else "Traducir",
                active = TranslatorManager.isTranslated(current?.session)
            ) {
                TranslatorManager.translateActive(this) { _, message ->
                    runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
                }
            },
            SheetAction(R.drawable.ic_menu_reader, "Modo lectura") {
                com.nexo.browser.reader.NexoReader.openActiveTab(this)
            },
            SheetAction(R.drawable.ic_menu_desktop, "Escritorio", active = TabManager.desktopModeActive(), checked = TabManager.desktopModeActive()) {
                TabManager.setDesktopMode(!TabManager.desktopModeActive())
            },
            SheetAction(R.drawable.ic_menu_copy, "Copiar enlace") { current?.let { copyText("Enlace", it.url) } },
            SheetAction(R.drawable.ic_menu_share, "Compartir") { shareCurrent() },
            SheetAction(R.drawable.ic_menu_find, "Buscar") { showFindBar() },
            SheetAction(R.drawable.ic_menu_pin, "Fijar en inicio") { pinCurrentToHome() },
            SheetAction(R.drawable.ic_menu_screenshot, "Captura") { saveScreenshot() },
            SheetAction(R.drawable.ic_menu_pdf, "Guardar PDF") { savePageAsPdf() },
            if (reopen != null) SheetAction(R.drawable.ic_menu_reopen, "Reabrir cerrada") { TabManager.reopenClosed() }
            else SheetAction(R.drawable.ic_tab_tools, "Herramientas") { TabTools.show(this) },
            SheetAction(
                R.drawable.ic_nexo_aperture,
                "Nexo AI",
                active = com.nexo.browser.ai.NexoAiConfig.read(this).enabled
            ) { com.nexo.browser.ai.NexoAiSheet.show(this) },
            SheetAction(R.drawable.ic_menu_settings, "Configuración") { openScreen(SettingsActivity::class.java) },
            SheetAction(
                R.drawable.ic_menu_update,
                if (AppUpdater.hasAvailableUpdate(this)) "Actualizar Nexo (v${AppUpdater.knownLatestVersion(this)})" else "Buscar actualizaciones",
                active = AppUpdater.hasAvailableUpdate(this)
            ) { AppUpdater.checkManual(this) }
        ).filter { onPage || it.icon !in PAGE_ONLY_ACTIONS }
        // Cabecera mínima: dominio actual y guardar en favoritos (sin título obvio).
        val menuHeader = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            val d = resources.displayMetrics.density
            setPadding((22 * d).toInt(), 0, (10 * d).toInt(), (6 * d).toInt())
            addView(TextView(this@MainActivity).apply {
                text = if (BrowserPrefs.isNexoHome(current?.url)) "Inicio" else current?.url?.let { displayUrl(it) }.orEmpty()
                textSize = 17f
                letterSpacing = -0.025f
                typeface = NexoUi.semiBold(this@MainActivity)
                setTextColor(ThemeManager.palette(this@MainActivity).text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (onPage && !pillReloadButton.isShown && !reloadButton.isShown) {
                val touchTarget = resources.getDimensionPixelSize(R.dimen.touch_target)
                addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_nav_reload)
                    contentDescription = "Recargar"
                    ThemeManager.styleIconButton(this@MainActivity, this)
                    setOnClickListener { dismiss(); TabManager.reload() }
                    setOnLongClickListener { dismiss(); TabManager.reload(bypassCache = true); true }
                }, LinearLayout.LayoutParams(touchTarget, touchTarget))
            }
            if (onPage && !current.isPrivate && UrlSafety.isWebUrl(current.url)) {
                val bookmarked = com.nexo.browser.workspace.BookmarkStore.isBookmarked(this@MainActivity, current.url)
                addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_menu_bookmark)
                    contentDescription = if (bookmarked) "Quitar de favoritos" else "Guardar en favoritos"
                    ThemeManager.styleIconButton(this@MainActivity, this, active = bookmarked)
                    setOnClickListener {
                        dismiss()
                        toggleCurrentBookmark()
                    }
                }, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
            }
        }
        val content = NexoSheets.column(
            this, menuHeader, NexoSheets.primaryActions(this, primaryActions, dismiss)
        )
        val menu = MenuSections.arrange(actions)
        if (menu.quick.isNotEmpty()) content.addView(NexoSheets.grid(this, menu.quick, dismiss = dismiss))
        content.addView(NexoSheets.actionGroups(this, menu.groups, dismiss))
        extensionShortcuts(dismiss)?.let { content.addView(it) }
        sheet = NexoSheets.show(this, content)
    }

    /**
     * Controles de extensiones: PhantomShield con su interruptor y, debajo, una
     * fila deslizable con todas las demás (icono real e insignia de cada una).
     */
    private fun extensionShortcuts(dismiss: () -> Unit): View? {
        val phantom = ExtensionManager.phantomEntry()
        val others = ExtensionManager.actionEntries().filterNot { it.id == ExtensionManager.PHANTOMSHIELD_ID }
        val items = listOfNotNull(phantom)
        val p = ThemeManager.palette(this)
        val d = resources.displayMetrics.density
        fun dp(value: Float) = (value * d).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(NexoUi.Tokens.SHEET_PADDING), dp(10f), dp(NexoUi.Tokens.SHEET_PADDING), dp(10f))
            if (items.isEmpty() && others.isEmpty()) addView(TextView(this@MainActivity).apply {
                text = "Administrar extensiones"; textSize = 13f; setTextColor(p.muted)
                typeface = NexoUi.regular(this@MainActivity)
                minimumHeight = dp(48f); gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true; setOnClickListener { dismiss(); openScreen(ExtensionsActivity::class.java) }
            })
            items.forEach { extension ->
                val isShield = extension.id == ExtensionManager.PHANTOMSHIELD_ID
                val badge = if (isShield) ExtensionManager.phantomBadge() else null
                // Icono lineal desnudo (sin cuadrado de color detrás).
                val icon = ImageView(this@MainActivity).apply {
                    setImageResource(if (isShield) R.drawable.ic_menu_shield else R.drawable.ic_extension_placeholder)
                    imageTintList = android.content.res.ColorStateList.valueOf(if (isShield && extension.enabled) p.success.color else p.muted)
                }
                if (!isShield) ExtensionManager.loadIconFor(extension.id, dp(40f)) { bitmap ->
                    if (bitmap != null) runOnUiThread { icon.imageTintList = null; icon.setImageBitmap(bitmap) }
                }
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(64f); setPadding(dp(16f), dp(10f), dp(12f), dp(10f))
                    background = ThemeManager.ripple(this@MainActivity, ThemeManager.rounded(this@MainActivity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD), NexoUi.Tokens.RADIUS_CARD)
                    NexoGlass.paperShadow(this, p)
                    isClickable = true; isFocusable = true
                    contentDescription = "Abrir panel de ${extension.name ?: "extensión"}"
                    setOnClickListener {
                        dismiss()
                        if (!extension.enabled) openScreen(ExtensionsActivity::class.java)
                        else ExtensionManager.runActionFor(extension.id) { ok, message ->
                            if (!ok) runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
                        }
                    }
                    addView(icon, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(16f) })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(this@MainActivity).apply {
                            text = extension.name ?: "Extensión"; textSize = 15f
                            typeface = NexoUi.medium(this@MainActivity)
                            setTextColor(p.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = when {
                                !extension.enabled -> "Extensión desactivada"
                                badge == "OFF" -> "Bloqueo pausado"
                                badge == "PAUSE" -> "Pausada en esta página"
                                badge != null -> "$badge bloqueos en esta página"
                                else -> "Activa"
                            }
                            textSize = 12f; setTextColor(p.muted); setPadding(0, dp(2f), 0, 0)
                            typeface = if (badge?.matches(Regex("[0-9]{1,3}\\+?")) == true) NexoUi.mono(this@MainActivity) else NexoUi.regular(this@MainActivity)
                        })
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    if (isShield) addView(android.widget.Switch(this@MainActivity).apply {
                        isChecked = extension.enabled
                        contentDescription = "Habilitar extensión PhantomShield"; ThemeManager.styleSwitch(this@MainActivity, this)
                        setOnCheckedChangeListener { _, enabled ->
                            dismiss()
                            ExtensionManager.setEnabledFor(this@MainActivity, extension.id, enabled) { ok, message ->
                                runOnUiThread {
                                    if (!ok) Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                    TabManager.activeTab()?.takeIf { BrowserPrefs.isNexoHome(it.url) }?.let { startPage.render(it.isPrivate) }
                                }
                            }
                        }
                    }) else addView(ImageView(this@MainActivity).apply {
                        setImageResource(R.drawable.ic_nav_chevron)
                        imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
                    }, LinearLayout.LayoutParams(dp(16f), dp(16f)))
                }
                addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
            }
            // Resto de extensiones: todas a mano en una fila, más "Añadir".
            val strip = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            others.forEach { extension -> strip.addView(extensionTile(extension, dismiss)) }
            strip.addView(extensionTile(null, dismiss))
            addView(android.widget.HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(strip)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Botón de una extensión en el menú (null = añadir extensiones). */
    private fun extensionTile(extension: ExtensionEntry?, dismiss: () -> Unit): View {
        val p = ThemeManager.palette(this)
        val d = resources.displayMetrics.density
        fun dp(value: Float) = (value * d).toInt()
        val name = extension?.name ?: "Añadir"
        val badge = extension?.let { ExtensionManager.badgeFor(it.id) }
        val icon = ImageView(this).apply {
            setImageResource(if (extension == null) R.drawable.ic_nav_add else R.drawable.ic_extension_placeholder)
            imageTintList = android.content.res.ColorStateList.valueOf(if (extension == null) p.muted else p.text)
            scaleType = ImageView.ScaleType.CENTER
            background = ThemeManager.rounded(this@MainActivity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_PINNED)
        }
        if (extension != null) ExtensionManager.loadIconFor(extension.id, dp(44f)) { bitmap ->
            if (bitmap != null) runOnUiThread { icon.imageTintList = null; icon.setImageBitmap(bitmap) }
        }
        val square = android.widget.FrameLayout(this).apply {
            addView(icon, android.widget.FrameLayout.LayoutParams(dp(46f), dp(46f), android.view.Gravity.CENTER))
            if (badge != null) addView(TextView(this@MainActivity).apply {
                text = badge; textSize = 9f; setTextColor(p.error.onColor)
                typeface = resources.getFont(R.font.jetbrains_mono)
                setPadding(dp(4f), dp(1f), dp(4f), dp(1f))
                background = ThemeManager.rounded(this@MainActivity, p.error.color, 8f)
            }, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            ))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            background = ThemeManager.ripple(this@MainActivity, null, 16f)
            isClickable = true; isFocusable = true
            contentDescription = if (extension == null) "Añadir extensiones" else "Abrir $name" + (badge?.let { " · $it" } ?: "")
            setOnClickListener {
                dismiss()
                if (extension == null) openScreen(ExtensionManager.storeScreen())
                else ExtensionManager.runActionFor(extension.id) { ok, message ->
                    if (!ok) runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show() }
                }
            }
            // Mantener pulsado: administrar (permisos, privado, desinstalar).
            setOnLongClickListener { dismiss(); openScreen(ExtensionsActivity::class.java); true }
            addView(square, LinearLayout.LayoutParams(dp(54f), dp(54f)))
            addView(TextView(this@MainActivity).apply {
                text = name; textSize = 10.5f; setTextColor(p.text); gravity = android.view.Gravity.CENTER
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = dp(72f)
                setPadding(0, dp(4f), 0, 0)
            }, LinearLayout.LayoutParams(dp(72f), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Pestaña siguiente/anterior con el gesto de la barra; el texto de la dirección se desliza. */
    private fun switchAdjacentTab(offset: Int) {
        val id = TabManager.adjacentTabId(offset)
        val shift = 40f * resources.displayMetrics.density * (if (offset > 0) 1 else -1)
        if (id == null) {
            // Sin más pestañas en esa dirección: un pequeño rebote.
            omnibox.animate().cancel()
            omnibox.animate().translationX(-shift / 3f).setDuration(90L).withEndAction {
                omnibox.animate().translationX(0f).setDuration(140L).start()
            }.start()
            return
        }
        TabManager.switchTo(id)
        omnibox.animate().cancel()
        omnibox.translationX = shift
        omnibox.alpha = 0.2f
        omnibox.animate().translationX(0f).alpha(1f).setDuration(200L).start()
    }

    /** Pulsación larga en el contador de pestañas: acciones rápidas. */
    private fun showTabQuickMenu() {
        val current = TabManager.activeTab()
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val actions = mutableListOf(
            SheetAction(R.drawable.ic_nav_add, "Nueva pestaña") { viewModel.newTab(false) },
            SheetAction(R.drawable.ic_menu_private, "Nueva pestaña privada") { viewModel.newTab(true) },
            SheetAction(R.drawable.ic_menu_burner, "Nueva identidad Burner") { BurnerMailbox.open(this) }
        )
        TabManager.lastClosed()?.let { closed ->
            actions += SheetAction(R.drawable.ic_menu_reopen, "Reabrir: " + closed.title.ifBlank { closed.url }.take(60)) {
                TabManager.reopenClosed()
            }
        }
        if (current != null) actions += SheetAction(R.drawable.ic_menu_close, "Cerrar esta pestaña", danger = true) {
            TabManager.closeTab(current.id)
        }
        actions += SheetAction(R.drawable.ic_menu_settings, "Personalizar barra de navegación") {
            NavCustomizerSheet.show(this) { applyNavigationMode() }
        }
        sheet = NexoSheets.show(this, NexoSheets.column(this,
            NexoSheets.header(this, "Pestañas", "Desliza la barra inferior para cambiar de pestaña"),
            NexoSheets.list(this, actions, dismiss)))
    }

    private fun startPageAction(action: String) {
        when (action) {
            "ai" -> com.nexo.browser.ai.NexoAiSheet.show(this)
            "downloads" -> openScreen(DownloadsActivity::class.java)
            "history" -> openScreen(HistoryActivity::class.java)
            "burner" -> BurnerMailbox.open(this)
            "settings" -> openScreen(SettingsActivity::class.java)
            "bookmarks" -> openScreen(BookmarksActivity::class.java)
            "workspaces" -> com.nexo.browser.workspace.WorkspaceStore.showSheet(this) { refreshStartPageIfVisible() }
            "reader_offline" -> com.nexo.browser.reader.NexoReader.openSavedArticlesSheet(this) { viewModel.navigate(it) }
        }
    }

    private fun refreshStartPageIfVisible() {
        val tab = TabManager.activeTab() ?: return
        if (BrowserPrefs.isNexoHome(tab.url) && startPage.visibility == View.VISIBLE) {
            startPage.render(tab.isPrivate)
        }
    }

    private fun toggleCurrentBookmark() {
        val tab = TabManager.activeTab() ?: return
        if (tab.isPrivate) {
            Toast.makeText(this, "Las páginas privadas no se guardan en favoritos", Toast.LENGTH_SHORT).show()
            return
        }
        if (BrowserPrefs.isNexoHome(tab.url) || !UrlSafety.isWebUrl(tab.url)) {
            Toast.makeText(this, "Abre una página web para guardarla en favoritos", Toast.LENGTH_SHORT).show()
            return
        }
        val added = com.nexo.browser.workspace.BookmarkStore.toggle(this, tab.title, tab.url)
        Toast.makeText(
            this,
            if (added) "Guardado en favoritos" else "Eliminado de favoritos",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Añade el sitio actual a los accesos rápidos de la página de inicio. */
    private fun pinCurrentToHome() {
        val tab = TabManager.activeTab() ?: return
        if (tab.isPrivate) {
            Toast.makeText(this, "Los sitios de pestañas privadas no se guardan en inicio", Toast.LENGTH_SHORT).show()
            return
        }
        val title = StartPageView.pin(this, tab.url)
        Toast.makeText(
            this,
            if (title != null) "«$title» fijado en la página de inicio" else "Este sitio ya está en inicio o no se puede fijar",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Captura de la página visible, guardada como PNG en Descargas. */
    private fun saveScreenshot() {
        val tab = TabManager.activeTab() ?: return
        if (BrowserPrefs.isNexoHome(tab.url) || pageView.width <= 0 || pageView.height <= 0) {
            Toast.makeText(this, "Abre una página para capturarla", Toast.LENGTH_SHORT).show()
            return
        }
        engineView.capture { bitmap ->
            if (bitmap == null) {
                Toast.makeText(this, "No se pudo capturar la página", Toast.LENGTH_SHORT).show()
            } else {
                DownloadStore.saveBitmap(this, bitmap, "Captura ${displayUrl(tab.url)}", SitePermissionStore.scope(tab)) { _, message ->
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** La página completa como PDF (motor de impresión del motor web), en Descargas. */
    private fun savePageAsPdf() {
        val tab = TabManager.activeTab() ?: return
        val session = tab.session ?: return
        if (BrowserPrefs.isNexoHome(tab.url)) {
            Toast.makeText(this, "Abre una página para guardarla", Toast.LENGTH_SHORT).show()
            return
        }
        val name = FileNames.sanitize(tab.title.ifBlank { displayUrl(tab.url) }.take(80), "pagina") + ".pdf"
        // Motores sin PDF directo: diálogo del sistema con "Guardar como PDF".
        suppressAutoPipOnce = true
        if (session.printWithSystemDialog(this, name.removeSuffix(".pdf"))) return
        suppressAutoPipOnce = false
        Toast.makeText(this, "Generando PDF…", Toast.LENGTH_SHORT).show()
        session.saveAsPdf { stream, error ->
            when {
                stream != null -> DownloadStore.saveStream(this, stream, name, "application/pdf", tab.url, SitePermissionStore.scope(tab)) { _, message ->
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                error != null -> Toast.makeText(this, "No se pudo generar el PDF: ${error.message ?: "error"}", Toast.LENGTH_LONG).show()
                else -> Toast.makeText(this, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openShieldPanel() {
        val shield = ExtensionManager.actionEntries().firstOrNull { it.id == ExtensionManager.PHANTOMSHIELD_ID }
        if (shield == null) {
            openScreen(ExtensionsActivity::class.java)
            return
        }
        ExtensionManager.runActionFor(shield.id) { ok, message ->
            if (!ok) runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun openScreen(screen: Class<out Activity>) {
        suppressAutoPipOnce = true
        val intent = Intent(this, screen)
        if (screen == DownloadsActivity::class.java) {
            SitePermissionStore.scope(TabManager.activeTab())?.let {
                intent.putExtra(DownloadsActivity.EXTRA_PRIVATE_SCOPE, it)
            }
        }
        startActivity(intent)
    }

    /**
     * [force]: el usuario abre el selector, se captura aunque suene un video.
     * La imagen solo se guarda si al llegar sigue a la vista la misma pestaña
     * con la misma página: antes una captura tardía podía quedar asignada a la
     * pestaña equivocada o mostrar la página anterior.
     */
    private fun captureCurrentTabPreview(force: Boolean = false, onCaptured: (() -> Unit)? = null) {
        val tab = TabManager.activeTab()
        val session = attachedSession
        if (
            tab == null ||
            session == null ||
            tab.isPrivate ||
            tab.session !== session ||
            BrowserPrefs.isNexoHome(tab.url) ||
            (!force && tab.isLoading) ||
            isInPictureInPictureMode ||
            startPage.visibility == View.VISIBLE ||
            pageView.width <= 0 ||
            pageView.height <= 0
        ) {
            onCaptured?.invoke()
            return
        }
        val tabId = tab.id
        val url = tab.url
        val epoch = tab.navigationEpoch
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = if (onCaptured != null) {
            Runnable { if (settled.compareAndSet(false, true)) onCaptured() }.also {
                pageView.postDelayed(it, 95L)
            }
        } else null
        engineView.capture { bitmap ->
            if (bitmap != null) {
                if (attachedSession === session && tab.session === session && tab.url == url &&
                    tab.navigationEpoch == epoch && !isDestroyed) {
                    TabPreviewStore.save(applicationContext, tabId, bitmap)
                    lastAutoPreview[tabId] = url to android.os.SystemClock.elapsedRealtime()
                } else {
                    bitmap.recycle()
                }
            }
            timeout?.let { pageView.removeCallbacks(it) }
            if (settled.compareAndSet(false, true)) onCaptured?.invoke()
        }
    }

    /**
     * Miniatura automática al terminar de cargar o al cambiar de vista (incluyendo SPAs y scroll):
     * siempre que cambia la página y, en la misma, como mucho una cada 12 s.
     */
    private fun scheduleIdlePreview(tab: BrowserTab, delayMs: Long = 1_100L) {
        pageView.removeCallbacks(idlePreview)
        val (lastUrl, lastAt) = lastAutoPreview[tab.id] ?: ("" to 0L)
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastUrl == tab.url && now - lastAt < 12_000L) return
        pageView.postDelayed(idlePreview, delayMs)
    }

    private fun showTabsDialog() {
        // Espera breve (~95 ms máx.) para que PixelCopy guarde el último estado antes de cubrir la vista.
        captureCurrentTabPreview(force = true) {
            if (!isFinishing && !isDestroyed) {
                TabSwitcher.show(this) { private -> viewModel.newTab(private) }
            }
        }
    }

    /** Mantener pulsado un enlace o imagen: hoja con las acciones disponibles. */
    override fun onContextElement(tab: BrowserTab, element: EngineContextElement) {
        val origin = tab.session ?: return
        fun openInSameContext(url: String) {
            if (tab.session !== origin || TabManager.activeTab() !== tab) return
            TabManager.createTab(url, tab.isPrivate, groupName = tab.groupName, burnerId = tab.burnerId, networkProfile = tab.networkProfile)
        }
        val link = element.linkUri.orEmpty()
        val image = element.srcUri.orEmpty()
        if (link.isBlank() && image.isBlank()) return

        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val actions = mutableListOf<SheetAction>()

        if (link.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_menu_open, "Abrir en nueva pestaña") {
                openInSameContext(link)
            }
            if (!tab.isPrivate) {
                actions += SheetAction(R.drawable.ic_menu_private, "Abrir en pestaña privada") {
                    if (TabManager.activeSession() === origin) TabManager.createTab(link, isPrivate = true)
                }
            }
            actions += SheetAction(R.drawable.ic_menu_link, "Abrir aquí") {
                if (TabManager.activeSession() === origin) TabManager.navigate(link)
            }
            actions += SheetAction(R.drawable.ic_menu_copy, "Copiar enlace") { copyText("Enlace", link) }
            actions += SheetAction(R.drawable.ic_menu_share, "Compartir enlace") { shareText(link) }
            actions += SheetAction(R.drawable.ic_menu_download, "Descargar enlace") { if (TabManager.activeSession() === origin) downloadUrl(tab, link) }
        }
        if (image.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_menu_image, "Abrir imagen en nueva pestaña") {
                openInSameContext(image)
            }
            actions += SheetAction(R.drawable.ic_menu_copy, "Copiar dirección de la imagen") {
                copyText("Imagen", image)
            }
            actions += SheetAction(R.drawable.ic_menu_download, "Descargar imagen") { if (TabManager.activeSession() === origin) downloadUrl(tab, image) }
        }

        val title = element.title?.takeIf { it.isNotBlank() }
            ?: element.altText?.takeIf { it.isNotBlank() }
            ?: element.linkText?.takeIf { it.isNotBlank() }
            ?: if (link.isNotBlank()) "Enlace" else "Imagen"

        sheet = NexoSheets.show(
            this,
            NexoSheets.column(
                this,
                NexoSheets.header(this, title, link.ifBlank { image }),
                NexoSheets.list(this, actions, dismiss)
            )
        )
    }

    private fun cleanShareUrl(rawUrl: String): Pair<String, Boolean> {
        val noCreds = UrlSafety.stripCredentials(rawUrl)
        val stripped = com.nexo.browser.shield.ShieldPolicy.stripTrackingParams(noCreds)
        return if (stripped != null && stripped != noCreds) stripped to true else noCreds to false
    }

    private fun shareText(text: String) {
        val (outText, cleaned) = if (UrlSafety.isWebUrl(text.trim())) cleanShareUrl(text.trim()) else text to false
        if (cleaned) Toast.makeText(this, "Enlace compartido sin parámetros de rastreo", Toast.LENGTH_SHORT).show()
        suppressAutoPipOnce = true
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, outText)
            }, "Compartir"
        ))
    }

    private fun copyText(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipboardPrivacy.plainText(label, value, TabManager.activeTab()?.isPrivate == true))
        Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
    }

    private fun downloadUrl(tab: BrowserTab, url: String) {
        val routed = tab.burnerId != null || tab.networkProfile?.type?.let { it != "direct" } == true
        if (ExtensionManager.isRemoteExtensionPackage(url)) {
            if (routed) {
                Toastish.show(this, "La instalación usa una conexión independiente de la identidad Burner.")
                return
            }
            Toast.makeText(this, "Abriendo instalador de extensión…", Toast.LENGTH_SHORT).show()
            ExtensionManager.installUrl(this, url) { _, message ->
                runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
            }
            return
        }

        if (!UrlSafety.isWebUrl(url)) {
            Toast.makeText(this, "Este recurso no se puede descargar directamente", Toast.LENGTH_LONG).show()
            return
        }

        // Descargar con el gestor de Nexo (cookies del perfil, modo privado) en lugar de
        // DownloadManager, que no tiene acceso a la sesión del navegador.
        if (routed) {
            val session = tab.session as? com.nexo.browser.engine.cefrium.CefriumEngineSession
            if (session?.startDownload(url) != true) Toastish.show(this, "No se pudo iniciar la descarga con la ruta de esta pestaña.")
            return
        }
        if (com.nexo.browser.shield.MalwareScan.blocksDownload(this, tab, url)) {
            Toast.makeText(this, "Descarga bloqueada: el sitio o el archivo instalador presenta riesgo alto.", Toast.LENGTH_LONG).show()
            return
        }
        if (com.nexo.browser.shield.MalwareScan.warnsDownload(this, tab, url)) {
            Toast.makeText(this, "Atención: estás descargando un instalador desde un origen con señales sospechosas.", Toast.LENGTH_LONG).show()
        }
        Toast.makeText(this, "Descarga iniciada", Toast.LENGTH_SHORT).show()
        DownloadStore.download(
            this,
            url,
            isPrivate = tab.isPrivate,
            referrer = tab.url,
            split = BrowserPrefs.parallelDownloads(this)
        ) { _, message ->
            runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun shareCurrent() {
        val tab = TabManager.activeTab() ?: return
        val (cleanUrl, stripped) = cleanShareUrl(tab.url)
        if (stripped) Toast.makeText(this, "Enlace compartido sin parámetros de rastreo", Toast.LENGTH_SHORT).show()
        suppressAutoPipOnce = true
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, tab.title)
                putExtra(Intent.EXTRA_TEXT, "${tab.title}\n$cleanUrl")
            }, "Compartir página"
        ))
    }

    private fun styleTabToolsButton(tab: BrowserTab?) {
        val btn = findViewById<ImageButton>(R.id.tabToolsButton)
        val p = ThemeManager.palette(this)
        val session = tab?.session as? com.nexo.browser.engine.cefrium.CefriumEngineSession
        val muted = session?.isAudioMuted() == true
        val home = tab == null || BrowserPrefs.isNexoHome(tab.url)
        val sslBlocked = !home && !session?.lastBlockedSslHost().isNullOrBlank()
        val scheme = if (tab != null) UrlSafety.scheme(tab.url) else ""
        val scanLevel = com.nexo.browser.shield.MalwareScan.resultFor(tab)?.decision?.level
        val scanAlert = !home && scanLevel != null && scanLevel != com.nexo.browser.shield.MalwareSignals.Level.CLEAN
        val scanDanger = scanAlert && scanLevel == com.nexo.browser.shield.MalwareSignals.Level.DANGEROUS

        val iconRes = when {
            scanAlert -> R.drawable.ic_shield_alert
            tab?.burnerId != null -> R.drawable.ic_menu_burner
            tab?.isPrivate == true -> R.drawable.ic_menu_private
            muted -> R.drawable.ic_menu_volume_off
            tab?.networkProfile != null -> R.drawable.ic_tab_tools
            home -> R.drawable.ic_nexo_aperture
            scheme == "https" && !sslBlocked -> R.drawable.ic_lock
            else -> R.drawable.ic_tab_tools
        }
        btn.setImageResource(iconRes)

        val highlight = tab != null && (tab.burnerId != null || tab.networkProfile != null || muted || sslBlocked || scanAlert)
        ThemeManager.styleIconButton(this, btn, highlight)
        when {
            scanDanger -> btn.imageTintList = android.content.res.ColorStateList.valueOf(p.danger)
            scanAlert -> btn.imageTintList = android.content.res.ColorStateList.valueOf(p.warning.color)
            sslBlocked -> btn.imageTintList = android.content.res.ColorStateList.valueOf(p.danger)
            tab?.burnerId != null -> btn.imageTintList = android.content.res.ColorStateList.valueOf(p.warning.color)
            !home && scheme == "http" -> btn.imageTintList = android.content.res.ColorStateList.valueOf(p.warning.color)
            !home && scheme == "https" && !highlight -> btn.imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
            home -> btn.imageTintList = android.content.res.ColorStateList.valueOf(p.text)
            tab.isPrivate -> btn.imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
        }
        btn.contentDescription = when {
            scanDanger -> "Control del sitio (página posiblemente peligrosa)"
            scanAlert -> "Control del sitio (página sospechosa)"
            sslBlocked -> "Control del sitio (certificado TLS bloqueado)"
            muted -> "Control del sitio (audio silenciado)"
            tab?.burnerId != null -> "Control del sitio (identidad Burner)"
            tab?.networkProfile != null -> "Control del sitio (ruta de red personalizada)"
            !home && scheme == "https" -> "Control del sitio (conexión HTTPS)"
            !home && scheme == "http" -> "Control del sitio (conexión sin cifrar)"
            home -> "Preguntar a Nexo AI"
            else -> "Control del sitio"
        }
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        root.setBackgroundColor(p.background)
        ThemeManager.styleFloatingDock(this, browserDock, dockGesturePill)
        navBar.background = null
        browserFrame.applyPalette(p.surfaceContainerHigh, p.accent, ThemeManager.hairline(p))
        pageView.setBackgroundColor(p.background)
        ThemeManager.styleOmniboxInput(this, omnibox)
        omnibox.textSize = 15f
        omnibox.typeface = if (omnibox.hasFocus()) ThemeManager.fontRegular(this) else ThemeManager.fontMono(this)
        omnibox.fontFeatureSettings = "tnum"
        ThemeManager.styleIconButton(this, compactBackButton)
        ThemeManager.styleIconButton(this, pillReloadButton)
        pillReloadButton.imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
        pillReloadButton.scaleX = 0.9f; pillReloadButton.scaleY = 0.9f
        styleTabToolsButton(TabManager.activeTab())
        styleMiniPlayerChip()
        // Emblema Nexo Aperture: integrado en el clúster, respira mientras Nexo AI escribe.
        val aiSnap = com.nexo.browser.ai.NexoAiController.snapshot()
        ThemeManager.styleAiSparkButton(this, aiButton, aiSnap.indicatorState)
        nexoAiPill.render(this, aiSnap)
        aiButton.setOnClickListener {
            ThemeManager.animateAiSparkTap(aiButton)
            com.nexo.browser.ai.NexoAiSheet.show(this)
        }
        ThemeManager.styleTabCounter(this, tabButton)
        ThemeManager.styleMenuIconButton(this, findViewById(R.id.menuButton))
        ThemeManager.styleFlatButton(this, clearOmniboxButton)
        clearOmniboxButton.setTextColor(p.muted)

        listOf(
            backButton,
            forwardButton,
            homeButton,
            reloadButton
        ).forEach { ThemeManager.styleIconButton(this, it) }
        ThemeManager.styleDockPrimaryIcon(this, newTabButton)

        // Avisos flotantes: cristal con radio concéntrico, nunca cajas con borde.
        ThemeManager.styleOmniboxInput(this, findInput)
        findCount.setTextColor(p.muted)
        findCount.typeface = ThemeManager.fontMono(this)
        findCount.fontFeatureSettings = "tnum"
        listOf(R.id.findPrev, R.id.findNext, R.id.findClose).forEach {
            ThemeManager.styleIconButton(this, findViewById(it))
        }

        invalidateChromeGlassCache()
        stylePrivateBanner(TabManager.activeTab())
        extensionBannerText.setTextColor(p.text)
        extensionBannerText.typeface = ThemeManager.fontMedium(this)
        ThemeManager.styleButton(this, extensionBannerAction, primary = true)
        ThemeManager.styleIconButton(this, extensionBannerDismiss)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(if (p.isMonochrome) p.text else p.accentSwatch)
        progress.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        miniDomain.setTextColor(p.text)
        miniDomain.typeface = ThemeManager.fontMono(this, 500)
        miniDomain.fontFeatureSettings = "tnum"
        miniLock.imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
        styleChromeGlass()
        applyNavigationMode()
        settingsFingerprint = fingerprint()
        themeKey = ThemeManager.themeKey(this)
    }

    private fun invalidateChromeGlassCache() {
        chromeGlassKey = ""
        chromeIslandKey = ""
        privateBannerKey = ""
    }

    /**
     * Cristal Líquido del cromo: píldora de dirección, clúster desacoplado, dock clásico,
     * barra de búsqueda, banners y mini-píldora. Aplica a todos los temas (Sistema, Obsidian,
     * OLED y Paper). Modo privado = vidrio ahumado discreto.
     */
    private fun styleChromeGlass() {
        if (!::chromeCluster.isInitialized || !::startPage.isInitialized || !::aiButton.isInitialized) return
        val p = ThemeManager.palette(this)
        val private = TabManager.activeTab()?.isPrivate == true
        val focused = omnibox.hasFocus()
        val startVisible = startPage.visibility == View.VISIBLE
        val intensity = GlassPreferences.intensity
        val paletteKey = ThemeManager.themeKey(this)
        webBackdrop?.intensity = intensity
        fun glass(host: View, radius: Float, smoked: Boolean = private) =
            NexoGlassDrawable(this, p, radius, smoked = smoked).also {
                if (startVisible) it.setBackdrop(startPage, host)
                else it.setWebBackdrop(webBackdrop, pageView, host)
            }

        val sharedKey = "$paletteKey|$private|$startVisible|${intensity.name}|$liquidChrome"
        val islandKey = "$sharedKey|$focused"
        if (islandKey != chromeIslandKey) {
            chromeIslandKey = islandKey
            chromeIsland.background = if (focused) ThemeManager.rounded(this, p.surfaceContainerHigh, NexoTokens.CARD_RADIUS)
                else glass(chromeIsland, NexoTokens.CARD_RADIUS)
            omniboxCapsule.background = null
            chromeCluster.background = null
            NexoGlass.float(chromeIsland, p)
        }
        if (sharedKey != chromeGlassKey) {
            chromeGlassKey = sharedKey
            tabButton.background = ThemeManager.ripple(this, glass(tabButton, NexoTokens.CARD_RADIUS), NexoTokens.CARD_RADIUS)
            NexoGlass.float(tabButton, p)
            miniPill.background = android.graphics.drawable.InsetDrawable(
                glass(miniPill, NexoTokens.PILL_RADIUS), 0, NexoUi.dp(this, 8f), 0, NexoUi.dp(this, 8f))
            NexoGlass.float(miniPill, p, if (p.isDark) 3f else 6f)
            if (::browserDock.isInitialized) {
                browserDock.background = glass(browserDock, 28f)
                NexoGlass.float(browserDock, p)
            }
            if (::findBar.isInitialized) {
                findBar.background = glass(findBar, NexoUi.Tokens.RADIUS_CARD)
                NexoGlass.float(findBar, p)
            }
            if (::extensionInstallBanner.isInitialized) {
                extensionInstallBanner.background = glass(extensionInstallBanner, NexoUi.Tokens.RADIUS_CARD)
                NexoGlass.float(extensionInstallBanner, p)
            }
            if (::privateBanner.isInitialized) {
                privateBanner.background = glass(privateBanner, NexoUi.Tokens.RADIUS_PILL, smoked = true)
                NexoGlass.float(privateBanner, p)
            }
            findViewById<View>(R.id.omniboxSuggestions)?.let { suggestions ->
                suggestions.background = glass(suggestions, NexoUi.Tokens.RADIUS_CARD)
                NexoGlass.float(suggestions, p)
            }
            statusScrim.setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(p.background, if (startVisible) 0 else 235))
            webBackdrop?.markDirty()
        }
        val home = TabManager.activeTab()?.let { BrowserPrefs.isNexoHome(it.url) } != false
        val showCluster = !focused || !liquidChrome
        chromeCluster.visibility = if (showCluster) View.VISIBLE else View.GONE
        tabButton.visibility = if (showCluster) View.VISIBLE else View.GONE
        // En Inicio el emblema vive dentro de la cápsula unificada de búsqueda y consulta.
        aiButton.visibility = if (home && liquidChrome) View.GONE else View.VISIBLE
        pillReloadButton.visibility = if (liquidChrome && !focused && !home &&
            resources.configuration.screenWidthDp >= 400) View.VISIBLE else View.GONE
    }

    /** Scroll Compacto: pliega el cromo en una mini-píldora de 34dp con candado y dominio. */
    private fun setChromeCompact(compact: Boolean) {
        if (!::miniPill.isInitialized) return
        val allowed = compact && liquidChrome && !omnibox.hasFocus() && !immersive &&
            startPage.visibility != View.VISIBLE && findBar.visibility != View.VISIBLE && !imeVisible
        if (chromeCompact == allowed) return
        chromeCompact = allowed
        val travel = NexoUi.dp(this, 18f).toFloat()
        if (allowed) {
            miniPill.visibility = View.VISIBLE
            miniPill.alpha = 0f
            miniPill.scaleX = 0.86f; miniPill.scaleY = 0.86f
            NexoMotion.spring(miniPill, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f)
            NexoMotion.spring(miniPill, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f)
            NexoMotion.spring(miniPill, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f)
            NexoMotion.spring(topBar, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 0f)
            NexoMotion.spring(topBar, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, travel) {
                if (chromeCompact) topBar.visibility = View.INVISIBLE
            }
        } else {
            topBar.visibility = View.VISIBLE
            NexoMotion.spring(topBar, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f)
            NexoMotion.spring(topBar, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f)
            NexoMotion.spring(miniPill, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 0f) {
                if (!chromeCompact) miniPill.visibility = View.GONE
            }
        }
        syncFloatingControls(animated = true)
        refreshCaptionsUi()
        TabManager.activeTab()?.let { scheduleIdlePreview(it, delayMs = 700L) }
    }

    private fun stylePrivateBanner(@Suppress("UNUSED_PARAMETER") tab: BrowserTab?) {
        // Solo la identidad Burner usa un chip (abre su buzón); el modo privado vive en el cristal ahumado.
        val startVisible = ::startPage.isInitialized && startPage.visibility == View.VISIBLE
        val key = "${ThemeManager.themeKey(this)}|$startVisible|${GlassPreferences.intensity.name}"
        if (key == privateBannerKey) return
        privateBannerKey = key
        val p = ThemeManager.palette(this)
        val bannerGlass = NexoGlassDrawable(this, p, NexoUi.Tokens.RADIUS_PILL, smoked = true).also {
            if (startVisible) it.setBackdrop(startPage, privateBanner)
            else if (::pageView.isInitialized) it.setWebBackdrop(webBackdrop, pageView, privateBanner)
        }
        privateBanner.background = bannerGlass
        NexoGlass.float(privateBanner, p)
        privateBanner.setTextColor(p.text)
        privateBanner.typeface = ThemeManager.fontMedium(this)
        privateBanner.setCompoundDrawablesRelativeWithIntrinsicBounds(
            getDrawable(R.drawable.ic_menu_burner)?.mutate()?.apply {
                setTint(p.warning.color)
                setBounds(0, 0, NexoUi.dp(this@MainActivity, 16f), NexoUi.dp(this@MainActivity, 16f))
            }, null, null, null)
        privateBanner.compoundDrawablePadding = NexoUi.dp(this, 8f)
    }

    /** Aplica el modo de navegación elegido (Clásico, Compacto o Personalizado) sin perder los controles esenciales. */
    private fun applyNavigationMode() {
        val mode = NavConfig.mode(this)
        val hideChrome = immersive
        if (liquidChrome) {
            // Liquid Split Chrome: sin dock; atrás/adelante viven en gestos y en el menú.
            compactBackButton.visibility = View.GONE
            dockScrollHidden = false
            browserDock.animate().cancel()
            browserDock.visibility = View.GONE
            styleChromeGlass()
            layoutEdgeToEdge()
            return
        }
        setChromeCompact(false)
        compactBackButton.visibility = if (mode == NavConfig.Mode.COMPACT && !hideChrome) View.VISIBLE else View.GONE
        compactBackButton.isEnabled = TabManager.canGoBackOrPrevious()
        compactBackButton.alpha = if (compactBackButton.isEnabled) 1f else 0.36f

        val targetActions = when (mode) {
            NavConfig.Mode.CLASSIC -> NavConfig.DEFAULT_ACTIONS
            NavConfig.Mode.CUSTOM -> NavConfig.actions(this)
            NavConfig.Mode.COMPACT -> emptyList()
        }

        if (mode == NavConfig.Mode.COMPACT) {
            dockScrollHidden = false
            browserDock.animate().cancel()
            browserDock.translationY = 0f
            browserDock.alpha = 1f
            browserDock.visibility = View.GONE
            return
        }

        navBar.removeAllViews()
        val touchTarget = resources.getDimensionPixelSize(R.dimen.touch_target)
        targetActions.forEach { action ->
            val btn = when (action) {
                NavConfig.Action.BACK -> backButton
                NavConfig.Action.FORWARD -> forwardButton
                NavConfig.Action.HOME -> homeButton
                NavConfig.Action.RELOAD -> reloadButton
                NavConfig.Action.NEW_TAB -> newTabButton
                else -> ImageButton(this).apply {
                    setImageResource(NavConfig.iconFor(action))
                    contentDescription = action.label
                    scaleType = ImageView.ScaleType.CENTER
                    setOnClickListener { executeNavAction(action) }
                }
            }
            (btn.parent as? android.view.ViewGroup)?.removeView(btn)
            if (action == NavConfig.Action.NEW_TAB) ThemeManager.styleDockPrimaryIcon(this, btn)
            else ThemeManager.styleIconButton(this, btn)
            navBar.addView(btn, LinearLayout.LayoutParams(0, touchTarget, 1f))
        }
        backButton.isEnabled = TabManager.canGoBackOrPrevious()
        backButton.alpha = if (backButton.isEnabled) 1f else 0.36f
        forwardButton.isEnabled = TabManager.activeTab()?.canGoForward == true
        forwardButton.alpha = if (forwardButton.isEnabled) 1f else 0.36f

        if (hideChrome) {
            browserDock.visibility = View.GONE
        } else if (!dockScrollHidden) {
            browserDock.visibility = View.VISIBLE
            browserDock.alpha = 1f
            browserDock.translationY = 0f
        }
        styleChromeGlass()
        layoutEdgeToEdge()
    }

    private fun executeNavAction(action: NavConfig.Action) {
        when (action) {
            NavConfig.Action.BACK -> TabManager.goBackOrPrevious()
            NavConfig.Action.FORWARD -> TabManager.goForward()
            NavConfig.Action.HOME -> viewModel.goHome()
            NavConfig.Action.RELOAD -> TabManager.reload()
            NavConfig.Action.NEW_TAB -> viewModel.newTab(false)
            NavConfig.Action.HISTORY -> openScreen(HistoryActivity::class.java)
            NavConfig.Action.DOWNLOADS -> openScreen(DownloadsActivity::class.java)
            NavConfig.Action.AI -> com.nexo.browser.ai.NexoAiSheet.show(this)
            NavConfig.Action.SHIELD -> openShieldPanel()
            NavConfig.Action.EXTENSIONS -> openScreen(ExtensionsActivity::class.java)
            NavConfig.Action.READER -> com.nexo.browser.reader.NexoReader.openActiveTab(this)
            NavConfig.Action.BOOKMARKS -> openScreen(BookmarksActivity::class.java)
        }
    }

    /** Ocultación inteligente y suave del dock inferior al desplazarse (respeta reducción de movimiento). */
    private fun setDockScrollHidden(hide: Boolean) {
        if (liquidChrome || NavConfig.mode(this) == NavConfig.Mode.COMPACT || immersive) return
        if (dockScrollHidden == hide) return
        val now = android.os.SystemClock.uptimeMillis()
        // Al ocultar aplica histéresis breve para evitar parpadeos; al mostrar responde al instante.
        if (hide && now - lastDockToggleMs < 350L) return
        lastDockToggleMs = now
        dockScrollHidden = hide

        // Muelles Liquid Craft (380 / 0.82) en lugar de curvas accelerate-decelerate.
        browserDock.animate().cancel()
        if (hide) {
            val shift = NexoUi.dp(this, 24f).toFloat()
            NexoMotion.spring(browserDock, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 0f)
            NexoMotion.spring(browserDock, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, shift) {
                if (dockScrollHidden) {
                    browserDock.visibility = View.GONE
                    browserDock.translationY = 0f
                    browserDock.alpha = 1f
                }
            }
        } else {
            if (browserDock.visibility != View.VISIBLE) {
                browserDock.translationY = NexoUi.dp(this, 20f).toFloat()
                browserDock.alpha = 0f
            }
            browserDock.visibility = View.VISIBLE
            NexoMotion.spring(browserDock, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, 1f)
            NexoMotion.spring(browserDock, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_Y, 0f)
        }
        syncFloatingControls(animated = true)
        refreshCaptionsUi()
    }

    private fun fingerprint(): String =
        listOf(
            BrowserPrefs.theme(this), BrowserPrefs.accent(this),
            BrowserPrefs.searchEngine(this), BrowserPrefs.freeSearch(this).toString(),
            BrowserPrefs.trackingProtection(this).toString(),
            BrowserPrefs.maxLiveTabs(this).toString(),
            BrowserPrefs.dnsProvider(this), BrowserPrefs.cookieMode(this),
            BrowserPrefs.httpsOnly(this).toString(), BrowserPrefs.globalPrivacyControl(this).toString(),
            BrowserPrefs.smartPip(this).toString(), BrowserPrefs.backgroundMedia(this).toString(),
            BrowserPrefs.captionsEnabled(this).toString(),
            BrowserPrefs.navMode(this), BrowserPrefs.navActions(this),
            BrowserPrefs.chromeStyle(this), GlassPreferences.intensity.name,
            BrowserPrefs.autoHideBars(this).toString()
        ).joinToString("|")

    /*
     * MainActivity está exportada (launcher), así que cualquier app puede
     * enviarle EXTRA_OPEN_URL. Solo las pantallas propias, que llegan por el
     * alias NO exportado InternalOpenActivity, pueden abrir páginas internas
     * (about:…). Desde fuera solo se aceptan http/https.
     */
    private fun handleNavigationIntent(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false)) return
        val internal = intent.component?.className == INTERNAL_OPEN_ALIAS

        val raw = (
            if (internal) intent.getStringExtra(EXTRA_OPEN_URL)
            else externalNavigationTarget(intent)
            )
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        // Consumir el intent para no reabrirlo al volver a la Activity.
        intent.removeExtra(EXTRA_OPEN_URL)
        intent.putExtra(EXTRA_CONSUMED, true)

        val allowed =
            if (internal) UrlSafety.isAppNavigable(raw)
            else UrlSafety.isExternallyOpenable(raw)

        if (!allowed) {
            Toast.makeText(
                this,
                "Nexo bloqueó un enlace con esquema no permitido",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Enlaces de otras apps: pestaña nueva (como otros navegadores). Así,
        // al abrir Nexo en frío, la restauración de la pestaña anterior no
        // puede pisar la URL pedida.
        if (internal) TabManager.navigate(raw) else TabManager.createTab(raw)
    }

    /**
     * Enlaces de otras apps: VIEW (Nexo como navegador predeterminado),
     * texto compartido y búsquedas web del sistema.
     */
    private fun externalNavigationTarget(intent: Intent): String? =
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                SHARED_URL.find(text)?.value ?: SearchResolver.resolve(this, text.take(500))
            }
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH ->
                intent.getStringExtra(SearchManager.QUERY)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { SearchResolver.resolve(this, it.take(500)) }
            else -> intent.getStringExtra(EXTRA_OPEN_URL)
        }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (MigrationMode.enabled(this)) return
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    override fun onBeforeTabSwitch(leaving: BrowserTab) {
        if (leaving.session === attachedSession) {
            captureCurrentTabPreview(force = true)
        }
    }

    override fun onActiveTabChanged(tab: BrowserTab, session: EngineSession) {
        val scope = tab.burnerId?.let { "burner:$it" } ?: if (tab.isPrivate) "private" else "normal"
        if (findPrivacyScope != scope) {
            hideFindBar()
            findInput.text?.clear()
            findPrivacyScope = scope
        }
        if (attachedSession !== session) {
            hideFindBar()
            setDockScrollHidden(false)
            // Los subtítulos son de la pestaña visible: no seguir transcribiendo otra.
            attachedSession?.let { LiveCaptions.stop(it) }
            // Un permiso pedido desde otra pestaña no se concede desde esta.
            attachedSession?.let { SitePermissionPrompts.cancelFor(it) }
            webBackdrop?.reset()
            (engineView as? com.nexo.browser.engine.cefrium.CefriumPageView)?.selectTab(tab.id)
            // El motor separa la vista anterior y conserva la cubierta si se está recuperando esta pestaña.
            // Fondo del tema mientras la página pinta (evita el destello blanco).
            engineView.attach(session, ThemeManager.palette(this).background)
            attachedSession = session
            webBackdrop?.markDirty()
        }
        if (fullScreenActive != tab.isFullScreen) {
            fullScreenActive = tab.isFullScreen
            applyChromeVisibility()
        }
        renderTab(tab)
        renderPageProgress(tab.loadProgress, tab.isLoading)
        if (!tab.isLoading) browserFrame.finishRefresh()
        // Si esta pestaña fue marcada como peligrosa mientras cargaba en segundo plano, mostrar el aviso ahora.
        com.nexo.browser.shield.MalwareScan.resultFor(tab)?.takeIf {
            it.decision.block && !com.nexo.browser.shield.MalwareScan.isBypassed(it.origin)
        }?.let { result ->
            com.nexo.browser.shield.MalwareWarning.show(this, tab, result)
        }
        updatePipParams()
    }

    override fun onFullScreenChanged(tab: BrowserTab, fullScreen: Boolean) {
        fullScreenActive = fullScreen
        applyChromeVisibility()
        syncPredictiveBack()
    }

    private val immersive: Boolean
        get() = isInPictureInPictureMode || fullScreenActive

    /**
     * Barras de Nexo y del sistema según el modo: normal, pantalla completa
     * de un video, o ventana PiP (solo el contenido web).
     */
    private fun applyChromeVisibility(forcePip: Boolean = false) {
        val pip = forcePip || isInPictureInPictureMode
        val hideChrome = pip || fullScreenActive
        val tab = TabManager.activeTab()
        val compact = NavConfig.mode(this) == NavConfig.Mode.COMPACT

        if (hideChrome) {
            browserDock.animate().cancel()
            browserDock.visibility = View.GONE
            navBar.visibility = View.GONE
            setChromeCompact(false)
        } else {
            navBar.visibility = View.VISIBLE
            browserDock.visibility = if (liquidChrome || compact || dockScrollHidden) View.GONE else View.VISIBLE
        }
        compactBackButton.visibility = if (!hideChrome && compact && !liquidChrome) View.VISIBLE else View.GONE
        bottomChrome.visibility = if (hideChrome) View.GONE else View.VISIBLE
        topBar.visibility = if (hideChrome || chromeCompact) View.INVISIBLE else View.VISIBLE
        if (hideChrome) topBar.visibility = View.GONE
        privateBanner.visibility =
            if (!hideChrome && tab?.burnerId != null) View.VISIBLE else View.GONE
        statusScrim.visibility = if (hideChrome) View.GONE else View.VISIBLE
        if (hideChrome) extensionInstallBanner.visibility = View.GONE else tab?.let { updateExtensionInstallBanner(it) }
        progress.visibility =
            if (!hideChrome && tab?.isLoading == true) View.VISIBLE else View.GONE
        nexoAiPill.setSuppressed(hideChrome)

        setSystemBarsHidden(fullScreenActive && !pip)
        fullscreenMediaOverlay?.setActive(fullScreenActive && !pip)
        if (hideChrome) startPage.visibility = View.GONE else tab?.let { updateStartPage(it) }
        updateMiniPlayerChip()
        layoutEdgeToEdge()
        root.requestLayout()
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (hidden) {
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            if (hidden) {
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                ThemeManager.applyWindow(this)
            }
        }
    }

    override fun onTabChanged(tab: BrowserTab) {
        if (tab.id == TabManager.activeTab()?.id) {
            if (findDocument != tab.navigationEpoch) hideFindBar()
            renderTab(tab)
            if (!tab.isLoading && !BrowserPrefs.isNexoHome(tab.url)) {
                scheduleIdlePreview(tab, delayMs = 950L)
            }
        }
    }

    override fun onProgress(tab: BrowserTab, progress: Int, loading: Boolean) {
        if (tab.id != TabManager.activeTab()?.id) return
        webBackdrop?.markDirty()
        renderPageProgress(progress, loading)
        if (!loading) {
            browserFrame.finishRefresh()
            scheduleIdlePreview(tab)
        }
    }

    private fun renderPageProgress(value: Int, loading: Boolean) {
        progress.progress = value
        progress.visibility = if (loading && !immersive) View.VISIBLE else View.GONE
    }

    override fun onTabCountChanged(count: Int) {
        tabButton.text = if (count > 99) "99+" else count.toString()
    }

    override fun onMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun isNativeInputFocused(): Boolean =
        (::omnibox.isInitialized && omnibox.hasFocus()) ||
            (::findInput.isInitialized && findInput.hasFocus())

    override fun onBrowserMediaStateChanged() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            BrowserPrefs.backgroundMedia(this) &&
            BrowserMediaController.isAnyPlaying() &&
            !mediaNotificationPermissionAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            mediaNotificationPermissionAsked = true
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_MEDIA_NOTIFICATIONS
            )
        }
        updateMiniPlayerChip()
        updatePipParams()
        followPipVideo()
        updateKeepScreenOn()
    }

    /** Mientras se ve un video la pantalla no se apaga (como en cualquier reproductor). */
    private fun updateKeepScreenOn() {
        val tab = TabManager.activeTab()
        val watching = tab != null && BrowserMediaController.isVideoPlaying(tab.id)
        if (watching == keepScreenOnApplied) return
        keepScreenOnApplied = watching
        if (watching) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Si el video cambia de sitio durante el PiP, mover el recorte con él. */
    private fun followPipVideo() {
        val current = pipCrop ?: return
        if (!isInPictureInPictureMode) return
        val tab = TabManager.activeTab() ?: return
        val rect = BrowserMediaController.videoRect(tab.id) ?: return
        if (!rect.intersect(0, 0, pageView.width, pageView.height)) return
        if (rect == current || rect.width() < 40 || rect.height() < 40) return
        pipCrop = rect
        applyPipCrop()
    }

    /** Página de inicio de Nexo en pestañas nuevas (about:blank). */
    private fun updateStartPage(tab: BrowserTab) {
        val show = BrowserPrefs.isNexoHome(tab.url) && !immersive
        if (show) {
            setDockScrollHidden(false)
            setChromeCompact(false)
            // Re-dibujar solo si cambió la pestaña, el tema o la privacidad.
            val key = tab.id + "|" + tab.isPrivate + "|" + ThemeManager.themeKey(this)
            if (key != startPageKey || startPage.visibility != View.VISIBLE) {
                startPage.render(tab.isPrivate)
                startPageKey = key
            }
        }
        val changed = startPage.visibility != (if (show) View.VISIBLE else View.GONE)
        startPage.visibility = if (show) View.VISIBLE else View.GONE
        if (changed) { styleChromeGlass(); layoutEdgeToEdge() }
    }

    /** Muestra el dominio limpio dentro de la cápsula (el icono de estado vive en tabToolsButton). */
    private fun renderOmnibox(tab: BrowserTab) {
        setOmniboxIcon(null)
        if (BrowserPrefs.isNexoHome(tab.url)) {
            omnibox.setText("")
            miniDomain.text = ""
            return
        }
        val shown = displayUrl(tab.url)
        omnibox.setText(shown)
        miniDomain.text = shown
        miniLock.setImageResource(if (tab.isPrivate) R.drawable.ic_menu_private else R.drawable.ic_lock)
        miniPill.contentDescription = "$shown. Mostrar barra de navegación"
    }

    private fun setOmniboxIcon(icon: Int?) {
        val drawable = icon?.let { getDrawable(it)?.mutate() }
        omnibox.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
    }

    private fun displayUrl(url: String): String {
        if (!UrlSafety.isWebUrl(url)) return "Página interna de Nexo"
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return url
        return host.removePrefix("www.").removePrefix("m.")
    }

    private fun renderTab(tab: BrowserTab) {
        WindowPrivacy.apply(window, tab.isPrivate)
        // Burner: la píldora flotante muestra el correo temporal y abre su buzón.
        privateBanner.text = if (tab.burnerId != null) {
            BurnerMailbox.address(tab.burnerId)?.let { "Burner · $it  ›" } ?: "Burner · identidad efímera · buzón  ›"
        } else "Navegación privada"
        privateBanner.isClickable = tab.burnerId != null
        privateBanner.contentDescription = if (tab.burnerId != null) "Abrir el buzón de esta identidad Burner" else null
        stylePrivateBanner(tab)
        styleTabToolsButton(tab)
        if (!omnibox.hasFocus()) renderOmnibox(tab)
        updateStartPage(tab)
        val canBack = TabManager.canGoBackOrPrevious()
        backButton.isEnabled = canBack
        backButton.alpha = if (canBack) 1f else 0.36f
        compactBackButton.isEnabled = canBack
        compactBackButton.alpha = if (canBack) 1f else 0.36f
        forwardButton.isEnabled = tab.canGoForward
        forwardButton.alpha = if (tab.canGoForward) 1f else 0.36f
        privateBanner.visibility =
            if (!immersive && tab.burnerId != null) View.VISIBLE else View.GONE
        styleChromeGlass()
        updateExtensionInstallBanner(tab)
        title = if (tab.isPrivate) "Navegación privada" else tab.title
        updateMiniPlayerChip()
        syncPredictiveBack()
    }

    /**
     * Cuando el usuario abre una ficha de Chrome Web Store o Microsoft Edge Add-ons, ofrece la
     * barra contextual nativa «Instalar en Nexo» o «Gestionar en Nexo» si ya está instalada.
     */
    private fun updateExtensionInstallBanner(tab: BrowserTab) {
        if (immersive || BrowserPrefs.isNexoHome(tab.url)) {
            currentStoreExtension = null
            extensionInstallBanner.visibility = View.GONE
            return
        }
        val target = com.nexo.browser.extensions.hub.ExtensionStoreResolver.detectStorePage(tab.url)
        if (target == null) {
            currentStoreExtension = null
            extensionInstallBanner.visibility = View.GONE
            return
        }
        val key = "${target.store.name}:${target.extensionId}"
        if (dismissedStoreKeys.contains(key)) {
            currentStoreExtension = target
            extensionInstallBanner.visibility = View.GONE
            return
        }
        currentStoreExtension = target
        val installed = com.nexo.browser.extensions.nxr.NxrRuntime.installed(this)
            .firstOrNull { it.id == target.extensionId || it.storeId == target.extensionId }
        if (installed != null) {
            val state = com.nexo.browser.extensions.nxr.NxrRuntime.lifecycleState(installed.id, this)
            extensionBannerText.text = "${installed.name} · ${state.label} en Nexo"
            extensionBannerAction.text = "Gestionar"
        } else {
            val label = tab.title
                .replace(" - Chrome Web Store", "", ignoreCase = true)
                .replace(" - Microsoft Edge Addons", "", ignoreCase = true)
                .trim()
                .takeIf { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }
                ?: target.store.label
            extensionBannerText.text = "$label · ${target.store.label}"
            extensionBannerAction.text = "Instalar en Nexo"
        }
        extensionInstallBanner.visibility = View.VISIBLE
    }

    override fun onStart() {
        if (MigrationMode.enabled(this)) { super.onStart(); return }
        super.onStart()
        activityStopped = false
        val tab = TabManager.activeTab()
        if (tab != null && tab.session == null) TabManager.switchTo(tab.id)
        else if (!isInPictureInPictureMode) {
            TabManager.resumeActive()
            // La superficie de video se recrea al volver: el puente repara el reproductor si quedó congelado.
            val session = tab?.session
            if (session != null && wasBackgrounded) pageView.postDelayed({ ExtensionManager.sendLifecycle(session, true) }, 250L)
        }
        wasBackgrounded = false
    }

    override fun onResume() {
        if (MigrationMode.enabled(this)) { super.onResume(); return }
        suppressAutoPipOnce = false
        backgroundPlaybackWasActive = false

        if (!isInPictureInPictureMode) {
            TabManager.activeTab()?.let { tab ->
                BrowserMediaController.clearPlaybackHold(tab.id)
            }
        }

        super.onResume()
        (engineView as? com.nexo.browser.engine.cefrium.CefriumPageView)?.onHostResume()
        webBackdrop?.setRunning(true)
        if (TabManager.activeTab()?.isPrivate != true) com.nexo.browser.engine.cefrium.SpareRendererPool.start(this)
        BrowserHosts.attach(this)
        LiveCaptions.reapply(this)
        refreshCaptionsUi()
        ExtensionManager.attachPromptActivity(this)
        AppUpdater.resumePendingInstallIfAllowed(this)
        TabManager.activeTab()?.takeIf { BrowserPrefs.isNexoHome(it.url) }?.let { startPage.render(it.isPrivate) }
        TabManager.activeTab()?.let { updateExtensionInstallBanner(it) }
        ExtensionManager.consumePendingAction { ok, message ->
            if (!ok) runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
        // Paso desde la app anterior: la nueva ofrece traer los datos; la anterior avisa de la mudanza.
        root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            LegacyImport.offerIfNeeded(this) {
                com.nexo.browser.engine.Engines.current.applySettings(this)
                TabManager.reapplySettings()
                recreate()
            }
            LegacyImport.noticeInOldApp(this)
            AppUpdater.checkIfDue(this)
        }, 900L)
        if (themeKey.isNotBlank() && themeKey != ThemeManager.themeKey(this)) {
            // Diálogos, menús y controles del sistema dependen del tema de la
            // Activity: recrearla para aplicarlo entero.
            recreate()
            return
        }
        if (settingsFingerprint.isNotBlank() && settingsFingerprint != fingerprint()) {
            ThemeManager.applyWindow(this)
            applyTheme()
            com.nexo.browser.engine.Engines.current.applySettings(this)
            TabManager.reapplySettings()
            ExtensionManager.sendBrowserState(this)
            MediaPlaybackService.sync(this)
            updatePipParams()
        }
    }

    override fun onPause() {
        if (!MigrationMode.enabled(this)) {
            captureCurrentTabPreview(force = true)
            (engineView as? com.nexo.browser.engine.cefrium.CefriumPageView)?.onHostPause()
            // En PiP sin audio de fondo persistente, reenviar onPause() a Cefrium 0.22.0:
            // mantiene el video visible en la ventana mini, lo recupera al encender pantalla
            // en Android 10 y pausa automáticamente al cerrar PiP. Fuera de PiP la pausa real
            // sigue ocurriendo en onStop() para no ocultar páginas bajo diálogos o pantalla dividida.
            if (isInPictureInPictureMode) {
                val tab = TabManager.activeTab()
                val keepAudioOnClose = tab != null &&
                    BrowserPrefs.backgroundMedia(this) &&
                    BrowserMediaController.isPlaybackActive(tab.id)
                if (!keepAudioOnClose) {
                    (tab?.session as? com.nexo.browser.engine.cefrium.CefriumEngineSession)?.onHostPauseInPip()
                }
            }
            webBackdrop?.setRunning(false)
            com.nexo.browser.engine.cefrium.SpareRendererPool.stop(this)
        }
        super.onPause()
    }

    private fun stopDeviceCaptions(session: EngineSession?) {
        if (session != null && LiveCaptions.usingDeviceAudio(session)) LiveCaptions.stop(session)
    }

    override fun onStop() {
        if (MigrationMode.enabled(this)) { super.onStop(); return }
        (engineView as? com.nexo.browser.engine.cefrium.CefriumPageView)?.onHostStop()
        // La captura se solicitó en onPause, con la superficie todavía disponible.
        pageView.removeCallbacks(idlePreview)

        if (!isInPictureInPictureMode) {
            val tab = TabManager.activeTab()
            stopDeviceCaptions(tab?.session)

            ExtensionManager.sendLifecycle(tab?.session, false)
            wasBackgrounded = true
            TabManager.prepareForBackground()

            /*
             * Si el usuario salió mientras multimedia estaba
             * reproduciéndose, damos al motor una segunda
             * oportunidad de continuar después de la transición.
             */
            if (backgroundPlaybackWasActive && BrowserPrefs.backgroundMedia(this) && tab != null) {
                listOf(180L, 650L).forEach { delay ->
                    pageView.postDelayed(
                        { BrowserMediaController.resumeIfRecent(tab.id, 8_000L) },
                        delay
                    )
                }
            }
        }

        activityStopped = true
        super.onStop()
    }

    // ---------------------------------------------------------------
    // Mini reproductor / Picture-in-Picture
    // ---------------------------------------------------------------

    private fun isYoutubeVideoPage(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()

        return when (host) {
            "youtu.be" -> path.length > 1
            "youtube.com", "www.youtube.com", "m.youtube.com" ->
                path == "/watch" ||
                    path.startsWith("/shorts/") ||
                    path.startsWith("/live/")
            else -> false
        }
    }

    private fun isMiniPlaybackActive(tab: BrowserTab): Boolean =
        BrowserMediaController.isVideoPlaying(tab.id) ||
            BrowserMediaController.isPlaying(tab.id)

    private fun hasMiniVideo(tab: BrowserTab): Boolean =
        BrowserMediaController.isVideoPresent(tab.id) ||
            BrowserMediaController.isVideoPlaying(tab.id) ||
            (isYoutubeVideoPage(tab.url) && BrowserMediaController.isPlaying(tab.id))

    private fun isMiniVideoAvailable(): Boolean {
        val tab = TabManager.activeTab() ?: return false
        return BrowserPrefs.smartPip(this) && hasMiniVideo(tab)
    }

    /** Página (pestaña + dirección) donde se ocultó el botón Mini con una pulsación larga. */
    private var miniChipHiddenFor: String? = null

    private fun updateMiniPlayerChip() {
        val tab = TabManager.activeTab()
        val key = tab?.let { it.id + "|" + it.url }
        setMiniChipVisible(!immersive && !isInPictureInPictureMode && isMiniVideoAvailable() && key != miniChipHiddenFor)
        refreshCaptionsUi()
    }

    // ---------------------------------------------------------------
    // Subtítulos en vivo
    // ---------------------------------------------------------------

    // Los subtítulos cambian varias veces por segundo (texto provisional): se dibujan como
    // mucho cada ~66 ms, agrupando los cambios, para no recolocar la pantalla en cada uno.
    private var captionRenderPending = false
    private var lastCaptionRender = 0L
    private val captionRender = Runnable {
        captionRenderPending = false
        lastCaptionRender = android.os.SystemClock.uptimeMillis()
        refreshCaptionsUi()
    }

    override fun onCaptionsChanged(session: EngineSession) {
        if (session !== attachedSession) return
        if (!captionRenderPending) {
            captionRenderPending = true
            val wait = (66L - (android.os.SystemClock.uptimeMillis() - lastCaptionRender)).coerceAtLeast(0L)
            captionView.postDelayed(captionRender, wait)
        }
        val message = LiveCaptions.message(session)
        if (LiveCaptions.state(session) == LiveCaptions.State.ERROR && message != null && message != lastCaptionMessage) {
            lastCaptionMessage = message
            // Sin ningún texto en el video: preguntar (nada se graba ni se envía sin elegirlo).
            if (LiveCaptions.takeNoTextChoice(session)) {
                SystemCaptions.showNoTextChoice(this, session) { refreshCaptionsUi() }
                return
            }
            // La página no pudo dar su audio: Android puede escucharlo directamente (una vez por activación).
            if (Build.VERSION.SDK_INT >= 29 && BrowserPrefs.deepgramKey(this).isNotBlank() &&
                !PlaybackAudioCapture.awaitingConsent() && LiveCaptions.takeDeviceSuggestion(session)
            ) {
                PlaybackAudioCapture.request(this, reason = message)
                return
            }
            // Los subtítulos siguen activos: si el usuario enciende los del reproductor,
            // Nexo los toma solo. Mantener pulsado CC abre la configuración (clave).
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private val captionHintExpiry = Runnable { refreshCaptionsUi() }

    /** Permite encender o ajustar Subtítulos en vivo directamente desde Nexo Media Engine. */
    internal fun openCaptionsFromMediaSheet() = toggleCaptions()

    /** Pulsación larga en Atrás/Adelante: hoja con el historial nativo de la pestaña. */
    private fun showTabHistorySheet(backwards: Boolean): Boolean {
        val session = attachedSession as? com.nexo.browser.engine.cefrium.CefriumEngineSession ?: return false
        val items = SiteControlModel.buildHistoryItems(session.navigationEntries(), backwards = backwards)
        if (items.isEmpty()) return false
        val p = ThemeManager.palette(this)
        val dp = { v: Float -> NexoUi.dp(this, v) }
        lateinit var sheet: android.app.Dialog
        val body = NexoUi.column(this, horizontalDp = NexoUi.Tokens.SHEET_PADDING)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(this@MainActivity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
            clipToOutline = true
            NexoGlass.paperShadow(this, p)
        }
        items.forEachIndexed { index, item ->
            if (index > 0) {
                card.addView(NexoUi.insetDivider(this).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(52f)
                })
            }
            val title = item.title.ifBlank { displayUrl(item.url) }
            val subtitle = displayUrl(item.url)
            val icon = if (backwards) R.drawable.ic_nav_back else R.drawable.ic_nav_forward
            card.addView(NexoUi.actionRow(this, icon, title, subtitle.takeIf { it != title }) {
                sheet.dismiss()
                session.goBackOrForward(item.offset)
            })
        }
        body.addView(card)
        val heading = if (backwards) "Historial atrás" else "Historial adelante"
        sheet = NexoSheets.show(this, NexoSheets.column(this, NexoSheets.header(this, heading), body))
        return true
    }

    private fun toggleCaptions() {
        val session = attachedSession ?: return
        // Activos: la hoja rápida permite elegir idioma, captura universal y modo, o apagarlos.
        if (LiveCaptions.isActive(session)) {
            CaptionsUi.showQuick(this, session, onChange = { refreshCaptionsUi() }) {
                LiveCaptions.stop(session)
                Toast.makeText(this, "Subtítulos desactivados", Toast.LENGTH_SHORT).show()
                refreshCaptionsUi()
            }
            return
        }
        // Sin clave también funciona con videos que traen subtítulos propios; si no, la página lo avisa.
        lastCaptionMessage = null
        LiveCaptions.toggle(this, session)
        if (Build.VERSION.SDK_INT >= 29 &&
            BrowserPrefs.captionPreferDeviceAudio(this) &&
            BrowserPrefs.deepgramKey(this).isNotBlank()
        ) {
            PlaybackAudioCapture.request(this)
            refreshCaptionsUi()
            return
        }
        val target = BrowserPrefs.captionTranslateTo(this)
        Toast.makeText(this,
            if (target.isBlank()) "Subtítulos activados · toca CC para captura universal o traducción"
            else "Subtítulos activados · traducción: ${CaptionProtocol.languageLabel(target)}",
            Toast.LENGTH_SHORT).show()
        refreshCaptionsUi()
    }

    private fun refreshCaptionsUi() {
        if (!::captionsButton.isInitialized) return
        val tab = TabManager.activeTab()
        val session = attachedSession
        val active = LiveCaptions.isActive(session)
        // Mostrar CC cuando hay video/audio (DOM, MediaController o audio nativo de Chromium incluyendo iframes/WebAudio).
        val nativeAudio = (session as? com.nexo.browser.engine.cefrium.CefriumEngineSession)?.isPlayingAudio() == true
        val hasMediaHere = tab != null && (hasMiniVideo(tab) || BrowserMediaController.isPlaybackActive(tab.id) || nativeAudio)
        val available = tab != null && (active || (BrowserPrefs.captionsEnabled(this) && !BrowserPrefs.isNexoHome(tab.url) && hasMediaHere))
        captionsButton.visibility = if (available && !immersive && !isInPictureInPictureMode) View.VISIBLE else View.GONE
        CaptionsUi.styleChip(
            this,
            captionsButton,
            active,
            backdrop = webBackdrop,
            backdropSource = pageView,
            smoked = tab?.isPrivate == true
        )
        // Las líneas de estado (no subtítulos) no se muestran en la ventana mini.
        val display = (if (active) LiveCaptions.display(this, session) else null)
            ?.takeUnless { it.status && isInPictureInPictureMode }
        CaptionsUi.render(
            this, captionView, browserFrame.width, browserFrame.height,
            if (pipCrop != null || isInPictureInPictureMode) null else tab?.let { BrowserMediaController.videoRect(it.id) },
            display,
            isInPictureInPictureMode,
            hint = display?.status == true,
            bottomObstruction = captionBottomObstruction(),
            topObstruction = if (immersive) 0 else safeTop
        )
        // El aviso genérico caduca: volver a dibujar para retirarlo.
        captionView.removeCallbacks(captionHintExpiry)
        if (display?.status == true && LiveCaptions.statusExpires(session)) {
            captionView.postDelayed(captionHintExpiry, LiveCaptions.HINT_MS + 150L)
        }
        syncFloatingControls(animated = false)
    }

    /** Parte del pie de la página tapada por el cromo flotante (la web fluye por detrás). */
    private fun captionBottomObstruction(): Int {
        if (!::bottomChrome.isInitialized || bottomChrome.visibility != View.VISIBLE || immersive) return 0
        val pageBottom = (browserFrame.layoutParams as? android.widget.FrameLayout.LayoutParams)?.bottomMargin ?: 0
        val chrome = when {
            chromeCompact && liquidChrome -> NexoUi.dp(this, 48f) + safeBottom
            dockScrollHidden && !liquidChrome -> (bottomChrome.height - browserDock.height).coerceAtLeast(safeBottom)
            else -> bottomChrome.height
        }
        return (chrome - pageBottom).coerceAtLeast(0) + NexoUi.dp(this, 6f)
    }

    /**
     * Píldora compacta Liquid Glass sobre el video (32 dp visuales en objetivo táctil de 48 dp),
     * coherente con la mini-píldora del cromo inferior.
     */
    private fun styleMiniPlayerChip() {
        val isPriv = TabManager.activeTab()?.isPrivate == true
        CaptionsUi.styleChip(
            this,
            miniPlayerButton,
            false,
            backdrop = webBackdrop,
            backdropSource = pageView,
            smoked = isPriv
        )
        miniPlayerButton.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(ThemeManager.palette(this).text)
        miniPlayerButton.isAllCaps = false
        miniPlayerButton.typeface = NexoUi.medium(this)
        miniPlayerButton.contentDescription = "Abrir el video en una ventana flotante. Mantén pulsado para ocultar este botón."
        miniPlayerButton.setOnLongClickListener {
            miniChipHiddenFor = TabManager.activeTab()?.let { it.id + "|" + it.url }
            setMiniChipVisible(false)
            Toast.makeText(this, "Botón Mini oculto en esta página.", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun setMiniChipVisible(show: Boolean) {
        val visible = miniPlayerButton.visibility == View.VISIBLE
        // Se llama a menudo (cambios de medios): no reiniciar una animación ya en curso.
        val target = miniPlayerButton.getTag(R.id.miniPlayerButton) as? Boolean
        if (target == show && (!show || visible)) return
        miniPlayerButton.setTag(R.id.miniPlayerButton, show)
        miniPlayerButton.animate().cancel()
        if (show) {
            if (!visible) {
                miniPlayerButton.alpha = 0f
                miniPlayerButton.scaleX = 0.85f
                miniPlayerButton.scaleY = 0.85f
                miniPlayerButton.visibility = View.VISIBLE
            }
            miniPlayerButton.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        } else if (visible) {
            miniPlayerButton.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(140L).withEndAction {
                miniPlayerButton.visibility = View.GONE
                miniPlayerButton.alpha = 1f
                miniPlayerButton.scaleX = 1f
                miniPlayerButton.scaleY = 1f
                syncFloatingControls(animated = false)
            }.start()
        }
        syncFloatingControls(animated = false)
    }

    internal fun startMiniPlayer(
        silent: Boolean = false,
        requirePlayback: Boolean = false,
        delayMs: Long = 120L
    ) {
        if (isInPictureInPictureMode) return
        pendingPipEntry?.let { pending ->
            if (delayMs <= 0L) {
                pageView.removeCallbacks(pending)
                pending.run()
            }
            return
        }

        val tab = TabManager.activeTab() ?: return
        val session = tab.session ?: return

        // Capturar el estado antes de tocar CSS, la sesión del motor o PiP.
        pipPlaybackWasActive = isMiniPlaybackActive(tab)

        if (!hasMiniVideo(tab)) {
            if (!silent) {
                Toast.makeText(this, "No se detectó un video para abrir Mini.", Toast.LENGTH_SHORT).show()
            }
            updateMiniPlayerChip()
            return
        }

        if (requirePlayback && !isMiniPlaybackActive(tab)) return

        // Mantener la sesión del motor activa durante la transición a PiP.
        TabManager.prepareForPictureInPicture()

        // Recorte nativo si conocemos el rectángulo del video; si no (p. ej.
        // video en un iframe), aislar el video con CSS como respaldo.
        val nativeCrop = lockForNativePip(tab)
        ExtensionManager.setPipMode(
            session,
            true,
            resumePlayback = pipPlaybackWasActive,
            isolateCss = !nativeCrop
        )

        miniPlayerButton.animate().cancel()
        miniPlayerButton.setTag(R.id.miniPlayerButton, false)
        miniPlayerButton.visibility = View.GONE

        val enterPip = Runnable {
            pendingPipEntry = null
            if (activityStopped || suppressAutoPipOnce || isFinishing || isDestroyed || attachedSession !== session ||
                TabManager.activeTab()?.id != tab.id) {
                ExtensionManager.setPipMode(session, false)
                releaseNativePip()
                return@Runnable
            }
            // Ocultar las barras ANTES de entrar: el primer cuadro de la
            // ventana mini ya debe ser solo el video.
            applyChromeVisibility(forcePip = true)

            val entered = runCatching {
                enterPictureInPictureMode(buildPipParams(tab.id))
            }.getOrDefault(false)

            if (!entered) {
                pipPlaybackWasActive = false
                ExtensionManager.setPipMode(session, false)
                releaseNativePip()
                applyChromeVisibility()
            }
        }

        /*
         * Botón manual: 120 ms para que JS prepare el aislamiento.
         * Home / cambio de app: delay 0 para evitar carrera con onStop().
         */
        pendingPipEntry = enterPip
        if (delayMs <= 0L) enterPip.run() else pageView.postDelayed(enterPip, delayMs)
    }

    private fun lockForNativePip(tab: BrowserTab): Boolean {
        val w = pageView.width
        val h = pageView.height
        if (w <= 0 || h <= 0) return false

        val rect = BrowserMediaController.videoRect(tab.id) ?: return false
        if (!rect.intersect(0, 0, w, h)) return false
        val minW = (80 * resources.displayMetrics.density).toInt()
        val minH = (45 * resources.displayMetrics.density).toInt()
        if (rect.width() < minW || rect.height() < minH) return false

        pipCrop = rect
        // Tamaño fijo: aunque la ventana se achique, el motor sigue viendo la misma
        // vista y la página no recibe ningún "resize".
        pageView.layoutParams = android.widget.FrameLayout.LayoutParams(w, h)
        return true
    }

    /** Amplía el rectángulo del video para cubrir la ventana PiP. */
    private fun applyPipCrop() {
        val crop = pipCrop ?: return
        val fw = browserFrame.width.toFloat()
        val fh = browserFrame.height.toFloat()
        if (fw <= 0f || fh <= 0f) return

        val scale = maxOf(fw / crop.width(), fh / crop.height())
        pageView.pivotX = 0f
        pageView.pivotY = 0f
        pageView.scaleX = scale
        pageView.scaleY = scale
        pageView.translationX = -crop.left * scale + (fw - crop.width() * scale) / 2f
        pageView.translationY = -crop.top * scale + (fh - crop.height() * scale) / 2f
    }

    private fun releaseNativePip() {
        if (pipCrop == null && pageView.scaleX == 1f && pageView.translationY == 0f) return
        pipCrop = null
        pageView.scaleX = 1f
        pageView.scaleY = 1f
        pageView.translationX = 0f
        pageView.translationY = 0f
        pageView.layoutParams = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    private fun buildPipParams(tabId: String?): PictureInPictureParams {
        val crop = pipCrop
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(
                if (crop != null) clampAspect(crop.width(), crop.height())
                else pipAspectRatio(tabId)
            )

        if (pageView.width > 0 && pageView.height > 0) {
            // El hint debe ir en coordenadas de ventana: solo el video.
            val location = IntArray(2)
            pageView.getLocationInWindow(location)
            val hint = if (crop != null) {
                Rect(crop).apply { offset(location[0], location[1]) }
            } else {
                Rect().takeIf { pageView.getGlobalVisibleRect(it) }
            }
            hint?.let { builder.setSourceRectHint(it) }
        }

        builder.setActions(pipActions(tabId))

        if (Build.VERSION.SDK_INT >= 33) {
            // Título y sitio del video en el menú de la ventana flotante (enmascarado en pestañas privadas).
            TabManager.activeTab()?.takeIf { it.id == tabId }?.let { tab ->
                if (tab.isPrivate) {
                    builder.setTitle("Navegación privada")
                } else {
                    val site = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.")
                    tab.title.takeIf { it.isNotBlank() }?.let { builder.setTitle(it.take(80)) }
                    site?.takeIf { it.isNotBlank() }?.let { builder.setSubtitle(it) }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 31) {
            /*
             * Nexo controla manualmente el PiP desde onUserLeaveHint().
             * autoEnter desactivado para no activar PiP al entrar en
             * Configuración, Historial, etc.
             */
            builder
                .setAutoEnterEnabled(false)
                .setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    private fun pipActions(tabId: String?): List<RemoteAction> {
        val playing = tabId != null && BrowserMediaController.isPlaybackActive(tabId)
        val (icon, label, command) =
            if (playing) Triple(R.drawable.ic_pip_pause, "Pausar", PIP_COMMAND_PAUSE)
            else Triple(R.drawable.ic_pip_play, "Reproducir", PIP_COMMAND_PLAY)

        fun action(iconRes: Int, description: String, value: String, requestCode: Int) =
            RemoteAction(
                Icon.createWithResource(this, iconRes), description, description,
                PendingIntent.getBroadcast(
                    this, requestCode,
                    Intent(ACTION_PIP_CONTROL).setPackage(packageName)
                        .putExtra(EXTRA_PIP_COMMAND, value),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

        val playback = action(icon, label, command, REQUEST_PIP_CONTROL)
        if (!ExtensionManager.hasMediaBridge(TabManager.activeSession())) return listOf(playback)
        return listOf(
            action(R.drawable.ic_pip_replay, getString(R.string.seek_backward), "seek_backward", 914),
            playback,
            action(R.drawable.ic_pip_forward, getString(R.string.seek_forward), "seek_forward", 915)
        )
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(buildPipParams(TabManager.activeTab()?.id)) }
    }

    private fun pipAspectRatio(tabId: String?): Rational {
        val (rawWidth, rawHeight) =
            if (tabId != null) BrowserMediaController.videoAspect(tabId) else 16 to 9
        return clampAspect(rawWidth, rawHeight)
    }

    private fun clampAspect(rawWidth: Int, rawHeight: Int): Rational {
        val width = rawWidth.coerceAtLeast(1)
        val height = rawHeight.coerceAtLeast(1)
        val ratio = width.toDouble() / height.toDouble()

        // Android PiP acepta ratios entre ~1:2.39 y 2.39:1; dejamos margen.
        return when {
            ratio < 0.42 -> Rational(42, 100)
            ratio > 2.38 -> Rational(238, 100)
            else -> Rational(width, height)
        }
    }

    override fun onUserLeaveHint() {
        if (MigrationMode.enabled(this) || PlaybackAudioCapture.awaitingConsent()) { super.onUserLeaveHint(); return }
        val tab = TabManager.activeTab()

        /*
         * Decidir ANTES de tocar el estado multimedia si vamos
         * realmente a Android PiP. PiP permanece visible y no
         * necesita foreground service ni reintentos de play().
         */
        val willEnterPip =
            !suppressAutoPipOnce &&
                !isInPictureInPictureMode &&
                BrowserPrefs.smartPip(this) &&
                tab != null &&
                hasMiniVideo(tab) &&
                isMiniPlaybackActive(tab)

        // El hold queda reservado para segundo plano real (sin PiP).
        backgroundPlaybackWasActive =
            !willEnterPip &&
                tab != null &&
                BrowserPrefs.backgroundMedia(this) &&
                BrowserMediaController.isPlaybackActive(tab.id)

        if (backgroundPlaybackWasActive && tab != null) {
            BrowserMediaController.holdPlayback(tab.id, 10_000L)
            MediaPlaybackService.sync(this)
        }

        if (willEnterPip) {
            startMiniPlayer(silent = true, requirePlayback = true, delayMs = 0L)
        }

        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ExtensionManager.setPipMode(
            attachedSession,
            isInPictureInPictureMode,
            resumePlayback = pipPlaybackWasActive
        )

        val tab = TabManager.activeTab()

        if (isInPictureInPictureMode) applyPipCrop() else releaseNativePip()

        when {
            isInPictureInPictureMode -> {
                /*
                 * El motor mantiene la sesión y el compositor activos.
                 * No forzar MediaSession.play(): en YouTube puede
                 * reiniciar el decoder y producir frames negros.
                 */
                TabManager.prepareForPictureInPicture()
            }

            activityStopped -> {
                // El usuario cerró la ventana mini (la X o arrastrándola).
                pipPlaybackWasActive = false
                stopDeviceCaptions(tab?.session)
                ExtensionManager.sendLifecycle(tab?.session, false)
                wasBackgrounded = true
                if (tab != null && BrowserPrefs.backgroundMedia(this) &&
                    BrowserMediaController.isPlaybackActive(tab.id)
                ) {
                    // Seguir como audio en segundo plano, con notificación.
                    BrowserMediaController.holdPlayback(tab.id, 10_000L)
                    TabManager.prepareForBackground()
                    MediaPlaybackService.sync(this)
                } else {
                    if (tab != null &&
                        !ExtensionManager.sendPipCommand(tab.session, PIP_COMMAND_PAUSE)
                    ) {
                        BrowserMediaController.pause(tab.id)
                    }
                    TabManager.prepareForBackground()
                }
            }

            else -> {
                // Volvió a pantalla completa desde la ventana mini.
                pipPlaybackWasActive = false
                TabManager.resumeActive()
                tab?.session?.let { session ->
                    pageView.postDelayed({ ExtensionManager.sendLifecycle(session, true) }, 350L)
                }
            }
        }

        runCatching {
            engineView.onPictureInPictureModeChanged(isInPictureInPictureMode)
        }
        applyChromeVisibility()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MEDIA_NOTIFICATIONS) MediaPlaybackService.sync(this)
        // Permisos que pidió el propio Chromium (p. ej. la cámara para un selector de archivos).
        if (requestCode !in permissionCallbacks &&
            com.nexo.browser.engine.Engines.current.onRequestPermissionsResult(requestCode, permissions, grantResults)) return
        permissionCallbacks.remove(requestCode)?.invoke(
            grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        )
    }

    // ---------------------------------------------------------------
    // BrowserHost: lo que piden las páginas web
    // ---------------------------------------------------------------

    override fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit) {
        val code = nextPermissionRequest++
        if (nextPermissionRequest > REQUEST_SITE_PERMISSIONS + 500) nextPermissionRequest = REQUEST_SITE_PERMISSIONS
        permissionCallbacks[code] = onResult
        suppressAutoPipOnce = true
        requestPermissions(permissions, code)
    }

    override fun launch(intent: Intent) {
        suppressAutoPipOnce = true
        startActivity(intent)
    }

    override fun suppressNextAutoPip() {
        suppressAutoPipOnce = true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PlaybackAudioCapture.REQUEST) { PlaybackAudioCapture.result(this,resultCode,data); return }
        // El registro de ComponentActivity entrega los resultados del selector de Cefrium.
    }

    /** Aplica un cambio de tema en vivo (desde el agente de Nexo AI o el cambio de modo del sistema). */
    internal fun applyLiveThemeFromAgent() {
        if (isFinishing || isDestroyed) return
        syncLiveTheme(config = null)
    }

    private fun syncLiveTheme(config: Configuration? = null) {
        startPageKey = ""
        ThemeManager.applyWindow(this, config)
        applyTheme()
        val active = TabManager.activeTab()
        if (active != null) {
            renderTab(active)
            if (BrowserPrefs.isNexoHome(active.url)) {
                startPage.render(active.isPrivate)
                startPageKey = active.id + "|" + active.isPrivate + "|" + ThemeManager.themeKey(this, config)
            }
        }
        com.nexo.browser.engine.Engines.current.applySettings(this)
        if (TabManager.isInitialized) TabManager.reapplySettings()
        ExtensionManager.sendBrowserState(this)
        refreshCaptionsUi()
        webBackdrop?.markDirty()
        themeKey = ThemeManager.themeKey(this, config)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (MigrationMode.enabled(this)) return
        // La Activity gestiona la rotación y cambios de modo claro/oscuro del sistema sin destruirse
        // (evita recrear sesiones Chromium y recargar páginas abiertas).
        com.nexo.browser.engine.Engines.current.onConfigurationChanged(this, newConfig)
        ViewCompat.requestApplyInsets(findViewById(android.R.id.content))
        val nextKey = ThemeManager.themeKey(this, newConfig)
        if (themeKey.isNotBlank() && themeKey != nextKey) {
            syncLiveTheme(newConfig)
        } else {
            styleChromeGlass()
            if (BrowserPrefs.isNexoHome(TabManager.activeTab()?.url)) {
                startPage.render(TabManager.activeTab()?.isPrivate == true)
            }
        }
    }

    override fun onDestroy() {
        if (MigrationMode.enabled(this)) { super.onDestroy(); return }
        findGeneration++
        runCatching { attachedSession?.finder?.clear() }
        aiControllerObserver?.let { com.nexo.browser.ai.NexoAiController.unobserve(it) }
        aiControllerObserver = null
        com.nexo.browser.shield.MalwareScan.removeObserver(malwareObserver)
        com.nexo.browser.shield.MalwareWarning.dismiss()
        PhantomShieldPanel.dismiss()
        com.nexo.browser.shield.PhantomShield.onActivityDestroyed()
        com.nexo.browser.engine.cefrium.SpareRendererPool.stop(this)
        GlassPreferences.removeListener(glassSettingsChanged)
        webBackdrop?.setRunning(false)
        if (::omnibox.isInitialized) omniboxKeyboard.cancel()
        if (::findInput.isInitialized) findKeyboard.cancel()
        if (::suggestions.isInitialized) suggestions.dispose()
        PageBridge.onScrollDirection = null
        unregisterPredictiveBack()
        PerfLog.detach(this)
        fullscreenMediaOverlay?.detach()
        pendingPipEntry?.let { pageView.removeCallbacks(it) }
        if (!isInPictureInPictureMode) ExtensionManager.setPipMode(attachedSession, false)
        runCatching { unregisterReceiver(pipControlReceiver) }
        BrowserMediaController.attach(null)
        LiveCaptions.attach(null)
        if (isFinishing && !isChangingConfigurations) LiveCaptions.stopAll()
        ExtensionManager.detachPromptActivity(this)
        BrowserHosts.detach(this)
        permissionCallbacks.values.forEach { it(false) }
        permissionCallbacks.clear()
        pageView.removeCallbacks(idlePreview)
        captionView.removeCallbacks(captionHintExpiry)
        captionView.removeCallbacks(captionRender)
        captionRenderPending = false
        TabManager.attach(null)
        if (attachedSession != null) {
            engineView.release()
            attachedSession = null
        }
        // No cerrar las sesiones del motor al recrear o destruir solo la Activity.
        if (isFinishing && !isChangingConfigurations && BrowserPrefs.clearOnExit(this)) {
            BrowsingData.clear(this)
        }
        super.onDestroy()
    }

    private fun canInterceptBack(): Boolean =
        findBar.visibility == View.VISIBLE ||
            omnibox.hasFocus() ||
            fullScreenActive ||
            TabManager.canGoBackOrPrevious()

    /**
     * Predictive Back (Android 13+): registra el callback solo cuando Nexo tiene
     * una acción interna pendiente; en la raíz de la pestaña lo suelta para que
     * Android muestre la animación nativa de regreso al inicio.
     */
    private fun syncPredictiveBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = predictiveBackCallback as? android.window.OnBackInvokedCallback ?: return
        val shouldRegister = canInterceptBack()
        if (shouldRegister && !backCallbackRegistered) {
            runCatching {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    cb
                )
                backCallbackRegistered = true
            }
        } else if (!shouldRegister && backCallbackRegistered) {
            unregisterPredictiveBack()
        }
    }

    private fun unregisterPredictiveBack() {
        if (Build.VERSION.SDK_INT < 33 || !backCallbackRegistered) return
        val cb = predictiveBackCallback as? android.window.OnBackInvokedCallback ?: return
        runCatching { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb) }
        backCallbackRegistered = false
    }

    private fun handleBackNavigation(): Boolean {
        if (findBar.visibility == View.VISIBLE) {
            hideFindBar()
            return true
        }
        if (omnibox.hasFocus()) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(omnibox.windowToken, 0)
            omnibox.clearFocus()
            return true
        }
        if (TabManager.exitFullScreen()) return true
        if (TabManager.canGoBackOrPrevious()) {
            TabManager.goBackOrPrevious()
            return true
        }
        return false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!handleBackNavigation()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_OPEN_URL = "nexo.open_url"
        private const val EXTRA_CONSUMED = "nexo.intent_consumed"
        private const val REQUEST_MEDIA_NOTIFICATIONS = 912
        private const val REQUEST_PIP_CONTROL = 913
        private const val REQUEST_SITE_PERMISSIONS = 2000

        /** Acciones del menú que solo tienen sentido con una página abierta. */
        private val PAGE_ONLY_ACTIONS = setOf(
            R.drawable.ic_menu_copy, R.drawable.ic_menu_share, R.drawable.ic_menu_find,
            R.drawable.ic_menu_pin, R.drawable.ic_menu_screenshot, R.drawable.ic_menu_pdf,
            R.drawable.ic_menu_translate, R.drawable.ic_menu_desktop, R.drawable.ic_menu_reader
        )

        /** Primer enlace web dentro de un texto compartido. */
        private val SHARED_URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

        private const val ACTION_PIP_CONTROL = "com.nexo.browser.action.PIP_CONTROL"
        private const val EXTRA_PIP_COMMAND = "command"
        private const val PIP_COMMAND_PLAY = "play"
        private const val PIP_COMMAND_PAUSE = "pause"

        /** Alias no exportado declarado en AndroidManifest.xml. */
        private val INTERNAL_OPEN_ALIAS =
            MainActivity::class.java.name.substringBeforeLast('.') + ".InternalOpenActivity"


        /**
         * Intent para que las pantallas propias abran una URL en el navegador.
         * Pasa por el alias no exportado, que es lo que permite URLs internas.
         */
        fun openUrlIntent(context: Context, url: String): Intent =
            Intent().apply {
                component = ComponentName(context, INTERNAL_OPEN_ALIAS)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_OPEN_URL, url)
            }
    }
}
