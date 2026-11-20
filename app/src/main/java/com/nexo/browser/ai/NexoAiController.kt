package com.nexo.browser.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Controlador de conversaciones persistentes de Nexo AI independiente de vistas, `Activity` y `Dialog`.
 *
 * Garantías arquitectónicas:
 * - Ninguna petición de larga duración guarda referencias a `Activity`, `Dialog` ni vistas.
 * - Minimizar el panel jamás cancela una consulta activa; la UI se desuscribe y al volver a abrir
 *   restaura exactamente la conversación y el progreso o respuesta generada.
 * - Cada solicitud tiene su propio `requestId` y [NexoAiClient.CancelHandle]; cancelar detiene
 *   únicamente esa solicitud sin afectar otros hilos ni comparaciones del laboratorio.
 * - Persiste automáticamente las conversaciones en [NexoAiStore].
 */
object NexoAiController {

    fun interface Transport {
        fun execute(
            settings: NexoAiConfig.Settings,
            apiKey: String,
            userTask: String,
            untrustedContext: String,
            cancelHandle: NexoAiClient.CancelHandle,
            preferStreaming: Boolean,
            reasoningEffort: String?,
            history: List<Pair<String, String>>,
            onDelta: (String) -> Unit
        ): Result<NexoAiClient.AiResponse>
    }

    private val defaultTransport = Transport { settings, apiKey, userTask, untrustedContext, cancelHandle, preferStreaming, reasoningEffort, history, onDelta ->
        NexoAiClient.executeStreaming(
            settings = settings,
            apiKey = apiKey,
            userTask = userTask,
            untrustedContext = untrustedContext,
            cancelHandle = cancelHandle,
            preferStreaming = preferStreaming,
            reasoningEffort = reasoningEffort,
            history = history,
            onDelta = onDelta
        )
    }

