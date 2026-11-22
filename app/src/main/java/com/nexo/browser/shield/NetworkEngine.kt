package com.nexo.browser.shield

import com.google.re2j.Pattern
import java.util.concurrent.atomic.AtomicLong

/**
 * Motor de reglas de red de PhantomShield en Kotlin: traducción fiel de
 * `background/network-engine.js` (mismo subconjunto ABP, mismos índices y
 * mismas decisiones), para evaluarlo en el interceptor de red sin pasar
 * por JavaScript en cada petición.
 *
 * Se construye en un hilo (listas) y después solo se lee desde varios hilos
 * de red de Chromium: un motor nuevo sustituye al anterior entero.
 */
internal class NetworkEngine {
    internal class Rule(
        val key: String,
        val flags: Set<String>,
        val domainOption: String?,
        val plain: Boolean,
        val isException: Boolean,
        val domainAnchor: Boolean,
        val leftAnchor: Boolean,
        val rightAnchor: Boolean,
        val pattern: String,
        val simpleDomain: String?
    ) {
        var raw: String? = null
        @Volatile var regex: Pattern? = null
        @Volatile var dead = false
        val important: Boolean get() = "important" in flags
        val popup: Boolean get() = "popup" in flags
    }

    private class Node {
        val children = HashMap<String, Node>(2)
        val rules = ArrayList<Rule>(1)
    }

    private var domainTrie = Node()
    private val tokenIndex = HashMap<String, MutableList<Rule>>()
    private val exceptionDomainTrie = Node()
    private val exceptionTokenIndex = HashMap<String, MutableList<Rule>>()
    private val globalExceptionRules = ArrayList<Rule>()
    internal val plainDomains = HashSet<String>()
    private val popupRules = ArrayList<Rule>()
    private val popupExceptions = ArrayList<Rule>()
    private val importantRules = ArrayList<Rule>()
    private var importantTrie: Node? = null
    private val badFilters = HashSet<String>()
    internal var ruleKeys: HashSet<String>? = HashSet()
        private set
    private var ruleTotal = 0

    private val blocked = AtomicLong(0)

    fun ruleCount(): Int = ruleTotal

