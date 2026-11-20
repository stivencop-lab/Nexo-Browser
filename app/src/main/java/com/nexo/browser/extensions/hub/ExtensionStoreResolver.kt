package com.nexo.browser.extensions.hub

import com.nexo.browser.UrlSafety
import java.net.URI
import java.net.URLEncoder

/**
 * Resolutor puro (JVM) de enlaces, identificadores y búsquedas de tiendas de extensiones
 * Chromium: Chrome Web Store, Microsoft Edge Add-ons y paquetes HTTPS directos (`.crx`, `.zip`, `.xpi`).
 *
 * No inyecta scripts en las tiendas ni falsifica el User-Agent: observa únicamente la URL
 * pública para ofrecer la instalación verificada en Nexo.
 */
object ExtensionStoreResolver {
    const val CWS_HOME_URL = "https://chromewebstore.google.com/"
    const val EDGE_HOME_URL = "https://microsoftedge.microsoft.com/addons/Microsoft-Edge-Extensions-Home"

    private val EXT_ID_REGEX = Regex("^[a-p]{32}$")
    private val PACKAGE_EXT_REGEX = Regex("(?i)\\.(crx|zip|xpi)$")
    private val LOCAL_HOSTS = setOf(
        "localhost", "local", "localdomain", "lan", "home.arpa", "internal", "test", "invalid", "onion"
    )

    enum class StoreKind(val label: String, val sourceOrigin: String) {
        CHROME_WEB_STORE("Chrome Web Store", "chrome_web_store"),
        EDGE_ADDONS("Microsoft Edge Add-ons", "edge_addons"),
        DIRECT_HTTPS_PACKAGE("URL HTTPS directa", "direct_url")
    }

    sealed class ResolvedTarget {
        data class StoreExtension(
            val store: StoreKind,
            val extensionId: String,
            val detailUrl: String
        ) : ResolvedTarget()

        data class DirectPackage(
            val url: String,
            val fileName: String
        ) : ResolvedTarget()

        data class RawExtensionId(val extensionId: String) : ResolvedTarget()

        data class StoreSearch(val query: String) : ResolvedTarget()
    }

    fun isValidExtensionId(candidate: String?): Boolean =
        candidate != null && EXT_ID_REGEX.matches(candidate.trim().lowercase())

    fun cwsDetailUrl(extensionId: String): String =
        "https://chromewebstore.google.com/detail/${extensionId.lowercase()}"

    fun edgeDetailUrl(extensionId: String): String =
        "https://microsoftedge.microsoft.com/addons/detail/${extensionId.lowercase()}"

    fun cwsSearchUrl(query: String): String =
        "https://chromewebstore.google.com/search/${encodeSegment(query)}"

    fun edgeSearchUrl(query: String): String =
        "https://microsoftedge.microsoft.com/addons/search/${encodeSegment(query)}"

    private fun encodeSegment(query: String): String =
        URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")

