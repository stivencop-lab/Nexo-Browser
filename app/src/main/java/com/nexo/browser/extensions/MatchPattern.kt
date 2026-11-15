package com.nexo.browser.extensions

/**
 * Patrón de coincidencia de extensiones (`matches`, permisos de host), con la
 * semántica de Chrome: `<all_urls>`, `*://` (http y https), `*.dominio`
 * (incluye el dominio) y ruta con `*` comparada contra ruta + consulta.
 * Puro: probado en la JVM.
 */
class MatchPattern private constructor(
    val raw: String,
    private val schemes: Set<String>,
    /** `null`: cualquier host. */
    private val host: String?,
    private val subdomains: Boolean,
    /** `null`: cualquier puerto. */
    private val port: Int?,
    private val path: Regex
) {
    val matchesAllHosts: Boolean get() = host == null

    fun matches(url: String): Boolean {
        val parsed = ParsedUrl.parse(url) ?: return false
        // Los WebSocket se rigen por los permisos de su equivalente HTTP.
        val effective = when (parsed.scheme) {
            "ws" -> "http"
            "wss" -> "https"
            else -> parsed.scheme
        }
        if (effective !in schemes) return false
        if (host != null) {
            val hostMatches = parsed.host == host || (subdomains && parsed.host.endsWith(".$host"))
            if (!hostMatches) return false
        }
        if (port != null && parsed.port(effective) != port) return false
        return path.matches(parsed.pathAndQuery)
    }

    override fun toString(): String = raw

    companion object {
        const val ALL_URLS = "<all_urls>"
        private val WEB_SCHEMES = setOf("http", "https")
        private val ALL_SCHEMES = setOf("http", "https", "file", "ftp")
        private val EXPLICIT_SCHEMES = setOf("http", "https", "file", "ftp")

        /** Devuelve `null` si el patrón no es válido (se ignora, como hace Chrome con un aviso). */
        fun parse(pattern: String): MatchPattern? {
            val text = pattern.trim()
            if (text.isEmpty() || text.length > 2048) return null
            if (text == ALL_URLS) return MatchPattern(text, ALL_SCHEMES, null, false, null, Regex(".*"))

            val separator = text.indexOf("://")
            if (separator <= 0) return null
            val scheme = text.substring(0, separator).lowercase()
            val schemes = when (scheme) {
                "*" -> WEB_SCHEMES
                in EXPLICIT_SCHEMES -> setOf(scheme)
                else -> return null
            }
            val rest = text.substring(separator + 3)
            val slash = rest.indexOf('/')
            if (slash < 0) return null
            val authority = rest.substring(0, slash)
            val pathPattern = rest.substring(slash)

            if (scheme == "file") {
                return if (authority.isEmpty()) MatchPattern(text, schemes, null, false, null, globToRegex(pathPattern)) else null
            }
            if (authority.isEmpty()) return null

            var hostPart = authority
            var port: Int? = null
            val colon = authority.lastIndexOf(':')
            if (colon > 0 && !authority.endsWith("]")) {
                val portText = authority.substring(colon + 1)
                hostPart = authority.substring(0, colon)
                port = when {
                    portText == "*" -> null
                    portText.isNotEmpty() && portText.all { it.isDigit() } && portText.length <= 5 ->
                        portText.toInt().takeIf { it in 1..65535 } ?: return null
                    else -> return null
                }
            }
            hostPart = hostPart.lowercase().trimEnd('.')

            return when {
                hostPart == "*" -> MatchPattern(text, schemes, null, false, port, globToRegex(pathPattern))
                hostPart.startsWith("*.") -> {
                    val base = hostPart.substring(2)
                    if (!validHost(base)) null else MatchPattern(text, schemes, base, true, port, globToRegex(pathPattern))
                }
                hostPart.contains('*') || !validHost(hostPart) -> null
                else -> MatchPattern(text, schemes, hostPart, false, port, globToRegex(pathPattern))
            }
        }

        private fun validHost(host: String): Boolean =
            host.isNotEmpty() && host.length <= 253 &&
                (host.matches(Regex("""\[[0-9a-f:.]+]""")) ||
                    host.split('.').all { label ->
                        label.isNotEmpty() && label.length <= 63 && label.matches(Regex("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?"))
                    })

        /** `*` = cualquier secuencia; el resto, literal. */
        internal fun globToRegex(glob: String, questionMark: Boolean = false): Regex {
            val out = StringBuilder()
            for (char in glob) {
                when {
                    char == '*' -> out.append(".*")
                    questionMark && char == '?' -> out.append('.')
                    else -> out.append(Regex.escape(char.toString()))
                }
            }
            return Regex(out.toString(), RegexOption.DOT_MATCHES_ALL)
        }
    }
}

/**
 * Regla completa de un script de contenido: `matches`, `exclude_matches`,
 * `include_globs` y `exclude_globs` (estos sobre la URL entera, con `*` y `?`).
 */
class UrlMatcher(
    matches: List<String>,
    excludeMatches: List<String> = emptyList(),
    includeGlobs: List<String> = emptyList(),
    excludeGlobs: List<String> = emptyList()
) {
    private val include = matches.mapNotNull { MatchPattern.parse(it) }
    private val exclude = excludeMatches.mapNotNull { MatchPattern.parse(it) }
    private val includeGlobs = includeGlobs.map { MatchPattern.globToRegex(it, questionMark = true) }
    private val excludeGlobs = excludeGlobs.map { MatchPattern.globToRegex(it, questionMark = true) }

    val isEmpty: Boolean get() = include.isEmpty()

    fun matches(url: String): Boolean {
        if (include.none { it.matches(url) }) return false
        if (exclude.any { it.matches(url) }) return false
        if (includeGlobs.isNotEmpty() && includeGlobs.none { it.matches(url) }) return false
        if (excludeGlobs.any { it.matches(url) }) return false
        return true
    }
}

/** Partes de una URL sin las excepciones de `java.net.URI` con caracteres que los navegadores aceptan. */
internal class ParsedUrl(val scheme: String, val host: String, private val explicitPort: Int?, val pathAndQuery: String) {
    fun port(scheme: String): Int = explicitPort ?: when (scheme) {
        "https" -> 443
        "http" -> 80
        "ftp" -> 21
        else -> -1
    }

    companion object {
        fun parse(url: String): ParsedUrl? {
            if (url.length > 8192) return null
            val separator = url.indexOf("://")
            if (separator <= 0) return null
            val scheme = url.substring(0, separator).lowercase()
            if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
            val rest = url.substring(separator + 3).substringBefore('#')
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
            var tail = rest.substring(authorityEnd)
            if (tail.startsWith("?")) tail = "/$tail"
            if (tail.isEmpty()) tail = "/"
            var host = authority
            var port: Int? = null
            val colon = authority.lastIndexOf(':')
            if (colon >= 0 && !authority.endsWith("]")) {
                host = authority.substring(0, colon)
                val portText = authority.substring(colon + 1)
                if (portText.isNotEmpty()) port = portText.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
            }
            if (host.isEmpty() && scheme != "file") return null
            return ParsedUrl(scheme, host.lowercase().trimEnd('.'), port, tail)
        }
    }
}
