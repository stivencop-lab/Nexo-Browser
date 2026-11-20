package com.nexo.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.nexo.browser.extensions.ExtensionCompatibility
import com.nexo.browser.extensions.hub.ExtensionHubSheets
import com.nexo.browser.extensions.hub.ExtensionStoreClient
import com.nexo.browser.extensions.hub.ExtensionStoreResolver
import com.nexo.browser.extensions.hub.ExtensionUpdateManager
import com.nexo.browser.extensions.nxr.NxrPackageInstaller
import com.nexo.browser.extensions.nxr.NxrRuntime
import com.nexo.browser.extensions.nxr.NxrExtensionPanel
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Nexo Extension Hub: centro unificado para buscar, inspeccionar, instalar, administrar
 * y actualizar extensiones Chromium desde:
 * - Chrome Web Store (`chromewebstore.google.com`)
 * - Microsoft Edge Add-ons (`microsoftedge.microsoft.com/addons`)
 * - Paquetes locales `.crx`, `.zip` y `.xpi`
 * - Enlaces HTTPS directos de desarrolladores
 *
 * Muestra de forma honesta el ciclo de vida de 4 estados (Descargada / Instalada /
 * Habilitada / Funcionando) y la matriz real de 20 APIs sobre Cefrium 0.21.0.
 */
class ExtensionsActivity : Activity() {
    private enum class Filter(val label: String) {
        ALL("Todas"),
        INSTALLED("Instaladas"),
        RUNNING("Funcionando"),
        UPDATES("Actualizaciones"),
        INCOMPATIBLE("Con límites"),
        CWS("Chrome Store"),
        EDGE("Edge Add-ons"),
        LOCAL("Local")
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var listView: ListView
    private lateinit var queryInput: EditText
    private lateinit var filterBar: LinearLayout
    private lateinit var metricsRow: LinearLayout
    private var toolsScroll: View? = null
    private var filterScroll: View? = null
    private var activeFilter = Filter.ALL
    private var displayedIds: List<String?> = emptyList()
    private val availableUpdates = ConcurrentHashMap<String, ExtensionUpdateManager.UpdateCheckResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        NxrRuntime.initialize(this)

        findViewById<TextView>(R.id.listTitle).apply {
            text = "Extensiones"
            typeface = NexoUi.semiBold(this@ExtensionsActivity)
            letterSpacing = -0.025f
        }
        findViewById<Button>(R.id.listAction).apply {
            text = "Archivo .crx"
            setOnClickListener { pickPackage() }
        }
        findViewById<TextView>(R.id.listEmpty).visibility = View.GONE
        listView = findViewById(R.id.listView)
        listView.setOnItemClickListener { _, _, position, _ ->
            val extId = displayedIds.getOrNull(position) ?: return@setOnItemClickListener
            if (extId == MATRIX_ROW) {
                ExtensionHubSheets.showCompatibilityMatrixSheet(this)
            } else if (extId == ExtensionManager.PHANTOMSHIELD_ID) {
                PhantomShieldPanel.show(this)
            } else {
                val installed = NxrRuntime.installed(this).firstOrNull { it.id == extId }
                val updateAction = installed?.takeIf { ExtensionUpdateManager.isUpdatableFromStore(it) }?.let { ext ->
                    { ExtensionHubSheets.openSingleUpdateFlow(this, ext) { refreshList() } }
                }
                NxrRuntime.showExtensionSheet(this, extId, onCheckUpdate = updateAction) { refreshList() }
            }
        }

