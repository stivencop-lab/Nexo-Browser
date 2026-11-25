package com.nexo.browser

import java.net.URI
import java.util.Locale
import kotlin.math.ln

/**
 * Lógica pura (comprobable en JVM) para Nexo Site Control:
 * - Diagnóstico honesto de conexión (HTTPS cifra el tránsito pero nunca se presenta
 *   como garantía absoluta de seguridad o legitimidad del sitio).
 * - Conversión exacta de porcentaje de zoom (75%–200%) a nivel de Chromium/Cefrium.
 * - Normalización de origen y host.
 */
object SiteControlModel {
    val ZOOM_STEPS = listOf(75, 85, 100, 115, 125, 150, 175, 200)
    const val MIN_ZOOM_PERCENT = 75
    const val MAX_ZOOM_PERCENT = 200

    enum class ConnectionKind { HTTPS, HTTP, SSL_BLOCKED, INTERNAL, OTHER }

    data class ConnectionInfo(
        val kind: ConnectionKind,
        val host: String,
        val origin: String?,
        val badge: String,
        val tone: NexoUi.Tone,
        val headline: String,
        val explanation: String
    )

    fun hostOf(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val uri = runCatching { URI(raw) }.getOrNull() ?: return ""
        return uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.").orEmpty()
    }

    fun originOf(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
        val portPart = if (uri.port > 0 && !defaultPort) ":${uri.port}" else ""
        return "$scheme://$host$portPart"
    }

    fun inspectConnection(url: String?, sslBlockedHost: String? = null): ConnectionInfo {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty() || raw.startsWith("nexo://", ignoreCase = true) ||
            raw.startsWith("about:", ignoreCase = true) || raw.startsWith("resource://", ignoreCase = true)
        ) {
            return ConnectionInfo(
                kind = ConnectionKind.INTERNAL,
                host = "Nexo",
                origin = null,
                badge = "Interna",
                tone = NexoUi.Tone.INFO,
                headline = "Página interna de Nexo",
                explanation = "Se muestra localmente en tu dispositivo sin conexión de red externa."
            )
        }
        val uri = runCatching { URI(raw) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = hostOf(raw).ifBlank { raw.take(64) }
        val origin = originOf(raw)

        if (!sslBlockedHost.isNullOrBlank() && sslBlockedHost.equals(host, ignoreCase = true)) {
            return ConnectionInfo(
                kind = ConnectionKind.SSL_BLOCKED,
                host = host,
                origin = origin,
                badge = "Certificado bloqueado",
                tone = NexoUi.Tone.DANGER,
                headline = "Certificado TLS rechazado",
                explanation = "Nexo canceló la conexión porque el certificado presentado por $host no es válido."
            )
        }

        return when (scheme) {
            "https" -> ConnectionInfo(
                kind = ConnectionKind.HTTPS,
                host = host,
                origin = origin,
                badge = "HTTPS",
                tone = NexoUi.Tone.SUCCESS,
                headline = "Conexión cifrada (HTTPS)",
                explanation = "El tráfico entre tu dispositivo y $host viaja cifrado. HTTPS protege el tránsito, pero no garantiza la legitimidad ni que el contenido del sitio sea seguro."
            )
            "http" -> ConnectionInfo(
                kind = ConnectionKind.HTTP,
                host = host,
                origin = origin,
                badge = "Sin cifrar",
                tone = NexoUi.Tone.WARNING,
                headline = "Conexión sin cifrar (HTTP)",
                explanation = "Los datos enviados en esta página pueden ser observados o alterados en la red. Evita introducir contraseñas o información sensible."
            )
            else -> ConnectionInfo(
                kind = ConnectionKind.OTHER,
                host = host,
                origin = origin,
                badge = scheme.ifBlank { "Local" }.uppercase(Locale.ROOT),
                tone = NexoUi.Tone.NEUTRAL,
                headline = "Recurso $scheme",
                explanation = "Esquema no web estándar gestionado por el navegador."
            )
        }
    }

