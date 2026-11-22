package com.nexo.browser.extensions

import com.google.re2j.Pattern

/**
 * Patrón de coincidencia de extensiones (`matches`, permisos de host), con la
 * semántica de Chrome: `<all_urls>`, `*://` (http y https), `*.dominio`
 * (incluye el dominio) y ruta con `*` comparada contra ruta + consulta.
 * Motor lineal RE2J: inmune a ReDoS por comodines repetidos en manifiestos de terceros.
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
    private val path: Pattern
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
        return path.matcher(parsed.pathAndQuery).matches()
    }

    override fun toString(): String = raw

    companion object {
        const val ALL_URLS = "<all_urls>"
        private val WEB_SCHEMES = setOf("http", "https")
        private val ALL_SCHEMES = setOf("http", "https", "file", "ftp")
        private val EXPLICIT_SCHEMES = setOf("http", "https", "file", "ftp")
        private val MATCH_ALL = Pattern.compile("(?s).*")
        private val IPV6_RE = Regex("""\[[0-9a-f:.]+]""")
        private val LABEL_RE = Regex("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?")

        /** Devuelve `null` si el patrón no es válido (se ignora, como hace Chrome con un aviso). */
        fun parse(pattern: String): MatchPattern? {
            val text = pattern.trim()
            if (text.isEmpty() || text.length > 2048) return null
            if (text == ALL_URLS) return MatchPattern(text, ALL_SCHEMES, null, false, null, MATCH_ALL)

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
                return if (authority.isEmpty()) MatchPattern(text, schemes, null, false, null, globToPattern(pathPattern)) else null
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
                hostPart == "*" -> MatchPattern(text, schemes, null, false, port, globToPattern(pathPattern))
                hostPart.startsWith("*.") -> {
                    val base = hostPart.substring(2)
                    if (!validHost(base)) null else MatchPattern(text, schemes, base, true, port, globToPattern(pathPattern))
                }
                hostPart.contains('*') || !validHost(hostPart) -> null
                else -> MatchPattern(text, schemes, hostPart, false, port, globToPattern(pathPattern))
            }
        }

        private fun validHost(host: String): Boolean =
            host.isNotEmpty() && host.length <= 253 &&
                (host.matches(IPV6_RE) ||
                    host.split('.').all { label ->
                        label.isNotEmpty() && label.length <= 63 && label.matches(LABEL_RE)
                    })

        /** `*` = cualquier secuencia; el resto, literal (compilado en RE2J en tiempo lineal). */
        internal fun globToPattern(glob: String, questionMark: Boolean = false): Pattern {
            val out = StringBuilder("(?s)")
            var prevStar = false
            for (char in glob) {
                when {
                    char == '*' -> {
                        if (!prevStar) out.append(".*")
                        prevStar = true
                    }
                    questionMark && char == '?' -> {
                        out.append('.')
                        prevStar = false
                    }
                    else -> {
                        out.append(Pattern.quote(char.toString()))
                        prevStar = false
                    }
                }
            }
            return Pattern.compile(out.toString())
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
    private val includeGlobs = includeGlobs.map { MatchPattern.globToPattern(it, questionMark = true) }
    private val excludeGlobs = excludeGlobs.map { MatchPattern.globToPattern(it, questionMark = true) }

    val isEmpty: Boolean get() = include.isEmpty()

    fun matches(url: String): Boolean {
        if (include.none { it.matches(url) }) return false
        if (exclude.any { it.matches(url) }) return false
        if (includeGlobs.isNotEmpty() && includeGlobs.none { it.matcher(url).matches() }) return false
        if (excludeGlobs.any { it.matcher(url).matches() }) return false
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
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '\\' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
            var tail = rest.substring(authorityEnd)
            if (tail.startsWith("\\")) tail = "/" + tail.substring(1).replace('\\', '/')
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
