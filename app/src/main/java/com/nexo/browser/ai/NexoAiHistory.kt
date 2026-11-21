package com.nexo.browser.ai

import java.text.Normalizer
import java.util.Calendar
import java.util.Locale

/** Reglas puras del historial de Nexo AI (búsqueda, agrupación por fecha, borrado). Probadas en la JVM. */
object NexoAiHistory {
    private const val MAX_TITLE = 60

    fun cleanTitle(raw: String): String? =
        raw.replace(Regex("[\\p{Cntrl}\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(MAX_TITLE).takeIf { it.isNotBlank() }

    /** Ids a borrar: el mensaje y, si es una pregunta, las respuestas que la siguen hasta la próxima pregunta. */
    fun messagesToDelete(messages: List<NexoAiModels.AiMessage>, messageId: String): Set<String> {
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return emptySet()
        val out = linkedSetOf(messages[idx].id)
        if (messages[idx].role == NexoAiModels.Role.USER) {
            for (i in idx + 1 until messages.size) {
                if (messages[i].role == NexoAiModels.Role.USER) break
                out += messages[i].id
            }
        }
        return out
    }

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Coincide en título, sitio o texto de cualquier mensaje; sin distinguir mayúsculas ni tildes. */
    fun matches(conv: NexoAiModels.AiConversation, query: String): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        if (fold(conv.title).contains(q) || fold(conv.hostHint).contains(q)) return true
        return conv.messages.any { fold(it.text).contains(q) }
    }

    enum class Group(val label: String) { TODAY("Hoy"), YESTERDAY("Ayer"), WEEK("Últimos 7 días"), OLDER("Anteriores") }

    fun group(updatedAtMs: Long, nowMs: Long, calendar: Calendar = Calendar.getInstance()): Group {
        calendar.timeInMillis = nowMs
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val today = calendar.timeInMillis
        val day = 24 * 3600 * 1000L
        return when {
            updatedAtMs >= today -> Group.TODAY
            updatedAtMs >= today - day -> Group.YESTERDAY
            updatedAtMs >= today - 6 * day -> Group.WEEK
            else -> Group.OLDER
        }
    }

    /** Conversaciones con contenido, más recientes primero, filtradas y agrupadas. */
    fun sections(
        conversations: List<NexoAiModels.AiConversation>,
        query: String,
        nowMs: Long
    ): List<Pair<Group, List<NexoAiModels.AiConversation>>> =
        conversations
            .filter { it.messages.isNotEmpty() && matches(it, query) }
            .sortedByDescending { it.updatedAtMs }
            .groupBy { group(it.updatedAtMs, nowMs) }
            .toList()
            .sortedBy { it.first.ordinal }
}
