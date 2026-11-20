package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
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
    private var activeFilter = Filter.ALL
    private var displayedIds: List<String?> = emptyList()
    private val availableUpdates = ConcurrentHashMap<String, ExtensionUpdateManager.UpdateCheckResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        NxrRuntime.initialize(this)

        findViewById<TextView>(R.id.listTitle).apply {
            text = "Extension Hub"
            typeface = NexoUi.bold(this@ExtensionsActivity)
            letterSpacing = -0.015f
        }
        findViewById<Button>(R.id.listAction).apply {
            text = "Archivo local"
            setOnClickListener { pickPackage() }
        }
        findViewById<TextView>(R.id.listEmpty).visibility = View.GONE
        listView = findViewById(R.id.listView)
        listView.setOnItemClickListener { _, _, position, _ ->
            val extId = displayedIds.getOrNull(position) ?: return@setOnItemClickListener
            if (extId == ExtensionManager.PHANTOMSHIELD_ID) {
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
        val searchBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainer
        val searchStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queryInput = EditText(this).apply {
            hint = "Buscar, pegar URL de tienda o ID…"
            setSingleLine(true)
            textSize = 14f
            typeface = NexoUi.regular(this@ExtensionsActivity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            minHeight = dp(48f)
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = ThemeManager.rounded(this@ExtensionsActivity, searchBg, 24f, searchStroke)
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
            minWidth = dp(56f)
            minHeight = dp(48f)
            ThemeManager.styleButton(this@ExtensionsActivity, this, primary = true)
            setOnClickListener { handleHubInput() }
        }
        searchRow.addView(queryInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8f)
        })
        searchRow.addView(goBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(searchRow)

        // Tarjetas de tiendas oficiales lado a lado (Chrome Web Store + Edge Add-ons)
        val storesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, dp(8f))
        }
        storesRow.addView(storeSourceCard("Chrome Store", "Catálogo .crx oficial", p.primary.container, p.primary.onContainer) {
            openInBrowser(ExtensionStoreResolver.CWS_HOME_URL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        storesRow.addView(storeSourceCard("Edge Add-ons", "Catálogo Edge", p.info.container, p.info.onContainer) {
            openInBrowser(ExtensionStoreResolver.EDGE_HOME_URL)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(storesRow)

        // Métricas compactas de estado
        metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6f))
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

        root.addView(header, 1)
        renderFilterChips()
    }

    private fun storeSourceCard(
        title: String,
        subtitle: String,
        badgeBg: Int,
        badgeFg: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val p = ThemeManager.palette(this)
        val surface = if (p.isDark) p.surfaceContainer else p.surfaceContainerLowest
        val stroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = ThemeManager.ripple(this@ExtensionsActivity, ThemeManager.rounded(this@ExtensionsActivity, surface, 18f, stroke), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "Abrir $title"
            setOnClickListener { onClick() }
            addView(android.widget.ImageView(this@ExtensionsActivity).apply {
                setImageResource(R.drawable.ic_menu_store)
                imageTintList = android.content.res.ColorStateList.valueOf(badgeFg)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                background = ThemeManager.rounded(this@ExtensionsActivity, badgeBg, 12f)
            }, LinearLayout.LayoutParams(dp(36f), dp(36f)).apply { marginEnd = dp(10f) })
            addView(LinearLayout(this@ExtensionsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@ExtensionsActivity).apply {
                    text = title
                    textSize = 13.5f
                    typeface = NexoUi.semiBold(this@ExtensionsActivity)
                    setTextColor(p.text)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@ExtensionsActivity).apply {
                    text = subtitle
                    textSize = 11.5f
                    typeface = NexoUi.regular(this@ExtensionsActivity)
                    setTextColor(p.muted)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                AlertDialog.Builder(this)
                    .setTitle("Elegir tienda para ${resolved.extensionId.take(12)}…")
                    .setMessage("¿Desde qué tienda oficial deseas descargar y verificar el paquete CRX?")
                    .setPositiveButton("Chrome Web Store") { _, _ ->
                        ExtensionHubSheets.openStoreInstallFlow(
                            this,
                            ExtensionStoreResolver.ResolvedTarget.StoreExtension(
                                ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE,
                                resolved.extensionId,
                                ExtensionStoreResolver.cwsDetailUrl(resolved.extensionId)
                            )
                        ) { refreshList() }
                    }
                    .setNeutralButton("Edge Add-ons") { _, _ ->
                        ExtensionHubSheets.openStoreInstallFlow(
                            this,
                            ExtensionStoreResolver.ResolvedTarget.StoreExtension(
                                ExtensionStoreResolver.StoreKind.EDGE_ADDONS,
                                resolved.extensionId,
                                ExtensionStoreResolver.edgeDetailUrl(resolved.extensionId)
                            )
                        ) { refreshList() }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            is ExtensionStoreResolver.ResolvedTarget.StoreSearch -> {
                AlertDialog.Builder(this)
                    .setTitle("Buscar «${resolved.query}»")
                    .setMessage("Al abrir la ficha de cualquier extensión en Nexo aparecerá automáticamente la barra «Instalar en Nexo».")
                    .setPositiveButton("Chrome Web Store") { _, _ ->
                        openInBrowser(ExtensionStoreResolver.cwsSearchUrl(resolved.query))
                    }
                    .setNeutralButton("Edge Add-ons") { _, _ ->
                        openInBrowser(ExtensionStoreResolver.edgeSearchUrl(resolved.query))
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
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

    private fun refreshList() {
        val rows = mutableListOf<ListRow>()
        val ids = mutableListOf<String?>()

        val installed = NxrRuntime.installed(this)
        val runningCount = installed.count { NxrRuntime.lifecycleState(it.id, this) == ExtensionCompatibility.LifecycleState.RUNNING }
        val updatesCount = availableUpdates.size

        metricsRow.removeAllViews()
        metricsRow.addView(NexoUi.metricTile(this, "${installed.size}", "Instaladas", NexoUi.Tone.NEUTRAL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        metricsRow.addView(NexoUi.metricTile(this, "$runningCount", "Activas", if (runningCount > 0) NexoUi.Tone.SUCCESS else NexoUi.Tone.NEUTRAL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        metricsRow.addView(NexoUi.metricTile(this, "$updatesCount", "Actualizaciones", if (updatesCount > 0) NexoUi.Tone.INFO else NexoUi.Tone.NEUTRAL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (activeFilter == Filter.ALL) {
            val shieldOn = com.nexo.browser.shield.PhantomShield.isEnabled()
            rows += ListRow(
                title = "PhantomShield",
                subtitle = "Bloqueo nativo de anuncios y rastreadores integrado en Chromium",
                meta = if (shieldOn) "Integrada · toca para abrir el panel" else "Desactivado desde el menú",
                badge = "PS",
                status = if (shieldOn) "Activa" else "Pausada",
                statusTone = if (shieldOn) NexoUi.Tone.SUCCESS else NexoUi.Tone.NEUTRAL
            )
            ids += ExtensionManager.PHANTOMSHIELD_ID

            rows += ListRow(
                title = "Puente de Nexo",
                subtitle = "Media Engine, Dark Engine, Reader y Subtítulos en vivo",
                meta = "Integrado en Cefrium 0.21.0",
                badge = "NX",
                status = "Integrado",
                statusTone = NexoUi.Tone.SUCCESS
            )
            ids += null
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

        if (filtered.isEmpty()) {
            rows += ListRow(
                title = if (installed.isEmpty()) "Nexo Extension Hub listo" else "Sin extensiones en «${activeFilter.label}»",
                subtitle = if (installed.isEmpty()) {
                    "Abre Chrome Store o Edge Add-ons arriba, pega una URL/ID o elige un paquete .crx, .zip o .xpi."
                } else {
                    "Cambia el filtro superior o instala nuevas extensiones desde las tiendas compatibles."
                },
                meta = "NXR verifica firmas CRX y permisos por sitio",
                badge = "HUB",
                status = "Listo",
                statusTone = NexoUi.Tone.ACCENT
            )
            ids += null
        } else {
            for (ext in filtered) {
                val state = NxrRuntime.lifecycleState(ext.id, this)
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
                    if (ext.unsupportedApis.isNotEmpty()) append(" · ${ext.unsupportedApis.size} API(s) sin soporte")
                    if (errCount > 0) append(" · $errCount aviso(s)")
                }
                val (statusLabel, statusTone) = when {
                    upd != null && upd.hasUpdate -> "Actualización" to NexoUi.Tone.INFO
                    ext.unsupportedApis.isNotEmpty() -> "Con límites" to NexoUi.Tone.WARNING
                    state == ExtensionCompatibility.LifecycleState.RUNNING -> "Funcionando" to NexoUi.Tone.SUCCESS
                    state == ExtensionCompatibility.LifecycleState.ENABLED -> "Habilitada" to NexoUi.Tone.ACCENT
                    else -> state.label to NexoUi.Tone.NEUTRAL
                }
                rows += ListRow(
                    title = ext.name,
                    subtitle = ext.description.ifBlank { "Sitios: ${ext.hostPermissions.take(3).joinToString(", ").ifBlank { "sin hosts" }}" },
                    meta = meta,
                    badge = ext.name.take(2).uppercase().ifBlank { "EX" },
                    status = statusLabel,
                    statusTone = statusTone
                )
                ids += ext.id
            }
        }

        displayedIds = ids
        listView.adapter = ThemeManager.rowsAdapter(this, rows)
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
                        AlertDialog.Builder(this)
                            .setTitle("Paquete rechazado")
                            .setMessage("No se pudo validar la extensión: ${err.message ?: "archivo no válido"}")
                            .setPositiveButton("Aceptar", null)
                            .show()
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
        const val REQUEST_PACKAGE = 4101
        const val MAX_PACKAGE_BYTES = ExtensionStoreClient.MAX_DOWNLOAD_BYTES
    }
}
