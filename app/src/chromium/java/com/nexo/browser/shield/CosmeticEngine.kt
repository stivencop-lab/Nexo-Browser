package com.nexo.browser.shield

/**
 * Reglas de ocultación (`##`, `#@#`, `$generichide`, `$elemhide`):
 * traducción de `background/cosmetic-engine.js` con los mismos límites.
 */
internal class CosmeticEngine {
    data class HostSelectors(val selectors: List<String>, val generic: Boolean)

    private val specific = HashMap<String, MutableList<String>>()
    private val exceptions = HashMap<String, MutableSet<String>>()
    private val genericExceptions = HashSet<String>()
    private val genericClass = HashSet<String>()
    private val genericId = HashSet<String>()
    private val genericComplex = ArrayList<String>()
    private val genericComplexSeen = HashSet<String>()
    private val noGeneric = HashSet<String>()
    private val noCosmetic = HashSet<String>()
    private var specificEntries = 0

    fun count(): Int = specificEntries + genericClass.size + genericId.size + genericComplex.size

    fun addHideException(line: String): Boolean {
        val match = HIDE_EXCEPTION.matchEntire(line) ?: return false
        val domain = match.groupValues[1].lowercase().trim('.')
        if (!DOMAIN.matches(domain)) return false
        val options = match.groupValues[2].lowercase().split(',')
        if (options.any { it.startsWith("domain=") }) return false
        var used = false
        if ("elemhide" in options || "ehide" in options) { noCosmetic.add(domain); used = true }
        if ("generichide" in options || "ghide" in options) { noGeneric.add(domain); used = true }
        return used
    }

    fun addLine(rawLine: String?): Boolean {
        val line = rawLine?.trim() ?: return false
        if (line.isEmpty() || line.length > 4096 || line[0] == '!' || line[0] == '[') return false
        val match = LINE.matchEntire(line) ?: return false
        val selector = match.groupValues[3].trim()
        if (selector.isEmpty() || selector.length > MAX_SELECTOR_LENGTH || UNSUPPORTED.containsMatchIn(selector)) return false
        val isException = match.groupValues[2] == "@"

        val included = ArrayList<String>()
        var negated = false
        for (part in match.groupValues[1].split(',')) {
            val domain = part.trim().lowercase()
            if (domain.isEmpty()) continue
            if (domain[0] == '~') { negated = true; continue }
            if (!DOMAIN.matches(domain)) return false
            included.add(domain)
        }
        if (negated && included.isEmpty()) return false

        if (isException) {
            if (included.isEmpty()) { genericExceptions.add(selector); return true }
            for (domain in included) exceptions.getOrPut(domain) { HashSet() }.add(selector)
            return true
        }
        if (included.isNotEmpty()) {
            if (specificEntries + included.size > MAX_SPECIFIC_ENTRIES) return false
            for (domain in included) specific.getOrPut(domain) { ArrayList(2) }.add(selector)
            specificEntries += included.size
            return true
        }
        val simpleClass = SIMPLE_CLASS.matchEntire(selector)
        val simpleId = if (simpleClass == null) SIMPLE_ID.matchEntire(selector) else null
        if (simpleClass != null || simpleId != null) {
            if (genericClass.size + genericId.size >= MAX_GENERIC_SIMPLE) return false
            if (simpleClass != null) genericClass.add(simpleClass.groupValues[1]) else genericId.add(simpleId!!.groupValues[1])
            return true
        }
        if (genericComplex.size >= MAX_GENERIC_COMPLEX || !genericComplexSeen.add(selector)) return false
        genericComplex.add(selector)
        return true
    }

    private fun chain(host: String?): List<String> {
        val clean = host.orEmpty().lowercase().removePrefix("www.").removeSuffix(".")
        if (clean.isEmpty()) return emptyList()
        val labels = clean.split('.')
        val result = ArrayList<String>()
        for (i in 0 until labels.size - 1) result.add(labels.subList(i, labels.size).joinToString("."))
        if (labels.size == 1) result.add(clean)
        return result
    }

    private fun exceptionsFor(chain: List<String>): Set<String> {
        val result = HashSet(genericExceptions)
        for (domain in chain) exceptions[domain]?.let { result.addAll(it) }
        return result
    }

    fun forHost(host: String?): HostSelectors {
        val chain = chain(host)
        if (chain.any { it in noCosmetic }) return HostSelectors(emptyList(), false)
        val generic = chain.none { it in noGeneric }
        val excluded = exceptionsFor(chain)
        val seen = HashSet<String>()
        val selectors = ArrayList<String>()
        fun push(selector: String) {
            if (selectors.size >= MAX_SELECTORS_PER_REPLY || selector in excluded || !seen.add(selector)) return
            selectors.add(selector)
        }
        for (domain in chain) specific[domain]?.forEach(::push)
        if (generic) genericComplex.forEach(::push)
        return HostSelectors(selectors, generic)
    }

    fun lookup(host: String?, classes: List<String>, ids: List<String>): List<String> {
        val chain = chain(host)
        if (chain.any { it in noCosmetic || it in noGeneric }) return emptyList()
        val excluded = exceptionsFor(chain)
        val selectors = ArrayList<String>()
        fun add(values: List<String>, index: Set<String>, prefix: String) {
            for (value in values.take(2000)) {
                if (value !in index) continue
                val selector = prefix + value
                if (selector !in excluded && selectors.size < MAX_SELECTORS_PER_REPLY) selectors.add(selector)
            }
        }
        add(classes, genericClass, ".")
        add(ids, genericId, "#")
        return selectors
    }

    companion object {
        private const val MAX_SPECIFIC_ENTRIES = 120_000
        private const val MAX_GENERIC_SIMPLE = 40_000
        private const val MAX_GENERIC_COMPLEX = 1_500
        private const val MAX_SELECTOR_LENGTH = 400
        const val MAX_SELECTORS_PER_REPLY = 1_500

        private val UNSUPPORTED = Regex(
            """:(?:has-text|contains|matches-css|matches-css-before|matches-css-after|matches-attr|matches-path|matches-media|matches-prop|upward|xpath|remove|remove-attr|remove-class|style|watch-attr|min-text-length|others|nth-ancestor|if|if-not|-abp-[a-z-]+)\(|\+js\(|^\^|[{}]|^script:|/\*""",
            RegexOption.IGNORE_CASE
        )
        private val SIMPLE_CLASS = Regex("""\.([\w-]+)""")
        private val SIMPLE_ID = Regex("""#([\w-]+)""")
        private val DOMAIN = Regex("""[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?""")
        private val LINE = Regex("""([^#]*)#(@?)#(.+)""")
        private val HIDE_EXCEPTION = Regex("""@@\|\|([a-z0-9.-]+)\^?\$([a-z0-9,~=|._-]+)""", RegexOption.IGNORE_CASE)
    }
}
