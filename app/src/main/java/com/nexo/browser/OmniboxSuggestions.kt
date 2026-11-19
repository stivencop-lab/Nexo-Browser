package com.nexo.browser

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Una fila del buscador universal Nexo Command. */
sealed class OmniboxSuggestion {
    data class PrefixHint(val scope: NexoCommandEngine.Scope) : OmniboxSuggestion()
    data class OpenTab(val tabId: String, val title: String, val url: String, val group: String?, val isPrivate: Boolean) : OmniboxSuggestion()
    data class OpenGroup(val groupName: String, val tabCount: Int, val firstTabId: String) : OmniboxSuggestion()
    data class Bookmark(val title: String, val url: String, val folder: String) : OmniboxSuggestion()
    data class Shortcut(val title: String, val url: String) : OmniboxSuggestion()
    data class Command(val spec: NexoCommandEngine.CommandSpec) : OmniboxSuggestion()
    data class Setting(val spec: NexoCommandEngine.SettingSpec) : OmniboxSuggestion()
    data class History(val title: String, val url: String) : OmniboxSuggestion()
    data class Search(val query: String) : OmniboxSuggestion()
}

/**
 * Nexo Command: buscador universal sobre el panel de sugerencias del omnibox.
 *
 * Combina en tiempo real pestañas abiertas, grupos, favoritos, accesos guardados,
 * historial local, ajustes, comandos rápidos y (solo cuando es seguro) sugerencias web.
 *
 * Privacidad:
 * - Las consultas locales con prefijo `@` y consultas privadas nunca salen al buscador remoto.
 * - Las sugerencias remotas únicamente generan [OmniboxSuggestion.Search].
 */
