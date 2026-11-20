package com.nexo.browser.ai

import java.util.Locale

/**
 * Modelos de dominio inmutables para Nexo AI 5.1:
 * conversaciones persistentes, vinculación segura de página, métricas reales
 * medidas con reloj monotónico y estados de panel / indicador.
 */
object NexoAiModels {

    /** Vínculo inmutable con la pestaña, URL y generación de documento al iniciar una consulta. */
    data class PageBinding(
        val tabId: String,
        val url: String,
        val host: String,
        val title: String,
        val documentGeneration: Long
    )

    /** Métricas reales medidas con reloj monotónico (`elapsedRealtimeNanos`). Nunca inventa valores. */
    data class AiMetrics(
        val totalDurationMs: Long,
        val networkLatencyMs: Long? = null,
        val timeToFirstTokenMs: Long? = null,
        val generationDurationMs: Long? = null,
        val tokensPerSecond: Double? = null,
        val httpStatus: Int = 200,
        val streamed: Boolean = false,
        val model: String = "",
        val provider: String = "",
        val reasoningEffort: String? = null,
        val executedAtMs: Long = System.currentTimeMillis()
    ) {
        fun formatCompact(usage: NexoAiClient.TokenUsage?): String = buildList {
            add(formatDuration(totalDurationMs))
            if (streamed && timeToFirstTokenMs != null) {
                add("1.er token ${formatDuration(timeToFirstTokenMs)}")
            }
            usage?.completionTokens?.let { add("$it tok") }
            tokensPerSecond?.let { add(String.format(Locale.US, "%.1f tok/s", it)) }
            if (!reasoningEffort.isNullOrBlank()) add("razonamiento: $reasoningEffort")
        }.joinToString(" · ")

        companion object {
            fun formatDuration(ms: Long): String = when {
                ms < 1000L -> "${ms} ms"
                ms < 10_000L -> String.format(Locale.US, "%.2f s", ms / 1000.0)
                else -> String.format(Locale.US, "%.1f s", ms / 1000.0)
            }

            /**
             * Calcula tokens/s únicamente cuando existen tokens de salida reales (>= 5)
             * y una duración real medible (>= 100 ms). Devuelve null si faltan datos fiables.
             */
            fun computeTokensPerSecond(
                completionTokens: Int?,
                generationDurationMs: Long?,
                totalDurationMs: Long
            ): Double? {
                if (completionTokens == null || completionTokens < 5) return null
                val denomMs = generationDurationMs?.takeIf { it >= 100L } ?: totalDurationMs
                if (denomMs < 100L) return null
                return completionTokens.toDouble() / (denomMs.toDouble() / 1000.0)
            }
        }
    }

    enum class Role { USER, ASSISTANT }

    enum class MessageStatus {
        SENDING,
        STREAMING,
        COMPLETED,
        CANCELLED,
        INTERRUPTED,
        ERROR
    }

    data class AiMessage(
        val id: String,
        val role: Role,
        val text: String,
        val timestampMs: Long,
        val status: MessageStatus = MessageStatus.COMPLETED,
        val requestId: String? = null,
        val model: String? = null,
        val provider: String? = null,
        val reasoningEffort: String? = null,
        val usage: NexoAiClient.TokenUsage? = null,
        val metrics: AiMetrics? = null,
        val actions: List<NexoAiClient.ProposedAction> = emptyList(),
        val errorMessage: String? = null,
        val pageBinding: PageBinding? = null,
        val scope: String = "page",
        val retryPrompt: String? = null
    ) {
        val isInFlight: Boolean
            get() = status == MessageStatus.SENDING || status == MessageStatus.STREAMING
    }

    data class AiConversation(
        val id: String,
        val title: String,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val messages: List<AiMessage> = emptyList(),
        val provider: String = "",
        val model: String = "",
        val hostHint: String = ""
    ) {
        val hasInFlight: Boolean
            get() = messages.any { it.isInFlight }
    }

    /** Experiencia de 3 estados del asistente: Minimizado → Panel parcial → Pantalla expandida. */
    enum class PanelState {
        MINIMIZED,
        PARTIAL,
        EXPANDED
    }

    /** Estado del emblema Aperture y del indicador flotante minimizado. */
    enum class IndicatorState {
        IDLE,
        GENERATING,
        READY,
        ERROR
    }

    /** Información de una petición activa independiente. */
    data class ActiveRequestInfo(
        val requestId: String,
        val conversationId: String,
        val assistantMessageId: String,
        val model: String,
        val provider: String,
        val startedAtMs: Long
    )

    /** Instantánea inmutable observada por la UI (panel, emblema Aperture y píldora minimizada). */
    data class ControllerSnapshot(
        val conversations: List<AiConversation>,
        val activeConversationId: String,
        val activeRequests: Map<String, ActiveRequestInfo>,
        val indicatorState: IndicatorState,
        val panelState: PanelState,
        val minimizedPillVisible: Boolean,
        val unreadReadyConversationId: String?,
        val lastErrorSummary: String?
    ) {
        val activeConversation: AiConversation?
            get() = conversations.firstOrNull { it.id == activeConversationId } ?: conversations.firstOrNull()

        val activeRequestForCurrentConversation: ActiveRequestInfo?
            get() = activeRequests.values.firstOrNull { it.conversationId == activeConversationId }

        val anyGenerating: Boolean
            get() = activeRequests.isNotEmpty()
    }
}