    fun clampZoomPercent(percent: Int): Int = percent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)

    fun nextZoomStep(currentPercent: Int, delta: Int): Int {
        val clamped = clampZoomPercent(currentPercent)
        if (delta == 0) return 100
        return if (delta > 0) {
            ZOOM_STEPS.firstOrNull { it > clamped } ?: MAX_ZOOM_PERCENT
        } else {
            ZOOM_STEPS.lastOrNull { it < clamped } ?: MIN_ZOOM_PERCENT
        }
    }

    /** Convierte porcentaje (75–200) al nivel logarítmico de zoom de Chromium (`1.2^level`). */
    fun zoomPercentToCefriumLevel(percent: Int): Double {
        val clamped = clampZoomPercent(percent)
        return if (clamped == 100) 0.0 else ln(clamped / 100.0) / ln(1.2)
    }

    fun normalizeHost(host: String): String =
        host.trim().lowercase(Locale.ROOT).removePrefix("www.").trimEnd('.')

    /** Empareja una entrada de `CefriumProfile.listSiteData` (host u origen) con el sitio actual. */
    fun siteDataMatches(entrySite: String, targetOrigin: String, targetHost: String): Boolean {
        val raw = entrySite.trim()
        if (raw.isEmpty()) return false
        val normTarget = normalizeHost(targetHost)
        if (normTarget.isEmpty()) return false
        return if ("://" in raw) {
            val entryOrigin = originOf(raw)
            entryOrigin != null && entryOrigin.equals(targetOrigin, ignoreCase = true)
        } else {
            normalizeHost(raw) == normTarget
        }
    }

    /** Formatea bytes de almacenamiento local de un sitio (IndexedDB, Cache Storage, etc.). */
    fun formatStorageBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "${(bytes / 1024L).coerceAtLeast(1L)} KB"
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / mb)
            else -> String.format(Locale.US, "%.2f GB", bytes / gb)
        }
    }

    /** Resume cookies y bytes almacenados para la subhoja de permisos del sitio. */
    fun formatSiteDataSummary(cookieCount: Long?, storageBytes: Long?): String {
        if (cookieCount == null && (storageBytes == null || storageBytes <= 0L)) {
            return "Política de cookies según Ajustes"
        }
        val cookiesLabel = when (cookieCount) {
            null -> null
            1L -> "1 cookie"
            else -> "$cookieCount cookies"
        }
        val storageLabel = storageBytes?.takeIf { it > 0L }?.let { "${formatStorageBytes(it)} almacenados" }
        return when {
            cookiesLabel != null && storageLabel != null -> "$cookiesLabel · $storageLabel"
            cookiesLabel != null -> if (cookieCount == 1L) "1 cookie guardada" else "$cookieCount cookies guardadas"
            storageLabel != null -> storageLabel
            else -> "Política de cookies según Ajustes"
        }
    }

    /** Entrada filtrada del historial nativo atrás/adelante de una pestaña con su salto relativo. */
    data class TabHistoryItem(
        val title: String,
        val url: String,
        val offset: Int
    )

    /**
     * Convierte la lista cronológica de `CefriumBrowser.getNavigationEntries()` (más antiguo primero)
     * en entradas saltables atrás (`offset < 0`) o adelante (`offset > 0`), ordenadas de la más
     * cercana a la más lejana.
     */
    fun buildHistoryItems(
        entries: List<Triple<Boolean, String, String>>,
        backwards: Boolean,
        limit: Int = 20
    ): List<TabHistoryItem> {
        val currentIdx = entries.indexOfFirst { it.first }
        if (currentIdx < 0) return emptyList()
        val indices = if (backwards) {
            (currentIdx - 1 downTo 0).asSequence()
        } else {
            (currentIdx + 1 until entries.size).asSequence()
        }
        return indices
            .take(limit.coerceAtLeast(1))
            .mapNotNull { idx ->
                val (_, url, title) = entries[idx]
                if (url.isBlank()) return@mapNotNull null
                TabHistoryItem(
                    title = title.trim(),
                    url = url.trim(),
                    offset = idx - currentIdx
                )
            }
            .toList()
    }
}
