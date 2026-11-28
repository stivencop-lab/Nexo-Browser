package com.nexo.browser.shield

/**
 * Catálogo y carga de listas: traducción de `background/filter-lists.js`.
 * Construye un [NetworkEngine] y un [CosmeticEngine] nuevos a partir de las
 * reglas incluidas en la app y del texto de las listas descargadas.
 */
internal object FilterLists {
    const val MAX_DYNAMIC_RULES = 220_000

    data class FilterList(val id: String, val name: String, val urls: List<String>, val optional: Boolean = false)

    val LISTS = listOf(
        FilterList("easylist", "EasyList", listOf(
            "https://easylist.to/easylist/easylist.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssetsCDN/main/thirdparties/easylist.txt"
        )),
        FilterList("easyprivacy", "EasyPrivacy", listOf(
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssetsCDN/main/thirdparties/easyprivacy.txt"
        )),
        FilterList("ubo-filters", "uBlock filters", listOf(
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt"
        )),
        FilterList("ubo-privacy", "uBlock privacy", listOf(
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt"
        )),
        FilterList("ubo-unbreak", "uBlock unbreak", listOf(
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt"
        )),
        FilterList("easylist-es", "EasyList Spanish", listOf(
            "https://easylist-downloads.adblockplus.org/easylistspanish.txt"
        ), optional = true),
        FilterList("easylist-cookie", "Cookies y molestias", listOf(
            "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt",
            "https://raw.githubusercontent.com/uBlockOrigin/uAssetsCDN/main/thirdparties/easylist-cookies.txt"
        ), optional = true),
        FilterList("ubo-badware", "uBlock Malware y Phishing", listOf(
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt"
        ), optional = true),
        FilterList("nocoin-miners", "NoCoin Criptomineros", listOf(
            "https://raw.githubusercontent.com/hoshsadiq/adblock-nocoin-list/master/nocoin.txt"
        ), optional = true)
    )

    enum class Kind { NETWORK, COSMETIC, HIDE_EXCEPTION, BADFILTER }
    data class Parsed(val kind: Kind, val rule: String)

    data class Bundled(
        val adDomains: List<String>,
        val trackerDomains: List<String>,
        val popupDomains: List<String>,
        val cosmeticRules: List<String>
    )

    class Result(
        val network: NetworkEngine,
        val cosmetic: CosmeticEngine,
        val threatDomains: Set<String> = emptySet(),
        val minerDomains: Set<String> = emptySet()
    ) {
        val networkCount: Int get() = network.ruleCount()
        val cosmeticCount: Int get() = cosmetic.count()
    }

