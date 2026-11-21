package com.nexo.browser.extensions.nxr

import com.google.re2j.Pattern
import com.nexo.browser.extensions.ExtensionManifest
import com.nexo.browser.shield.UrlParts
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Static domain-scoped MV3 rules, evaluated by PhantomShield's network interceptor. */
internal object DnrBridge {
    private const val PERMISSION = "declarativeNetRequest"
    private const val MAX_RULES = 30_000
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "NexoDnr").apply { isDaemon = true } }
    private val revision = AtomicLong()
    @Volatile private var extensions = emptyList<Rules>()
    @Volatile private var runtimeRoot: File? = null

    @Synchronized
    fun initialize(root: File) {
        if (runtimeRoot == root) return
        runtimeRoot = root
        extensions = emptyList()
        refresh(root)
    }

    @Synchronized
    fun refresh(root: File, changedId: String? = null) {
        if (runtimeRoot != root) return
        val ticket = revision.incrementAndGet()
        if (changedId != null) extensions = extensions.filterNot { it.id == changedId }
        worker.execute {
            if (ticket != revision.get()) return@execute
            val updated = NxrPackageInstaller.listInstalled(root).mapNotNull { ext ->
                runCatching { compileExtension(ext.id, root) }.getOrNull()
            }
            synchronized(DnrBridge) {
                if (ticket == revision.get()) extensions = updated
            }
        }
    }

    fun blocks(url: String, initiator: String, top: String, kind: String, method: String, isPrivate: Boolean): Boolean {
        val active = extensions
        if (active.isEmpty()) return false
        val request = Request.from(url, initiator, top, kind, method) ?: return false
        return active.any {
            if (!it.blocks(request, isPrivate)) false else {
                it.recordBlock()
                true
            }
        }
    }

    fun ruleCount(id: String): Int = extensions.firstOrNull { it.id == id }?.compiledCount ?: 0
    fun hasBlocked(id: String): Boolean = extensions.firstOrNull { it.id == id }?.hasBlocked == true

    internal fun compileExtension(id: String, root: File): Rules? {
        val policy = NxrPermissions.readPolicy(NxrPackageInstaller.extensionDir(root, id))
        if (!policy.enabled) return null
        val files = NxrPackageInstaller.filesDir(root, id)
        val manifest = NxrPackageInstaller.readManifest(root, id) ?: return null
        if (manifest.manifestVersion != 3 || PERMISSION !in manifest.permissions) return null
        val blocks = mutableMapOf<String, MutableList<Rule>>()
        val allows = mutableMapOf<String, MutableList<Rule>>()
        val globalAllows = ArrayList<Rule>()
        var readRules = 0
        var indexed = 0
        var compiled = 0
        for (ref in manifest.dnrRulesets) {
            if (!ref.enabled) continue
            val path = ExtensionManifest.normalizePath(ref.path) ?: return null
            val file = File(files, path)
            if (!file.isFile || file.length() !in 1L..8_388_608L) return null
            val entries = JSONArray(file.readText())
            for (i in 0 until entries.length()) {
                if (++readRules > MAX_RULES) return null
                val entry = entries.optJSONObject(i) ?: return null
                val action = entry.optJSONObject("action")?.optString("type")
                if (action == "allowAllRequests") return null
                if (action != "block" && action != "allow") continue
                val rule = Rule.compile(entry)
                if (rule == null) {
                    // Skipping an exception could block traffic that the extension allows.
                    if (action == "allow") return null
                    continue
                }
                val domains = rule.indexDomains
                if (domains.isEmpty()) {
                    if (action != "allow") continue
                    if (globalAllows.size >= 512) return null
                    globalAllows += rule
                } else {
                    indexed += domains.size
                    if (indexed > 200_000) return null
                    val index = if (action == "allow") allows else blocks
                    for (domain in domains) index.getOrPut(domain) { ArrayList() }.add(rule)
                }
                compiled++
            }
        }
        return if (blocks.isEmpty()) null else Rules(id, policy, blocks, allows, globalAllows, compiled)
    }

    internal class Request private constructor(
        val url: String, val host: String, val origin: String?, val site: String,
        val type: String, val method: String
    ) {
        private var firstParty: Boolean? = null

        fun isFirstParty(): Boolean {
            firstParty?.let { return it }
            val result = origin != null && (host == origin || registrableHost(host) == registrableHost(origin))
            firstParty = result
            return result
        }

        companion object {
            private val siteCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 512
            }

            fun from(url: String, initiator: String, top: String, type: String, method: String): Request? {
                val host = UrlParts.host(url)?.trimEnd('.') ?: return null
                return Request(url, host, UrlParts.host(initiator)?.trimEnd('.'), NxrPermissions.extractHost(top),
                    type, method.lowercase(Locale.ROOT))
            }

            private fun registrableHost(host: String): String = synchronized(siteCache) {
                siteCache.getOrPut(host) {
                    runCatching { HttpUrl.Builder().scheme("https").host(host).build().topPrivateDomain() }
                        .getOrNull() ?: host
                }
            }
        }
    }

    internal class Rules(
        val id: String, private val policy: NxrPermissions.Policy, private val blocks: Map<String, List<Rule>>,
        private val allows: Map<String, List<Rule>>, private val globalAllows: List<Rule>, val compiledCount: Int
    ) {
        @Volatile var hasBlocked = false
            private set

        fun recordBlock() { hasBlocked = true }

        fun blocks(request: Request, isPrivate: Boolean): Boolean {
            val site = request.site
            if (site.isEmpty() || site in policy.deniedHosts || isPrivate && !policy.allowInPrivate ||
                policy.restrictToAllowedHosts && site !in policy.allowedHostsOverride) return false
            var priority = 0
            for (suffix in suffixes(request.host)) {
                for (rule in blocks[suffix].orEmpty()) {
                    if (rule.priority > priority && rule.matches(request)) priority = rule.priority
                }
            }
            if (priority == 0) return false
            for (suffix in suffixes(request.host)) {
                if (allows[suffix].orEmpty().any { it.priority >= priority && it.matches(request) }) return false
            }
            return globalAllows.none { it.priority >= priority && it.matches(request) }
        }
    }

    internal class Rule(
        val priority: Int,
        val indexDomains: Set<String>,
        private val filter: Pattern?,
        private val domains: Set<String>,
        private val excludedDomains: Set<String>,
        private val origins: Set<String>,
        private val excludedOrigins: Set<String>,
        private val types: Set<String>,
        private val excludedTypes: Set<String>,
        private val methods: Set<String>,
        private val excludedMethods: Set<String>,
        private val firstParty: Boolean?
    ) {
        fun matches(request: Request): Boolean =
            (types.isEmpty() || request.type in types) && request.type !in excludedTypes &&
                (methods.isEmpty() || request.method in methods) && request.method !in excludedMethods &&
                (domains.isEmpty() || domainMatches(request.host, domains)) &&
                !domainMatches(request.host, excludedDomains) &&
                (origins.isEmpty() || domainMatches(request.origin, origins)) &&
                !domainMatches(request.origin, excludedOrigins) &&
                (firstParty == null || request.origin != null && request.isFirstParty() == firstParty) &&
                (filter == null || filter.matcher(request.url).find())

        companion object {
            private val domainPattern = Regex("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*")
            private val eligibleHost = Regex("""^(?:\|\||\|https?://)([\w.-]+)(?:[/:?^|#]|$)""", RegexOption.IGNORE_CASE)
            private val simpleDomain = Regex("""^\|\|([\w.-]+)\^$""")
            private val supported = setOf("urlFilter", "requestDomains", "excludedRequestDomains",
                "initiatorDomains", "excludedInitiatorDomains", "resourceTypes", "excludedResourceTypes",
                "isUrlFilterCaseSensitive", "domainType", "requestMethods", "excludedRequestMethods")

            fun compile(entry: JSONObject): Rule? {
                val conditions = entry.optJSONObject("condition") ?: return null
                if (conditions.keys().asSequence().any { it !in supported }) return null
                val raw = if (conditions.has("urlFilter")) {
                    (conditions.opt("urlFilter") as? String)?.takeIf { it.length in 1..2048 } ?: return null
                } else null
                val domains = values(conditions, "requestDomains", ::parseDomain) ?: return null
                val scopedHost = raw?.let { eligibleHost.find(it)?.groupValues?.get(1) }?.let(::parseDomain)
                val matchCase = conditions.optBoolean("isUrlFilterCaseSensitive")
                val filter = if (raw == null || !matchCase && simpleDomain.matches(raw) && scopedHost != null) null else
                    compileFilter(raw, matchCase)
                val scope = if (scopedHost != null) setOf(scopedHost) else domains
                val priority = entry.optInt("priority", 1)
                if (priority < 1) return null
                val party = when (conditions.optString("domainType")) {
                    "" -> null
                    "firstParty" -> true
                    "thirdParty" -> false
                    else -> return null
                }
                return Rule(priority, scope, filter, domains,
                    values(conditions, "excludedRequestDomains", ::parseDomain) ?: return null,
                    values(conditions, "initiatorDomains", ::parseDomain) ?: return null,
                    values(conditions, "excludedInitiatorDomains", ::parseDomain) ?: return null,
                    values(conditions, "resourceTypes") { it } ?: return null,
                    values(conditions, "excludedResourceTypes") { it } ?: return null,
                    values(conditions, "requestMethods") { it } ?: return null,
                    values(conditions, "excludedRequestMethods") { it } ?: return null,
                    party)
            }

            private fun compileFilter(raw: String, matchCase: Boolean): Pattern {
                val pattern = buildString {
                    val domainAnchor = raw.startsWith("||")
                    val startAnchor = !domainAnchor && raw.startsWith('|')
                    val endAnchor = raw.endsWith('|') && raw.length > (if (domainAnchor) 2 else 1)
                    if (domainAnchor) append("""^https?://(?:[A-Za-z0-9_-]+\.)*""")
                    else if (startAnchor) append('^')
                    val start = if (domainAnchor) 2 else if (startAnchor) 1 else 0
                    val end = if (endAnchor) raw.length - 1 else raw.length
                    var literalStart = start
                    for (i in start until end) {
                        val c = raw[i]
                        if (c != '*' && c != '^') continue
                        append(Pattern.quote(raw.substring(literalStart, i)))
                        if (c == '*') append(".*") else append("(?:[^A-Za-z0-9_.%\\-]|$)")
                        literalStart = i + 1
                    }
                    append(Pattern.quote(raw.substring(literalStart, end)))
                    if (endAnchor) append('$')
                }
                return Pattern.compile(pattern, if (matchCase) 0 else Pattern.CASE_INSENSITIVE)
            }

            private fun values(json: JSONObject, name: String, parse: (String) -> String?): Set<String>? {
                if (!json.has(name)) return emptySet()
                val array = json.optJSONArray(name) ?: return null
                if (array.length() !in 1..65_536) return null
                val result = HashSet<String>()
                for (index in 0 until array.length()) {
                    val item = array.opt(index) as? String ?: return null
                    result.add(parse(item) ?: return null)
                }
                return result
            }

            private fun parseDomain(raw: String): String? =
                raw.lowercase(Locale.ROOT).takeIf { it.length <= 253 && domainPattern.matches(it) }

            private fun domainMatches(host: String?, domains: Set<String>): Boolean =
                host != null && domains.isNotEmpty() && suffixes(host).any { it in domains }
        }
    }

    private fun suffixes(host: String): Sequence<String> = generateSequence(host) { suffix ->
        val dot = suffix.indexOf('.')
        if (dot < 0) null else suffix.substring(dot + 1)
    }
}
