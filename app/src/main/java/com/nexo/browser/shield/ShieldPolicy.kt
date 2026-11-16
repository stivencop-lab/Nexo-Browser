package com.nexo.browser.shield

import java.net.URLDecoder

/** Ajustes de PhantomShield (mismas claves y valores por defecto que la extensión). */
internal data class ShieldSettings(
    val adblockEnabled: Boolean = true,
    val privacyEnabled: Boolean = false,
    val popupBlockEnabled: Boolean = true,
    val antiBlockEnabled: Boolean = false,
    val disabledDomains: List<String> = emptyList()
) {
    fun isHostDisabled(hostname: String?): Boolean {
        if (hostname.isNullOrEmpty()) return false
        val host = hostname.removePrefix("www.")
        return disabledDomains.any { ShieldPolicy.hostnameMatches(host, it) }
    }

    fun isEnabledForHost(hostname: String?): Boolean = adblockEnabled && !isHostDisabled(hostname)

    companion object {
        fun normalizeDomains(values: List<Any?>): List<String> =
            values.filterIsInstance<String>().filter { it.isNotBlank() }.map { it.trim().lowercase().removePrefix("www.") }
    }
}

internal sealed class ShieldDecision {
    object Allow : ShieldDecision()
    object Block : ShieldDecision()
    /** Script de sustitución local (`web-accessible/…`) en lugar del rastreador. */
    data class Surrogate(val asset: String) : ShieldDecision()
    /** Página principal hacia una red de ventanas emergentes: no se carga. */
    object BlockPage : ShieldDecision()
}

/**
 * Decisiones de `webRequest.onBeforeRequest` de `background/background.js`
 * para una petición de Chromium. Sin Android: se prueba en la JVM.
 */
internal object ShieldPolicy {
    private val TRACKING_PARAMS = setOf(
        "gclid", "dclid", "fbclid", "msclkid", "mc_eid", "yclid", "_hsenc",
        "gbraid", "wbraid", "utm_id", "utm_source", "utm_medium",
        "utm_campaign", "utm_term", "utm_content"
    )

    private val YOUTUBE_AD_PATHS = listOf("/pagead/", "/api/stats/ads", "/get_midroll_", "/youtubei/v1/player/ad_break", "/ptracking")

    /** `surrogate-redirects.js`: host, ruta exacta y recurso local. */
    private val SURROGATES = listOf(
        Triple("google-analytics.com", "/analytics.js", "web-accessible/ga-surrogate.js"),
        Triple("googletagmanager.com", "/gtm.js", "web-accessible/gtm-surrogate.js"),
        Triple("googletagservices.com", "/tag/js/gpt.js", "web-accessible/noop.js"),
        Triple("connect.facebook.net", "/en_US/fbevents.js", "web-accessible/noop.js")
    )

    private val TWO_PART_SUFFIX = Regex("""\.(?:com?|org|net|gov|edu|ac)\.[a-z]{2}$""")

    fun hostnameMatches(hostname: String, domain: String): Boolean =
        hostname == domain || hostname.endsWith(".$domain")

    fun siteOfHost(host: String?): String {
        val value = host.orEmpty().lowercase()
        val parts = value.split('.').filter { it.isNotEmpty() }
        return parts.takeLast(if (TWO_PART_SUFFIX.containsMatchIn(value)) 3 else 2).joinToString(".")
    }

    /** URL sin parámetros de rastreo, o `null` si no tenía ninguno. */
    fun stripTrackingParams(url: String): String? {
        val query = url.indexOf('?')
        if (query == -1 || !url.startsWith("http", ignoreCase = true)) return null
        val hash = url.indexOf('#', query).let { if (it == -1) url.length else it }
        val parts = url.substring(query + 1, hash).split('&')
        val kept = parts.filter { part ->
            val name = part.substringBefore('=')
            val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
            part.isEmpty() || decoded.lowercase() !in TRACKING_PARAMS
        }
        if (kept.size == parts.size) return null
        val rest = kept.filter { it.isNotEmpty() }.joinToString("&")
        return url.substring(0, query) + (if (rest.isEmpty()) "" else "?$rest") + url.substring(hash)
    }

    fun isYouTubeAdRequest(url: String): Boolean {
        val host = UrlParts.host(url) ?: return false
        if (!(hostnameMatches(host, "youtube.com") || hostnameMatches(host, "youtube-nocookie.com") ||
                hostnameMatches(host, "youtubekids.com"))) return false
        val path = UrlParts.path(url)
        return YOUTUBE_AD_PATHS.any { path.startsWith(it) }
    }

