package com.nexo.browser

import java.text.Normalizer
import java.util.Locale

/**
 * Motor puro y comprobable en JVM para Nexo Command (buscador universal).
 *
 * Unifica pestañas abiertas, grupos, favoritos, accesos guardados, historial local,
 * ajustes y comandos del navegador con prefijos opcionales (@tabs, @history,
 * @actions, @settings, @bookmarks).
 *
 * Privacidad y seguridad:
 * - Ninguna consulta con prefijo `@` ni privada se envía nunca a buscadores externos.
 * - Las sugerencias remotas SOLO pueden generar resultados de búsqueda web y nunca
 *   pueden invocar acciones o ajustes privilegiados.
 */
object NexoCommandEngine {
    enum class Scope(val prefix: String, val label: String, val hint: String) {
        ALL("", "Todo", "Buscar en pestañas, historial, comandos y web"),
        TABS("@tabs", "Pestañas", "Cambiar a pestañas abiertas y grupos"),
        HISTORY("@history", "Historial", "Buscar en tu historial local"),
        ACTIONS("@actions", "Acciones", "Ejecutar comandos rápidos de Nexo"),
        SETTINGS("@settings", "Ajustes", "Ir directo a una configuración"),
        BOOKMARKS("@bookmarks", "Favoritos", "Abrir sitios guardados en favoritos")
    }

    data class ParsedQuery(
        val scope: Scope,
        val term: String,
        val isPrefixed: Boolean,
        val incompletePrefix: Boolean
    )

    data class TabCandidate(
        val id: String,
        val title: String,
        val url: String,
        val group: String?,
        val isPrivate: Boolean,
        val active: Boolean
    )

    data class BookmarkCandidate(
        val id: String,
        val title: String,
        val url: String,
        val folder: String = ""
    )

    data class ShortcutCandidate(
        val title: String,
        val url: String
    )

    data class HistoryCandidate(
        val title: String,
        val url: String
    )

    data class CommandSpec(
        val id: String,
        val title: String,
        val subtitle: String,
        val keywords: List<String>,
        val requiresWebPage: Boolean = false
    )

    data class SettingSpec(
        val key: String,
        val title: String,
        val section: String,
        val query: String,
        val keywords: List<String>
    )

    sealed class Result {
        data class PrefixHint(val scope: Scope) : Result()
        data class OpenTab(
            val tabId: String,
            val title: String,
            val url: String,
            val group: String?,
            val isPrivate: Boolean
        ) : Result()
        data class OpenGroup(val groupName: String, val tabCount: Int, val firstTabId: String) : Result()
        data class Bookmark(val title: String, val url: String, val folder: String) : Result()
        data class Shortcut(val title: String, val url: String) : Result()
        data class Command(val spec: CommandSpec) : Result()
        data class Setting(val spec: SettingSpec) : Result()
        data class History(val title: String, val url: String) : Result()
        data class RemoteSearch(val query: String) : Result()
    }

    val SCOPED_PREFIXES = listOf(
        Scope.TABS,
        Scope.HISTORY,
        Scope.ACTIONS,
        Scope.SETTINGS,
        Scope.BOOKMARKS
    )

