package com.nexo.browser

import android.content.Context

object BrowserPrefs {
    private const val FILE = "nexo_browser_settings"

    const val THEME_SYSTEM = "system"
    const val THEME_MIDNIGHT = "midnight"
    const val THEME_OLED = "oled"
    const val THEME_LIGHT = "light"

    // Los acentos guardados en versiones anteriores se migran al leerlos.
    const val ACCENT_MONO = "mono"
    const val ACCENT_SIGNAL = "signal"
    const val ACCENT_COBALT = "cobalt"
    const val ACCENT_ORANGE = "orange"
    const val ACCENT_SAGE = "sage"
    val ACCENTS = listOf(ACCENT_MONO, ACCENT_COBALT, ACCENT_ORANGE, ACCENT_SAGE)

    /** Cromo inferior: «Liquid Split» (por defecto) o el Dock clásico refinado. */
    const val CAPTION_POS_VIDEO = "video"
    const val CAPTION_POS_TOP = "top"
    const val CAPTION_POS_CENTER = "center"
    const val CAPTION_POS_BOTTOM = "bottom"
    const val CAPTION_POS_CUSTOM = "custom"
    val CAPTION_POSITIONS = setOf(CAPTION_POS_VIDEO, CAPTION_POS_TOP, CAPTION_POS_CENTER, CAPTION_POS_BOTTOM, CAPTION_POS_CUSTOM)

    const val CHROME_LIQUID = "liquid"
    const val CHROME_DOCK = "dock"

    /** Acentos de UI 5.x/6.x → equivalente adulto más cercano de UI 7.0. */
    fun migrateAccent(raw: String?): String = when (raw) {
        null, "", "dynamic" -> ACCENT_MONO
        "violet", "cyan", ACCENT_SIGNAL -> ACCENT_COBALT
        "lime" -> ACCENT_SAGE
        "pink", "red" -> ACCENT_ORANGE
        in ACCENTS -> raw
        else -> ACCENT_MONO
    }

    const val ENGINE_GOOGLE = "google"
    const val ENGINE_DDG = "duckduckgo"
    const val ENGINE_BING = "bing"
    const val ENGINE_BRAVE = "brave"
    const val ENGINE_STARTPAGE = "startpage"

    const val DNS_CUSTOM = "custom"
    const val DNS_SYSTEM = "system"
    const val DNS_CLOUDFLARE = "cloudflare"
    const val DNS_GOOGLE = "google"
    const val DNS_QUAD9 = "quad9"

    const val COOKIES_BALANCED = "balanced"
    const val COOKIES_FIRST_PARTY = "first_party"
    const val COOKIES_ALL = "all"
    const val COOKIES_NONE = "none"

    /** Claves de versiones antiguas que ya no se usan (p. ej. una API key en texto plano). */
    private val legacyKeys = listOf("translator_api_key", "show_bridge_badge")

