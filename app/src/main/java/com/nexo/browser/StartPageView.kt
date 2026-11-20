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

/** Inicio de Nexo UI 7.0 «Liquid Craft»: lienzo sereno, sitios fijados y Espacios; nunca inventa contadores de protección. */
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

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }

    private var safeTopPx = 0
    private var safeBottomPx = 0

    /** Aire superior (barra de estado) e inferior (cromo flotante) que la página reserva. */
    fun setSafeArea(top: Int, bottom: Int) {
        if (top == safeTopPx && bottom == safeBottomPx) return
        safeTopPx = top
        safeBottomPx = bottom
        applyContentPadding()
    }

    private fun applyContentPadding() {
        content.setPadding(dp(20f), safeTopPx + dp(8f), dp(20f), safeBottomPx + dp(12f))
    }

    /** Lienzo sereno: color del tema + lavado radial ultrafino arriba (5 %, jamás neón). */
    private class AmbientWash(private val canvasColor: Int, private val tint: Int) : android.graphics.drawable.Drawable() {
        private val fill = android.graphics.Paint()
        private val wash = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun onBoundsChange(bounds: android.graphics.Rect) {
            val r = bounds.width() * 0.95f
            wash.shader = android.graphics.RadialGradient(bounds.exactCenterX(), bounds.top - r * 0.18f, r,
                intArrayOf(tint, ColorUtils.setAlphaComponent(tint, 0)), floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        override fun draw(canvas: android.graphics.Canvas) {
            fill.color = canvasColor
            canvas.drawRect(bounds, fill)
            canvas.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + bounds.width() * 1.1f, wash)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    fun render(isPrivate: Boolean) {
        renderedPrivate = isPrivate
        renderedShieldToken = shieldToken()
        val p = ThemeManager.palette(activity)
        val cfg = readConfig(activity)
        val washTint = ColorUtils.setAlphaComponent(if (p.isMonochrome) p.text else p.accentSwatch, if (p.isDark) 13 else 10)
        background = AmbientWash(p.background, washTint)
        content.removeAllViews()
        applyContentPadding()

        // 1. Cabecera mínima: PhantomShield con contador a la izquierda; iconos fantasma a la derecha.
        val top = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val badge = ExtensionManager.phantomBadge(global = true)
        val available = ExtensionManager.phantomEntry()?.enabled == true && badge != "OFF" && badge != "PAUSE"
        if (cfg.showPrivacyCard || isPrivate) top.addView(shieldPill(p, badge, available))
        top.addView(View(activity), params(0, dp(1f)).apply { weight = 1f })
        top.addView(ghostAction(if (isPrivate) R.drawable.ic_nav_add else R.drawable.ic_menu_private,
            if (isPrivate) "Abrir pestaña normal" else "Abrir pestaña privada") {
            if (isPrivate) TabManager.createTab(BrowserPrefs.NEXO_HOME) else onPrivate()
        })
        if (!isPrivate) top.addView(ghostAction(R.drawable.ic_menu_settings, "Personalizar página de inicio") { openCustomizer() })
        content.addView(top, params())

        // 2. Monograma N + saludo sereno con aire generoso.
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 6 -> "Buenas noches"
            hour < 13 -> "Buenos días"
            hour < 20 -> "Buenas tardes"
            else -> "Buenas noches"
        }
        content.addView(View(activity).apply {
            background = if (isPrivate) android.graphics.drawable.LayerDrawable(arrayOf(
                ThemeManager.rounded(activity, p.surfaceContainerHigh, 10f),
                activity.getDrawable(R.drawable.ic_menu_private)!!.mutate().apply { setTint(p.text) }
            )).apply { setLayerInset(1, dp(8f), dp(8f), dp(8f), dp(8f)) }
            else NexoMonogramDrawable(activity, p.text, p.background)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, params(dp(36f), dp(36f)).apply { topMargin = dp(44f) })
        content.addView(text(if (isPrivate) "Navegación privada" else greeting, 30f, p.text, semiBold = true).apply {
            letterSpacing = -0.025f
            isAccessibilityHeading = true
            setPadding(0, dp(14f), 0, 0)
        }, params())

        if (isPrivate) renderPrivateBody(p) else renderStandardBody(p, cfg)
    }

    private fun shieldPill(p: BrowserPalette, badge: String?, available: Boolean): View {
        val count = badge?.takeIf { it.matches(Regex("[0-9]{1,5}\\+?")) }
        val label = when {
            count != null -> count
            badge == "OFF" || badge == "PAUSE" -> "Pausado"
            available -> "Activo"
            else -> "Shield"
        }
        val pill = InsetDrawable(ThemeManager.rounded(activity, p.surface, NexoUi.Tokens.RADIUS_PILL), 0, dp(6f), 0, dp(6f))
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            background = ThemeManager.ripple(activity, pill, NexoUi.Tokens.RADIUS_PILL)
            NexoGlass.paperShadow(this, p)
            setPadding(dp(12f), 0, dp(14f), 0)
            isClickable = true
            isFocusable = true
            contentDescription = if (count != null) "PhantomShield: $count rastreadores bloqueados" else "PhantomShield: $label"
            setOnClickListener { onShield() }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_menu_shield)
                imageTintList = ColorStateList.valueOf(if (available) p.success.color else p.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, params(dp(16f), dp(16f)).apply { marginEnd = dp(8f) })
            addView(text(label, 13f, p.text).apply {
                typeface = if (count != null) NexoUi.mono(activity) else NexoUi.medium(activity)
                fontFeatureSettings = "tnum"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
    }

    private fun ghostAction(iconRes: Int, desc: String, onClick: () -> Unit): ImageView =
        ImageView(activity).apply {
            val p = ThemeManager.palette(activity)
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(p.muted)
            scaleType = ImageView.ScaleType.CENTER
            background = ThemeManager.ripple(activity, null, NexoUi.Tokens.RADIUS_PILL)
            contentDescription = desc
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = params(dp(48f), dp(48f))
        }

    private fun renderPrivateBody(p: BrowserPalette) {
        val card = NexoUi.card(activity, 18f)
        listOf(
            "No se guarda historial, búsquedas ni miniaturas.",
            "Cookies y datos locales se borran al cerrar la última pestaña privada.",
            "Las descargas permanecen; tu proveedor y los sitios siguen viendo tu conexión."
        ).forEachIndexed { i, line ->
            card.addView(text(line, 14f, if (i == 0) p.text else p.muted).apply {
                setLineSpacing(0f, 1.3f)
                setPadding(0, if (i == 0) 0 else dp(10f), 0, 0)
            })
        }
        card.addView(NexoUi.button(activity, "Crear identidad Burner") { onAction("burner") }, NexoUi.buttonParams(activity, 16f))
        content.addView(card, params().apply { topMargin = dp(28f) })
        content.addView(View(activity), params(1, 0).apply { weight = 1f })
    }

    private fun renderStandardBody(p: BrowserPalette, cfg: StartPageConfig) {
        // 3. Cuadrícula 4×2 de sitios fijados: squircles tonales sin borde (r:20, 52×52).
        if (cfg.showQuickAccess) {
            val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
            val webLinks = allLinks().filter { it.kind == "web" && it.id !in hidden }
            val fontScale = resources.configuration.fontScale.coerceIn(1f, 2f)
            val availableWidth = (width / resources.displayMetrics.density).takeIf { it > 0 }
                ?: resources.configuration.screenWidthDp.toFloat()
            val usableDp = minOf(availableWidth, UiLayout.CONTENT_MAX_DP.toFloat()) - 40f
            val columns = floor(usableDp / (72f * fontScale)).toInt().coerceIn(2, 4)
            val links = webLinks.take(columns * 2 - 1) + Link("Añadir", kind = "add")
            val gridBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            links.chunked(columns).forEach { group ->
                val row = LinearLayout(activity).apply { weightSum = columns.toFloat() }
                group.forEach { row.addView(tile(it), params(0).apply { weight = 1f }) }
                gridBox.addView(row, params())
            }
            content.addView(gridBox, params().apply { topMargin = dp(36f) })
        }

        // Inicio no minimalista: favoritos y lectura sin conexión en tarjetas limpias.
        if (!BrowserPrefs.minimalStart(activity)) {
            val bookmarks = if (cfg.showBookmarks) BookmarkStore.list(activity) else emptyList()
            if (bookmarks.isNotEmpty()) {
                content.addView(sectionHeading("Favoritos", "Ver todos") { onAction("bookmarks") }, params().apply { topMargin = dp(20f) })
                content.addView(horizontalCards(bookmarks.take(8).map { bm ->
                    val host = runCatching { android.net.Uri.parse(bm.url).host }.getOrNull().orEmpty().removePrefix("www.")
                    compactCard(bm.title, host.ifBlank { bm.url }) { onOpen(bm.url) }
                }), params())
            }
            val offline = if (cfg.showOfflineReader) OfflineArticleStore.list(OfflineArticleStore.directory(activity)) else emptyList()
            if (offline.isNotEmpty()) {
                content.addView(sectionHeading("Para leer", "Ver todos") { onAction("reader_offline") }, params().apply { topMargin = dp(16f) })
                content.addView(horizontalCards(offline.take(6).map { art ->
                    compactCard(art.title, listOf(art.siteName, "${art.readingMinutes} min").filter { it.isNotBlank() }.joinToString(" · ")) {
                        NexoReader.showArticleDialog(activity, art, openedOffline = true, onOpenUrl = onOpen)
                    }
                }), params())
            }
        }

        content.addView(View(activity), params(1, 0).apply { weight = 1f })

        // 4. Tira horizontal limpia de Grupos y Espacios con puntos de color, al alcance del pulgar.
        val chips = ArrayList<View>()
        if (cfg.showRecentGroups) {
            TabManager.allTabs()
                .filter { !it.isPrivate && !it.groupName.isNullOrBlank() }
                .groupBy { it.groupName!!.trim() }
                .entries.take(8).forEach { (name, tabs) ->
                    chips += stripChip(name, tabs.size.toString(), TabGroupRules.color(name)) { TabManager.switchTo(tabs.first().id) }
                }
        }
        if (cfg.showWorkspaces) {
            WorkspaceStore.list(activity).take(8).forEach { ws ->
                chips += stripChip(ws.name, null, SiteIconStore.letterColor(ws.name)) {
                    val count = WorkspaceStore.restoreLazily(ws)
                    Toastish.show(activity, "Espacio «${ws.name}» abierto ($count pestañas).")
                }
            }
        }
        if (chips.isNotEmpty()) {
            content.addView(NexoUi.chipRow(activity, chips), params().apply { topMargin = dp(20f) })
        }
    }

    /** Píldora de la tira de Espacios/Grupos: punto de color, nombre y recuento tabular. */
    private fun stripChip(label: String, count: String?, dot: Int, onClick: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        val pill = InsetDrawable(ThemeManager.rounded(activity, p.surface, NexoUi.Tokens.RADIUS_PILL), 0, dp(6f), 0, dp(6f))
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48f)
            setPadding(dp(14f), 0, dp(16f), 0)
            background = ThemeManager.ripple(activity, pill, NexoUi.Tokens.RADIUS_PILL)
            NexoGlass.paperShadow(this, p)
            isClickable = true
            isFocusable = true
            contentDescription = listOfNotNull(label, count?.let { "$it pestañas" }).joinToString(", ")
            setOnClickListener { onClick() }
            addView(View(activity).apply { background = ThemeManager.rounded(activity, dot, 4f) },
                params(dp(7f), dp(7f)).apply { marginEnd = dp(8f) })
            addView(text(label, 13.5f, p.text).apply {
                typeface = NexoUi.medium(activity)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(160f)
            })
            if (count != null) addView(text(count, 12.5f, p.muted).apply {
                typeface = NexoUi.mono(activity)
                fontFeatureSettings = "tnum"
                setPadding(dp(6f), 0, 0, 0)
            })
        }
    }

    private fun sectionHeading(title: String, actionLabel: String?, onActionClick: () -> Unit): LinearLayout {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 15f, p.text, semiBold = true).apply {
                letterSpacing = -0.025f
                isAccessibilityHeading = true
            }, params(0).apply { weight = 1f })
            if (!actionLabel.isNullOrBlank()) {
                addView(text(actionLabel, 13f, p.muted).apply {
                    typeface = NexoUi.medium(activity)
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
            row.addView(card, LinearLayout.LayoutParams(dp(172f), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (idx > 0) marginStart = dp(10f)
            })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(row)
        }
    }

    private fun compactCard(title: String, subtitle: String, onClick: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(72f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.surface, NexoUi.Tokens.RADIUS_CARD), NexoUi.Tokens.RADIUS_CARD)
            NexoGlass.paperShadow(this, p)
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $subtitle"
            setOnClickListener { onClick() }
            addView(text(title, 13.5f, p.text).apply {
                typeface = NexoUi.medium(activity)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(text(subtitle, 12f, p.muted).apply {
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
        togglesCard.addView(NexoUi.switchRow(activity, "Inicio minimalista", "Oculta favoritos y lecturas", BrowserPrefs.minimalStart(activity)) {
            BrowserPrefs.setMinimalStart(activity, it)
            render(renderedPrivate)
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Sitios fijados", null, cfg.showQuickAccess) {
            update(cfg.copy(showQuickAccess = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Favoritos", null, cfg.showBookmarks) {
            update(cfg.copy(showBookmarks = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Espacios", null, cfg.showWorkspaces) {
            update(cfg.copy(showWorkspaces = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Grupos de pestañas", null, cfg.showRecentGroups) {
            update(cfg.copy(showRecentGroups = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Lectura sin conexión", null, cfg.showOfflineReader) {
            update(cfg.copy(showOfflineReader = it))
        })
        togglesCard.addView(NexoUi.divider(activity, 0f))
        togglesCard.addView(NexoUi.switchRow(activity, "Contador de PhantomShield", null, cfg.showPrivacyCard) {
            update(cfg.copy(showPrivacyCard = it))
        })
        body.addView(togglesCard)

        var sheet: android.app.Dialog? = null
        body.addView(NexoUi.button(activity, "Elegir sitios fijados") {
            sheet?.dismiss()
            editLinks()
        }, NexoUi.buttonParams(activity, 14f))
        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Personalizar inicio"),
                body
            )
        )
    }

    /** Squircle tonal de 52×52 (r:20) sin borde: favicon real, monograma de respaldo o "+". */
    private fun tile(link: Link): View {
        val p = ThemeManager.palette(activity)
        val host = BrowserPagePresentation.shortcut(link.url)?.identity.orEmpty()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2f), dp(6f), dp(2f), dp(10f))
            background = ThemeManager.ripple(activity, null, NexoUi.Tokens.RADIUS_PINNED)
            isClickable = true
            isFocusable = true
            contentDescription = if (link.kind == "add") "Añadir sitio fijado" else link.label
            setOnClickListener {
                NexoMotion.pulse(getChildAt(0), 0.92f)
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
                background = ThemeManager.rounded(activity, p.surface, NexoUi.Tokens.RADIUS_PINNED)
                NexoGlass.paperShadow(this, p)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val iconRes = when {
                link.kind == "add" -> R.drawable.ic_nav_add
                host.startsWith("youtube.com:") -> R.drawable.ic_quick_youtube
                host.startsWith("google.com:") -> R.drawable.ic_quick_google
                host.startsWith("github.com:") -> R.drawable.ic_quick_github
                else -> null
            }
            if (iconRes != null) {
                square.addView(ImageView(activity).apply {
                    setImageResource(iconRes)
                    when {
                        link.kind == "add" -> imageTintList = ColorStateList.valueOf(p.muted)
                        host.startsWith("github.com:") -> imageTintList = ColorStateList.valueOf(p.text)
                    }
                }, FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
            } else {
                val siteHost = runCatching { android.net.Uri.parse(link.url).host }.getOrNull().orEmpty()
                square.addView(text(
                    if (host.contains("wikipedia.org")) "W" else SiteIconStore.letter(siteHost.ifBlank { link.label }),
                    18f, p.text, semiBold = true
                ).apply {
                    gravity = Gravity.CENTER
                    if (host.contains("wikipedia.org")) typeface = Typeface.create("serif", Typeface.NORMAL)
                }, FrameLayout.LayoutParams(dp(32f), dp(32f), Gravity.CENTER))
            }
            if (link.kind == "web" && iconRes == null && !renderedPrivate) {
                val origin = BrowserPagePresentation.shortcut(link.url)?.origin
                if (origin != null) SiteIconStore.load(activity, origin) { bitmap ->
                    square.removeAllViews()
                    square.addView(ImageView(activity).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
                }
            }
            addView(square, params(dp(52f), dp(52f)))
            addView(text(link.label, 12f, p.muted).apply {
                typeface = NexoUi.regular(activity)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(2f), dp(8f), dp(2f), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, params())
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
        val dialog = AlertDialog.Builder(activity).setTitle("Añadir sitio fijado").setView(input)
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
        AlertDialog.Builder(activity).setTitle("Sitios fijados")
            .setMultiChoiceItems(links.map { it.label }.toTypedArray(), links.map { it.id !in hidden }.toBooleanArray()) { _, index, checked ->
                if (checked) hidden.remove(links[index].id) else hidden.add(links[index].id)
            }.setNegativeButton("Cancelar", null).setPositiveButton("Guardar") { _, _ ->
                prefs.edit().putStringSet("hidden", hidden).apply()
                render(false)
            }.show()
    }
}
