package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Normalizer
import java.util.Calendar

/**
 * Historial con búsqueda en vivo: filtra por título, dominio o dirección
 * mientras se escribe (sin distinguir mayúsculas ni tildes).
 */
class HistoryActivity : Activity() {
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var search: EditText
    private lateinit var clearSearch: ImageButton
    private var all: List<IndexedEntry> = emptyList()
    private var entries: List<HistoryEntry> = emptyList()
    private var query = ""
    private val main = Handler(Looper.getMainLooper())
    private val applyFilter = Runnable { render() }

    /** Entrada con su texto de búsqueda ya normalizado: filtrar no repite trabajo. */
    private data class IndexedEntry(val entry: HistoryEntry, val host: String, val haystack: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyWindow(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        listView = findViewById(R.id.listView)
        emptyView = findViewById<TextView>(R.id.listEmpty).apply {
            setText(R.string.empty_history)
            ThemeManager.styleText(this@HistoryActivity, this, muted = true)
        }
        listView.emptyView = emptyView
        findViewById<TextView>(R.id.listTitle).text = "Historial"
        addSearchField()

        findViewById<Button>(R.id.listAction).apply {
            text = "Limpiar"
            setOnClickListener {
                AlertDialog.Builder(this@HistoryActivity)
                    .setTitle("Limpiar historial")
                    .setMessage("Esto no elimina descargas.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Limpiar") { _, _ ->
                        HistoryStore.clear(this@HistoryActivity)
                        reload(force = true)
                    }.show()
            }
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            entries.getOrNull(position)?.let { open(it.url) }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val entry = entries.getOrNull(position) ?: return@setOnItemLongClickListener true
            showEntryOptions(entry)
            true
        }
        // Al desplazar la lista se oculta el teclado para ver más resultados.
        listView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) {
                if (scrollState == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }
            override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) = Unit
        })
        applyTheme()
    }

    override fun onResume() {
        super.onResume()
        reload(force = false)
    }

    override fun onDestroy() {
        main.removeCallbacks(applyFilter)
        super.onDestroy()
    }

    private fun addSearchField() {
        val p = ThemeManager.palette(this)
        val root = findViewById<LinearLayout>(R.id.listRoot)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(2f), dp(16f), dp(10f))
        }
        val searchBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainer
        val searchStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        search = EditText(this).apply {
            hint = "Buscar en el historial…"
            setSingleLine(true)
            textSize = 14.5f
            typeface = NexoUi.regular(this@HistoryActivity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            minHeight = dp(48f)
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = ThemeManager.rounded(this@HistoryActivity, searchBg, 24f, searchStroke)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablesRelative.firstOrNull()?.mutate()?.setTint(p.muted)
            compoundDrawablePadding = dp(10f)
            setPadding(dp(16f), dp(8f), dp(48f), dp(8f))
            contentDescription = "Buscar en el historial"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = normalize(s?.toString().orEmpty().trim())
                    clearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    // Breve espera: se filtra mientras se escribe sin rehacer la lista en cada tecla.
                    main.removeCallbacks(applyFilter)
                    main.postDelayed(applyFilter, 90)
                }
            })
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    hideKeyboard(); true
                } else false
            }
        }
        clearSearch = ImageButton(this).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Borrar búsqueda"
            visibility = View.GONE
            ThemeManager.styleIconButton(this@HistoryActivity, this)
            imageTintList = android.content.res.ColorStateList.valueOf(p.muted)
            setOnClickListener { search.setText(""); search.requestFocus() }
        }
        val frame = android.widget.FrameLayout(this).apply {
            addView(search, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(clearSearch, android.widget.FrameLayout.LayoutParams(dp(48f), dp(48f), android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL))
        }
        row.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row, 1)
    }

    private fun reload(force: Boolean) {
        val updated = HistoryStore.list(this)
        if (!force && listView.adapter != null && updated.size == all.size &&
            updated.zip(all).all { (a, b) -> a == b.entry }) return
        all = updated.map {
            val host = runCatching { android.net.Uri.parse(it.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
            IndexedEntry(it, host, normalize(it.title + " " + host + " " + it.url))
        }
        findViewById<Button>(R.id.listAction).isEnabled = all.isNotEmpty()
        search.isEnabled = all.isNotEmpty()
        render()
    }

    private fun render() {
        val terms = query.split(' ').filter { it.isNotBlank() }
        val visible = if (terms.isEmpty()) all else all.filter { item -> terms.all { item.haystack.contains(it) } }
        entries = visible.map { it.entry }
        emptyView.text = if (all.isEmpty()) getString(R.string.empty_history)
            else "Sin resultados para «${search.text.toString().trim()}»"
        val today = startOfDay(0)
        val yesterday = startOfDay(-1)
        listView.adapter = ThemeManager.rowsAdapter(
            this,
            visible.map { item ->
                val entry = item.entry
                val day = when {
                    entry.timestamp >= today -> "Hoy"
                    entry.timestamp >= yesterday -> "Ayer"
                    else -> DateFormat.format("d MMM yyyy", entry.timestamp).toString()
                }
                ListRow(
                    title = entry.title.ifBlank { item.host.ifBlank { "Página web" } },
                    subtitle = item.host.ifBlank { entry.url },
                    meta = day + " · " + DateFormat.format("HH:mm", entry.timestamp),
                    badge = siteInitial(item.host)
                )
            }
        )
        findViewById<TextView>(R.id.listTitle).text =
            if (terms.isEmpty()) "Historial" else "Historial · ${visible.size}"
    }

    private fun startOfDay(offsetDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Inicial del dominio principal: es.wikipedia.org → W, m.youtube.com → Y. */
    private fun siteInitial(host: String): String {
        val labels = host.split('.').filter { it.isNotBlank() }
        val main = if (labels.size >= 2) labels[labels.size - 2] else labels.firstOrNull().orEmpty()
        return main.take(1).uppercase()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private fun showEntryOptions(entry: HistoryEntry) {
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val cleanUrl = com.nexo.browser.shield.ShieldPolicy.stripTrackingParams(UrlSafety.stripCredentials(entry.url))
            ?: UrlSafety.stripCredentials(entry.url)
        val actions = listOf(
            SheetAction(R.drawable.ic_menu_open, "Abrir en pestaña nueva") {
                hideKeyboard()
                TabManager.createTab(entry.url)
                finish()
            },
            SheetAction(R.drawable.ic_menu_private, "Abrir en pestaña privada") {
                hideKeyboard()
                TabManager.createTab(entry.url, isPrivate = true)
                finish()
            },
            SheetAction(R.drawable.ic_menu_bookmark, "Guardar en favoritos") {
                val saved = com.nexo.browser.workspace.BookmarkStore.add(this, entry.title.ifBlank { entry.url }, entry.url)
                android.widget.Toast.makeText(
                    this,
                    if (saved != null) "Guardado en favoritos" else "No se pudo guardar este enlace",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            SheetAction(R.drawable.ic_menu_copy, "Copiar enlace limpio") {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(ClipboardPrivacy.plainText("Enlace", cleanUrl, sensitive = false))
                android.widget.Toast.makeText(this, "Enlace copiado", android.widget.Toast.LENGTH_SHORT).show()
            },
            SheetAction(R.drawable.ic_menu_close, "Quitar del historial", danger = true) {
                HistoryStore.remove(this, entry.url)
                reload(force = true)
            }
        )
        sheet = NexoSheets.show(
            this,
            NexoSheets.column(
                this,
                NexoSheets.header(this, entry.title.ifBlank { "Página visitada" }, cleanUrl),
                NexoSheets.list(this, actions, dismiss)
            )
        )
    }

    private fun open(url: String) {
        hideKeyboard()
        startActivity(MainActivity.openUrlIntent(this, url))
    }

    private fun applyTheme() {
        val p = ThemeManager.palette(this)
        findViewById<View>(R.id.listRoot).setBackgroundColor(p.background)
        ThemeManager.styleText(this, findViewById(R.id.listTitle))
        ThemeManager.styleButton(this, findViewById(R.id.listAction))
        ThemeManager.styleBackButton(this, findViewById(R.id.backButton))
    }
}
