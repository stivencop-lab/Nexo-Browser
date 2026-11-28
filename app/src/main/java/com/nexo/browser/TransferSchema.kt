package com.nexo.browser

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Datos portables de Nexo (nunca estado opaco del motor ni contenedores
 * privados). [origin] y [payloadPath] solo validan copias antiguas (formato 1),
 * que pueden traer datos del motor anterior: se comprueban y se ignoran.
 */
object TransferSchema {
    val secretNames = setOf("deepgram_key", "deepl_key", "ai_translate_key")
    val settingsTypes = mapOf("accent" to "string", "ai_translate_endpoint" to "string", "ai_translate_model" to "string", "ai_translate_provider" to "string", "auto_hide_bars" to "boolean", "autoplay" to "string", "background_media" to "boolean", "caption_daily_limit" to "int", "caption_audio_auto" to "boolean", "caption_language" to "string", "caption_mode" to "string", "caption_size" to "int", "caption_translate" to "string", "caption_usage_day" to "string", "caption_usage_seconds" to "int", "captions_enabled" to "boolean", "clear_on_exit" to "boolean", "cookie_mode" to "string", "desktop_default" to "boolean", "dns_custom" to "string", "dns_provider" to "string", "dns_strict" to "boolean", "fingerprinting" to "boolean", "force_zoom" to "boolean", "free_search" to "boolean", "gpc" to "boolean", "https_only" to "boolean", "javascript" to "boolean", "max_live" to "int", "nav_actions" to "string", "nav_mode" to "string", "nexo_home" to "boolean", "pull_to_refresh" to "boolean", "dock_gestures" to "boolean", "parallel_downloads" to "boolean", "query_stripping" to "boolean", "remote_debugging" to "boolean", "restore_tabs" to "boolean", "safe_browsing" to "boolean", "search" to "string", "smart_pip" to "boolean", "strict_tracking" to "boolean", "tab_view_mode" to "string", "text_scale" to "int", "theme" to "string", "tracking" to "boolean", "translation_provider" to "string", "translator_target" to "string")
    val settingsKeys: Set<String> get() = settingsTypes.keys
    internal const val MAX_NATIVE_BYTES = 8 * 1024 * 1024
    internal const val MAX_PREFERENCE_CHARS = 2 * 1024 * 1024
    internal const val MAX_ITEMS = 500
    internal const val MAX_SECRET_CHARS = 16384

    internal val typesByFile = mapOf(
        "nexo_browser_settings" to settingsTypes,
        "nexo_history" to mapOf("entries" to "string"),
        "nexo_quick_access" to mapOf("custom" to "string", "hidden" to "set")
    )

    fun tabs(raw: String): String {
        val input = JSONArray(raw); require(input.length() <= MAX_ITEMS)
        val result = JSONArray()
        for (i in 0 until input.length()) {
            val t = input.getJSONObject(i)
            if (t.optBoolean("isPrivate") || t.optBoolean("private") || t.optString("burnerId").let { it.isNotEmpty() && it != "null" }) continue
            val url = t.getString("url").take(8192)
            if (!UrlSafety.isWebUrl(url) && url != "about:blank") continue
            result.put(JSONObject().put("id", UUID.randomUUID().toString()).put("url", url)
                .put("title", t.optString("title").take(512)).put("groupName", t.optString("groupName").take(80))
                .put("desktopMode", t.optBoolean("desktopMode")).put("pinned", t.optBoolean("pinned"))
                .put("lastUsed", t.optLong("lastUsed")))
        }
        return result.toString()
    }
    fun validId(id: String): Boolean = id.length in 1..180 && id.matches(Regex("[A-Za-z0-9@._{}+-]+")) && id != "." && id != ".."
    fun uuid(uuid: String): Boolean = uuid.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
    fun origin(name: String, uuids: Set<String>): Boolean = uuids.any {
        name == "moz-extension+++$it" || name == "moz-extension+++$it^userContextId=4294967295"
    }
    fun payloadPath(path: String, uuids: Set<String>, ids: Set<String>): Boolean {
        if (!MigrationArchive.safePath(path)) return false
        if (path in setOf("native.json", "addons.json", "manifest.json")) return true
        val p = path.split('/')
        return when {
            p.size >= 4 && p[0] == "gecko" && p[1] == "default" -> origin(p[2], uuids) && p.drop(3).none { it.startsWith(".") && it != ".metadata-v2" }
            p.size == 4 && p[0] == "gecko" && p[1] == "legacy" -> p[2] in ids && p[3] == "storage.js"
            p.size == 2 && p[0] == "addons" -> p[1].matches(Regex("[a-f0-9]{64}\\.xpi"))
            else -> false
        }
    }
}
