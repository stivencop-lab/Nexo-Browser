package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.nexo.browser.workspace.BookmarkStore

/**
 * Pantalla nativa de Favoritos de Nexo Workspace:
 * - Búsqueda instantánea por título, URL y carpeta.
 * - Filtro por carpetas.
 * - Creación, edición y eliminación de favoritos.
 * - Importación y exportación reales en formato estándar Netscape HTML y JSON mediante SAF.
 */
class BookmarksActivity : Activity() {
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var search: EditText
    private lateinit var clearSearch: ImageButton
    private lateinit var folderBar: LinearLayout

    private var visible: List<BookmarkStore.Bookmark> = emptyList()
    private var selectedFolder: String? = null
    private var query = ""
    private var pendingExportMime = "text/html"
    private var pendingExportHtml = true

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)

        listView = findViewById(R.id.listView)
        emptyView = findViewById<TextView>(R.id.listEmpty).apply {
            text = "Todavía no tienes favoritos guardados.\nUsa «Añadir» o guarda cualquier página desde el menú."
            ThemeManager.styleText(this@BookmarksActivity, this, muted = true)
        }
        listView.emptyView = emptyView
        findViewById<TextView>(R.id.listTitle).text = "Favoritos"

        findViewById<Button>(R.id.listAction).apply {
            text = "Añadir"
            setOnClickListener { showEditDialog(null) }
        }

        buildTopControls()

        listView.setOnItemClickListener { _, _, position, _ ->
            visible.getOrNull(position)?.let { bm ->
                hideKeyboard()
                startActivity(MainActivity.openUrlIntent(this, bm.url))
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            visible.getOrNull(position)?.let { showBookmarkOptions(it) }
            true
        }

        applyTheme()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildTopControls() {
        val p = ThemeManager.palette(this)
        val root = findViewById<LinearLayout>(R.id.listRoot)

        // Barra de búsqueda
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(NexoUi.dp(this@BookmarksActivity, 16f), NexoUi.dp(this@BookmarksActivity, 2f), NexoUi.dp(this@BookmarksActivity, 16f), NexoUi.dp(this@BookmarksActivity, 8f))
        }
        search = EditText(this).apply {
            hint = "Buscar en favoritos"
            setSingleLine(true)
            textSize = 14.5f
            minHeight = NexoUi.dp(this@BookmarksActivity, 48f)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT
            ThemeManager.styleEdit(this@BookmarksActivity, this)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = NexoUi.dp(this@BookmarksActivity, 10f)
            setPadding(NexoUi.dp(this@BookmarksActivity, 16f), NexoUi.dp(this@BookmarksActivity, 8f), NexoUi.dp(this@BookmarksActivity, 48f), NexoUi.dp(this@BookmarksActivity, 8f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    clearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                    render()
                }
            })
        }
        clearSearch = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Limpiar búsqueda"
            visibility = View.GONE
            ThemeManager.styleIconButton(this@BookmarksActivity, this)
            imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
            setOnClickListener { search.setText(""); search.requestFocus() }
        }
        val frame = FrameLayout(this).apply {
            addView(search, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(clearSearch, FrameLayout.LayoutParams(NexoUi.dp(this@BookmarksActivity, 48f), NexoUi.dp(this@BookmarksActivity, 48f), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        searchRow.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(searchRow, 1)

        // Acciones de importación / exportación estándar
        val ioRow = NexoUi.chipRow(this, listOf(
            NexoUi.chip(this, "Importar HTML / JSON", false) { launchImport() },
            NexoUi.chip(this, "Exportar HTML", false) { launchExport(asHtml = true) },
            NexoUi.chip(this, "Exportar JSON", false) { launchExport(asHtml = false) }
        ), sidePaddingDp = 16f)
        root.addView(ioRow, 2)

        // Barra dinámica de carpetas
        folderBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, NexoUi.dp(this@BookmarksActivity, 6f), 0, NexoUi.dp(this@BookmarksActivity, 4f))
        }
        root.addView(folderBar, 3)
    }

    private fun render() {
        val file = BookmarkStore.file(this)
        val folders = BookmarkStore.folders(file)
        if (selectedFolder != null && selectedFolder !in folders) selectedFolder = null

        folderBar.removeAllViews()
        val chips = mutableListOf<View>()
        chips += NexoUi.chip(this, "Todas", selectedFolder == null) {
            selectedFolder = null
            render()
        }
        folders.forEach { folder ->
            chips += NexoUi.chip(this, folder, selectedFolder == folder) {
                selectedFolder = folder
                render()
            }
        }
        folderBar.addView(NexoUi.chipRow(this, chips, sidePaddingDp = 16f))

        visible = BookmarkStore.search(file, query, selectedFolder)
        emptyView.text = if (query.isNotEmpty()) {
            "Sin favoritos que coincidan con «$query»."
        } else if (selectedFolder != null) {
            "La carpeta «$selectedFolder» está vacía."
        } else {
            "Todavía no tienes favoritos guardados.\nPulsa «Añadir» o guarda sitios desde el menú de Nexo."
        }

        listView.adapter = ThemeManager.rowsAdapter(
            this,
            visible.map { bm ->
                val host = runCatching { Uri.parse(bm.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
                ListRow(
                    title = bm.title,
                    subtitle = host.ifBlank { bm.url },
                    meta = bm.folder,
                    badge = bm.title.take(1).uppercase().ifBlank { "★" }
                )
            }
        )
        findViewById<TextView>(R.id.listTitle).text =
            if (visible.isEmpty()) "Favoritos" else "Favoritos · ${visible.size}"
    }

    private fun showBookmarkOptions(bm: BookmarkStore.Bookmark) {
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val actions = listOf(
            SheetAction(R.drawable.ic_menu_open, "Abrir en pestaña nueva") {
                TabManager.createTab(bm.url)
                finish()
            },
            SheetAction(R.drawable.ic_menu_private, "Abrir en pestaña privada") {
                TabManager.createTab(bm.url, isPrivate = true)
                finish()
            },
            SheetAction(R.drawable.ic_menu_bookmark, "Editar favorito") { showEditDialog(bm) },
            SheetAction(R.drawable.ic_menu_close, "Eliminar favorito", danger = true) {
                BookmarkStore.delete(this, bm.id)
                render()
                Toast.makeText(this, "Favorito eliminado", Toast.LENGTH_SHORT).show()
            }
        )
        sheet = NexoSheets.show(this, NexoSheets.column(
            this,
            NexoSheets.header(this, bm.title, "${bm.folder} · ${bm.url}"),
            NexoSheets.list(this, actions, dismiss)
        ))
    }

    private fun showEditDialog(existing: BookmarkStore.Bookmark?) {
        val pad = NexoUi.dp(this, 14f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val titleInput = EditText(this).apply {
            hint = "Título"
            setText(existing?.title.orEmpty())
            setSingleLine(true)
            ThemeManager.styleEdit(this@BookmarksActivity, this)
        }
        val urlInput = EditText(this).apply {
            hint = "https://ejemplo.com"
            setText(existing?.url.orEmpty())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ThemeManager.styleEdit(this@BookmarksActivity, this)
        }
        val folderInput = EditText(this).apply {
            hint = "Carpeta (ej. General, Lectura, Trabajo)"
            setText(existing?.folder ?: selectedFolder ?: BookmarkStore.DEFAULT_FOLDER)
            setSingleLine(true)
            ThemeManager.styleEdit(this@BookmarksActivity, this)
        }
        box.addView(titleInput, NexoUi.buttonParams(this, 4f))
        box.addView(urlInput, NexoUi.buttonParams(this, 8f))
        box.addView(folderInput, NexoUi.buttonParams(this, 8f))

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuevo favorito" else "Editar favorito")
            .setView(box)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ ->
                val rawUrl = urlInput.text?.toString().orEmpty().trim()
                val normalized = if (rawUrl.contains("://")) rawUrl else "https://$rawUrl"
                if (!UrlSafety.isWebUrl(normalized)) {
                    Toast.makeText(this, "Introduce una dirección HTTP o HTTPS válida.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val title = titleInput.text?.toString().orEmpty().trim().ifBlank { normalized }
                val folder = folderInput.text?.toString().orEmpty().trim().ifBlank { BookmarkStore.DEFAULT_FOLDER }
                val file = BookmarkStore.file(this)
                if (existing == null) {
                    BookmarkStore.add(file, title, normalized, folder)
                } else {
                    BookmarkStore.update(file, existing.id, title, normalized, folder)
                }
                render()
            }.show()
    }

    private fun launchImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/html", "application/json", "text/plain"))
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_IMPORT)
        }.onFailure {
            Toast.makeText(this, "No se pudo abrir el selector de archivos.", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchExport(asHtml: Boolean) {
        if (BookmarkStore.list(this).isEmpty()) {
            Toast.makeText(this, "No hay favoritos para exportar.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExportHtml = asHtml
        pendingExportMime = if (asHtml) "text/html" else "application/json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = pendingExportMime
            putExtra(Intent.EXTRA_TITLE, if (asHtml) "nexo-favoritos.html" else "nexo-favoritos.json")
        }
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_EXPORT)
        }.onFailure {
            Toast.makeText(this, "No se pudo crear el archivo de exportación.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_IMPORT -> {
                val content = runCatching {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull().orEmpty()
                if (content.isBlank()) {
                    Toast.makeText(this, "El archivo está vacío o no se pudo leer.", Toast.LENGTH_LONG).show()
                    return
                }
                val file = BookmarkStore.file(this)
                val trimmed = content.trim()
                val imported = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    BookmarkStore.importJson(file, trimmed)
                } else {
                    BookmarkStore.importNetscapeHtml(file, trimmed)
                }
                render()
                Toast.makeText(
                    this,
                    if (imported > 0) "Importados $imported favoritos nuevos." else "No se encontraron favoritos web nuevos.",
                    Toast.LENGTH_LONG
                ).show()
            }
            REQ_EXPORT -> {
                val payload = if (pendingExportHtml) {
                    BookmarkStore.exportNetscapeHtml(BookmarkStore.list(this))
                } else {
                    BookmarkStore.exportJson(BookmarkStore.list(this))
                }
                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
                Toast.makeText(
                    this,
                    if (ok) "Favoritos exportados correctamente." else "No se pudo guardar el archivo de exportación.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.listRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.listTitle))
        ThemeManager.styleButton(this, findViewById(R.id.listAction), primary = true)
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
    }

    companion object {
        private const val REQ_IMPORT = 710
        private const val REQ_EXPORT = 711
    }
}
