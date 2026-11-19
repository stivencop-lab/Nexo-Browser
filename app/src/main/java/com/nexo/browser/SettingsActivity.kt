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

    private val textSizes = listOf(80, 90, 100, 110, 120, 135, 150, 175, 200)
    private val autoplayOptions = listOf(
        "Permitir" to BrowserPrefs.AUTOPLAY_ALLOW,
        "Bloquear solo con sonido" to BrowserPrefs.AUTOPLAY_BLOCK_AUDIBLE,
        "Bloquear siempre" to BrowserPrefs.AUTOPLAY_BLOCK_ALL
    )

    private val themes = listOf(
        "Seguir sistema" to BrowserPrefs.THEME_SYSTEM,
        "Midnight" to BrowserPrefs.THEME_MIDNIGHT,
        "OLED negro" to BrowserPrefs.THEME_OLED,
        "Claro" to BrowserPrefs.THEME_LIGHT
    )
    private val accents by lazy {
        listOf(
            "Cobalto" to BrowserPrefs.ACCENT_COBALT,
            "Violeta" to BrowserPrefs.ACCENT_VIOLET,
            "Cian" to BrowserPrefs.ACCENT_CYAN,
            "Lima" to BrowserPrefs.ACCENT_LIME,
            "Naranja" to BrowserPrefs.ACCENT_ORANGE,
            "Rosa" to BrowserPrefs.ACCENT_PINK,
            "Rojo" to BrowserPrefs.ACCENT_RED
        ) + if (Build.VERSION.SDK_INT >= 31 || BrowserPrefs.accent(this) == BrowserPrefs.ACCENT_DYNAMIC) {
            listOf(getString(R.string.dynamic_color) to BrowserPrefs.ACCENT_DYNAMIC)
        } else emptyList()
    }
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

        dns.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateDnsCustomVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateDnsCustomVisibility()
            }
        }

        configureSearchFilter()
        populate()
        applyTheme()
        updateDependencies()
        tracking.setOnCheckedChangeListener { _, _ -> updateDependencies() }
        captionsEnabled.setOnCheckedChangeListener { _, _ -> updateDependencies() }
        autoUpdate.setOnCheckedChangeListener { _, enabled ->
            AppUpdater.setAutoCheck(this, enabled)
            updateAboutAndUpdateSummary()
        }
        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener { save() }
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

    private fun updateDefaultBrowserVisibility() {
        val filtering = settingsSearchInput.text?.toString()?.trim().orEmpty().isNotEmpty()
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
        var sectionHasMatch = false
        for (i in 0 until column.childCount) {
            val child = column.getChildAt(i)
            if (child.id == R.id.settingsSearchBox || child.id == R.id.defaultBrowserButton || child.id == R.id.aboutText) continue
            if (child is TextView && child.tag == "section") {
                currentSection?.visibility = if (terms.isEmpty() || sectionHasMatch) View.VISIBLE else View.GONE
                currentSection = child
                currentSectionTitle = normalize(child.text?.toString().orEmpty())
                sectionHasMatch = false
                if (terms.isEmpty()) child.visibility = View.VISIBLE
                continue
            }
            if (child.tag == "card") {
                if (terms.isEmpty()) {
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
        currentSection?.visibility = if (terms.isEmpty() || sectionHasMatch) View.VISIBLE else View.GONE
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
        listOf<View>(findViewById(R.id.configureCaptionsButton), findViewById(R.id.captionsSummary)).forEach {
            it.isEnabled = captionsEnabled.isChecked
            it.alpha = if (captionsEnabled.isChecked) 1f else 0.38f
        }
    }

    private fun save() {
        val selectedDns = dnsOptions[dns.selectedItemPosition].second
        val customDoh = dnsCustomInput.text?.toString()?.trim().orEmpty()
        if (selectedDns == BrowserPrefs.DNS_CUSTOM) {
            if (!DnsProfile.valid(customDoh)) {
                dnsCustomInput.error = "Introduce una URL DoH HTTPS válida (sin credenciales ni parámetros)."
                dnsCustomInput.requestFocus()
                Toast.makeText(this, "Revisa la dirección DoH personalizada (debe usar HTTPS).", Toast.LENGTH_LONG).show()
                return
            }
            BrowserPrefs.setDnsCustom(this, customDoh)
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
        TabManager.reapplySettings()
        ExtensionManager.sendBrowserState(this)
        MediaPlaybackService.sync(this)
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        finish()
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
            dns, cookies, translatorTarget, textSize, autoplay
        ).forEach {
            ThemeManager.styleSpinner(this, it)
        }
        listOf(
            restore, tracking, gpc, httpsOnly, freeSearch, nexoHome, smartPip, backgroundMedia,
            forceZoom, pullToRefresh, dockGestures, parallelDownloads, desktopDefault, javascript,
            strictTracking, safeBrowsing, fingerprinting, queryStripping, clearOnExit, remoteDebug,
            captionsEnabled, dnsStrict, autoUpdate
        ).forEach {
            ThemeManager.styleSwitch(this, it)
        }
        ThemeManager.styleEdit(this, settingsSearchInput)
        ThemeManager.styleIconButton(this, settingsSearchClear)
        ThemeManager.styleEdit(this, dnsCustomInput)
        ThemeManager.styleText(this, findViewById(R.id.settingsTitle))
        ThemeManager.styleButton(this, findViewById(R.id.saveSettingsButton), primary = true)
        ThemeManager.styleButton(this, findViewById(R.id.clearDataButton), danger = true)
        ThemeManager.styleButton(this, findViewById(R.id.defaultBrowserButton))
        ThemeManager.styleButton(this, findViewById(R.id.configureCaptionsButton))
        ThemeManager.styleButton(this, findViewById(R.id.checkUpdatesButton))
        ThemeManager.styleButton(this, findViewById(R.id.perfLogButton))
        ThemeManager.styleButton(this, findViewById(R.id.legacyImportButton))
        ThemeManager.styleButton(this, findViewById(R.id.transferProfileButton))
        ThemeManager.styleButton(this, findViewById(R.id.resetSettingsButton))
        findViewById<View>(R.id.aboutText).background = ThemeManager.ripple(this, null, 14f)
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
        findViewById<View>(R.id.settingsFooter).background =
            ThemeManager.barBackground(this, lineOnTop = true)
        findViewById<View>(R.id.settingsHeader).setBackgroundColor(p.background)
        ThemeManager.takeInitialFocus(settingsRoot)
    }

    companion object {
        const val EXTRA_QUERY = "nexo_settings_query"
        private const val REQUEST_DEFAULT_BROWSER = 51
    }
}
