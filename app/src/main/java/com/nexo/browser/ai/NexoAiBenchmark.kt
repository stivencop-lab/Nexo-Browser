package com.nexo.browser.ai

import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Laboratorio de comparación multimodelo (**Nexo AI Benchmark**).
 *
 * - Permite seleccionar entre 2 y 5 modelos de una misma conexión API y enviarles exactamente
 *   el mismo prompt y contexto opcional.
 * - Limita la concurrencia a un máximo razonable ([MAX_CONCURRENCY] = 2 solicitudes simultáneas).
 * - Mide tiempos reales con reloj monotónico (`NexoAiClient.nanoClock`), diferenciando latencia de red,
 *   TTFT (solo si existe streaming SSE real), tiempo de generación y duración total.
 * - Nunca inventa TTFT, tokens ni tokens/s.
 * - Un fallo o cancelación de un modelo jamás cancela ni mezcla respuestas de los demás.
 * - Calcula la mediana real por modelo cuando el usuario repite ejecuciones con la misma configuración.
 */
object NexoAiBenchmark {

    const val MIN_MODELS = 2
    const val MAX_MODELS = 5
    const val MAX_CONCURRENCY = 2

    enum class EntryStatus {
        PENDING,
        RUNNING,
        STREAMING,
        COMPLETED,
        ERROR,
        CANCELLED
    }

    data class ModelRunResult(
        val requestId: String,
        val model: String,
        val provider: String,
        val reasoningEffort: String?,
        val reasoningSupported: Boolean,
        val status: EntryStatus,
        val text: String = "",
        val usage: NexoAiClient.TokenUsage? = null,
        val metrics: NexoAiModels.AiMetrics? = null,
        val httpStatus: Int? = null,
        val errorMessage: String? = null,
        val runDurationsMs: List<Long> = emptyList(),
        val executedAtMs: Long = System.currentTimeMillis()
    ) {
        val medianDurationMs: Long?
            get() = computeMedian(runDurationsMs)

        val repeatCount: Int
            get() = runDurationsMs.size
    }

    data class BenchmarkRun(
        val runId: String,
        val prompt: String,
        val includedPageContext: Boolean,
        val provider: String,
        val reasoningEffort: String?,
        val startedAtMs: Long,
        val entries: List<ModelRunResult>
    ) {
        val isRunning: Boolean
            get() = entries.any { it.status == EntryStatus.PENDING || it.status == EntryStatus.RUNNING || it.status == EntryStatus.STREAMING }

        /** Modelo más rápido en esta ejecución (solo entre respuestas exitosas; nunca juzga calidad). */
        val fastestCompletedModel: String?
            get() = entries
                .filter { it.status == EntryStatus.COMPLETED && it.metrics != null }
                .minByOrNull { it.metrics!!.totalDurationMs }
                ?.model
    }

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(BenchmarkRun?) -> Unit>()
    private val entryHandles = ConcurrentHashMap<String, NexoAiClient.CancelHandle>()
    private val durationHistory = ConcurrentHashMap<String, MutableList<Long>>()

    private val defaultPool: ExecutorService by lazy {
        Executors.newFixedThreadPool(MAX_CONCURRENCY) { r ->
            Thread(r, "NexoAiBench").apply { isDaemon = true }
        }
    }

    @Volatile internal var transport: NexoAiController.Transport = NexoAiController.Transport { settings, apiKey, userTask, untrustedContext, cancelHandle, preferStreaming, reasoningEffort, history, onDelta ->
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

    @Volatile internal var executor: java.util.concurrent.Executor = defaultPool
    @Volatile internal var uiDispatcher: ((Runnable) -> Unit)? = null

    private var currentRun: BenchmarkRun? = null

    fun currentRun(): BenchmarkRun? = synchronized(lock) { currentRun }

    fun observe(listener: (BenchmarkRun?) -> Unit) {
        listeners.addIfAbsent(listener)
        dispatch(listener, currentRun())
    }

    fun unobserve(listener: (BenchmarkRun?) -> Unit) {
        listeners.remove(listener)
    }

    internal fun resetForTest(
        testTransport: NexoAiController.Transport,
        testExecutor: java.util.concurrent.Executor = defaultPool,
        syncUi: Boolean = true
    ) {
        synchronized(lock) {
            entryHandles.values.forEach { it.cancel() }
            entryHandles.clear()
            durationHistory.clear()
            listeners.clear()
            currentRun = null
            transport = testTransport
            executor = testExecutor
            uiDispatcher = if (syncUi) { r -> r.run() } else null
        }
    }

    /** Calcula la mediana exacta en ms (puro, probado en la JVM). */
    fun computeMedian(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2L
        }
    }