    /** Clave normalizada (opciones ordenadas, sin `badfilter`), como `ruleKey` en JS. */
    fun ruleKey(raw: String): String {
        val text = raw.trim()
        val index = text.indexOf('$')
        if (index == -1) return text.lowercase()
        val options = text.substring(index + 1).split(',').map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "badfilter" }.sorted()
        return (text.substring(0, index) + if (options.isNotEmpty()) "$" + options.joinToString(",") else "").lowercase()
    }

    fun addBadFilter(raw: String): Boolean {
        if (!BADFILTER.containsMatchIn(raw)) return false
        badFilters.add(ruleKey(raw))
        return true
    }

    /** Libera el índice de duplicados, que solo hace falta mientras se cargan listas. */
    fun finalizeRules() {
        ruleKeys = null
    }

    fun init(adDomains: List<String>, trackerDomains: List<String>, popupDomains: List<String>): Int {
        var added = 0
        for (entry in adDomains + trackerDomains + popupDomains) {
            if (entry.isBlank()) continue
            val source = entry.trim()
            if (addRule(createRule(if (source.startsWith("@@")) source else "||$source^"))) added++
        }
        return added
    }

    private class Options(val flags: MutableSet<String> = HashSet(), var domain: String? = null) {
        val keys: List<String> get() = flags.toList() + listOfNotNull(domain?.let { "domain" })
    }

    private fun parseOptions(text: String, extraKeys: MutableList<String>): Options {
        val options = Options()
        if (text.isEmpty()) return options
        for (rawOption in text.split(',')) {
            val option = rawOption.trim().lowercase()
            if (option.isEmpty()) continue
            val separator = option.indexOf('=')
            if (separator == -1) {
                when (option) {
                    "1p" -> options.flags.add("~third-party")
                    "~1p" -> options.flags.add("third-party")
                    else -> {
                        val negative = if (option.startsWith("~")) "~" else ""
                        val base = if (negative.isNotEmpty()) option.substring(1) else option
                        val alias = OPTION_ALIASES[base]
                        options.flags.add(if (alias != null) (if (alias.startsWith("~")) alias else negative + alias) else option)
                    }
                }
            } else {
                val key = option.substring(0, separator)
                val value = option.substring(separator + 1)
                if (key == "domain" || key == "from") options.domain = value else extraKeys.add(key)
            }
        }
        return options
    }

    fun createRule(rawString: String?): Rule? {
        var raw = rawString?.trim() ?: return null
        if (raw.isEmpty() || raw.length > 4096 || raw.startsWith("!") || raw.startsWith("[")) return null
        if (BADFILTER.containsMatchIn(raw)) return null
        if ("##" in raw || "#@#" in raw || "#?#" in raw) return null

        val optionIndex = raw.indexOf('$')
        val optionText = if (optionIndex == -1) "" else raw.substring(optionIndex + 1)
        var body = if (optionIndex == -1) raw else raw.substring(0, optionIndex)
        val extraKeys = ArrayList<String>()
        val options = parseOptions(optionText, extraKeys)

        for (key in options.keys + extraKeys) {
            val baseKey = key.removePrefix("~")
            val supported = baseKey in REQUEST_TYPES || key == "third-party" || key == "~third-party" ||
                key == "domain" || key == "important"
            if (baseKey in UNSUPPORTED_OPTIONS || !supported) return null
        }

        val isException = body.startsWith("@@")
        if (isException) body = body.substring(2)
        val domainAnchor = body.startsWith("||")
        if (domainAnchor) body = body.substring(2)
        val leftAnchor = !domainAnchor && body.startsWith("|")
        if (leftAnchor) body = body.substring(1)
        val rightAnchor = body.endsWith("|")
        if (rightAnchor) body = body.substring(0, body.length - 1)
        if (body.isEmpty() || (body.startsWith("/") && body.endsWith("/"))) return null

        val hasOptions = options.flags.isNotEmpty() || options.domain != null
        var simpleDomain: String? = null
        if (domainAnchor) {
            SIMPLE_DOMAIN.matchEntire(body)?.let { match ->
                val domain = match.groupValues[1].lowercase().trim('.')
                if (!isValidHostname(domain)) return null
                simpleDomain = domain
            }
        }
        if (simpleDomain == null && domainAnchor) {
            val boundary = body.indexOfFirst { it == '/' || it == '^' }
            val host = if (boundary == -1) body else body.substring(0, boundary)
            if (!isValidHostname(host.replace('*', 'a'))) return null
        }
        return Rule(
            key = ruleKey(raw),
            flags = options.flags,
            domainOption = options.domain,
            plain = !hasOptions,
            isException = isException,
            domainAnchor = domainAnchor,
            leftAnchor = leftAnchor,
            rightAnchor = rightAnchor,
            pattern = body,
            simpleDomain = simpleDomain
        ).also { it.raw = raw }
    }

    private fun test(rule: Rule, url: String): Boolean {
        if (rule.dead) return false
        val regex = rule.regex ?: try {
            compilePattern(rule).also { rule.regex = it }
        } catch (_: Exception) {
            rule.dead = true
            return false
        }
        return regex.matcher(url).find()
    }

    internal fun isCompiled(rule: Rule): Boolean = rule.regex != null

    private fun compilePattern(rule: Rule): Pattern {
        fun escapePart(value: String): String = buildString {
            for (char in value) {
                when (char) {
                    '*' -> append(".*")
                    '^' -> append("(?:[^a-z0-9_.%-]|$)")
                    '\\', '$', '.', '+', '?', '(', ')', '[', ']', '{', '}', '|' -> append('\\').append(char)
                    else -> append(char)
                }
            }
        }
        val expression = if (rule.domainAnchor) {
            val boundary = rule.pattern.indexOfFirst { it == '/' || it == '^' }
            val host = if (boundary == -1) rule.pattern else rule.pattern.substring(0, boundary)
            val rest = if (boundary == -1) "" else rule.pattern.substring(boundary)
            require(isValidHostname(host.replace('*', 'a'))) { "Invalid host" }
            // RE2J guarantees linear-time matching without backtracking (no lookahead needed).
            val suffix = when {
                rest.isEmpty() -> "(?:[:/?#]|$)"
                rest.startsWith("^") -> "(?:[:/?#]|$)" + escapePart(rest.substring(1))
                else -> escapePart(rest)
            }
            "^[a-z][a-z0-9+.-]*://(?:[^./?#]+\\.)*" + escapePart(host) + suffix
        } else {
            (if (rule.leftAnchor) "^" else "") + escapePart(rule.pattern) + (if (rule.rightAnchor) "$" else "")
        }
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE)
    }

    fun addRule(rule: Rule?): Boolean {
        if (rule == null || rule.pattern.isEmpty()) return false
        if (badFilters.isNotEmpty() && rule.key in badFilters) return false
        val key = (rule.raw ?: return false).lowercase()
        val keys = ruleKeys ?: HashSet<String>().also { ruleKeys = it }
        if (!keys.add(key)) return false
        rule.raw = null

        if (rule.popup && rule.flags.count { it in REQUEST_TYPES } == 1) {
            (if (rule.isException) popupExceptions else popupRules).add(rule)
            ruleTotal++
            return true
        }
        if (!rule.isException && rule.important) {
            val domain = rule.simpleDomain
            if (domain != null) insert(domain, rule, importantTrie ?: Node().also { importantTrie = it })
            else importantRules.add(rule)
            ruleTotal++
            return true
        }
        if (!rule.isException && rule.simpleDomain != null && rule.plain) {
            plainDomains.add(rule.simpleDomain)
            ruleTotal++
            return true
        }
        val stored = store(rule)
        if (stored) ruleTotal++
        return stored
    }

    private fun tokensOf(pattern: String): List<String> =
        pattern.split(NON_WORD).filter { it.length >= 4 }.sortedByDescending { it.length }

    private fun store(rule: Rule): Boolean {
        val domain = rule.simpleDomain
        if (rule.isException) {
            if (domain != null) {
                insert(domain, rule, exceptionDomainTrie)
                return true
            }
            val tokens = tokensOf(rule.pattern)
            if (tokens.isEmpty()) {
                globalExceptionRules.add(rule)
                return true
            }
            exceptionTokenIndex.getOrPut(tokens[0].lowercase()) { ArrayList(1) }.add(rule)
            return true
        }
        if (domain != null) {
            insert(domain, rule, domainTrie)
            return true
        }
        val tokens = tokensOf(rule.pattern)
        if (tokens.isEmpty()) return false
        tokenIndex.getOrPut(tokens[0].lowercase()) { ArrayList(1) }.add(rule)
        return true
    }

    private fun insert(domain: String, rule: Rule, root: Node) {
        var node = root
        for (part in domain.split('.').asReversed()) node = node.children.getOrPut(part) { Node() }
        node.rules.add(rule)
    }

    private fun hostnameMatches(hostname: String, ruleDomain: String): Boolean =
        hostname == ruleDomain || hostname.endsWith(".$ruleDomain")

    fun isThirdPartyRequest(url: String?, documentUrl: String?): Boolean {
        if (url.isNullOrEmpty() || documentUrl.isNullOrEmpty()) return true
        val a = UrlParts.host(url) ?: return true
        val b = UrlParts.host(documentUrl) ?: return true
        return siteKey(a) != siteKey(b)
    }

    private fun matchOptions(rule: Rule, requestType: String, isThirdParty: Boolean, documentDomain: String): Boolean {
        val flags = rule.flags
        if ("third-party" in flags && !isThirdParty) return false
        if ("~third-party" in flags && isThirdParty) return false
        val mappedType = mapRequestType(requestType)
        if (mappedType == UNKNOWN) {
            // Tipo desconocido: las reglas que dependen del tipo no se aplican.
            if (flags.any { it.removePrefix("~") in REQUEST_TYPES }) return false
        } else {
            if (flags.any { it in REQUEST_TYPES } && mappedType !in flags) return false
            if ("~$mappedType" in flags) return false
        }
        val domainOption = rule.domainOption
        if (domainOption != null) {
            val included = ArrayList<String>()
            val excluded = ArrayList<String>()
            for (rawDomain in domainOption.split('|')) {
                val domain = rawDomain.trim().lowercase()
                if (domain.isEmpty()) continue
                if (domain.startsWith("~")) excluded.add(domain.substring(1)) else included.add(domain)
            }
            if (excluded.any { hostnameMatches(documentDomain, it) }) return false
            if (included.isNotEmpty() && included.none { hostnameMatches(documentDomain, it) }) return false
        }
        return true
    }

    private fun mapRequestType(type: String): String = when (type) {
        "main_frame" -> "document"
        "sub_frame" -> "subdocument"
        "script", "image", "stylesheet", "xmlhttprequest", "media", "font", "object", "ping", "websocket", "popup" -> type
        "beacon" -> "ping"
        UNKNOWN -> UNKNOWN
        else -> "other"
    }

    private fun findDomainRules(hostname: String, root: Node?): List<Rule> {
        var node = root ?: return emptyList()
        var matches: ArrayList<Rule>? = null
        for (part in hostname.split('.').asReversed()) {
            node = node.children[part] ?: break
            if (node.rules.isNotEmpty()) (matches ?: ArrayList<Rule>().also { matches = it }).addAll(node.rules)
        }
        return matches ?: emptyList()
    }

    private fun matchesPlainDomain(hostname: String): Boolean {
        if (plainDomains.isEmpty()) return false
        var candidate = hostname
        while (candidate.isNotEmpty()) {
            if (candidate in plainDomains) return true
            val dot = candidate.indexOf('.')
            if (dot == -1) return false
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    private fun urlTokens(url: String): Set<String> =
        url.lowercase().split(NON_WORD).filterTo(LinkedHashSet()) { it.length >= 4 }

    private fun findExceptionRules(hostname: String, tokens: Set<String>): Collection<Rule> {
        val candidates = LinkedHashSet<Rule>(globalExceptionRules)
        candidates.addAll(findDomainRules(hostname, exceptionDomainTrie))
        for (token in tokens) exceptionTokenIndex[token]?.let { candidates.addAll(it) }
        return candidates
    }

    fun shouldBlock(url: String?, requestType: String, documentUrl: String?, countBlocked: Boolean = true): Boolean {
        if (url.isNullOrEmpty()) return false
        val hostname = UrlParts.host(url) ?: return false
        val documentDomain = if (documentUrl.isNullOrEmpty()) "" else UrlParts.host(documentUrl) ?: return false
        val isThirdParty = isThirdPartyRequest(url, documentUrl)

        if (requestType == "popup") {
            val matches = { rule: Rule ->
                (if (rule.simpleDomain != null) hostnameMatches(hostname, rule.simpleDomain) else test(rule, url)) &&
                    matchOptions(rule, "popup", isThirdParty, documentDomain)
            }
            if (popupExceptions.any(matches)) return false
            if (popupRules.any(matches)) return true
        }

        // `$important` gana a cualquier excepción.
        for (rule in findDomainRules(hostname, importantTrie) + importantRules) {
            val hit = rule.simpleDomain != null || test(rule, url)
            if (hit && matchOptions(rule, requestType, isThirdParty, documentDomain)) {
                if (countBlocked) recordBlocked()
                return true
            }
        }

        val tokens = urlTokens(url)
        for (rule in findExceptionRules(hostname, tokens)) {
            if ((rule.simpleDomain != null || test(rule, url)) && matchOptions(rule, requestType, isThirdParty, documentDomain)) {
                return false
            }
        }

        if (matchesPlainDomain(hostname)) {
            if (countBlocked) recordBlocked()
            return true
        }

        for (rule in findDomainRules(hostname, domainTrie)) {
            if (matchOptions(rule, requestType, isThirdParty, documentDomain)) {
                if (countBlocked) recordBlocked()
                return true
            }
        }

        for (token in tokens) {
            val rules = tokenIndex[token] ?: continue
            for (rule in rules) {
                if (test(rule, url) && matchOptions(rule, requestType, isThirdParty, documentDomain)) {
                    if (countBlocked) recordBlocked()
                    return true
                }
            }
        }

        if (hostname == "googlevideo.com" || hostname.endsWith(".googlevideo.com")) {
            if ("adformat=" in url || "ad_type=" in url || "ctier=A" in url) {
                if (countBlocked) recordBlocked()
                return true
            }
        }
        return false
    }

    fun isDomainBlocked(hostname: String?): Boolean {
        val normalized = hostname.orEmpty().lowercase().removeSuffix(".")
        if (!isValidHostname(normalized)) return false
        return matchesPlainDomain(normalized) || findDomainRules(normalized, domainTrie).isNotEmpty()
    }

    fun recordBlocked(amount: Long = 1) { blocked.addAndGet(amount.coerceAtLeast(1)) }
    fun resetBlockedCount() = blocked.set(0)
    fun setBlockedCount(value: Long) = blocked.set(value.coerceAtLeast(0))
    fun getBlockedCount(): Long = blocked.get()

    companion object {
        const val UNKNOWN = "unknown"

        val REQUEST_TYPES = setOf(
            "document", "subdocument", "script", "image", "stylesheet",
            "xmlhttprequest", "media", "font", "object", "ping", "websocket", "other", "popup"
        )
        private val UNSUPPORTED_OPTIONS = setOf(
            "csp", "elemhide", "generichide", "genericblock",
            "removeparam", "redirect", "redirect-rule", "replace", "urltransform",
            "match-case", "strict1p", "strict3p"
        )
        private val OPTION_ALIASES = mapOf(
            "popunder" to "popup", "1p" to "~third-party", "3p" to "third-party", "css" to "stylesheet",
            "frame" to "subdocument", "img" to "image", "xhr" to "xmlhttprequest"
        )
        private val COMMON_TWO_PART_SUFFIXES = setOf(
            "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "org.au",
            "com.br", "com.co", "com.mx", "co.jp", "co.kr", "co.nz",
            "com.ar", "com.pe", "com.ve", "com.ec"
        )
        internal val BADFILTER = Regex("""\$(?:[^,]*,)*badfilter(?:,|$)""", RegexOption.IGNORE_CASE)
        private val SIMPLE_DOMAIN = Regex("""([a-z0-9.-]+)\^?""", RegexOption.IGNORE_CASE)
        private val NON_WORD = Regex("""\W+""")
        private val LABEL = Regex("""[a-z0-9*](?:[a-z0-9*-]*[a-z0-9*])?""", RegexOption.IGNORE_CASE)
        private val IPV4 = Regex("""\d+(?:\.\d+){3}""")

        fun isValidHostname(hostname: String): Boolean =
            hostname.isNotEmpty() && hostname.length <= 253 &&
                hostname.split('.').all { it.isNotEmpty() && it.length <= 63 && LABEL.matches(it) }

        fun siteKey(hostname: String): String {
            val normalized = hostname.lowercase().removeSuffix(".")
            if (normalized.isEmpty() || IPV4.matches(normalized) || ':' in normalized) return normalized
            val labels = normalized.split('.')
            if (labels.size <= 2) return normalized
            val lastTwo = labels.takeLast(2).joinToString(".")
            return if (lastTwo in COMMON_TWO_PART_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
        }
    }
}

/** Partes de una URL sin `java.net.URI`, que rechaza muchas URL reales. */
internal object UrlParts {
    /** Host en minúsculas (`null` si no es una URL con autoridad). */
    fun host(url: String): String? {
        val scheme = url.indexOf("://")
        if (scheme <= 0) return null
        val start = scheme + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#' || c == '\\') { end = i; break }
        }
        var authority = url.substring(start, end)
        authority = authority.substring(authority.lastIndexOf('@') + 1)
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close == -1) return null
            authority.substring(1, close)
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase()
    }

    /** Ruta (sin consulta ni fragmento). */
    fun path(url: String): String {
        val scheme = url.indexOf("://")
        if (scheme <= 0) return ""
        val slash = url.indexOf('/', scheme + 3)
        if (slash == -1) return "/"
        var end = url.length
        for (i in slash until url.length) if (url[i] == '?' || url[i] == '#') { end = i; break }
        return url.substring(slash, end)
    }
}