    val COMMANDS: List<CommandSpec> = listOf(
        CommandSpec("new_tab", "Nueva pestaña", "Abrir una pestaña normal", listOf("nueva", "pestana", "tab", "new")),
        CommandSpec("private_tab", "Modo privado", "Abrir pestaña privada sin historial", listOf("privada", "privado", "incognito", "private")),
        CommandSpec("burner_tab", "Nueva identidad Burner", "Pestaña aislada con correo temporal", listOf("burner", "identidad", "correo", "temporal")),
        CommandSpec("translate", "Traducir página", "Traducir el contenido actual", listOf("traducir", "translate", "idioma"), requiresWebPage = true),
        CommandSpec("find_in_page", "Buscar en página", "Localizar texto en la página abierta", listOf("buscar", "find", "pagina", "texto"), requiresWebPage = true),
        CommandSpec("downloads", "Abrir descargas", "Ver archivos descargados", listOf("descargas", "downloads", "archivos")),
        CommandSpec("history", "Abrir historial", "Explorar páginas visitadas", listOf("historial", "history", "recientes")),
        CommandSpec("settings", "Abrir ajustes", "Configurar Nexo Browser", listOf("ajustes", "configuracion", "settings", "preferencias")),
        CommandSpec("phantom_shield", "Abrir PhantomShield", "Controlar bloqueador y privacidad", listOf("phantom", "shield", "bloqueador", "anuncios", "privacidad")),
        CommandSpec("nexo_ai", "Abrir Nexo AI", "Asistente contextual para página y pestañas", listOf("ai", "ia", "asistente", "resumen", "nexo ai")),
        CommandSpec("extensions", "Administrar extensiones", "Abrir Nexo Extension Hub y complementos", listOf("extensiones", "extensions", "complementos", "addons", "hub")),
        CommandSpec("nav_customize", "Personalizar navegación", "Cambiar entre modo Clásico, Compacto y Personalizado", listOf("navegacion", "barra", "dock", "compacto", "personalizar")),
        CommandSpec("tab_tools", "Control del sitio y pestaña", "Permisos, herramientas, proxy y grupos", listOf("sitio", "permisos", "herramientas", "proxy", "site")),
        CommandSpec("reader_mode", "Activar modo lectura", "Vista limpia, tipografía y lectura en voz alta", listOf("lectura", "reader", "leer", "articulo", "voz", "tts"), requiresWebPage = true),
        CommandSpec("offline_articles", "Artículos sin conexión", "Abrir artículos guardados en Nexo Reader", listOf("offline", "sin conexion", "guardados", "reader", "articulos"))
    )

    val SETTINGS: List<SettingSpec> = listOf(
        SettingSpec("theme", "Tema y color de acento", "Apariencia", "tema", listOf("tema", "oscuro", "claro", "oled", "color", "dinamico", "apariencia")),
        SettingSpec("search", "Motor de búsqueda", "General", "buscador", listOf("buscador", "motor", "google", "duckduckgo", "startpage", "buscar")),
        SettingSpec("dns", "DNS seguro (DoH)", "Privacidad", "dns", listOf("dns", "doh", "cloudflare", "quad9", "adguard")),
        SettingSpec("cookies", "Política de cookies", "Privacidad", "cookies", listOf("cookies", "terceros", "sitio", "rastreo")),
        SettingSpec("tracking", "Protección contra rastreo", "Privacidad", "rastreo", listOf("rastreo", "tracking", "gpc", "fingerprinting", "privacidad")),
        SettingSpec("https", "Solo HTTPS", "Privacidad", "https", listOf("https", "cifrado", "seguridad", "conexion")),
        SettingSpec("media", "Mini reproductor y segundo plano", "Multimedia", "reproductor", listOf("pip", "video", "audio", "segundo plano", "multimedia")),
        SettingSpec("captions", "Subtítulos en vivo", "Multimedia", "subtitulos", listOf("subtitulos", "captions", "cc", "transcripcion")),
        SettingSpec("translator", "Idioma del traductor", "Idioma", "traductor", listOf("traductor", "idioma", "espanol", "ingles")),
        SettingSpec("text", "Tamaño de texto y zoom", "Accesibilidad", "texto", listOf("texto", "zoom", "fuente", "tamano", "accesibilidad")),
        SettingSpec("downloads", "Descargas paralelas", "Descargas", "descargas", listOf("descargas", "paralelas", "archivos")),
        SettingSpec("transfer", "Transferir o respaldar perfil", "Datos", "perfil", listOf("perfil", "transferir", "exportar", "importar", "backup"))
    )

