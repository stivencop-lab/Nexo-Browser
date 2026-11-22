package com.nexo.browser.extensions.nxr

import com.nexo.browser.extensions.ExtensionCompatibility
import com.nexo.browser.extensions.ExtensionManifest
import com.nexo.browser.extensions.MatchPattern
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Modelo explícito de permisos y consentimiento por origen para extensiones NXR.
 *
 * Reglas de seguridad:
 * - Nunca autoriza esquemas internos (`about:`, `chrome:`, `file:`, `data:`, `javascript:`).
 * - En pestañas privadas requiere autorización explícita (`allowInPrivate = true`).
 * - Distingue entre permisos concedidos en instalación, sitios bloqueados por el usuario
 *   y APIs no soportadas bajo las APIs públicas de Cefrium 0.21.0.
 * Puro sobre [File]: probado en la JVM.
 */
object NxrPermissions {
    private const val PERMS_FILE = "permissions.json"

    /** APIs cuya ejecución no existe para terceros en la API pública de Cefrium 0.21.0. */
    val ENGINE_UNSUPPORTED_APIS: Set<String> = ExtensionCompatibility.UNSUPPORTED_PERMS

    data class Policy(
        val enabled: Boolean = true,
        val allowInPrivate: Boolean = false,
        val grantedHostPatterns: List<String> = emptyList(),
        val deniedHosts: Set<String> = emptySet(),
        val allowedHostsOverride: Set<String> = emptySet(),
        val restrictToAllowedHosts: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("enabled", enabled)
            .put("allowInPrivate", allowInPrivate)
            .put("grantedHostPatterns", JSONArray(grantedHostPatterns))
            .put("deniedHosts", JSONArray(deniedHosts.sorted()))
            .put("allowedHostsOverride", JSONArray(allowedHostsOverride.sorted()))
            .put("restrictToAllowedHosts", restrictToAllowedHosts)

        companion object {
            fun fromJson(json: JSONObject?): Policy {
                if (json == null) return Policy()
                fun list(name: String): List<String> {
                    val arr = json.optJSONArray(name) ?: return emptyList()
                    return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                }
                return Policy(
                    enabled = json.optBoolean("enabled", true),
                    allowInPrivate = json.optBoolean("allowInPrivate", false),
                    grantedHostPatterns = list("grantedHostPatterns"),
                    deniedHosts = list("deniedHosts").map { it.lowercase() }.toSet(),
                    allowedHostsOverride = list("allowedHostsOverride").map { it.lowercase() }.toSet(),
                    restrictToAllowedHosts = json.optBoolean("restrictToAllowedHosts", false)
                )
            }
        }
    }

    data class CapabilitySummary(
        val level: ExtensionCompatibility.Level,
        val verdict: ExtensionCompatibility.InstallVerdict,
        val runnableContentScripts: Int,
        val hasServiceWorkerBackground: Boolean,
        val hasBroadHostAccess: Boolean,
        val supportedApis: List<String>,
        val limitedApis: Map<String, String>,
        val unsupportedApis: List<String>,
        val apiBreakdown: List<ExtensionCompatibility.ApiMatrixRow> = emptyList()
    )

    fun readPolicy(extDir: File): Policy {
        val file = File(extDir, PERMS_FILE)
        if (!file.isFile) return Policy()
        return runCatching { Policy.fromJson(JSONObject(file.readText(Charsets.UTF_8))) }.getOrDefault(Policy())
    }

    fun writePolicy(extDir: File, policy: Policy) {
        extDir.mkdirs()
        File(extDir, PERMS_FILE).writeText(policy.toJson().toString(), Charsets.UTF_8)
    }

    fun isWebHttpUrl(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    fun extractHost(url: String): String {
        val after = url.substringAfter("://", "")
        val end = after.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        val authority = (if (end >= 0) after.substring(0, end) else after).substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close > 1) authority.substring(1, close) else return ""
        } else {
            authority.substringBefore(':')
        }
        return host.lowercase().removePrefix("www.").trimEnd('.')
    }

    /** Verifica si la extensión con [policy] puede actuar sobre [url] en modo [isPrivate]. */
    fun isAuthorizedForUrl(policy: Policy, url: String, isPrivate: Boolean): Boolean {
        if (!policy.enabled) return false
        if (isPrivate && !policy.allowInPrivate) return false
        if (!isWebHttpUrl(url)) return false
        val host = extractHost(url)
        if (host.isEmpty()) return false
        if (host in policy.deniedHosts) return false
        if (policy.restrictToAllowedHosts) return host in policy.allowedHostsOverride
        if (host in policy.allowedHostsOverride) return true
        if (policy.grantedHostPatterns.isEmpty()) return false
        return policy.grantedHostPatterns.any { raw ->
            MatchPattern.parse(raw)?.matches(url) == true
        }
    }

    /** Evalúa de forma honesta qué partes del manifiesto puede ejecutar NXR sobre Cefrium 0.21.0. */
    fun summarizeCapabilities(manifest: ExtensionManifest): CapabilitySummary {
        val report = ExtensionCompatibility.evaluate(manifest)
        val hasSw = manifest.background?.serviceWorker != null
        val runnableScripts = manifest.contentScripts.count { it.js.isNotEmpty() || it.css.isNotEmpty() }
        val requestedHosts = requestedHostPatterns(manifest)
        val broadHost = requestedHosts.any { ExtensionCompatibility.isBroadHostPattern(it) }
        return CapabilitySummary(
            level = report.level,
            verdict = report.verdict,
            runnableContentScripts = runnableScripts,
            hasServiceWorkerBackground = hasSw,
            hasBroadHostAccess = broadHost,
            supportedApis = report.supported,
            limitedApis = report.limited,
            unsupportedApis = report.unsupported,
            apiBreakdown = report.apiBreakdown
        )
    }

    /** Extrae los patrones de host solicitados por `host_permissions` y `content_scripts`. */
    fun requestedHostPatterns(manifest: ExtensionManifest): List<String> =
        (manifest.hostPermissions + manifest.contentScripts.flatMap { it.matches })
            .map { it.trim() }
            .filter { it.isNotEmpty() && MatchPattern.parse(it) != null }
            .distinct()
}