class OmniboxSuggestions(
    private val activity: Activity,
    private val panel: LinearLayout,
    private val bookmarksProvider: () -> List<NexoCommandEngine.BookmarkCandidate> = { emptyList() },
    private val onPick: (OmniboxSuggestion) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "NexoSuggest").apply { isDaemon = true } }
    private var generation = 0
    private var query = ""
    private var lastRemoteAllowed = false
    private var local: List<NexoCommandEngine.Result> = emptyList()
    private var remote: List<String> = emptyList()
    private val remoteTask = Runnable { requestRemote() }

    /** Devuelve el primer resultado local accionable (para Enter en consultas con prefijo `@`). */
    fun firstLocalAction(): OmniboxSuggestion? = local.firstOrNull()?.let { toSuggestion(it) }

    /** Texto de la barra con foco; vacío o null oculta el panel. */
    fun update(text: String?, remoteAllowed: Boolean) {
        val current = text?.trim().orEmpty()
        val id = ++generation
        main.removeCallbacks(remoteTask)
        RemoteSuggestions.cancel()
        if (current.isEmpty()) { hide(); return }
        if (current != query) remote = emptyList()
        query = current
        lastRemoteAllowed = remoteAllowed

        val app = activity.applicationContext
        val activeId = TabManager.activeTab()?.id
        val onWebPage = TabManager.activeTab()?.let { !BrowserPrefs.isNexoHome(it.url) } == true
        val tabsSnapshot = TabManager.allTabs().map { tab ->
            NexoCommandEngine.TabCandidate(
                id = tab.id,
                title = tab.title,
                url = tab.url,
                group = tab.groupName,
                isPrivate = tab.isPrivate,
                active = tab.id == activeId
            )
        }
        val parsed = NexoCommandEngine.parseQuery(current)

        worker.execute {
            val bookmarksSnapshot = runCatching { bookmarksProvider() }.getOrDefault(emptyList())
            val shortcutsSnapshot = if (parsed.scope == NexoCommandEngine.Scope.ALL) {
                runCatching { StartPageView.savedShortcuts(app) }.getOrDefault(emptyList())
            } else emptyList()
            val historySnapshot = when {
                parsed.scope == NexoCommandEngine.Scope.HISTORY && parsed.term.isBlank() ->
                    HistoryStore.list(app).take(8).map { NexoCommandEngine.HistoryCandidate(it.title, it.url) }
                parsed.scope == NexoCommandEngine.Scope.ALL || parsed.scope == NexoCommandEngine.Scope.HISTORY ->
                    HistoryStore.index(app).search(parsed.term.ifBlank { current }, 6)
                        .map { NexoCommandEngine.HistoryCandidate(it.title, it.url) }
                else -> emptyList()
            }
            val found = NexoCommandEngine.searchLocal(
                raw = current,
                tabs = tabsSnapshot,
                bookmarks = bookmarksSnapshot,
                shortcuts = shortcutsSnapshot,
                history = historySnapshot,
                onWebPage = onWebPage,
                maxResults = if (parsed.isPrefixed) 8 else 6
            )
            main.post {
                if (id != generation) return@post
                local = found
                render()
            }
        }

        if (NexoCommandEngine.allowsRemoteSuggestions(current, remoteAllowed)) {
            RemoteSuggestions.cached(BrowserPrefs.searchEngine(app), current)?.let {
                remote = it
                render()
            } ?: main.postDelayed(remoteTask, 250L)
        } else {
            remote = emptyList()
        }
    }

    private fun requestRemote() {
        val id = generation
        val asked = query
        if (!NexoCommandEngine.allowsRemoteSuggestions(asked, lastRemoteAllowed)) return
        RemoteSuggestions.fetch(BrowserPrefs.searchEngine(activity.applicationContext), asked) { answered, values ->
            main.post {
                // Respuesta de una consulta anterior: se descarta.
                if (id != generation || answered != query) return@post
                remote = values
                render()
            }
        }
    }

    fun hide() {
        ++generation
        main.removeCallbacks(remoteTask)
        RemoteSuggestions.cancel()
        local = emptyList(); remote = emptyList(); query = ""
        panel.removeAllViews()
        panel.visibility = View.GONE
    }

    private fun render() {
        panel.removeAllViews()
        val remoteRows = NexoCommandEngine.remoteResults(remote, query, lastRemoteAllowed, max = 2)
            .map { OmniboxSuggestion.Search(it.query) }
        val localRows = local.map { toSuggestion(it) }
        val rows = if (query.startsWith("@")) localRows else (localRows + remoteRows)
        if (rows.isEmpty()) { panel.visibility = View.GONE; return }
        val palette = ThemeManager.palette(activity)
        panel.background = ThemeManager.rounded(activity, palette.elevated, 18f, palette.outline)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        rows.forEachIndexed { index, item ->
            if (index > 0) listContainer.addView(NexoUi.divider(activity, horizontalMarginDp = 12f))
            listContainer.addView(row(item))
        }
        if (rows.size > 5) {
            val scroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                addView(listContainer)
            }
            panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, NexoUi.dp(activity, 272f)))
        } else {
            panel.addView(listContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        panel.visibility = View.VISIBLE
    }

    private fun toSuggestion(result: NexoCommandEngine.Result): OmniboxSuggestion = when (result) {
        is NexoCommandEngine.Result.PrefixHint -> OmniboxSuggestion.PrefixHint(result.scope)
        is NexoCommandEngine.Result.OpenTab -> OmniboxSuggestion.OpenTab(result.tabId, result.title, result.url, result.group, result.isPrivate)
        is NexoCommandEngine.Result.OpenGroup -> OmniboxSuggestion.OpenGroup(result.groupName, result.tabCount, result.firstTabId)
        is NexoCommandEngine.Result.Bookmark -> OmniboxSuggestion.Bookmark(result.title, result.url, result.folder)
        is NexoCommandEngine.Result.Shortcut -> OmniboxSuggestion.Shortcut(result.title, result.url)
        is NexoCommandEngine.Result.Command -> OmniboxSuggestion.Command(result.spec)
        is NexoCommandEngine.Result.Setting -> OmniboxSuggestion.Setting(result.spec)
        is NexoCommandEngine.Result.History -> OmniboxSuggestion.History(result.title, result.url)
        is NexoCommandEngine.Result.RemoteSearch -> OmniboxSuggestion.Search(result.query)
    }

    private fun cleanHost(url: String): String =
        url.substringAfter("://").removePrefix("www.")

    private fun commandIcon(id: String): Int = when (id) {
        "new_tab" -> R.drawable.ic_nav_add
        "private_tab" -> R.drawable.ic_menu_private
        "burner_tab" -> R.drawable.ic_menu_burner
        "translate" -> R.drawable.ic_menu_translate
        "find_in_page" -> R.drawable.ic_menu_find
        "downloads" -> R.drawable.ic_menu_download
        "history" -> R.drawable.ic_menu_history
        "settings" -> R.drawable.ic_menu_settings
        "phantom_shield" -> R.drawable.ic_menu_shield
        "nexo_ai" -> R.drawable.ic_menu_ai
        "extensions" -> R.drawable.ic_menu_extensions
        "reader_mode" -> R.drawable.ic_menu_reader
        "bookmarks" -> R.drawable.ic_menu_bookmark
        "nav_customize", "tab_tools" -> R.drawable.ic_tab_tools
        else -> R.drawable.ic_menu_open
    }

    private data class RowVisual(
        val icon: Int,
        val title: String,
        val subtitle: String?,
        val badge: String,
        val tone: NexoUi.Tone,
        val a11y: String
    )

    private fun describe(item: OmniboxSuggestion): RowVisual = when (item) {
        is OmniboxSuggestion.PrefixHint -> RowVisual(
            R.drawable.ic_search, item.scope.prefix, item.scope.hint,
            item.scope.label, NexoUi.Tone.INFO, "Filtrar por ${item.scope.label}"
        )
        is OmniboxSuggestion.OpenTab -> RowVisual(
            if (item.isPrivate) R.drawable.ic_menu_private else R.drawable.ic_menu_open,
            item.title,
            item.group?.let { "$it · ${cleanHost(item.url)}" } ?: cleanHost(item.url),
            if (item.isPrivate) "Privada" else "Pestaña",
            if (item.isPrivate) NexoUi.Tone.INFO else NexoUi.Tone.ACCENT,
            "Cambiar a pestaña ${item.title}"
        )
        is OmniboxSuggestion.OpenGroup -> RowVisual(
            R.drawable.ic_menu_folder,
            item.groupName,
            "${item.tabCount} pestañas abiertas",
            "Grupo",
            NexoUi.Tone.INFO,
            "Abrir grupo ${item.groupName}"
        )
        is OmniboxSuggestion.Bookmark -> RowVisual(
            R.drawable.ic_menu_bookmark,
            item.title,
            if (item.folder.isNotBlank()) "${item.folder} · ${cleanHost(item.url)}" else cleanHost(item.url),
            "Favorito",
            NexoUi.Tone.SUCCESS,
            "Abrir favorito ${item.title}"
        )
        is OmniboxSuggestion.Shortcut -> RowVisual(
            R.drawable.ic_menu_pin,
            item.title,
            cleanHost(item.url),
            "Acceso",
            NexoUi.Tone.NEUTRAL,
            "Abrir acceso ${item.title}"
        )
        is OmniboxSuggestion.Command -> RowVisual(
            commandIcon(item.spec.id),
            item.spec.title,
            item.spec.subtitle,
            "Acción",
            NexoUi.Tone.ACCENT,
            "Ejecutar acción ${item.spec.title}"
        )
        is OmniboxSuggestion.Setting -> RowVisual(
            R.drawable.ic_menu_settings,
            item.spec.title,
            item.spec.section,
            "Ajuste",
            NexoUi.Tone.WARNING,
            "Abrir ajuste ${item.spec.title}"
        )
        is OmniboxSuggestion.History -> RowVisual(
            R.drawable.ic_menu_history,
            item.title,
            cleanHost(item.url),
            "Historial",
            NexoUi.Tone.NEUTRAL,
            "Abrir historial ${item.title}"
        )
        is OmniboxSuggestion.Search -> RowVisual(
            R.drawable.ic_search,
            item.query,
            null,
            "Web",
            NexoUi.Tone.NEUTRAL,
            "Buscar ${item.query}"
        )
    }

    private fun row(item: OmniboxSuggestion): View {
        val p = ThemeManager.palette(activity)
        val visual = describe(item)
        val (_, iconFg) = NexoUi.toneColors(p, visual.tone)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = NexoUi.dp(activity, 50f)
            setPadding(NexoUi.dp(activity, 12f), NexoUi.dp(activity, 6f), NexoUi.dp(activity, 12f), NexoUi.dp(activity, 6f))
            background = ThemeManager.ripple(activity, null, 14f)
            isClickable = true
            isFocusable = true
            contentDescription = visual.a11y
            setOnClickListener { onPick(item) }
            addView(ImageView(activity).apply {
                setImageResource(visual.icon)
                imageTintList = ColorStateList.valueOf(if (visual.tone == NexoUi.Tone.NEUTRAL) p.muted else iconFg)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(NexoUi.dp(activity, 20f), NexoUi.dp(activity, 20f)).apply {
                marginEnd = NexoUi.dp(activity, 12f)
            })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = visual.title; textSize = 14.5f; setTextColor(p.text); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                visual.subtitle?.let {
                    addView(TextView(activity).apply {
                        text = it; textSize = 11.5f; setTextColor(p.muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(NexoUi.statusPill(activity, visual.badge, visual.tone), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = NexoUi.dp(activity, 8f) })
        }
    }
}
