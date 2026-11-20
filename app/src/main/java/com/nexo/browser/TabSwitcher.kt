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

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.background)
        }

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

        // Selector segmentado centrado dentro de la propia barra superior
        val segContainerBg = if (p.isDark) p.surfaceContainer else p.surfaceContainerHigh
        val segStroke = if (p.isAmoled) ThemeManager.hairline(p) else null
        val segments = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeManager.rounded(activity, segContainerBg, 22f, segStroke)
            setPadding(dp(activity, 3f), dp(activity, 3f), dp(activity, 3f), dp(activity, 3f))
        }
        val normalSeg = segmentButton(activity, "Pestañas")
        val privateSeg = segmentButton(activity, "Privadas")
        segments.addView(normalSeg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        segments.addView(privateSeg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val viewModeBtn = ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(activity, 48f), dp(activity, 48f))
            ThemeManager.styleIconButton(activity, this)
        }

        topBar.addView(back)
        topBar.addView(segments, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(activity, 4f)
            marginEnd = dp(activity, 4f)
        })
        topBar.addView(viewModeBtn)
        root.addView(topBar)

        // ==================== BUSCADOR + RAIL DE GRUPOS ====================
        val (searchBox, searchInput) = NexoUi.searchBar(activity, "Buscar pestañas por título, sitio o grupo") { q ->
            searchQuery = q
            refresh()
        }
        root.addView(searchBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(dp(activity, 16f), dp(activity, 2f), dp(activity, 16f), dp(activity, 4f))
        })

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
            setPadding(dp(activity, 11f), dp(activity, 4f), dp(activity, 11f), dp(activity, 16f))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
            val gap = dp(activity, 5f)
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
                TabProModel.ViewMode.GRID -> R.drawable.ic_view_grid to "Vista cuadrícula. Pulsa para cambiar de vista"
                TabProModel.ViewMode.COMPACT -> R.drawable.ic_view_compact to "Vista compacta. Pulsa para cambiar de vista"
                TabProModel.ViewMode.LIST -> R.drawable.ic_view_list to "Vista lista. Pulsa para cambiar de vista"
            }
            viewModeBtn.setImageResource(iconRes)
            viewModeBtn.contentDescription = desc
        }

        viewModeBtn.setOnClickListener {
            val allModes = TabProModel.ViewMode.entries
            val next = allModes[(allModes.indexOf(viewMode) + 1) % allModes.size]
            applyViewMode(next)
            Toastish.show(activity, "Vista: ${next.label}")
        }
        viewModeBtn.setOnLongClickListener {
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
                }
            )
            picker = NexoSheets.show(
                activity,
                NexoSheets.column(
                    activity,
                    NexoSheets.header(activity, "Vista de pestañas", "Elige la densidad que prefieras"),
                    NexoSheets.list(activity, actions) { picker.dismiss() }
                )
            )
            true
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
            setTextColor(p.accent)
            typeface = NexoUi.bold(activity)
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
            background = ThemeManager.rounded(activity, p.surfaceContainerHighest, 16f, ThemeManager.hairline(p))
            elevation = dp(activity, 4f).toFloat()
            visibility = View.GONE
            addView(undoText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(undoButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(undoBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(activity, 16f), dp(activity, 4f), dp(activity, 16f), dp(activity, 6f))
        })

        // ==================== BARRA INFERIOR PARA UNA SOLA MANO ====================
        root.addView(NexoUi.divider(activity, 0f))
        val bottomDock = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (p.isAmoled) p.background else p.surfaceContainerLow)
            setPadding(dp(activity, 14f), dp(activity, 10f), dp(activity, 14f), dp(activity, 12f))
        }
        val closeAll = Button(activity).apply {
            text = "Cerrar todas"
            contentDescription = "Cerrar todas las pestañas visibles"
            textSize = 13.5f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            ThemeManager.styleButton(activity, this, danger = false)
            setTextColor(p.danger)
            setPadding(dp(activity, 16f), dp(activity, 10f), dp(activity, 16f), dp(activity, 10f))
        }
        val newTabBtn = Button(activity).apply {
            text = "+ Nueva pestaña"
            contentDescription = "Abrir una nueva pestaña"
            textSize = 14f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            ThemeManager.styleButton(activity, this, primary = true)
            setPadding(dp(activity, 18f), dp(activity, 10f), dp(activity, 18f), dp(activity, 10f))
            setOnClickListener {
                dialog.dismiss()
                onNewTab(showPrivate)
                TabManager.activeTab()?.let { TabManager.moveToGroup(it.id, selectedGroup) }
            }
        }
        bottomDock.addView(closeAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomDock.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        bottomDock.addView(newTabBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(activity, 10f)
        })
        root.addView(bottomDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
            chips += NexoUi.chip(activity, "+ Grupo", false) {
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
            newTabBtn.text = if (showPrivate) "+ Privada" else "+ Nueva pestaña"

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

            normalSeg.text = "Pestañas · $normalCount"
            privateSeg.text = "Privadas · $privateCount"
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

        dialog.setContentView(root)
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        dialog.show()
        ViewCompat.requestApplyInsets(root)
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
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(activity, 40f)
            setPadding(dp(activity, 8f), dp(activity, 7f), dp(activity, 8f), dp(activity, 7f))
            isClickable = true
        }

    private fun styleSegment(activity: Activity, view: TextView, selected: Boolean) {
        val p = ThemeManager.palette(activity)
        val activeBg = if (p.isDark) p.surfaceContainerHighest else p.surfaceContainerLowest
        view.background = if (selected) ThemeManager.rounded(activity, activeBg, 19f) else null
        view.setTextColor(if (selected) p.text else p.muted)
        view.typeface = if (selected) NexoUi.semiBold(activity) else NexoUi.medium(activity)
        view.elevation = if (selected && !p.isDark) dp(activity, 1f).toFloat() else 0f
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
        val image: TopCropImageView
    ) : RecyclerView.ViewHolder(card) {
        var boundId: String? = null
        var shownPreview: Bitmap? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val p = ThemeManager.palette(activity)
        val cardBg = if (p.isDark) p.surfaceContainer else p.surfaceContainerLowest
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, cardBg, 20f)
            clipToOutline = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), dp(4f), dp(2f), dp(4f))
        }
        val iconWell = FrameLayout(activity).apply {
            background = ThemeManager.rounded(activity, p.surfaceContainerHigh, 10f)
        }
        val icon = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        iconWell.addView(icon, FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER))
        header.addView(iconWell, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginEnd = dp(8f) })

        val titleCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(activity).apply {
            textSize = 13.5f
            setTextColor(p.text)
            typeface = NexoUi.semiBold(activity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle = TextView(activity).apply {
            textSize = 11.5f
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
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(13f), dp(13f), dp(13f), dp(13f))
            ThemeManager.styleIconButton(activity, this)
            imageTintList = ColorStateList.valueOf(p.muted)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        card.addView(header)

        // Banda de insignias (solo para modos Compacta y Lista; en Cuadrícula van sobre el preview para alturas uniformes)
        val badgeStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10f), 0, dp(10f), dp(10f))
            visibility = View.GONE
        }
        card.addView(badgeStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Marco de vista previa neutro y elegante (misma altura en todas las tarjetas de cuadrícula)
        val previewSurface = if (p.isDark) p.surfaceContainerLow else p.surfaceContainer
        val preview = FrameLayout(activity).apply {
            background = ThemeManager.rounded(activity, previewSurface, 14f)
            clipToOutline = true
        }
        val placeholderIcon = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val placeholderLetter = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(p.text)
            typeface = NexoUi.bold(activity)
        }
        val placeholderHost = TextView(activity).apply {
            textSize = 11.5f
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
            addView(FrameLayout(activity).apply {
                addView(placeholderIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(placeholderLetter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, LinearLayout.LayoutParams(dp(42f), dp(42f)))
            addView(placeholderHost)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val image = TopCropImageView(activity).apply { visibility = View.GONE }
        preview.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Insignias flotantes limpias en la esquina inferior izquierda de la miniatura
        val previewBadges = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(8f))
            visibility = View.GONE
        }
        preview.addView(previewBadges, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ))

        card.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(164f)).apply {
            setMargins(dp(6f), 0, dp(6f), dp(6f))
        })

        return Holder(
            card, header, iconWell, icon, title, subtitle, badgeStrip, close,
            preview, previewBadges, placeholderIcon, placeholderLetter, placeholderHost, image
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

        val radius = if (mode == TabProModel.ViewMode.LIST) 16f else 20f
        val cardBg = if (p.isDark) p.surfaceContainer else p.surfaceContainerLowest
        holder.card.background = ThemeManager.rounded(activity, cardBg, radius)
        val strokeColor = when {
            active -> p.accent
            tab.groupName != null -> ColorUtils.setAlphaComponent(TabGroupRules.color(tab.groupName!!), 150)
            p.isAmoled || !p.isDark -> ThemeManager.hairline(p)
            else -> Color.TRANSPARENT
        }
        holder.card.foreground = if (strokeColor != Color.TRANSPARENT) {
            ThemeManager.rounded(activity, Color.TRANSPARENT, radius, strokeColor, if (active) 2f else 1f)
        } else null

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
        holder.close.contentDescription = "Cerrar pestaña: ${tab.title}"
        holder.close.setOnClickListener { onClose(tab) }

        val home = BrowserPrefs.isNexoHome(tab.url)
        val host = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
        val displayHost = if (home) "Inicio" else host.ifBlank { tab.url }
        holder.placeholderHost.text = displayHost

        if (mode == TabProModel.ViewMode.GRID) {
            holder.header.setPadding(dp(10f), dp(4f), dp(2f), dp(4f))
            holder.subtitle.visibility = View.GONE
        } else {
            holder.header.setPadding(dp(12f), dp(8f), dp(4f), dp(8f))
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
        if (active) addBadge("Activa", NexoUi.Tone.ACCENT)
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
        }
        val origin = BrowserPagePresentation.shortcut(tab.url)?.origin
        when {
            tab.isPrivate -> {
                holder.iconWell.background = ThemeManager.rounded(activity, p.primary.container, 10f)
                holder.icon.setImageResource(R.drawable.ic_menu_private)
                holder.icon.imageTintList = ColorStateList.valueOf(p.primary.onContainer)
                holder.placeholderIcon.setImageResource(R.drawable.ic_menu_private)
                holder.placeholderIcon.imageTintList = ColorStateList.valueOf(p.accent)
                holder.placeholderIcon.background = null
            }
            home || origin == null -> {
                holder.iconWell.background = ThemeManager.rounded(activity, p.surfaceContainerHigh, 10f)
                holder.icon.setImageResource(R.drawable.ic_nexo_logo)
                holder.icon.imageTintList = null
                holder.placeholderIcon.setImageResource(R.drawable.ic_nexo_logo)
                holder.placeholderIcon.imageTintList = null
                holder.placeholderIcon.background = null
            }
            else -> {
                holder.iconWell.background = ThemeManager.rounded(activity, p.surfaceContainerHigh, 10f)
                holder.icon.imageTintList = null
                holder.placeholderIcon.imageTintList = null
                val cachedIcon = SiteIconStore.cached(origin)
                if (cachedIcon != null) {
                    showSiteIcon(holder, cachedIcon)
                } else {
                    holder.icon.setImageResource(R.drawable.ic_search)
                    holder.icon.imageTintList = ColorStateList.valueOf(p.muted)
                    holder.placeholderIcon.setImageDrawable(null)
                    holder.placeholderIcon.background = ThemeManager.rounded(activity, SiteIconStore.letterColor(host), 14f)
                    holder.placeholderLetter.setTextColor(UiContrast.foreground(SiteIconStore.letterColor(host)))
                    holder.placeholderLetter.text = SiteIconStore.letter(host)
                    holder.placeholderLetter.visibility = View.VISIBLE
                    SiteIconStore.load(activity, origin) { bitmap ->
                        if (holder.boundId == tab.id) showSiteIcon(holder, bitmap)
                    }
                }
            }
        }

        if (mode == TabProModel.ViewMode.GRID) {
            holder.preview.visibility = View.VISIBLE
            bindPreview(holder, tab)
        } else {
            holder.preview.visibility = View.GONE
            holder.shownPreview = null
            holder.image.setImageDrawable(null)
            holder.image.visibility = View.GONE
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
            if (bitmap == null) {
                holder.shownPreview = null
                holder.image.setImageDrawable(null)
                holder.image.visibility = View.GONE
                return
            }
            if (holder.shownPreview === bitmap && holder.image.visibility == View.VISIBLE) return
            holder.shownPreview = bitmap
            holder.image.setBackgroundColor(ThemeManager.palette(activity).surfaceContainer)
            holder.image.setImageBitmap(bitmap)
            holder.image.visibility = View.VISIBLE
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
 * Muestra la parte superior de la miniatura (como un navegador real).
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
        if (width == 0 || d.intrinsicWidth <= 0) return
        val scale = width.toFloat() / d.intrinsicWidth.toFloat()
        imageMatrix = Matrix().apply { setScale(scale, scale) }
    }
}
