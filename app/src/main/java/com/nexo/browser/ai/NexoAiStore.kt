package com.nexo.browser.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Almacén local persistente de conversaciones de Nexo AI.
 *
 * - Puro sobre [File] para poder probarse íntegramente en la JVM sin dependencias de Android.
 * - Escritura atómica (`tmp` + rename) para evitar corrupción ante cierres inesperados.
 * - Nunca guarda claves API ni cabeceras de autorización.
 * - Al recargar tras un cierre del proceso, cualquier mensaje que hubiera quedado en estado
 *   `SENDING` o `STREAMING` se marca honestamente como `INTERRUPTED` para permitir reintentar.
 */
class NexoAiStore(private val storageFile: File) {

    data class PersistedState(
        val activeConversationId: String?,
        val conversations: List<NexoAiModels.AiConversation>
    )

    @Synchronized
    fun load(): PersistedState {
        if (!storageFile.exists() || !storageFile.isFile) {
            return PersistedState(activeConversationId = null, conversations = emptyList())
        }
        val raw = runCatching { storageFile.readText(Charsets.UTF_8) }.getOrNull()
            ?: return PersistedState(null, emptyList())
        if (raw.isBlank()) return PersistedState(null, emptyList())
        return runCatching {
            val root = JSONObject(raw)
            val activeId = root.optString("activeConversationId").takeIf { it.isNotBlank() }
            val arr = root.optJSONArray("conversations") ?: JSONArray()
            val conversations = ArrayList<NexoAiModels.AiConversation>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseConversation(obj)?.let { conversations += it }
            }
            PersistedState(activeId, conversations)
        }.getOrElse {
            PersistedState(null, emptyList())
        }
    }

    @Synchronized
    fun save(activeConversationId: String?, conversations: List<NexoAiModels.AiConversation>) {
        val parent = storageFile.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()

        val bounded = conversations
            .sortedWith(compareByDescending<NexoAiModels.AiConversation> { it.pinned }.thenByDescending { it.updatedAtMs })
            .take(MAX_CONVERSATIONS)
            .map { conv ->
                if (conv.messages.size > MAX_MESSAGES_PER_CONVERSATION) {
                    conv.copy(messages = conv.messages.takeLast(MAX_MESSAGES_PER_CONVERSATION))
                } else conv
            }

        val root = JSONObject()
            .put("version", STORE_VERSION)
            .put("activeConversationId", activeConversationId.orEmpty())
            .put("conversations", JSONArray().apply {
                bounded.forEach { put(serializeConversation(it)) }
            })

        val tmp = File(storageFile.parentFile ?: File("."), "${storageFile.name}.tmp")
        runCatching {
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(storageFile)) {
                storageFile.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun serializeConversation(conv: NexoAiModels.AiConversation): JSONObject =
        JSONObject()
            .put("id", conv.id)
            .put("title", conv.title)
            .put("createdAtMs", conv.createdAtMs)
            .put("updatedAtMs", conv.updatedAtMs)
            .put("provider", conv.provider)
            .put("model", conv.model)
            .put("hostHint", conv.hostHint)
            .put("pinned", conv.pinned)
            .put("messages", JSONArray().apply {
                conv.messages.forEach { put(serializeMessage(it)) }
            })

    private fun serializeMessage(msg: NexoAiModels.AiMessage): JSONObject =
        JSONObject().apply {
            put("id", msg.id)
            put("role", msg.role.name)
            put("text", msg.text)
            put("timestampMs", msg.timestampMs)
            put("status", msg.status.name)
            msg.requestId?.let { put("requestId", it) }
            msg.model?.let { put("model", it) }
            msg.provider?.let { put("provider", it) }
            msg.reasoningEffort?.let { put("reasoningEffort", it) }
            msg.errorMessage?.let { put("errorMessage", NexoAiConfig.sanitizeLogText(it)) }
            put("scope", msg.scope)
            msg.retryPrompt?.let { put("retryPrompt", it) }
            msg.usage?.let { u ->
                put("usage", JSONObject().apply {
                    u.promptTokens?.let { put("p", it) }
                    u.completionTokens?.let { put("c", it) }
                    u.totalTokens?.let { put("t", it) }
                })
            }
            msg.metrics?.let { m ->
                put("metrics", JSONObject().apply {
                    put("totalMs", m.totalDurationMs)
                    m.networkLatencyMs?.let { put("netMs", it) }
                    m.timeToFirstTokenMs?.let { put("ttftMs", it) }
                    m.generationDurationMs?.let { put("genMs", it) }
                    m.tokensPerSecond?.let { put("tps", it) }
                    put("http", m.httpStatus)
                    put("streamed", m.streamed)
                    put("model", m.model)
                    put("provider", m.provider)
                    m.reasoningEffort?.let { put("reasoning", it) }
                    put("at", m.executedAtMs)
                })
            }
            if (msg.actions.isNotEmpty()) {
                put("actions", JSONArray().apply {
                    msg.actions.forEach { a ->
                        put(JSONObject()
                            .put("type", a.type)
                            .put("label", a.label)
                            .put("target", a.target)
                            .put("value", a.value))
                    }
                })
            }
            if (msg.agentSteps.isNotEmpty()) {
                put("agentSteps", JSONArray().apply {
                    msg.agentSteps.forEach { st ->
                        put(JSONObject()
                            .put("tool", st.tool)
                            .put("summary", st.summary)
                            .put("ok", st.ok))
                    }
                })
            }
            msg.pageBinding?.let { b ->
                put("pageBinding", JSONObject()
                    .put("tabId", b.tabId)
                    .put("url", b.url)
                    .put("host", b.host)
                    .put("title", b.title)
                    .put("docGen", b.documentGeneration))
            }
        }

    private fun parseConversation(obj: JSONObject): NexoAiModels.AiConversation? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val msgsArr = obj.optJSONArray("messages") ?: JSONArray()
        val messages = ArrayList<NexoAiModels.AiMessage>()
        for (i in 0 until msgsArr.length()) {
            val mObj = msgsArr.optJSONObject(i) ?: continue
            parseMessage(mObj)?.let { messages += it }
        }
        return NexoAiModels.AiConversation(
            id = id,
            title = obj.optString("title").ifBlank { "Conversación" },
            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
            messages = messages,
            provider = obj.optString("provider"),
            model = obj.optString("model"),
            hostHint = obj.optString("hostHint"),
            pinned = obj.optBoolean("pinned", false)
        )
    }

    private fun parseMessage(obj: JSONObject): NexoAiModels.AiMessage? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val role = runCatching { NexoAiModels.Role.valueOf(obj.optString("role")) }.getOrNull() ?: return null
        val rawStatus = runCatching { NexoAiModels.MessageStatus.valueOf(obj.optString("status")) }
            .getOrDefault(NexoAiModels.MessageStatus.COMPLETED)

        // Si Android mató el proceso durante una petición activa, ser honestos y marcarla interrumpida.
        val status = if (rawStatus == NexoAiModels.MessageStatus.SENDING || rawStatus == NexoAiModels.MessageStatus.STREAMING) {
            NexoAiModels.MessageStatus.INTERRUPTED
        } else {
            rawStatus
        }
        val errorMessage = if (status == NexoAiModels.MessageStatus.INTERRUPTED && obj.optString("errorMessage").isBlank()) {
            "Consulta interrumpida al cerrarse la aplicación. Puedes reintentarla."
        } else {
            obj.optString("errorMessage").takeIf { it.isNotBlank() }
        }

        val usage = obj.optJSONObject("usage")?.let { u ->
            NexoAiClient.TokenUsage(
                promptTokens = if (u.has("p")) u.optInt("p") else null,
                completionTokens = if (u.has("c")) u.optInt("c") else null,
                totalTokens = if (u.has("t")) u.optInt("t") else null
            )
        }
        val metrics = obj.optJSONObject("metrics")?.let { m ->
            NexoAiModels.AiMetrics(
                totalDurationMs = m.optLong("totalMs", 0L),
                networkLatencyMs = if (m.has("netMs")) m.optLong("netMs") else null,
                timeToFirstTokenMs = if (m.has("ttftMs")) m.optLong("ttftMs") else null,
                generationDurationMs = if (m.has("genMs")) m.optLong("genMs") else null,
                tokensPerSecond = if (m.has("tps")) m.optDouble("tps") else null,
                httpStatus = m.optInt("http", 200),
                streamed = m.optBoolean("streamed", false),
                model = m.optString("model"),
                provider = m.optString("provider"),
                reasoningEffort = m.optString("reasoning").takeIf { it.isNotBlank() },
                executedAtMs = m.optLong("at", 0L)
            )
        }
        val actions = ArrayList<NexoAiClient.ProposedAction>()
        obj.optJSONArray("actions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val type = a.optString("type")
                val target = a.optString("target")
                if (type.isNotBlank() && target.isNotBlank()) {
                    actions += NexoAiClient.ProposedAction(
                        type = type,
                        label = a.optString("label"),
                        target = target,
                        value = a.optString("value")
                    )
                }
            }
        }
        val agentSteps = ArrayList<NexoAiModels.AgentStep>()
        obj.optJSONArray("agentSteps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i) ?: continue
                val tool = st.optString("tool")
                val summary = st.optString("summary")
                if (tool.isNotBlank() && summary.isNotBlank()) {
                    agentSteps += NexoAiModels.AgentStep(tool, summary, st.optBoolean("ok", true))
                }
            }
        }
        val pageBinding = obj.optJSONObject("pageBinding")?.let { b ->
            NexoAiModels.PageBinding(
                tabId = b.optString("tabId"),
                url = b.optString("url"),
                host = b.optString("host"),
                title = b.optString("title"),
                documentGeneration = b.optLong("docGen", 0L)
            )
        }

        return NexoAiModels.AiMessage(
            id = id,
            role = role,
            text = obj.optString("text"),
            timestampMs = obj.optLong("timestampMs", 0L),
            status = status,
            requestId = obj.optString("requestId").takeIf { it.isNotBlank() },
            model = obj.optString("model").takeIf { it.isNotBlank() },
            provider = obj.optString("provider").takeIf { it.isNotBlank() },
            reasoningEffort = obj.optString("reasoningEffort").takeIf { it.isNotBlank() },
            usage = usage,
            metrics = metrics,
            actions = actions,
            errorMessage = errorMessage,
            pageBinding = pageBinding,
            scope = obj.optString("scope", "page"),
            retryPrompt = obj.optString("retryPrompt").takeIf { it.isNotBlank() },
            agentSteps = agentSteps
        )
    }

    companion object {
        private const val STORE_VERSION = 2
        const val MAX_CONVERSATIONS = 60
        const val MAX_MESSAGES_PER_CONVERSATION = 100
    }
}