    /** Valida la selección de modelos antes de lanzar el benchmark. */
    fun validateSelection(models: List<String>, prompt: String): String? {
        val clean = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.size < MIN_MODELS) return "Selecciona al menos $MIN_MODELS modelos para comparar."
        if (clean.size > MAX_MODELS) return "Puedes comparar un máximo de $MAX_MODELS modelos por ejecución."
        if (prompt.trim().isBlank()) return "Escribe un prompt de prueba para enviar a los modelos."
        return null
    }

    /**
     * Inicia una comparación justa de 2 a 5 modelos sobre la misma conexión API.
     * Concurrencia acotada a [MAX_CONCURRENCY] (= 2) solicitudes simultáneas.
     */
    fun startComparison(
        baseSettings: NexoAiConfig.Settings,
        apiKey: String,
        models: List<String>,
        prompt: String,
        optionalContext: String = "",
        reasoningEffort: String? = null,
        preferStreaming: Boolean = true
    ): Result<BenchmarkRun> {
        val cleanModels = models.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val err = validateSelection(cleanModels, prompt)
        if (err != null) return Result.failure(IllegalArgumentException(err))

        val trimmedPrompt = prompt.trim()
        val boundedContext = optionalContext.trim()
        val runId = "bench_${UUID.randomUUID().toString().take(10)}"
        val now = System.currentTimeMillis()

        // Cancelar handles huérfanos de una corrida previa solo si ya estaba en curso
        val previousHandles = entryHandles.values.toList()
        entryHandles.clear()
        previousHandles.forEach { it.cancel() }

        val entries = cleanModels.map { model ->
            val reqId = "${runId}_${UUID.randomUUID().toString().take(8)}"
            val historyKey = historyKey(baseSettings.provider, model, reasoningEffort, trimmedPrompt)
            val prevDurations = durationHistory[historyKey]?.toList().orEmpty()
            ModelRunResult(
                requestId = reqId,
                model = model,
                provider = baseSettings.provider,
                reasoningEffort = reasoningEffort?.takeIf { NexoAiClient.supportsReasoningEffort(baseSettings.provider, model) },
                reasoningSupported = NexoAiClient.supportsReasoningEffort(baseSettings.provider, model),
                status = EntryStatus.PENDING,
                runDurationsMs = prevDurations,
                executedAtMs = now
            )
        }

        val run = BenchmarkRun(
            runId = runId,
            prompt = trimmedPrompt,
            includedPageContext = boundedContext.isNotBlank(),
            provider = baseSettings.provider,
            reasoningEffort = reasoningEffort,
            startedAtMs = now,
            entries = entries
        )

        val handlesByReq = LinkedHashMap<String, NexoAiClient.CancelHandle>()
        entries.forEach { e ->
            val h = NexoAiClient.CancelHandle()
            handlesByReq[e.requestId] = h
            entryHandles[e.requestId] = h
        }

        synchronized(lock) {
            currentRun = run
        }
        notifyAll(run)

        val semaphore = Semaphore(MAX_CONCURRENCY)
        for (entry in entries) {
            val handle = handlesByReq[entry.requestId]!!
            executor.execute {
                if (handle.isCancelled) return@execute
                var acquired = false
                try {
                    semaphore.acquire()
                    acquired = true
                    if (handle.isCancelled) return@execute

                    updateEntry(runId, entry.requestId) { it.copy(status = EntryStatus.RUNNING) }

                    val modelSettings = baseSettings.copy(model = entry.model)
                    val streamBuf = StringBuilder()
                    var lastEmit = 0L

                    val result = transport.execute(
                        settings = modelSettings,
                        apiKey = apiKey,
                        userTask = trimmedPrompt,
                        untrustedContext = boundedContext,
                        cancelHandle = handle,
                        preferStreaming = preferStreaming,
                        reasoningEffort = entry.reasoningEffort,
                        history = emptyList()
                    ) { delta ->
                        if (handle.isCancelled) return@execute
                        streamBuf.append(delta)
                        val t = System.currentTimeMillis()
                        if (t - lastEmit >= 80L || streamBuf.length <= delta.length) {
                            lastEmit = t
                            updateEntry(runId, entry.requestId) {
                                it.copy(status = EntryStatus.STREAMING, text = streamBuf.toString())
                            }
                        }
                    }

                    entryHandles.remove(entry.requestId)

                    if (handle.isCancelled) {
                        updateEntry(runId, entry.requestId) {
                            it.copy(
                                status = EntryStatus.CANCELLED,
                                text = it.text.ifBlank { streamBuf.toString() },
                                errorMessage = "Cancelado por el usuario."
                            )
                        }
                        return@execute
                    }

                    result.fold(
                        onSuccess = { resp ->
                            val durationMs = resp.metrics?.totalDurationMs
                            val hKey = historyKey(baseSettings.provider, entry.model, reasoningEffort, trimmedPrompt)
                            val updatedDurations = if (durationMs != null) {
                                durationHistory.compute(hKey) { _, prev ->
                                    val list = prev ?: mutableListOf()
                                    list.add(durationMs)
                                    while (list.size > 10) list.removeAt(0)
                                    list
                                }?.toList().orEmpty()
                            } else entry.runDurationsMs

                            updateEntry(runId, entry.requestId) {
                                it.copy(
                                    status = EntryStatus.COMPLETED,
                                    text = resp.text,
                                    usage = resp.usage,
                                    metrics = resp.metrics,
                                    httpStatus = resp.metrics?.httpStatus ?: 200,
                                    errorMessage = null,
                                    runDurationsMs = updatedDurations,
                                    executedAtMs = System.currentTimeMillis()
                                )
                            }
                        },
                        onFailure = { err ->
                            val httpCode = (err as? NexoAiClient.HttpStatusException)?.statusCode
                            val cleanErr = NexoAiConfig.sanitizeLogText(err.message ?: "Error al consultar el modelo.")
                            updateEntry(runId, entry.requestId) {
                                it.copy(
                                    status = EntryStatus.ERROR,
                                    text = it.text.ifBlank { streamBuf.toString() },
                                    httpStatus = httpCode,
                                    errorMessage = cleanErr,
                                    executedAtMs = System.currentTimeMillis()
                                )
                            }
                        }
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    entryHandles.remove(entry.requestId)
                    if (acquired) semaphore.release()
                }
            }
        }

        return Result.success(run)
    }

    /** Cancela un modelo individual sin interrumpir los demás modelos de la comparación. */
    fun cancelModel(requestId: String): Boolean {
        val handle = entryHandles.remove(requestId) ?: return false
        handle.cancel()
        val updated = synchronized(lock) {
            val run = currentRun ?: return false
            val idx = run.entries.indexOfFirst { it.requestId == requestId }
            if (idx < 0) return false
            val list = run.entries.toMutableList()
            list[idx] = list[idx].copy(
                status = EntryStatus.CANCELLED,
                errorMessage = "Cancelado por el usuario."
            )
            run.copy(entries = list).also { currentRun = it }
        }
        notifyAll(updated)
        return true
    }

    /** Cancela todos los modelos aún pendientes/en curso de la comparación actual. */
    fun cancelAll() {
        val handles = entryHandles.entries.toList()
        entryHandles.clear()
        handles.forEach { it.value.cancel() }
        val updated = synchronized(lock) {
            val run = currentRun ?: return
            val list = run.entries.map { entry ->
                if (entry.status == EntryStatus.PENDING || entry.status == EntryStatus.RUNNING || entry.status == EntryStatus.STREAMING) {
                    entry.copy(status = EntryStatus.CANCELLED, errorMessage = "Cancelado por el usuario.")
                } else entry
            }
            run.copy(entries = list).also { currentRun = it }
        }
        notifyAll(updated)
    }

    private fun historyKey(provider: String, model: String, reasoning: String?, prompt: String): String =
        "$provider|$model|${reasoning.orEmpty()}|${prompt.hashCode()}"

    private fun updateEntry(
        runId: String,
        requestId: String,
        transform: (ModelRunResult) -> ModelRunResult
    ) {
        val updated = synchronized(lock) {
            val run = currentRun
            if (run == null || run.runId != runId) return
            val idx = run.entries.indexOfFirst { it.requestId == requestId }
            if (idx < 0) return
            val list = run.entries.toMutableList()
            list[idx] = transform(list[idx])
            run.copy(entries = list).also { currentRun = it }
        }
        notifyAll(updated)
    }

    private fun notifyAll(run: BenchmarkRun?) {
        for (l in listeners) dispatch(l, run)
    }

    private fun dispatch(listener: (BenchmarkRun?) -> Unit, run: BenchmarkRun?) {
        val custom = uiDispatcher
        if (custom != null) {
            custom { listener(run) }
            return
        }
        val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull()
        if (mainLooper != null) {
            if (Looper.myLooper() == mainLooper) listener(run)
            else Handler(mainLooper).post { listener(run) }
        } else {
            listener(run)
        }
    }
}
