package com.nexo.browser

import org.json.JSONObject

/**
 * Lo que la página cuenta de su reproducción (media-reporter.js): metadatos de
 * `navigator.mediaSession`, posición del elemento audible y qué acciones admite.
 * Puro (sin Android) para probarlo en la JVM.
 */
data class MediaDetails(
    val artist: String = "",
    val album: String = "",
    /** Carátula `https://` ya resuelta; vacía si la página no da ninguna utilizable. */
    val artworkUrl: String = "",
    val positionSec: Double = 0.0,
    /** 0 = desconocida o en directo. */
    val durationSec: Double = 0.0,
    val rate: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val isVideo: Boolean = false,
    /** `SystemClock.elapsedRealtime()` del informe, para extrapolar la posición. */
    val reportedAtMs: Long = 0L
) {
    val isLive: Boolean get() = durationSec <= 0.0

    /** Posición estimada ahora mismo (la página informa solo cuando algo cambia). */
    fun positionAt(nowMs: Long, playing: Boolean): Double {
        if (!playing || reportedAtMs <= 0L) return positionSec
        val elapsed = (nowMs - reportedAtMs).coerceAtLeast(0L) / 1000.0
        val estimate = positionSec + elapsed * rate
        return if (durationSec > 0.0) estimate.coerceAtMost(durationSec) else estimate
    }

    /** Sin carátula ni artista: privado. */
    fun forPrivate(): MediaDetails = copy(artist = "", album = "", artworkUrl = "")

    companion object {
        private const val MAX_TEXT = 200
        private const val MAX_URL = 2048
        private const val MAX_DURATION_SEC = 7 * 24 * 3600.0

        fun fromJson(json: JSONObject, nowMs: Long): MediaDetails {
            val duration = json.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0.0 && it < MAX_DURATION_SEC } ?: 0.0
            val position = json.optDouble("position", 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val rate = json.optDouble("rate", 1.0).toFloat().takeIf { it.isFinite() && it > 0f }?.coerceIn(0.25f, 4f) ?: 1f
            return MediaDetails(
                artist = clean(json.optString("artist")),
                album = clean(json.optString("album")),
                artworkUrl = artwork(json.optString("artwork")),
                positionSec = if (duration > 0.0) position.coerceAtMost(duration) else position,
                durationSec = duration,
                rate = rate,
                hasNext = json.optBoolean("next"),
                hasPrevious = json.optBoolean("prev"),
                canSeek = json.optBoolean("seek") && duration > 0.0,
                isVideo = json.optBoolean("video"),
                reportedAtMs = nowMs
            )
        }

        private fun clean(text: String): String =
            text.replace(Regex("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), " ")
                .replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)

        /** Solo carátulas https sin credenciales: nunca `data:`, `blob:`, `file:` ni direcciones con usuario. */
        internal fun artwork(raw: String): String {
            val url = raw.trim()
            if (url.isEmpty() || url.length > MAX_URL) return ""
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return ""
            if (!uri.scheme.equals("https", ignoreCase = true)) return ""
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return ""
            return url
        }
    }
}

/**
 * Botones de la notificación multimedia según lo que admite la página.
 * Orden visual: anterior/−10 s, reproducir/pausar, siguiente/+10 s, cerrar.
 */
object MediaNotificationLayout {
    enum class Button { PREVIOUS, REWIND, PLAY, PAUSE, FORWARD, NEXT, CLOSE }

    data class Layout(val buttons: List<Button>, val compact: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is Layout && buttons == other.buttons && compact.contentEquals(other.compact)

        override fun hashCode(): Int = buttons.hashCode() * 31 + compact.contentHashCode()
    }

    fun layout(details: MediaDetails?, playing: Boolean): Layout {
        val d = details ?: MediaDetails()
        val buttons = ArrayList<Button>(4)
        when {
            d.hasPrevious -> buttons += Button.PREVIOUS
            d.canSeek -> buttons += Button.REWIND
        }
        buttons += if (playing) Button.PAUSE else Button.PLAY
        when {
            d.hasNext -> buttons += Button.NEXT
            d.canSeek -> buttons += Button.FORWARD
        }
        buttons += Button.CLOSE
        // Vista compacta: hasta tres, siempre con reproducir/pausar en el centro.
        val compact = buttons.indices.filter { buttons[it] != Button.CLOSE }.take(3).toIntArray()
        return Layout(buttons, compact)
    }

    /** "1:05" o "1:02:03". */
    fun formatTime(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0) seconds.toLong() else 0L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}
