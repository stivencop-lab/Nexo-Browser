package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider

class DownloadsActivity : Activity() {
    private lateinit var listView: ListView
    private var entries: List<DownloadEntry> = emptyList()
    // Descargas en curso, mostradas antes que las terminadas.
    private var running: List<ActiveDownload> = emptyList()
    private var runningKey = ""
    private val progressListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        listView = findViewById(R.id.listView)
        listView.emptyView = findViewById<TextView>(R.id.listEmpty).apply {
            setText(R.string.empty_downloads)
            ThemeManager.styleText(this@DownloadsActivity, this, muted = true)
        }
        findViewById<TextView>(R.id.listTitle).text = "Descargas"

        findViewById<Button>(R.id.listAction).apply {
            text = "Limpiar lista"
            setOnClickListener {
                DownloadStore.clearList(this@DownloadsActivity)
                refresh()
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            running.getOrNull(position)?.let { confirmCancel(it); return@setOnItemClickListener }
            entries.getOrNull(position - running.size)?.let { open(it) }
        }

        listView.setOnItemLongClickListener { _, _, raw, _ ->
            val position = raw - running.size
            if (position !in entries.indices) return@setOnItemLongClickListener true
            showDownloadOptions(entries[position])
            true
        }

        applyTheme()
    }

    override fun onResume() {
        super.onResume()
        DownloadStore.addListener(progressListener)
        refresh()
    }

    override fun onPause() {
        DownloadStore.removeListener(progressListener)
        super.onPause()
    }

    private fun confirmCancel(job: ActiveDownload) {
        AlertDialog.Builder(this)
            .setTitle("¿Cancelar la descarga?")
            .setMessage(job.fileName)
            .setNegativeButton("Seguir", null)
            .setPositiveButton("Cancelar descarga") { _, _ ->
                DownloadStore.cancel(job.id)
                Toast.makeText(this, "Cancelando…", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun refresh() {
        val updated = DownloadStore.list(this)
        val active = DownloadStore.activeDownloads()
        // Con descargas en curso la lista se redibuja con cada avance.
        val key = active.joinToString { it.id + ":" + it.received.get() + ":" + it.connections }
        if (listView.adapter != null && updated == entries && key == runningKey) return
        entries = updated
        running = active
        runningKey = key
        findViewById<Button>(R.id.listAction).isEnabled = entries.isNotEmpty()
        val position = listView.firstVisiblePosition
        val top = listView.getChildAt(0)?.top ?: 0
        listView.adapter = ThemeManager.rowsAdapter(
            this,
            running.map { job ->
                ListRow(
                    title = job.fileName,
                    subtitle = DownloadStore.progressText(this, job).ifBlank { "Iniciando…" },
                    meta = "Toca para cancelar",
                    badge = if (job.percent >= 0) "${job.percent}%" else "↓",
                    status = "Descargando",
                    statusTone = NexoUi.Tone.INFO
                )
            } + entries.map {
                val ext = it.fileName.substringAfterLast('.', "").uppercase().take(4)
                val source = runCatching { android.net.Uri.parse(it.sourceUrl).host }
                    .getOrNull()
                    ?.removePrefix("www.")
                    .orEmpty()
                ListRow(
                    title = it.fileName,
                    subtitle = listOf(Formatter.formatShortFileSize(this, it.size), source)
                        .filter { part -> part.isNotBlank() }
                        .joinToString(" · "),
                    meta = android.text.format.DateFormat.format("dd/MM/yyyy · HH:mm", it.timestamp).toString(),
                    badge = ext.ifBlank { "↓" }
                )
            }
        )
        // Sin saltos de desplazamiento al actualizar el progreso.
        listView.setSelectionFromTop(position, top)
    }

    private fun open(entry: DownloadEntry) {
        val file = DownloadStore.fileFor(this, entry)
        if (file == null) {
            Toast.makeText(this, "El archivo ya no existe", Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.files",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    uri,
                    entry.mimeType.ifBlank { "application/octet-stream" }
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)
        }.onFailure {
            Toast.makeText(
                this,
                "No se pudo abrir este archivo con una aplicación instalada",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showDownloadOptions(entry: DownloadEntry) {
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val actions = mutableListOf(
            SheetAction(R.drawable.ic_menu_open, "Abrir archivo") { open(entry) },
            SheetAction(R.drawable.ic_menu_share, "Compartir archivo") { share(entry) }
        )
        if (entry.sourceUrl.isNotBlank()) {
            actions += SheetAction(R.drawable.ic_menu_copy, "Copiar enlace de origen") {
                val clean = com.nexo.browser.shield.ShieldPolicy.stripTrackingParams(UrlSafety.stripCredentials(entry.sourceUrl))
                    ?: UrlSafety.stripCredentials(entry.sourceUrl)
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipboardPrivacy.plainText("Origen de descarga", clean, sensitive = false))
                Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show()
            }
        }
        actions += SheetAction(R.drawable.ic_menu_close, "Eliminar descarga", danger = true) {
            AlertDialog.Builder(this)
                .setTitle("Eliminar descarga")
                .setMessage(entry.fileName)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar") { _, _ ->
                    DownloadStore.remove(this, entry.id)
                    refresh()
                }
                .show()
        }
        val sizeStr = Formatter.formatShortFileSize(this, entry.size)
        sheet = NexoSheets.show(
            this,
            NexoSheets.column(
                this,
                NexoSheets.header(this, entry.fileName, sizeStr),
                NexoSheets.list(this, actions, dismiss)
            )
        )
    }

    private fun share(entry: DownloadEntry) {
        val file = DownloadStore.fileFor(this, entry)
        if (file == null) {
            Toast.makeText(this, "El archivo ya no existe", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = entry.mimeType.ifBlank { "application/octet-stream" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir ${entry.fileName}"))
        }.onFailure {
            Toast.makeText(this, "No se pudo compartir este archivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.listRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.listTitle))
        ThemeManager.styleButton(this, findViewById(R.id.listAction))
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
    }
}