    private val defaultExecutor: Executor by lazy {
        Executors.newCachedThreadPool { r ->
            Thread(r, "NexoAiWorker").apply { isDaemon = true }
        }
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(NexoAiModels.ControllerSnapshot) -> Unit>()
    private val activeHandles = ConcurrentHashMap<String, NexoAiClient.CancelHandle>()

    @Volatile private var store: NexoAiStore? = null
    @Volatile private var initialized = false
    @Volatile private var appContext: Context? = null

    @Volatile internal var transport: Transport = defaultTransport
    @Volatile internal var ioExecutor: Executor = defaultExecutor
    @Volatile internal var uiDispatcher: ((Runnable) -> Unit)? = null

    private var conversations: MutableList<NexoAiModels.AiConversation> = mutableListOf()
    private var activeConversationId: String = ""
    private val activeRequests = LinkedHashMap<String, NexoAiModels.ActiveRequestInfo>()
    private var panelState: NexoAiModels.PanelState = NexoAiModels.PanelState.MINIMIZED
    private var minimizedPillVisible: Boolean = false
    private var unreadReadyConversationId: String? = null
    private var lastErrorSummary: String? = null
    private var controllerGeneration: Long = 0L

    fun initialize(context: Context) {
        val app = context.applicationContext
        if (initialized) {
            if (appContext == null) appContext = app
            return
        }
        synchronized(lock) {
            if (initialized) {
                if (appContext == null) appContext = app
                return
            }
            appContext = app
            val file = File(File(app.filesDir, "nexo_ai"), "conversations.json")
            val s = NexoAiStore(file)
            store = s
            loadFromStoreLocked(s)
            initialized = true
        }
    }

    /** Inicialización aislada para pruebas JVM sobre una carpeta temporal (sin Android). */
    internal fun initializeForTest(
        storageFile: File,
        testTransport: Transport = defaultTransport,
        testExecutor: Executor = Executor { it.run() },
        syncUi: Boolean = true
    ) {
        synchronized(lock) {
            controllerGeneration++
            activeHandles.values.forEach { it.cancel() }
            activeHandles.clear()
            activeRequests.clear()
            listeners.clear()
            appContext = null
            transport = testTransport
            ioExecutor = testExecutor
            uiDispatcher = if (syncUi) { r -> r.run() } else null
            panelState = NexoAiModels.PanelState.MINIMIZED
            minimizedPillVisible = false
            unreadReadyConversationId = null
            lastErrorSummary = null
            val s = NexoAiStore(storageFile)
            store = s
            loadFromStoreLocked(s)
            initialized = true
        }
    }

    /** Restablece únicamente los manejadores de test sin borrar el archivo en disco (simula reinicio de proceso). */
    internal fun simulateProcessRestartForTest(
        storageFile: File,
        testTransport: Transport = defaultTransport,
        testExecutor: Executor = Executor { it.run() }
    ) {
        initializeForTest(storageFile, testTransport, testExecutor, syncUi = true)
    }

    private fun loadFromStoreLocked(s: NexoAiStore) {
        val loaded = s.load()
        conversations = loaded.conversations.toMutableList()
        if (conversations.isEmpty()) {
            val fresh = createEmptyConversationLocked("")
            conversations.add(fresh)
            activeConversationId = fresh.id
        } else {
            activeConversationId = loaded.activeConversationId
                ?.takeIf { id -> conversations.any { it.id == id } }
                ?: conversations.first().id
        }
    }

    private fun createEmptyConversationLocked(hostHint: String): NexoAiModels.AiConversation {
        val now = System.currentTimeMillis()
        return NexoAiModels.AiConversation(
            id = "conv_${UUID.randomUUID().toString().take(10)}",
            title = "Nueva conversación",
            createdAtMs = now,
            updatedAtMs = now,
            messages = emptyList(),
            hostHint = hostHint
        )
    }

    fun snapshot(): NexoAiModels.ControllerSnapshot = synchronized(lock) {
        buildSnapshotLocked()
    }

    private fun buildSnapshotLocked(): NexoAiModels.ControllerSnapshot {
        val indicator = when {
            activeRequests.isNotEmpty() -> NexoAiModels.IndicatorState.GENERATING
            unreadReadyConversationId != null -> NexoAiModels.IndicatorState.READY
            !lastErrorSummary.isNullOrBlank() -> NexoAiModels.IndicatorState.ERROR
            else -> NexoAiModels.IndicatorState.IDLE
        }
        return NexoAiModels.ControllerSnapshot(
            conversations = conversations.toList(),
            activeConversationId = activeConversationId,
            activeRequests = LinkedHashMap(activeRequests),
            indicatorState = indicator,
            panelState = panelState,
            minimizedPillVisible = minimizedPillVisible,
            unreadReadyConversationId = unreadReadyConversationId,
            lastErrorSummary = lastErrorSummary
        )
    }

    fun observe(listener: (NexoAiModels.ControllerSnapshot) -> Unit) {
        listeners.addIfAbsent(listener)
        dispatchToListener(listener, snapshot())
    }

    fun unobserve(listener: (NexoAiModels.ControllerSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners(snap: NexoAiModels.ControllerSnapshot) {
        for (l in listeners) {
            dispatchToListener(l, snap)
        }
    }

    private fun dispatchToListener(
        listener: (NexoAiModels.ControllerSnapshot) -> Unit,
        snap: NexoAiModels.ControllerSnapshot
    ) {
        val custom = uiDispatcher
        if (custom != null) {
            custom { listener(snap) }
            return
        }
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
        if (mainLooper != null) {
            if (Looper.myLooper() == mainLooper) listener(snap)
            else Handler(mainLooper).post { listener(snap) }
        } else {
            listener(snap)
        }
    }

    private fun persistLocked() {
        store?.save(activeConversationId, conversations)
    }

    /** Inicia una conversación nueva sin eliminar las anteriores. */
    fun newConversation(hostHint: String = ""): String {
        val snap = synchronized(lock) {
            // Reutiliza la conversación activa si ya está completamente vacía
            val current = conversations.firstOrNull { it.id == activeConversationId }
            if (current != null && current.messages.isEmpty()) {
                return current.id
            }
            val fresh = createEmptyConversationLocked(hostHint)
            conversations.add(0, fresh)
            activeConversationId = fresh.id
            unreadReadyConversationId = null
            lastErrorSummary = null
            persistLocked()
            buildSnapshotLocked()
        }
        notifyListeners(snap)
        return snap.activeConversationId
    }

    fun selectConversation(conversationId: String) {
        val snap = synchronized(lock) {
            if (conversations.none { it.id == conversationId }) return
            activeConversationId = conversationId
            if (unreadReadyConversationId == conversationId) {
                unreadReadyConversationId = null
            }
            lastErrorSummary = null
            persistLocked()
            buildSnapshotLocked()
        }
        notifyListeners(snap)
    }

    fun deleteConversation(conversationId: String) {
        val snap = synchronized(lock) {
            val idx = conversations.indexOfFirst { it.id == conversationId }
            if (idx < 0) return
            // Cancelar únicamente solicitudes activas de ese hilo
            val toCancel = activeRequests.values.filter { it.conversationId == conversationId }.map { it.requestId }
            toCancel.forEach { reqId ->
                activeHandles.remove(reqId)?.cancel()
                activeRequests.remove(reqId)
            }
            conversations.removeAt(idx)
            if (conversations.isEmpty()) {
                val fresh = createEmptyConversationLocked("")
                conversations.add(fresh)
                activeConversationId = fresh.id
            } else if (activeConversationId == conversationId) {
                activeConversationId = conversations.first().id
            }
            if (unreadReadyConversationId == conversationId) unreadReadyConversationId = null
            persistLocked()
            buildSnapshotLocked()
        }
        notifyListeners(snap)
    }

    /**
     * Cambia el estado visual del panel (Minimizado / Parcial / Expandido) SIN cancelar consultas
     * ni reconstruir conversaciones.
     */
    fun setPanelState(state: NexoAiModels.PanelState) {
        val snap = synchronized(lock) {
            panelState = state
            when (state) {
                NexoAiModels.PanelState.MINIMIZED -> {
                    val hasActivity = activeRequests.isNotEmpty() ||
                        unreadReadyConversationId != null ||
                        !lastErrorSummary.isNullOrBlank() ||
                        (conversations.firstOrNull { it.id == activeConversationId }?.messages?.isNotEmpty() == true)
                    minimizedPillVisible = hasActivity
                }
                NexoAiModels.PanelState.PARTIAL, NexoAiModels.PanelState.EXPANDED -> {
                    minimizedPillVisible = false
                    if (unreadReadyConversationId == activeConversationId) {
                        unreadReadyConversationId = null
                    }
                    lastErrorSummary = null
                }
            }
            buildSnapshotLocked()
        }
        notifyListeners(snap)
    }

    /** Oculta explícitamente la píldora flotante minimizada sin borrar el historial ni cancelar peticiones. */
    fun dismissMinimizedPill() {
        val snap = synchronized(lock) {
            minimizedPillVisible = false
            buildSnapshotLocked()
        }
        notifyListeners(snap)
    }

    /**
     * Envía un prompt dentro de [conversationId] (o el hilo activo).
     *
     * - Crea de inmediato el mensaje de usuario y el placeholder del asistente en estado `SENDING`.
     * - Lanza la petición HTTP/SSE en un hilo en segundo plano sin retener vistas ni `Activity`.
     * - Devuelve el `requestId` asignado para cancelación individual.
     */
    fun sendPrompt(
        settings: NexoAiConfig.Settings,
        apiKey: String,
        userTask: String,
        untrustedContext: String,
        scope: String = "page",
        pageBinding: NexoAiModels.PageBinding? = null,
        reasoningEffort: String? = null,
        preferStreaming: Boolean = true,
        conversationId: String? = null,
        logLabel: String = "consulta"
    ): String {
        val now = System.currentTimeMillis()
        val requestId = "req_${UUID.randomUUID().toString().take(12)}"
        val userMsgId = "msg_u_${UUID.randomUUID().toString().take(10)}"
        val assistantMsgId = "msg_a_${UUID.randomUUID().toString().take(10)}"
        val effectiveModel = settings.effectiveModel()
        val cancelHandle = NexoAiClient.CancelHandle()

        val targetConvId: String
        val historyTurns: List<Pair<String, String>>
        val launchGen: Long
        val snapStart = synchronized(lock) {
            launchGen = controllerGeneration
            if (conversations.isEmpty()) {
                val fresh = createEmptyConversationLocked(pageBinding?.host.orEmpty())
                conversations.add(fresh)
                activeConversationId = fresh.id
            }
            targetConvId = conversationId?.takeIf { id -> conversations.any { it.id == id } } ?: activeConversationId
            val idx = conversations.indexOfFirst { it.id == targetConvId }
            val conv = conversations[idx]

            // Historial de turnos completados previos del mismo hilo
            historyTurns = conv.messages
                .filter { it.status == NexoAiModels.MessageStatus.COMPLETED && it.text.isNotBlank() }
                .map { (if (it.role == NexoAiModels.Role.USER) "user" else "assistant") to it.text }

            val userMsg = NexoAiModels.AiMessage(
                id = userMsgId,
                role = NexoAiModels.Role.USER,
                text = userTask.trim(),
                timestampMs = now,
                status = NexoAiModels.MessageStatus.COMPLETED,
                scope = scope,
                pageBinding = pageBinding
            )
            val assistantMsg = NexoAiModels.AiMessage(
                id = assistantMsgId,
                role = NexoAiModels.Role.ASSISTANT,
                text = "",
                timestampMs = now + 1,
                status = NexoAiModels.MessageStatus.SENDING,
                requestId = requestId,
                model = effectiveModel,
                provider = settings.provider,
                reasoningEffort = reasoningEffort,
                pageBinding = pageBinding,
                scope = scope,
                retryPrompt = userTask.trim()
            )
            val updatedTitle = if (conv.messages.isEmpty()) {
                userTask.trim().replace(Regex("\\s+"), " ").take(44).ifBlank { "Conversación" }
            } else conv.title

            val updatedConv = conv.copy(
                title = updatedTitle,
                updatedAtMs = now,
                messages = conv.messages + userMsg + assistantMsg,
                provider = settings.provider,
                model = effectiveModel,
                hostHint = pageBinding?.host?.takeIf { it.isNotBlank() } ?: conv.hostHint
            )
            conversations[idx] = updatedConv
            // Mover hilo actualizado arriba del listado
            if (idx > 0) {
                conversations.removeAt(idx)
                conversations.add(0, updatedConv)
            }

            activeHandles[requestId] = cancelHandle
            activeRequests[requestId] = NexoAiModels.ActiveRequestInfo(
                requestId = requestId,
                conversationId = targetConvId,
                assistantMessageId = assistantMsgId,
                model = effectiveModel,
                provider = settings.provider,
                startedAtMs = now
            )
            lastErrorSummary = null
            persistLocked()
            buildSnapshotLocked()
        }
        notifyListeners(snapStart)

        val logHost = pageBinding?.host.orEmpty()
        val ctxForLog = appContext

        ioExecutor.execute {
            val streamText = StringBuilder()
            var lastUiEmitMs = 0L

            val result = transport.execute(
                settings = settings,
                apiKey = apiKey,
                userTask = userTask,
                untrustedContext = untrustedContext,
                cancelHandle = cancelHandle,
                preferStreaming = preferStreaming,
                reasoningEffort = reasoningEffort,
                history = historyTurns
            ) { delta ->
                if (cancelHandle.isCancelled) return@execute
                streamText.append(delta)
                val nowEmit = System.currentTimeMillis()
                // Limita refrescos UI a ~15 fps durante streaming rápido para no ahogar el hilo principal
                if (nowEmit - lastUiEmitMs >= 60L || streamText.length <= delta.length) {
                    lastUiEmitMs = nowEmit
                    val partialSnap = synchronized(lock) {
                        if (controllerGeneration != launchGen) return@synchronized null
                        updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                            msg.copy(
                                text = streamText.toString(),
                                status = NexoAiModels.MessageStatus.STREAMING
                            )
                        }
                        buildSnapshotLocked()
                    }
                    if (partialSnap != null) notifyListeners(partialSnap)
                }
            }

            val finalSnap = synchronized(lock) {
                if (controllerGeneration != launchGen) return@synchronized null
                activeHandles.remove(requestId)
                activeRequests.remove(requestId)

                if (cancelHandle.isCancelled) {
                    updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                        msg.copy(
                            text = msg.text.ifBlank { streamText.toString() },
                            status = NexoAiModels.MessageStatus.CANCELLED,
                            errorMessage = "Generación detenida por el usuario."
                        )
                    }
                } else {
                    result.fold(
                        onSuccess = { aiResp ->
                            updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                                msg.copy(
                                    text = aiResp.text,
                                    status = NexoAiModels.MessageStatus.COMPLETED,
                                    usage = aiResp.usage,
                                    metrics = aiResp.metrics,
                                    actions = aiResp.actions,
                                    errorMessage = null
                                )
                            }
                            if (panelState == NexoAiModels.PanelState.MINIMIZED || activeConversationId != targetConvId) {
                                unreadReadyConversationId = targetConvId
                                if (panelState == NexoAiModels.PanelState.MINIMIZED) {
                                    minimizedPillVisible = true
                                }
                            }
                            ctxForLog?.let { NexoAiConfig.appendLog(it, logLabel, logHost, "ok") }
                        },
                        onFailure = { err ->
                            val cleanErr = NexoAiConfig.sanitizeLogText(err.message ?: "No se pudo completar la solicitud.")
                            updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                                msg.copy(
                                    text = msg.text.ifBlank { streamText.toString() },
                                    status = NexoAiModels.MessageStatus.ERROR,
                                    errorMessage = cleanErr
                                )
                            }
                            lastErrorSummary = cleanErr
                            if (panelState == NexoAiModels.PanelState.MINIMIZED) {
                                minimizedPillVisible = true
                            }
                            ctxForLog?.let { NexoAiConfig.appendLog(it, logLabel, logHost, "error") }
                        }
                    )
                }
                persistLocked()
                buildSnapshotLocked()
            }
            if (finalSnap != null) notifyListeners(finalSnap)
        }

