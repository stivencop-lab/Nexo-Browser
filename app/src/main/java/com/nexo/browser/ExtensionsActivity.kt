package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.nexo.browser.extensions.ExtensionCompatibility
import com.nexo.browser.extensions.nxr.NxrPackageInstaller
import com.nexo.browser.extensions.nxr.NxrPermissions
import com.nexo.browser.extensions.nxr.NxrRuntime
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Gestor de extensiones de Nexo: módulos integrados (PhantomShield, Puente de Nexo)
 * y runtime progresivo NXR para instalar, autorizar, diagnosticar y desinstalar
 * paquetes `.crx`, `.xpi` y `.zip`.
 */
class ExtensionsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var listView: ListView
    private var displayedIds: List<String?> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        NxrRuntime.initialize(this)

        findViewById<TextView>(R.id.listTitle).text = "Extensiones · NXR"
        findViewById<Button>(R.id.listAction).apply {
            text = "Instalar extensión"
            setOnClickListener { pickPackage() }
        }
        findViewById<TextView>(R.id.listEmpty).visibility = View.GONE
        listView = findViewById(R.id.listView)
        listView.setOnItemClickListener { _, _, position, _ ->
            val extId = displayedIds.getOrNull(position) ?: return@setOnItemClickListener
            if (extId == ExtensionManager.PHANTOMSHIELD_ID) {
                PhantomShieldPanel.show(this)
            } else {
                NxrRuntime.showExtensionSheet(this, extId) { refreshList() }
            }
        }

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

    private fun refreshList() {
        val rows = mutableListOf<ListRow>()
        val ids = mutableListOf<String?>()

        rows += ListRow(
            "PhantomShield",
            "Anuncios y rastreadores con motor nativo; IA opcional con tu API",
            if (com.nexo.browser.shield.PhantomShield.isEnabled()) "Activo · toca para abrir panel" else "Desactivado desde el menú",
            "✓"
        )
        ids += ExtensionManager.PHANTOMSHIELD_ID

        rows += ListRow(
            "Puente de Nexo",
            "Nexo Media Engine, Nexo Dark Engine y Subtítulos en vivo",
            "Activo · integrado en el motor Chromium",
            "✓"
        )
        ids += null

        val installed = NxrRuntime.installed(this)
        if (installed.isEmpty()) {
            rows += ListRow(
                "NXR Extensions Runtime",
                "Instala paquetes locales .crx, .xpi o .zip con content scripts, CSS y storage.local aislado",
                "Sin extensiones de terceros instaladas · toca «Instalar extensión»",
                "NXR"
            )
            ids += null
        } else {
            for (ext in installed) {
                val policy = NxrRuntime.policyFor(ext.id)
                val errCount = NxrRuntime.diagnostics(ext.id).size
                val status = buildString {
                    append(if (policy.enabled) "Activa" else "Desactivada")
                    append(" · v${ext.version} · MV${ext.manifestVersion}")
                    if (ext.unsupportedApis.isNotEmpty()) append(" · ${ext.unsupportedApis.size} API(s) sin soporte")
                    if (errCount > 0) append(" · $errCount aviso(s)")
                }
                rows += ListRow(
                    ext.name,
                    ext.description.ifBlank { "Sitios: ${ext.hostPermissions.take(3).joinToString(", ").ifBlank { "sin hosts" }}" },
                    status,
                    if (policy.enabled) "ON" else "OFF"
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
                main.post {
                    if (!isFinishing && !isDestroyed) {
                        showInstallConsentDialog(inspection, temp)
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

    private fun showInstallConsentDialog(inspection: NxrPackageInstaller.Inspection, tempFile: File) {
        val levelText = when (inspection.capabilities.level) {
            ExtensionCompatibility.Level.FULL -> "Compatible en NXR"
            ExtensionCompatibility.Level.PARTIAL -> "Compatible parcialmente en NXR"
            ExtensionCompatibility.Level.UNSUPPORTED -> "Sin scripts ejecutables compatibles en Cefrium"
        }
        val details = buildString {
            appendLine("${inspection.localizedName} v${inspection.manifest.version} (${inspection.sourceType}, MV${inspection.manifest.manifestVersion})")
            appendLine("Estado: $levelText")
            appendLine("Archivos: ${inspection.fileCount} (${(inspection.totalUncompressedBytes / 1024L).coerceAtLeast(1L)} KB)")
            appendLine("Content scripts ejecutables: ${inspection.capabilities.runnableContentScripts}")
            if (inspection.requestedHosts.isNotEmpty()) {
                appendLine()
                appendLine("Sitios que solicita leer/modificar:")
                inspection.requestedHosts.take(8).forEach { appendLine("• $it") }
            }
            if (inspection.capabilities.limitedApis.isNotEmpty()) {
                appendLine()
                appendLine("Con límites sobre Cefrium:")
                inspection.capabilities.limitedApis.forEach { (k, v) -> appendLine("• $k: $v") }
            }
            if (inspection.capabilities.unsupportedApis.isNotEmpty()) {
                appendLine()
                appendLine("No soportado en Cefrium:")
                appendLine(inspection.capabilities.unsupportedApis.joinToString(", "))
            }
            if (inspection.missingFiles.isNotEmpty()) {
                appendLine()
                appendLine("Aviso: faltan archivos referenciados: ${inspection.missingFiles.joinToString(", ")}")
            }
        }.trim()

        AlertDialog.Builder(this)
            .setTitle("¿Instalar extensión?")
            .setMessage(details)
            .setOnCancelListener { tempFile.delete() }
            .setNegativeButton("Cancelar") { _, _ -> tempFile.delete() }
            .setPositiveButton("Instalar y autorizar") { _, _ ->
                io.execute {
                    val result = runCatching {
                        NxrPackageInstaller.install(
                            rootDir = NxrRuntime.rootDirectory(this),
                            packageFile = tempFile,
                            grantedHosts = inspection.requestedHosts
                        )
                    }
                    tempFile.delete()
                    main.post {
                        if (isFinishing || isDestroyed) return@post
                        result.onSuccess { installed ->
                            Toastish.show(this, "${installed.name} instalada en NXR.")
                            refreshList()
                        }.onFailure { err ->
                            AlertDialog.Builder(this)
                                .setTitle("Error de instalación")
                                .setMessage(err.message ?: "No se pudo instalar la extensión.")
                                .setPositiveButton("Aceptar", null)
                                .show()
                        }
                    }
                }
            }
            .show()
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
        const val MAX_PACKAGE_BYTES = 20L * 1024L * 1024L
    }
}
