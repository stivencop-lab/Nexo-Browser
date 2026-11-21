package com.nexo.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Capturas de la interfaz nativa para revisión visual. Solo con -PnexoScreenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h900dp-xhdpi")
class ScreenshotTest {
    private val out = File(System.getProperty("nexo.shots") ?: "build/nexo-shots").apply { mkdirs() }

    private fun save(view: View, name: String) {
        val width = view.width.takeIf { it > 0 } ?: 824
        val height = view.height.takeIf { it > 0 } ?: 1800
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        FileOutputStream(File(out, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun measureAndSave(activity: Activity, view: View, name: String, heightPx: Int = 1800) {
        val spec = View.MeasureSpec.makeMeasureSpec(824, View.MeasureSpec.EXACTLY)
        view.measure(spec, View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        save(view, name)
    }

    @Test fun settings() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.findViewById<View>(R.id.settingsRoot)
        val scroll = (root as android.view.ViewGroup)
        save(activity.window.decorView, "settings")
        // Contenido completo (desplazable).
        val content = (0 until scroll.childCount).map { scroll.getChildAt(it) }
            .firstOrNull { it is android.widget.ScrollView } as? android.widget.ScrollView
        content?.getChildAt(0)?.let { measureAndSave(activity, it, "settings-full", 8000) }
    }

    @Test fun history() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        HistoryStore.add(app, "https://es.wikipedia.org/wiki/Navegador_web", "Navegador web - Wikipedia")
        HistoryStore.add(app, "https://www.youtube.com/watch?v=abc", "Video de prueba - YouTube")
        HistoryStore.add(app, "https://github.com/", "GitHub")
        Thread.sleep(300)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val activity = Robolectric.buildActivity(HistoryActivity::class.java).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "history")
    }

    @Test fun startPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ThemeManager.applyWindow(activity)
        val page = StartPageView(activity, onOpen = {}, onPrivate = {}, onShield = {}, onExtensions = {})
        val frame = FrameLayout(activity).apply { setBackgroundColor(ThemeManager.palette(activity).background); addView(page) }
        activity.setContentView(frame)
        page.visibility = View.VISIBLE
        page.render(false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "start-page")
    }

    private fun seedTabs(vararg tabs: BrowserTab) {
        val field = TabManager::class.java.getDeclaredField("tabs").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val list = field.get(TabManager) as MutableList<BrowserTab>
        list.clear(); list.addAll(tabs)
        TabManager::class.java.getDeclaredField("activeId").apply { isAccessible = true }.set(TabManager, tabs.first().id)
    }

    private fun sampleTabs() = arrayOf(
        BrowserTab(url = "https://www.youtube.com/watch?v=abc", title = "Video de prueba - YouTube", groupName = "Música"),
        BrowserTab(url = "https://es.wikipedia.org/wiki/Navegador_web", title = "Navegador web - Wikipedia", groupName = "Trabajo"),
        BrowserTab(url = "https://github.com/", title = "GitHub"),
        BrowserTab(url = "https://news.ycombinator.com/", title = "Hacker News", groupName = "Trabajo")
    )

    private fun themed(theme: String): Activity {
        BrowserPrefs.setTheme(org.robolectric.RuntimeEnvironment.getApplication(), theme)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ThemeManager.applyWindow(activity)
        return activity
    }

