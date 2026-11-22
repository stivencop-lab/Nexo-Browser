package com.nexo.browser.shield

import java.net.URLDecoder

/** Ajustes de PhantomShield (mismas claves y valores por defecto que la extensión). */
internal data class ShieldSettings(
    val adblockEnabled: Boolean = true,
    val privacyEnabled: Boolean = false,
    val popupBlockEnabled: Boolean = true,
    val antiBlockEnabled: Boolean = false,
    val disabledDomains: List<String> = emptyList(),
    val protectionLevel: String = LEVEL_BALANCED,
    val annoyancesEnabled: Boolean = true,
    val cryptominerEnabled: Boolean = true,
    val gpcEnabled: Boolean = true,
    val queryStrippingEnabled: Boolean = true,
    val allowedDomains: List<String> = emptyList(),
    val userCosmeticRules: Map<String, List<String>> = emptyMap()
) {
    fun isHostDisabled(hostname: String?): Boolean {
        if (hostname.isNullOrEmpty()) return false
        val host = hostname.removePrefix("www.")
        return disabledDomains.any { ShieldPolicy.hostnameMatches(host, it) }
    }

    fun isRequestHostAllowed(hostname: String?): Boolean {
        if (hostname.isNullOrEmpty() || allowedDomains.isEmpty()) return false
        val host = hostname.removePrefix("www.")
        return allowedDomains.any { ShieldPolicy.hostnameMatches(host, it) }
    }

    fun isEnabledForHost(hostname: String?): Boolean = adblockEnabled && !isHostDisabled(hostname)

    companion object {
        const val LEVEL_BALANCED = "balanced"
        const val LEVEL_STRICT = "strict"
        const val LEVEL_AGGRESSIVE = "aggressive"

        fun normalizeLevel(raw: String?): String = when (raw?.lowercase()) {
            LEVEL_STRICT -> LEVEL_STRICT
            LEVEL_AGGRESSIVE -> LEVEL_AGGRESSIVE
            else -> LEVEL_BALANCED
        }

        fun normalizeDomains(values: List<Any?>): List<String> =
            values.filterIsInstance<String>().filter { it.isNotBlank() }.map { it.trim().lowercase().removePrefix("www.").trimEnd('.') }.distinct()
    }
}