    private val COSMETIC_MARKER = Regex("""#@?#|#\?#|#\$#|#%#""")
    private val COSMETIC_CSS = Regex("""^[^#]*#@?#""")
    private val UNSUPPORTED_NETWORK = Regex(
        """\$(?:[^,]*,)*(?:csp|redirect(?:-rule)?|removeparam|replace|urltransform|permissions|header|uritransform|ipaddress|to|method)(?:[=,]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val HIDE_EXCEPTION = Regex("""\$(?:[^,]*,)*(?:elemhide|ehide|generichide|ghide)(?:,|$)""", RegexOption.IGNORE_CASE)
    private val GENERICBLOCK = Regex("""\$(?:[^,]*,)*genericblock(?:,|$)""", RegexOption.IGNORE_CASE)
    private val FIRST_PASS_BADFILTER = Regex("""^[^!]*\$(?:[^,]*,)*badfilter(?:,.*)?$""", RegexOption.IGNORE_CASE)
    private val SIMPLE_HOST_RULE = Regex("""^\|\|([a-z0-9.-]+)\^?(?:\$(?:document|all|important|popup|frame))?$""", RegexOption.IGNORE_CASE)

    fun parseRule(input: String): Parsed? {
        val line = input.trim()
        if (line.isEmpty() || line.length > 4096 || line.startsWith("!") || line.startsWith("[Adblock")) return null
        if (COSMETIC_MARKER.containsMatchIn(line)) {
            return if (COSMETIC_CSS.containsMatchIn(line)) Parsed(Kind.COSMETIC, line) else null
        }
        if (HIDE_EXCEPTION.containsMatchIn(line)) {
            return if (line.startsWith("@@")) Parsed(Kind.HIDE_EXCEPTION, line) else null
        }
        if (NetworkEngine.BADFILTER.containsMatchIn(line)) return Parsed(Kind.BADFILTER, line)
        if ((line.startsWith("/") && line.lastIndexOf('/') > 0) || UNSUPPORTED_NETWORK.containsMatchIn(line) ||
            GENERICBLOCK.containsMatchIn(line)) return null
        return Parsed(Kind.NETWORK, line)
    }

    /** Extrae dominios planos (`||dominio^`) de una lista temática (badware o mineros). */
    fun extractPlainDomains(text: String?): Set<String> {
        if (text.isNullOrEmpty()) return emptySet()
        val out = HashSet<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("@@")) continue
            val m = SIMPLE_HOST_RULE.matchEntire(line) ?: continue
            val host = m.groupValues[1].lowercase().trim('.')
            if ('.' in host && NetworkEngine.isValidHostname(host)) out.add(host)
        }
        return out
    }

    fun loadRulesText(
        text: String, engine: NetworkEngine, limit: Int, cosmetic: CosmeticEngine?, tracking: Boolean = false
    ): Int {
        if (text.isEmpty()) return 0
        val maxRules = limit.coerceAtLeast(0)
        var count = 0
        for (line in text.lineSequence()) {
            val parsed = parseRule(line) ?: continue
            when (parsed.kind) {
                Kind.BADFILTER -> engine.addBadFilter(parsed.rule)
                Kind.NETWORK -> if (count < maxRules && engine.addRule(engine.createRule(parsed.rule), tracking)) count++
                Kind.COSMETIC -> cosmetic?.addLine(parsed.rule)
                Kind.HIDE_EXCEPTION -> cosmetic?.addHideException(parsed.rule)
            }
        }
        return count
    }

    /** Motores nuevos: reglas incluidas y listas descargadas (`id` → texto), en el orden del catálogo. */
    fun build(bundled: Bundled, texts: Map<String, String>): Result {
        val engine = NetworkEngine()
        val cosmetic = CosmeticEngine()
        val ordered = LISTS.mapNotNull { list -> texts[list.id]?.takeIf { it.isNotEmpty() }?.let { list.id to it } }
        // Primero los `$badfilter` de todas las listas: la regla anulada no llega a entrar.
        for ((_, text) in ordered) {
            for (line in text.lineSequence()) if (FIRST_PASS_BADFILTER.matches(line.trimEnd('\r'))) engine.addBadFilter(line.trim())
        }
        engine.init(bundled.adDomains, bundled.trackerDomains, bundled.popupDomains)
        bundled.cosmeticRules.forEach { cosmetic.addLine(it) }
        var total = 0
        for ((id, text) in ordered) {
            total += loadRulesText(text, engine, MAX_DYNAMIC_RULES - total, cosmetic,
                tracking = id == "easyprivacy" || id == "ubo-privacy")
        }
        engine.finalizeRules()
        val threats = extractPlainDomains(texts["ubo-badware"])
        val miners = extractPlainDomains(texts["nocoin-miners"])
        return Result(engine, cosmetic, threats, miners)
    }

    /** Lista de una descarga: texto declarativo, no una página de error. */
    fun isValidListText(text: String): Boolean =
        text.length >= 200 && !Regex("""^\s*<(?:!doctype|html)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

/**
 * Lee los arreglos de texto de los archivos de datos de la extensión
 * (`window.PHANTOM_AD_DOMAINS = [ "…", … ];`) sin ejecutar JavaScript:
 * solo cadenas entre comillas y comentarios.
 */
internal object BundledData {
    fun array(source: String, name: String): List<String> {
        val start = source.indexOf(name)
        require(start >= 0) { "Falta $name" }
        var i = source.indexOf('[', start)
        require(i >= 0) { "Falta el arreglo de $name" }
        i++
        val result = ArrayList<String>()
        while (i < source.length) {
            val c = source[i]
            when {
                c == ']' -> return result
                c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                    i = source.indexOf('\n', i).let { if (it == -1) source.length else it }
                }
                c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                    i = source.indexOf("*/", i + 2).let { require(it >= 0); it + 2 }
                }
                c == '"' || c == '\'' -> {
                    val (value, next) = string(source, i, c)
                    result.add(value)
                    i = next
                }
                c.isWhitespace() || c == ',' -> i++
                else -> error("Elemento no admitido en $name: '$c'")
            }
        }
        error("Arreglo sin cerrar: $name")
    }

    private fun string(source: String, start: Int, quote: Char): Pair<String, Int> {
        val out = StringBuilder()
        var i = start + 1
        while (i < source.length) {
            val c = source[i]
            when (c) {
                quote -> return out.toString() to i + 1
                '\\' -> {
                    val n = source[i + 1]
                    when (n) {
                        'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                        'u' -> { out.append(source.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                        else -> out.append(n)
                    }
                    i += 2
                    continue
                }
                '\n' -> error("Cadena sin cerrar")
                else -> out.append(c)
            }
            i++
        }
        error("Cadena sin cerrar")
    }

    /** Las cuatro listas incluidas, a partir del contenido de cada archivo de datos. */
    fun parse(read: (String) -> String): FilterLists.Bundled = FilterLists.Bundled(
        adDomains = array(read("data/domains-adservers.js"), "PHANTOM_AD_DOMAINS"),
        trackerDomains = array(read("data/domains-trackers.js"), "PHANTOM_TRACKER_DOMAINS"),
        popupDomains = array(read("data/domains-popups.js"), "PHANTOM_POPUP_DOMAINS"),
        cosmeticRules = array(read("data/cosmetic-rules.js"), "PHANTOM_COSMETIC_RULES")
    )
}