    private fun p(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun purgeLegacyKeys(c: Context) {
        val prefs = p(c)
        if (legacyKeys.none { prefs.contains(it) }) return
        prefs.edit().apply { legacyKeys.forEach { remove(it) } }.apply()
    }

    fun theme(c: Context) = p(c).getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
    fun setTheme(c: Context, v: String) = p(c).edit().putString("theme", v).apply()

    fun accent(c: Context) = migrateAccent(p(c).getString("accent", ACCENT_MONO))
    fun setAccent(c: Context, v: String) = p(c).edit().putString("accent", v).apply()

    fun searchEngine(c: Context) = p(c).getString("search", ENGINE_GOOGLE) ?: ENGINE_GOOGLE
    fun setSearchEngine(c: Context, v: String) = p(c).edit().putString("search", v).apply()

    fun freeSearch(c: Context) = p(c).getBoolean("free_search", false)
    fun setFreeSearch(c: Context, v: Boolean) = p(c).edit().putBoolean("free_search", v).apply()

    /** URL de la página de inicio propia de Nexo (accesos rápidos). */
    const val NEXO_HOME = "about:blank"

    fun isNexoHome(url: String?): Boolean =
        url.isNullOrBlank() || url.equals(NEXO_HOME, ignoreCase = true)

    fun nexoHome(c: Context) = p(c).getBoolean("nexo_home", true)
    fun setNexoHome(c: Context, v: Boolean) = p(c).edit().putBoolean("nexo_home", v).apply()

    /** Inicio y nueva pestaña: página de Nexo o la del buscador elegido. */
    fun homePage(c: Context): String =
        if (nexoHome(c)) NEXO_HOME else searchHome(c)

    fun searchHome(c: Context): String =
        when (searchEngine(c)) {
            ENGINE_DDG -> "https://duckduckgo.com/"
            ENGINE_BING -> "https://www.bing.com/"
            ENGINE_BRAVE -> "https://search.brave.com/"
            ENGINE_STARTPAGE -> "https://www.startpage.com/"
            else -> "https://www.google.com/"
        }

    fun restoreTabs(c: Context) = p(c).getBoolean("restore_tabs", true)
    fun setRestoreTabs(c: Context, v: Boolean) = p(c).edit().putBoolean("restore_tabs", v).apply()

    fun trackingProtection(c: Context) = p(c).getBoolean("tracking", true)
    fun setTrackingProtection(c: Context, v: Boolean) = p(c).edit().putBoolean("tracking", v).apply()

    fun globalPrivacyControl(c: Context) = p(c).getBoolean("gpc", true)
    fun setGlobalPrivacyControl(c: Context, v: Boolean) = p(c).edit().putBoolean("gpc", v).apply()

    fun httpsOnly(c: Context) = p(c).getBoolean("https_only", false)
    fun setHttpsOnly(c: Context, v: Boolean) = p(c).edit().putBoolean("https_only", v).apply()

    fun dnsCustom(c: Context) = p(c).getString("dns_custom", "").orEmpty()
    fun setDnsCustom(c: Context, value: String) = p(c).edit().putString("dns_custom",value).apply()
    fun dnsStrict(c: Context) = p(c).getBoolean("dns_strict",false)
    fun setDnsStrict(c: Context, value: Boolean) = p(c).edit().putBoolean("dns_strict",value).apply()
    fun dnsProvider(c: Context) = p(c).getString("dns_provider", DNS_SYSTEM) ?: DNS_SYSTEM
    fun setDnsProvider(c: Context, v: String) = p(c).edit().putString("dns_provider", v).apply()

    fun cookieMode(c: Context) = p(c).getString("cookie_mode", COOKIES_BALANCED) ?: COOKIES_BALANCED
    fun setCookieMode(c: Context, v: String) = p(c).edit().putString("cookie_mode", v).apply()

    const val AUTOPLAY_ALLOW = "allow"
    const val AUTOPLAY_BLOCK_AUDIBLE = "block_audible"
    const val AUTOPLAY_BLOCK_ALL = "block_all"

    fun textScale(c: Context) = p(c).getInt("text_scale", 100).coerceIn(80, 200)
    fun setTextScale(c: Context, v: Int) = p(c).edit().putInt("text_scale", v.coerceIn(80, 200)).apply()
    fun forceZoom(c: Context) = p(c).getBoolean("force_zoom", false)
    fun setForceZoom(c: Context, v: Boolean) = p(c).edit().putBoolean("force_zoom", v).apply()
    fun desktopDefault(c: Context) = p(c).getBoolean("desktop_default", false)
    fun setDesktopDefault(c: Context, v: Boolean) = p(c).edit().putBoolean("desktop_default", v).apply()
    fun autoplay(c: Context) = p(c).getString("autoplay", AUTOPLAY_ALLOW) ?: AUTOPLAY_ALLOW
    fun setAutoplay(c: Context, v: String) = p(c).edit().putString("autoplay", v).apply()
    fun javascript(c: Context) = p(c).getBoolean("javascript", true)
    fun setJavascript(c: Context, v: Boolean) = p(c).edit().putBoolean("javascript", v).apply()
    fun strictTracking(c: Context) = p(c).getBoolean("strict_tracking", false)
    fun setStrictTracking(c: Context, v: Boolean) = p(c).edit().putBoolean("strict_tracking", v).apply()
    fun safeBrowsing(c: Context) = p(c).getBoolean("safe_browsing", true)
    fun setSafeBrowsing(c: Context, v: Boolean) = p(c).edit().putBoolean("safe_browsing", v).apply()
    fun fingerprinting(c: Context) = p(c).getBoolean("fingerprinting", true)
    fun setFingerprinting(c: Context, v: Boolean) = p(c).edit().putBoolean("fingerprinting", v).apply()
    fun queryStripping(c: Context) = p(c).getBoolean("query_stripping", true)
    fun setQueryStripping(c: Context, v: Boolean) = p(c).edit().putBoolean("query_stripping", v).apply()
    fun clearOnExit(c: Context) = p(c).getBoolean("clear_on_exit", false)
    fun setClearOnExit(c: Context, v: Boolean) = p(c).edit().putBoolean("clear_on_exit", v).apply()
    fun remoteDebugging(c: Context) = p(c).getBoolean("remote_debugging", false)
    fun setRemoteDebugging(c: Context, v: Boolean) = p(c).edit().putBoolean("remote_debugging", v).apply()

    // Subtítulos en vivo (Deepgram) y su traducción.
    const val TRANSLATE_GOOGLE = "google"
    const val TRANSLATE_DEEPL = "deepl"
    fun captionsEnabled(c: Context) = p(c).getBoolean("captions_enabled", true)
    fun setCaptionsEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("captions_enabled", v).apply()
    /** Las claves viven cifradas en [SecretStore]; las de 0.22.0 (en claro) se migran al leerlas. */
    private fun secret(c: Context, name: String): String {
        val legacy = p(c).getString(name, null)
        if (legacy != null) {
            if (SecretStore.put(c, name, legacy)) {
                p(c).edit().remove(name).apply()
            } else {
                return legacy.trim()
            }
        }
        return SecretStore.get(c, name)
    }
    fun deepgramKey(c: Context) = secret(c, "deepgram_key")
    fun setDeepgramKey(c: Context, v: String) { SecretStore.put(c, "deepgram_key", v) }
    fun captionLanguage(c: Context) = p(c).getString("caption_language", "multi") ?: "multi"
    fun setCaptionLanguage(c: Context, v: String) = p(c).edit().putString("caption_language", v).apply()
    fun captionTranslateTo(c: Context) = p(c).getString("caption_translate", "") ?: ""
    fun setCaptionTranslateTo(c: Context, v: String) = p(c).edit().putString("caption_translate", v).apply()
    fun translationProvider(c: Context) = p(c).getString("translation_provider", TRANSLATE_GOOGLE) ?: TRANSLATE_GOOGLE
    fun setTranslationProvider(c: Context, v: String) = p(c).edit().putString("translation_provider", v).apply()
    fun deeplKey(c: Context) = secret(c, "deepl_key")
    fun setDeeplKey(c: Context, v: String) { SecretStore.put(c, "deepl_key", v) }
    // Traducción con un modelo de IA (por lotes, con contexto).
    const val TRANSLATE_AI = "ai"
    fun aiTranslateProvider(c: Context) = p(c).getString("ai_translate_provider", "openai") ?: "openai"
    fun setAiTranslateProvider(c: Context, v: String) = p(c).edit().putString("ai_translate_provider", v).apply()
    fun aiTranslateModel(c: Context) = p(c).getString("ai_translate_model", "") ?: ""
    fun setAiTranslateModel(c: Context, v: String) = p(c).edit().putString("ai_translate_model", v.trim()).apply()
    fun aiTranslateEndpoint(c: Context) = p(c).getString("ai_translate_endpoint", "") ?: ""
    fun setAiTranslateEndpoint(c: Context, v: String) = p(c).edit().putString("ai_translate_endpoint", v.trim()).apply()
    fun aiTranslateKey(c: Context) = secret(c, "ai_translate_key")
    fun setAiTranslateKey(c: Context, v: String) { SecretStore.put(c, "ai_translate_key", v) }
    // Segundo modelo de IA (otra API/proveedor): se piden los dos a la vez y gana el primero que responde.
    fun aiTranslate2Enabled(c: Context) = p(c).getBoolean("ai_translate2_enabled", false)
    fun setAiTranslate2Enabled(c: Context, v: Boolean) = p(c).edit().putBoolean("ai_translate2_enabled", v).apply()
    fun aiTranslate2Provider(c: Context) = p(c).getString("ai_translate2_provider", "openrouter") ?: "openrouter"
    fun setAiTranslate2Provider(c: Context, v: String) = p(c).edit().putString("ai_translate2_provider", v).apply()
    fun aiTranslate2Model(c: Context) = p(c).getString("ai_translate2_model", "") ?: ""
    fun setAiTranslate2Model(c: Context, v: String) = p(c).edit().putString("ai_translate2_model", v.trim()).apply()
    fun aiTranslate2Endpoint(c: Context) = p(c).getString("ai_translate2_endpoint", "") ?: ""
    fun setAiTranslate2Endpoint(c: Context, v: String) = p(c).edit().putString("ai_translate2_endpoint", v.trim()).apply()
    fun aiTranslate2Key(c: Context) = secret(c, "ai_translate2_key")
    fun setAiTranslate2Key(c: Context, v: String) { SecretStore.put(c, "ai_translate2_key", v) }
    // Original, traducido o ambos.
    fun captionMode(c: Context) = p(c).getString("caption_mode", "translated") ?: "translated"
    fun setCaptionMode(c: Context, v: String) = p(c).edit().putString("caption_mode", v).apply()
    // Límite diario de audio enviado a Deepgram (minutos; 0 = sin límite) y consumo de hoy.
    /** false (por defecto): sin subtítulos de texto, Nexo pregunta antes de enviar el audio a Deepgram. */
    fun captionAudioAuto(c: Context) = p(c).getBoolean("caption_audio_auto", false)
    fun setCaptionAudioAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("caption_audio_auto", v).apply()
    /** Priorizar captura universal de audio con Android (MediaProjection) al activar CC con Deepgram. */
    fun captionPreferDeviceAudio(c: Context) = p(c).getBoolean("caption_prefer_device_audio", false)
    fun setCaptionPreferDeviceAudio(c: Context, v: Boolean) = p(c).edit().putBoolean("caption_prefer_device_audio", v).apply()
    /** Sitios (host principal) donde el modo nocturno de Nexo no se aplica. */
    fun nightExceptions(c: Context): Set<String> = p(c).getStringSet("night_exceptions", emptySet()).orEmpty()
    fun setNightException(c: Context, host: String, excluded: Boolean) {
        val clean = host.lowercase().removePrefix("www.").take(253)
        if (clean.isBlank()) return
        val next = nightExceptions(c).toMutableSet().apply { if (excluded) add(clean) else remove(clean) }
        p(c).edit().putStringSet("night_exceptions", next.take(500).toSet()).apply()
    }
    fun isNightException(c: Context, host: String?): Boolean =
        host != null && host.lowercase().removePrefix("www.") in nightExceptions(c)

    /** Audio del motor por el mezclador de Android (latencia ~100 ms) para que la captura lo oiga. */
    fun captureFriendlyAudio(c: Context) = p(c).getBoolean("capture_friendly_audio", true)
    fun setCaptureFriendlyAudio(c: Context, v: Boolean) = p(c).edit().putBoolean("capture_friendly_audio", v).apply()
    fun captionDailyLimit(c: Context) = p(c).getInt("caption_daily_limit", 120).coerceIn(0, 1440)
    fun setCaptionDailyLimit(c: Context, v: Int) = p(c).edit().putInt("caption_daily_limit", v.coerceIn(0, 1440)).apply()
    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
    fun captionSecondsToday(c: Context): Int =
        if (p(c).getString("caption_usage_day", "") == today()) p(c).getInt("caption_usage_seconds", 0) else 0
    fun addCaptionSeconds(c: Context, seconds: Int) {
        if (seconds <= 0) return
        val total = captionSecondsToday(c) + seconds
        p(c).edit().putString("caption_usage_day", today()).putInt("caption_usage_seconds", total).apply()
    }
    fun captionSize(c: Context) = p(c).getInt("caption_size", 13).coerceIn(9, 24)
    fun setCaptionSize(c: Context, v: Int) = p(c).edit().putInt("caption_size", v.coerceIn(9, 24)).apply()
    // Diseño de los subtítulos: fondo ("solid", "soft", "none"), color del texto y longitud.
    fun captionBackground(c: Context) = p(c).getString("caption_background", "solid")?.takeIf { it in setOf("solid", "soft", "none") } ?: "solid"
    fun setCaptionBackground(c: Context, v: String) = p(c).edit().putString("caption_background", v).apply()
    fun captionColor(c: Context) = p(c).getString("caption_color", "white")?.takeIf { it in setOf("white", "yellow") } ?: "white"
    fun setCaptionColor(c: Context, v: String) = p(c).edit().putString("caption_color", v).apply()
    /** Líneas visibles (1–3): también fija cuánto texto se conserva en pantalla. */
    fun captionLines(c: Context) = p(c).getInt("caption_lines", 2).coerceIn(1, 3)
    fun setCaptionLines(c: Context, v: Int) = p(c).edit().putInt("caption_lines", v.coerceIn(1, 3)).apply()
    /** Con traducción: no mostrar el idioma original mientras llega la traducción. */
    fun captionHideOriginal(c: Context) = p(c).getBoolean("caption_hide_original", true)
    fun setCaptionHideOriginal(c: Context, v: Boolean) = p(c).edit().putBoolean("caption_hide_original", v).apply()
    /** Segundos sin voz tras los que se borra el último subtítulo. */
    fun captionClearSeconds(c: Context) = p(c).getInt("caption_clear_seconds", 3).coerceIn(1, 10)
    fun setCaptionClearSeconds(c: Context, v: Int) = p(c).edit().putInt("caption_clear_seconds", v.coerceIn(1, 10)).apply()

    fun pullToRefresh(c: Context) = p(c).getBoolean("pull_to_refresh", true)
    /** Deslizar la barra inferior: a los lados cambia de pestaña, hacia arriba abre el selector. */
    fun dockGestures(c: Context) = p(c).getBoolean("dock_gestures", true)
    fun setDockGestures(c: Context, v: Boolean) = p(c).edit().putBoolean("dock_gestures", v).apply()
    /** Modo de barra de navegación adaptable: classic, compact o custom. */
    fun navMode(c: Context) = p(c).getString("nav_mode", NavConfig.Mode.CLASSIC.id) ?: NavConfig.Mode.CLASSIC.id
    fun setNavMode(c: Context, v: String) = p(c).edit().putString("nav_mode", v).apply()
    fun navActions(c: Context) = p(c).getString("nav_actions", NavConfig.serializeActions(NavConfig.DEFAULT_ACTIONS))
        ?: NavConfig.serializeActions(NavConfig.DEFAULT_ACTIONS)
    fun setNavActions(c: Context, v: String) = p(c).edit().putString("nav_actions", v).apply()
    fun chromeStyle(c: Context) = p(c).getString("chrome_style", CHROME_LIQUID)
        ?.takeIf { it == CHROME_LIQUID || it == CHROME_DOCK } ?: CHROME_LIQUID
    fun setChromeStyle(c: Context, v: String) = p(c).edit().putString("chrome_style", v).apply()
    /** «Una API para todo»: todas las funciones de IA usan la conexión de Nexo AI (por defecto). */
    fun unifiedAi(c: Context) = p(c).getBoolean("unified_ai", true)
    fun setUnifiedAi(c: Context, v: Boolean) = p(c).edit().putBoolean("unified_ai", v).apply()
    /** Subtítulos: "video" (sobre el video), "top", "center", "bottom" o "custom" (arrastrados). */
    fun captionPosition(c: Context) = p(c).getString("caption_position", CAPTION_POS_VIDEO)
        ?.takeIf { it in CAPTION_POSITIONS } ?: CAPTION_POS_VIDEO
    fun setCaptionPosition(c: Context, v: String) = p(c).edit().putString("caption_position", v).apply()
    /** Posición vertical guardada al arrastrar (0 = arriba, 1 = abajo del área visible). */
    fun captionCustomY(c: Context) = p(c).getFloat("caption_custom_y", 0.82f).coerceIn(0f, 1f)
    fun setCaptionCustomY(c: Context, v: Float) = p(c).edit().putFloat("caption_custom_y", v.coerceIn(0f, 1f)).apply()
    /** Escáner de páginas: heurística local (sin red) activada por defecto. */
    fun malwareScan(c: Context) = p(c).getBoolean("malware_scan", true)
    fun setMalwareScan(c: Context, v: Boolean) = p(c).edit().putBoolean("malware_scan", v).apply()
    /** Segunda opinión con IA para páginas dudosas: envía URL y texto al proveedor, así que es opcional. */
    fun malwareScanAi(c: Context) = p(c).getBoolean("malware_scan_ai", false)
    fun setMalwareScanAi(c: Context, v: Boolean) = p(c).edit().putBoolean("malware_scan_ai", v).apply()
    fun minimalStart(c: Context) = p(c).getBoolean("minimal_start", true)
    fun setMinimalStart(c: Context, v: Boolean) = p(c).edit().putBoolean("minimal_start", v).apply()
    fun autoHideBars(c: Context) = p(c).getBoolean("auto_hide_bars", true)
    fun setAutoHideBars(c: Context, v: Boolean) = p(c).edit().putBoolean("auto_hide_bars", v).apply()
    /** Vista preferida de Nexo Tabs Pro: grid, compact o list. */
    fun tabViewMode(c: Context) = p(c).getString("tab_view_mode", TabProModel.ViewMode.GRID.id) ?: TabProModel.ViewMode.GRID.id
    fun setTabViewMode(c: Context, v: String) = p(c).edit().putString("tab_view_mode", v).apply()
    /** Repartir archivos grandes en varias conexiones (si el servidor lo admite). */
    fun parallelDownloads(c: Context) = p(c).getBoolean("parallel_downloads", true)
    fun setParallelDownloads(c: Context, v: Boolean) = p(c).edit().putBoolean("parallel_downloads", v).apply()
    fun setPullToRefresh(c: Context, v: Boolean) = p(c).edit().putBoolean("pull_to_refresh", v).apply()
    fun smartPip(c: Context) = p(c).getBoolean("smart_pip", true)
    fun setSmartPip(c: Context, v: Boolean) = p(c).edit().putBoolean("smart_pip", v).apply()

    fun backgroundMedia(c: Context) = p(c).getBoolean("background_media", true)
    fun setBackgroundMedia(c: Context, v: Boolean) = p(c).edit().putBoolean("background_media", v).apply()

    fun translatorTarget(c: Context) =
        p(c).getString("translator_target", "es") ?: "es"
    fun setTranslatorTarget(c: Context, v: String) =
        p(c).edit().putString("translator_target", v).apply()

    fun maxLiveTabs(c: Context) = p(c).getInt("max_live", 5).coerceIn(1, 8)
    fun setMaxLiveTabs(c: Context, v: Int) = p(c).edit().putInt("max_live", v.coerceIn(1, 8)).apply()

    /** Formato antiguo: solo se lee una vez para migrar a [TabStore]. */
    fun tabsJson(c: Context) = p(c).getString("tabs_json", null)
    fun clearTabsJson(c: Context) = p(c).edit().remove("tabs_json").apply()

    fun activeTabId(c: Context) = p(c).getString("active_tab", null)
    fun setActiveTabId(c: Context, v: String?) = p(c).edit().putString("active_tab", v).apply()

    /** Restablece los ajustes visuales, de privacidad y navegación a sus valores por defecto sin borrar pestañas ni claves. */
    fun resetDefaults(c: Context) {
        val prefs = p(c)
        val keep = setOf("tabs_json", "active_tab", "caption_usage_day", "caption_usage_seconds")
        val keysToRemove = prefs.all.keys.filterNot { it in keep }
        prefs.edit().apply {
            keysToRemove.forEach { remove(it) }
        }.apply()
    }
}
