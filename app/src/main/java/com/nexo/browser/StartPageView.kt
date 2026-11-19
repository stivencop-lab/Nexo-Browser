package com.nexo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nexo.browser.reader.NexoReader
import com.nexo.browser.reader.OfflineArticleStore
import com.nexo.browser.workspace.BookmarkStore
import com.nexo.browser.workspace.WorkspaceStore
import org.json.JSONArray
import org.json.JSONObject

/** Native rendering of Nexo Start Page with customizable Nexo Workspace blocks; never fabricates protection counters. */
@SuppressLint("ViewConstructor")
class StartPageView(
    private val activity: Activity,
    private val onOpen: (String) -> Unit,
    private val onPrivate: () -> Unit,
    private val onShield: () -> Unit,
    private val onExtensions: () -> Unit,
    /** Accesos de Nexo: "downloads", "history", "burner", "settings", "bookmarks", "workspaces", "reader_offline". */
    private val onAction: (String) -> Unit = {}
) : ScrollView(activity) {
    /** Configuración modular de bloques visibles en la página de inicio. */
    data class StartPageConfig(
        val showQuickAccess: Boolean = true,
        val showBookmarks: Boolean = true,
        val showWorkspaces: Boolean = true,
        val showRecentGroups: Boolean = true,
        val showOfflineReader: Boolean = true,
        val showPrivacyCard: Boolean = true
    )

    companion object {
        private const val PREFS = "nexo_quick_access"
        private const val MAX_TILES = 11

        fun readConfig(context: Context): StartPageConfig {
            val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return StartPageConfig(
                showQuickAccess = sp.getBoolean("cfg_quick_access", true),
                showBookmarks = sp.getBoolean("cfg_bookmarks", true),
                showWorkspaces = sp.getBoolean("cfg_workspaces", true),
                showRecentGroups = sp.getBoolean("cfg_recent_groups", true),
                showOfflineReader = sp.getBoolean("cfg_offline_reader", true),
                showPrivacyCard = sp.getBoolean("cfg_privacy_card", true)
            )
        }

        fun saveConfig(context: Context, config: StartPageConfig) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("cfg_quick_access", config.showQuickAccess)
                .putBoolean("cfg_bookmarks", config.showBookmarks)
                .putBoolean("cfg_workspaces", config.showWorkspaces)
                .putBoolean("cfg_recent_groups", config.showRecentGroups)
                .putBoolean("cfg_offline_reader", config.showOfflineReader)
                .putBoolean("cfg_privacy_card", config.showPrivacyCard)
                .apply()
        }

        /**
         * Reordena un acceso web desplazándolo [delta] posiciones (-1 antes, +1 después).
         * Si el acceso no estaba todavía en la lista personalizada, incorpora el orden visible
         * actual de accesos web para que el desplazamiento sea determinista.
         */
        fun reorderShortcut(
            currentCustom: List<String>,
            visibleWebOrigins: List<String>,
            targetOrigin: String,
            delta: Int
        ): List<String> {
            val base = LinkedHashSet<String>()
            currentCustom.forEach { base.add(it) }
            visibleWebOrigins.forEach { base.add(it) }
            val list = base.toMutableList()
            val idx = list.indexOf(targetOrigin)
            if (idx < 0) return currentCustom
            val targetIdx = (idx + delta).coerceIn(0, list.lastIndex)
            if (targetIdx == idx) return list.take(24)
            list.removeAt(idx)
            list.add(targetIdx, targetOrigin)
            return list.take(24)
        }

        /** Fija el origen de [url] en los accesos rápidos. Devuelve su nombre o null si ya estaba. */
        fun pin(context: Context, url: String): String? {
            val shortcut = BrowserPagePresentation.shortcut(url) ?: return null
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = runCatching { JSONArray(prefs.getString("custom", "[]")) }.getOrDefault(JSONArray())
            for (index in 0 until saved.length()) {
                val known = BrowserPagePresentation.shortcut(saved.optJSONObject(index)?.optString("url").orEmpty())
                if (known?.identity == shortcut.identity) {
                    // Ya estaba: si se había ocultado, volver a mostrarlo.
                    val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
                    if (shortcut.origin !in hidden) return null
                    prefs.edit().putStringSet("hidden", hidden - shortcut.origin).apply()
                    return shortcut.title
                }
            }
            // Los fijados van delante; como mucho 24.
            val updated = JSONArray().put(JSONObject().put("url", shortcut.origin))
            for (index in 0 until minOf(saved.length(), 23)) updated.put(saved.get(index))
            val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
            prefs.edit().putString("custom", updated.toString()).putStringSet("hidden", hidden - shortcut.origin).apply()
            return shortcut.title
        }

        /** Devuelve los accesos web guardados visibles para el buscador universal Nexo Command. */
        fun savedShortcuts(context: Context): List<NexoCommandEngine.ShortcutCandidate> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
            val out = ArrayList<NexoCommandEngine.ShortcutCandidate>()
            val seen = HashSet<String>()
            val saved = runCatching { JSONArray(prefs.getString("custom", "[]")) }.getOrDefault(JSONArray())
            for (index in 0 until saved.length()) {
                val shortcut = BrowserPagePresentation.shortcut(saved.optJSONObject(index)?.optString("url").orEmpty())
                if (shortcut != null && shortcut.origin !in hidden && seen.add(shortcut.identity)) {
                    out.add(NexoCommandEngine.ShortcutCandidate(shortcut.title, shortcut.origin))
                }
            }
            listOf(
                "YouTube" to "https://m.youtube.com/",
                "Google" to "https://www.google.com/",
                "Wikipedia" to "https://es.wikipedia.org/",
                "GitHub" to "https://github.com/",
                "Gmail" to "https://mail.google.com/",
                "Maps" to "https://maps.google.com/"
            ).forEach { (title, url) ->
                val identity = BrowserPagePresentation.shortcut(url)?.identity ?: url
                if (url !in hidden && seen.add(identity)) {
                    out.add(NexoCommandEngine.ShortcutCandidate(title, url))
                }
            }
            return out
        }
    }

    private data class Link(val label: String, val url: String = "", val kind: String = "web") {
        val id get() = if (kind == "web") url else kind
    }
    private val content = ContentColumn(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.START }
    private val prefs get() = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var renderedPrivate = false
    private var renderedShieldToken: String? = null
    private val delayedShieldRefresh = Runnable { refreshShieldIfChanged() }

    private fun shieldToken() =
        "${ExtensionManager.phantomEntry()?.enabled}|${ExtensionManager.phantomBadge(global = true)}"

    private fun refreshShieldIfChanged() {
        if (isShown && hasWindowFocus() && renderedShieldToken != shieldToken()) render(renderedPrivate)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        removeCallbacks(delayedShieldRefresh)
        if (hasWindowFocus) {
            refreshShieldIfChanged()
            // One recheck lets a batched extension badge arrive after closing its panel.
            postDelayed(delayedShieldRefresh, 1200L)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) post { if (isAttachedToWindow) render(renderedPrivate) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(delayedShieldRefresh)
        super.onDetachedFromWindow()
    }
    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun params(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(width, height)
    private fun text(label: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = label; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(resources.getFont(R.font.inter_variable), Typeface.BOLD)
    }
    private fun bento() = ThemeManager.palette(activity).let { p -> GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.surface, p.surface)
    ).apply { cornerRadius = dp(18f).toFloat(); setStroke(dp(1f), p.outline) } }
    init { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL)) }

    fun render(isPrivate: Boolean) {
        renderedPrivate = isPrivate
        renderedShieldToken = shieldToken()
        val p = ThemeManager.palette(activity)
        val cfg = readConfig(activity)
        setBackgroundColor(p.background); content.removeAllViews(); content.setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val badge = ExtensionManager.phantomBadge(global = true)
        val available = ExtensionManager.phantomEntry()?.enabled == true && badge != "OFF" && badge != "PAUSE"
        val shieldColor = if (available) p.success.color else p.muted
        val shieldContainer = if (available) p.success.container else p.elevated
        top.addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48f); setPadding(dp(10f), 0, dp(10f), 0)
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.surface, 22f, p.outline), 22f)
            isClickable = true; isFocusable = true; contentDescription = "Abrir panel de PhantomShield"
            setOnClickListener { onShield() }
            addView(View(activity).apply { background = ThemeManager.rounded(activity, shieldColor, 4f) }, params(dp(6f), dp(6f)).apply { marginEnd = dp(8f) })
            addView(text("PhantomShield", 11f, p.text, true))
            addView(text(when (badge) { "OFF" -> "Pausado"; "PAUSE" -> "Pausado"; null -> "Panel"; else -> "$badge total" }, 10.5f, shieldColor, true).apply {
                typeface = resources.getFont(R.font.jetbrains_mono)
                setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
                background = ThemeManager.rounded(activity, shieldContainer, 12f)
            }, params().apply { width = ViewGroup.LayoutParams.WRAP_CONTENT; marginStart = dp(8f) })
        })
        top.addView(View(activity), params(0, dp(1f)).apply { weight = 1f })
        if (!isPrivate) {
            top.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_menu_settings); imageTintList = ColorStateList.valueOf(p.muted)
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f)); background = ThemeManager.ripple(activity, bento(), 22f)
                contentDescription = "Personalizar página de inicio"; isClickable = true; isFocusable = true
                setOnClickListener { openCustomizer() }
            }, params(dp(48f), dp(48f)).apply { marginEnd = dp(8f) })
        }
        top.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_menu_private); imageTintList = ColorStateList.valueOf(p.muted)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f)); background = ThemeManager.ripple(activity, bento(), 22f)
            contentDescription = "Abrir pestaña privada"; isClickable = true; isFocusable = true
            setOnClickListener { onPrivate() }
        }, params(dp(48f), dp(48f)))
        content.addView(top, params())

        content.addView(ImageView(activity).apply {
            setImageResource(if (isPrivate) R.drawable.ic_menu_private else R.drawable.ic_nexo_logo)
            val inset = if (isPrivate) dp(16f) else 0
            setPadding(inset, inset, inset, inset)
            imageTintList = if (isPrivate) ColorStateList.valueOf(p.onAccentSoft) else null
            background = if (isPrivate) ThemeManager.rounded(activity, p.accentSoft, 16f, p.outlineVariant) else null

        }, params(dp(64f), dp(64f)).apply { topMargin = dp(28f); bottomMargin = dp(16f) })
        content.addView(text(if (isPrivate) "Navegación privada" else "Nexo", 34f, p.text, true).apply { gravity = Gravity.START })
        content.addView(text(if (isPrivate) "Un espacio separado para navegar" else "Navegación rápida, privacidad bajo tu control", 12f, p.muted).apply {
            gravity = Gravity.START; setPadding(0, dp(6f), 0, dp(20f))
        }, params())
        if (isPrivate) {
            content.addView(text("Estas pestañas no se guardan en el historial ni en las miniaturas. Sus cookies se eliminan al cerrar todas las pestañas privadas. Los archivos que descargues permanecen guardados. Tu proveedor de internet y los sitios que visitas pueden seguir viendo tu actividad.", 14f, p.muted).apply {
                setLineSpacing(0f, 1.2f); setPadding(dp(4f), dp(16f), dp(4f), dp(16f))
            }, params())
        } else {
            val accentLink = if (p.isDark) androidx.core.graphics.ColorUtils.blendARGB(p.accent, p.text, 0.5f) else p.accent

            if (cfg.showQuickAccess) {
                val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                heading.addView(text("Accesos rápidos", 13f, p.muted, true), params(0).apply { weight = 1f })
                heading.addView(text("Editar", 12f, accentLink, true).apply {
                    minimumHeight = dp(48f); gravity = Gravity.CENTER; setPadding(dp(12f), 0, 0, 0)
                    isClickable = true; isFocusable = true; setOnClickListener { editLinks() }
                })
                content.addView(heading, params())
                val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
                val links = allLinks().filterNot { it.id in hidden }.take(MAX_TILES) + Link("Añadir", kind = "add")
                val availableWidth = (width / resources.displayMetrics.density).takeIf { it > 0 }
                    ?: resources.configuration.screenWidthDp.toFloat()
                val columns = UiLayout.actionColumns(minOf(availableWidth, UiLayout.CONTENT_MAX_DP.toFloat()) - 40f, resources.configuration.fontScale)
                links.chunked(columns).forEach { group ->
                    val row = LinearLayout(activity).apply { weightSum = columns.toFloat() }
                    group.forEach { row.addView(tile(it), params(0).apply { weight = 1f }) }
                    content.addView(row, params())
                }
            }

            // Franja compacta de acceso a Nexo Workspace (Favoritos, Espacios, Lectura sin conexión).
            val bookmarks = if (cfg.showBookmarks) BookmarkStore.list(activity) else emptyList()
            val workspaces = if (cfg.showWorkspaces) WorkspaceStore.list(activity) else emptyList()
            val offlineArticles = if (cfg.showOfflineReader) {
                OfflineArticleStore.list(OfflineArticleStore.directory(activity))
            } else emptyList()

            if (cfg.showBookmarks || cfg.showWorkspaces || cfg.showOfflineReader) {
                val quickChips = ArrayList<View>()
                if (cfg.showBookmarks) {
                    val label = if (bookmarks.isEmpty()) "Favoritos" else "Favoritos (${bookmarks.size})"
                    quickChips.add(NexoUi.chip(activity, label, selected = false) { onAction("bookmarks") })
                }
                if (cfg.showWorkspaces) {
                    val label = if (workspaces.isEmpty()) "Espacios" else "Espacios (${workspaces.size})"
                    quickChips.add(NexoUi.chip(activity, label, selected = false) { onAction("workspaces") })
                }
                if (cfg.showOfflineReader) {
                    val label = if (offlineArticles.isEmpty()) "Sin conexión" else "Sin conexión (${offlineArticles.size})"
                    quickChips.add(NexoUi.chip(activity, label, selected = false) { onAction("reader_offline") })
                }
                if (quickChips.isNotEmpty()) {
                    content.addView(NexoUi.chipRow(activity, quickChips), params().apply { topMargin = dp(10f) })
                }
            }

            if (cfg.showBookmarks && bookmarks.isNotEmpty()) {
                content.addView(sectionHeading("Favoritos", "Ver todos", accentLink) { onAction("bookmarks") }, params().apply { topMargin = dp(14f) })
                content.addView(horizontalCards(bookmarks.take(8).map { bm ->
                    val host = runCatching { android.net.Uri.parse(bm.url).host }.getOrNull().orEmpty().removePrefix("www.")
                    compactCard(bm.title, "${bm.folder} · ${host.ifBlank { bm.url }}", R.drawable.ic_menu_bookmark) {
                        onOpen(bm.url)
                    }
                }), params())
            }

            if (cfg.showWorkspaces && workspaces.isNotEmpty()) {
                content.addView(sectionHeading("Espacios de trabajo", "Gestionar", accentLink) { onAction("workspaces") }, params().apply { topMargin = dp(14f) })
                content.addView(horizontalCards(workspaces.take(8).map { ws ->
                    compactCard(ws.name, ws.groupSummary(), R.drawable.ic_menu_workspace) {
                        val count = WorkspaceStore.restoreLazily(ws)
                        Toastish.show(activity, "Espacio «${ws.name}» abierto ($count pestañas).")
                    }
                }), params())
            }

            if (cfg.showRecentGroups) {
                val groups = TabManager.allTabs()
                    .filter { !it.isPrivate && !it.groupName.isNullOrBlank() }
                    .groupBy { it.groupName!!.trim() }
                    .entries.toList()
                if (groups.isNotEmpty()) {
                    content.addView(sectionHeading("Grupos activos", null, accentLink) {}, params().apply { topMargin = dp(14f) })
                    val chips = groups.take(10).map { (name, tabs) ->
                        NexoUi.chip(activity, "$name (${tabs.size})", selected = false, dotColor = TabGroupRules.color(name)) {
                            TabManager.switchTo(tabs.first().id)
                        }
                    }
                    content.addView(NexoUi.chipRow(activity, chips), params())
                }
            }

            if (cfg.showOfflineReader && offlineArticles.isNotEmpty()) {
                content.addView(sectionHeading("Lectura sin conexión", "Ver todos", accentLink) { onAction("reader_offline") }, params().apply { topMargin = dp(14f) })
                content.addView(horizontalCards(offlineArticles.take(6).map { art ->
                    val sub = buildString {
                        if (art.siteName.isNotBlank()) append(art.siteName).append(" · ")
                        append("${art.readingMinutes} min")
                    }
                    compactCard(art.title, sub, R.drawable.ic_menu_reader) {
                        NexoReader.showArticleDialog(activity, art, openedOffline = true, onOpenUrl = onOpen)
                    }
                }), params())
            }
        }
        content.addView(View(activity), params(1, 0).apply { weight = 1f })
        if (isPrivate || cfg.showPrivacyCard) {
            content.addView(privacyCard(), params().apply { topMargin = dp(24f) })
        }
    }

    private fun sectionHeading(title: String, actionLabel: String?, accentColor: Int, onActionClick: () -> Unit): LinearLayout {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 13f, p.muted, true), params(0).apply { weight = 1f })
            if (!actionLabel.isNullOrBlank()) {
                addView(text(actionLabel, 12f, accentColor, true).apply {
                    minimumHeight = dp(48f); gravity = Gravity.CENTER; setPadding(dp(12f), 0, 0, 0)
                    isClickable = true; isFocusable = true; setOnClickListener { onActionClick() }
                })
            }
        }
    }

    private fun horizontalCards(cards: List<View>): HorizontalScrollView {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        cards.forEachIndexed { idx, card ->
            row.addView(card, LinearLayout.LayoutParams(dp(188f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (idx > 0) marginStart = dp(10f)
            })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    private fun compactCard(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(74f)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.surface, 16f, p.outline), 16f)
            isClickable = true; isFocusable = true
            contentDescription = "$title. $subtitle"
            setOnClickListener { onClick() }
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(p.accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, params(dp(16f), dp(16f)).apply { marginEnd = dp(8f) })
                addView(text(title, 13f, p.text, true).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, params(0).apply { weight = 1f })
            })
            addView(text(subtitle, 11.5f, p.muted).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6f), 0, 0)
            })
        }
    }

    private fun openCustomizer() {
        var cfg = readConfig(activity)
        val body = NexoUi.column(activity)
        fun update(next: StartPageConfig) {
            cfg = next
            saveConfig(activity, next)
            render(renderedPrivate)
        }
        body.addView(NexoUi.switchRow(activity, "Accesos rápidos", "Cuadrícula de sitios fijados y frecuentes", cfg.showQuickAccess) {
            update(cfg.copy(showQuickAccess = it))
        })
        body.addView(NexoUi.switchRow(activity, "Favoritos", "Franja de sitios guardados en Nexo Workspace", cfg.showBookmarks) {
            update(cfg.copy(showBookmarks = it))
        })
        body.addView(NexoUi.switchRow(activity, "Espacios de trabajo", "Colecciones de pestañas y grupos con carga perezosa", cfg.showWorkspaces) {
            update(cfg.copy(showWorkspaces = it))
        })
        body.addView(NexoUi.switchRow(activity, "Grupos activos", "Acceso directo a los grupos de pestañas abiertos", cfg.showRecentGroups) {
            update(cfg.copy(showRecentGroups = it))
        })
        body.addView(NexoUi.switchRow(activity, "Lectura sin conexión", "Artículos guardados con Nexo Reader", cfg.showOfflineReader) {
            update(cfg.copy(showOfflineReader = it))
        })
        body.addView(NexoUi.switchRow(activity, "Resumen de PhantomShield", "Tarjeta inferior con estado real de protección", cfg.showPrivacyCard) {
            update(cfg.copy(showPrivacyCard = it))
        })
        var sheet: android.app.Dialog? = null
        body.addView(NexoUi.button(activity, "Elegir accesos rápidos visibles") {
            sheet?.dismiss()
            editLinks()
        }, NexoUi.buttonParams(activity, 14f))
        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Personalizar inicio", "Activa o desactiva los bloques de Nexo Workspace"),
                body
            )
        )
    }

    private fun tile(link: Link): View {
        val p = ThemeManager.palette(activity)
        val host = BrowserPagePresentation.shortcut(link.url)?.identity.orEmpty()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4f), 0, dp(12f))
            background = ThemeManager.ripple(activity, null, 18f); isClickable = true; isFocusable = true; contentDescription = link.label
            setOnClickListener {
                when (link.kind) {
                    "shield" -> onShield(); "extensions" -> onExtensions(); "add" -> addLink()
                    "downloads", "history", "burner", "settings" -> onAction(link.kind)
                    else -> onOpen(link.url)
                }
            }
            if (link.kind == "web") setOnLongClickListener { tileOptions(link); true }
            val square = FrameLayout(activity).apply { background = bento() }
            val iconRes = when {
                link.kind == "shield" -> R.drawable.ic_quick_shield
                link.kind == "extensions" -> R.drawable.ic_extension_placeholder
                link.kind == "downloads" -> R.drawable.ic_menu_download
                link.kind == "history" -> R.drawable.ic_menu_history
                link.kind == "burner" -> R.drawable.ic_menu_burner
                link.kind == "add" -> R.drawable.ic_nav_add
                host.startsWith("youtube.com:") -> R.drawable.ic_quick_youtube
                host.startsWith("google.com:") -> R.drawable.ic_quick_google
                host.startsWith("github.com:") -> R.drawable.ic_quick_github
                else -> null
            }
            // Color propio de cada acceso de Nexo (los logos de sitios traen el suyo).
            val tint = when (link.kind) {
                "extensions" -> p.info.color
                "downloads" -> p.accent
                "history" -> p.tertiary.color
                "burner" -> p.warning.color
                "add" -> p.muted
                else -> null
            }
            if (iconRes != null) {
                square.addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    if (tint != null) imageTintList = ColorStateList.valueOf(tint)
                }, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
            } else {
                // Inicial sobre el color del sitio mientras llega su logo (o si no tiene).
                val siteHost = runCatching { android.net.Uri.parse(link.url).host }.getOrNull().orEmpty()
                square.addView(text(if (host.contains("wikipedia.org")) "W" else SiteIconStore.letter(siteHost.ifBlank { link.label }), 20f, UiContrast.foreground(SiteIconStore.letterColor(siteHost.ifBlank { link.label })), true).apply {
                    gravity = Gravity.CENTER
                    if (host.contains("wikipedia.org")) typeface = Typeface.create("serif", Typeface.BOLD)
                    background = ThemeManager.rounded(activity, SiteIconStore.letterColor(siteHost.ifBlank { link.label }), 12f)
                }, FrameLayout.LayoutParams(dp(34f), dp(34f), Gravity.CENTER))
            }
            if (link.kind == "web" && iconRes == null && !renderedPrivate) {
                val origin = BrowserPagePresentation.shortcut(link.url)?.origin
                if (origin != null) SiteIconStore.load(activity, origin) { bitmap ->
                    square.removeAllViews()
                    square.addView(ImageView(activity).apply {
                        setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(dp(30f), dp(30f), Gravity.CENTER))
                }
            }
            addView(square, params(dp(56f), dp(56f)))
            addView(text(link.label, 11f, if (link.kind == "shield") { p.success.color } else p.text).apply {
                gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(2f), dp(6f), dp(2f), 0)
            }, params())
        }
    }

    private fun privacyCard(): View {
        val p = ThemeManager.palette(activity)
        val badge = ExtensionManager.phantomBadge(global = true)
        val available = ExtensionManager.phantomEntry()?.enabled == true && badge != "OFF" && badge != "PAUSE"
        val statusColor = if (available) p.success.color else p.muted
        val statusBg = if (available) androidx.core.graphics.ColorUtils.setAlphaComponent(p.success.color, 28) else p.elevated
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(76f); setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.surface, 18f, p.outline), 18f)
            isClickable = true; isFocusable = true; contentDescription = "Abrir protección PhantomShield"
            setOnClickListener { onShield() }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_quick_shield); setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                imageTintList = if (available) null else ColorStateList.valueOf(p.muted)
                background = ThemeManager.rounded(activity, statusBg, 12f)
            }, params(dp(38f), dp(38f)).apply { marginEnd = dp(12f) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val status = when (badge) { "OFF" -> "PAUSADA"; "PAUSE" -> "PAUSADA"; null -> "PANEL"; else -> "ACTIVA" }
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text("Protección PhantomShield", 12f, p.text, true), params(0).apply { weight = 1f })
                    addView(text(status, 8.5f, statusColor, true).apply {
                        setPadding(dp(5f), dp(3f), dp(5f), dp(3f)); typeface = resources.getFont(R.font.jetbrains_mono)
                        background = ThemeManager.rounded(activity, statusBg, 6f)
                    })
                })
                addView(text(if (badge != null && badge.matches(Regex("[0-9]{1,3}\\+?"))) "$badge bloqueos en total" else "Consulta la protección de cada sitio", 10.5f, p.muted).apply { setPadding(0, dp(4f), 0, 0) })
            }, params(0).apply { weight = 1f })
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_nav_chevron)
                imageTintList = ColorStateList.valueOf(p.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, params(dp(20f), dp(20f)).apply { marginStart = dp(8f) })
        }
    }

    private fun allLinks(): List<Link> {
        val links = ArrayList<Link>()
        try {
            val stored = JSONArray(prefs.getString("custom", "[]"))
            for (index in 0 until stored.length()) {
                val shortcut = BrowserPagePresentation.shortcut(stored.getJSONObject(index).optString("url"))
                if (shortcut != null) links.add(Link(shortcut.title, shortcut.origin))
            }
        } catch (_: org.json.JSONException) {
            // An invalid saved list must not prevent showing the built-in shortcuts.
        }
        links.addAll(listOf(
            Link("YouTube", "https://m.youtube.com/"),
            Link("Google", "https://www.google.com/"),
            Link("Shield", kind = "shield"),
            Link("Wikipedia", "https://es.wikipedia.org/"),
            Link("Descargas", kind = "downloads"),
            Link("Historial", kind = "history"),
            Link("Burner", kind = "burner"),
            Link("Extensiones", kind = "extensions"),
            Link("GitHub", "https://github.com/"),
            Link("Gmail", "https://mail.google.com/"),
            Link("Maps", "https://maps.google.com/")
        ))
        for (entry in HistoryStore.list(activity)) {
            val shortcut = BrowserPagePresentation.shortcut(entry.url)
            if (shortcut != null) links.add(Link(shortcut.title, shortcut.origin))
        }
        val seen = HashSet<String>()
        val unique = ArrayList<Link>()
        for (link in links) {
            val identity = if (link.kind == "web") {
                BrowserPagePresentation.shortcut(link.url)?.identity ?: continue
            } else link.id
            if (seen.add(identity)) unique.add(link)
        }
        return unique
    }

    private fun addLink() {
        val input = EditText(activity).apply {
            hint = "https://ejemplo.com"; setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ThemeManager.styleEdit(activity, this); setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Añadir acceso rápido").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Añadir", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val raw = input.text.toString().trim(); val candidate = if (raw.contains("://")) raw else "https://$raw"
            val link = BrowserPagePresentation.shortcut(candidate)
            if (link == null) { input.error = "Introduce una dirección web válida"; return@setOnClickListener }
            val saved = runCatching { JSONArray(prefs.getString("custom", "[]")) }.getOrDefault(JSONArray())
            saved.put(JSONObject().put("url", link.origin))
            prefs.edit().putString("custom", saved.toString()).apply()
            dialog.dismiss(); render(false)
        } }
        dialog.show()
    }

    private fun moveShortcut(targetUrl: String, delta: Int) {
        val currentCustom = buildList {
            val saved = runCatching { JSONArray(prefs.getString("custom", "[]")) }.getOrDefault(JSONArray())
            for (i in 0 until saved.length()) {
                val u = BrowserPagePresentation.shortcut(saved.optJSONObject(i)?.optString("url").orEmpty())?.origin
                if (u != null) add(u)
            }
        }
        val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
        val visibleWeb = allLinks().filter { it.kind == "web" && it.id !in hidden }.mapNotNull {
            BrowserPagePresentation.shortcut(it.url)?.origin
        }
        val origin = BrowserPagePresentation.shortcut(targetUrl)?.origin ?: return
        val updated = reorderShortcut(currentCustom, visibleWeb, origin, delta)
        val arr = JSONArray()
        updated.forEach { arr.put(JSONObject().put("url", it)) }
        prefs.edit().putString("custom", arr.toString()).apply()
        render(renderedPrivate)
    }

    /** Pulsación larga en un acceso: abrir en otra pestaña, reordenar, guardar o quitarlo de inicio. */
    private fun tileOptions(link: Link) {
        var sheet: android.app.Dialog? = null
        val dismiss = { sheet?.dismiss(); Unit }
        val actions = listOf(
            SheetAction(R.drawable.ic_menu_open, "Abrir en pestaña nueva") { TabManager.createTab(link.url) },
            SheetAction(R.drawable.ic_menu_private, "Abrir en pestaña privada") { TabManager.createTab(link.url, isPrivate = true) },
            SheetAction(R.drawable.ic_menu_bookmark, "Guardar en favoritos") {
                BookmarkStore.add(activity, link.label, link.url)
                Toastish.show(activity, "«${link.label}» guardado en favoritos")
                render(renderedPrivate)
            },
            SheetAction(R.drawable.ic_nav_up, "Mover antes") { moveShortcut(link.url, -1) },
            SheetAction(R.drawable.ic_nav_down, "Mover después") { moveShortcut(link.url, +1) },
            SheetAction(R.drawable.ic_menu_close, "Quitar de inicio", danger = true) {
                prefs.edit().putStringSet("hidden", prefs.getStringSet("hidden", emptySet()).orEmpty() + link.id).apply()
                render(renderedPrivate)
            }
        )
        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, link.label, link.url),
            NexoSheets.list(activity, actions, dismiss)))
    }

    private fun editLinks() {
        val links = allLinks().take(20)
        val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
        AlertDialog.Builder(activity).setTitle("Mostrar accesos rápidos")
            .setMultiChoiceItems(links.map { it.label }.toTypedArray(), links.map { it.id !in hidden }.toBooleanArray()) { _, index, checked ->
                if (checked) hidden.remove(links[index].id) else hidden.add(links[index].id)
            }.setNegativeButton("Cancelar", null).setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putStringSet("hidden", hidden).apply(); render(false)
            }.show()
    }
}
