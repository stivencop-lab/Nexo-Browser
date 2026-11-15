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
import com.nexo.browser.extensions.CrxPackage
import com.nexo.browser.extensions.ExtensionCompatibility
import com.nexo.browser.extensions.ExtensionManifest
import com.nexo.browser.extensions.ExtensionMessages
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Extensiones en la variante Chromium: estado de los módulos integrados y
 * comprobación de una extensión de escritorio (CRX de Chrome Web Store, XPI
 * de Firefox o ZIP) con el runtime NXR, que dice qué funcionaría antes de
 * que exista el instalador.
 */
class ExtensionsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        findViewById<TextView>(R.id.listTitle).text = "Extensiones"
        findViewById<Button>(R.id.listAction).apply {
            text = "Comprobar extensión"
            setOnClickListener { pickPackage() }
        }
        findViewById<TextView>(R.id.listEmpty).visibility = View.GONE
        findViewById<ListView>(R.id.listView).adapter = ThemeManager.rowsAdapter(
            this,
            listOf(
                ListRow("PhantomShield", "Bloqueo de anuncios y rastreadores con motor nativo", "Próxima fase de Nexo Chromium", "3"),
                ListRow("Puente de Nexo", "Subtítulos en vivo, mini video y modo nocturno de páginas", "Próxima fase de Nexo Chromium", "4"),
                ListRow(
                    "Extensiones de escritorio",
                    "Chrome Web Store, Firefox (AMO) y archivos .crx/.xpi/.zip",
                    "Toca «Comprobar extensión» para ver si una funcionaría",
                    "NXR"
                )
            )
        )
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.listRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.listTitle))
        ThemeManager.styleButton(this, findViewById(R.id.listAction))
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun pickPackage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent.createChooser(intent, "Elegir extensión"), REQUEST_PACKAGE)
        }
    }

    @Deprecated("API de Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PACKAGE || resultCode != RESULT_OK || uri == null) return
        io.execute {
            val report = runCatching { analyze(uri) }.getOrElse { error -> "No se pudo leer la extensión: ${error.message ?: "archivo no válido"}" }
            main.post {
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this).setTitle("Compatibilidad").setMessage(report).setPositiveButton("Aceptar", null).show()
                }
            }
        }
    }

    /** Copia el paquete (con límite), verifica el CRX si lo es y lee su manifiesto. */
    private fun analyze(uri: Uri): String {
        val temp = File.createTempFile("nxr-check", ".pkg", cacheDir)
        try {
            contentResolver.openInputStream(uri)?.use { input -> copyLimited(input, temp) }
                ?: error("no se pudo abrir")
            var origin = "Paquete ZIP/XPI"
            val zip: InputStream = if (CrxPackage.isCrx(temp)) {
                val verified = CrxPackage.verify(temp)
                origin = "CRX${verified.formatVersion} firmado · ID ${verified.extensionId}"
                CrxPackage.openZip(temp, verified)
            } else temp.inputStream()
            val files = readSmallFiles(zip)
            val manifest = ExtensionManifest.parse(files["manifest.json"] ?: error("no contiene manifest.json"))
            val messages = ExtensionMessages.load("", manifest.defaultLocale, Locale.getDefault().toLanguageTag()) { folder ->
                files["_locales/$folder/messages.json"]
            }
            val report = ExtensionCompatibility.evaluate(manifest)
            val level = when (report.level) {
                ExtensionCompatibility.Level.FULL -> "Compatible"
                ExtensionCompatibility.Level.PARTIAL -> "Compatible en parte"
                ExtensionCompatibility.Level.UNSUPPORTED -> "No compatible"
            }
            return buildString {
                appendLine("${messages.localize(manifest.name)} ${manifest.version} (MV${manifest.manifestVersion})")
                appendLine(origin)
                appendLine()
                appendLine(level)
                if (report.limited.isNotEmpty()) {
                    appendLine()
                    appendLine("Con límites:")
                    report.limited.forEach { (permission, note) -> appendLine("• $permission: $note") }
                }
                if (report.unsupported.isNotEmpty()) {
                    appendLine()
                    appendLine("Sin equivalente en Android WebView: ${report.unsupported.joinToString()}")
                }
                appendLine()
                append("La instalación llegará con el runtime de extensiones de Nexo.")
            }
        } finally {
            temp.delete()
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
                if (total > MAX_PACKAGE_BYTES) error("el paquete supera ${MAX_PACKAGE_BYTES / (1024 * 1024)} MB")
                output.write(buffer, 0, read)
            }
        }
    }

    /** Solo el manifiesto y los textos de idioma, con límites de tamaño y de entradas. */
    private fun readSmallFiles(zip: InputStream): Map<String, String> {
        val files = mutableMapOf<String, String>()
        ZipInputStream(zip).use { input ->
            var entries = 0
            while (true) {
                val entry = input.nextEntry ?: break
                if (++entries > 4000) error("demasiados archivos")
                val name = ExtensionManifest.normalizePath(entry.name) ?: continue
                val wanted = name == "manifest.json" || (name.startsWith("_locales/") && name.endsWith("/messages.json"))
                if (entry.isDirectory || !wanted) continue
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                    if (bytes.size() > ExtensionManifest.MAX_BYTES) error("$name es demasiado grande")
                }
                files[name] = bytes.toString(Charsets.UTF_8.name())
            }
        }
        return files
    }

    private companion object {
        const val REQUEST_PACKAGE = 4101
        const val MAX_PACKAGE_BYTES = 120L * 1024L * 1024L
    }
}