internal enum class BlockCategory(val id: String) {
    AD("ad"),
    TRACKER("tracker"),
    POPUP("popup"),
    MINER("miner"),
    THREAT("threat"),
    DNR("dnr")
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
        "gclid", "dclid", "fbclid", "msclkid", "mc_eid", "mc_cid", "yclid", "ysclid", "ymclid",
        "_hsenc", "_hsmi", "gbraid", "wbraid", "gclsrc", "gad_source", "gad_campaignid",
        "utm_id", "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_source_platform", "utm_creative_format", "utm_marketing_tactic", "utm_reader", "utm_name", "utm_cid",
        "igsh", "igshid", "ttclid", "twclid", "li_fat_id", "sclid", "srsltid", "mkt_tok",
        "hsa_acc", "hsa_cam", "hsa_grp", "hsa_ad", "hsa_src", "hsa_tgt", "hsa_kw", "hsa_mt", "hsa_net", "hsa_ver",
        "epik", "vero_id", "vero_conv", "oly_enc_id", "oly_anon_id", "rb_clickid", "wickedid", "_openstat",
        "fb_action_ids", "fb_action_types", "fb_source",
        "ml_subscriber", "ml_subscriber_hash", "s_cid", "icid", "ncid", "irclickid", "zanpid", "ndclid",
        "ko_click_id", "sc_cid", "wt_mc", "wt_zmc",
        "pk_campaign", "pk_kwd", "pk_source", "pk_medium", "pk_content",
        "mtm_campaign", "mtm_kwd", "mtm_source", "mtm_medium", "mtm_content", "mtm_cid",
        "matomo_campaign", "matomo_kwd", "piwik_campaign", "piwik_kwd",
        "oft_id", "oft_k", "oft_lk", "oft_d", "oft_c", "oft_ck", "oft_ids", "oft_sk"
    )

    /** Parámetro `si` solo en hosts de compartición conocidos (YouTube y Spotify), para evitar falsos positivos. */
    private val SI_PARAM_HOSTS = setOf("youtube.com", "youtu.be", "open.spotify.com", "spotify.com")

    /** Envoltorios de redirección que registran clics salientes antes de llevar al destino real. */
    private val BOUNCE_REDIRECTS = listOf(
        Triple("l.facebook.com", "/l.php", "u"),
        Triple("lm.facebook.com", "/l.php", "u"),
        Triple("m.facebook.com", "/l.php", "u"),
        Triple("l.instagram.com", "/", "u"),
        Triple("out.reddit.com", "/", "url"),
        Triple("t.umblr.com", "/redirect", "z"),
        Triple("youtube.com", "/redirect", "q")
    )

    /** Dominios conocidos de criptominería en navegador (WebAssembly/JS coinjackers). */
    internal val CRYPTOMINER_HOSTS = setOf(
        "coinhive.com", "coin-hive.com", "authedmine.com", "cnhv.co",
        "crypto-loot.com", "cryptoloot.pro", "webminepool.com", "webmine.cz",
        "jsecoin.com", "minero.cc", "monerominer.rocks", "coinimp.com",
        "hostingcloud.racing", "ppoi.org", "miner.pr0gramm.com", "deepminer.net",
        "afminer.com", "2giga.link", "reasedoper.pw", "mataharirama.xyz",
        "listat.biz", "lmodr.biz", "minecrunch.co", "coinerra.com",
        "minemytraffic.com", "cryptobara.com", "papoto.com", "coinnebula.com",
        "browsermine.com", "bmst.pw", "minr.pw", "xbasfbno.info"
    )

    /** Selectores de alta confianza para ocultar banners de cookies y overlays molestos sin romper páginas. */
    internal val ANNOYANCE_COSMETIC_SELECTORS = listOf(
        "#onetrust-consent-sdk",
        "#onetrust-banner-sdk",
        "#CybotCookiebotDialog",
        "#CybotCookiebotDialogBodyUnderlay",
        ".qc-cmp2-container",
        "#qc-cmp2-container",
        ".fc-consent-root",
        "#didomi-host",
        ".didomi-popup-container",
        "#truste-consent-track",
        "#usercentrics-root",
        ".cc-window.cc-banner",
        "#cookie-law-info-bar",
        ".osano-cm-window",
        "[id^=\"sp_message_container_\"]",
        ".cky-consent-container",
        "#cmplz-cookiebanner-container",
        "#borlabs-cookie-box",
        ".iubenda-cs-container",
        "#cmpbox",
        "#cmpbox2",
        ".klaviyo-popup-overlay"
    )

    /** Veto de seguridad funcional (traducción de `data/functional-safety.js`) para heurísticas y dominios aprendidos. */
    private val PROTECTED_HOSTS = setOf(
        "accounts.google.com", "appleid.apple.com", "microsoftonline.com", "auth0.com", "clerk.com", "clerk.accounts.dev",
        "challenges.cloudflare.com", "recaptcha.net", "hcaptcha.com", "stripe.com", "paypal.com",
        "youtube.com", "youtube-nocookie.com", "youtu.be", "vimeo.com", "dailymotion.com", "twitch.tv",
        "spotify.com", "soundcloud.com", "voe.sx", "streamsb.net", "sbembed.com", "vidhide.com", "lulustream.com", "doodstream.com"
    )
    private val MEDIA_EXTENSION = Regex("""\.(?:m3u8|mpd|mp4|webm|m4s|ts|aac|mp3|ogg|wav|vtt|srt|ass|dfxp|ttml)$""", RegexOption.IGNORE_CASE)
    private val FUNCTIONAL_PATH = Regex(
        """(?:^|/)(?:embed|player|watch|v|video|stream|e|d|oauth2?|authorize|sso|login|signin|captcha|recaptcha|hcaptcha|turnstile|checkout|payment)(?:[/._-]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val AGGRESSIVE_TELEMETRY_PATH = Regex(
        """(?:^|/)(?:collect|telemetry|pixel|beacon|log_event|events/track|analytics/track)(?:[/._?-]|$)""",
        RegexOption.IGNORE_CASE
    )

    private val YOUTUBE_AD_PATHS = listOf("/pagead/", "/api/stats/ads", "/get_midroll_", "/youtubei/v1/player/ad_break", "/ptracking")

    /** `surrogate-redirects.js`: host, ruta exacta y recurso local. */
    private val SURROGATES = listOf(
        Triple("google-analytics.com", "/analytics.js", "web-accessible/ga-surrogate.js"),
        Triple("googletagmanager.com", "/gtm.js", "web-accessible/gtm-surrogate.js"),
        Triple("googletagmanager.com", "/gtag/js", "web-accessible/ga-surrogate.js"),
        Triple("googletagservices.com", "/tag/js/gpt.js", "web-accessible/noop.js"),
        Triple("connect.facebook.net", "/en_US/fbevents.js", "web-accessible/noop.js"),
        Triple("pagead2.googlesyndication.com", "/pagead/js/adsbygoogle.js", "web-accessible/noop.js"),
        Triple("c.amazon-adsystem.com", "/aax2/apstag.js", "web-accessible/noop.js")
    )

    private val TWO_PART_SUFFIX = Regex("""\.(?:com?|org|net|gov|edu|ac)\.[a-z]{2}$""")

    fun hostnameMatches(hostname: String, domain: String): Boolean =
        hostname == domain || hostname.endsWith(".$domain")

    private fun hostInSet(hostname: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var candidate = hostname.lowercase().removeSuffix(".")
        while (candidate.isNotEmpty()) {
            if (candidate in set) return true
            val dot = candidate.indexOf('.')
            if (dot == -1) return false
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    fun isCryptominerHost(hostname: String?, extraMiners: Set<String> = emptySet()): Boolean {
        val host = hostname?.lowercase()?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: return false
        return hostInSet(host, CRYPTOMINER_HOSTS) || hostInSet(host, extraMiners)
    }

    fun isThreatHost(hostname: String?, threatDomains: Set<String>): Boolean {
        val host = hostname?.lowercase()?.removeSuffix(".")?.takeIf { it.isNotEmpty() } ?: return false
        return hostInSet(host, threatDomains)
    }

    /** Cierto para inicios de sesión, CAPTCHA, pagos o vídeo funcional que nunca debe cortar una heurística. */
    fun isProtectedSafetyUrl(url: String?): Boolean {
        if (url.isNullOrEmpty() || url.length > 4096) return false
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return false
        val host = UrlParts.host(url) ?: return false
        val path = UrlParts.path(url)
        return hostInSet(host, PROTECTED_HOSTS) || MEDIA_EXTENSION.containsMatchIn(path) || FUNCTIONAL_PATH.containsMatchIn(path)
    }

    fun siteOfHost(host: String?): String {
        val value = host.orEmpty().lowercase()
        val parts = value.split('.').filter { it.isNotEmpty() }
        return parts.takeLast(if (TWO_PART_SUFFIX.containsMatchIn(value)) 3 else 2).joinToString(".")
    }

    /** Desenvuelve redirecciones de rastreo conocidas (`l.facebook.com`, `out.reddit.com`...) si el destino es HTTP(S). */
    fun unwrapBounceRedirect(url: String): String? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
        val host = UrlParts.host(url) ?: return null
        val path = UrlParts.path(url)
        val rule = BOUNCE_REDIRECTS.firstOrNull { (domain, expectedPath, _) ->
            hostnameMatches(host, domain) && (path == expectedPath || (expectedPath == "/" && path.isEmpty()))
        } ?: return null
        val queryStart = url.indexOf('?')
        if (queryStart == -1) return null
        val hash = url.indexOf('#', queryStart).let { if (it == -1) url.length else it }
        val targetParam = rule.third
        for (part in url.substring(queryStart + 1, hash).split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).lowercase()
            if (name != targetParam) continue
            val rawValue = part.substring(eq + 1)
            val decoded = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrNull()?.trim() ?: return null
            if ((decoded.startsWith("https://", ignoreCase = true) || decoded.startsWith("http://", ignoreCase = true)) &&
                decoded.length <= 4096 && UrlParts.host(decoded)?.isNotEmpty() == true) {
                return decoded
            }
        }
        return null
    }

    /** URL sin parámetros de rastreo, o `null` si no tenía ninguno. */
    fun stripTrackingParams(url: String): String? {
        val query = url.indexOf('?')
        if (query == -1 || !url.startsWith("http", ignoreCase = true)) return null
        val host = UrlParts.host(url).orEmpty()
        val stripSi = SI_PARAM_HOSTS.any { hostnameMatches(host, it) }
        val hash = url.indexOf('#', query).let { if (it == -1) url.length else it }
        val parts = url.substring(query + 1, hash).split('&')
        val kept = parts.filter { part ->
            val name = part.substringBefore('=')
            val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name).lowercase()
            part.isEmpty() || (decoded !in TRACKING_PARAMS && !(stripSi && decoded == "si"))
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

    /** Clasifica el motivo de bloqueo para métricas y registro en vivo por pestaña. */
    fun classifyBlocked(
        host: String?,
        url: String,
        type: String,
        popupHosts: List<String>,
        trackerHosts: Set<String> = emptySet(),
        minerHosts: Set<String> = emptySet(),
        threatHosts: Set<String> = emptySet()
    ): BlockCategory {
        if (type == "popup") return BlockCategory.POPUP
        val normalized = host?.lowercase()?.removeSuffix(".").orEmpty()
        if (normalized.isNotEmpty()) {
            if (isThreatHost(normalized, threatHosts)) return BlockCategory.THREAT
            if (isCryptominerHost(normalized, minerHosts)) return BlockCategory.MINER
            if (popupHosts.any { hostnameMatches(normalized, it) }) return BlockCategory.POPUP
            if (hostInSet(normalized, trackerHosts) || type == "ping" || AGGRESSIVE_TELEMETRY_PATH.containsMatchIn(UrlParts.path(url))) {
                return BlockCategory.TRACKER
            }
        }
        return BlockCategory.AD
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
        val host = UrlParts.host(url) ?: return ShieldDecision.Allow
        if (settings.isRequestHostAllowed(host)) return ShieldDecision.Allow
        if (type == "script") surrogateFor(url)?.let { return ShieldDecision.Surrogate(it) }
        if (type == "main_frame") {
            // Una página que el usuario abre no se cancela; solo las redes de ventanas emergentes.
            return if (popupHosts.any { hostnameMatches(host, it) }) ShieldDecision.BlockPage else ShieldDecision.Allow
        }
        if (settings.cryptominerEnabled && isCryptominerHost(host)) return ShieldDecision.Block
        if (isYouTubeAdRequest(url)) return ShieldDecision.Block
        if (engine.shouldBlock(url, type, documentUrl, countBlocked = false)) return ShieldDecision.Block

        val level = ShieldSettings.normalizeLevel(settings.protectionLevel)
        if (level == ShieldSettings.LEVEL_STRICT || level == ShieldSettings.LEVEL_AGGRESSIVE) {
            val isThirdParty = topHost != null && siteOfHost(host) != siteOfHost(topHost)
            if (isThirdParty && !isProtectedSafetyUrl(url)) {
                if (type == "ping") return ShieldDecision.Block
                if (level == ShieldSettings.LEVEL_AGGRESSIVE &&
                    (type == "script" || type == "xmlhttprequest" || type == "image") &&
                    AGGRESSIVE_TELEMETRY_PATH.containsMatchIn(UrlParts.path(url))) {
                    return ShieldDecision.Block
                }
            }
        }
        return ShieldDecision.Allow
    }

    /** Ventana nueva abierta por [openerUrl] hacia [url]: ¿es una red de "popunder"? */
    fun blocksPopup(engine: NetworkEngine, settings: ShieldSettings, popupHosts: List<String>, url: String, openerUrl: String?): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val openerHost = openerUrl?.let { UrlParts.host(it) }
        if (!settings.isEnabledForHost(openerHost) || !settings.popupBlockEnabled) return false
        val host = UrlParts.host(url) ?: return false
        if (settings.isRequestHostAllowed(host)) return false
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
