package com.nexo.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
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
import androidx.core.graphics.ColorUtils
import com.nexo.browser.reader.NexoReader
import com.nexo.browser.reader.OfflineArticleStore
import com.nexo.browser.workspace.BookmarkStore
import com.nexo.browser.workspace.WorkspaceStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

/** Native rendering of Nexo UI 5.0 Start Page with customizable Nexo Workspace blocks; never fabricates protection counters. */
@SuppressLint("ViewConstructor")
class StartPageView(
    private val activity: Activity,
    private val onOpen: (String) -> Unit,
    private val onPrivate: () -> Unit,
    private val onShield: () -> Unit,
    private val onExtensions: () -> Unit,
    /** Accesos de Nexo: "ai", "downloads", "history", "burner", "settings", "bookmarks", "workspaces", "reader_offline". */
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
                    val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
                    if (shortcut.origin !in hidden) return null
                    prefs.edit().putStringSet("hidden", hidden - shortcut.origin).apply()
                    return shortcut.title
                }
            }
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
    private fun params(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, height)

    private fun text(label: String, size: Float, color: Int, bold: Boolean = false, semiBold: Boolean = false) =
        TextView(activity).apply {
            text = label
            textSize = size
            setTextColor(color)
            typeface = when {
                bold -> NexoUi.bold(activity)
                semiBold -> NexoUi.semiBold(activity)
                else -> NexoUi.regular(activity)
            }
        }

    private fun cardSurface(p: BrowserPalette): Int =
        if (p.isDark) p.surfaceContainerLow else p.surfaceContainerLowest

    private fun elevatedSurface(p: BrowserPalette): Int =
        if (p.isDark) p.surfaceContainer else p.surfaceContainerLowest

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }

    fun render(isPrivate: Boolean) {
        renderedPrivate = isPrivate
        renderedShieldToken = shieldToken()
        val p = ThemeManager.palette(activity)
        val cfg = readConfig(activity)
        setBackgroundColor(p.background)
        content.removeAllViews()
        content.setPadding(dp(18f), dp(12f), dp(18f), dp(20f))

        // -----------------------------------------------------------------
        // 1. Franja superior mínima: estado discreto de PhantomShield + utilidades
        // -----------------------------------------------------------------
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val badge = ExtensionManager.phantomBadge(global = true)
        val available = ExtensionManager.phantomEntry()?.enabled == true && badge != "OFF" && badge != "PAUSE"
        val shieldColor = if (available) p.success.color else p.muted
        val shieldPillBg = if (available) p.success.container else p.surfaceContainerHigh
        val shieldPillFg = if (available) p.success.onContainer else p.muted

        if (isPrivate || cfg.showPrivacyCard) {
            val topPillBg = cardSurface(p)
            val topPillInset = InsetDrawable(
                ThemeManager.rounded(activity, topPillBg, 18f, if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null),
                0, dp(5f), 0, dp(5f)
            )
            top.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48f)
                background = ThemeManager.ripple(activity, topPillInset, 18f)
                setPadding(dp(12f), dp(5f), dp(8f), dp(5f))
                isClickable = true
                isFocusable = true
                contentDescription = "Abrir panel de PhantomShield"
                setOnClickListener { onShield() }
                addView(View(activity).apply {
                    background = ThemeManager.rounded(activity, shieldColor, 4f)
                }, params(dp(7f), dp(7f)).apply { marginEnd = dp(8f) })
                addView(text("PhantomShield", 12f, p.text, semiBold = true))
                val statusLabel = when (badge) {
                    "OFF", "PAUSE" -> "Pausado"
                    null -> if (available) "Activo" else "Panel"
                    else -> badge
                }
                addView(text(statusLabel, 10.5f, shieldPillFg, bold = true).apply {
                    typeface = NexoUi.mono(activity)
                    background = ThemeManager.rounded(activity, shieldPillBg, 10f)
                    setPadding(dp(7f), dp(2.5f), dp(7f), dp(2.5f))
                }, params(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8f)
                })
            })
        }
        top.addView(View(activity), params(0, dp(1f)).apply { weight = 1f })

        fun topCircleAction(iconRes: Int, desc: String, active: Boolean = false, onClick: () -> Unit): ImageView {
            val circleBg = if (active) p.accentSoft else cardSurface(p)
            val tint = if (active) p.onAccentSoft else p.muted
            val stroke = if (!active && (p.isAmoled || !p.isDark)) ThemeManager.hairline(p) else null
            val inset = InsetDrawable(ThemeManager.rounded(activity, circleBg, 20f, stroke), dp(4f))
            return ImageView(activity).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(tint)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = ThemeManager.ripple(activity, inset, 22f)
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                contentDescription = desc
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        if (!isPrivate) {
            top.addView(topCircleAction(R.drawable.ic_menu_settings, "Personalizar página de inicio") {
                openCustomizer()
            }, params(dp(48f), dp(48f)).apply { marginEnd = dp(4f) })
        }
        top.addView(topCircleAction(R.drawable.ic_menu_private, "Abrir pestaña privada", active = isPrivate) {
            onPrivate()
        }, params(dp(48f), dp(48f)))
        content.addView(top, params())

        // -----------------------------------------------------------------
        // 2. Identidad Nexo: cabecera editorial compacta y equilibrada
        // -----------------------------------------------------------------
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 6 -> "Buenas noches"
            hour < 13 -> "Buenos días"
            hour < 20 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        val heroRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(16f), dp(2f), dp(14f))
        }
        heroRow.addView(ImageView(activity).apply {
            setImageResource(if (isPrivate) R.drawable.ic_menu_private else R.drawable.ic_nexo_logo)
            if (isPrivate) {
                val inset = dp(10f)
                setPadding(inset, inset, inset, inset)
                imageTintList = ColorStateList.valueOf(p.onAccentSoft)
                background = ThemeManager.rounded(activity, p.accentSoft, 14f)
            }
        }, params(dp(44f), dp(44f)).apply { marginEnd = dp(14f) })
        heroRow.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(if (isPrivate) "Navegación privada" else greeting, 24f, p.text, bold = true).apply {
                letterSpacing = -0.02f
                maxLines = 2
            })
            addView(text(
                if (isPrivate) "Espacio aislado · sin historial ni cookies persistentes"
                else "Nexo Browser · navegación limpia y privada",
                12.5f, p.muted
            ).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, 0)
            })
        }, params(0).apply { weight = 1f })
        content.addView(heroRow, params())

        if (isPrivate) {
            renderPrivateBody(p)
        } else {
            renderStandardBody(p, cfg, badge, available)
        }

        content.addView(View(activity), params(1, 0).apply { weight = 1f })
        if (isPrivate) {
            content.addView(privacyStrip(p, badge, available), params().apply { topMargin = dp(20f) })
        }
    }

    private fun renderPrivateBody(p: BrowserPalette) {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, cardSurface(p), 22f, ThemeManager.hairline(p))
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        }
        card.addView(text("Qué protege esta sesión", 14.5f, p.text, semiBold = true))
        val bullets = listOf(
            "No guarda historial, búsquedas ni miniaturas en el dispositivo.",
            "Elimina cookies y datos locales al cerrar todas las pestañas privadas.",
            "Las descargas guardadas permanecen; tu ISP y los sitios siguen viendo tu conexión."
        )
        bullets.forEach { line ->
            card.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(10f), 0, 0)
                addView(View(activity).apply {
                    background = ThemeManager.rounded(activity, p.accent, 3f)
                }, params(dp(6f), dp(6f)).apply { topMargin = dp(6f); marginEnd = dp(10f) })
                addView(text(line, 13f, p.muted).apply { setLineSpacing(0f, 1.25f) }, params(0).apply { weight = 1f })
            })
        }
        card.addView(NexoUi.button(activity, "Crear identidad Burner (aislada + correo)", primary = false) {
            onAction("burner")
        }, NexoUi.buttonParams(activity, 14f))
        content.addView(card, params())
    }

    private fun renderStandardBody(p: BrowserPalette, cfg: StartPageConfig, badge: String?, available: Boolean) {
        val accentLink = if (p.isDark) ColorUtils.blendARGB(p.accent, p.text, 0.35f) else p.accent

        // -----------------------------------------------------------------
        // 3. Tarjeta protagonista de Nexo AI (limpia, sin emojis duplicados)
        // -----------------------------------------------------------------
        val aiCardFill = if (p.isDark) {
            ColorUtils.blendARGB(p.surfaceContainerLow, p.accentSoft, 0.32f)
        } else {
            ColorUtils.blendARGB(p.surfaceContainerLowest, p.accentSoft, 0.48f)
        }
        val aiStroke = ColorUtils.setAlphaComponent(p.accent, if (p.isAmoled) 110 else 70)
        val aiCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, aiCardFill, 22f, aiStroke), 22f)
            setPadding(dp(14f), dp(13f), dp(14f), dp(12f))
            isClickable = true
            isFocusable = true
            contentDescription = "Pregúntale a Nexo AI sobre esta página o tus pestañas"
            setOnClickListener { onAction("ai") }
        }
        aiCard.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_nexo_ai_spark)
                imageTintList = ColorStateList.valueOf(if (p.isDark) p.onAccentSoft else p.accent)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                background = ThemeManager.rounded(activity, p.accentSoft, 12f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, params(dp(36f), dp(36f)).apply { marginEnd = dp(12f) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Pregúntale a Nexo AI", 15f, p.text, semiBold = true))
                addView(text("Resume, traduce o compara pestañas", 12f, p.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2f), 0, 0)
                })
            }, params(0).apply { weight = 1f })
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_nav_chevron)
                imageTintList = ColorStateList.valueOf(if (p.isDark) p.onAccentSoft else p.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, params(dp(18f), dp(18f)).apply { marginStart = dp(8f) })
        })

        // Píldoras sutiles de intención rápida dentro de la tarjeta IA
        val aiIntents = listOf("Resumir", "Traducir", "Comparar pestañas")
        val intentRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        val pillBg = if (p.isDark) ColorUtils.setAlphaComponent(p.surfaceContainerHighest, 190) else p.surfaceContainerLowest
        aiIntents.forEachIndexed { idx, label ->
            intentRow.addView(TextView(activity).apply {
                text = label
                textSize = 11.5f
                typeface = NexoUi.medium(activity)
                setTextColor(p.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(11f), dp(5f), dp(11f), dp(5f))
                background = ThemeManager.rounded(activity, pillBg, 14f, ThemeManager.hairline(p))
            }, params(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (idx > 0) marginStart = dp(6f)
            })
        }
        aiCard.addView(intentRow)
        content.addView(aiCard, params().apply { bottomMargin = dp(18f) })

        // -----------------------------------------------------------------
        // 4. Accesos rápidos: cuadrícula tonal de 4 columnas (sin bordes pesados)
        // -----------------------------------------------------------------
        if (cfg.showQuickAccess) {
            content.addView(sectionHeading("Accesos rápidos", "Editar", accentLink) { editLinks() }, params())
            val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
            val links = allLinks().filterNot { it.id in hidden }.take(MAX_TILES) + Link("Añadir", kind = "add")
            val availableWidth = (width / resources.displayMetrics.density).takeIf { it > 0 }
                ?: resources.configuration.screenWidthDp.toFloat()
            val usableDp = minOf(availableWidth, UiLayout.CONTENT_MAX_DP.toFloat()) - 36f
            val fontScale = resources.configuration.fontScale.coerceIn(1f, 2f)
            val columns = floor(usableDp / (78f * fontScale)).toInt().coerceIn(2, 5)
            val gridBox = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(2f), 0, dp(4f))
            }
            links.chunked(columns).forEach { group ->
                val row = LinearLayout(activity).apply { weightSum = columns.toFloat() }
                group.forEach { row.addView(tile(it), params(0).apply { weight = 1f }) }
                gridBox.addView(row, params())
            }
            content.addView(gridBox, params())
        }

        // -----------------------------------------------------------------
        // 5. Nexo Workspace: Favoritos, Espacios, Grupos activos y Lectura sin conexión
        // -----------------------------------------------------------------
        val bookmarks = if (cfg.showBookmarks) BookmarkStore.list(activity) else emptyList()
        val workspaces = if (cfg.showWorkspaces) WorkspaceStore.list(activity) else emptyList()
        val offlineArticles = if (cfg.showOfflineReader) {
            OfflineArticleStore.list(OfflineArticleStore.directory(activity))
        } else emptyList()

        if (cfg.showBookmarks || cfg.showWorkspaces || cfg.showOfflineReader) {
            content.addView(sectionHeading("Workspace", null, accentLink) {}, params().apply { topMargin = dp(8f) })
            val hubRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val hubTiles = ArrayList<View>()
            if (cfg.showBookmarks) {
                hubTiles += workspaceHubTile(
                    icon = R.drawable.ic_menu_bookmark,
                    label = "Favoritos",
                    count = if (bookmarks.isEmpty()) "Vacío" else "${bookmarks.size}",
                    tone = NexoUi.Tone.ACCENT
                ) { onAction("bookmarks") }
            }
            if (cfg.showWorkspaces) {
                hubTiles += workspaceHubTile(
                    icon = R.drawable.ic_menu_workspace,
                    label = "Espacios",
                    count = if (workspaces.isEmpty()) "Crear" else "${workspaces.size}",
                    tone = NexoUi.Tone.INFO
                ) { onAction("workspaces") }
            }
            if (cfg.showOfflineReader) {
                hubTiles += workspaceHubTile(
                    icon = R.drawable.ic_menu_reader,
                    label = "Lectura",
                    count = if (offlineArticles.isEmpty()) "Offline" else "${offlineArticles.size}",
                    tone = NexoUi.Tone.SUCCESS
                ) { onAction("reader_offline") }
            }
            hubTiles.forEachIndexed { idx, tileView ->
                hubRow.addView(tileView, params(0).apply {
                    weight = 1f
                    if (idx > 0) marginStart = dp(8f)
                })
            }
            if (hubTiles.isNotEmpty()) {
                content.addView(hubRow, params().apply { topMargin = dp(4f) })
            }
        }

        if (cfg.showBookmarks && bookmarks.isNotEmpty()) {
            content.addView(sectionHeading("Favoritos recientes", "Ver todos", accentLink) { onAction("bookmarks") }, params().apply { topMargin = dp(14f) })
            content.addView(horizontalCards(bookmarks.take(8).map { bm ->
                val host = runCatching { android.net.Uri.parse(bm.url).host }.getOrNull().orEmpty().removePrefix("www.")
                compactCard(bm.title, "${bm.folder} · ${host.ifBlank { bm.url }}", R.drawable.ic_menu_bookmark) {
                    onOpen(bm.url)
                }
            }), params())
        }

        if (cfg.showWorkspaces && workspaces.isNotEmpty()) {
            content.addView(sectionHeading("Espacios guardados", "Gestionar", accentLink) { onAction("workspaces") }, params().apply { topMargin = dp(14f) })
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
                content.addView(sectionHeading("Grupos activos", null, accentLink) {}, params().apply { topMargin = dp(12f) })
                val chips = groups.take(10).map { (name, tabs) ->
                    NexoUi.chip(activity, "$name · ${tabs.size}", selected = false, dotColor = TabGroupRules.color(name)) {
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

    private fun workspaceHubTile(icon: Int, label: String, count: String, tone: NexoUi.Tone, onClick: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        val (iconBg, iconFg) = NexoUi.toneColors(p, tone)
        val fill = cardSurface(p)
        val stroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(74f)
            setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, fill, 18f, stroke), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "$label: $count"
            setOnClickListener { onClick() }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(iconFg)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                    background = ThemeManager.rounded(activity, iconBg, 10f)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, params(dp(28f), dp(28f)))
                addView(View(activity), params(0, dp(1f)).apply { weight = 1f })
                addView(text(count, 11f, p.muted, bold = true).apply {
                    typeface = NexoUi.mono(activity)
                    maxLines = 1
                })
            })
            addView(text(label, 12.5f, p.text, semiBold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(8f), 0, 0)
            })
        }
    }

    private fun sectionHeading(title: String, actionLabel: String?, accentColor: Int, onActionClick: () -> Unit): LinearLayout {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title.uppercase(), 11f, p.muted, bold = true).apply {
                letterSpacing = 0.08f
                setPadding(dp(2f), dp(6f), 0, dp(4f))
            }, params(0).apply { weight = 1f })
            if (!actionLabel.isNullOrBlank()) {
                addView(text(actionLabel, 12.5f, accentColor, semiBold = true).apply {
                    minimumHeight = dp(48f)
                    gravity = Gravity.CENTER
                    setPadding(dp(12f), 0, dp(2f), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onActionClick() }
                })
            }
        }
    }

    private fun horizontalCards(cards: List<View>): HorizontalScrollView {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        cards.forEachIndexed { idx, card ->
            row.addView(card, LinearLayout.LayoutParams(dp(186f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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
        val stroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(74f)
            setPadding(dp(13f), dp(12f), dp(13f), dp(12f))
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, cardSurface(p), 18f, stroke), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $subtitle"
            setOnClickListener { onClick() }
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(p.accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, params(dp(16f), dp(16f)).apply { marginEnd = dp(8f) })
                addView(text(title, 13f, p.text, semiBold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, params(0).apply { weight = 1f })
            })
            addView(text(subtitle, 11.5f, p.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
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
        val togglesCard = NexoUi.card(activity)
        togglesCard.addView(NexoUi.switchRow(activity, "Accesos rápidos", "Cuadrícula de sitios fijados y frecuentes", cfg.showQuickAccess) {
            update(cfg.copy(showQuickAccess = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Favoritos", "Acceso directo a tus sitios guardados", cfg.showBookmarks) {
            update(cfg.copy(showBookmarks = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Espacios de trabajo", "Colecciones de pestañas y grupos con carga perezosa", cfg.showWorkspaces) {
            update(cfg.copy(showWorkspaces = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Grupos activos", "Acceso directo a los grupos de pestañas abiertos", cfg.showRecentGroups) {
            update(cfg.copy(showRecentGroups = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Lectura sin conexión", "Artículos guardados con Nexo Reader", cfg.showOfflineReader) {
            update(cfg.copy(showOfflineReader = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Resumen de PhantomShield", "Franja inferior discreta con estado de protección", cfg.showPrivacyCard) {
            update(cfg.copy(showPrivacyCard = it))
        })
        body.addView(togglesCard)

        var sheet: android.app.Dialog? = null
        body.addView(NexoUi.button(activity, "Elegir accesos rápidos visibles") {
            sheet?.dismiss()
            editLinks()
        }, NexoUi.buttonParams(activity, 14f))
        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Personalizar inicio", "Organiza los bloques visibles de tu página principal"),
                body
            )
        )
    }

    private fun tile(link: Link): View {
        val p = ThemeManager.palette(activity)
        val host = BrowserPagePresentation.shortcut(link.url)?.identity.orEmpty()
        val wellBg = if (link.kind == "add") p.surfaceContainerHigh else elevatedSurface(p)
        val wellStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2f), dp(4f), dp(2f), dp(12f))
            background = ThemeManager.ripple(activity, null, 18f)
            isClickable = true
            isFocusable = true
            contentDescription = link.label
            setOnClickListener {
                when (link.kind) {
                    "shield" -> onShield()
                    "extensions" -> onExtensions()
                    "add" -> addLink()
                    "downloads", "history", "burner", "settings" -> onAction(link.kind)
                    else -> onOpen(link.url)
                }
            }
            if (link.kind == "web") setOnLongClickListener { tileOptions(link); true }
            val square = FrameLayout(activity).apply {
                background = ThemeManager.rounded(activity, wellBg, 17f, wellStroke)
            }
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
                val siteHost = runCatching { android.net.Uri.parse(link.url).host }.getOrNull().orEmpty()
                val letterBg = SiteIconStore.letterColor(siteHost.ifBlank { link.label })
                square.addView(text(
                    if (host.contains("wikipedia.org")) "W" else SiteIconStore.letter(siteHost.ifBlank { link.label }),
                    18f,
                    UiContrast.foreground(letterBg),
                    bold = true
                ).apply {
                    gravity = Gravity.CENTER
                    if (host.contains("wikipedia.org")) typeface = Typeface.create("serif", Typeface.BOLD)
                    background = ThemeManager.rounded(activity, letterBg, 11f)
                }, FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.CENTER))
            }
            if (link.kind == "web" && iconRes == null && !renderedPrivate) {
                val origin = BrowserPagePresentation.shortcut(link.url)?.origin
                if (origin != null) SiteIconStore.load(activity, origin) { bitmap ->
                    square.removeAllViews()
                    square.addView(ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(dp(28f), dp(28f), Gravity.CENTER))
                }
            }
            addView(square, params(dp(54f), dp(54f)))
            addView(text(link.label, 11.5f, if (link.kind == "add") p.muted else p.text, semiBold = false).apply {
                typeface = NexoUi.medium(activity)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(2f), dp(6f), dp(2f), 0)
            }, params())
        }
    }

    private fun privacyStrip(p: BrowserPalette, badge: String?, available: Boolean): View {
        val statusBg = if (available) p.success.container else p.surfaceContainerHigh
        val statusFg = if (available) p.success.onContainer else p.muted
        val statusLabel = when (badge) {
            "OFF", "PAUSE" -> "PAUSADA"
            null -> if (available) "ACTIVA" else "PANEL"
            else -> "ACTIVA"
        }
        val subtitle = if (badge != null && badge.matches(Regex("[0-9]{1,3}\\+?"))) {
            "$badge rastreadores y anuncios bloqueados"
        } else {
            "Protección integrada de Nexo"
        }
        val stroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58f)
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, cardSurface(p), 18f, stroke), 18f)
            isClickable = true
            isFocusable = true
            contentDescription = "Abrir protección PhantomShield"
            setOnClickListener { onShield() }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_quick_shield)
                setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                imageTintList = if (available) null else ColorStateList.valueOf(p.muted)
                background = ThemeManager.rounded(activity, statusBg, 11f)
            }, params(dp(34f), dp(34f)).apply { marginEnd = dp(12f) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("PhantomShield", 13f, p.text, semiBold = true))
                addView(text(subtitle, 11.5f, p.muted).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(1f), 0, 0)
                })
            }, params(0).apply { weight = 1f })
            addView(text(statusLabel, 9.5f, statusFg, bold = true).apply {
                typeface = NexoUi.mono(activity)
                setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
                background = ThemeManager.rounded(activity, statusBg, 8f)
            }, params(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            })
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_nav_chevron)
                imageTintList = ColorStateList.valueOf(p.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, params(dp(18f), dp(18f)))
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
            Link("Wikipedia", "https://es.wikipedia.org/"),
            Link("GitHub", "https://github.com/"),
            Link("Gmail", "https://mail.google.com/"),
            Link("Maps", "https://maps.google.com/"),
            Link("Descargas", kind = "downloads"),
            Link("Historial", kind = "history"),
            Link("Burner", kind = "burner"),
            Link("Extensiones", kind = "extensions"),
            Link("Shield", kind = "shield")
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
            hint = "https://ejemplo.com"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ThemeManager.styleEdit(activity, this)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Añadir acceso rápido").setView(input)
            .setNegativeButton("Cancelar", null).setPositiveButton("Añadir", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text.toString().trim()
                val candidate = if (raw.contains("://")) raw else "https://$raw"
                val link = BrowserPagePresentation.shortcut(candidate)
                if (link == null) {
                    input.error = "Introduce una dirección web válida"
                    return@setOnClickListener
                }
                val saved = runCatching { JSONArray(prefs.getString("custom", "[]")) }.getOrDefault(JSONArray())
                saved.put(JSONObject().put("url", link.origin))
                prefs.edit().putString("custom", saved.toString()).apply()
                dialog.dismiss()
                render(false)
            }
        }
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
        sheet = NexoSheets.show(activity, NexoSheets.column(
            activity,
            NexoSheets.header(activity, link.label, link.url),
            NexoSheets.list(activity, actions, dismiss)
        ))
    }

    private fun editLinks() {
        val links = allLinks().take(20)
        val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty().toMutableSet()
        AlertDialog.Builder(activity).setTitle("Mostrar accesos rápidos")
            .setMultiChoiceItems(links.map { it.label }.toTypedArray(), links.map { it.id !in hidden }.toBooleanArray()) { _, index, checked ->
                if (checked) hidden.remove(links[index].id) else hidden.add(links[index].id)
            }.setNegativeButton("Cancelar", null).setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putStringSet("hidden", hidden).apply()
                render(false)
            }.show()
    }
}