        return requestId
    }

    /** Cancela explícitamente UNA solicitud concreta sin tocar otras conversaciones ni el benchmark. */
    fun cancelRequest(requestId: String): Boolean {
        val handle = activeHandles.remove(requestId) ?: return false
        handle.cancel()
        val snap = synchronized(lock) {
            val req = activeRequests.remove(requestId)
            if (req != null) {
                updateAssistantMessageLocked(req.conversationId, req.assistantMessageId) { msg ->
                    msg.copy(
                        status = NexoAiModels.MessageStatus.CANCELLED,
                        errorMessage = "Generación detenida por el usuario."
                    )
                }
                persistLocked()
            }
            buildSnapshotLocked()
        }
        notifyListeners(snap)
        return true
    }

    /** Cancela únicamente la solicitud activa de la conversación actual (si existe). */
    fun cancelActiveConversationRequest(): Boolean {
        val reqId = synchronized(lock) {
            activeRequests.values.firstOrNull { it.conversationId == activeConversationId }?.requestId
        } ?: return false
        return cancelRequest(reqId)
    }

    private fun updateAssistantMessageLocked(
        conversationId: String,
        messageId: String,
        transform: (NexoAiModels.AiMessage) -> NexoAiModels.AiMessage
    ) {
        val idx = conversations.indexOfFirst { it.id == conversationId }
        if (idx < 0) return
        val conv = conversations[idx]
        val mIdx = conv.messages.indexOfFirst { it.id == messageId }
        if (mIdx < 0) return
        val updatedMessages = conv.messages.toMutableList()
        updatedMessages[mIdx] = transform(updatedMessages[mIdx])
        conversations[idx] = conv.copy(
            updatedAtMs = System.currentTimeMillis(),
            messages = updatedMessages
        )
    }
}
