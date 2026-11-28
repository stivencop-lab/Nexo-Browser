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

    private var bookmarks: List<BookmarkStore.Bookmark> = emptyList()
    private var visible: List<BookmarkStore.Bookmark> = emptyList()
    private var selectedFolder: String? = null
    private var query = ""
    private var pendingExportMime = "text/html"
    private var pendingExportHtml = true

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        pendingExportHtml = savedInstanceState?.getBoolean("bookmarkExportHtml", true) ?: true
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
        runBookmarkOperation({ BookmarkStore.list(it) }) { result ->
            result.onSuccess { bookmarks = it; render() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("bookmarkExportHtml", pendingExportHtml)
        super.onSaveInstanceState(outState)
    }

    private fun <T> runBookmarkOperation(
        work: (android.content.Context) -> T,
        complete: (Result<T>) -> Unit
    ) {
        val app = applicationContext
        bookmarkIo.execute {
            val result = runCatching { work(app) }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) complete(result)
            }
        }
    }

    private fun readImport(input: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= MAX_IMPORT_BYTES - output.size()) { "El archivo de favoritos supera el límite de importación." }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun buildTopControls() {
        val p = ThemeManager.palette(this)
        val root = findViewById<LinearLayout>(R.id.listRoot)

        // Barra de búsqueda tipo píldora
        val searchBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainer
        val searchStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(NexoUi.dp(this@BookmarksActivity, 16f), NexoUi.dp(this@BookmarksActivity, 2f), NexoUi.dp(this@BookmarksActivity, 16f), NexoUi.dp(this@BookmarksActivity, 8f))
        }
        search = EditText(this).apply {
            hint = "Buscar en favoritos…"
            setSingleLine(true)
            textSize = 14.5f
            typeface = NexoUi.regular(this@BookmarksActivity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            minHeight = NexoUi.dp(this@BookmarksActivity, 48f)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT
            background = ThemeManager.rounded(this@BookmarksActivity, searchBg, 24f, searchStroke)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablesRelative.firstOrNull()?.mutate()?.setTint(p.muted)
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
        val folders = (listOf(BookmarkStore.DEFAULT_FOLDER) + bookmarks.map { it.folder }).distinct()
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

        visible = BookmarkStore.search(bookmarks, query, selectedFolder)
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
                runBookmarkOperation({ app ->
                    check(BookmarkStore.delete(app, bm.id)) { "El favorito ya no existe." }
                    BookmarkStore.list(app)
                }) { result ->
                    result.onSuccess {
                        bookmarks = it
                        render()
                        Toastish.show(this, "Favorito eliminado")
                    }.onFailure { Toastish.show(this, "No se pudo eliminar el favorito.") }
                }
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
                runBookmarkOperation({ app ->
                    val file = BookmarkStore.file(app)
                    val saved = if (existing == null) BookmarkStore.add(file, title, normalized, folder) != null
                        else BookmarkStore.update(file, existing.id, title, normalized, folder)
                    check(saved) { "No se pudo guardar el favorito." }
                    BookmarkStore.list(app)
                }) { result ->
                    result.onSuccess { bookmarks = it; render() }
                        .onFailure { Toastish.show(this, "No se pudo guardar el favorito.") }
                }
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
            REQ_IMPORT -> runBookmarkOperation({ app ->
                val content = app.contentResolver.openInputStream(uri)?.use(::readImport)?.trim().orEmpty()
                require(content.isNotEmpty()) { "El archivo está vacío o no se pudo leer." }
                val file = BookmarkStore.file(app)
                val imported = if (content.startsWith("{") || content.startsWith("[")) {
                    BookmarkStore.importJson(file, content)
                } else {
                    BookmarkStore.importNetscapeHtml(file, content)
                }
                imported to BookmarkStore.list(file)
            }) { result ->
                result.onSuccess { (imported, updated) ->
                    bookmarks = updated
                    render()
                    Toastish.show(this, if (imported > 0) "Importados $imported favoritos nuevos." else "No se encontraron favoritos web nuevos.")
                }.onFailure { Toastish.show(this, it.message ?: "No se pudieron importar los favoritos.") }
            }
            REQ_EXPORT -> {
                val exportHtml = pendingExportHtml
                runBookmarkOperation({ app ->
                    val records = BookmarkStore.list(app)
                    val payload = if (exportHtml) BookmarkStore.exportNetscapeHtml(records) else BookmarkStore.exportJson(records)
                    val stream = app.contentResolver.openOutputStream(uri, "wt") ?: error("No se pudo abrir el archivo de exportación.")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
                }) { result ->
                    Toastish.show(this, if (result.isSuccess) "Favoritos exportados correctamente." else "No se pudo guardar el archivo de exportación.")
                }
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
        private const val MAX_IMPORT_BYTES = 48 * 1024 * 1024
        private val bookmarkIo = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "NexoBookmarks").apply { isDaemon = true }
        }

        private const val REQ_IMPORT = 710
        private const val REQ_EXPORT = 711
    }
}