    private fun chrome(theme: String, name: String) {
        val activity = themed(theme)
        val p = ThemeManager.palette(activity)
        val root = activity.layoutInflater.inflate(R.layout.activity_main, null)
        root.setBackgroundColor(p.background)
        ThemeManager.styleFloatingDock(activity, root.findViewById(R.id.browserDock), root.findViewById(R.id.dockGesturePill))
        ThemeManager.styleOmniboxCapsule(activity, root.findViewById(R.id.omniboxCapsule), focused = false)
        ThemeManager.styleOmniboxInput(activity, root.findViewById(R.id.omnibox))
        root.findViewById<android.widget.EditText>(R.id.omnibox).setText("es.wikipedia.org")
        val toolsBtn = root.findViewById<android.widget.ImageButton>(R.id.tabToolsButton)
        toolsBtn.setImageResource(R.drawable.ic_lock)
        ThemeManager.styleIconButton(activity, toolsBtn)
        toolsBtn.imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
        ThemeManager.styleAiSparkButton(activity, root.findViewById(R.id.aiButton))
        ThemeManager.styleTabCounter(activity, root.findViewById(R.id.tabButton))
        ThemeManager.styleMenuIconButton(activity, root.findViewById(R.id.menuButton))
        listOf(R.id.backButton, R.id.forwardButton, R.id.homeButton, R.id.reloadButton)
            .forEach { ThemeManager.styleIconButton(activity, root.findViewById(it)) }
        ThemeManager.styleDockPrimaryIcon(activity, root.findViewById(R.id.newTabButton))
        // Liquid Split Chrome: cristal en píldora y clúster; el dock clásico queda oculto.
        root.findViewById<View>(R.id.browserDock).visibility = View.GONE
        root.findViewById<View>(R.id.chromeCluster).apply { background = NexoGlassDrawable(activity, p, NexoUi.Tokens.RADIUS_PILL); NexoGlass.float(this, p) }
        root.findViewById<android.widget.ImageButton>(R.id.pillReloadButton).apply {
            ThemeManager.styleIconButton(activity, this); imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
        }
        root.findViewById<View>(R.id.pageView).setBackgroundColor(p.surfaceContainerLowest)
        activity.setContentView(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, name)
    }

    @Test fun chromeLight() = chrome(BrowserPrefs.THEME_LIGHT, "chrome-light")
    @Test fun chromeDark() = chrome(BrowserPrefs.THEME_MIDNIGHT, "chrome-dark")

