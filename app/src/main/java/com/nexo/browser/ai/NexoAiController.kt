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
 * Controlador de conversaciones persistentes y Agente de Navegador de Nexo AI independiente de vistas,
 * `Activity` y `Dialog`.
 *
 * Garantías arquitectónicas:
 * - Ninguna petición de larga duración guarda referencias a `Activity`, `Dialog` ni vistas.
 * - Minimizar el panel jamás cancela una consulta ni un bucle agente activo; la UI se desuscribe
 *   y al volver a abrir restaura exactamente la conversación, los pasos ejecutados y la respuesta.
 * - Cada solicitud tiene su propio `requestId` y [NexoAiClient.CancelHandle]; cancelar detiene
 *   únicamente esa solicitud sin afectar otros hilos ni comparaciones del laboratorio.
 * - Persiste automáticamente las conversaciones (con fijado y trazas del agente) en [NexoAiStore].
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
            agentMode = false,
            onDelta = onDelta
        )
    }

    private val defaultAgentTransport = Transport { settings, apiKey, userTask, untrustedContext, cancelHandle, preferStreaming, reasoningEffort, history, onDelta ->
        NexoAiClient.executeStreaming(
            settings = settings,
            apiKey = apiKey,
            userTask = userTask,
            untrustedContext = untrustedContext,
            cancelHandle = cancelHandle,
            preferStreaming = preferStreaming,
            reasoningEffort = reasoningEffort,
            history = history,
            agentMode = true,
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
    @Volatile internal var agentTransport: Transport = defaultAgentTransport
    @Volatile internal var ioExecutor: Executor = defaultExecutor
    @Volatile internal var uiDispatcher: ((Runnable) -> Unit)? = null

    private var conversations: MutableList<NexoAiModels.AiConversation> = mutableListOf()
    private var activeConversationId: String = ""
    private val activeRequests = LinkedHashMap<String, NexoAiModels.ActiveRequestInfo>()
    private val activeTasks = LinkedHashMap<String, NexoAiModels.AgentTask>()
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
        syncUi: Boolean = true,
        testAgentTransport: Transport = testTransport
    ) {
        synchronized(lock) {
            controllerGeneration++
            activeHandles.values.forEach { it.cancel() }
            activeHandles.clear()
            activeRequests.clear()
            activeTasks.clear()
            listeners.clear()
            appContext = null
            transport = testTransport
            agentTransport = testAgentTransport
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
            lastErrorSummary = lastErrorSummary,
            activeTasks = activeTasks.values.sortedByDescending { it.updatedAtMs }
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

    fun togglePinConversation(conversationId: String) {
        val snap = synchronized(lock) {
            val idx = conversations.indexOfFirst { it.id == conversationId }
            if (idx < 0) return
            conversations[idx] = conversations[idx].copy(pinned = !conversations[idx].pinned)
            persistLocked()
            buildSnapshotLocked()
        }
        notifyListeners(snap)
    }

    fun deleteConversation(conversationId: String) {
        val snap = synchronized(lock) {
            val idx = conversations.indexOfFirst { it.id == conversationId }
            if (idx < 0) return
            val toCancel = activeRequests.values.filter { it.conversationId == conversationId }.map { it.requestId }
            toCancel.forEach { reqId ->
                activeHandles.remove(reqId)?.cancel()
                activeRequests.remove(reqId)
            }
            conversations.removeAt(idx)
            activeTasks.remove(conversationId)
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

    /** Borra todo el historial (cancela lo que esté en curso) y deja una conversación vacía. */
    fun deleteAllConversations() {
        val snap = synchronized(lock) {
            activeHandles.values.forEach { it.cancel() }
            activeHandles.clear()
            activeRequests.clear()
            activeTasks.clear()
            conversations.clear()
            val fresh = createEmptyConversationLocked("")
            conversations.add(fresh)
            activeConversationId = fresh.id
            unreadReadyConversationId = null
            lastErrorSummary = null
            minimizedPillVisible = false
            persistLocked()
            buildSnapshotLocked()
        }
        notifyListeners(snap)
    }

    /**
     * Elimina un mensaje. Una pregunta se lleva consigo su respuesta (sin ella la respuesta
     * pierde el contexto); si esa respuesta seguía generándose, se cancela.
     */
    fun deleteMessage(conversationId: String, messageId: String) {
        val snap = synchronized(lock) {
            val idx = conversations.indexOfFirst { it.id == conversationId }
            if (idx < 0) return
            val conv = conversations[idx]
            val removed = NexoAiHistory.messagesToDelete(conv.messages, messageId)
            if (removed.isEmpty()) return
            conv.messages.filter { it.id in removed }.mapNotNull { it.requestId }.forEach { reqId ->
                activeHandles.remove(reqId)?.cancel()
                activeRequests.remove(reqId)
            }
            conversations[idx] = conv.copy(messages = conv.messages.filterNot { it.id in removed })
            persistLocked()
            buildSnapshotLocked()
        }
        notifyListeners(snap)
    }

    fun renameConversation(conversationId: String, title: String) {
        val clean = NexoAiHistory.cleanTitle(title) ?: return
        val snap = synchronized(lock) {
            val idx = conversations.indexOfFirst { it.id == conversationId }
            if (idx < 0) return
            conversations[idx] = conversations[idx].copy(title = clean)
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

    /** Envía un prompt estándar (un turno) dentro de [conversationId] o el hilo activo. */
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

        val (targetConvId, historyTurns, launchGen) = enqueueConversationTurn(
            requestId = requestId,
            userMsgId = userMsgId,
            assistantMsgId = assistantMsgId,
            now = now,
            settings = settings,
            userTask = userTask,
            scope = scope,
            pageBinding = pageBinding,
            reasoningEffort = reasoningEffort,
            conversationId = conversationId,
            cancelHandle = cancelHandle
        )

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

            completeTurn(
                launchGen = launchGen,
                requestId = requestId,
                targetConvId = targetConvId,
                assistantMsgId = assistantMsgId,
                cancelHandle = cancelHandle,
                streamFallback = streamText.toString(),
                result = result,
                agentSteps = emptyList(),
                extraActions = emptyList(),
                ctxForLog = ctxForLog,
                logLabel = logLabel,
                logHost = logHost
            )
        }

        return requestId
    }

    /**
     * Ejecuta el **Modo Agente de Navegador** en bucle multi-paso acotado (`Observar → Herramienta → Actuar → Observar`,
     * hasta [maxSteps] pasos; 8 en modo estándar o 20 en tareas a largo plazo con hitos reanudables),
     * publicando en tiempo real cada paso ejecutado, protegiendo contra bucles y haciendo streaming de la respuesta final.
     */
    fun sendAgentPrompt(
        settings: NexoAiConfig.Settings,
        apiKey: String,
        userTask: String,
        initialContext: String,
        toolExecutor: NexoAiAgent.ToolExecutor,
        pageBinding: NexoAiModels.PageBinding? = null,
        reasoningEffort: String? = null,
        preferStreaming: Boolean = true,
        conversationId: String? = null,
        logLabel: String = "agente",
        maxSteps: Int = NexoAiAgent.MAX_AGENT_STEPS,
        longTask: Boolean = false
    ): String {
        val effectiveMaxSteps = maxSteps.coerceIn(1, NexoAiAgent.MAX_LONG_TASK_STEPS)
        val now = System.currentTimeMillis()
        val requestId = "req_${UUID.randomUUID().toString().take(12)}"
        val userMsgId = "msg_u_${UUID.randomUUID().toString().take(10)}"
        val assistantMsgId = "msg_a_${UUID.randomUUID().toString().take(10)}"
        val cancelHandle = NexoAiClient.CancelHandle()

        val (targetConvId, baseHistory, launchGen) = enqueueConversationTurn(
            requestId = requestId,
            userMsgId = userMsgId,
            assistantMsgId = assistantMsgId,
            now = now,
            settings = settings,
            userTask = userTask,
            scope = if (longTask) "long_agent" else "agent",
            pageBinding = pageBinding,
            reasoningEffort = reasoningEffort,
            conversationId = conversationId,
            cancelHandle = cancelHandle
        )

        if (longTask) {
            val taskSnap = synchronized(lock) {
                activeTasks[targetConvId] = NexoAiModels.AgentTask(
                    conversationId = targetConvId,
                    goal = userTask.trim().take(160),
                    progressPercent = 5,
                    lastCheckpoint = "Iniciando tarea de largo plazo…",
                    nextStep = "Planificar y recopilar información inicial",
                    stepsExecuted = 0,
                    completed = false,
                    updatedAtMs = now
                )
                activeRequests[requestId]?.let { req ->
                    activeRequests[requestId] = req.copy(
                        isAgent = true,
                        longTask = true,
                        maxSteps = effectiveMaxSteps,
                        progressPercent = 5
                    )
                }
                buildSnapshotLocked()
            }
            notifyListeners(taskSnap)
        }

        val logHost = pageBinding?.host.orEmpty()
        val ctxForLog = appContext

        ioExecutor.execute {
            val steps = mutableListOf<NexoAiModels.AgentStep>()
            val pendingActions = mutableListOf<NexoAiClient.ProposedAction>()
            val loopHistory = baseHistory.toMutableList()
            var currentTask = if (longTask) {
                "TAREA A LARGO PLAZO (hasta $effectiveMaxSteps pasos; usa `task_checkpoint` cada pocos pasos para guardar hitos y progreso):\n$userTask"
            } else userTask
            var currentContext = initialContext
            var finalResult: Result<NexoAiClient.AiResponse>? = null
            var lastStream = ""
            var lastToolSignature = ""
            var repeatedToolCount = 0
            var reachedStepCap = false
            var lastCheckpointText = ""
            var lastNextStep = ""
            var currentProgress: Int? = if (longTask) 5 else null

            for (stepIdx in 1..effectiveMaxSteps) {
                if (cancelHandle.isCancelled) break
                val stepStream = StringBuilder()
                var lastEmit = 0L

                val planSnap = synchronized(lock) {
                    if (controllerGeneration != launchGen) return@synchronized null
                    activeRequests[requestId]?.let { req ->
                        activeRequests[requestId] = req.copy(
                            isAgent = true,
                            longTask = longTask,
                            currentStepIndex = stepIdx,
                            maxSteps = effectiveMaxSteps,
                            currentStepLabel = if (stepIdx == 1) "Planificando acciones…" else "Analizando paso $stepIdx…",
                            progressPercent = currentProgress
                        )
                    }
                    buildSnapshotLocked()
                }
                if (planSnap != null) notifyListeners(planSnap)

                val turnResult = agentTransport.execute(
                    settings = settings,
                    apiKey = apiKey,
                    userTask = currentTask,
                    untrustedContext = currentContext,
                    cancelHandle = cancelHandle,
                    preferStreaming = preferStreaming,
                    reasoningEffort = reasoningEffort,
                    history = loopHistory
                ) { delta ->
                    if (cancelHandle.isCancelled) return@execute
                    stepStream.append(delta)
                    val nowEmit = System.currentTimeMillis()
                    if (nowEmit - lastEmit >= 70L || stepStream.length <= delta.length) {
                        lastEmit = nowEmit
                        val snap = synchronized(lock) {
                            if (controllerGeneration != launchGen) return@synchronized null
                            updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                                msg.copy(
                                    text = stepStream.toString(),
                                    status = NexoAiModels.MessageStatus.STREAMING,
                                    agentSteps = steps.toList()
                                )
                            }
                            buildSnapshotLocked()
                        }
                        if (snap != null) notifyListeners(snap)
                    }
                }

                lastStream = stepStream.toString()
                if (cancelHandle.isCancelled || turnResult.isFailure) {
                    finalResult = turnResult
                    break
                }

                val aiResp = turnResult.getOrThrow()
                val toolCall = NexoAiAgent.parseToolCall(aiResp.text, logHost)
                if (toolCall == null) {
                    val cleanedFinal = NexoAiRichContent.stripInternalBlocks(aiResp.text)
                        .ifBlank { aiResp.text }
                    finalResult = Result.success(aiResp.copy(text = cleanedFinal))
                    break
                }
                if (stepIdx == effectiveMaxSteps) {
                    reachedStepCap = true
                    val cleanedPartial = NexoAiRichContent.stripInternalBlocks(aiResp.text).trim()
                    val checkpointNote = buildString {
                        if (cleanedPartial.isNotBlank()) {
                            appendLine(cleanedPartial)
                            appendLine()
                        }
                        append("### Hito de tarea alcanzado ($effectiveMaxSteps pasos)\n")
                        if (lastCheckpointText.isNotBlank()) appendLine("- **Último avance:** $lastCheckpointText")
                        if (lastNextStep.isNotBlank()) appendLine("- **Siguiente paso sugerido:** $lastNextStep")
                        append("Pulsa **Continuar tarea** cuando quieras que siga desde este punto.")
                    }
                    finalResult = Result.success(aiResp.copy(text = checkpointNote))
                    break
                }

                // Protección anti-bucle: impide que una página maliciosa repita la misma herramienta sin fin.
                val signature = "${toolCall.tool}|${toolCall.args.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}"
                if (signature == lastToolSignature) {
                    repeatedToolCount++
                } else {
                    lastToolSignature = signature
                    repeatedToolCount = 1
                }
                if (repeatedToolCount > 2) {
                    val loopOutcome = NexoAiAgent.ToolOutcome(
                        false,
                        "Bucle repetido detenido (${toolCall.tool})",
                        "Esta misma llamada se repitió sin cambios. Cambia de estrategia o entrega ya tu respuesta final al usuario."
                    )
                    steps += NexoAiModels.AgentStep(toolCall.tool, loopOutcome.summary, ok = false)
                    if (repeatedToolCount >= 4) {
                        finalResult = Result.success(
                            aiResp.copy(
                                text = "El agente detuvo una repetición automática de `${toolCall.tool}` por seguridad. Puedes indicar el siguiente paso si deseas continuar."
                            )
                        )
                        break
                    }
                    currentTask = NexoAiAgent.wrapToolObservation(toolCall, loopOutcome, stepIdx, effectiveMaxSteps)
                    currentContext = ""
                    continue
                }

                // Mostrar paso en curso en la UI (sheet + HUD flotante en vivo) antes de ejecutarlo
                val runningStep = NexoAiModels.AgentStep(toolCall.tool, "${toolCall.reason}…", ok = true)
                val stepRunningSnap = synchronized(lock) {
                    if (controllerGeneration != launchGen) return@synchronized null
                    activeRequests[requestId]?.let { req ->
                        activeRequests[requestId] = req.copy(
                            isAgent = true,
                            longTask = longTask,
                            currentStepIndex = stepIdx,
                            maxSteps = effectiveMaxSteps,
                            currentStepLabel = "${toolCall.reason}…",
                            progressPercent = currentProgress
                        )
                    }
                    if (panelState == NexoAiModels.PanelState.MINIMIZED) {
                        minimizedPillVisible = true
                    }
                    updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                        msg.copy(
                            text = "",
                            status = NexoAiModels.MessageStatus.STREAMING,
                            agentSteps = steps + runningStep
                        )
                    }
                    buildSnapshotLocked()
                }
                if (stepRunningSnap != null) notifyListeners(stepRunningSnap)

                val outcome = runCatching { toolExecutor.run(toolCall) }.getOrElse { err ->
                    NexoAiAgent.ToolOutcome(false, "Error al ejecutar ${toolCall.tool}", err.message ?: "Error interno.")
                }
                steps += NexoAiModels.AgentStep(toolCall.tool, outcome.summary, outcome.ok)
                outcome.pendingAction?.let { pendingActions += it }

                if (toolCall.tool == "task_checkpoint") {
                    currentProgress = toolCall.args["progress"]?.toIntOrNull()?.coerceIn(0, 100) ?: currentProgress
                    lastCheckpointText = toolCall.args["completed"].orEmpty().ifBlank { outcome.summary }
                    lastNextStep = toolCall.args["next_step"].orEmpty()
                } else if (longTask) {
                    val est = ((stepIdx.toFloat() / effectiveMaxSteps.toFloat()) * 90f).toInt().coerceIn(5, 92)
                    currentProgress = maxOf(currentProgress ?: 5, est)
                    lastCheckpointText = outcome.summary
                }

                val stepDoneSnap = synchronized(lock) {
                    if (controllerGeneration != launchGen) return@synchronized null
                    activeRequests[requestId]?.let { req ->
                        activeRequests[requestId] = req.copy(
                            isAgent = true,
                            longTask = longTask,
                            currentStepIndex = stepIdx,
                            maxSteps = effectiveMaxSteps,
                            currentStepLabel = outcome.summary,
                            progressPercent = currentProgress
                        )
                    }
                    if (longTask || toolCall.tool == "task_checkpoint") {
                        val existing = activeTasks[targetConvId]
                        activeTasks[targetConvId] = NexoAiModels.AgentTask(
                            conversationId = targetConvId,
                            goal = toolCall.args["goal"]?.takeIf { it.isNotBlank() } ?: existing?.goal ?: userTask.trim().take(160),
                            progressPercent = currentProgress ?: 50,
                            lastCheckpoint = lastCheckpointText.ifBlank { outcome.summary },
                            nextStep = lastNextStep,
                            stepsExecuted = steps.size,
                            completed = false,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    }
                    updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                        msg.copy(
                            agentSteps = steps.toList(),
                            actions = (msg.actions + pendingActions).distinctBy { "${it.type}|${it.target}|${it.value}" }
                        )
                    }
                    buildSnapshotLocked()
                }
                if (stepDoneSnap != null) notifyListeners(stepDoneSnap)

                // Añadir el paso ejecutado al historial temporal del bucle agente
                if (stepIdx == 1) {
                    loopHistory += "user" to userTask
                } else {
                    loopHistory += "user" to currentTask
                }
                loopHistory += "assistant" to "Ejecuté la herramienta ${toolCall.tool} (${toolCall.reason})."
                currentTask = NexoAiAgent.wrapToolObservation(toolCall, outcome, stepIdx, effectiveMaxSteps)
                currentContext = ""
            }

            if (longTask || activeTasks.containsKey(targetConvId)) {
                synchronized(lock) {
                    if (controllerGeneration == launchGen) {
                        val existing = activeTasks[targetConvId]
                        if (existing != null) {
                            val finishedCleanly = !cancelHandle.isCancelled && !reachedStepCap && finalResult?.isSuccess == true
                            activeTasks[targetConvId] = existing.copy(
                                progressPercent = if (finishedCleanly) 100 else (currentProgress ?: existing.progressPercent),
                                lastCheckpoint = if (finishedCleanly) "Tarea completada" else lastCheckpointText.ifBlank { existing.lastCheckpoint },
                                nextStep = if (finishedCleanly) "" else lastNextStep,
                                stepsExecuted = steps.size,
                                completed = finishedCleanly,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        }
                    }
                }
            }

            completeTurn(
                launchGen = launchGen,
                requestId = requestId,
                targetConvId = targetConvId,
                assistantMsgId = assistantMsgId,
                cancelHandle = cancelHandle,
                streamFallback = lastStream,
                result = finalResult ?: Result.failure(IllegalStateException("El agente no pudo completar la respuesta.")),
                agentSteps = steps.toList(),
                extraActions = pendingActions.toList(),
                ctxForLog = ctxForLog,
                logLabel = logLabel,
                logHost = logHost
            )
        }

        return requestId
    }

    private fun enqueueConversationTurn(
        requestId: String,
        userMsgId: String,
        assistantMsgId: String,
        now: Long,
        settings: NexoAiConfig.Settings,
        userTask: String,
        scope: String,
        pageBinding: NexoAiModels.PageBinding?,
        reasoningEffort: String?,
        conversationId: String?,
        cancelHandle: NexoAiClient.CancelHandle
    ): Triple<String, List<Pair<String, String>>, Long> {
        val effectiveModel = settings.effectiveModel()
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
            if (idx > 0) {
                conversations.removeAt(idx)
                conversations.add(0, updatedConv)
            }

            activeHandles[requestId] = cancelHandle
            val isAgentScope = scope == "agent" || scope == "long_agent"
            val isLongScope = scope == "long_agent"
            activeRequests[requestId] = NexoAiModels.ActiveRequestInfo(
                requestId = requestId,
                conversationId = targetConvId,
                assistantMessageId = assistantMsgId,
                model = effectiveModel,
                provider = settings.provider,
                startedAtMs = now,
                isAgent = isAgentScope,
                longTask = isLongScope,
                currentStepIndex = if (isAgentScope) 1 else 0,
                maxSteps = if (isLongScope) NexoAiAgent.MAX_LONG_TASK_STEPS else NexoAiAgent.MAX_AGENT_STEPS,
                currentStepLabel = if (isLongScope) "Iniciando tarea larga…" else if (isAgentScope) "Iniciando agente…" else ""
            )
            lastErrorSummary = null
            persistLocked()
            buildSnapshotLocked()
        }
        notifyListeners(snapStart)
        return Triple(targetConvId, historyTurns, launchGen)
    }

    private fun completeTurn(
        launchGen: Long,
        requestId: String,
        targetConvId: String,
        assistantMsgId: String,
        cancelHandle: NexoAiClient.CancelHandle,
        streamFallback: String,
        result: Result<NexoAiClient.AiResponse>,
        agentSteps: List<NexoAiModels.AgentStep>,
        extraActions: List<NexoAiClient.ProposedAction>,
        ctxForLog: Context?,
        logLabel: String,
        logHost: String
    ) {
        val finalSnap = synchronized(lock) {
            if (controllerGeneration != launchGen) return@synchronized null
            activeHandles.remove(requestId)
            activeRequests.remove(requestId)

            if (cancelHandle.isCancelled) {
                updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                    msg.copy(
                        text = msg.text.ifBlank { streamFallback },
                        status = NexoAiModels.MessageStatus.CANCELLED,
                        errorMessage = "Generación detenida por el usuario.",
                        agentSteps = agentSteps.ifEmpty { msg.agentSteps }
                    )
                }
            } else {
                result.fold(
                    onSuccess = { aiResp ->
                        val mergedActions = (extraActions + aiResp.actions)
                            .distinctBy { "${it.type}|${it.target}|${it.value}" }
                        updateAssistantMessageLocked(targetConvId, assistantMsgId) { msg ->
                            msg.copy(
                                text = aiResp.text,
                                status = NexoAiModels.MessageStatus.COMPLETED,
                                usage = aiResp.usage,
                                metrics = aiResp.metrics,
                                actions = mergedActions,
                                errorMessage = null,
                                agentSteps = agentSteps.ifEmpty { msg.agentSteps }
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
                                text = msg.text.ifBlank { streamFallback },
                                status = NexoAiModels.MessageStatus.ERROR,
                                errorMessage = cleanErr,
                                agentSteps = agentSteps.ifEmpty { msg.agentSteps }
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

    /** Detiene inmediatamente cualquier agente autónomo en ejecución (desde el HUD flotante). */
    fun cancelActiveAgentRequest(): Boolean {
        val reqId = synchronized(lock) {
            activeRequests.values.firstOrNull { it.isAgent }?.requestId
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
