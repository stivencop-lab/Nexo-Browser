package com.nexo.browser

import com.nexo.browser.ai.NexoAiBenchmark
import com.nexo.browser.ai.NexoAiClient
import com.nexo.browser.ai.NexoAiConfig
import com.nexo.browser.ai.NexoAiController
import com.nexo.browser.ai.NexoAiModels
import com.nexo.browser.ai.NexoAiPageAgent
import com.nexo.browser.ai.NexoAiStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NexoAi51Test {

    @get:Rule
    val temp = TemporaryFolder()

    private val baseSettings = NexoAiConfig.Settings(
        enabled = true,
        consentAccepted = true,
        provider = "openai",
        model = "gpt-4.1-mini"
    )

    // ==================== PERSISTENCIA Y MINIMIZACIÓN ====================

    @Test
    fun minimizeDuringQueryKeepsRequestAliveAndRestoresCompletedAnswer() {
        val storeFile = temp.newFile("ai_minimize.json")
        val startedLatch = CountDownLatch(1)
        val allowFinishLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)
        val pool = Executors.newCachedThreadPool()

        NexoAiController.initializeForTest(
            storageFile = storeFile,
            testExecutor = pool,
            testTransport = NexoAiController.Transport { settings, _, _, _, _, _, _, _, onDelta ->
                startedLatch.countDown()
                onDelta("Resumen ")
                allowFinishLatch.await(5, TimeUnit.SECONDS)
                onDelta("completo.")
                Result.success(
                    NexoAiClient.AiResponse(
                        text = "Resumen completo.",
                        usage = NexoAiClient.TokenUsage(100, 25, 125),
                        metrics = NexoAiModels.AiMetrics(
                            totalDurationMs = 1200L,
                            networkLatencyMs = 200L,
                            timeToFirstTokenMs = 350L,
                            generationDurationMs = 850L,
                            tokensPerSecond = 29.4,
                            httpStatus = 200,
                            streamed = true,
                            model = settings.effectiveModel(),
                            provider = settings.provider
                        )
                    )
                )
            }
        )

        val observer: (NexoAiModels.ControllerSnapshot) -> Unit = { snap ->
            val last = snap.activeConversation?.messages?.lastOrNull()
            if (last?.status == NexoAiModels.MessageStatus.COMPLETED && last.role == NexoAiModels.Role.ASSISTANT) {
                doneLatch.countDown()
            }
        }
        NexoAiController.observe(observer)
        try {
            NexoAiController.setPanelState(NexoAiModels.PanelState.PARTIAL)
            val binding = NexoAiModels.PageBinding("tab-1", "https://example.com/a", "example.com", "Art A", 3L)
            NexoAiController.sendPrompt(
                settings = baseSettings,
                apiKey = "sk-test",
                userTask = "Resume esta página",
                untrustedContext = "Contenido",
                pageBinding = binding
            )

            assertTrue("La petición debe comenzar en segundo plano", startedLatch.await(3, TimeUnit.SECONDS))

            // 1. Minimizar durante la consulta: JAMÁS debe cancelar la solicitud
            NexoAiController.setPanelState(NexoAiModels.PanelState.MINIMIZED)
            val whileMinimized = NexoAiController.snapshot()
            assertTrue("Debe seguir activa tras minimizar", whileMinimized.anyGenerating)
            assertEquals(NexoAiModels.IndicatorState.GENERATING, whileMinimized.indicatorState)
            assertTrue("La píldora minimizada debe ser visible", whileMinimized.minimizedPillVisible)

            // 2. Restaurar ANTES de recibir respuesta completa: conserva el hilo y streaming parcial
            NexoAiController.setPanelState(NexoAiModels.PanelState.EXPANDED)
            val restoredEarly = NexoAiController.snapshot()
            assertTrue(restoredEarly.anyGenerating)
            assertEquals(2, restoredEarly.activeConversation?.messages?.size)

            // 3. Minimizar de nuevo y dejar que termine la respuesta mientras el usuario navega
            NexoAiController.setPanelState(NexoAiModels.PanelState.MINIMIZED)
            allowFinishLatch.countDown()
            assertTrue("La respuesta debe completarse aunque el panel esté minimizado", doneLatch.await(5, TimeUnit.SECONDS))

            val readySnap = NexoAiController.snapshot()
            assertFalse(readySnap.anyGenerating)
            assertEquals(NexoAiModels.IndicatorState.READY, readySnap.indicatorState)
            assertNotNull(readySnap.unreadReadyConversationId)
            assertTrue(readySnap.minimizedPillVisible)

            // 4. Restaurar DESPUÉS de recibir respuesta: lee el texto exacto y limpia indicador no leído
            NexoAiController.setPanelState(NexoAiModels.PanelState.PARTIAL)
            val finalSnap = NexoAiController.snapshot()
            assertEquals(NexoAiModels.IndicatorState.IDLE, finalSnap.indicatorState)
            val reply = finalSnap.activeConversation!!.messages.last()
            assertEquals("Resumen completo.", reply.text)
            assertEquals(NexoAiModels.MessageStatus.COMPLETED, reply.status)
            assertEquals(350L, reply.metrics?.timeToFirstTokenMs)
        } finally {
            NexoAiController.unobserve(observer)
            pool.shutdownNow()
        }
    }

    @Test
    fun dismissMinimizedPillPreservesHistoryAndProcessRestartMarksInFlightAsInterrupted() {
        val storeFile = temp.newFile("ai_restart.json")
        val blockLatch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)
        val pool = Executors.newCachedThreadPool()

        // 1. Primera sesión: una respuesta completada + una segunda consulta aún en vuelo al morir el proceso
        NexoAiController.initializeForTest(
            storageFile = storeFile,
            testExecutor = pool,
            testTransport = NexoAiController.Transport { _, _, task, _, _, _, _, _, _ ->
                if (task.contains("Primera")) {
                    Result.success(NexoAiClient.AiResponse("Respuesta guardada.", null))
                } else {
                    startedLatch.countDown()
                    blockLatch.await(5, TimeUnit.SECONDS)
                    Result.success(NexoAiClient.AiResponse("Nunca llega si el proceso muere", null))
                }
            }
        )

        val firstDone = CountDownLatch(1)
        val obs: (NexoAiModels.ControllerSnapshot) -> Unit = { snap ->
            if (snap.activeConversation?.messages?.any { it.text == "Respuesta guardada." } == true) {
                firstDone.countDown()
            }
        }
        NexoAiController.observe(obs)
        NexoAiController.sendPrompt(baseSettings, "sk-test", "Primera consulta", "")
        assertTrue(firstDone.await(3, TimeUnit.SECONDS))
        NexoAiController.unobserve(obs)

        // Minimizar y cerrar explícitamente la píldora flotante NO borra el historial
        NexoAiController.setPanelState(NexoAiModels.PanelState.MINIMIZED)
        assertTrue(NexoAiController.snapshot().minimizedPillVisible)
        NexoAiController.dismissMinimizedPill()
        assertFalse(NexoAiController.snapshot().minimizedPillVisible)
        assertEquals(2, NexoAiController.snapshot().activeConversation?.messages?.size)

        // Lanzar segunda consulta que queda en curso cuando "muere" el proceso
        NexoAiController.sendPrompt(baseSettings, "sk-test", "Segunda en curso", "")
        assertTrue(startedLatch.await(3, TimeUnit.SECONDS))

        // 2. Simular reinicio completo de la aplicación leyendo desde disco
        NexoAiController.simulateProcessRestartForTest(storeFile)
        blockLatch.countDown()
        pool.shutdownNow()

        val afterRestart = NexoAiController.snapshot()
        val msgs = afterRestart.activeConversation!!.messages
        assertEquals(4, msgs.size)
        assertEquals("Respuesta guardada.", msgs[1].text)
        assertEquals(NexoAiModels.MessageStatus.COMPLETED, msgs[1].status)
        assertEquals(NexoAiModels.MessageStatus.INTERRUPTED, msgs[3].status)
        assertEquals("Segunda en curso", msgs[3].retryPrompt)
        assertNotNull(msgs[3].errorMessage)
    }

    @Test
    fun explicitCancelStopsOnlyTargetedRequestAndModelChangeDoesNotMixThreads() {
        val storeFile = temp.newFile("ai_cancel_isolation.json")
        val bothStarted = CountDownLatch(2)
        val releaseB = CountDownLatch(1)
        val bFinished = CountDownLatch(1)
        val pool = Executors.newCachedThreadPool()

        NexoAiController.initializeForTest(
            storageFile = storeFile,
            testExecutor = pool,
            testTransport = NexoAiController.Transport { settings, _, task, _, handle, _, _, _, _ ->
                bothStarted.countDown()
                if (task == "Consulta A") {
                    while (!handle.isCancelled) {
                        Thread.sleep(15)
                    }
                    Result.failure(IOException("Solicitud cancelada por el usuario."))
                } else {
                    releaseB.await(5, TimeUnit.SECONDS)
                    Result.success(NexoAiClient.AiResponse("Respuesta de ${settings.effectiveModel()}", null))
                }
            }
        )

        val obs: (NexoAiModels.ControllerSnapshot) -> Unit = { snap ->
            if (snap.conversations.any { c -> c.messages.any { it.text == "Respuesta de gpt-4.1" } }) {
                bFinished.countDown()
            }
        }
        NexoAiController.observe(obs)
        try {
            val convA = NexoAiController.snapshot().activeConversationId
            val reqA = NexoAiController.sendPrompt(
                settings = baseSettings.copy(model = "gpt-4.1-mini"),
                apiKey = "sk-test",
                userTask = "Consulta A",
                untrustedContext = "",
                conversationId = convA
            )
            // Crear conversación nueva con otro modelo mientras A sigue en curso
            val convB = NexoAiController.newConversation("docs.example.com")
            NexoAiController.sendPrompt(
                settings = baseSettings.copy(model = "gpt-4.1"),
                apiKey = "sk-test",
                userTask = "Consulta B",
                untrustedContext = "",
                conversationId = convB
            )

            assertTrue(bothStarted.await(3, TimeUnit.SECONDS))

            // Cancelar únicamente reqA: convB debe continuar y finalizar con su propio modelo
            assertTrue(NexoAiController.cancelRequest(reqA))
            releaseB.countDown()
            assertTrue(bFinished.await(3, TimeUnit.SECONDS))

            val snap = NexoAiController.snapshot()
            val threadA = snap.conversations.first { it.id == convA }
            val threadB = snap.conversations.first { it.id == convB }
            assertEquals(NexoAiModels.MessageStatus.CANCELLED, threadA.messages.last().status)
            assertEquals(NexoAiModels.MessageStatus.COMPLETED, threadB.messages.last().status)
            assertEquals("Respuesta de gpt-4.1", threadB.messages.last().text)
            assertEquals("gpt-4.1", threadB.messages.last().model)
        } finally {
            NexoAiController.unobserve(obs)
            pool.shutdownNow()
        }
    }

    @Test
    fun pageBindingPreventsStaleActionsAfterTabOrUrlChange() {
        val original = NexoAiModels.PageBinding("tab-1", "https://bank.example/home", "bank.example", "Inicio", 4L)
        val samePage = NexoAiModels.PageBinding("tab-1", "https://bank.example/home", "bank.example", "Inicio", 4L)
        val navigatedUrl = NexoAiModels.PageBinding("tab-1", "https://other.example/page", "other.example", "Otra", 5L)
        val switchedTab = NexoAiModels.PageBinding("tab-2", "https://bank.example/home", "bank.example", "Inicio", 4L)
        val reloadedSameUrl = NexoAiModels.PageBinding("tab-1", "https://bank.example/home", "bank.example", "Inicio", 5L)

        assertTrue(NexoAiPageAgent.isBindingMatchingCurrent(original, samePage))
        assertFalse(NexoAiPageAgent.isBindingMatchingCurrent(original, navigatedUrl))
        assertFalse(NexoAiPageAgent.isBindingMatchingCurrent(original, switchedTab))
        assertFalse(NexoAiPageAgent.isBindingMatchingCurrent(original, reloadedSameUrl))
        assertFalse(NexoAiPageAgent.isBindingMatchingCurrent(original, null))
    }

    // ==================== STREAMING SSE REAL Y MÉTRICAS ====================

    @Test
    fun parsesOpenAiAndClaudeSseStreamsWithRealTtftAndUsage() {
        val openAiSse = """
            data: {"choices":[{"delta":{"role":"assistant"}}]}
            
            data: {"choices":[{"delta":{"content":"Hola "}}]}
            
            data: {"choices":[{"delta":{"content":"Nexo AI!"}}]}
            
            data: {"choices":[],"usage":{"prompt_tokens":40,"completion_tokens":12,"total_tokens":52}}
            
            data: [DONE]
        """.trimIndent()
        val deltas = mutableListOf<String>()
        var firstTokNanos: Long? = null
        val parsedOpenAi = NexoAiClient.parseSseStream(
            provider = "openai",
            reader = BufferedReader(StringReader(openAiSse)),
            onDelta = { deltas += it },
            onFirstTokenNanos = { firstTokNanos = it }
        ).getOrThrow()

        assertEquals("Hola Nexo AI!", parsedOpenAi.first)
        assertEquals(listOf("Hola ", "Nexo AI!"), deltas)
        assertNotNull("TTFT debe registrarse en el primer delta real", firstTokNanos)
        assertEquals(40, parsedOpenAi.second?.promptTokens)
        assertEquals(12, parsedOpenAi.second?.completionTokens)
        assertEquals(52, parsedOpenAi.second?.totalTokens)

        val claudeSse = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":60}}}
            
            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Claude "}}
            
            event: content_block_delta
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"stream."}}
            
            event: message_delta
            data: {"type":"message_delta","usage":{"output_tokens":18}}
        """.trimIndent()
        val parsedClaude = NexoAiClient.parseSseStream(
            provider = "claude",
            reader = BufferedReader(StringReader(claudeSse))
        ).getOrThrow()
        assertEquals("Claude stream.", parsedClaude.first)
        assertEquals(60, parsedClaude.second?.promptTokens)
        assertEquals(18, parsedClaude.second?.completionTokens)
        assertEquals(78, parsedClaude.second?.totalTokens)
    }

    @Test
    fun parseModelListFiltersNonChatModelsAndReasoningEffortRespectsCapabilities() {
        val rawModels = """
            {
              "data": [
                {"id": "gpt-4.1-mini"},
                {"id": "whisper-1"},
                {"id": "text-embedding-3-small"},
                {"id": "tts-1-hd"},
                {"id": "o3-mini"},
                {"id": "dall-e-3"},
                {"id": "gpt-4.1"}
              ]
            }
        """.trimIndent()
        val list = NexoAiClient.parseModelList(rawModels)
        assertEquals(listOf("gpt-4.1", "gpt-4.1-mini", "o3-mini"), list)

        // reasoning_effort solo se envía a modelos compatibles
        val reqO3 = NexoAiClient.buildRequest(
            settings = baseSettings.copy(model = "o3-mini"),
            apiKey = "sk-test",
            userTask = "Resuelve",
            untrustedContext = "",
            stream = true,
            reasoningEffort = "high"
        )
        val bodyO3 = JSONObject(reqO3.bodyJson)
        assertEquals("high", bodyO3.optString("reasoning_effort"))
        assertTrue(bodyO3.optBoolean("stream"))

        val reqStd = NexoAiClient.buildRequest(
            settings = baseSettings.copy(model = "gpt-4.1-mini"),
            apiKey = "sk-test",
            userTask = "Resuelve",
            untrustedContext = "",
            stream = true,
            reasoningEffort = "high"
        )
        assertFalse(JSONObject(reqStd.bodyJson).has("reasoning_effort"))

        // Nunca inventar tokens/s si faltan tokens reales
        assertNull(NexoAiModels.AiMetrics.computeTokensPerSecond(null, 1000L, 1000L))
        assertNull(NexoAiModels.AiMetrics.computeTokensPerSecond(3, 1000L, 1000L))
        assertEquals(50.0, NexoAiModels.AiMetrics.computeTokensPerSecond(50, 1000L, 1500L)!!, 0.01)
    }

    // ==================== BENCHMARK MULTIMODELO ====================

    @Test
    fun benchmarkValidates2To5ModelsBoundsConcurrencyAndHandlesPartialFailuresAndRepeats() {
        assertNotNull(NexoAiBenchmark.validateSelection(listOf("m1"), "Prompt"))
        assertNotNull(NexoAiBenchmark.validateSelection(listOf("m1", "m2", "m3", "m4", "m5", "m6"), "Prompt"))
        assertNotNull(NexoAiBenchmark.validateSelection(listOf("m1", "m2"), "   "))
        assertNull(NexoAiBenchmark.validateSelection(listOf("m1", "m2"), "Prompt"))
        assertNull(NexoAiBenchmark.validateSelection(listOf("m1", "m2", "m3", "m4", "m5"), "Prompt"))

        val activeCount = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val finishedLatch = CountDownLatch(5)
        val pool = Executors.newFixedThreadPool(4)

        NexoAiBenchmark.resetForTest(
            testExecutor = pool,
            testTransport = NexoAiController.Transport { settings, _, _, _, _, _, reasoning, _, _ ->
                val cur = activeCount.incrementAndGet()
                maxObservedConcurrency.accumulateAndGet(cur, ::maxOf)
                try {
                    Thread.sleep(40)
                    val m = settings.effectiveModel()
                    when (m) {
                        "model-quota-429" -> Result.failure(NexoAiClient.HttpStatusException(429, "Cuota agotada (HTTP 429)"))
                        "model-timeout" -> Result.failure(IOException("Tiempo de espera agotado"))
                        "model-no-stream" -> Result.success(
                            NexoAiClient.AiResponse(
                                text = "Respuesta sin streaming de $m",
                                usage = null, // tokens no disponibles
                                metrics = NexoAiModels.AiMetrics(
                                    totalDurationMs = 900L,
                                    timeToFirstTokenMs = null,
                                    streamed = false,
                                    model = m,
                                    provider = settings.provider,
                                    reasoningEffort = reasoning
                                )
                            )
                        )
                        "o3-mini" -> Result.success(
                            NexoAiClient.AiResponse(
                                text = "Respuesta razonada de $m",
                                usage = NexoAiClient.TokenUsage(20, 40, 60),
                                metrics = NexoAiModels.AiMetrics(
                                    totalDurationMs = 1400L,
                                    timeToFirstTokenMs = 600L,
                                    generationDurationMs = 800L,
                                    tokensPerSecond = 50.0,
                                    streamed = true,
                                    model = m,
                                    provider = settings.provider,
                                    reasoningEffort = reasoning
                                )
                            )
                        )
                        else -> Result.success(
                            NexoAiClient.AiResponse(
                                text = "Respuesta rápida de $m",
                                usage = NexoAiClient.TokenUsage(20, 30, 50),
                                metrics = NexoAiModels.AiMetrics(
                                    totalDurationMs = 450L,
                                    timeToFirstTokenMs = 150L,
                                    generationDurationMs = 300L,
                                    tokensPerSecond = 100.0,
                                    streamed = true,
                                    model = m,
                                    provider = settings.provider,
                                    reasoningEffort = reasoning
                                )
                            )
                        )
                    }
                } finally {
                    activeCount.decrementAndGet()
                    finishedLatch.countDown()
                }
            }
        )

        try {
            val models = listOf("gpt-4.1-mini", "o3-mini", "model-no-stream", "model-quota-429", "model-timeout")
            val started = NexoAiBenchmark.startComparison(
                baseSettings = baseSettings,
                apiKey = "sk-test",
                models = models,
                prompt = "Explica qué es TCP en 2 líneas.",
                reasoningEffort = "medium"
            ).getOrThrow()
            assertEquals(5, started.entries.size)
            assertTrue("Todos los modelos deben concluir", finishedLatch.await(8, TimeUnit.SECONDS))
            // Esperar a que las actualizaciones finales de entries se publiquen
            val deadline = System.currentTimeMillis() + 3000L
            while (NexoAiBenchmark.currentRun()?.isRunning == true && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }

            assertTrue(
                "La concurrencia máxima nunca debe superar ${NexoAiBenchmark.MAX_CONCURRENCY}",
                maxObservedConcurrency.get() <= NexoAiBenchmark.MAX_CONCURRENCY
            )

            val run = NexoAiBenchmark.currentRun()!!
            assertEquals("gpt-4.1-mini", run.fastestCompletedModel)

            val byModel = run.entries.associateBy { it.model }
            // Sin mezcla de respuestas entre modelos
            assertEquals("Respuesta rápida de gpt-4.1-mini", byModel["gpt-4.1-mini"]!!.text)
            assertEquals("Respuesta razonada de o3-mini", byModel["o3-mini"]!!.text)
            assertEquals("Respuesta sin streaming de model-no-stream", byModel["model-no-stream"]!!.text)

            // Modelo sin streaming ni tokens -> TTFT y tokens honestamente null
            assertNull(byModel["model-no-stream"]!!.metrics?.timeToFirstTokenMs)
            assertNull(byModel["model-no-stream"]!!.usage)
            assertNull(byModel["model-no-stream"]!!.metrics?.tokensPerSecond)

            // Diferentes capacidades de razonamiento
            assertEquals("medium", byModel["o3-mini"]!!.reasoningEffort)
            assertTrue(byModel["o3-mini"]!!.reasoningSupported)
            assertNull(byModel["gpt-4.1-mini"]!!.reasoningEffort)
            assertFalse(byModel["gpt-4.1-mini"]!!.reasoningSupported)

            // Fallos individuales (HTTP 429 y timeout) sin cancelar los demás
            assertEquals(NexoAiBenchmark.EntryStatus.ERROR, byModel["model-quota-429"]!!.status)
            assertEquals(429, byModel["model-quota-429"]!!.httpStatus)
            assertEquals(NexoAiBenchmark.EntryStatus.ERROR, byModel["model-timeout"]!!.status)

            // Mediana exacta en repeticiones
            assertEquals(500L, NexoAiBenchmark.computeMedian(listOf(900L, 400L, 500L)))
            assertEquals(600L, NexoAiBenchmark.computeMedian(listOf(400L, 500L, 700L, 900L)))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun benchmarkCancelsSingleModelWithoutStoppingOtherModel() {
        val started = CountDownLatch(2)
        val releaseFast = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        NexoAiBenchmark.resetForTest(
            testExecutor = pool,
            testTransport = NexoAiController.Transport { settings, _, _, _, handle, _, _, _, _ ->
                started.countDown()
                if (settings.effectiveModel() == "slow-model") {
                    while (!handle.isCancelled) Thread.sleep(15)
                    Result.failure(IOException("Cancelado"))
                } else {
                    releaseFast.await(5, TimeUnit.SECONDS)
                    Result.success(
                        NexoAiClient.AiResponse(
                            text = "Listo",
                            usage = NexoAiClient.TokenUsage(10, 15, 25),
                            metrics = NexoAiModels.AiMetrics(totalDurationMs = 300L, streamed = true, timeToFirstTokenMs = 100L)
                        )
                    )
                }
            }
        )

        try {
            val run = NexoAiBenchmark.startComparison(
                baseSettings = baseSettings,
                apiKey = "sk-test",
                models = listOf("slow-model", "fast-model"),
                prompt = "Hola"
            ).getOrThrow()

            assertTrue(started.await(3, TimeUnit.SECONDS))
            val slowReqId = run.entries.first { it.model == "slow-model" }.requestId
            assertTrue(NexoAiBenchmark.cancelModel(slowReqId))
            releaseFast.countDown()

            val deadline = System.currentTimeMillis() + 3000L
            while (NexoAiBenchmark.currentRun()?.isRunning == true && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val finalRun = NexoAiBenchmark.currentRun()!!
            val byModel = finalRun.entries.associateBy { it.model }
            assertEquals(NexoAiBenchmark.EntryStatus.CANCELLED, byModel["slow-model"]!!.status)
            assertEquals(NexoAiBenchmark.EntryStatus.COMPLETED, byModel["fast-model"]!!.status)
            assertEquals("Listo", byModel["fast-model"]!!.text)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun storeDoesNotPersistApiKeysAndRecoversValidConversations() {
        val file = temp.newFile("ai_store_sec.json")
        val store = NexoAiStore(file)
        val msg = NexoAiModels.AiMessage(
            id = "m1",
            role = NexoAiModels.Role.ASSISTANT,
            text = "Hola mundo",
            timestampMs = 1000L,
            status = NexoAiModels.MessageStatus.ERROR,
            errorMessage = "Fallo con Bearer sk-secret-1234567890"
        )
        val conv = NexoAiModels.AiConversation("c1", "Test", 1000L, 1000L, listOf(msg))
        store.save("c1", listOf(conv))

        val raw = file.readText()
        assertFalse("Nunca deben guardarse claves API en el almacén de hilos", raw.contains("sk-secret-1234567890"))
        val loaded = store.load()
        assertEquals("c1", loaded.activeConversationId)
        assertEquals(1, loaded.conversations.size)
        assertEquals("Hola mundo", loaded.conversations.first().messages.first().text)
    }
}
