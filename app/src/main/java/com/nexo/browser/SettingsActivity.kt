package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.Normalizer

class SettingsActivity : Activity() {
    private lateinit var theme: Spinner
    private lateinit var accent: Spinner
    private lateinit var search: Spinner
    private lateinit var liveTabs: Spinner
    private lateinit var dns: Spinner
    private lateinit var dnsCustomBox: View
    private lateinit var dnsCustomInput: EditText
    private lateinit var dnsStrict: Switch
    private lateinit var cookies: Spinner
    private lateinit var restore: Switch
    private lateinit var tracking: Switch
    private lateinit var gpc: Switch
    private lateinit var httpsOnly: Switch
    private lateinit var freeSearch: Switch
    private lateinit var nexoHome: Switch
    private lateinit var smartPip: Switch
    private lateinit var backgroundMedia: Switch
    private lateinit var translatorTarget: Spinner
    private lateinit var textSize: Spinner
    private lateinit var autoplay: Spinner
    private lateinit var forceZoom: Switch
    private lateinit var pullToRefresh: Switch
    private lateinit var dockGestures: Switch
    private lateinit var navMode: Spinner
    private lateinit var autoHideBars: Switch
    private lateinit var customizeNavButton: Button
    private lateinit var parallelDownloads: Switch
    private lateinit var desktopDefault: Switch
    private lateinit var javascript: Switch
    private lateinit var strictTracking: Switch
    private lateinit var safeBrowsing: Switch
    private lateinit var fingerprinting: Switch
    private lateinit var queryStripping: Switch
    private lateinit var clearOnExit: Switch
    private lateinit var remoteDebug: Switch
    private lateinit var captionsEnabled: Switch
    private lateinit var autoUpdate: Switch
    private lateinit var settingsSearchInput: EditText
    private lateinit var settingsSearchClear: ImageButton

    private val navModes = NavConfig.Mode.entries.map { it.label to it }
    private val textSizes = listOf(80, 90, 100, 110, 120, 135, 150, 175, 200)
    private val autoplayOptions = listOf(
        "Permitir" to BrowserPrefs.AUTOPLAY_ALLOW,
        "Bloquear solo con sonido" to BrowserPrefs.AUTOPLAY_BLOCK_AUDIBLE,
        "Bloquear siempre" to BrowserPrefs.AUTOPLAY_BLOCK_ALL
    )