    private val LOCALE_SEGMENT_REGEX = Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,4})?$")

    /**
     * Detecta cualquier página de tienda oficial de extensiones Chromium
     * (Chrome Web Store o Microsoft Edge Add-ons: inicio, búsqueda, categorías o ficha)
     * para activar automáticamente el modo escritorio en la pestaña.
     */
    fun isExtensionStoreUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return false
        val scheme = UrlSafety.scheme(raw)
        if (scheme != "https" && scheme != "http") return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        val segments = (uri.path ?: "").split('/').filter { it.isNotBlank() }

        if (host == "chromewebstore.google.com") return true
        if (host == "chrome.google.com") {
            return segments.firstOrNull()?.equals("webstore", ignoreCase = true) == true
        }
        if (host == "microsoftedge.microsoft.com") {
            val first = segments.getOrNull(0)
            val second = segments.getOrNull(1)
            return first?.equals("addons", ignoreCase = true) == true ||
                (first != null && LOCALE_SEGMENT_REGEX.matches(first) && second?.equals("addons", ignoreCase = true) == true)
        }
        return false
    }

    /**
     * Detecta si [url] corresponde a la ficha oficial de una extensión en Chrome Web Store
     * o Microsoft Edge Add-ons (usado por la barra contextual «Instalar en Nexo»).
     */
    fun detectStorePage(url: String?): ResolvedTarget.StoreExtension? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith("https://", ignoreCase = true)) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val segments = (uri.path ?: "").split('/').filter { it.isNotBlank() }

        // Chrome Web Store:
        // - https://chromewebstore.google.com/detail/<slug>/<32id>
        // - https://chromewebstore.google.com/detail/<32id>
        // - https://chrome.google.com/webstore/detail/<slug>/<32id>
        if (host == "chromewebstore.google.com") {
            val idx = segments.indexOfFirst { it.equals("detail", ignoreCase = true) }
            if (idx >= 0) {
                val id = segments.drop(idx + 1).lastOrNull { isValidExtensionId(it) }?.lowercase()
                if (id != null) {
                    return ResolvedTarget.StoreExtension(StoreKind.CHROME_WEB_STORE, id, cwsDetailUrl(id))
                }
            }
        }
        if (host == "chrome.google.com") {
            val wsIdx = segments.indexOfFirst { it.equals("webstore", ignoreCase = true) }
            val detIdx = segments.indexOfFirst { it.equals("detail", ignoreCase = true) }
            if (wsIdx >= 0 && detIdx == wsIdx + 1) {
                val id = segments.drop(detIdx + 1).lastOrNull { isValidExtensionId(it) }?.lowercase()
                if (id != null) {
                    return ResolvedTarget.StoreExtension(StoreKind.CHROME_WEB_STORE, id, cwsDetailUrl(id))
                }
            }
        }

        // Microsoft Edge Add-ons:
        // - https://microsoftedge.microsoft.com/addons/detail/<slug>/<32id>
        // - https://microsoftedge.microsoft.com/es-es/addons/detail/<slug>/<32id>
        // - https://microsoftedge.microsoft.com/addons/detail/<32id>
        if (host == "microsoftedge.microsoft.com") {
            val addonsIdx = segments.indexOfFirst { it.equals("addons", ignoreCase = true) }
            val detIdx = segments.indexOfFirst { it.equals("detail", ignoreCase = true) }
            val validPrefix = addonsIdx == 0 || (addonsIdx == 1 && LOCALE_SEGMENT_REGEX.matches(segments[0]))
            if (validPrefix && detIdx == addonsIdx + 1) {
                val id = segments.drop(detIdx + 1).lastOrNull { isValidExtensionId(it) }?.lowercase()
                if (id != null) {
                    return ResolvedTarget.StoreExtension(StoreKind.EDGE_ADDONS, id, edgeDetailUrl(id))
                }
            }
        }

        return null
    }

    /** Comprueba si [url] es una URL HTTPS pública segura que apunta a `.crx`, `.zip` o `.xpi`. */
    fun isDirectExtensionPackageUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (!isPublicHttpsUrl(raw)) return false
        val path = runCatching { URI(raw).path }.getOrNull().orEmpty()
        return PACKAGE_EXT_REGEX.containsMatchIn(path)
    }

    /** Comprueba que [url] use `https://` hacia un host público (sin IP privada, localhost ni credenciales). */
    fun isPublicHttpsUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty() || raw.any { it <= ' ' }) return false
        if (UrlSafety.scheme(raw) != "https") return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (uri.isOpaque || uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (host.length > 253 || !host.contains('.') || host.contains(':')) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        if (LOCAL_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        if (uri.port != -1 && uri.port != 443) return false
        return true
    }

    /** Resuelve la entrada del buscador del Extension Hub: ficha de tienda, URL directa, ID de 32 letras o búsqueda. */
    fun resolveInput(input: String?): ResolvedTarget? {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return null

        detectStorePage(text)?.let { return it }

        if (isDirectExtensionPackageUrl(text)) {
            val path = runCatching { URI(text).path }.getOrNull().orEmpty()
            val fileName = path.substringAfterLast('/').ifBlank { "extension.crx" }
            return ResolvedTarget.DirectPackage(text, fileName)
        }

        if (isValidExtensionId(text)) {
            return ResolvedTarget.RawExtensionId(text.lowercase())
        }

        // Permitir URLs pegadas sin "https://" cuando son de CWS o Edge Add-ons.
        if (!text.contains(' ') && (
                text.startsWith("chromewebstore.google.com/", ignoreCase = true) ||
                    text.startsWith("chrome.google.com/webstore/", ignoreCase = true) ||
                    text.startsWith("microsoftedge.microsoft.com/", ignoreCase = true)
                )
        ) {
            detectStorePage("https://$text")?.let { return it }
        }

        return ResolvedTarget.StoreSearch(text)
    }

    fun isAllowedCwsDownloadHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h == "clients2.google.com" ||
            h == "clients2.googleusercontent.com" ||
            h == "dl.google.com" ||
            h.endsWith(".googleusercontent.com") ||
            h.endsWith(".gvt1.com") ||
            h.endsWith(".gvt2.com") ||
            h.endsWith(".dl.google.com")
    }

    fun isAllowedEdgeDownloadHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h == "edge.microsoft.com" ||
            h == "microsoftedge.microsoft.com" ||
            h.endsWith(".delivery.mp.microsoft.com") ||
            h.endsWith(".microsoft.com")
    }

    /** Compara versiones numéricas estilo WebExtension (`1.10.2` > `1.2.9`). */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().split('.').map { it.toLongOrNull() ?: 0L }
        val pb = b.trim().split('.').map { it.toLongOrNull() ?: 0L }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val va = pa.getOrElse(i) { 0L }
            val vb = pb.getOrElse(i) { 0L }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
