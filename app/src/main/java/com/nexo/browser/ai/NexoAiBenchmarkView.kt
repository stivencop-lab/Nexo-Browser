package com.nexo.browser.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.nexo.browser.CaptionProtocol
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Vista nativa del laboratorio **Comparar modelos (Nexo AI Benchmark)** integrada dentro de Nexo AI.
 *
 * Permite elegir una conexión API configurada, cargar/seleccionar entre 2 y 5 modelos,
 * escribir un prompt único (con contexto de página únicamente bajo activación explícita),
 * ejecutar la comparación con concurrencia controlada (= 2) y revisar tiempos reales,
 * TTFT (solo streaming SSE real), tokens reales, mediana en repeticiones y respuestas desplegables.
 */
class NexoAiBenchmarkView(
    private val activity: Activity,
    private val onRequestScrollDown: () -> Unit = {}
) : LinearLayout(activity) {

    private val catalogIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NexoAiModelCatalog").apply { isDaemon = true }
    }
    private val p = ThemeManager.palette(activity)
    private val dp = { v: Float -> NexoUi.dp(activity, v) }

    private var settings = NexoAiConfig.read(activity)
    private var selectedProvider = settings.provider
    private var availableModels: List<String> = initialModelsFor(settings)
    private val selectedModels = LinkedHashSet<String>().apply {
        addAll(availableModels.take(2))
    }
    private var reasoningEffort: String? = null
    private var includePageContext: Boolean = false
    private var catalogNote: String = "Toca «Cargar modelos» para consultar la API o usa la lista sugerida."
    private var loadingCatalog: Boolean = false
    private val expandedEntries = HashSet<String>()

    private val providerRowHolder: LinearLayout
    private val catalogNoteView: TextView
    private val selectedCountBadge: TextView
    private val modelChipsHolder: LinearLayout
    private val customModelField: EditText
    private val promptField: EditText
    private val reasoningRowHolder: LinearLayout
    private val runBtnHolder: LinearLayout
    private val resultsContainer: LinearLayout

    private val benchObserver: (NexoAiBenchmark.BenchmarkRun?) -> Unit = { run ->
        if (!activity.isFinishing && !activity.isDestroyed) {
            renderRunResults(run)
            renderActionButtons(run)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(NexoUi.Tokens.SHEET_PADDING), dp(14f), dp(NexoUi.Tokens.SHEET_PADDING), dp(18f))

        // Aviso honesto de cuota: línea discreta al pie (sin cajas tintadas).
        val quotaNote = TextView(activity).apply {
            text = "Máx. 2 solicitudes en paralelo · Usa tu propia clave API · La página solo se envía si lo activas"
            textSize = 12f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(14f), dp(8f), 0)
        }

        // Resultados de comparación visibles de inmediato al ejecutarse o reabrirse el laboratorio
        resultsContainer = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(0, 0, 0, dp(6f))
        }
        addView(resultsContainer)

        // Conexión API
        addView(NexoUi.section(activity, "Conexión API"))
        providerRowHolder = LinearLayout(activity).apply { orientation = VERTICAL }
        addView(providerRowHolder)

        // Cabecera de modelos + botón cargar desde API
        val modelHeader = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(4f))
        }
        modelHeader.addView(TextView(activity).apply {
            text = "Modelos (2 a 5)"
            textSize = 15f
            letterSpacing = -0.025f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectedCountBadge = NexoUi.statusPill(activity, "2/5", NexoUi.Tone.ACCENT)
        modelHeader.addView(selectedCountBadge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8f)
        })
        val fetchBtn = NexoUi.chip(activity, "Cargar modelos", false) {
            fetchModelsFromApi()
        }
        modelHeader.addView(fetchBtn)
        addView(modelHeader)

        catalogNoteView = TextView(activity).apply {
            text = catalogNote
            textSize = 11.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            setPadding(0, 0, 0, dp(6f))
        }
        addView(catalogNoteView)

        modelChipsHolder = LinearLayout(activity).apply { orientation = VERTICAL }
        addView(modelChipsHolder)

        // Añadir modelo personalizado rápido
        val addCustomRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(4f))
        }
        customModelField = EditText(activity).apply {
            hint = "Añadir ID de modelo manualmente…"
            textSize = 13f
            setTextColor(p.text)
            setHintTextColor(p.muted)
            typeface = NexoUi.regular(activity)
            isSingleLine = true
            minHeight = dp(42f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            typeface = NexoUi.mono(activity)
            background = ThemeManager.rounded(activity, com.nexo.browser.NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CONTROL)
        }
        addCustomRow.addView(customModelField, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8f)
        })
        addCustomRow.addView(NexoUi.chip(activity, "Añadir", false) {
            val id = customModelField.text.toString().trim()
            if (id.isEmpty()) return@chip
            if (id !in availableModels) availableModels = listOf(id) + availableModels
            if (selectedModels.size < NexoAiBenchmark.MAX_MODELS) {
                selectedModels.add(id)
            } else {
                Toastish.show(activity, "Máximo ${NexoAiBenchmark.MAX_MODELS} modelos por comparación.")
            }
            customModelField.setText("")
            renderModelChips()
        })
        addView(addCustomRow)

        // Prompt de prueba
        addView(NexoUi.section(activity, "Prompt de prueba"))
        promptField = EditText(activity).apply {
            hint = "Escribe exactamente el mismo prompt para todos los modelos…"
            textSize = 14f
            setTextColor(p.text)
            setHintTextColor(p.muted)
            typeface = NexoUi.regular(activity)
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = ThemeManager.rounded(activity, com.nexo.browser.NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CONTROL)
        }
        NexoAiBenchmark.currentRun()?.prompt?.takeIf { it.isNotBlank() }?.let { promptField.setText(it) }
        addView(promptField, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        })

        // Razonamiento (solo se aplica a modelos compatibles)
        addView(NexoUi.section(activity, "Razonamiento"))
        reasoningRowHolder = LinearLayout(activity).apply { orientation = VERTICAL }
        addView(reasoningRowHolder)

        // Opción explícita para incluir contexto de la página (desactivada por defecto)
        val contextCard = NexoUi.card(activity, paddingDp = 4f)
        contextCard.addView(NexoUi.switchRow(activity, "Incluir la página actual", null, includePageContext) { includePageContext = it })
        addView(contextCard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
            bottomMargin = dp(8f)
        })

        // Botones de ejecución / cancelación
        runBtnHolder = LinearLayout(activity).apply { orientation = VERTICAL }
        addView(runBtnHolder)
        addView(quotaNote)

        renderProviderRow()
        renderModelChips()
        renderReasoningRow()
        renderActionButtons(NexoAiBenchmark.currentRun())
        renderRunResults(NexoAiBenchmark.currentRun())
    }

    fun attach() {
        NexoAiBenchmark.observe(benchObserver)
    }

    fun detach() {
        NexoAiBenchmark.unobserve(benchObserver)
    }

    fun refreshConfig() {
        settings = NexoAiConfig.read(activity)
        renderProviderRow()
    }

    private fun initialModelsFor(s: NexoAiConfig.Settings): List<String> {
        val list = LinkedHashSet<String>()
        val configured = s.effectiveModel()
        if (configured.isNotBlank()) list += configured
        list.addAll(NexoAiClient.suggestedModels(s.provider))
        return list.toList()
    }

    private fun renderProviderRow() {
        providerRowHolder.removeAllViews()
        val activeLabel = NexoAiConfig.PROVIDERS.firstOrNull { it.first == selectedProvider }?.second ?: selectedProvider
        val chips = NexoAiConfig.PROVIDERS.map { (id, label) ->
            NexoUi.chip(activity, label, selectedProvider == id) {
                if (selectedProvider == id) return@chip
                selectedProvider = id
                val nextSettings = settings.copy(provider = id)
                availableModels = initialModelsFor(nextSettings)
                selectedModels.clear()
                selectedModels.addAll(availableModels.take(2))
                catalogNote = "Mostrando modelos sugeridos para $label. Toca «Cargar modelos» para consultar la API."
                catalogNoteView.text = catalogNote
                renderProviderRow()
                renderModelChips()
            }
        }
        providerRowHolder.addView(NexoUi.chipRow(activity, chips, sidePaddingDp = 0f))
        val keyPresent = selectedProvider == "custom" || NexoAiConfig.hasApiKey(activity)
        if (!keyPresent) {
            providerRowHolder.addView(NexoUi.note(activity, "Configura tu clave API de $activeLabel en la pestaña Ajustes para ejecutar pruebas."))
        }
    }

    private fun renderModelChips() {
        modelChipsHolder.removeAllViews()
        selectedCountBadge.text = "${selectedModels.size}/${NexoAiBenchmark.MAX_MODELS}"
        val rows = availableModels.take(20).chunked(3)
        for (chunk in rows) {
            val row = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(2f), 0, dp(2f))
            }
            val inner = LinearLayout(activity).apply { orientation = HORIZONTAL }
            for (model in chunk) {
                val chosen = model in selectedModels
                val chip = NexoUi.chip(activity, model, chosen) {
                    if (chosen) {
                        selectedModels.remove(model)
                    } else if (selectedModels.size < NexoAiBenchmark.MAX_MODELS) {
                        selectedModels.add(model)
                    } else {
                        Toastish.show(activity, "Máximo ${NexoAiBenchmark.MAX_MODELS} modelos por comparación.")
                    }
                    renderModelChips()
                }
                inner.addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6f)
                })
            }
            row.addView(inner)
            modelChipsHolder.addView(row)
        }
    }

    private fun renderReasoningRow() {
        reasoningRowHolder.removeAllViews()
        val chips = NexoAiClient.REASONING_EFFORTS.map { (value, label) ->
            NexoUi.chip(activity, label, reasoningEffort == value) {
                reasoningEffort = value
                renderReasoningRow()
            }
        }
        reasoningRowHolder.addView(NexoUi.chipRow(activity, chips, sidePaddingDp = 0f))
    }

    private fun renderActionButtons(run: NexoAiBenchmark.BenchmarkRun?) {
        runBtnHolder.removeAllViews()
        val running = run?.isRunning == true
        if (running) {
            runBtnHolder.addView(NexoUi.button(activity, "Detener comparación", danger = true) {
                NexoAiBenchmark.cancelAll()
                Toastish.show(activity, "Comparación detenida.")
            }, NexoUi.buttonParams(activity, 4f))
        } else {
            runBtnHolder.addView(NexoUi.button(activity, "Comparar ${selectedModels.size} modelos", primary = true) {
                launchComparison()
            }, NexoUi.buttonParams(activity, 4f))
            if (run != null) {
                runBtnHolder.addView(NexoUi.button(activity, "Repetir (mediana)", primary = false) {
                    promptField.setText(run.prompt)
                    launchComparison()
                }, NexoUi.buttonParams(activity, 6f))
            }
        }
    }

    private fun fetchModelsFromApi() {
        if (loadingCatalog) return
        loadingCatalog = true
        catalogNoteView.text = "Consultando modelos disponibles en la API…"
        val reqSettings = settings.copy(provider = selectedProvider)
        val key = NexoAiConfig.apiKey(activity)
        catalogIo.execute {
            val result = NexoAiClient.fetchAvailableModels(reqSettings, key)
            post {
                loadingCatalog = false
                if (activity.isFinishing || activity.isDestroyed) return@post
                availableModels = result.models
                catalogNote = result.note ?: if (result.fromApi) "Modelos cargados desde la API." else "Usando modelos sugeridos."
                catalogNoteView.text = catalogNote
                if (selectedModels.none { it in availableModels }) {
                    selectedModels.clear()
                    selectedModels.addAll(availableModels.take(2))
                }
                renderModelChips()
            }
        }
    }

    private fun launchComparison() {
        val currentSettings = NexoAiConfig.read(activity).copy(provider = selectedProvider)
        val key = NexoAiConfig.apiKey(activity)
        val models = selectedModels.toList()
        val prompt = promptField.text.toString().trim()

        val selErr = NexoAiBenchmark.validateSelection(models, prompt)
        if (selErr != null) {
            Toastish.show(activity, selErr)
            return
        }
        val cfgErr = NexoAiConfig.validate(currentSettings.copy(model = models.first()), key)
        if (cfgErr != null) {
            Toastish.show(activity, cfgErr)
            return
        }

        if (includePageContext) {
            NexoAiPageAgent.captureActiveSnapshot(
                context = activity,
                maxChars = currentSettings.maxContextChars,
                allowPrivate = currentSettings.allowPrivateTabs
            ) { snap, err ->
                if (err != null) {
                    Toastish.show(activity, err)
                    return@captureActiveSnapshot
                }
                val ctxText = snap?.toContextText("page").orEmpty()
                startBenchmarkRun(currentSettings, key, models, prompt, ctxText)
            }
        } else {
            startBenchmarkRun(currentSettings, key, models, prompt, "")
        }
    }

    private fun startBenchmarkRun(
        runSettings: NexoAiConfig.Settings,
        apiKey: String,
        models: List<String>,
        prompt: String,
        contextText: String
    ) {
        val started = NexoAiBenchmark.startComparison(
            baseSettings = runSettings,
            apiKey = apiKey,
            models = models,
            prompt = prompt,
            optionalContext = contextText,
            reasoningEffort = reasoningEffort,
            preferStreaming = true
        )
        started.onFailure { Toastish.show(activity, it.message ?: "No se pudo iniciar la comparación.") }
        started.onSuccess { onRequestScrollDown() }
    }

    /**
     * Resultados estilo Linear / Vercel Speed Insights: una tarjeta waterfall por modelo con barra
     * bicolor proporcional (TTFT + generación) y cifras tabulares alineadas a la derecha.
     */
    private fun renderRunResults(run: NexoAiBenchmark.BenchmarkRun?) {
        resultsContainer.removeAllViews()
        if (run == null) return

        val headerRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), 0, dp(10f))
        }
        headerRow.addView(TextView(activity).apply {
            text = "Comparativa · ${run.entries.size} modelos"
            textSize = 16f
            letterSpacing = -0.025f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
            isAccessibilityHeading = true
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Honesto: solo velocidad, nunca calidad.
        run.fastestCompletedModel?.let { fastest ->
            headerRow.addView(NexoUi.statusPill(activity, "Más rápido · ${fastest.substringAfterLast('/')}", NexoUi.Tone.SUCCESS))
        }
        resultsContainer.addView(headerRow)

        val scaleMs = run.entries.mapNotNull { it.metrics?.totalDurationMs }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        for (entry in run.entries) {
            resultsContainer.addView(buildModelResultCard(entry, isFastest = entry.model == run.fastestCompletedModel, scaleMs = scaleMs))
        }
    }

    /** Barra waterfall: pista tonal + TTFT (tinta) + generación (acento/éxito); discontinua si falló. */
    private class WaterfallBar(context: android.content.Context) : View(context) {
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val first = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val gen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val dash = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
        }
        private val r = android.graphics.RectF()
        var ttftFraction = 0f
        var totalFraction = 0f
        var failed = false

        fun colors(trackColor: Int, ttftColor: Int, genColor: Int, failColor: Int) {
            track.color = trackColor; first.color = ttftColor; gen.color = genColor; dash.color = failColor
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val h = height.toFloat()
            val rad = h / 2f
            r.set(0f, 0f, width.toFloat(), h)
            if (failed) {
                r.inset(dash.strokeWidth, dash.strokeWidth)
                canvas.drawRoundRect(r, rad, rad, dash)
                return
            }
            canvas.drawRoundRect(r, rad, rad, track)
            val total = width * totalFraction.coerceIn(0f, 1f)
            val ttft = width * ttftFraction.coerceIn(0f, totalFraction.coerceIn(0f, 1f))
            if (total <= 0f) return
            canvas.save()
            r.set(0f, 0f, total, h)
            val clip = android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) }
            canvas.clipPath(clip)
            canvas.drawRect(0f, 0f, total, h, gen)
            if (ttft > 0f) canvas.drawRect(0f, 0f, ttft, h, first)
            canvas.restore()
        }
    }

    private fun buildModelResultCard(entry: NexoAiBenchmark.ModelRunResult, isFastest: Boolean, scaleMs: Long): View {
        val card = LinearLayout(activity).apply {
            orientation = VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
            background = ThemeManager.rounded(activity, com.nexo.browser.NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
            com.nexo.browser.NexoGlass.paperShadow(this, p)
        }
        val m = entry.metrics
        val failed = entry.status == NexoAiBenchmark.EntryStatus.ERROR

        // Fila 1: modelo · tok/s · total (tabular, alineado a la derecha).
        val topRow = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(TextView(activity).apply {
            text = entry.model.substringAfterLast('/')
            textSize = 15.5f
            typeface = NexoUi.medium(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        fun mono(textValue: String, size: Float, color: Int) = TextView(activity).apply {
            text = textValue
            textSize = size
            typeface = NexoUi.mono(activity)
            fontFeatureSettings = "tnum"
            setTextColor(color)
            gravity = Gravity.END
        }
        when {
            failed -> topRow.addView(NexoUi.statusPill(activity, entry.httpStatus?.let { "HTTP $it" } ?: "Error", NexoUi.Tone.DANGER))
            m != null -> {
                val secondary = when {
                    !m.streamed -> "Sin SSE"
                    m.tokensPerSecond != null -> String.format(Locale.US, "%.0f tok/s", m.tokensPerSecond)
                    else -> ""
                }
                if (secondary.isNotBlank()) topRow.addView(mono(secondary, 12f, p.muted), LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10f) })
                topRow.addView(mono(NexoAiModels.AiMetrics.formatDuration(m.totalDurationMs), 15.5f, p.text).apply { typeface = NexoUi.mono(activity) })
            }
            else -> topRow.addView(TextView(activity).apply {
                text = when (entry.status) {
                    NexoAiBenchmark.EntryStatus.PENDING -> "En cola"
                    NexoAiBenchmark.EntryStatus.STREAMING -> "Recibiendo…"
                    NexoAiBenchmark.EntryStatus.CANCELLED -> "Cancelado"
                    else -> "Esperando…"
                }
                textSize = 13f
                typeface = NexoUi.regular(activity)
                setTextColor(p.muted)
            })
        }
        card.addView(topRow)

        // Fila 2: barra waterfall bicolor proporcional al modelo más lento.
        val bar = WaterfallBar(activity).apply {
            val genColor = when {
                isFastest -> p.success.color
                m?.streamed == false -> p.muted
                p.isMonochrome -> ColorUtils.setAlphaComponent(p.text, 150)
                else -> p.accentSwatch
            }
            colors(p.surfaceContainerHighest, p.text, genColor, p.danger)
            this.failed = failed
            if (m != null) {
                totalFraction = m.totalDurationMs.toFloat() / scaleMs
                ttftFraction = if (m.streamed && m.timeToFirstTokenMs != null) m.timeToFirstTokenMs.toFloat() / scaleMs else 0f
            }
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6f)).apply { topMargin = dp(10f); bottomMargin = dp(8f) })

        // Fila 3: desglose TTFT · Gen a la izquierda, tokens de salida a la derecha.
        if (m != null) {
            val breakdown = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val left = buildList {
                if (m.streamed && m.timeToFirstTokenMs != null) add("TTFT ${NexoAiModels.AiMetrics.formatDuration(m.timeToFirstTokenMs)}")
                else add("Respuesta completa")
                m.generationDurationMs?.let { add("Gen ${NexoAiModels.AiMetrics.formatDuration(it)}") }
                if (!m.streamed) add("Sin streaming")
                if (entry.repeatCount >= 2 && entry.medianDurationMs != null) add("med. ${NexoAiModels.AiMetrics.formatDuration(entry.medianDurationMs!!)} ×${entry.repeatCount}")
            }.joinToString(" · ")
            breakdown.addView(mono(left, 11.5f, p.muted).apply { gravity = Gravity.START; maxLines = 2 },
                LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            entry.usage?.completionTokens?.let { breakdown.addView(mono("$it tok", 11.5f, p.muted)) }
            card.addView(breakdown)
        }
        card.contentDescription = buildString {
            append(entry.model)
            m?.let { append(", total ${NexoAiModels.AiMetrics.formatDuration(it.totalDurationMs)}") }
            if (isFastest) append(", el más rápido")
            if (failed) append(", error")
        }

        if (!entry.errorMessage.isNullOrBlank()) {
            card.addView(TextView(activity).apply {
                text = entry.errorMessage
                textSize = 13.5f
                typeface = NexoUi.regular(activity)
                setTextColor(p.text)
                setPadding(0, dp(4f), 0, dp(4f))
            })
        }

        if (entry.text.isNotBlank()) {
            val isExpanded = entry.requestId in expandedEntries
            val bodyView = TextView(activity).apply {
                text = entry.text
                textSize = 14f
                typeface = NexoUi.regular(activity)
                setTextColor(p.muted)
                setTextIsSelectable(true)
                maxLines = if (isExpanded) Int.MAX_VALUE else 3
                ellipsize = if (isExpanded) null else TextUtils.TruncateAt.END
                setLineSpacing(dp(3f).toFloat(), 1f)
                setPadding(0, dp(6f), 0, dp(6f))
            }
            card.addView(bodyView)

            val actionsRow = LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (entry.text.length > 180 || entry.text.count { it == '\n' } >= 3) {
                actionsRow.addView(NexoUi.chip(activity, if (isExpanded) "Contraer" else "Ver completa", false) {
                    if (isExpanded) expandedEntries.remove(entry.requestId) else expandedEntries.add(entry.requestId)
                    renderRunResults(NexoAiBenchmark.currentRun())
                }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6f)
                })
            }
            actionsRow.addView(NexoUi.chip(activity, "Copiar", false) {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Nexo AI Benchmark", entry.text))
                Toastish.show(activity, "Respuesta de ${entry.model} copiada.")
            })
            card.addView(actionsRow)
        } else if (entry.status == NexoAiBenchmark.EntryStatus.RUNNING || entry.status == NexoAiBenchmark.EntryStatus.PENDING || entry.status == NexoAiBenchmark.EntryStatus.STREAMING) {
            card.addView(NexoUi.chip(activity, "Cancelar este modelo", false) {
                NexoAiBenchmark.cancelModel(entry.requestId)
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4f)
            })
        }

        return card.apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8f)
            }
        }
    }
}
