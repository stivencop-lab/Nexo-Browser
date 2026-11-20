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
        setPadding(dp(16f), dp(10f), dp(16f), dp(18f))

        // Banner honesto de cuota/costos y privacidad
        val noticeBg = if (p.isDark) ColorUtils.setAlphaComponent(p.accent, 24) else p.accentSoft
        val noticeCard = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            background = ThemeManager.rounded(activity, noticeBg, 14f, ColorUtils.setAlphaComponent(p.accent, 70))
        }
        noticeCard.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_ai_benchmark)
            imageTintList = ColorStateList.valueOf(if (p.isDark) p.onAccentSoft else p.accent)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(10f) })
        noticeCard.addView(TextView(activity).apply {
            text = "Cada comparación envía 1 solicitud por modelo (máx. 2 en paralelo) y consume cuota/créditos de tu API. Nunca se envía la página sin tu permiso."
            textSize = 11.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.text)
            setLineSpacing(dp(2f).toFloat(), 1f)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(noticeCard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

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
            text = "Modelos a comparar (2 a 5)"
            textSize = 13f
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
            background = ThemeManager.rounded(activity, if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest, 12f, ThemeManager.hairline(p))
        }
        addCustomRow.addView(customModelField, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8f)
        })
        addCustomRow.addView(NexoUi.chip(activity, "+ Añadir", false) {
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
            background = ThemeManager.rounded(activity, if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest, 14f, ThemeManager.hairline(p))
        }
        NexoAiBenchmark.currentRun()?.prompt?.takeIf { it.isNotBlank() }?.let { promptField.setText(it) }
        addView(promptField, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        })

        // Razonamiento (solo se aplica a modelos compatibles)
        addView(NexoUi.section(activity, "Nivel de razonamiento (si el modelo lo soporta)"))
        reasoningRowHolder = LinearLayout(activity).apply { orientation = VERTICAL }
        addView(reasoningRowHolder)

        // Opción explícita para incluir contexto de la página (desactivada por defecto)
        val contextCard = NexoUi.card(activity, paddingDp = 4f)
        contextCard.addView(NexoUi.switchRow(
            activity,
            "Incluir contexto de la página actual",
            "Desactivado por defecto. Actívalo solo si quieres evaluar los modelos sobre esta web.",
            includePageContext
        ) { includePageContext = it })
        addView(contextCard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
            bottomMargin = dp(8f)
        })

        // Botones de ejecución / cancelación
        runBtnHolder = LinearLayout(activity).apply { orientation = VERTICAL }
        addView(runBtnHolder)

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
            runBtnHolder.addView(NexoUi.button(activity, "Ejecutar comparación (${selectedModels.size} modelos)", primary = true) {
                launchComparison()
            }, NexoUi.buttonParams(activity, 4f))
            if (run != null) {
                runBtnHolder.addView(NexoUi.button(activity, "Repetir misma prueba (calcular mediana)", primary = false) {
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

    private fun renderRunResults(run: NexoAiBenchmark.BenchmarkRun?) {
        resultsContainer.removeAllViews()
        if (run == null) return

        // Cabecera resumen + ganador de velocidad (honesto: solo velocidad, nunca inteligencia)
        val headerRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(8f))
        }
        headerRow.addView(TextView(activity).apply {
            text = "Resultados de la comparación"
            textSize = 14.5f
            typeface = NexoUi.bold(activity)
            setTextColor(p.text)
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        run.fastestCompletedModel?.let { fastest ->
            headerRow.addView(NexoUi.statusPill(activity, "Más rápido: ${fastest.substringAfterLast('/')}", NexoUi.Tone.SUCCESS))
        }
        resultsContainer.addView(headerRow)

        // Tabla compacta de métricas reales
        val tableCard = NexoUi.card(activity, paddingDp = 10f)
        val tableHeader = makeTableRow(
            col1 = "Modelo",
            col2 = "Total",
            col3 = "1.er tok",
            col4 = "Salida",
            col5 = "Estado",
            isHeader = true
        )
        tableCard.addView(tableHeader)
        tableCard.addView(NexoUi.divider(activity, 6f))

        run.entries.forEachIndexed { idx, entry ->
            if (idx > 0) tableCard.addView(NexoUi.divider(activity, 4f))
            val totalStr = entry.metrics?.let { NexoAiModels.AiMetrics.formatDuration(it.totalDurationMs) } ?: "—"
            val ttftStr = if (entry.metrics?.streamed == true && entry.metrics.timeToFirstTokenMs != null) {
                NexoAiModels.AiMetrics.formatDuration(entry.metrics.timeToFirstTokenMs)
            } else "—"
            val outTokStr = entry.usage?.completionTokens?.toString() ?: "—"
            val stateStr = when (entry.status) {
                NexoAiBenchmark.EntryStatus.PENDING -> "En cola"
                NexoAiBenchmark.EntryStatus.RUNNING -> "Esperando…"
                NexoAiBenchmark.EntryStatus.STREAMING -> "Recibiendo…"
                NexoAiBenchmark.EntryStatus.COMPLETED -> "OK"
                NexoAiBenchmark.EntryStatus.CANCELLED -> "Cancelado"
                NexoAiBenchmark.EntryStatus.ERROR -> entry.httpStatus?.let { "HTTP $it" } ?: "Error"
            }
            val shortName = entry.model.substringAfterLast('/')
            val rowText = if (entry.repeatCount >= 2 && entry.medianDurationMs != null) {
                "$shortName\nmed. ${NexoAiModels.AiMetrics.formatDuration(entry.medianDurationMs!!)} (${entry.repeatCount}x)"
            } else shortName
            tableCard.addView(makeTableRow(rowText, totalStr, ttftStr, outTokStr, stateStr, isHeader = false, status = entry.status))
        }
        resultsContainer.addView(tableCard, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

        // Tarjetas desplegables por modelo con métricas detalladas y texto completo
        for (entry in run.entries) {
            resultsContainer.addView(buildModelResultCard(entry, isFastest = entry.model == run.fastestCompletedModel))
        }
    }

    private fun makeTableRow(
        col1: String,
        col2: String,
        col3: String,
        col4: String,
        col5: String,
        isHeader: Boolean,
        status: NexoAiBenchmark.EntryStatus? = null
    ): LinearLayout = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2f), dp(4f), dp(2f), dp(4f))

        fun cell(text: String, weight: Float, alignEnd: Boolean, color: Int? = null): TextView = TextView(activity).apply {
            this.text = text
            textSize = if (isHeader) 11f else 11.5f
            typeface = if (isHeader) NexoUi.semiBold(activity) else NexoUi.medium(activity)
            setTextColor(color ?: if (isHeader) p.muted else p.text)
            gravity = if (alignEnd) Gravity.END else Gravity.START
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        }

        val statusColor = when (status) {
            NexoAiBenchmark.EntryStatus.COMPLETED -> p.success.color
            NexoAiBenchmark.EntryStatus.ERROR -> p.danger
            NexoAiBenchmark.EntryStatus.RUNNING, NexoAiBenchmark.EntryStatus.STREAMING -> p.accent
            else -> p.muted
        }

        addView(cell(col1, 2.2f, alignEnd = false))
        addView(cell(col2, 1.0f, alignEnd = true))
        addView(cell(col3, 1.0f, alignEnd = true))
        addView(cell(col4, 0.9f, alignEnd = true))
        addView(cell(col5, 1.1f, alignEnd = true, color = if (isHeader) p.muted else statusColor))
    }

    private fun buildModelResultCard(entry: NexoAiBenchmark.ModelRunResult, isFastest: Boolean): View {
        val card = NexoUi.card(activity, paddingDp = 12f)
        val topRow = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(activity).apply {
            text = entry.model
            textSize = 13.5f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })

        val (pillLabel, pillTone) = when (entry.status) {
            NexoAiBenchmark.EntryStatus.PENDING -> "En cola" to NexoUi.Tone.NEUTRAL
            NexoAiBenchmark.EntryStatus.RUNNING -> "Consultando…" to NexoUi.Tone.INFO
            NexoAiBenchmark.EntryStatus.STREAMING -> "En vivo…" to NexoUi.Tone.ACCENT
            NexoAiBenchmark.EntryStatus.COMPLETED -> (if (isFastest) "Más rápido" else "Correcto") to NexoUi.Tone.SUCCESS
            NexoAiBenchmark.EntryStatus.CANCELLED -> "Cancelado" to NexoUi.Tone.WARNING
            NexoAiBenchmark.EntryStatus.ERROR -> "Error" to NexoUi.Tone.DANGER
        }
        topRow.addView(NexoUi.statusPill(activity, pillLabel, pillTone))
        card.addView(topRow)

        // Línea de métricas reales detalladas (sin inventar datos)
        val details = buildList {
            entry.metrics?.let { m ->
                add("Total: ${NexoAiModels.AiMetrics.formatDuration(m.totalDurationMs)}")
                m.networkLatencyMs?.let { add("Red: ${NexoAiModels.AiMetrics.formatDuration(it)}") }
                if (m.streamed && m.timeToFirstTokenMs != null) {
                    add("1.er token: ${NexoAiModels.AiMetrics.formatDuration(m.timeToFirstTokenMs)}")
                } else if (!m.streamed) {
                    add("Sin streaming")
                }
                m.generationDurationMs?.let { add("Gen: ${NexoAiModels.AiMetrics.formatDuration(it)}") }
                m.tokensPerSecond?.let { add(String.format(Locale.US, "%.1f tok/s", it)) }
            }
            entry.usage?.let { u ->
                val fmt = u.format()
                if (fmt.isNotBlank()) add(fmt)
            }
            if (!entry.reasoningEffort.isNullOrBlank()) {
                add("Razonamiento: ${entry.reasoningEffort}")
            } else if (!entry.reasoningSupported && !reasoningEffort.isNullOrBlank()) {
                add("Sin parámetro de razonamiento")
            }
            if (entry.repeatCount >= 2 && entry.medianDurationMs != null) {
                add("Mediana (${entry.repeatCount}): ${NexoAiModels.AiMetrics.formatDuration(entry.medianDurationMs!!)}")
            }
        }
        if (details.isNotEmpty()) {
            card.addView(TextView(activity).apply {
                text = details.joinToString(" · ")
                textSize = 11f
                typeface = NexoUi.mono(activity)
                setTextColor(p.muted)
                setPadding(0, dp(4f), 0, dp(4f))
            })
        }

        if (!entry.errorMessage.isNullOrBlank()) {
            card.addView(TextView(activity).apply {
                text = entry.errorMessage
                textSize = 12.5f
                typeface = NexoUi.medium(activity)
                setTextColor(p.danger)
                setPadding(0, dp(4f), 0, dp(4f))
            })
        }

        if (entry.text.isNotBlank()) {
            val isExpanded = entry.requestId in expandedEntries
            val bodyView = TextView(activity).apply {
                text = entry.text
                textSize = 13.5f
                typeface = NexoUi.regular(activity)
                setTextColor(p.text)
                setTextIsSelectable(true)
                maxLines = if (isExpanded) Int.MAX_VALUE else 4
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