        buildHubHeader()

        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.listRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.listTitle))
        ThemeManager.styleButton(this, findViewById(R.id.listAction))
        findViewById<Button>(R.id.listAction).textSize = 13.5f
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildHubHeader() {
        val p = ThemeManager.palette(this)
        val root = findViewById<LinearLayout>(R.id.listRoot)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(6f))
        }

        // Buscador universal tipo píldora (tiendas / URL / ID de 32 letras)
        val searchBg = p.surface
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queryInput = EditText(this).apply {
            hint = "Pegar URL de tienda o ID…"
            setSingleLine(true)
            textSize = 14f
            typeface = NexoUi.regular(this@ExtensionsActivity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            minHeight = dp(48f)
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = ThemeManager.rounded(this@ExtensionsActivity, searchBg, NexoUi.Tokens.RADIUS_PILL)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablesRelative.firstOrNull()?.mutate()?.setTint(p.muted)
            compoundDrawablePadding = dp(10f)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            contentDescription = "Buscar extensión o pegar enlace de Chrome Web Store o Edge Add-ons"
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    handleHubInput()
                    true
                } else false
            }
        }
        val goBtn = Button(this).apply {
            text = "Ir"
            isAllCaps = false
            textSize = 14f
            minWidth = dp(48f)
            minHeight = dp(48f)
            ThemeManager.styleButton(this@ExtensionsActivity, this, primary = true)
            setPadding(dp(16f), 0, dp(16f), 0)
            setOnClickListener { handleHubInput() }
        }
        // Campo y botón «Ir» comparten una sola píldora (sin caja dentro de caja).
        searchRow.background = ThemeManager.rounded(this, searchBg, NexoUi.Tokens.RADIUS_PILL)
        NexoGlass.paperShadow(searchRow, p)
        searchRow.setPadding(0, 0, dp(4f), 0)
        queryInput.background = null
        searchRow.addView(queryInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(goBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f)))
        header.addView(searchRow)

        // Tarjetas de tiendas oficiales lado a lado (Chrome Web Store + Edge Add-ons)
        val storesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, dp(8f))
        }
        storesRow.addView(storeSourceCard("Chrome Web Store", "Catálogo oficial", p.text, p.text) {
            openInBrowser(ExtensionStoreResolver.CWS_HOME_URL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        storesRow.addView(storeSourceCard("Edge Add-ons", "Extensiones PC", p.text, p.text) {
            openInBrowser(ExtensionStoreResolver.EDGE_HOME_URL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(storesRow)

        // Franja compacta de estado (solo con extensiones instaladas; nunca cajas 0/0/0).
        metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), 0, 0, dp(4f))
        }
        header.addView(metricsRow)

        // Acciones secundarias (actualizaciones y matriz de 20 APIs)
        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolsRow.addView(NexoUi.chip(this, "Buscar actualizaciones", false) {
            checkAllUpdates()
        }, chipMargin())
        toolsRow.addView(NexoUi.chip(this, "Matriz 20 APIs", false) {
            ExtensionHubSheets.showCompatibilityMatrixSheet(this)
        }, chipMargin())
        toolsScroll.addView(toolsRow)
        header.addView(toolsScroll)
        this.toolsScroll = toolsScroll

        // Filtros de lista
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterScroll.addView(filterBar)
        header.addView(filterScroll)
        this.filterScroll = filterScroll

        root.addView(header, 1)
        renderFilterChips()
    }

    /** Tarjeta de descubrimiento: icono desnudo, nombre y una línea; r:22 sin borde. */
    private fun storeSourceCard(
        title: String,
        subtitle: String,
        @Suppress("UNUSED_PARAMETER") badgeBg: Int,
        badgeFg: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val p = ThemeManager.palette(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(104f)
            setPadding(dp(16f), dp(16f), dp(12f), dp(14f))
            background = ThemeManager.ripple(this@ExtensionsActivity, ThemeManager.rounded(this@ExtensionsActivity, p.surface, NexoUi.Tokens.RADIUS_CARD), NexoUi.Tokens.RADIUS_CARD)
            NexoGlass.paperShadow(this, p)
            isClickable = true
            isFocusable = true
            contentDescription = "Abrir $title"
            setOnClickListener { onClick() }
            addView(android.widget.ImageView(this@ExtensionsActivity).apply {
                setImageResource(R.drawable.ic_menu_store)
                imageTintList = android.content.res.ColorStateList.valueOf(badgeFg)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { bottomMargin = dp(14f) })
            addView(TextView(this@ExtensionsActivity).apply {
                text = title
                textSize = 15f
                typeface = NexoUi.semiBold(this@ExtensionsActivity)
                letterSpacing = -0.015f
                setTextColor(p.text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@ExtensionsActivity).apply {
                text = subtitle
                textSize = 12.5f
                typeface = NexoUi.regular(this@ExtensionsActivity)
                setTextColor(p.muted)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, 0)
            })
        }
    }

    private fun chipMargin() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dp(6f) }

    private fun renderFilterChips() {
        filterBar.removeAllViews()
        for (filter in Filter.entries) {
            val label = if (filter == Filter.UPDATES && availableUpdates.isNotEmpty()) {
                "${filter.label} (${availableUpdates.size})"
            } else filter.label
            filterBar.addView(NexoUi.chip(this, label, activeFilter == filter) {
                activeFilter = filter
                renderFilterChips()
                refreshList()
            }, chipMargin())
        }
    }

    private fun handleHubInput() {
        val raw = queryInput.text.toString().trim()
        val resolved = ExtensionStoreResolver.resolveInput(raw)
        if (resolved == null) {
            Toastish.show(this, "Escribe un término de búsqueda, una URL de tienda o un ID de 32 letras.")
            return
        }
        when (resolved) {
            is ExtensionStoreResolver.ResolvedTarget.StoreExtension -> {
                ExtensionHubSheets.openStoreInstallFlow(this, resolved) { refreshList() }
            }
            is ExtensionStoreResolver.ResolvedTarget.DirectPackage -> {
                ExtensionHubSheets.openDirectUrlInstallFlow(this, resolved.url) { refreshList() }
            }
            is ExtensionStoreResolver.ResolvedTarget.RawExtensionId -> {
                lateinit var picker: android.app.Dialog
                val body = NexoUi.column(this)
                body.addView(NexoUi.note(this, "ID detectado: ${resolved.extensionId}\nElige desde qué tienda oficial deseas descargar y verificar el paquete CRX:"))
                body.addView(NexoUi.button(this, "Chrome Web Store", primary = true) {
                    picker.dismiss()
                    ExtensionHubSheets.openStoreInstallFlow(
                        this,
                        ExtensionStoreResolver.ResolvedTarget.StoreExtension(
                            ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE,
                            resolved.extensionId,
                            ExtensionStoreResolver.cwsDetailUrl(resolved.extensionId)
                        )
                    ) { refreshList() }
                }, NexoUi.buttonParams(this, 10f))
                body.addView(NexoUi.button(this, "Microsoft Edge Add-ons", primary = false) {
                    picker.dismiss()
                    ExtensionHubSheets.openStoreInstallFlow(
                        this,
                        ExtensionStoreResolver.ResolvedTarget.StoreExtension(
                            ExtensionStoreResolver.StoreKind.EDGE_ADDONS,
                            resolved.extensionId,
                            ExtensionStoreResolver.edgeDetailUrl(resolved.extensionId)
                        )
                    ) { refreshList() }
                }, NexoUi.buttonParams(this, 6f))
                picker = NexoSheets.show(
                    this,
                    NexoSheets.column(this, NexoSheets.header(this, "Elegir tienda oficial", resolved.extensionId.take(16) + "…"), body)
                )
            }
            is ExtensionStoreResolver.ResolvedTarget.StoreSearch -> {
                lateinit var searchSheet: android.app.Dialog
                val body = NexoUi.column(this)
                body.addView(NexoUi.note(
                    this,
                    "La tienda se abrirá automáticamente en modo escritorio y aparecerá la barra «Instalar en Nexo» al entrar en cualquier extensión."
                ))
                body.addView(NexoUi.button(this, "Buscar en Chrome Web Store", primary = true) {
                    searchSheet.dismiss()
                    openInBrowser(ExtensionStoreResolver.cwsSearchUrl(resolved.query))
                }, NexoUi.buttonParams(this, 10f))
                body.addView(NexoUi.button(this, "Buscar en Edge Add-ons", primary = false) {
                    searchSheet.dismiss()
                    openInBrowser(ExtensionStoreResolver.edgeSearchUrl(resolved.query))
                }, NexoUi.buttonParams(this, 6f))
                searchSheet = NexoSheets.show(
                    this,
                    NexoSheets.column(this, NexoSheets.header(this, "Buscar «${resolved.query}»", "Catálogos oficiales de escritorio"), body)
                )
            }
        }
    }

    private fun openInBrowser(url: String) {
        startActivity(MainActivity.openUrlIntent(this, url))
        finish()
    }

    private fun checkAllUpdates() {
        val installed = NxrRuntime.installed(this).filter { ExtensionUpdateManager.isUpdatableFromStore(it) }
        if (installed.isEmpty()) {
            Toastish.show(this, "No hay extensiones instaladas desde Chrome Web Store o Edge Add-ons para comprobar.")
            return
        }
        Toastish.show(this, "Buscando actualizaciones para ${installed.size} extensión(es)…")
        io.execute {
            var found = 0
            for (ext in installed) {
                val res = ExtensionUpdateManager.checkForUpdate(ext)
                if (res.hasUpdate) {
                    availableUpdates[ext.id] = res
                    found++
                } else if (res.error == null) {
                    availableUpdates.remove(ext.id)
                }
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                renderFilterChips()
                refreshList()
                Toastish.show(
                    this,
                    if (found > 0) "$found actualización(es) disponible(s). Toca la extensión para revisar cambios."
                    else "Todas tus extensiones de tienda están actualizadas."
                )
            }
        }
    }

    private data class HubItem(
        val id: String?,
        val title: String,
        val subtitle: String,
        val meta: String,
        val badge: String,
        val status: String,
        val statusTone: NexoUi.Tone,
        val toggleable: Boolean = false,
        val enabled: Boolean = false,
        val hasPopup: Boolean = false,
        val builtIn: Boolean = false,
        val icon: Int = 0,
        val popupPage: String? = null,
        val optionsPage: String? = null
    )

    private val iconCache = HashMap<String, android.graphics.Bitmap?>()

    private fun cachedIcon(extId: String): android.graphics.Bitmap? {
        if (iconCache.containsKey(extId)) return iconCache[extId]
        val bmp = NxrRuntime.loadIcon(this, extId, dp(42f))
        iconCache[extId] = bmp
        return bmp
    }

    private fun refreshList() {
        val items = mutableListOf<HubItem>()
        val ids = mutableListOf<String?>()

        val installed = NxrRuntime.installed(this)
        val runningCount = installed.count { NxrRuntime.lifecycleState(it.id, this) == ExtensionCompatibility.LifecycleState.RUNNING }
        val updatesCount = availableUpdates.size

        // Con 0 CRX se ocultan métricas, filtros y herramientas vacías: prioridad al descubrimiento.
        val hasInstalled = installed.isNotEmpty()
        metricsRow.removeAllViews()
        metricsRow.visibility = if (hasInstalled) View.VISIBLE else View.GONE
        toolsScroll?.visibility = if (hasInstalled) View.VISIBLE else View.GONE
        filterScroll?.visibility = if (hasInstalled) View.VISIBLE else View.GONE
        if (!hasInstalled) activeFilter = Filter.ALL
        if (hasInstalled) {
            val pal = ThemeManager.palette(this)
            metricsRow.addView(TextView(this).apply {
                text = buildList {
                    add("${installed.size} instaladas")
                    add("$runningCount activas")
                    if (updatesCount > 0) add("$updatesCount actualizaciones")
                }.joinToString(" · ")
                textSize = 12.5f
                typeface = NexoUi.mono(this@ExtensionsActivity)
                fontFeatureSettings = "tnum"
                setTextColor(pal.muted)
                minHeight = dp(32f)
                gravity = Gravity.CENTER_VERTICAL
            })
        }

        if (activeFilter == Filter.ALL) {
            val shieldOn = com.nexo.browser.shield.PhantomShield.isEnabled()
            items += HubItem(
                id = ExtensionManager.PHANTOMSHIELD_ID,
                title = "PhantomShield",
                subtitle = "Bloqueo nativo en Chromium",
                meta = "",
                badge = "PS",
                status = if (shieldOn) "Activa" else "Pausada",
                statusTone = if (shieldOn) NexoUi.Tone.SUCCESS else NexoUi.Tone.NEUTRAL,
                builtIn = true,
                icon = R.drawable.ic_menu_shield
            )
            ids += ExtensionManager.PHANTOMSHIELD_ID

            items += HubItem(
                id = null,
                title = "Puente Nexo",
                subtitle = "Dark Engine, Reader y firmas CRX3",
                meta = "",
                badge = "NX",
                status = "Nativo",
                statusTone = NexoUi.Tone.NEUTRAL,
                builtIn = true,
                icon = R.drawable.ic_extension_placeholder
            )
            ids += null

            items += HubItem(
                id = MATRIX_ROW,
                title = "Compatibilidad",
                subtitle = "Matriz de 20 APIs sobre Cefrium",
                meta = "",
                badge = "API",
                status = "",
                statusTone = NexoUi.Tone.NEUTRAL,
                builtIn = true,
                icon = R.drawable.ic_ai_benchmark
            )
            ids += MATRIX_ROW
        }

        val filtered = installed.filter { ext ->
            val state = NxrRuntime.lifecycleState(ext.id, this)
            when (activeFilter) {
                Filter.ALL, Filter.INSTALLED -> true
                Filter.RUNNING -> state == ExtensionCompatibility.LifecycleState.RUNNING
                Filter.UPDATES -> availableUpdates.containsKey(ext.id)
                Filter.INCOMPATIBLE -> ext.unsupportedApis.isNotEmpty() || ext.compatibilityLevel == ExtensionCompatibility.Level.UNSUPPORTED.name
                Filter.CWS -> ext.sourceOrigin == NxrPackageInstaller.ORIGIN_CHROME_WEB_STORE
                Filter.EDGE -> ext.sourceOrigin == NxrPackageInstaller.ORIGIN_EDGE_ADDONS
                Filter.LOCAL -> ext.sourceOrigin == NxrPackageInstaller.ORIGIN_LOCAL_FILE || ext.sourceOrigin == NxrPackageInstaller.ORIGIN_DIRECT_URL
            }
        }

        if (filtered.isEmpty() && hasInstalled) {
            items += HubItem(
                id = null,
                title = "Sin extensiones en «${activeFilter.label}»",
                subtitle = "Cambia el filtro o instala desde las tiendas.",
                meta = "",
                badge = "HUB",
                status = "Listo",
                statusTone = NexoUi.Tone.ACCENT
            )
            ids += null
        } else if (filtered.isNotEmpty()) {
            val rootDir = NxrRuntime.rootDirectory(this)
            for (ext in filtered) {
                val state = NxrRuntime.lifecycleState(ext.id, this)
                val policy = NxrRuntime.policyFor(ext.id)
                val manifest = NxrPackageInstaller.readManifest(rootDir, ext.id)
                val errCount = NxrRuntime.diagnostics(ext.id).size
                val upd = availableUpdates[ext.id]
                val sourceShort = when (ext.sourceOrigin) {
                    NxrPackageInstaller.ORIGIN_CHROME_WEB_STORE -> "Chrome Store"
                    NxrPackageInstaller.ORIGIN_EDGE_ADDONS -> "Edge Add-ons"
                    NxrPackageInstaller.ORIGIN_DIRECT_URL -> "HTTPS"
                    else -> "Local"
                }
                val meta = buildString {
                    append("$sourceShort · v${ext.version} · MV${ext.manifestVersion}")
                    if (upd != null && upd.hasUpdate) append(" · ↑ v${upd.remoteVersion}")
                    if (manifest?.action?.popup != null) append(" · Popup UI")
                    if (ext.unsupportedApis.isNotEmpty()) append(" · ${ext.unsupportedApis.size} API(s) sin soporte")
                    if (errCount > 0) append(" · $errCount aviso(s)")
                }
                val (statusLabel, statusTone) = when {
                    upd != null && upd.hasUpdate -> "Actualización" to NexoUi.Tone.INFO
                    state == ExtensionCompatibility.LifecycleState.RUNNING -> "Funcionando" to NexoUi.Tone.SUCCESS
                    state == ExtensionCompatibility.LifecycleState.ENABLED -> "Habilitada" to NexoUi.Tone.ACCENT
                    ext.unsupportedApis.isNotEmpty() -> "Con límites" to NexoUi.Tone.WARNING
                    else -> state.label to NexoUi.Tone.NEUTRAL
                }
                items += HubItem(
                    id = ext.id,
                    title = ext.name,
                    subtitle = ext.description.ifBlank { "Sitios: ${ext.hostPermissions.take(3).joinToString(", ").ifBlank { "sin hosts" }}" },
                    meta = meta,
                    badge = ext.name.take(2).uppercase().ifBlank { "EX" },
                    status = statusLabel,
                    statusTone = statusTone,
                    toggleable = true,
                    enabled = policy.enabled,
                    hasPopup = !manifest?.action?.popup.isNullOrBlank(),
                    popupPage = manifest?.action?.popup,
                    optionsPage = manifest?.optionsPage
                )
                ids += ext.id
            }
        }

        displayedIds = ids
        listView.divider = null
        listView.dividerHeight = 0
        listView.adapter = buildHubAdapter(items)
    }

    private fun buildHubAdapter(items: List<HubItem>): android.widget.BaseAdapter {
        val p = ThemeManager.palette(this)
        return object : android.widget.BaseAdapter() {
            override fun getCount(): Int = items.size
            override fun getItem(position: Int): HubItem = items[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = items[position]
                val first = position == 0 || items[position - 1].builtIn != item.builtIn
                val last = position == items.lastIndex || items[position + 1].builtIn != item.builtIn
                val wrapper = LinearLayout(this@ExtensionsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16f), 0, dp(16f), if (item.builtIn && !last) 0 else dp(10f))
                }
                if (item.builtIn && first) wrapper.addView(NexoUi.section(this@ExtensionsActivity, "Integrados"))
                // Integrados: una lista agrupada con divisores inset; instaladas: tarjetas r:22.
                val r = NexoUi.Tokens.RADIUS_CARD * resources.displayMetrics.density
                val radii = if (item.builtIn) floatArrayOf(
                    if (first) r else 0f, if (first) r else 0f, if (first) r else 0f, if (first) r else 0f,
                    if (last) r else 0f, if (last) r else 0f, if (last) r else 0f, if (last) r else 0f
                ) else FloatArray(8) { r }
                val shape = android.graphics.drawable.GradientDrawable().apply { setColor(p.surface); cornerRadii = radii }
                val mask = android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.WHITE); cornerRadii = radii }
                val card = LinearLayout(this@ExtensionsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16f), dp(12f), dp(14f), dp(12f))
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(p.text, 28)), shape, mask)
                    minimumHeight = dp(64f)
                }
                val row = LinearLayout(this@ExtensionsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

                val iconBitmap = item.id?.takeIf { item.toggleable }?.let { cachedIcon(it) }
                when {
                    item.builtIn -> row.addView(android.widget.ImageView(this@ExtensionsActivity).apply {
                        setImageResource(item.icon)
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            if (item.statusTone == NexoUi.Tone.SUCCESS) p.success.color else p.muted)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(20f), dp(20f)).apply { marginEnd = dp(16f) })
                    iconBitmap != null -> row.addView(android.widget.ImageView(this@ExtensionsActivity).apply {
                        setImageBitmap(iconBitmap)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        background = ThemeManager.rounded(this@ExtensionsActivity, p.surfaceContainerHigh, 10f)
                        clipToOutline = true
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginEnd = dp(14f) })
                    item.toggleable -> row.addView(TextView(this@ExtensionsActivity).apply {
                        text = item.badge.take(1)
                        textSize = 14f
                        typeface = NexoUi.medium(this@ExtensionsActivity)
                        gravity = Gravity.CENTER
                        setTextColor(p.text)
                        background = ThemeManager.rounded(this@ExtensionsActivity, p.surfaceContainerHighest, NexoUi.Tokens.RADIUS_PILL)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginEnd = dp(14f) })
                }

                val textCol = LinearLayout(this@ExtensionsActivity).apply { orientation = LinearLayout.VERTICAL }
                textCol.addView(TextView(this@ExtensionsActivity).apply {
                    text = item.title
                    textSize = 15f
                    typeface = NexoUi.medium(this@ExtensionsActivity)
                    setTextColor(p.text)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (item.subtitle.isNotBlank()) textCol.addView(TextView(this@ExtensionsActivity).apply {
                    text = item.subtitle
                    textSize = 13f
                    typeface = NexoUi.regular(this@ExtensionsActivity)
                    setTextColor(p.muted)
                    maxLines = if (item.builtIn) 1 else 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(2f), 0, 0)
                })
                if (item.meta.isNotBlank()) textCol.addView(TextView(this@ExtensionsActivity).apply {
                    text = item.meta
                    textSize = 11.5f
                    typeface = NexoUi.mono(this@ExtensionsActivity)
                    fontFeatureSettings = "tnum"
                    setTextColor(p.muted)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4f), 0, 0)
                })
                row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                if (item.toggleable && item.id != null) {
                    row.addView(android.widget.Switch(this@ExtensionsActivity).apply {
                        isChecked = item.enabled
                        isFocusable = false
                        isFocusableInTouchMode = false
                        minHeight = dp(48f)
                        contentDescription = "Activar o desactivar ${item.title}"
                        ThemeManager.styleSwitch(this@ExtensionsActivity, this)
                        setOnCheckedChangeListener { _, checked ->
                            NxrRuntime.setEnabled(this@ExtensionsActivity, item.id, checked)
                            if (checked) NxrRuntime.injectActiveTabIfMatching(item.id)
                            refreshList()
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(8f)
                    })
                } else if (item.status.isNotBlank()) {
                    row.addView(TextView(this@ExtensionsActivity).apply {
                        text = item.status
                        textSize = 13f
                        typeface = NexoUi.medium(this@ExtensionsActivity)
                        setTextColor(if (item.statusTone == NexoUi.Tone.SUCCESS) p.success.color else p.muted)
                    })
                }
                card.addView(row)

                // Popup / Opciones como acciones fantasma (solo si la extensión las declara).
                if (item.id != null && item.toggleable && (item.popupPage != null || item.optionsPage != null)) {
                    val actions = LinearLayout(this@ExtensionsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(50f), dp(4f), 0, 0)
                    }
                    fun ghost(label: String, page: String) = TextView(this@ExtensionsActivity).apply {
                        text = label
                        textSize = 13f
                        typeface = NexoUi.medium(this@ExtensionsActivity)
                        setTextColor(p.text)
                        gravity = Gravity.CENTER
                        minHeight = dp(48f)
                        setPadding(dp(14f), 0, dp(14f), 0)
                        background = ThemeManager.ripple(this@ExtensionsActivity, android.graphics.drawable.InsetDrawable(
                            ThemeManager.rounded(this@ExtensionsActivity, p.surfaceContainerHigh, NexoUi.Tokens.RADIUS_PILL), 0, dp(8f), 0, dp(8f)), NexoUi.Tokens.RADIUS_PILL)
                        setOnClickListener {
                            if (!NxrExtensionPanel.show(this@ExtensionsActivity, item.id, item.title, page)) {
                                Toastish.show(this@ExtensionsActivity, "No se pudo abrir $label de ${item.title}.")
                            }
                        }
                    }
                    item.popupPage?.let { actions.addView(ghost("Popup", it)) }
                    item.optionsPage?.let { actions.addView(ghost("Opciones", it), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) }) }
                    card.addView(actions)
                }

                wrapper.addView(card)
                if (item.builtIn && !last) wrapper.addView(View(this@ExtensionsActivity).apply {
                    setBackgroundColor(p.surface)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.6f).coerceAtLeast(1)).apply { })
                if (item.builtIn && !last) (wrapper.getChildAt(wrapper.childCount - 1)).background =
                    android.graphics.drawable.LayerDrawable(arrayOf(
                        android.graphics.drawable.ColorDrawable(p.surface),
                        android.graphics.drawable.InsetDrawable(android.graphics.drawable.ColorDrawable(ThemeManager.hairline(p)), dp(52f), 0, 0, 0)
                    ))
                return wrapper
            }
        }
    }

    private fun pickPackage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, "Elegir paquete .crx / .xpi / .zip"), REQUEST_PACKAGE)
        }
    }

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PACKAGE || resultCode != RESULT_OK || uri == null) return
        io.execute {
            val temp = File.createTempFile("nxr-install", ".pkg", cacheDir)
            try {
                contentResolver.openInputStream(uri)?.use { input -> copyLimited(input, temp) }
                    ?: error("No se pudo abrir el archivo seleccionado.")
                val inspection = NxrPackageInstaller.inspect(temp)
                val sourceInfo = NxrPackageInstaller.SourceInfo(
                    sourceOrigin = NxrPackageInstaller.ORIGIN_LOCAL_FILE,
                    storeId = inspection.id.takeIf { ExtensionStoreResolver.isValidExtensionId(it) },
                    publicKeySha256 = inspection.publicKeySha256,
                    signatureVerified = inspection.signatureVerified
                )
                main.post {
                    if (!isFinishing && !isDestroyed) {
                        ExtensionHubSheets.showInstallReviewSheet(this, temp, inspection, sourceInfo) {
                            refreshList()
                        }
                    } else {
                        temp.delete()
                    }
                }
            } catch (err: Throwable) {
                temp.delete()
                main.post {
                    if (!isFinishing && !isDestroyed) {
                        lateinit var errSheet: android.app.Dialog
                        val body = NexoUi.column(this)
                        body.addView(NexoUi.statusPill(this, "Paquete rechazado", NexoUi.Tone.DANGER))
                        body.addView(NexoUi.note(this, "No se pudo validar la extensión: ${err.message ?: "archivo no válido"}"))
                        body.addView(NexoUi.button(this, "Entendido", primary = true) { errSheet.dismiss() }, NexoUi.buttonParams(this, 10f))
                        errSheet = NexoSheets.show(
                            this,
                            NexoSheets.column(this, NexoSheets.header(this, "No se pudo abrir el paquete", "Verificación NXR"), body)
                        )
                    }
                }
            }
        }
    }

    private fun copyLimited(input: InputStream, target: File) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_PACKAGE_BYTES) error("El paquete supera ${MAX_PACKAGE_BYTES / (1024 * 1024)} MB.")
                output.write(buffer, 0, read)
            }
        }
    }

    private companion object {
        private const val MATRIX_ROW = "__nexo_api_matrix__"
        const val REQUEST_PACKAGE = 4101
        const val MAX_PACKAGE_BYTES = ExtensionStoreClient.MAX_DOWNLOAD_BYTES
    }
}