    private fun dialogShot(dialog: android.app.Dialog?, name: String) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val decor = (dialog ?: org.robolectric.shadows.ShadowDialog.getLatestDialog()).window!!.decorView
        measureAndSave(decor.context as? Activity ?: Robolectric.buildActivity(Activity::class.java).get(), decor, name, 2400)
    }

    @Test fun tabTools() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        TabTools.show(activity)
        dialogShot(null, "tab-tools-dark")
    }

    @Test fun tabSwitcher() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        TabSwitcher.show(activity) {}
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        save(dialog.window!!.decorView, "tab-switcher-dark")
    }

    @Test fun settingsDark() {
        BrowserPrefs.setTheme(org.robolectric.RuntimeEnvironment.getApplication(), BrowserPrefs.THEME_MIDNIGHT)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        save(activity.window.decorView, "settings-dark")
    }

    @Test fun captionsSheet() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        BrowserPrefs.setCaptionTranslateTo(activity, "es")
        BrowserPrefs.setTranslationProvider(activity, BrowserPrefs.TRANSLATE_AI)
        BrowserPrefs.setAiTranslateProvider(activity, "claude")
        CaptionsUi.showSheet(activity)
        dialogShot(null, "captions-sheet-dark")
    }

    @Test fun captionsOverlay() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        val p = ThemeManager.palette(activity)
        val root = activity.layoutInflater.inflate(R.layout.activity_main, null)
        root.setBackgroundColor(p.background)
        root.findViewById<View>(R.id.pageView).setBackgroundColor(0xFF1F2937.toInt())
        activity.setContentView(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val frame = root.findViewById<View>(R.id.browserFrame)
        val video = View(activity).apply { setBackgroundColor(0xFF374151.toInt()) }
        val rect = android.graphics.Rect(0, 160, frame.width, 160 + frame.width * 9 / 16)
        (frame as android.view.ViewGroup).addView(video, 1, FrameLayout.LayoutParams(rect.width(), rect.height()).apply { topMargin = rect.top })
        val cc = root.findViewById<android.widget.Button>(R.id.captionsButton)
        cc.visibility = View.VISIBLE
        CaptionsUi.styleChip(activity, cc, true)
        val mini = root.findViewById<android.widget.Button>(R.id.miniPlayerButton)
        mini.visibility = View.VISIBLE
        CaptionsUi.styleChip(activity, mini, false)
        CaptionsUi.render(activity, root.findViewById(R.id.captionView), frame.width, frame.height, rect,
            CaptionDisplay("Hoy vamos a ver cómo funciona el motor de un navegador.", "And the rendering",
                "Today we will see how a browser engine works."), false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "captions-overlay")
    }

    @Test fun captionsQuick() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        BrowserPrefs.setCaptionTranslateTo(activity, "ko")
        val session = com.nexo.browser.engine.FakeEngineSession()
        CaptionsUi.showQuick(activity, session, onChange = {}) {}
        dialogShot(null, "captions-quick-dark")
        CaptionsUi.pickLanguage(activity, "ko") {}
        dialogShot(null, "captions-languages-dark")
    }

    @Test fun siteControlDark() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        SiteControlSheet.show(activity)
        dialogShot(null, "site-control-dark")
    }

    @Test fun malwareWarning() {
        for ((theme, tag) in listOf(BrowserPrefs.THEME_MIDNIGHT to "dark", BrowserPrefs.THEME_LIGHT to "paper")) {
            val tab = BrowserTab(url = "https://paypal-login.secure-check.xyz/signin", title = "PayPal")
            val activity = themed(theme)
            activity.setContentView(FrameLayout(activity))
            val local = com.nexo.browser.shield.MalwareSignals.assess(tab.url,
                com.nexo.browser.shield.MalwareSignals.PageFacts(title = "PayPal", passwordFields = 1, crossOriginCredentialForms = 1))
            val result = com.nexo.browser.shield.MalwareScan.Result(
                "https://paypal-login.secure-check.xyz", com.nexo.browser.shield.MalwareSignals.decide(local, null), local.score, 0L)
            com.nexo.browser.shield.MalwareWarning.show(activity, tab, result)
            dialogShot(null, "malware-warning-$tag")
            com.nexo.browser.shield.MalwareWarning.dismiss()
        }
    }

    @Test fun bookmarksDark() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        BrowserPrefs.setTheme(app, BrowserPrefs.THEME_MIDNIGHT)
        com.nexo.browser.workspace.BookmarkStore.add(app, "Documentación Cefrium", "https://cefrium.com/guides/public-api/", "Desarrollo")
        com.nexo.browser.workspace.BookmarkStore.add(app, "Android Design", "https://developer.android.com/design", "Diseño")
        val activity = Robolectric.buildActivity(BookmarksActivity::class.java).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "bookmarks-dark")
    }

    @Test fun extensionsHubDark() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        BrowserPrefs.setTheme(app, BrowserPrefs.THEME_MIDNIGHT)
        val activity = Robolectric.buildActivity(ExtensionsActivity::class.java).setup().get()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "extensions-hub-dark")
    }

    @Test fun navCustomizerDark() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        NavCustomizerSheet.show(activity) {}
        dialogShot(null, "nav-customizer-dark")
    }

    @Test fun chromeOled() = chrome(BrowserPrefs.THEME_OLED, "chrome-oled")

    /** Barrido Liquid Craft: las pantallas principales en Paper y OLED (no solo Obsidian). */
    @Test fun liquidCraftThemeSweep() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        for ((theme, tag) in listOf(BrowserPrefs.THEME_LIGHT to "paper", BrowserPrefs.THEME_OLED to "oled")) {
            seedTabs(*sampleTabs())
            var activity = themed(theme)
            val page = StartPageView(activity, onOpen = {}, onPrivate = {}, onShield = {}, onExtensions = {})
            activity.setContentView(FrameLayout(activity).apply { addView(page) })
            page.render(false)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            save(activity.window.decorView, "sweep-start-$tag")

            activity = themed(theme); activity.setContentView(FrameLayout(activity))
            TabSwitcher.show(activity) {}
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            save(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "sweep-tabs-$tag")

            activity = themed(theme); activity.setContentView(FrameLayout(activity))
            SiteControlSheet.show(activity)
            dialogShot(null, "sweep-site-$tag")

            activity = themed(theme); activity.setContentView(FrameLayout(activity))
            com.nexo.browser.ai.NexoAiSheet.show(activity)
            dialogShot(null, "sweep-ai-$tag")

            BrowserPrefs.setTheme(app, theme)
            val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            save(settings.window.decorView, "sweep-settings-$tag")
            val ext = Robolectric.buildActivity(ExtensionsActivity::class.java).setup().get()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            save(ext.window.decorView, "sweep-extensions-$tag")
        }
    }

    /** Subtítulos sin video conocido: nunca bajo el cromo flotante; arriba si se elige. */
    @Test fun captionsPositions() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        val root = activity.layoutInflater.inflate(R.layout.activity_main, null)
        root.findViewById<View>(R.id.pageView).setBackgroundColor(0xFF1F2937.toInt())
        root.findViewById<View>(R.id.browserDock).visibility = View.GONE
        activity.setContentView(root)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val frame = root.findViewById<View>(R.id.browserFrame)
        val chrome = root.findViewById<View>(R.id.bottomChrome)
        val caption = root.findViewById<android.widget.TextView>(R.id.captionView)
        val text = CaptionDisplay("Los subtítulos nunca quedan detrás de la barra flotante.", "", "")
        for (position in listOf(BrowserPrefs.CAPTION_POS_BOTTOM, BrowserPrefs.CAPTION_POS_TOP)) {
            BrowserPrefs.setCaptionPosition(activity, position)
            CaptionsUi.render(activity, caption, frame.width, frame.height, null, text, false, bottomObstruction = chrome.height)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            org.junit.Assert.assertTrue("El subtítulo no debe solaparse con el cromo",
                caption.translationY + caption.measuredHeight <= frame.height - chrome.height)
            save(activity.window.decorView, "captions-position-$position")
        }
        BrowserPrefs.setCaptionPosition(activity, BrowserPrefs.CAPTION_POS_VIDEO)
    }

    @Test fun startPageDark() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        val page = StartPageView(activity, onOpen = {}, onPrivate = {}, onShield = {}, onExtensions = {})
        val frame = FrameLayout(activity).apply { setBackgroundColor(ThemeManager.palette(activity).background); addView(page) }
        activity.setContentView(frame)
        page.visibility = View.VISIBLE
        page.render(false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "start-page-dark")
    }

    @Test fun startPageOled() {
        val activity = themed(BrowserPrefs.THEME_OLED)
        val page = StartPageView(activity, onOpen = {}, onPrivate = {}, onShield = {}, onExtensions = {})
        val frame = FrameLayout(activity).apply { setBackgroundColor(ThemeManager.palette(activity).background); addView(page) }
        activity.setContentView(frame)
        page.visibility = View.VISIBLE
        page.render(false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "start-page-oled")
    }

    @Test fun nexoAiDark() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        com.nexo.browser.ai.NexoAiSheet.show(activity)
        dialogShot(null, "nexo-ai-dark")
    }

    @Test fun mainMenuDark() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val primaryActions = listOf(
            SheetAction(R.drawable.ic_nav_add, "Nueva") {},
            SheetAction(R.drawable.ic_menu_private, "Privada") {},
            SheetAction(R.drawable.ic_menu_burner, "Burner") {}
        )
        val actions = listOf(
            SheetAction(R.drawable.ic_menu_bookmark, "Favoritos") {},
            SheetAction(R.drawable.ic_menu_workspace, "Espacios") {},
            SheetAction(R.drawable.ic_menu_history, "Historial") {},
            SheetAction(R.drawable.ic_menu_download, "Descargas") {},
            SheetAction(R.drawable.ic_menu_translate, "Traducir") {},
            SheetAction(R.drawable.ic_menu_reader, "Modo lectura") {},
            SheetAction(R.drawable.ic_menu_desktop, "Escritorio", active = true, checked = true) {},
            SheetAction(R.drawable.ic_menu_copy, "Copiar enlace") {},
            SheetAction(R.drawable.ic_menu_share, "Compartir") {},
            SheetAction(R.drawable.ic_menu_find, "Buscar") {},
            SheetAction(R.drawable.ic_nexo_aperture, "Nexo AI") {},
            SheetAction(R.drawable.ic_menu_settings, "Configuración") {},
            SheetAction(R.drawable.ic_menu_update, "Buscar actualizaciones") {}
        )
        val menu = MenuSections.arrange(actions)
        val content = NexoSheets.column(
            activity,
            NexoSheets.header(activity, "youtube.com"),
            NexoSheets.primaryActions(activity, primaryActions, dismiss)
        )
        if (menu.quick.isNotEmpty()) content.addView(NexoSheets.grid(activity, menu.quick, dismiss = dismiss))
        content.addView(NexoSheets.actionGroups(activity, menu.groups, dismiss))
        sheet = NexoSheets.show(activity, content)
        dialogShot(sheet, "main-menu-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun compact360dpScreens() {
        chrome(BrowserPrefs.THEME_MIDNIGHT, "chrome-360dp")
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        val page = StartPageView(activity, onOpen = {}, onPrivate = {}, onShield = {}, onExtensions = {})
        val frame = FrameLayout(activity).apply { setBackgroundColor(ThemeManager.palette(activity).background); addView(page) }
        activity.setContentView(frame)
        page.visibility = View.VISIBLE
        page.render(false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "start-page-360dp")
    }

    @Test
    @Config(qualifiers = "w680dp-h900dp-xhdpi")
    fun tablet600dpScreens() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        val page = StartPageView(activity, onOpen = {}, onPrivate = {}, onShield = {}, onExtensions = {})
        val frame = FrameLayout(activity).apply { setBackgroundColor(ThemeManager.palette(activity).background); addView(page) }
        activity.setContentView(frame)
        page.visibility = View.VISIBLE
        page.render(false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(activity.window.decorView, "start-page-tablet")
        TabSwitcher.show(activity) {}
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "tab-switcher-tablet")
    }

    @Test
    @Config(qualifiers = "w800dp-h412dp-land-xhdpi")
    fun landscapeChrome() {
        chrome(BrowserPrefs.THEME_MIDNIGHT, "chrome-landscape")
    }

    @Test
    fun nexoAiOled() {
        seedTabs(*sampleTabs())
        val activity = themed(BrowserPrefs.THEME_OLED)
        activity.setContentView(FrameLayout(activity))
        com.nexo.browser.ai.NexoAiSheet.show(activity)
        dialogShot(null, "nexo-ai-oled")
    }

    @Test
    fun tabSwitcherListManyTabs() {
        val many = (1..12).map { i ->
            BrowserTab(
                id = "t$i",
                session = com.nexo.browser.engine.FakeEngineSession(),
                url = "https://site$i.example.org/article/$i",
                title = "Artículo técnico #$i sobre rendimiento Android",
                groupName = if (i <= 4) "Trabajo" else if (i <= 7) "Lectura" else null,
                pinned = i == 1
            )
        }.toTypedArray()
        seedTabs(*many)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        BrowserPrefs.setTabViewMode(app, TabProModel.ViewMode.LIST.id)
        try {
            val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
            activity.setContentView(FrameLayout(activity))
            TabSwitcher.show(activity) {}
            dialogShot(null, "tab-switcher-list-dark")
        } finally {
            BrowserPrefs.setTabViewMode(app, TabProModel.ViewMode.GRID.id)
        }
    }

    @Test
    fun nexoAi51FullVisualFlowAndPersistence() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        seedTabs(*sampleTabs())
        val storeFile = java.io.File(app.cacheDir, "shot_ai51_${System.nanoTime()}.json")
        com.nexo.browser.ai.NexoAiConfig.save(
            app,
            com.nexo.browser.ai.NexoAiConfig.Settings(
                enabled = true,
                consentAccepted = true,
                provider = "openai",
                model = "gpt-4.1-mini"
            )
        )
        com.nexo.browser.ai.NexoAiConfig.setApiKey(app, "sk-shot-demo-key")

        // 1. Seed conversación real persistida (flujo Consultar -> Minimizar -> Navegar -> Restaurar -> Leer respuesta)
        com.nexo.browser.ai.NexoAiController.initializeForTest(
            storageFile = storeFile,
            testTransport = com.nexo.browser.ai.NexoAiController.Transport { settings, _, _, _, _, _, _, _, _ ->
                Result.success(
                    com.nexo.browser.ai.NexoAiClient.AiResponse(
                        text = "• Nexo AI 5.1 mantiene las conversaciones en un controlador independiente del panel.\n" +
                            "• Al minimizar el asistente puedes seguir navegando sin cancelar la generación.\n" +
                            "• El laboratorio integrado compara entre 2 y 5 modelos con tiempos monotónicos reales.",
                        usage = com.nexo.browser.ai.NexoAiClient.TokenUsage(184, 62, 246),
                        actions = listOf(
                            com.nexo.browser.ai.NexoAiClient.ProposedAction("highlight", "Resaltar arquitectura", "controlador independiente"),
                            com.nexo.browser.ai.NexoAiClient.ProposedAction("open_link", "Abrir documentación", "https://example.com/docs")
                        ),
                        metrics = com.nexo.browser.ai.NexoAiModels.AiMetrics(
                            totalDurationMs = 1840L,
                            networkLatencyMs = 210L,
                            timeToFirstTokenMs = 420L,
                            generationDurationMs = 1420L,
                            tokensPerSecond = 43.7,
                            httpStatus = 200,
                            streamed = true,
                            model = settings.effectiveModel(),
                            provider = settings.provider
                        )
                    )
                )
            }
        )
        val s = com.nexo.browser.ai.NexoAiConfig.read(app)
        val binding = com.nexo.browser.ai.NexoAiModels.PageBinding("t1", "https://developer.android.com/guide", "developer.android.com", "Android Developers", 1L)
        com.nexo.browser.ai.NexoAiController.setPanelState(com.nexo.browser.ai.NexoAiModels.PanelState.MINIMIZED)
        com.nexo.browser.ai.NexoAiController.sendPrompt(
            settings = s,
            apiKey = "sk-shot-demo-key",
            userTask = "Resume los cambios clave de esta documentación",
            untrustedContext = "Contenido de ejemplo",
            pageBinding = binding
        )

        // 2. Capturar vista minimizada (botón ✨ en estado READY + píldora flotante in-app sobre el navegador)
        val chromeActivity = themed(BrowserPrefs.THEME_MIDNIGHT)
        chromeActivity.setContentView(R.layout.activity_main)
        val p = ThemeManager.palette(chromeActivity)
        chromeActivity.findViewById<View>(R.id.rootView).setBackgroundColor(p.background)
        val capsule = chromeActivity.findViewById<android.widget.LinearLayout>(R.id.omniboxCapsule)
        val omnibox = chromeActivity.findViewById<android.widget.EditText>(R.id.omnibox)
        val aiBtn = chromeActivity.findViewById<android.widget.ImageButton>(R.id.aiButton)
        val pill = chromeActivity.findViewById<com.nexo.browser.ai.NexoAiPillView>(R.id.nexoAiPill)
        val dock = chromeActivity.findViewById<View>(R.id.browserDock)
        val tabBtn = chromeActivity.findViewById<android.widget.Button>(R.id.tabButton)
        val menuBtn = chromeActivity.findViewById<android.widget.Button>(R.id.menuButton)
        val lockBtn = chromeActivity.findViewById<android.widget.ImageButton>(R.id.tabToolsButton)
        ThemeManager.styleOmniboxCapsule(chromeActivity, capsule, false)
        ThemeManager.styleOmniboxInput(chromeActivity, omnibox)
        omnibox.setText("developer.android.com/guide")
        ThemeManager.styleIconButton(chromeActivity, lockBtn)
        ThemeManager.styleTabCounter(chromeActivity, tabBtn)
        tabBtn.text = "3"
        ThemeManager.styleMenuIconButton(chromeActivity, menuBtn)
        ThemeManager.styleFloatingDock(chromeActivity, dock, chromeActivity.findViewById(R.id.dockGesturePill))
        listOf(R.id.backButton, R.id.forwardButton, R.id.homeButton, R.id.reloadButton).forEach {
            ThemeManager.styleIconButton(chromeActivity, chromeActivity.findViewById(it))
        }
        ThemeManager.styleDockPrimaryIcon(chromeActivity, chromeActivity.findViewById(R.id.newTabButton))
        val readySnap = com.nexo.browser.ai.NexoAiController.snapshot()
        ThemeManager.styleAiSparkButton(chromeActivity, aiBtn, readySnap.indicatorState)
        pill.render(chromeActivity, readySnap)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        save(chromeActivity.window.decorView, "ai51-minimized-ready-dark")

        // 3. Restaurar conversación en Panel parcial (66 %) y verificar persistencia tras navegar
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        com.nexo.browser.ai.NexoAiSheet.show(activity, initialTab = com.nexo.browser.ai.NexoAiSheet.Tab.CHAT, initialExpanded = false)
        dialogShot(null, "ai51-chat-partial-dark")

        // 4. Pantalla expandida (94 %) con respuesta restaurada y métricas reales
        com.nexo.browser.ai.NexoAiSheet.show(activity, initialTab = com.nexo.browser.ai.NexoAiSheet.Tab.CHAT, initialExpanded = true)
        dialogShot(null, "ai51-chat-expanded-dark")

        // 5. Tema Claro y OLED
        val lightAct = themed(BrowserPrefs.THEME_LIGHT)
        lightAct.setContentView(FrameLayout(lightAct))
        com.nexo.browser.ai.NexoAiSheet.show(lightAct, initialTab = com.nexo.browser.ai.NexoAiSheet.Tab.CHAT, initialExpanded = false)
        dialogShot(null, "ai51-chat-light")

        val oledAct = themed(BrowserPrefs.THEME_OLED)
        oledAct.setContentView(FrameLayout(oledAct))
        com.nexo.browser.ai.NexoAiSheet.show(oledAct, initialTab = com.nexo.browser.ai.NexoAiSheet.Tab.CHAT, initialExpanded = false)
        dialogShot(null, "ai51-chat-oled")

        // 6. Laboratorio de Benchmark multimodelo (3 modelos: 2 exitosos con TTFT/sin streaming + 1 HTTP 429)
        val benchPool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val benchDone = java.util.concurrent.CountDownLatch(3)
        com.nexo.browser.ai.NexoAiBenchmark.resetForTest(
            testExecutor = benchPool,
            testTransport = com.nexo.browser.ai.NexoAiController.Transport { st, _, _, _, _, _, _, _, _ ->
                val m = st.effectiveModel()
                try {
                    when (m) {
                        "gpt-4.1-mini" -> Result.success(
                            com.nexo.browser.ai.NexoAiClient.AiResponse(
                                text = "HTTP/3 utiliza QUIC sobre UDP para reducir la latencia de conexión y evitar bloqueo de cabecera.",
                                usage = com.nexo.browser.ai.NexoAiClient.TokenUsage(28, 34, 62),
                                metrics = com.nexo.browser.ai.NexoAiModels.AiMetrics(
                                    totalDurationMs = 1420L, networkLatencyMs = 180L, timeToFirstTokenMs = 390L,
                                    generationDurationMs = 1030L, tokensPerSecond = 33.0, streamed = true,
                                    model = m, provider = st.provider
                                )
                            )
                        )
                        "gpt-4.1" -> Result.success(
                            com.nexo.browser.ai.NexoAiClient.AiResponse(
                                text = "HTTP/3 reemplaza TCP por QUIC sobre UDP, integrando TLS 1.3 y multiplexación independiente de flujos.",
                                usage = com.nexo.browser.ai.NexoAiClient.TokenUsage(28, 41, 69),
                                metrics = com.nexo.browser.ai.NexoAiModels.AiMetrics(
                                    totalDurationMs = 3180L, networkLatencyMs = 240L, timeToFirstTokenMs = null,
                                    streamed = false, model = m, provider = st.provider
                                )
                            )
                        )
                        else -> Result.failure(com.nexo.browser.ai.NexoAiClient.HttpStatusException(429, "Límite de solicitudes alcanzado (HTTP 429)."))
                    }
                } finally {
                    benchDone.countDown()
                }
            }
        )
        com.nexo.browser.ai.NexoAiBenchmark.startComparison(
            baseSettings = s,
            apiKey = "sk-shot-demo-key",
            models = listOf("gpt-4.1-mini", "gpt-4.1", "o4-mini"),
            prompt = "Explica HTTP/3 en una frase clara y técnica."
        )
        benchDone.await(3, java.util.concurrent.TimeUnit.SECONDS)
        benchPool.shutdownNow()
        com.nexo.browser.ai.NexoAiSheet.show(activity, initialTab = com.nexo.browser.ai.NexoAiSheet.Tab.BENCHMARK, initialExpanded = true)
        dialogShot(null, "ai51-benchmark-dark")

        // 7. Configuración de proveedores integrada
        com.nexo.browser.ai.NexoAiSheet.show(activity, initialTab = com.nexo.browser.ai.NexoAiSheet.Tab.SETTINGS, initialExpanded = true)
        dialogShot(null, "ai51-settings-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun nexoAi51Compact360dpAndRecreationPersistence() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        seedTabs(*sampleTabs())
        val storeFile = java.io.File(app.cacheDir, "shot_ai51_compact_${System.nanoTime()}.json")
        com.nexo.browser.ai.NexoAiController.initializeForTest(
            storageFile = storeFile,
            testTransport = com.nexo.browser.ai.NexoAiController.Transport { _, _, _, _, _, _, _, _, _ ->
                Result.success(com.nexo.browser.ai.NexoAiClient.AiResponse("Respuesta persistida tras recreación y en 360 dp.", null))
            }
        )
        com.nexo.browser.ai.NexoAiController.sendPrompt(
            settings = com.nexo.browser.ai.NexoAiConfig.Settings(enabled = true, consentAccepted = true, provider = "openai", model = "gpt-4.1-mini"),
            apiKey = "sk-test",
            userTask = "Comprueba accesibilidad en pantalla compacta",
            untrustedContext = ""
        )

        // Simular recreación de Activity (p. ej. rotación de pantalla) y abrir de nuevo Nexo AI
        val firstActivity = themed(BrowserPrefs.THEME_MIDNIGHT)
        firstActivity.setContentView(FrameLayout(firstActivity))
        com.nexo.browser.ai.NexoAiSheet.show(firstActivity)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.dismiss()
        firstActivity.finish()

        val recreatedActivity = themed(BrowserPrefs.THEME_MIDNIGHT)
        recreatedActivity.resources.configuration.fontScale = 1.5f
        recreatedActivity.setContentView(FrameLayout(recreatedActivity))
        com.nexo.browser.ai.NexoAiSheet.show(recreatedActivity)
        dialogShot(null, "ai51-compact-360dp")

        val snap = com.nexo.browser.ai.NexoAiController.snapshot()
        org.junit.Assert.assertEquals(2, snap.activeConversation?.messages?.size)
        org.junit.Assert.assertEquals(
            "Respuesta persistida tras recreación y en 360 dp.",
            snap.activeConversation?.messages?.last()?.text
        )
    }

    @Test
    fun nexoAiHistoryDark() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        seedTabs(*sampleTabs())
        com.nexo.browser.ai.NexoAiController.initializeForTest(
            storageFile = java.io.File(app.cacheDir, "shot_ai_history_${System.nanoTime()}.json"),
            testTransport = com.nexo.browser.ai.NexoAiController.Transport { _, _, task, _, _, _, _, _, _ ->
                Result.success(com.nexo.browser.ai.NexoAiClient.AiResponse("Respuesta a: $task", null))
            }
        )
        val settings = com.nexo.browser.ai.NexoAiConfig.Settings(enabled = true, consentAccepted = true, provider = "openai", model = "gpt-4.1-mini")
        for (q in listOf("Resume las noticias de economía", "¿Qué precio tiene el plan anual?", "Traduce la tabla de especificaciones")) {
            com.nexo.browser.ai.NexoAiController.newConversation()
            com.nexo.browser.ai.NexoAiController.sendPrompt(settings, "sk-test", q, "")
        }
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        activity.setContentView(FrameLayout(activity))
        com.nexo.browser.ai.NexoAiHistorySheet.show(activity)
        dialogShot(null, "nexo-ai-history-dark")
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.dismiss()
        com.nexo.browser.ai.NexoAiSheet.show(activity)
        dialogShot(null, "nexo-ai-thread-actions-dark")
    }

    @Test
    @Config(qualifiers = "w900dp-h412dp-xhdpi")
    fun fullscreenOverlayDark() {
        val activity = themed(BrowserPrefs.THEME_MIDNIGHT)
        val frame = PullRefreshLayout(activity).apply { setBackgroundColor(0xFF000000.toInt()) }
        activity.setContentView(frame)
        val overlay = com.nexo.browser.media.NexoMediaEngine.FullscreenOverlay(activity, frame) {}
        overlay.setActive(true)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val spec = View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY)
        frame.measure(spec, View.MeasureSpec.makeMeasureSpec(824, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, 1800, 824)
        // Doble toque en el lateral derecho: salta y muestra el contador.
        val t = android.os.SystemClock.uptimeMillis()
        fun touch(action: Int, at: Long, x: Float) = android.view.MotionEvent.obtain(t, at, action, x, 400f, 0)
        frame.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_DOWN, t, 1500f))
        frame.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_UP, t + 60, 1500f))
        frame.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_DOWN, t + 160, 1500f))
        frame.dispatchTouchEvent(touch(android.view.MotionEvent.ACTION_UP, t + 220, 1500f))
        frame.measure(spec, View.MeasureSpec.makeMeasureSpec(824, View.MeasureSpec.EXACTLY))
        frame.layout(0, 0, 1800, 824)
        save(frame, "fullscreen-overlay-dark")
        overlay.detach()
        org.junit.Assert.assertNull("al salir no deja el filtro de toques", frame.touchFilter)
    }
}
