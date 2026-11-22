package com.nexo.browser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Nexo Tabs Pro (UI 5.0): selector moderno de pestañas a pantalla completa.
 *
 * Composición optimizada:
 * - Cabecera unificada con selector segmentado integrado (Pestañas | Privadas) y botón
 *   de cambio de vista (Cuadrícula / Compacta / Lista).
 * - Buscador integrado + rail único de grupos y filtro de duplicadas.
 * - Tarjetas limpias de altura uniforme con insignias integradas en el marco de vista previa,
 *   identificación clara de la pestaña activa y controles de cierre discretos.
 * - Barra inferior ergonómica para una sola mano (+ Nueva pestaña al alcance del pulgar).
 */
object TabSwitcher {
    private const val MAX_PREFETCH_PREVIEWS = 12

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun show(
        activity: Activity,
        onNewTab: (private: Boolean) -> Unit
    ) {
        val p = ThemeManager.palette(activity)
        val dialog = Dialog(activity, ThemeManager.themeRes(activity))
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var showPrivate = TabManager.activeTab()?.isPrivate == true
        // null = todas; "" = sin grupo; cualquier otro valor = nombre del grupo.
        var selectedGroup: String? = null
        var searchQuery = ""
        var onlyDuplicates = false
        var viewMode = TabProModel.ViewMode.fromId(BrowserPrefs.tabViewMode(activity))

        // Deck espacial: columna de contenido + barra inferior flotante de cristal superpuesta.
        val frame = FrameLayout(activity).apply { setBackgroundColor(p.background) }
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        lateinit var refresh: () -> Unit

        // ==================== BARRA SUPERIOR COMPACTA ====================
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, 8f), dp(activity, 8f), dp(activity, 8f), dp(activity, 6f))
        }
        val back = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_nav_back)
            contentDescription = activity.getString(R.string.back)
            layoutParams = LinearLayout.LayoutParams(dp(activity, 48f), dp(activity, 48f))
            ThemeManager.styleIconButton(activity, this)
            setOnClickListener { dialog.dismiss() }
        }

        val heading = TextView(activity).apply {
            text = "Pestañas"
            textSize = 17f
            letterSpacing = -0.025f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
            gravity = Gravity.CENTER
            isAccessibilityHeading = true
        }
        val searchToggle = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_search)
            contentDescription = "Buscar pestañas"
            layoutParams = LinearLayout.LayoutParams(dp(activity, 48f), dp(activity, 48f))
            ThemeManager.styleIconButton(activity, this)
        }
        val viewModeBtn = ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 48f), dp(activity, 48f))
            ThemeManager.styleIconButton(activity, this)
        }

        // «Cerrar todas» vive en la hoja de vista (botón de vista) junto a las densidades.
        val closeAll = Button(activity).apply { visibility = View.GONE }
        topBar.addView(back)
        topBar.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(searchToggle)
        topBar.addView(viewModeBtn)
        root.addView(topBar)

        // Segmentos [Pestañas | Privadas] dentro de la barra flotante inferior.
        val normalSeg = segmentButton(activity, "Pestañas")
        val privateSeg = segmentButton(activity, "Privadas")
        val segments = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = NexoGlassDrawable(activity, p, NexoUi.Tokens.RADIUS_PILL)
            NexoGlass.float(this, p)
            setPadding(dp(activity, 5f), dp(activity, 5f), dp(activity, 5f), dp(activity, 5f))
            addView(normalSeg, LinearLayout.LayoutParams(0, dp(activity, 48f), 1f))
            addView(privateSeg, LinearLayout.LayoutParams(0, dp(activity, 48f), 1f))
        }

        // ==================== BUSCADOR + RAIL DE GRUPOS ====================
        val (searchBox, searchInput) = NexoUi.searchBar(activity, "Buscar pestañas por título, sitio o grupo") { q ->
            searchQuery = q
            refresh()
        }
        searchBox.visibility = View.GONE
        root.addView(searchBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(activity, 16f), dp(activity, 2f), dp(activity, 16f), dp(activity, 4f))
        })
        searchToggle.setOnClickListener {
            val show = searchBox.visibility != View.VISIBLE
            searchBox.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                searchInput.requestFocus()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(searchInput, 0)
            } else searchInput.setText("")
        }

        val groupBar = FrameLayout(activity)
        root.addView(groupBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(activity, 2f) })

        // ==================== CUADRÍCULA / COMPACTA / LISTA ====================
        val emptyContainer = FrameLayout(activity).apply { visibility = View.GONE }
        val metrics = activity.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val gridColumns = when {
            widthDp >= 840f -> 4
            widthDp >= 600f -> 3
            else -> 2
        }
        val layoutManager = GridLayoutManager(activity, if (viewMode == TabProModel.ViewMode.LIST) 1 else gridColumns)
        val grid = RecyclerView(activity).apply {
            this.layoutManager = layoutManager
            setPadding(dp(activity, 11f), dp(activity, 4f), dp(activity, 11f), dp(activity, 112f))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
            val gap = dp(activity, 5f)  // 10dp entre tarjetas
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.set(gap, gap, gap, gap)
                }
            })
        }
        val body = FrameLayout(activity).apply {
            addView(grid)
            addView(emptyContainer)
        }
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fun applyViewMode(nextMode: TabProModel.ViewMode) {
            if (viewMode != nextMode) {
                viewMode = nextMode
                BrowserPrefs.setTabViewMode(activity, nextMode.id)
                layoutManager.spanCount = if (nextMode == TabProModel.ViewMode.LIST) 1 else gridColumns
                refresh()
            }
        }

        fun updateViewModeIcon() {
            val (iconRes, desc) = when (viewMode) {
                TabProModel.ViewMode.GRID -> R.drawable.ic_view_grid to "Vista cuadrícula. Opciones de vista y cerrar pestañas"
                TabProModel.ViewMode.COMPACT -> R.drawable.ic_view_compact to "Vista compacta. Opciones de vista y cerrar pestañas"
                TabProModel.ViewMode.LIST -> R.drawable.ic_view_list to "Vista lista. Opciones de vista y cerrar pestañas"
            }
            viewModeBtn.setImageResource(iconRes)
            viewModeBtn.contentDescription = desc
        }

        viewModeBtn.setOnLongClickListener {
            val allModes = TabProModel.ViewMode.entries
            val next = allModes[(allModes.indexOf(viewMode) + 1) % allModes.size]
            applyViewMode(next)
            Toastish.show(activity, "Vista: ${next.label}")
            true
        }
        viewModeBtn.setOnClickListener {
            lateinit var picker: Dialog
            val actions = listOf(
                SheetAction(R.drawable.ic_view_grid, "Cuadrícula", active = viewMode == TabProModel.ViewMode.GRID, subtitle = "Miniaturas visuales por pestaña") {
                    applyViewMode(TabProModel.ViewMode.GRID)
                },
                SheetAction(R.drawable.ic_view_compact, "Compacta", active = viewMode == TabProModel.ViewMode.COMPACT, subtitle = "Tarjetas ligeras sin miniaturas") {
                    applyViewMode(TabProModel.ViewMode.COMPACT)
                },
                SheetAction(R.drawable.ic_view_list, "Lista", active = viewMode == TabProModel.ViewMode.LIST, subtitle = "Ideal para muchas pestañas abiertas") {
                    applyViewMode(TabProModel.ViewMode.LIST)
                },
                SheetAction(R.drawable.ic_menu_close, closeAll.text.toString().ifBlank { "Cerrar todas" }, danger = true) {
                    closeAll.performClick()
                }
            )
            picker = NexoSheets.show(
                activity,
                NexoSheets.column(
                    activity,
                    NexoSheets.header(activity, "Vista de pestañas"),
                    NexoSheets.list(activity, actions) { picker.dismiss() }
                )
            )
        }

        // ==================== BARRA DESHACER ====================
        val undoText = TextView(activity).apply {
            textSize = 13.5f
            typeface = NexoUi.medium(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val undoButton = TextView(activity).apply {
            text = "Deshacer"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(p.text)
            typeface = NexoUi.semiBold(activity)
            setPadding(dp(activity, 14f), dp(activity, 8f), dp(activity, 14f), dp(activity, 8f))
            minWidth = dp(activity, 48f)
            minHeight = dp(activity, 48f)
            background = ThemeManager.ripple(activity, null, 14f)
            isClickable = true
        }
        val undoBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 48f)
            setPadding(dp(activity, 16f), dp(activity, 2f), dp(activity, 4f), dp(activity, 2f))
            background = NexoGlassDrawable(activity, p, NexoUi.Tokens.RADIUS_PILL)
            NexoGlass.float(this, p)
            visibility = View.GONE
            addView(undoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(undoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // ==================== BARRA INFERIOR FLOTANTE DE CRISTAL ====================
        val newTabBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_nav_add)
            contentDescription = "Abrir una nueva pestaña"
            scaleType = ImageView.ScaleType.CENTER
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.text, NexoUi.Tokens.RADIUS_PILL), NexoUi.Tokens.RADIUS_PILL)
            imageTintList = ColorStateList.valueOf(p.background)
            NexoGlass.float(this, p)
            setOnClickListener {
                NexoMotion.pulse(this, 0.9f)
                dialog.dismiss()
                onNewTab(showPrivate)
                TabManager.activeTab()?.let { TabManager.moveToGroup(it.id, selectedGroup) }
            }
        }
        val bottomDock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16f), 0, dp(activity, 16f), dp(activity, 12f))
            clipChildren = false
            clipToPadding = false
        }
        bottomDock.addView(undoBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(activity, 10f)
        })
        bottomDock.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(segments, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(newTabBtn, LinearLayout.LayoutParams(dp(activity, 58f), dp(activity, 58f)).apply { marginStart = dp(activity, 10f) })
        })
        frame.addView(bottomDock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val hideUndo = Runnable { undoBar.visibility = View.GONE }

        fun closeWithUndo(tab: BrowserTab) {
            val lastNormal = !tab.isPrivate && TabManager.allTabs().count { !it.isPrivate } == 1
            TabManager.closeTab(tab.id)
            if (lastNormal) { dialog.dismiss(); return }
            refresh()
            undoBar.removeCallbacks(hideUndo)
            if (TabManager.lastClosed()?.id == tab.id) {
                undoText.text = "Cerrada: " + tab.title.ifBlank { "pestaña" }
                undoButton.setOnClickListener {
                    undoBar.removeCallbacks(hideUndo)
                    undoBar.visibility = View.GONE
                    TabManager.reopenClosed(tab.id, activate = false)
                    refresh()
                }
                undoBar.visibility = View.VISIBLE
                undoBar.postDelayed(hideUndo, 5000L)
            } else {
                undoBar.visibility = View.GONE
            }
        }

        val adapter = TabCardAdapter(
            activity,
            onOpen = { tab ->
                TabManager.switchTo(tab.id)
                dialog.dismiss()
            },
            onClose = { tab -> closeWithUndo(tab) },
            onLongPress = null
        )
        grid.adapter = adapter

        var dragged: RecyclerView.ViewHolder? = null
        var dragMoved = false
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = holder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0 || from == to) return false
                val moving = adapter.items[from]
                val anchor = adapter.items[to]
                if (moving.pinned != anchor.pinned) return false
                adapter.moveItem(from, to)
                TabManager.moveTab(moving.id, anchor.id, after = to > from)
                dragMoved = true
                return true
            }
            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(holder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && holder != null) {
                    dragged = holder
                    dragMoved = false
                    holder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    val dur = NexoUi.animDuration(activity, 120L)
                    if (dur > 0L) holder.itemView.animate().scaleX(1.03f).scaleY(1.03f).setDuration(dur).start()
                    holder.itemView.elevation = dp(activity, 8f).toFloat()
                }
            }
            override fun getSwipeThreshold(holder: RecyclerView.ViewHolder) = 0.35f
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val tab = adapter.items.getOrNull(holder.bindingAdapterPosition) ?: return
                closeWithUndo(tab)
            }
            override fun onChildDraw(
                canvas: Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, active: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val width = holder.itemView.width.coerceAtLeast(1)
                    holder.itemView.alpha = (1f - abs(dX) / width).coerceIn(0.15f, 1f)
                    holder.itemView.rotation = (dX / width) * 3.5f
                }
                super.onChildDraw(canvas, rv, holder, dX, dY, actionState, active)
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                holder.itemView.alpha = 1f
                holder.itemView.rotation = 0f
                if (dragged === holder) {
                    dragged = null
                    val dur = NexoUi.animDuration(activity, 120L)
                    if (dur > 0L) holder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(dur).start()
                    else { holder.itemView.scaleX = 1f; holder.itemView.scaleY = 1f }
                    holder.itemView.elevation = 0f
                    if (!dragMoved) adapter.items.getOrNull(holder.bindingAdapterPosition)?.let { tab ->
                        moveSheet(activity, tab) { refresh() }
                    } else refresh()
                }
            }
        }).attachToRecyclerView(grid)

        val previewListener: (String) -> Unit = { id ->
            if (TabProModel.shouldPrefetchPreviews(viewMode)) adapter.previewChanged(id)
        }
        TabPreviewStore.addListener(previewListener)
        dialog.setOnDismissListener {
            TabPreviewStore.removeListener(previewListener)
            undoBar.removeCallbacks(hideUndo)
        }

        refresh = {
            val all = TabManager.allTabs()
            val normalCount = all.count { !it.isPrivate }
            val privateCount = all.count { it.isPrivate }
            val visibleMode = all.filter { it.isPrivate == showPrivate }
            val summaries = visibleMode.map {
                TabProModel.TabSummary(it.id, it.title, it.url, it.groupName, it.isPrivate, it.pinned)
            }
            val dupGroups = TabProModel.duplicateGroupCount(summaries)
            if (dupGroups == 0) onlyDuplicates = false

            updateViewModeIcon()

            // --- Rail unificado de grupos + duplicadas
            val names = visibleMode.mapNotNull { it.groupName }.distinct().sorted()
            if (selectedGroup != null && selectedGroup != "" && selectedGroup !in names) selectedGroup = null
            if (selectedGroup == "" && (names.isEmpty() || visibleMode.none { it.groupName == null })) selectedGroup = null

            val chips = mutableListOf<View>()
            if (names.isNotEmpty() || dupGroups > 0) {
                chips += NexoUi.chip(activity, "Todas · ${visibleMode.size}", selectedGroup == null && !onlyDuplicates) {
                    selectedGroup = null; onlyDuplicates = false; refresh()
                }
            }
            names.forEach { name ->
                val count = visibleMode.count { it.groupName == name }
                chips += NexoUi.chip(activity, "$name · $count", selectedGroup == name, TabGroupRules.color(name)) {
                    selectedGroup = name; refresh()
                }.apply {
                    setOnLongClickListener { groupSheet(activity, name, showPrivate) { renamed -> selectedGroup = renamed; refresh() }; true }
                }
            }
            val ungrouped = visibleMode.count { it.groupName == null }
            if (names.isNotEmpty() && ungrouped > 0) {
                chips += NexoUi.chip(activity, "Sin grupo · $ungrouped", selectedGroup == "") { selectedGroup = ""; refresh() }
            }
            if (dupGroups > 0) {
                chips += NexoUi.chip(
                    activity,
                    if (dupGroups == 1) "Duplicadas · 1" else "Duplicadas · $dupGroups",
                    onlyDuplicates,
                    p.warning.color
                ) {
                    onlyDuplicates = !onlyDuplicates
                    refresh()
                }
            }
            chips += NexoUi.chip(activity, "Nuevo grupo", false) {
                NexoUi.askText(activity, "Nuevo grupo", "Nombre del grupo", action = "Crear") { value ->
                    val name = TabGroupRules.name(value) ?: return@askText
                    val tab = TabManager.activeTab()?.takeIf { it.isPrivate == showPrivate }
                        ?: adapter.items.firstOrNull() ?: TabManager.createTab(BrowserPrefs.homePage(activity), showPrivate, false)
                        ?: return@askText
                    TabManager.moveToGroup(tab.id, name)
                    selectedGroup = name; refresh()
                }
            }
            groupBar.removeAllViews()
            groupBar.addView(NexoUi.chipRow(activity, chips, sidePaddingDp = 16f), FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL
            ))

            val (items, dupes) = TabProModel.organize(
                tabs = all,
                showPrivate = showPrivate,
                selectedGroup = selectedGroup,
                query = searchQuery,
                onlyDuplicates = onlyDuplicates
            ) { TabProModel.TabSummary(it.id, it.title, it.url, it.groupName, it.isPrivate, it.pinned) }

            if (TabProModel.shouldPrefetchPreviews(viewMode)) {
                TabPreviewStore.prefetch(activity, items.filterNot { it.isPrivate }.take(MAX_PREFETCH_PREVIEWS).map { it.id })
            }
            adapter.submit(items, TabManager.activeTab()?.id, viewMode, dupes)

            val pinnedInList = items.count { it.pinned }
            val closable = items.filterNot { it.pinned }
            closeAll.text = when {
                selectedGroup == "" -> "Cerrar sin grupo"
                selectedGroup != null -> "Cerrar «$selectedGroup»"
                pinnedInList > 0 && closable.isNotEmpty() -> "Cerrar (${closable.size})"
                else -> "Cerrar todas"
            }
            closeAll.isEnabled = items.isNotEmpty()
            closeAll.alpha = if (items.isNotEmpty()) 1f else 0.45f
            newTabBtn.contentDescription = if (showPrivate) "Abrir una pestaña privada" else "Abrir una nueva pestaña"

            WindowPrivacy.apply(dialog.window!!, showPrivate || activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)

            emptyContainer.removeAllViews()
            if (items.isEmpty()) {
                val (emptyIcon, emptyTitle, emptySub, ctaLabel, ctaAction) = when {
                    searchQuery.isNotBlank() -> listOf(
                        R.drawable.ic_search, "Sin coincidencias",
                        "Ninguna pestaña coincide con «$searchQuery».", "Limpiar búsqueda",
                        { searchInput.setText("") }
                    )
                    showPrivate -> listOf(
                        R.drawable.ic_menu_private, "Sin pestañas privadas",
                        "Las pestañas privadas no guardan historial ni cookies al cerrarse.", "Nueva privada",
                        { dialog.dismiss(); onNewTab(true) }
                    )
                    else -> listOf(
                        R.drawable.ic_nav_add, "No hay pestañas aquí",
                        "Crea una pestaña nueva o cambia de filtro.", "Nueva pestaña",
                        { dialog.dismiss(); onNewTab(false) }
                    )
                }
                @Suppress("UNCHECKED_CAST")
                emptyContainer.addView(NexoUi.emptyState(
                    activity,
                    emptyIcon as Int,
                    emptyTitle as String,
                    emptySub as String,
                    ctaLabel as String,
                    ctaAction as () -> Unit
                ), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                emptyContainer.visibility = View.VISIBLE
            } else {
                emptyContainer.visibility = View.GONE
            }

            normalSeg.text = "Pestañas"
            privateSeg.text = "Privadas"
            normalSeg.contentDescription = "Pestañas: $normalCount"
            privateSeg.contentDescription = "Privadas: $privateCount"
            heading.text = if (showPrivate) "Privadas · $privateCount" else "Pestañas · $normalCount"
            styleSegment(activity, normalSeg, !showPrivate)
            styleSegment(activity, privateSeg, showPrivate)
        }

        closeAll.setOnClickListener {
            val nonPinned = adapter.items.filterNot { it.pinned }
            val targets = if (selectedGroup == null && nonPinned.isNotEmpty() && nonPinned.size < adapter.items.size) {
                nonPinned
            } else {
                adapter.items
            }
            val ids = targets.map { it.id }
            val keepNote = if (targets.size < adapter.items.size) {
                "Se conservarán ${adapter.items.size - targets.size} pestañas fijadas."
            } else null
            android.app.AlertDialog.Builder(activity)
                .setTitle("¿Cerrar ${ids.size} pestañas?")
                .apply { if (keepNote != null) setMessage(keepNote) }
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Cerrar") { _, _ ->
                    ids.forEach { TabManager.closeTab(it) }
                    if (targets.size == adapter.items.size) dialog.dismiss() else refresh()
                }.show()
        }
        normalSeg.setOnClickListener { if (showPrivate) { showPrivate = false; selectedGroup = null; onlyDuplicates = false; refresh() } }
        privateSeg.setOnClickListener { if (!showPrivate) { showPrivate = true; selectedGroup = null; onlyDuplicates = false; refresh() } }
        refresh()
        adapter.items.indexOfFirst { it.id == TabManager.activeTab()?.id }.takeIf { it > 0 }?.let { grid.scrollToPosition(it) }

        dialog.setContentView(frame)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(p.background))
            WindowCompat.setDecorFitsSystemWindows(this, false)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !p.isDark
                isAppearanceLightNavigationBars = !p.isDark
            }
            setWindowAnimations(android.R.style.Animation_Activity)
        }
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            root.setPadding(safe.left, safe.top, safe.right, 0)
            bottomDock.setPadding(safe.left + dp(activity, 16f), 0, safe.right + dp(activity, 16f), maxOf(safe.bottom, ime) + dp(activity, 12f))
            insets
        }
        dialog.show()
        ViewCompat.requestApplyInsets(frame)
    }

    private fun moveSheet(activity: Activity, tab: BrowserTab, done: () -> Unit) {
        val names = TabManager.allTabs().filter { it.isPrivate == tab.isPrivate }.mapNotNull { it.groupName }.distinct().sorted()
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        val actionCard = NexoUi.card(activity, paddingDp = 4f)
        actionCard.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_pin,
            if (tab.pinned) "Desfijar pestaña" else "Fijar pestaña",
            subtitle = if (tab.pinned) "Volver al orden normal" else "Mantener al principio"
        ) {
            TabManager.togglePin(tab.id)
            sheet.dismiss()
            done()
        })
        actionCard.addView(NexoUi.insetDivider(activity))
        actionCard.addView(NexoUi.actionRow(activity, R.drawable.ic_nav_add, "Nuevo grupo con esta pestaña") {
            sheet.dismiss()
            NexoUi.askText(activity, "Nuevo grupo", "Nombre del grupo", action = "Crear y mover") { value ->
                TabManager.moveToGroup(tab.id, value); done()
            }
        })
        actionCard.addView(NexoUi.insetDivider(activity))
        actionCard.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_close, "Cerrar pestaña", danger = true) {
            sheet.dismiss(); TabManager.closeTab(tab.id); done()
        })
        body.addView(actionCard)

        val chips = mutableListOf<View>()
        chips += NexoUi.chip(activity, "Sin grupo", tab.groupName == null) { TabManager.moveToGroup(tab.id, null); sheet.dismiss(); done() }
        names.forEach { name ->
            chips += NexoUi.chip(activity, name, tab.groupName == name, TabGroupRules.color(name)) {
                TabManager.moveToGroup(tab.id, name); sheet.dismiss(); done()
            }
        }
        body.addView(NexoUi.section(activity, "Mover a grupo"))
        body.addView(NexoUi.chipRow(activity, chips, sidePaddingDp = 0f))

        val subtitle = buildString {
            append(tab.groupName?.let { "Grupo: $it" } ?: "Sin grupo")
            if (tab.pinned) append(" · Fijada")
        }
        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, tab.title.ifBlank { "Nueva pestaña" }, subtitle,
                badge = if (tab.pinned) "Fijada" else null, badgeTone = NexoUi.Tone.ACCENT), body))
    }

    /** Hoja de un grupo: renombrar, desagrupar o cerrar sus pestañas. */
    private fun groupSheet(activity: Activity, name: String, privateMode: Boolean, done: (String?) -> Unit) {
        val count = TabManager.allTabs().count { it.isPrivate == privateMode && it.groupName == name }
        val actions = listOf(
            SheetAction(R.drawable.ic_menu_copy, "Renombrar grupo") {
                NexoUi.askText(activity, "Renombrar grupo", "Nombre del grupo", name) { value ->
                    val clean = TabGroupRules.name(value) ?: return@askText
                    TabManager.renameGroup(name, clean, privateMode); done(clean)
                }
            },
            SheetAction(R.drawable.ic_tab_tools, "Desagrupar y conservar pestañas") {
                TabManager.renameGroup(name, null, privateMode); done(null)
            },
            SheetAction(R.drawable.ic_menu_close, "Cerrar las $count pestañas", danger = true) {
                android.app.AlertDialog.Builder(activity).setTitle("¿Cerrar el grupo «$name»?")
                    .setMessage("Se cerrarán $count pestañas.")
                    .setNegativeButton("Cancelar", null).setPositiveButton("Cerrar") { _, _ ->
                        TabManager.allTabs().filter { it.isPrivate == privateMode && it.groupName == name }.map { it.id }
                            .forEach { TabManager.closeTab(it) }
                        done(null)
                    }.show()
            }
        )
        lateinit var sheet: Dialog
        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, name, if (count == 1) "1 pestaña" else "$count pestañas"),
            NexoSheets.list(activity, actions) { sheet.dismiss() }))
    }

    private fun segmentButton(activity: Activity, label: String): TextView =
        TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(activity, 8f), 0, dp(activity, 8f), 0)
            isClickable = true
        }

    /** Segmento activo concéntrico: r = 999 dentro de la píldora de cristal. */
    private fun styleSegment(activity: Activity, view: TextView, selected: Boolean) {
        val p = ThemeManager.palette(activity)
        view.background = if (selected) ThemeManager.rounded(activity, if (p.isDark) p.surfaceContainerHighest else p.surface, NexoUi.Tokens.RADIUS_PILL)
            else ThemeManager.ripple(activity, null, NexoUi.Tokens.RADIUS_PILL)
        view.setTextColor(if (selected) p.text else p.muted)
        view.typeface = NexoUi.medium(activity)
        view.isSelected = selected
    }
}