    private val themes = listOf(
        "Seguir sistema" to BrowserPrefs.THEME_SYSTEM,
        "Obsidian" to BrowserPrefs.THEME_MIDNIGHT,
        "OLED" to BrowserPrefs.THEME_OLED,
        "Paper" to BrowserPrefs.THEME_LIGHT
    )
    private val accents = BrowserPrefs.ACCENTS.map { ThemeManager.accentLabel(it) to it }
    private val engines = listOf(
        "Google" to BrowserPrefs.ENGINE_GOOGLE,
        "DuckDuckGo" to BrowserPrefs.ENGINE_DDG,
        "Bing" to BrowserPrefs.ENGINE_BING,
        "Brave Search" to BrowserPrefs.ENGINE_BRAVE,
        "Startpage" to BrowserPrefs.ENGINE_STARTPAGE
    )
    private val dnsOptions = listOf(
        "DNS del sistema" to BrowserPrefs.DNS_SYSTEM,
        "Cloudflare DNS over HTTPS" to BrowserPrefs.DNS_CLOUDFLARE,
        "Google DNS over HTTPS" to BrowserPrefs.DNS_GOOGLE,
        "Quad9 DNS over HTTPS" to BrowserPrefs.DNS_QUAD9,
        "DoH personalizado" to BrowserPrefs.DNS_CUSTOM
    )
    private val cookieOptions = listOf(
        "Equilibrado · aislar terceros" to BrowserPrefs.COOKIES_BALANCED,
        "Solo cookies del sitio" to BrowserPrefs.COOKIES_FIRST_PARTY,
        "Aceptar todas" to BrowserPrefs.COOKIES_ALL,
        "Bloquear todas" to BrowserPrefs.COOKIES_NONE
    )
    private val liveOptions = listOf(2, 3, 4, 5, 6, 8)
    private val translatorLanguages by lazy {
        val common = listOf(
            "Español" to "es",
            "Inglés" to "en",
            "Portugués" to "pt",
            "Francés" to "fr",
            "Alemán" to "de",
            "Italiano" to "it",
            "Japonés" to "ja",
            "Coreano" to "ko"
        )
        val selected = BrowserPrefs.translatorTarget(this)
        if (common.none { it.second == selected } && selected.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) {
            common + (java.util.Locale.forLanguageTag(selected).getDisplayName(java.util.Locale.getDefault()) to selected)
        } else common
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        theme = findViewById(R.id.themeSpinner)
        accent = findViewById(R.id.accentSpinner)
        search = findViewById(R.id.searchSpinner)
        liveTabs = findViewById(R.id.liveTabsSpinner)
        dns = findViewById(R.id.dnsSpinner)
        dnsCustomBox = findViewById(R.id.dnsCustomBox)
        dnsCustomInput = findViewById(R.id.dnsCustomInput)
        dnsStrict = findViewById(R.id.dnsStrictSwitch)
        cookies = findViewById(R.id.cookiesSpinner)
        restore = findViewById(R.id.restoreTabsSwitch)
        tracking = findViewById(R.id.trackingSwitch)
        gpc = findViewById(R.id.gpcSwitch)
        httpsOnly = findViewById(R.id.httpsOnlySwitch)
        freeSearch = findViewById(R.id.freeSearchSwitch)
        nexoHome = findViewById(R.id.nexoHomeSwitch)
        smartPip = findViewById(R.id.smartPipSwitch)
        backgroundMedia = findViewById(R.id.backgroundMediaSwitch)
        translatorTarget = findViewById(R.id.translatorTargetSpinner)
        textSize = findViewById(R.id.textSizeSpinner)
        autoplay = findViewById(R.id.autoplaySpinner)
        forceZoom = findViewById(R.id.forceZoomSwitch)
        pullToRefresh = findViewById(R.id.pullToRefreshSwitch)
        dockGestures = findViewById(R.id.dockGesturesSwitch)
        navMode = findViewById(R.id.navModeSpinner)
        autoHideBars = findViewById(R.id.autoHideBarsSwitch)
        customizeNavButton = findViewById(R.id.customizeNavButton)
        parallelDownloads = findViewById(R.id.parallelDownloadsSwitch)
        desktopDefault = findViewById(R.id.desktopDefaultSwitch)
        javascript = findViewById(R.id.javascriptSwitch)
        strictTracking = findViewById(R.id.strictTrackingSwitch)
        safeBrowsing = findViewById(R.id.safeBrowsingSwitch)
        fingerprinting = findViewById(R.id.fingerprintingSwitch)
        queryStripping = findViewById(R.id.queryStrippingSwitch)
        clearOnExit = findViewById(R.id.clearOnExitSwitch)
        remoteDebug = findViewById(R.id.remoteDebugSwitch)
        captionsEnabled = findViewById(R.id.captionsEnabledSwitch)
        autoUpdate = findViewById(R.id.autoUpdateSwitch)
        settingsSearchInput = findViewById(R.id.settingsSearchInput)
        settingsSearchClear = findViewById(R.id.settingsSearchClear)

        findViewById<Button>(R.id.transferProfileButton).setOnClickListener { MigrationMode.enter(this) }
        // Paso automático desde la app anterior (com.ejemplo.navegador); solo tiene sentido en la nueva.
        findViewById<Button>(R.id.legacyImportButton).apply {
            visibility = if (packageName == LegacyImport.TARGET_PACKAGE) View.VISIBLE else View.GONE
            setOnClickListener { LegacyImport.showManual(this@SettingsActivity) { recreate() } }
        }
        findViewById<Button>(R.id.perfLogButton).setOnClickListener {
            val report = PerfLog.report(this)
            AlertDialog.Builder(this)
                .setTitle("Mediciones de rendimiento")
                .setMessage(report + "\n\nMide igual antes y después: mismas páginas, red y batería; al menos 10 ejecuciones (cierra Nexo del todo entre arranques).")
                .setPositiveButton("Copiar") { _, _ ->
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Nexo rendimiento", report))
                    Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Borrar") { _, _ -> PerfLog.clear(this) }
                .setNegativeButton("Cerrar", null)
                .show()
        }
        findViewById<Button>(R.id.checkUpdatesButton).setOnClickListener {
            AppUpdater.checkManual(this) { updateAboutAndUpdateSummary() }
        }
        findViewById<Button>(R.id.resetSettingsButton).setOnClickListener { confirmResetDefaults() }
        customizeNavButton.setOnClickListener {
            NavCustomizerSheet.show(this) {
                val currentMode = NavConfig.mode(this)
                navMode.setSelection(navModes.indexOfFirst { it.second == currentMode }.coerceAtLeast(0))
                autoHideBars.isChecked = BrowserPrefs.autoHideBars(this)
                updateDependencies()
            }
        }
        navMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateDependencies()
                scheduleSave()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateDependencies()
            }
        }