    fun surrogateFor(url: String): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val host = UrlParts.host(url) ?: return null
        val path = UrlParts.path(url)
        return SURROGATES.firstOrNull { (domain, exact, _) -> hostnameMatches(host, domain) && path == exact }?.third
    }

    /**
     * Decisión para una petición. [topHost]: host de la página principal de
     * la pestaña; [documentUrl]: documento que pide (el marco que la hace, o la página
     * principal).
     */
    fun decide(
        engine: NetworkEngine,
        settings: ShieldSettings,
        popupHosts: List<String>,
        url: String,
        type: String,
        topHost: String?,
        documentUrl: String?
    ): ShieldDecision {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ShieldDecision.Allow
        if (!settings.isEnabledForHost(topHost)) return ShieldDecision.Allow
        if (type == "script") surrogateFor(url)?.let { return ShieldDecision.Surrogate(it) }
        val host = UrlParts.host(url) ?: return ShieldDecision.Allow
        if (type == "main_frame") {
            // Una página que el usuario abre no se cancela; solo las redes de ventanas emergentes.
            return if (popupHosts.any { hostnameMatches(host, it) }) ShieldDecision.BlockPage else ShieldDecision.Allow
        }
        if (isYouTubeAdRequest(url)) return ShieldDecision.Block
        if (engine.shouldBlock(url, type, documentUrl, countBlocked = false)) return ShieldDecision.Block
        return ShieldDecision.Allow
    }

    /** Ventana nueva abierta por [openerUrl] hacia [url]: ¿es una red de "popunder"? */
    fun blocksPopup(engine: NetworkEngine, settings: ShieldSettings, popupHosts: List<String>, url: String, openerUrl: String?): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val openerHost = openerUrl?.let { UrlParts.host(it) }
        if (!settings.isEnabledForHost(openerHost) || !settings.popupBlockEnabled) return false
        val host = UrlParts.host(url) ?: return false
        if (popupHosts.any { hostnameMatches(host, it) }) return true
        if (openerUrl == null || !openerUrl.startsWith("http")) return false
        if (siteOfHost(openerHost) == siteOfHost(host)) return false
        return engine.shouldBlock(url, "popup", openerUrl, countBlocked = false)
    }

    /**
     * Tipo de recurso que informa Chromium (nombre de `CefriumResourceType`).
     * Solo `OTHER` y los desconocidos se deducen de la URL.
     */
    fun typeFromEngine(name: String, url: String): String = when (name) {
        "MAIN_FRAME" -> "main_frame"
        "SUB_FRAME" -> "sub_frame"
        "STYLESHEET" -> "stylesheet"
        "SCRIPT", "WORKER", "SERVICE_WORKER" -> "script"
        "IMAGE", "FAVICON" -> "image"
        "FONT" -> "font"
        "MEDIA" -> "media"
        "XHR" -> "xmlhttprequest"
        "PING" -> "ping"
        "PREFETCH" -> "other"
        else -> inferType(url, false, null, null)
    }

    /**
     * Tipo de recurso deducido de `Sec-Fetch-Dest`
     * (si viene), de `Accept` y de la extensión. `unknown` desactiva las
     * reglas que dependen del tipo (más vale no bloquear que romper).
     */
    fun inferType(url: String, isMainFrame: Boolean, accept: String?, fetchDest: String?): String {
        if (isMainFrame) return "main_frame"
        when (fetchDest?.lowercase()) {
            "iframe", "frame", "fencedframe" -> return "sub_frame"
            "script", "worker", "sharedworker", "serviceworker", "audioworklet", "paintworklet" -> return "script"
            "style" -> return "stylesheet"
            "image" -> return "image"
            "font" -> return "font"
            "audio", "video", "track" -> return "media"
            "object", "embed" -> return "object"
            "empty" -> return "xmlhttprequest"
        }
        val acceptValue = accept?.lowercase().orEmpty()
        if (acceptValue.startsWith("text/html")) return "sub_frame"
        if (acceptValue.startsWith("text/css")) return "stylesheet"
        if (acceptValue.startsWith("image/")) return "image"
        val path = UrlParts.path(url).lowercase()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when (ext) {
            "js", "mjs" -> "script"
            "css" -> "stylesheet"
            "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp" -> "image"
            "woff", "woff2", "ttf", "otf", "eot" -> "font"
            "mp4", "webm", "m3u8", "mpd", "mp3", "m4s", "aac", "ogg", "wav" -> "media"
            else -> NetworkEngine.UNKNOWN
        }
    }
}
