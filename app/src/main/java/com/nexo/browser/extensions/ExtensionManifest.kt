package com.nexo.browser.extensions

import org.json.JSONArray
import org.json.JSONObject

/**
 * Manifiesto de una extensión de escritorio (Chrome MV2/MV3 o Firefox),
 * normalizado. No ejecuta nada: solo describe lo que la extensión pide.
 * Puro: probado en la JVM.
 */
data class ExtensionManifest(
    val manifestVersion: Int,
    val name: String,
    val version: String,
    val description: String,
    val defaultLocale: String?,
    /** ID declarado por Firefox (`browser_specific_settings.gecko.id`); Chrome lo deriva de la clave. */
    val geckoId: String?,
    /** Clave pública en base64 (`key`), de la que Chrome deriva el ID. */
    val publicKey: String?,
    val background: Background?,
    val contentScripts: List<ContentScript>,
    val permissions: Set<String>,
    val hostPermissions: List<String>,
    val optionalPermissions: Set<String>,
    val optionalHostPermissions: List<String>,
    val action: Action?,
    val optionsPage: String?,
    val optionsOpenInTab: Boolean,
    val webAccessibleResources: List<WebAccessible>,
    val dnrRulesets: List<RulesetRef>,
    val icons: Map<Int, String>,
    val incognito: String,
    val contentSecurityPolicy: String?
) {
    data class Background(
        val serviceWorker: String?,
        val scripts: List<String>,
        val page: String?,
        val isModule: Boolean,
        val persistent: Boolean
    )

    data class ContentScript(
        val matches: List<String>,
        val excludeMatches: List<String>,
        val includeGlobs: List<String>,
        val excludeGlobs: List<String>,
        val js: List<String>,
        val css: List<String>,
        val runAt: RunAt,
        val allFrames: Boolean,
        val matchAboutBlank: Boolean,
        val world: World
    ) {
        val matcher: UrlMatcher by lazy { UrlMatcher(matches, excludeMatches, includeGlobs, excludeGlobs) }
    }

    enum class RunAt(val wire: String) {
        DOCUMENT_START("document_start"), DOCUMENT_END("document_end"), DOCUMENT_IDLE("document_idle");

        companion object {
            fun from(value: String?): RunAt = entries.firstOrNull { it.wire == value } ?: DOCUMENT_IDLE
        }
    }

    enum class World { ISOLATED, MAIN }

    data class Action(val popup: String?, val title: String?, val icons: Map<Int, String>)

    data class WebAccessible(val resources: List<String>, val matches: List<String>)

    data class RulesetRef(val id: String, val enabled: Boolean, val path: String)

    /** Todas las rutas de archivos que el manifiesto referencia (para comprobar que existen). */
    fun referencedFiles(): Set<String> = buildSet {
        background?.serviceWorker?.let(::add)
        background?.scripts?.let(::addAll)
        background?.page?.let(::add)
        contentScripts.forEach { addAll(it.js); addAll(it.css) }
        action?.popup?.let(::add)
        optionsPage?.let(::add)
        dnrRulesets.forEach { add(it.path) }
    }.map { normalizePath(it) }.filterNotNull().toSet()

    companion object {
        /** Tamaño máximo razonable de un `manifest.json`. */
        const val MAX_BYTES = 1024 * 1024

        fun parse(text: String): ExtensionManifest {
            require(text.length <= MAX_BYTES) { "El manifiesto es demasiado grande." }
            val json = runCatching { JSONObject(text.removePrefix("\uFEFF")) }
                .getOrElse { throw IllegalArgumentException("manifest.json no es JSON válido.") }
            val mv = json.optInt("manifest_version", 0)
            require(mv == 2 || mv == 3) { "manifest_version $mv no es compatible (solo 2 o 3)." }
            val name = json.optString("name").trim()
            require(name.isNotEmpty() && name.length <= 200) { "La extensión no tiene nombre." }
            val version = json.optString("version").trim()
            require(version.matches(Regex("""\d{1,9}(\.\d{1,9}){0,3}"""))) { "Versión no válida: «$version»." }

            val gecko = json.optJSONObject("browser_specific_settings")?.optJSONObject("gecko")
                ?: json.optJSONObject("applications")?.optJSONObject("gecko")

            val rawPermissions = strings(json.optJSONArray("permissions"))
            val rawOptional = strings(json.optJSONArray("optional_permissions"))
            // MV2 mezcla hosts y APIs en `permissions`; MV3 los separa.
            val (hostsInPermissions, apiPermissions) = rawPermissions.partition { isHostPermission(it) }
            val (optionalHostsInPermissions, optionalApis) = rawOptional.partition { isHostPermission(it) }

            val actionJson = when (mv) {
                3 -> json.optJSONObject("action")
                else -> json.optJSONObject("browser_action") ?: json.optJSONObject("page_action")
            } ?: json.optJSONObject("action")

            return ExtensionManifest(
                manifestVersion = mv,
                name = name,
                version = version,
                description = json.optString("description").take(1000),
                defaultLocale = json.optString("default_locale").takeIf { it.isNotBlank() },
                geckoId = gecko?.optString("id")?.takeIf { it.isNotBlank() },
                publicKey = json.optString("key").takeIf { it.isNotBlank() },
                background = parseBackground(json.optJSONObject("background")),
                contentScripts = parseContentScripts(json.optJSONArray("content_scripts")),
                permissions = apiPermissions.toSet(),
                hostPermissions = hostsInPermissions + strings(json.optJSONArray("host_permissions")),
                optionalPermissions = optionalApis.toSet(),
                optionalHostPermissions = optionalHostsInPermissions + strings(json.optJSONArray("optional_host_permissions")),
                action = actionJson?.let {
                    Action(
                        popup = it.optString("default_popup").takeIf { p -> p.isNotBlank() },
                        title = it.optString("default_title").takeIf { t -> t.isNotBlank() },
                        icons = icons(it.opt("default_icon"))
                    )
                },
                optionsPage = json.optJSONObject("options_ui")?.optString("page")?.takeIf { it.isNotBlank() }
                    ?: json.optString("options_page").takeIf { it.isNotBlank() },
                optionsOpenInTab = json.optJSONObject("options_ui")?.optBoolean("open_in_tab", false)
                    ?: json.has("options_page"),
                webAccessibleResources = parseWebAccessible(json.optJSONArray("web_accessible_resources"), mv),
                dnrRulesets = parseRulesets(json.optJSONObject("declarative_net_request")),
                icons = icons(json.opt("icons")),
                incognito = json.optString("incognito", "spanning"),
                contentSecurityPolicy = when (val csp = json.opt("content_security_policy")) {
                    is String -> csp
                    is JSONObject -> csp.optString("extension_pages").takeIf { it.isNotBlank() }
                    else -> null
                }
            )
        }

        fun isHostPermission(value: String): Boolean =
            value == MatchPattern.ALL_URLS || value.contains("://")

        private fun parseBackground(json: JSONObject?): Background? {
            json ?: return null
            val worker = json.optString("service_worker").takeIf { it.isNotBlank() }
            val scripts = strings(json.optJSONArray("scripts"))
            val page = json.optString("page").takeIf { it.isNotBlank() }
            if (worker == null && scripts.isEmpty() && page == null) return null
            return Background(
                serviceWorker = worker,
                scripts = scripts,
                page = page,
                isModule = json.optString("type") == "module",
                persistent = json.optBoolean("persistent", worker == null)
            )
        }

        private fun parseContentScripts(array: JSONArray?): List<ContentScript> {
            array ?: return emptyList()
            return (0 until minOf(array.length(), 64)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val matches = strings(item.optJSONArray("matches"))
                if (matches.isEmpty()) return@mapNotNull null
                ContentScript(
                    matches = matches,
                    excludeMatches = strings(item.optJSONArray("exclude_matches")),
                    includeGlobs = strings(item.optJSONArray("include_globs")),
                    excludeGlobs = strings(item.optJSONArray("exclude_globs")),
                    js = strings(item.optJSONArray("js")),
                    css = strings(item.optJSONArray("css")),
                    runAt = RunAt.from(item.optString("run_at")),
                    allFrames = item.optBoolean("all_frames", false),
                    matchAboutBlank = item.optBoolean("match_about_blank", false) ||
                        item.optBoolean("match_origin_as_fallback", false),
                    world = if (item.optString("world").equals("MAIN", ignoreCase = true)) World.MAIN else World.ISOLATED
                )
            }
        }

        private fun parseWebAccessible(array: JSONArray?, mv: Int): List<WebAccessible> {
            array ?: return emptyList()
            if (mv == 2) {
                val resources = strings(array)
                return if (resources.isEmpty()) emptyList() else listOf(WebAccessible(resources, listOf(MatchPattern.ALL_URLS)))
            }
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                WebAccessible(strings(item.optJSONArray("resources")), strings(item.optJSONArray("matches")))
                    .takeIf { it.resources.isNotEmpty() }
            }
        }

        private fun parseRulesets(json: JSONObject?): List<RulesetRef> {
            val array = json?.optJSONArray("rule_resources") ?: return emptyList()
            return (0 until minOf(array.length(), 100)).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val path = item.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RulesetRef(id, item.optBoolean("enabled", false), path)
            }
        }

        private fun icons(value: Any?): Map<Int, String> = when (value) {
            is String -> mapOf(0 to value)
            is JSONObject -> value.keys().asSequence().mapNotNull { key ->
                val size = key.toIntOrNull() ?: return@mapNotNull null
                val path = value.optString(key).takeIf { it.isNotBlank() } ?: return@mapNotNull null
                size to path
            }.toMap()
            else -> emptyMap()
        }

        private fun strings(array: JSONArray?): List<String> {
            array ?: return emptyList()
            return (0 until minOf(array.length(), 4096)).mapNotNull { index ->
                (array.opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 }
            }
        }

        /**
         * Ruta relativa segura dentro del paquete, sin `..`, barras iniciales ni
         * esquemas; `null` si sale del paquete.
         */
        fun normalizePath(path: String): String? {
            val clean = path.trim().substringBefore('#').substringBefore('?').replace('\\', '/').trimStart('/')
            if (clean.isEmpty() || clean.contains("://") || clean.contains('\u0000')) return null
            val parts = mutableListOf<String>()
            for (part in clean.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> return null
                    else -> parts += part
                }
            }
            return parts.joinToString("/").takeIf { it.isNotEmpty() }
        }
    }
}