        dns.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateDnsCustomVisibility()
                scheduleSave()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateDnsCustomVisibility()
            }
        }

        configureSearchFilter()
        populate()
        applyTheme()
        updateDependencies()
        tracking.setOnCheckedChangeListener { _, _ -> updateDependencies(); scheduleSave() }
        captionsEnabled.setOnCheckedChangeListener { _, _ -> updateDependencies(); scheduleSave() }
        autoUpdate.setOnCheckedChangeListener { _, enabled ->
            AppUpdater.setAutoCheck(this, enabled)
            updateAboutAndUpdateSummary()
            scheduleSave()
        }
        installLiveControls()
        installAutosave()
        findViewById<Button>(R.id.clearDataButton).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.defaultBrowserButton).setOnClickListener { requestDefaultBrowser() }
        findViewById<Button>(R.id.configureCaptionsButton).setOnClickListener {
            CaptionsUi.showSheet(this) { updateCaptionsSummary() }
        }
        intent?.getStringExtra(EXTRA_QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.let { initialQuery ->
            settingsSearchInput.setText(initialQuery)
            settingsSearchInput.setSelection(initialQuery.length)
        }
    }

    override fun onResume() {
        super.onResume()
        updateDefaultBrowserVisibility()
        updateCaptionsSummary()
        updateAboutAndUpdateSummary()
        AppUpdater.resumePendingInstallIfAllowed(this)
    }

    private var selectedCategory: String = "all"

    private val settingsCategories = listOf(
        "all" to "Todo",
        "appearance" to "Apariencia",
        "navigation" to "Navegación",
        "privacy" to "Privacidad",
        "media" to "Multimedia",
        "advanced" to "Avanzado"
    )

    private fun sectionBelongsToCategory(normalizedTitle: String, category: String): Boolean = when (category) {
        "all" -> true
        "appearance" -> normalizedTitle.contains("apariencia") || normalizedTitle.contains("busqueda")
        "navigation" -> normalizedTitle.contains("navegacion") || normalizedTitle.contains("pestanas") || normalizedTitle.contains("traduccion")
        "privacy" -> normalizedTitle.contains("privacidad") || normalizedTitle.contains("datos")
        "media" -> normalizedTitle.contains("multimedia") || normalizedTitle.contains("subtitulos")
        "advanced" -> normalizedTitle.contains("actualizaciones") || normalizedTitle.contains("avanzado")
        else -> true
    }

    private fun renderCategoryRail() {
        val holder = findViewById<ViewGroup>(R.id.settingsCategoryHolder) ?: return
        if (holder.childCount > 0) return
        // Pestañas de categoría con línea inferior deslizante (sin cajas segmentadas).
        val tabs = NexoUi.UnderlineTabs(this, settingsCategories, selectedCategory) { id ->
            if (selectedCategory != id) {
                selectedCategory = id
                filterSettings(settingsSearchInput.text?.toString()?.trim().orEmpty())
            }
        }
        holder.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(tabs.view)
        })
        holder.addView(NexoUi.divider(this, 0f).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, NexoUi.dp(this@SettingsActivity, 0.6f).coerceAtLeast(1), android.view.Gravity.BOTTOM)
        })
    }

    // -----------------------------------------------------------------
    // Nexo UI 7.0: selectores en vivo y guardado automático
    // -----------------------------------------------------------------
    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSnapshot = ""
    private val saveRunnable = Runnable { persist(fromUser = true) }

    /** Todo cambio se guarda solo (con breve espera) y se confirma con un toast de cristal. */
    private fun scheduleSave(delayMs: Long = 320L) {
        if (lastSnapshot.isEmpty()) return
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, delayMs)
    }

    private fun installAutosave() {
        listOf(
            restore, gpc, httpsOnly, freeSearch, nexoHome, smartPip, backgroundMedia,
            forceZoom, pullToRefresh, dockGestures, autoHideBars, parallelDownloads, desktopDefault, javascript,
            strictTracking, safeBrowsing, fingerprinting, queryStripping, clearOnExit, remoteDebug, dnsStrict
        ).forEach { it.setOnCheckedChangeListener { _, _ -> scheduleSave() } }
        val spinnerListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = scheduleSave()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        listOf(theme, accent, search, liveTabs, cookies, translatorTarget, textSize, autoplay)
            .forEach { it.onItemSelectedListener = spinnerListener }
        dnsCustomInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = scheduleSave(900L)
        })
        // Instantánea tras el primer pase de maquetación: los callbacks iniciales de los Spinner no guardan.
        findViewById<View>(R.id.settingsRoot).post { lastSnapshot = snapshot() }
    }

    override fun onPause() {
        if (saveHandler.hasCallbacks(saveRunnable)) {
            saveHandler.removeCallbacks(saveRunnable)
            persist(fromUser = false)
        }
        super.onPause()
    }

    /** Tema (Sistema / Obsidian / OLED / Paper) y acento como selectores en vivo. */
    private fun installLiveControls() {
        val p = ThemeManager.palette(this)
        fun replaceSpinner(spinner: Spinner, view: View) {
            val parent = spinner.parent as ViewGroup
            parent.addView(view, parent.indexOfChild(spinner), spinner.layoutParams)
            spinner.visibility = View.GONE
        }
        val themeOptions = listOf(
            BrowserPrefs.THEME_SYSTEM to "Sistema", BrowserPrefs.THEME_MIDNIGHT to "Obsidian",
            BrowserPrefs.THEME_OLED to "OLED", BrowserPrefs.THEME_LIGHT to "Paper"
        )
        val themeHolder = android.widget.FrameLayout(this).apply { id = R.id.themeSegmented }
        fun renderTheme() {
            themeHolder.removeAllViews()
            val current = themes.getOrNull(theme.selectedItemPosition)?.second ?: BrowserPrefs.theme(this)
            themeHolder.addView(NexoUi.segmented(this, themeOptions, current) { value ->
                theme.setSelection(themes.indexOfFirst { it.second == value }.coerceAtLeast(0))
                renderTheme()
                scheduleSave(60L)
            })
        }
        renderTheme()
        replaceSpinner(theme, themeHolder)

        // Acento: cinco círculos de 32dp en objetivos de 48dp, con aro en el seleccionado.
        val accentLabel = TextView(this).apply {
            textSize = 13f
            typeface = NexoUi.medium(this@SettingsActivity)
            setTextColor(p.text)
        }
        val swatches = LinearLayout(this).apply { id = R.id.accentSwatches; gravity = android.view.Gravity.CENTER_VERTICAL }
        fun renderAccents() {
            swatches.removeAllViews()
            val current = accents.getOrNull(accent.selectedItemPosition)?.second ?: BrowserPrefs.accent(this)
            accentLabel.text = ThemeManager.accentLabel(current)
            BrowserPrefs.ACCENTS.forEach { value ->
                val seed = ThemeManager.accentSeed(value)
                val color = if (seed == BrowserColors.MONOCHROME) p.text else seed
                val selected = value == current
                swatches.addView(View(this).apply {
                    val ring = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(color)
                        if (selected) setStroke(NexoUi.dp(this@SettingsActivity, 2.5f), p.background)
                    }
                    val outer = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.TRANSPARENT)
                        if (selected) setStroke(NexoUi.dp(this@SettingsActivity, 1.5f), p.text)
                    }
                    background = android.graphics.drawable.LayerDrawable(arrayOf(outer, ring)).apply {
                        val i = NexoUi.dp(this@SettingsActivity, 2f)
                        setLayerInset(1, i, i, i, i)
                    }
                    contentDescription = ThemeManager.accentLabel(value) + if (selected) " (seleccionado)" else ""
                    isSelected = selected
                    setOnClickListener {
                        accent.setSelection(accents.indexOfFirst { it.second == value }.coerceAtLeast(0))
                        renderAccents()
                        NexoMotion.pulse(this, 0.88f)
                        scheduleSave(60L)
                    }
                }, LinearLayout.LayoutParams(NexoUi.dp(this, 36f), NexoUi.dp(this, 36f)).apply {
                    // 36dp visibles + 12dp de separación = objetivos de 48dp sin solaparse.
                    marginEnd = NexoUi.dp(this@SettingsActivity, 12f)
                    topMargin = NexoUi.dp(this@SettingsActivity, 6f)
                    bottomMargin = NexoUi.dp(this@SettingsActivity, 6f)
                })
            }
        }
        renderAccents()
        val accentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(swatches)
        }
        replaceSpinner(accent, accentBox)
        // La etiqueta «Color de acento» gana el nombre del acento alineado a la derecha.
        (accentBox.parent as ViewGroup).let { card ->
            val idx = card.indexOfChild(accentBox)
            (card.getChildAt(idx - 1) as? TextView)?.let { title ->
                card.removeView(title)
                card.addView(LinearLayout(this).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    title.text = "Acento"
                    addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(accentLabel)
                }, idx - 1)
            }
        }

        // Barra inferior: «Isla Liquid» (Liquid Split Chrome) o «Dock Clásico» refinado.
        val chromeHolder = android.widget.FrameLayout(this).apply { id = R.id.chromeStyleSegmented }
        fun renderChrome() {
            chromeHolder.removeAllViews()
            val current = BrowserPrefs.chromeStyle(this)
            chromeHolder.addView(NexoUi.segmented(this, listOf(BrowserPrefs.CHROME_LIQUID to "Isla Liquid", BrowserPrefs.CHROME_DOCK to "Dock Clásico"), current) { value ->
                BrowserPrefs.setChromeStyle(this, value)
                renderChrome()
                updateDependencies()
                NexoUi.glassToast(this, "Guardado")
            })
        }
        renderChrome()
        val navCard = navMode.parent as ViewGroup
        val navIdx = navCard.indexOfChild(navMode)
        val navTitle = navCard.getChildAt(navIdx - 1) as? TextView
        navCard.addView(TextView(this).apply {
            text = "Barra inferior"
            textSize = 14f
            tag = "muted"
            setPadding(0, NexoUi.dp(this@SettingsActivity, 6f), 0, NexoUi.dp(this@SettingsActivity, 8f))
        }, (navIdx - 1).coerceAtLeast(0))
        navCard.addView(chromeHolder, navIdx, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = NexoUi.dp(this@SettingsActivity, 6f)
        })
        navTitle?.text = "Dock clásico"
        // Inicio minimalista, junto a la barra (sin subtítulo).
        navCard.addView(NexoUi.switchRow(this, "Inicio minimalista", null, BrowserPrefs.minimalStart(this)) {
            BrowserPrefs.setMinimalStart(this, it)
            NexoUi.glassToast(this, "Guardado")
        }.apply { setPadding(0, paddingTop, 0, paddingBottom) })
        autoHideBars.text = "Contraer al desplazar"
        dockGestures.text = "Deslizar la barra para cambiar de pestaña"
    }

    private fun updateDefaultBrowserVisibility() {
        val filtering = settingsSearchInput.text?.toString()?.trim().orEmpty().isNotEmpty() || selectedCategory != "all"
        findViewById<Button>(R.id.defaultBrowserButton).visibility =
            if (filtering || isDefaultBrowser()) View.GONE else View.VISIBLE
    }

    private fun configureSearchFilter() {
        settingsSearchClear.setOnClickListener {
            settingsSearchInput.setText("")
            settingsSearchInput.clearFocus()
            hideKeyboard()
        }
        settingsSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.trim().orEmpty()
                settingsSearchClear.visibility = if (raw.isEmpty()) View.GONE else View.VISIBLE
                filterSettings(raw)
            }
        })
        settingsSearchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                hideKeyboard()
                true
            } else false
        }
    }

    private fun filterSettings(rawQuery: String) {
        val terms = normalize(rawQuery).split(' ').filter { it.isNotBlank() }
        val column = findViewById<ViewGroup>(R.id.settingsColumn) ?: return
        updateDefaultBrowserVisibility()
        var currentSection: View? = null
        var currentSectionTitle = ""
        var sectionAllowed = true
        var sectionHasMatch = false
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            if (child.id == R.id.settingsSearchBox ||
                child.id == R.id.settingsCategoryHolder ||
                child.id == R.id.defaultBrowserButton ||
                child.id == R.id.aboutText
            ) continue
            if (child is TextView && child.tag == "section") {
                currentSection?.visibility = if (sectionAllowed && (terms.isEmpty() || sectionHasMatch)) View.VISIBLE else View.GONE
                currentSection = child
                currentSectionTitle = normalize(child.text?.toString().orEmpty())
                sectionAllowed = terms.isNotEmpty() || sectionBelongsToCategory(currentSectionTitle, selectedCategory)
                sectionHasMatch = false
                if (terms.isEmpty()) child.visibility = if (sectionAllowed) View.VISIBLE else View.GONE
                continue
            }
            if (child.tag == "card") {
                if (!sectionAllowed) {
                    child.visibility = View.GONE
                } else if (terms.isEmpty()) {
                    child.visibility = View.VISIBLE
                    sectionHasMatch = true
                } else {
                    val haystack = currentSectionTitle + " " + collectText(child)
                    val matches = terms.all { haystack.contains(it) }
                    child.visibility = if (matches) View.VISIBLE else View.GONE
                    if (matches) sectionHasMatch = true
                }
            }
        }
        currentSection?.visibility = if (sectionAllowed && (terms.isEmpty() || sectionHasMatch)) View.VISIBLE else View.GONE
    }

    private fun collectText(view: View): String {
        val sb = StringBuilder()
        fun walk(node: View) {
            when (node) {
                is Spinner -> {
                    val adapter = node.adapter
                    if (adapter != null) {
                        for (idx in 0 until adapter.count) {
                            sb.append(' ').append(adapter.getItem(idx)?.toString().orEmpty())
                        }
                    }
                }
                is TextView -> {
                    sb.append(' ').append(node.text?.toString().orEmpty())
                    if (!node.hint.isNullOrBlank()) sb.append(' ').append(node.hint)
                }
            }
            if (node is ViewGroup && node !is Spinner) {
                for (i in 0 until node.childCount) walk(node.getChildAt(i))
            }
        }
        walk(view)
        return normalize(sb.toString())
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(settingsSearchInput.windowToken, 0)
    }

    private fun isDefaultBrowser(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roles.isRoleHeld(RoleManager.ROLE_BROWSER)
            }
        }
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val handler = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        return handler?.activityInfo?.packageName == packageName
    }

    private fun requestDefaultBrowser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER) &&
                !roles.isRoleHeld(RoleManager.ROLE_BROWSER)
            ) {
                @Suppress("DEPRECATION")
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER), REQUEST_DEFAULT_BROWSER)
                return
            }
        }
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "Elige Nexo en Ajustes › Apps predeterminadas › Navegador", Toast.LENGTH_LONG).show()
        }
    }

    private fun populate() {
        theme.adapter = ThemeManager.spinnerAdapter(this, themes.map { it.first })
        accent.adapter = ThemeManager.spinnerAdapter(this, accents.map { it.first })
        search.adapter = ThemeManager.spinnerAdapter(this, engines.map { it.first })
        liveTabs.adapter = ThemeManager.spinnerAdapter(this, liveOptions.map(Int::toString))
        dns.adapter = ThemeManager.spinnerAdapter(this, dnsOptions.map { it.first })
        cookies.adapter = ThemeManager.spinnerAdapter(this, cookieOptions.map { it.first })
        translatorTarget.adapter = ThemeManager.spinnerAdapter(
            this,
            translatorLanguages.map { it.first }
        )
        textSize.adapter = ThemeManager.spinnerAdapter(this, textSizes.map { if (it == 100) "100 % (normal)" else "$it %" })
        autoplay.adapter = ThemeManager.spinnerAdapter(this, autoplayOptions.map { it.first })
        navMode.adapter = ThemeManager.spinnerAdapter(this, navModes.map { it.first })
        navMode.setSelection(navModes.indexOfFirst { it.second == NavConfig.mode(this) }.coerceAtLeast(0))
        autoHideBars.isChecked = BrowserPrefs.autoHideBars(this)
        textSize.setSelection(textSizes.indexOf(BrowserPrefs.textScale(this)).takeIf { it >= 0 } ?: textSizes.indexOf(100))
        autoplay.setSelection(autoplayOptions.indexOfFirst { it.second == BrowserPrefs.autoplay(this) }.coerceAtLeast(0))
        forceZoom.isChecked = BrowserPrefs.forceZoom(this)
        pullToRefresh.isChecked = BrowserPrefs.pullToRefresh(this)
        dockGestures.isChecked = BrowserPrefs.dockGestures(this)
        parallelDownloads.isChecked = BrowserPrefs.parallelDownloads(this)
        desktopDefault.isChecked = BrowserPrefs.desktopDefault(this)
        javascript.isChecked = BrowserPrefs.javascript(this)
        strictTracking.isChecked = BrowserPrefs.strictTracking(this)
        safeBrowsing.isChecked = BrowserPrefs.safeBrowsing(this)
        fingerprinting.isChecked = BrowserPrefs.fingerprinting(this)
        queryStripping.isChecked = BrowserPrefs.queryStripping(this)
        clearOnExit.isChecked = BrowserPrefs.clearOnExit(this)
        remoteDebug.isChecked = BrowserPrefs.remoteDebugging(this)
        dnsCustomInput.setText(BrowserPrefs.dnsCustom(this))
        dnsStrict.isChecked = BrowserPrefs.dnsStrict(this)
        autoUpdate.isChecked = AppUpdater.autoCheck(this)
        captionsEnabled.isChecked = BrowserPrefs.captionsEnabled(this)
        updateCaptionsSummary()
        updateAboutAndUpdateSummary()

        theme.setSelection(themes.indexOfFirst { it.second == BrowserPrefs.theme(this) }.coerceAtLeast(0))
        accent.setSelection(accents.indexOfFirst { it.second == BrowserPrefs.accent(this) }.coerceAtLeast(0))
        search.setSelection(engines.indexOfFirst { it.second == BrowserPrefs.searchEngine(this) }.coerceAtLeast(0))
        liveTabs.setSelection(liveOptions.indexOf(BrowserPrefs.maxLiveTabs(this)).coerceAtLeast(0))
        dns.setSelection(dnsOptions.indexOfFirst { it.second == BrowserPrefs.dnsProvider(this) }.coerceAtLeast(0))
        cookies.setSelection(cookieOptions.indexOfFirst { it.second == BrowserPrefs.cookieMode(this) }.coerceAtLeast(0))
        updateDnsCustomVisibility()

        restore.isChecked = BrowserPrefs.restoreTabs(this)
        tracking.isChecked = BrowserPrefs.trackingProtection(this)
        gpc.isChecked = BrowserPrefs.globalPrivacyControl(this)
        httpsOnly.isChecked = BrowserPrefs.httpsOnly(this)
        freeSearch.isChecked = BrowserPrefs.freeSearch(this)
        nexoHome.isChecked = BrowserPrefs.nexoHome(this)
        smartPip.isChecked = BrowserPrefs.smartPip(this)
        backgroundMedia.isChecked = BrowserPrefs.backgroundMedia(this)
        translatorTarget.setSelection(
            translatorLanguages.indexOfFirst {
                it.second == BrowserPrefs.translatorTarget(this)
            }.coerceAtLeast(0)
        )
    }

    private fun updateDnsCustomVisibility() {
        val selectedProvider = dnsOptions.getOrNull(dns.selectedItemPosition)?.second
        dnsCustomBox.visibility = if (selectedProvider == BrowserPrefs.DNS_CUSTOM) View.VISIBLE else View.GONE
    }

    private fun updateAboutAndUpdateSummary() {
        findViewById<TextView>(R.id.updateStatusText)?.text = AppUpdater.statusSummary(this)
        findViewById<TextView>(R.id.aboutText)?.apply {
            text = aboutText() + "\nToca para ver el diagnóstico del motor"
            setOnClickListener { showEngineDiagnostics() }
        }
    }

    private fun updateDependencies() {
        strictTracking.isEnabled = tracking.isChecked
        strictTracking.alpha = if (tracking.isChecked) 1f else 0.38f
        val liquid = BrowserPrefs.chromeStyle(this) == BrowserPrefs.CHROME_LIQUID
        val hasDock = liquid || navModes.getOrNull(navMode.selectedItemPosition)?.second != NavConfig.Mode.COMPACT
        autoHideBars.isEnabled = hasDock
        autoHideBars.alpha = if (hasDock) 1f else 0.38f
        // Los ajustes del Dock Clásico solo se muestran cuando está elegido.
        val dockOnly = if (liquid) View.GONE else View.VISIBLE
        navMode.visibility = dockOnly
        customizeNavButton.visibility = dockOnly
        (navMode.parent as? ViewGroup)?.let { card ->
            (card.getChildAt(card.indexOfChild(navMode) - 1) as? TextView)?.visibility = dockOnly
        }
        listOf<View>(findViewById(R.id.configureCaptionsButton), findViewById(R.id.captionsSummary)).forEach {
            it.isEnabled = captionsEnabled.isChecked
            it.alpha = if (captionsEnabled.isChecked) 1f else 0.38f
        }
    }

    /** Valores visibles serializados: solo se guarda (y se avisa) si algo cambió de verdad. */
    private fun snapshot(): String = listOf(
        theme.selectedItemPosition, accent.selectedItemPosition, search.selectedItemPosition, liveTabs.selectedItemPosition,
        dns.selectedItemPosition, cookies.selectedItemPosition, translatorTarget.selectedItemPosition,
        textSize.selectedItemPosition, autoplay.selectedItemPosition, navMode.selectedItemPosition,
        dnsCustomInput.text?.toString()?.trim().orEmpty()
    ).joinToString("|") + "|" + listOf(
        restore, tracking, gpc, httpsOnly, freeSearch, nexoHome, smartPip, backgroundMedia,
        forceZoom, pullToRefresh, dockGestures, autoHideBars, parallelDownloads, desktopDefault, javascript,
        strictTracking, safeBrowsing, fingerprinting, queryStripping, clearOnExit, remoteDebug,
        captionsEnabled, dnsStrict, autoUpdate
    ).joinToString("") { if (it.isChecked) "1" else "0" }

    private fun persist(fromUser: Boolean) {
        val now = snapshot()
        if (now == lastSnapshot) return
        val previousTheme = BrowserPrefs.theme(this) + BrowserPrefs.accent(this)
        var selectedDns = dnsOptions[dns.selectedItemPosition].second
        val customDoh = dnsCustomInput.text?.toString()?.trim().orEmpty()
        if (selectedDns == BrowserPrefs.DNS_CUSTOM) {
            if (!DnsProfile.valid(customDoh)) {
                // Se guarda todo lo demás; el DNS sigue en su valor anterior hasta que la URL sea válida.
                dnsCustomInput.error = "Introduce una URL DoH HTTPS válida (sin credenciales ni parámetros)."
                selectedDns = BrowserPrefs.dnsProvider(this)
            } else {
                BrowserPrefs.setDnsCustom(this, customDoh)
            }
        }
        BrowserPrefs.setDnsStrict(this, dnsStrict.isChecked)
        BrowserPrefs.setTheme(this, themes[theme.selectedItemPosition].second)
        BrowserPrefs.setAccent(this, accents[accent.selectedItemPosition].second)
        BrowserPrefs.setSearchEngine(this, engines[search.selectedItemPosition].second)
        BrowserPrefs.setDnsProvider(this, selectedDns)
        BrowserPrefs.setCookieMode(this, cookieOptions[cookies.selectedItemPosition].second)
        BrowserPrefs.setMaxLiveTabs(this, liveOptions[liveTabs.selectedItemPosition])
        BrowserPrefs.setRestoreTabs(this, restore.isChecked)
        BrowserPrefs.setTrackingProtection(this, tracking.isChecked)
        BrowserPrefs.setGlobalPrivacyControl(this, gpc.isChecked)
        BrowserPrefs.setHttpsOnly(this, httpsOnly.isChecked)
        BrowserPrefs.setFreeSearch(this, freeSearch.isChecked)
        BrowserPrefs.setNexoHome(this, nexoHome.isChecked)
        BrowserPrefs.setSmartPip(this, smartPip.isChecked)
        BrowserPrefs.setBackgroundMedia(this, backgroundMedia.isChecked)
        BrowserPrefs.setTextScale(this, textSizes[textSize.selectedItemPosition])
        BrowserPrefs.setAutoplay(this, autoplayOptions[autoplay.selectedItemPosition].second)
        NavConfig.setMode(this, navModes[navMode.selectedItemPosition].second)
        BrowserPrefs.setAutoHideBars(this, autoHideBars.isChecked)
        BrowserPrefs.setForceZoom(this, forceZoom.isChecked)
        BrowserPrefs.setPullToRefresh(this, pullToRefresh.isChecked)
        BrowserPrefs.setDockGestures(this, dockGestures.isChecked)
        BrowserPrefs.setParallelDownloads(this, parallelDownloads.isChecked)
        BrowserPrefs.setDesktopDefault(this, desktopDefault.isChecked)
        BrowserPrefs.setJavascript(this, javascript.isChecked)
        BrowserPrefs.setStrictTracking(this, strictTracking.isChecked)
        BrowserPrefs.setSafeBrowsing(this, safeBrowsing.isChecked)
        BrowserPrefs.setFingerprinting(this, fingerprinting.isChecked)
        BrowserPrefs.setQueryStripping(this, queryStripping.isChecked)
        BrowserPrefs.setClearOnExit(this, clearOnExit.isChecked)
        BrowserPrefs.setRemoteDebugging(this, remoteDebug.isChecked)
        BrowserPrefs.setCaptionsEnabled(this, captionsEnabled.isChecked)
        AppUpdater.setAutoCheck(this, autoUpdate.isChecked)
        BrowserPrefs.setTranslatorTarget(
            this,
            translatorLanguages[
                translatorTarget.selectedItemPosition
            ].second
        )

        com.nexo.browser.engine.Engines.current.applySettings(this)
        // El guardado automático también corre en onPause: si el proceso se restauró directamente
        // en Configuración, las pestañas aún no existen y MainActivity aplicará los ajustes al iniciar.
        if (TabManager.isInitialized) TabManager.reapplySettings()
        ExtensionManager.sendBrowserState(this)
        MediaPlaybackService.sync(this)
        lastSnapshot = now
        if (!fromUser || isFinishing) return
        if (previousTheme != BrowserPrefs.theme(this) + BrowserPrefs.accent(this)) {
            // Tema y acento en vivo: la pantalla se repinta al instante con los nuevos tokens.
            recreate()
            return
        }
        NexoUi.glassToast(this, "Guardado")
    }

    private fun confirmResetDefaults() {
        AlertDialog.Builder(this)
            .setTitle("Restablecer configuración")
            .setMessage("Se restaurarán los ajustes predeterminados de apariencia, privacidad y navegación. Tus pestañas abiertas, historial, descargas y claves guardadas se conservan.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Restablecer") { _, _ ->
                BrowserPrefs.resetDefaults(this)
                AppUpdater.setAutoCheck(this, true)
                com.nexo.browser.engine.Engines.current.applySettings(this)
                TabManager.reapplySettings()
                ExtensionManager.sendBrowserState(this)
                MediaPlaybackService.sync(this)
                Toast.makeText(this, "Ajustes predeterminados restaurados", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Borrar datos de navegación")
            .setMessage("Se borrarán cookies, caché, datos de sitios, miniaturas e historial. Las pestañas abiertas se conservan.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Borrar") { _, _ ->
                BrowsingData.clear(this) { ok, error ->
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) "Datos de navegación borrados" else "No se pudo borrar: ${error ?: "error desconocido"}",
                            if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.show()
    }

    private fun updateCaptionsSummary() {
        val language = CaptionsUi.LANGUAGES.firstOrNull { it.first == BrowserPrefs.captionLanguage(this) }?.second ?: "Automático"
        val target = CaptionsUi.TARGETS.firstOrNull { it.first == BrowserPrefs.captionTranslateTo(this) }?.second ?: "No traducir"
        findViewById<TextView>(R.id.captionsSummary).text =
            (if (BrowserPrefs.deepgramKey(this).isBlank()) "Sin clave de Deepgram: solo videos con subtítulos propios."
            else "Audio: $language · ${LiveCaptions.usageText(this)}") + "\nTraducción: $target"
    }

    /** Estado del motor y últimos eventos, para copiar y enviar sin adb. */
    private fun showEngineDiagnostics() {
        var currentReport = com.nexo.browser.engine.cefrium.EngineDiagnostics.report(this)
        val body = TextView(this).apply {
            text = currentReport
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            ThemeManager.styleText(this@SettingsActivity, this)
        }
        AlertDialog.Builder(this)
            .setTitle("Diagnóstico del motor")
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setPositiveButton("Copiar") { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Diagnóstico de Nexo", currentReport))
                Toast.makeText(this, "Diagnóstico saneado copiado", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Compartir") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico técnico de Nexo Browser")
                    putExtra(Intent.EXTRA_TEXT, currentReport)
                }
                runCatching { startActivity(Intent.createChooser(share, "Exportar diagnóstico saneado")) }
            }
            .setNegativeButton("Cerrar", null)
            .show()

        val activeSession = TabManager.activeSession() as? com.nexo.browser.engine.cefrium.CefriumEngineSession
        com.nexo.browser.engine.cefrium.EngineDiagnostics.probeActivePageMetrics(activeSession) { perfLine ->
            if (!perfLine.isNullOrBlank() && !isFinishing && !isDestroyed) {
                runOnUiThread {
                    currentReport = com.nexo.browser.engine.cefrium.EngineDiagnostics.report(this, perfLine)
                    body.text = currentReport
                }
            }
        }
    }

    private fun aboutText(): String {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        return "Nexo $version · motor ${com.nexo.browser.engine.Engines.current.displayName}"
    }

    private fun styleReadableTree(view: View) {
        when {
            view.tag == "card" -> ThemeManager.styleCard(this, view)
            view.tag == "divider" -> ThemeManager.styleDivider(this, view)
            view is Button || view is EditText -> Unit
            view is TextView && view.tag == "section" -> ThemeManager.styleSectionLabel(this, view)
            view is TextView && view.tag == "muted" -> ThemeManager.styleText(this, view, muted = true)
            view is TextView -> ThemeManager.styleText(this, view)
        }
        if (view is ViewGroup && view !is Spinner) {
            for (i in 0 until view.childCount) styleReadableTree(view.getChildAt(i))
        }
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        val settingsRoot = findViewById<View>(R.id.settingsRoot)
        settingsRoot.setBackgroundColor(p.background)
        styleReadableTree(settingsRoot)
        listOf(
            theme, accent, search, liveTabs,
            dns, cookies, translatorTarget, textSize, autoplay, navMode
        ).forEach {
            ThemeManager.styleSpinner(this, it)
        }
        listOf(
            restore, tracking, gpc, httpsOnly, freeSearch, nexoHome, smartPip, backgroundMedia,
            forceZoom, pullToRefresh, dockGestures, autoHideBars, parallelDownloads, desktopDefault, javascript,
            strictTracking, safeBrowsing, fingerprinting, queryStripping, clearOnExit, remoteDebug,
            captionsEnabled, dnsStrict, autoUpdate
        ).forEach {
            ThemeManager.styleSwitch(this, it)
        }
        settingsSearchInput.setTextColor(p.text)
        settingsSearchInput.setHintTextColor(p.muted)
        settingsSearchInput.typeface = NexoUi.regular(this)
        settingsSearchInput.hint = "Buscar ajustes…"
        settingsSearchInput.background = ThemeManager.rounded(this, p.surface, NexoUi.Tokens.RADIUS_PILL)
        NexoGlass.paperShadow(settingsSearchInput, p)
        settingsSearchInput.compoundDrawablesRelative.firstOrNull()?.mutate()?.setTint(p.muted)
        ThemeManager.styleIconButton(this, settingsSearchClear)
        renderCategoryRail()
        ThemeManager.styleEdit(this, dnsCustomInput)
        findViewById<TextView>(R.id.settingsTitle).apply {
            ThemeManager.styleText(this@SettingsActivity, this)
            typeface = NexoUi.semiBold(this@SettingsActivity)
            letterSpacing = -0.025f
        }
        ThemeManager.styleButton(this, findViewById(R.id.clearDataButton), danger = true)
        ThemeManager.styleButton(this, findViewById(R.id.defaultBrowserButton))
        ThemeManager.styleButton(this, customizeNavButton)
        ThemeManager.styleButton(this, findViewById(R.id.configureCaptionsButton))
        ThemeManager.styleButton(this, findViewById(R.id.checkUpdatesButton))
        ThemeManager.styleButton(this, findViewById(R.id.perfLogButton))
        ThemeManager.styleButton(this, findViewById(R.id.legacyImportButton))
        ThemeManager.styleButton(this, findViewById(R.id.transferProfileButton))
        ThemeManager.styleButton(this, findViewById(R.id.resetSettingsButton))
        findViewById<View>(R.id.aboutText).background = ThemeManager.ripple(this, null, 14f)
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
        findViewById<View>(R.id.settingsHeader).setBackgroundColor(p.background)
        ThemeManager.takeInitialFocus(settingsRoot)
    }

    companion object {
        const val EXTRA_QUERY = "nexo_settings_query"
        private const val REQUEST_DEFAULT_BROWSER = 51
    }
}