/** Datos de una tarjeta: si no cambian, la tarjeta no se vuelve a dibujar. */
private data class TabCardState(
    val id: String,
    val title: String,
    val url: String,
    val groupName: String?,
    val isPrivate: Boolean,
    val burner: Boolean,
    val pinned: Boolean,
    val active: Boolean,
    val duplicateCount: Int,
    val mode: TabProModel.ViewMode
)

private class TabCardAdapter(
    private val activity: Activity,
    private val onOpen: (BrowserTab) -> Unit,
    private val onClose: (BrowserTab) -> Unit,
    private val onLongPress: ((BrowserTab) -> Unit)?
) : RecyclerView.Adapter<TabCardAdapter.Holder>() {
    var items: List<BrowserTab> = emptyList()
        private set
    private var states: List<TabCardState> = emptyList()
    private val stableIds = StableTabIds()

    init { setHasStableIds(true) }

    private fun dp(value: Float) = (value * activity.resources.displayMetrics.density).toInt()

    fun submit(
        next: List<BrowserTab>,
        activeId: String?,
        mode: TabProModel.ViewMode,
        duplicates: Map<String, Int>
    ) {
        val nextStates = next.map {
            TabCardState(
                id = it.id,
                title = it.title,
                url = it.url,
                groupName = it.groupName,
                isPrivate = it.isPrivate,
                burner = it.burnerId != null,
                pinned = it.pinned,
                active = it.id == activeId,
                duplicateCount = duplicates[it.id] ?: 0,
                mode = mode
            )
        }
        val old = states
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = nextStates.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].id == nextStates[b].id
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == nextStates[b]
        })
        items = next
        stableIds.retain(nextStates.mapTo(HashSet()) { it.id })
        states = nextStates
        diff.dispatchUpdatesTo(this)
    }

    /** Movimiento durante el arrastre (el orden real lo guarda TabManager). */
    fun moveItem(from: Int, to: Int) {
        items = items.toMutableList().apply { add(to, removeAt(from)) }
        states = states.toMutableList().apply { add(to, removeAt(from)) }
        notifyItemMoved(from, to)
    }

    fun previewChanged(id: String) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) notifyItemChanged(index, PREVIEW)
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = stableIds.id(items[position].id)

    class Holder(
        val card: LinearLayout,
        val header: LinearLayout,
        val iconWell: FrameLayout,
        val icon: ImageView,
        val title: TextView,
        val subtitle: TextView,
        val badgeStrip: LinearLayout,
        val close: ImageButton,
        val preview: FrameLayout,
        val previewBadges: LinearLayout,
        val placeholderIcon: ImageView,
        val placeholderLetter: TextView,
        val placeholderHost: TextView,
        val image: TopCropImageView,
        val stage: FrameLayout,
        val headerTop: FrameLayout,
        val headerBottom: FrameLayout,
        val scrim: View,
        val activeChip: TextView
    ) : RecyclerView.ViewHolder(card) {
        var boundId: String? = null
        var shownPreview: Bitmap? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val p = ThemeManager.palette(activity)
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, p.surface, NexoUi.Tokens.RADIUS_CARD)
            clipToOutline = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(2f), dp(4f))
        }
        val iconWell = FrameLayout(activity)
        val icon = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        iconWell.addView(icon, FrameLayout.LayoutParams(dp(16f), dp(16f), Gravity.CENTER))
        header.addView(iconWell, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(8f) })

        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity).apply {
            textSize = 13.5f
            setTextColor(p.text)
            typeface = NexoUi.medium(activity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle = TextView(activity).apply {
            textSize = 12f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            visibility = View.GONE
        }
        titleCol.addView(title)
        titleCol.addView(subtitle)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_close)
            scaleType = ImageView.ScaleType.CENTER
            scaleX = 0.8f
            scaleY = 0.8f
            background = ThemeManager.ripple(activity, null, NexoUi.Tokens.RADIUS_PILL)
            imageTintList = ColorStateList.valueOf(p.muted)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44f), dp(44f)))

        // Ranura superior (Compacta / Lista) e inferior sobre el degradado (Cuadrícula).
        val headerTop = FrameLayout(activity)
        card.addView(headerTop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val badgeStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), dp(10f))
            visibility = View.GONE
        }
        card.addView(badgeStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Captura real a sangre completa (miniatura WebView) dentro de la tarjeta r:22.
        val stage = FrameLayout(activity)
        val preview = FrameLayout(activity).apply { setBackgroundColor(if (p.isDark) p.surfaceContainerHigh else p.surfaceContainer) }
        val placeholderIcon = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val placeholderLetter = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(p.text)
            typeface = NexoUi.semiBold(activity)
        }
        val placeholderHost = TextView(activity).apply {
            textSize = 12f
            typeface = NexoUi.medium(activity)
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(10f), dp(8f), dp(10f), 0)
        }
        preview.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(40f))
            addView(FrameLayout(activity).apply {
                addView(placeholderIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(placeholderLetter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(36f), dp(36f)))
            addView(placeholderHost)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val image = TopCropImageView(activity).apply { visibility = View.GONE }
        preview.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        stage.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Degradado oscuro inferior sutil: el título siempre es legible sobre cualquier captura.
        val scrim = View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x66000000, 0x00000000))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stage.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84f), Gravity.BOTTOM))

        val previewBadges = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(8f), dp(8f), 0)
            visibility = View.GONE
        }
        stage.addView(previewBadges, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        val activeChip = TextView(activity).apply {
            text = "Activa"
            textSize = 11.5f
            typeface = NexoUi.medium(activity)
            setTextColor(0xFFF4F4F5.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(10f), dp(4f))
            background = ThemeManager.rounded(activity, 0x99000000.toInt(), NexoUi.Tokens.RADIUS_PILL)
            compoundDrawablePadding = dp(6f)
            visibility = View.GONE
        }
        stage.addView(activeChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(8f), dp(8f), 0, 0)
        })

        val headerBottom = FrameLayout(activity)
        stage.addView(headerBottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        card.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(212f)))

        return Holder(
            card, header, iconWell, icon, title, subtitle, badgeStrip, close,
            preview, previewBadges, placeholderIcon, placeholderLetter, placeholderHost, image,
            stage, headerTop, headerBottom, scrim, activeChip
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PREVIEW }) {
            val state = states.getOrNull(position) ?: return
            if (state.mode == TabProModel.ViewMode.GRID) bindPreview(holder, items[position])
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tab = items[position]
        val state = states.getOrNull(position)
        val p = ThemeManager.palette(activity)
        val active = state?.active == true
        val mode = state?.mode ?: TabProModel.ViewMode.GRID
        val dupCount = state?.duplicateCount ?: 0
        val changedTab = holder.boundId != tab.id
        holder.boundId = tab.id

        val grid = mode == TabProModel.ViewMode.GRID
        val radius = if (mode == TabProModel.ViewMode.LIST) 18f else NexoUi.Tokens.RADIUS_CARD
        holder.card.background = ThemeManager.rounded(activity, p.surface, radius)
        NexoGlass.paperShadow(holder.card, p)
        // Activa: aro fino del acento (Monocromo = tinta). Sin contornos en el resto.
        val ring = if (p.isMonochrome) p.text else p.accentSwatch
        holder.card.foreground = if (active) ThemeManager.rounded(activity, Color.TRANSPARENT, radius, ring, 2f) else null

        // La cabecera vive sobre el degradado en Cuadrícula y arriba en Compacta/Lista.
        val slot = if (grid) holder.headerBottom else holder.headerTop
        if (holder.header.parent !== slot) {
            (holder.header.parent as? ViewGroup)?.removeView(holder.header)
            slot.addView(holder.header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val onImage = 0xFFF4F4F5.toInt()
        applyStageContrast(holder, grid && holder.image.visibility == View.VISIBLE)
        holder.activeChip.visibility = if (grid && active) View.VISIBLE else View.GONE
        if (active) holder.activeChip.setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (p.isMonochrome) p.success.color else p.accentSwatch)
            setSize(dp(6f), dp(6f))
        }, null, null, null)

        holder.card.setOnClickListener { onOpen(tab) }
        if (onLongPress != null) holder.card.setOnLongClickListener { onLongPress.invoke(tab); true }
        else { holder.card.setOnLongClickListener(null); holder.card.isLongClickable = false }

        holder.card.contentDescription = buildString {
            append(tab.title.ifBlank { "Nueva pestaña" })
            if (tab.pinned) append(" · fijada")
            if (tab.burnerId != null) append(" · burner")
            if (active) append(" · pestaña actual")
            if (dupCount >= 2) append(" · URL duplicada $dupCount veces")
        }

        holder.title.text = tab.title.ifBlank { "Nueva pestaña" }
        holder.title.setCompoundDrawablesRelativeWithIntrinsicBounds(
            tab.groupName?.let { group ->
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(TabGroupRules.color(group))
                    setSize(dp(7f), dp(7f))
                }
            }, null, null, null
        )
        holder.title.compoundDrawablePadding = dp(6f)
        holder.title.typeface = NexoUi.medium(activity)
        holder.close.contentDescription = "Cerrar pestaña: ${tab.title}"
        holder.close.setOnClickListener { onClose(tab) }

        val home = BrowserPrefs.isNexoHome(tab.url)
        val host = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
        val displayHost = if (home) "Inicio" else host.ifBlank { tab.url }
        holder.placeholderHost.text = displayHost

        if (mode == TabProModel.ViewMode.GRID) {
            holder.header.setPadding(dp(12f), 0, dp(0f), 0)
            holder.subtitle.visibility = View.GONE
        } else {
            holder.header.setPadding(dp(14f), dp(8f), dp(4f), dp(8f))
            holder.subtitle.text = if (mode == TabProModel.ViewMode.LIST && !home) {
                tab.url.substringAfter("://").removePrefix("www.")
            } else displayHost
            holder.subtitle.visibility = View.VISIBLE
        }

        // Insignias: sobre la miniatura en Cuadrícula (mantiene alturas 100% uniformes); en banda inferior para Compacta/Lista
        holder.badgeStrip.removeAllViews()
        holder.previewBadges.removeAllViews()
        val targetBadges = if (mode == TabProModel.ViewMode.GRID) holder.previewBadges else holder.badgeStrip
        fun addBadge(label: String, tone: NexoUi.Tone) {
            val pill = NexoUi.statusPill(activity, label, tone)
            targetBadges.addView(pill, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(5f) })
        }
        if (active && !grid) addBadge("Activa", NexoUi.Tone.ACCENT)
        if (tab.isFrozen) addBadge("Suspendida", NexoUi.Tone.NEUTRAL)
        if (tab.pinned) addBadge("Fijada", NexoUi.Tone.INFO)
        if (tab.burnerId != null) addBadge("Burner", NexoUi.Tone.WARNING)
        if (dupCount >= 2) addBadge("×$dupCount", NexoUi.Tone.WARNING)
        if (mode != TabProModel.ViewMode.GRID && !tab.groupName.isNullOrBlank()) {
            addBadge(tab.groupName!!, NexoUi.Tone.NEUTRAL)
        }
        holder.badgeStrip.visibility = if (mode != TabProModel.ViewMode.GRID && holder.badgeStrip.childCount > 0) View.VISIBLE else View.GONE
        holder.previewBadges.visibility = if (mode == TabProModel.ViewMode.GRID && holder.previewBadges.childCount > 0) View.VISIBLE else View.GONE

        if (changedTab) {
            holder.icon.setImageDrawable(null)
            holder.placeholderIcon.setImageDrawable(null)
            holder.placeholderLetter.visibility = View.GONE
            holder.shownPreview = null
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
        }
        val origin = BrowserPagePresentation.shortcut(tab.url)?.origin
        when {
            tab.isPrivate -> {
                holder.iconWell.background = null
                holder.icon.setImageResource(R.drawable.ic_menu_private)
                holder.icon.imageTintList = ColorStateList.valueOf(if (grid) onImage else p.text)
                holder.placeholderIcon.setImageResource(R.drawable.ic_menu_private)
                holder.placeholderIcon.imageTintList = ColorStateList.valueOf(p.muted)
                holder.placeholderIcon.background = null
            }
            home || origin == null -> {
                holder.iconWell.background = null
                holder.icon.setImageResource(R.drawable.ic_nexo_logo)
                holder.icon.imageTintList = ColorStateList.valueOf(if (grid) onImage else p.text)
                holder.placeholderIcon.setImageDrawable(NexoMonogramDrawable(activity, p.text, p.background, 9f))
                holder.placeholderIcon.imageTintList = null
                holder.placeholderIcon.background = null
            }
            else -> {
                holder.iconWell.background = ThemeManager.rounded(activity, 0xFFFFFFFF.toInt(), 4f)
                holder.icon.imageTintList = null
                holder.placeholderIcon.imageTintList = null
                val cachedIcon = SiteIconStore.cached(origin)
                if (cachedIcon != null) {
                    showSiteIcon(holder, cachedIcon)
                } else {
                    holder.iconWell.background = null
                    holder.icon.setImageResource(R.drawable.ic_search)
                    holder.icon.imageTintList = ColorStateList.valueOf(if (grid) onImage else p.muted)
                    holder.placeholderIcon.setImageDrawable(null)
                    holder.placeholderIcon.background = ThemeManager.rounded(activity, p.surfaceContainerHighest, 12f)
                    holder.placeholderLetter.setTextColor(p.text)
                    holder.placeholderLetter.text = SiteIconStore.letter(host)
                    holder.placeholderLetter.visibility = View.VISIBLE
                    SiteIconStore.load(activity, origin) { bitmap ->
                        if (holder.boundId == tab.id) showSiteIcon(holder, bitmap)
                    }
                }
            }
        }

        if (mode == TabProModel.ViewMode.GRID) {
            holder.stage.visibility = View.VISIBLE
            holder.preview.visibility = View.VISIBLE
            bindPreview(holder, tab)
            applyStageContrast(holder, holder.image.visibility == View.VISIBLE)
        } else {
            holder.stage.visibility = View.GONE
            holder.preview.visibility = View.GONE
            holder.shownPreview = null
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
        }
    }

    /**
     * Degradado oscuro y texto claro solo sobre capturas reales; sobre el marcador del tema
     * (p. ej. Paper) la cabecera usa la tinta normal y no se oscurece el fondo.
     */
    private fun applyStageContrast(holder: Holder, overImage: Boolean) {
        val p = ThemeManager.palette(activity)
        val onImage = 0xFFF4F4F5.toInt()
        holder.scrim.visibility = if (overImage) View.VISIBLE else View.GONE
        holder.title.setTextColor(if (overImage) onImage else p.text)
        holder.close.imageTintList = ColorStateList.valueOf(if (overImage) ColorUtils.setAlphaComponent(onImage, 200) else p.muted)
        if (holder.iconWell.background == null) {
            holder.icon.imageTintList?.let { holder.icon.imageTintList = ColorStateList.valueOf(if (overImage) onImage else p.text) }
        }
    }

    private fun showSiteIcon(holder: Holder, bitmap: Bitmap) {
        holder.icon.imageTintList = null
        holder.icon.setImageBitmap(bitmap)
        holder.placeholderLetter.visibility = View.GONE
        holder.placeholderIcon.background = null
        holder.placeholderIcon.setImageBitmap(bitmap)
    }

    private fun bindPreview(holder: Holder, tab: BrowserTab) {
        val hasPreview = !tab.isPrivate && !BrowserPrefs.isNexoHome(tab.url)
        if (!hasPreview) {
            holder.shownPreview = null
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
            return
        }
        fun show(bitmap: Bitmap?) {
            if (bitmap == null || bitmap.isRecycled) {
                holder.shownPreview = null
                holder.image.setImageDrawable(null)
                holder.image.visibility = View.GONE
                applyStageContrast(holder, false)
                return
            }
            if (holder.shownPreview === bitmap && holder.image.visibility == View.VISIBLE) return
            holder.shownPreview = bitmap
            holder.image.setBackgroundColor(ThemeManager.palette(activity).surfaceContainer)
            holder.image.setImageBitmap(bitmap)
            holder.image.visibility = View.VISIBLE
            applyStageContrast(holder, true)
        }
        val cached = TabPreviewStore.cached(tab.id)
        if (cached != null) { show(cached); return }
        if (holder.shownPreview == null) holder.image.visibility = View.GONE
        TabPreviewStore.loadAsync(activity, tab.id) { bitmap ->
            if (holder.boundId == tab.id) show(bitmap)
        }
    }

    companion object {
        private const val PREVIEW = "preview"
    }
}

/**
 * Muestra la parte superior de la miniatura cubriendo toda la tarjeta (tanto capturas verticales
 * como horizontales de video), centrada en el eje X y anclada arriba.
 * La app usa Activity del framework (no AppCompat), así que extender
 * ImageView directamente es lo correcto.
 */
@android.annotation.SuppressLint("AppCompatCustomView")
private class TopCropImageView(context: Context) : ImageView(context) {
    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        updateMatrix()
    }

    private fun updateMatrix() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val scale = maxOf(
            width.toFloat() / d.intrinsicWidth.toFloat(),
            height.toFloat() / d.intrinsicHeight.toFloat()
        )
        val dx = (width - d.intrinsicWidth * scale) / 2f
        imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, 0f)
        }
    }
}