    private val PREFIX_ALIASES: Map<String, Scope> = mapOf(
        "@tabs" to Scope.TABS,
        "@tab" to Scope.TABS,
        "@pestanas" to Scope.TABS,
        "@history" to Scope.HISTORY,
        "@historial" to Scope.HISTORY,
        "@actions" to Scope.ACTIONS,
        "@action" to Scope.ACTIONS,
        "@acciones" to Scope.ACTIONS,
        "@comandos" to Scope.ACTIONS,
        "@settings" to Scope.SETTINGS,
        "@ajustes" to Scope.SETTINGS,
        "@config" to Scope.SETTINGS,
        "@bookmarks" to Scope.BOOKMARKS,
        "@bookmark" to Scope.BOOKMARKS,
        "@favoritos" to Scope.BOOKMARKS
    )

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) append(ch)
            }
        }
    }

    fun parseQuery(raw: String): ParsedQuery {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("@")) {
            return ParsedQuery(Scope.ALL, trimmed, isPrefixed = false, incompletePrefix = false)
        }
        val firstSpace = trimmed.indexOf(' ')
        val token = normalize(if (firstSpace < 0) trimmed else trimmed.substring(0, firstSpace))
        val exact = PREFIX_ALIASES[token]
        if (exact != null) {
            val rest = if (firstSpace < 0) "" else trimmed.substring(firstSpace + 1).trim()
            return ParsedQuery(exact, rest, isPrefixed = true, incompletePrefix = false)
        }
        if (firstSpace < 0) {
            return ParsedQuery(Scope.ALL, token, isPrefixed = true, incompletePrefix = true)
        }
        return ParsedQuery(Scope.ALL, trimmed, isPrefixed = true, incompletePrefix = false)
    }

    /**
     * Decide si es seguro consultar sugerencias de un buscador remoto.
     * Las consultas con `@`, consultas privadas/URLs locales o pestañas aisladas nunca salen a la red.
     */
    fun allowsRemoteSuggestions(raw: String, remoteAllowed: Boolean): Boolean {
        if (!remoteAllowed) return false
        val trimmed = raw.trim()
        if (trimmed.length < 2) return false
        if (trimmed.startsWith("@")) return false
        if (UrlSafety.isPrivateQuery(trimmed)) return false
        return true
    }

    /**
     * Convierte sugerencias remotas únicamente en búsquedas web seguras.
     * Nunca permite que un servidor remoto inyecte o dispare comandos locales.
     */
    fun remoteResults(values: List<String>, rawQuery: String, remoteAllowed: Boolean, max: Int = 2): List<Result.RemoteSearch> {
        if (!allowsRemoteSuggestions(rawQuery, remoteAllowed)) return emptyList()
        return values.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("@") }
            .distinct()
            .take(max)
            .map { Result.RemoteSearch(it) }
            .toList()
    }

    fun searchLocal(
        raw: String,
        tabs: List<TabCandidate>,
        bookmarks: List<BookmarkCandidate>,
        shortcuts: List<ShortcutCandidate>,
        history: List<HistoryCandidate>,
        onWebPage: Boolean,
        commands: List<CommandSpec> = COMMANDS,
        settings: List<SettingSpec> = SETTINGS,
        maxResults: Int = 8
    ): List<Result> {
        val parsed = parseQuery(raw)
        if (parsed.incompletePrefix) {
            val prefixToken = parsed.term // normalized, e.g. "@" or "@ta"
            return SCOPED_PREFIXES.filter {
                prefixToken == "@" || it.prefix.startsWith(prefixToken) || normalize(it.label).startsWith(prefixToken.removePrefix("@"))
            }.map { Result.PrefixHint(it) }.take(maxResults)
        }

        val queryNorm = normalize(parsed.term)
        val tokens = queryNorm.split(' ').filter { it.isNotBlank() }

        fun matchScore(vararg fields: String): Int {
            if (tokens.isEmpty()) return if (parsed.isPrefixed) 10 else 0
            val normalizedFields = fields.map { normalize(it) }.filter { it.isNotEmpty() }
            if (normalizedFields.isEmpty()) return 0
            val combined = normalizedFields.joinToString(" ")
            if (!tokens.all { combined.contains(it) }) return 0
            var score = 20
            val primary = normalizedFields.first()
            if (primary == queryNorm) score += 80
            else if (primary.startsWith(queryNorm)) score += 50
            else if (normalizedFields.any { it.startsWith(queryNorm) }) score += 35
            return score
        }

        val results = mutableListOf<Pair<Int, Result>>()

        if (parsed.scope == Scope.ALL || parsed.scope == Scope.TABS) {
            val tabLimit = if (parsed.scope == Scope.TABS) maxResults else 2
            val matchedTabs = tabs
                .filterNot { it.active && !parsed.isPrefixed }
                .mapNotNull { tab ->
                    val s = matchScore(tab.title, tab.url, tab.group.orEmpty())
                    if (s > 0) (s + 30) to Result.OpenTab(tab.id, tab.title.ifBlank { tab.url }, tab.url, tab.group, tab.isPrivate) else null
                }
                .sortedByDescending { it.first }
                .take(tabLimit)
            results.addAll(matchedTabs)

            val groupLimit = if (parsed.scope == Scope.TABS) 3 else 1
            val matchedGroups = tabs
                .filter { !it.group.isNullOrBlank() }
                .groupBy { it.group!! }
                .mapNotNull { (groupName, members) ->
                    val s = matchScore(groupName, members.joinToString(" ") { it.title })
                    if (s > 0) (s + 24) to Result.OpenGroup(groupName, members.size, members.first().id) else null
                }
                .sortedByDescending { it.first }
                .take(groupLimit)
            results.addAll(matchedGroups)
        }

        if (parsed.scope == Scope.ALL || parsed.scope == Scope.BOOKMARKS) {
            val limit = if (parsed.scope == Scope.BOOKMARKS) maxResults else 2
            val matched = bookmarks.mapNotNull { bm ->
                val s = matchScore(bm.title, bm.url, bm.folder)
                if (s > 0) (s + 26) to Result.Bookmark(bm.title.ifBlank { bm.url }, bm.url, bm.folder) else null
            }.sortedByDescending { it.first }.take(limit)
            results.addAll(matched)
        }

        if (parsed.scope == Scope.ALL) {
            val matchedShortcuts = shortcuts.mapNotNull { sc ->
                val s = matchScore(sc.title, sc.url)
                if (s > 0) (s + 18) to Result.Shortcut(sc.title, sc.url) else null
            }.sortedByDescending { it.first }.take(2)
            results.addAll(matchedShortcuts)
        }

        if (parsed.scope == Scope.ALL || parsed.scope == Scope.ACTIONS) {
            val limit = if (parsed.scope == Scope.ACTIONS) maxResults else 2
            val minLenOk = parsed.scope == Scope.ACTIONS || queryNorm.length >= 2
            if (minLenOk) {
                val matched = commands
                    .filter { onWebPage || !it.requiresWebPage }
                    .mapNotNull { cmd ->
                        val s = matchScore(cmd.title, cmd.keywords.joinToString(" "), cmd.subtitle)
                        if (s > 0) (s + if (parsed.scope == Scope.ACTIONS) 40 else 22) to Result.Command(cmd) else null
                    }
                    .sortedByDescending { it.first }
                    .take(limit)
                results.addAll(matched)
            }
        }

        if (parsed.scope == Scope.ALL || parsed.scope == Scope.SETTINGS) {
            val limit = if (parsed.scope == Scope.SETTINGS) maxResults else 2
            val minLenOk = parsed.scope == Scope.SETTINGS || queryNorm.length >= 2
            if (minLenOk) {
                val matched = settings.mapNotNull { spec ->
                    val s = matchScore(spec.title, spec.keywords.joinToString(" "), spec.section)
                    if (s > 0) (s + if (parsed.scope == Scope.SETTINGS) 35 else 15) to Result.Setting(spec) else null
                }.sortedByDescending { it.first }.take(limit)
                results.addAll(matched)
            }
        }

        if (parsed.scope == Scope.ALL || parsed.scope == Scope.HISTORY) {
            val limit = if (parsed.scope == Scope.HISTORY) maxResults else 3
            val seenUrls = results.mapNotNull { (_, r) ->
                when (r) {
                    is Result.Bookmark -> r.url
                    is Result.Shortcut -> r.url
                    else -> null
                }
            }.toHashSet()
            val matched = history.mapNotNull { item ->
                if (item.url in seenUrls) return@mapNotNull null
                val s = matchScore(item.title, item.url)
                if (s > 0) (s + 12) to Result.History(item.title.ifBlank { item.url }, item.url) else null
            }.sortedByDescending { it.first }.take(limit)
            results.addAll(matched)
        }

        return results.sortedByDescending { it.first }.map { it.second }.take(maxResults)
    }
}
