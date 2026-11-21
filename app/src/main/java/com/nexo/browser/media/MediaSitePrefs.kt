package com.nexo.browser.media

import android.content.Context
import org.json.JSONObject

/**
 * Preferencias multimedia globales y por dominio para Nexo Media Engine.
 * Guardadas en `nexo_media_engine` para no alterar las claves portables de `BrowserPrefs`.
 */
object MediaSitePrefs {
    private const val PREFS = "nexo_media_engine"
    private const val KEY_SITES = "site_prefs"
    private const val KEY_DEFAULT_SEEK = "default_seek_sec"
    private const val KEY_GESTURES = "fullscreen_gestures"
    private const val KEY_CAPTION_FONT = "caption_font"
    private const val KEY_DEFAULT_OFFSET_MS = "default_caption_offset_ms"
    private const val KEY_AUTO_ROTATE = "fullscreen_auto_rotate"
    private const val MAX_SITES = 300

    const val BG_INHERIT = "inherit"
    const val BG_ALLOW = "allow"
    const val BG_BLOCK = "block"

    const val FONT_SANS = "sans"
    const val FONT_SERIF = "serif"
    const val FONT_MONO = "mono"

    val SEEK_STEPS = listOf(5, 10, 15, 30)
    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    data class SiteConfig(
        val speed: Float = 1.0f,
        val seekStepSec: Int = 10,
        val captionOffsetMs: Int = 0,
        val captionMode: String = "",
        val captionTarget: String = "",
        val backgroundMode: String = BG_INHERIT
    ) {
        fun isDefault(): Boolean =
            speed == 1.0f && seekStepSec == 10 && captionOffsetMs == 0 &&
                captionMode.isEmpty() && captionTarget.isEmpty() && backgroundMode == BG_INHERIT

        fun toJson(): JSONObject = JSONObject()
            .put("speed", speed.toDouble())
            .put("seek", seekStepSec)
            .put("offset", captionOffsetMs)
            .put("cmode", captionMode)
            .put("ctarget", captionTarget)
            .put("bg", backgroundMode)

        companion object {
            fun fromJson(json: JSONObject?): SiteConfig {
                if (json == null) return SiteConfig()
                return SiteConfig(
                    speed = clampSpeed(json.optDouble("speed", 1.0).toFloat()),
                    seekStepSec = clampSeekStep(json.optInt("seek", 10)),
                    captionOffsetMs = clampOffsetMs(json.optInt("offset", 0)),
                    captionMode = json.optString("cmode", "").takeIf { it in setOf("", "original", "translated", "both") } ?: "",
                    captionTarget = json.optString("ctarget", "").take(12),
                    backgroundMode = json.optString("bg", BG_INHERIT).takeIf { it in setOf(BG_INHERIT, BG_ALLOW, BG_BLOCK) } ?: BG_INHERIT
                )
            }
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun normalizeHost(hostOrUrl: String?): String {
        if (hostOrUrl.isNullOrBlank()) return ""
        val raw = hostOrUrl.trim()
        val parsed = if ("://" in raw) runCatching { java.net.URI(raw).host }.getOrNull().orEmpty() else raw.substringBefore('/').substringBefore(':')
        return parsed.lowercase().removePrefix("www.").take(253)
    }

    fun clampSpeed(speed: Float): Float =
        if (speed.isFinite()) speed.coerceIn(0.25f, 3.0f) else 1.0f

    fun clampSeekStep(seconds: Int): Int = seconds.coerceIn(3, 60)

    fun clampOffsetMs(offsetMs: Int): Int = offsetMs.coerceIn(-5000, 5000)

    fun defaultSeekStep(context: Context): Int =
        clampSeekStep(prefs(context).getInt(KEY_DEFAULT_SEEK, 10))

    fun setDefaultSeekStep(context: Context, seconds: Int) =
        prefs(context).edit().putInt(KEY_DEFAULT_SEEK, clampSeekStep(seconds)).apply()

    fun fullscreenGestures(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GESTURES, true)

    fun setFullscreenGestures(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_GESTURES, enabled).apply()

    /** Pantalla completa: girar según la forma del video (horizontal o vertical). */
    fun autoRotate(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_ROTATE, true)

    fun setAutoRotate(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_ROTATE, enabled).apply()

    fun captionFont(context: Context): String =
        prefs(context).getString(KEY_CAPTION_FONT, FONT_SANS)?.takeIf { it in setOf(FONT_SANS, FONT_SERIF, FONT_MONO) } ?: FONT_SANS

    fun setCaptionFont(context: Context, font: String) {
        val clean = font.takeIf { it in setOf(FONT_SANS, FONT_SERIF, FONT_MONO) } ?: FONT_SANS
        prefs(context).edit().putString(KEY_CAPTION_FONT, clean).apply()
    }

    fun defaultCaptionOffsetMs(context: Context): Int =
        clampOffsetMs(prefs(context).getInt(KEY_DEFAULT_OFFSET_MS, 0))

    fun setDefaultCaptionOffsetMs(context: Context, offsetMs: Int) =
        prefs(context).edit().putInt(KEY_DEFAULT_OFFSET_MS, clampOffsetMs(offsetMs)).apply()

    fun forSite(context: Context, hostOrUrl: String?): SiteConfig {
        val host = normalizeHost(hostOrUrl)
        if (host.isEmpty()) return SiteConfig(seekStepSec = defaultSeekStep(context), captionOffsetMs = defaultCaptionOffsetMs(context))
        val root = runCatching { JSONObject(prefs(context).getString(KEY_SITES, "{}") ?: "{}") }.getOrNull()
        val entry = root?.optJSONObject(host)
        return if (entry != null) SiteConfig.fromJson(entry)
        else SiteConfig(seekStepSec = defaultSeekStep(context), captionOffsetMs = defaultCaptionOffsetMs(context))
    }

    fun saveSite(context: Context, hostOrUrl: String, config: SiteConfig) {
        val host = normalizeHost(hostOrUrl)
        if (host.isEmpty()) return
        val root = runCatching { JSONObject(prefs(context).getString(KEY_SITES, "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (config.isDefault()) {
            root.remove(host)
        } else {
            root.put(host, config.toJson())
        }
        val keys = root.keys().asSequence().toList()
        if (keys.size > MAX_SITES) {
            keys.take(keys.size - MAX_SITES).forEach { root.remove(it) }
        }
        prefs(context).edit().putString(KEY_SITES, root.toString()).apply()
    }

    fun clearSite(context: Context, hostOrUrl: String) {
        val host = normalizeHost(hostOrUrl)
        if (host.isEmpty()) return
        val root = runCatching { JSONObject(prefs(context).getString(KEY_SITES, "{}") ?: "{}") }.getOrDefault(JSONObject())
        root.remove(host)
        prefs(context).edit().putString(KEY_SITES, root.toString()).apply()
    }
}
