package com.nexo.browser.ai

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.nexo.browser.CaptionProtocol
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.TabManager
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel adaptable de **Nexo AI 5.1**:
 *
 * Arquitectura y experiencia:
 * - **Conversaciones persistentes** gobernadas por [NexoAiController] + [NexoAiStore]:
 *   minimizar el panel **jamás cancela** consultas ni borra el historial; la UI solo se desuscribe
 *   y al volver a abrir restaura exactamente el hilo, streaming en curso o respuesta completada.
 * - **Tres estados suaves**: Minimizado (píldora flotante in-app) → Panel parcial (66 %) → Pantalla expandida (94 %),
 *   cambiables por botón o gesto vertical sin reconstruir el diálogo ni interrumpir peticiones.
 * - **Tres secciones limpias**: **Chat**, **Comparar** (laboratorio multimodelo [NexoAiBenchmarkView])
 *   y **Ajustes** (proveedores, modelo, clave en Keystore y privacidad).
 * - **Seguridad del agente de página**: cada consulta contextual se vincula a `(tabId, url, documentGeneration)`
 *   para impedir que una acción propuesta se ejecute sobre otra página tras navegar.
 */
object NexoAiSheet {

    enum class Tab { CHAT, BENCHMARK, SETTINGS }

    private data class QuickAction(
        val icon: Int,
        val title: String,
        val subtitle: String,
        val prompt: String,
        val logLabel: String,
        val forceScope: String? = null
    )

    private val QUICK_ACTIONS = listOf(
        QuickAction(
            icon = R.drawable.ic_menu_reader,
            title = "Resumir página",
            subtitle = "Ideas clave en viñetas",
            prompt = "Resume esta página en viñetas claras con las ideas principales.",
            logLabel = "resumir"
        ),
        QuickAction(
            icon = R.drawable.ic_menu_find,
            title = "Explicar selección",
            subtitle = "Aclara el texto elegido",
            prompt = "Explica de forma clara y sencilla el texto seleccionado.",
            logLabel = "explicar_seleccion",
            forceScope = "selection"
        ),
        QuickAction(
            icon = R.drawable.ic_menu_translate,
            title = "Traducir",
            subtitle = "Contenido al español",
            prompt = "Traduce el contenido relevante al español conservando el sentido.",
            logLabel = "traducir_ai"
        ),
        QuickAction(
            icon = R.drawable.ic_menu_file,
            title = "Extraer datos",
            subtitle = "Tablas, precios y fechas",
            prompt = "Extrae los datos estructurados, precios, fechas o tablas clave en formato ordenado.",
            logLabel = "extraer_datos"
        ),
        QuickAction(
            icon = R.drawable.ic_menu_link,
            title = "Enlaces clave",
            subtitle = "Destinos principales",
            prompt = "Identifica los enlaces más importantes de esta página y explica para qué sirve cada uno.",
            logLabel = "enlaces_clave"
        ),
        QuickAction(
            icon = R.drawable.ic_menu_workspace,
            title = "Comparar pestañas",
            subtitle = "Agrupa por tema",
            prompt = "Compara las pestañas abiertas, agrúpalas por tema y sugiere próximos pasos.",
            logLabel = "comparar_pestanas",
            forceScope = "tabs"
        )
    )

    fun show(
        activity: Activity,
        initialTab: Tab = Tab.CHAT,
        initialExpanded: Boolean = false
    ) {
        NexoAiController.initialize(activity)
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }

        var activeTab = initialTab
        var scope = "page" // "page" | "selection" | "tabs" | "free"
        var reasoningEffort: String? = null
        var isExpanded = initialExpanded
        var lastRenderedKey = ""

        lateinit var sheetHandle: NexoSheets.AdaptiveSheetHandle
        lateinit var bodyScroll: ScrollView

        val pageHost = runCatching { android.net.Uri.parse(TabManager.activeTab()?.url.orEmpty()).host }
            .getOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()

        fun scrollToBottom() {
            bodyScroll.post { bodyScroll.fullScroll(View.FOCUS_DOWN) }
        }

        // ==================== CABECERA COMPACTA + PESTAÑAS ====================
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(2f), dp(8f), dp(8f))
        }
        val brandBadge = FrameLayout(activity).apply {
            background = ThemeManager.rounded(
                activity,
                p.primary.container,
                14f,
                ColorUtils.setAlphaComponent(p.accent, 80)
            )
        }
        brandBadge.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_nexo_ai_spark)
            imageTintList = ColorStateList.valueOf(p.primary.onContainer)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER))

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(6f), 0)
        }
        val headerTitle = TextView(activity).apply {
            text = "Nexo AI"
            textSize = 17.5f
            letterSpacing = -0.01f
            typeface = NexoUi.bold(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val modelPill = TextView(activity).apply {
            textSize = 11.5f
            typeface = NexoUi.medium(activity)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
        }
        titleCol.addView(headerTitle)
        titleCol.addView(modelPill)

        val expandBtn = ImageButton(activity).apply {
            setImageResource(if (isExpanded) R.drawable.ic_ai_collapse else R.drawable.ic_ai_expand)
            contentDescription = if (isExpanded) "Reducir panel de Nexo AI" else "Expandir Nexo AI a pantalla completa"
            ThemeManager.styleIconButton(activity, this)
        }
        val minimizeBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_chevron_down)
            contentDescription = "Minimizar Nexo AI sin cancelar consultas"
            ThemeManager.styleIconButton(activity, this)
            setOnClickListener { sheetHandle.dismissAnimated() }
        }

        headerRow.addView(brandBadge, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(expandBtn, LinearLayout.LayoutParams(dp(44f), dp(48f)))
        headerRow.addView(minimizeBtn, LinearLayout.LayoutParams(dp(44f), dp(48f)))
        header.addView(headerRow)

        // Selector segmentado de 3 secciones: Chat | Comparar | Ajustes
        val tabBarTrack = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
            background = ThemeManager.rounded(
                activity,
                if (p.isAmoled) 0xFF121620.toInt() else if (p.isDark) p.surfaceContainerHigh else p.surfaceContainer,
                16f,
                ThemeManager.hairline(p)
            )
        }
        val tabBarWrap = FrameLayout(activity).apply {
            setPadding(dp(16f), 0, dp(16f), dp(10f))
            addView(tabBarTrack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        header.addView(tabBarWrap)
        header.addView(NexoUi.divider(activity, 0f))

        // ==================== CUERPO MULTISECCIÓN ====================
        val rootBody = FrameLayout(activity)
        val chatContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(12f))
        }
        val benchmarkView = NexoAiBenchmarkView(activity) { scrollToBottom() }
        val settingsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(18f))
            visibility = View.GONE
        }
        rootBody.addView(chatContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rootBody.addView(benchmarkView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rootBody.addView(settingsContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Carril de hilos persistentes (Nueva + historial reciente)
        val threadRailWrap = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8f))
        }
        val threadRail = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        threadRailWrap.addView(threadRail)
        chatContainer.addView(threadRailWrap)

        // Tarjeta de bienvenida / acciones rápidas (solo cuando el hilo actual está vacío)
        val emptyStateBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chatContainer.addView(emptyStateBox)

        // Banner contextual compacto si faltan credenciales o consentimiento
        val setupBanner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = ThemeManager.rounded(
                activity,
                if (p.isDark) ColorUtils.setAlphaComponent(p.accent, 24) else p.accentSoft,
                16f,
                ColorUtils.setAlphaComponent(p.accent, 80)
            )
            visibility = View.GONE
        }
        chatContainer.addView(setupBanner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) })

        // Hilo de mensajes vivo
        val messagesBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chatContainer.addView(messagesBox)

        // ==================== PIE FIJO (COMPOSITOR DEL CHAT) ====================
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(6f), dp(14f), dp(8f))
            background = GradientDrawable().apply {
                setColor(if (p.isAmoled) 0xFF0C0E13.toInt() else if (p.isDark) p.surfaceContainer else p.surfaceContainerLow)
            }
        }
        footer.addView(NexoUi.divider(activity, 0f))

        val composerMetaRow = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(6f), 0, dp(6f))
        }
        val composerMetaInner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        composerMetaRow.addView(composerMetaInner)
        footer.addView(composerMetaRow)

        val composerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(4f), dp(6f), dp(4f))
            background = ThemeManager.rounded(
                activity,
                if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest,
                22f,
                ThemeManager.hairline(p)
            )
        }
        val promptField = EditText(activity).apply {
            hint = "Pregunta sobre esta página o conversa…"
            textSize = 14.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            background = null
            maxLines = 4
            minHeight = dp(42f)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(4f), dp(8f), dp(6f), dp(8f))
        }
        val sendOrStopBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_ai_send)
            contentDescription = "Enviar consulta a Nexo AI"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        composerRow.addView(promptField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composerRow.addView(sendOrStopBtn, LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { marginStart = dp(6f) })
        footer.addView(composerRow)

        fun updateSendButton(generating: Boolean) {
            if (generating) {
                sendOrStopBtn.setImageResource(R.drawable.ic_ai_stop)
                sendOrStopBtn.contentDescription = "Detener generación actual"
                sendOrStopBtn.background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.error.container, 20f), 20f)
                sendOrStopBtn.imageTintList = ColorStateList.valueOf(p.error.onContainer)
            } else {
                sendOrStopBtn.setImageResource(R.drawable.ic_ai_send)
                sendOrStopBtn.contentDescription = "Enviar consulta a Nexo AI"
                sendOrStopBtn.background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.accent, 20f), 20f)
                sendOrStopBtn.imageTintList = ColorStateList.valueOf(p.onAccent)
            }
        }

        fun refreshHeader() {
            val s = NexoAiConfig.read(activity)
            val keyOk = NexoAiConfig.validate(s, NexoAiConfig.apiKey(activity)) == null
            val hostLabel = pageHost.ifBlank { "Inicio" }
            val modelLabel = when {
                !s.enabled -> "Desactivado · toca Ajustes"
                !keyOk -> "Configura tu clave API"
                else -> s.effectiveModel().ifBlank { s.provider }
            }
            modelPill.text = "$hostLabel · $modelLabel"
            modelPill.setTextColor(if (s.enabled && keyOk) p.muted else p.accent)
        }

        fun renderScopeAndReasoning() {
            composerMetaInner.removeAllViews()
            val scopes = listOf(
                "page" to "Página",
                "selection" to "Selección",
                "tabs" to "Pestañas",
                "free" to "Sin contexto"
            )
            for ((id, label) in scopes) {
                composerMetaInner.addView(
                    NexoUi.chip(activity, label, scope == id) {
                        scope = id
                        renderScopeAndReasoning()
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(6f)
                    }
                )
            }
            val s = NexoAiConfig.read(activity)
            if (NexoAiClient.supportsReasoningEffort(s.provider, s.effectiveModel())) {
                val nextLabel = when (reasoningEffort) {
                    "low" -> "Raz: Bajo"
                    "medium" -> "Raz: Medio"
                    "high" -> "Raz: Alto"
                    else -> "Raz: Auto"
                }
                composerMetaInner.addView(
                    NexoUi.chip(activity, nextLabel, reasoningEffort != null) {
                        reasoningEffort = when (reasoningEffort) {
                            null -> "low"
                            "low" -> "medium"
                            "medium" -> "high"
                            else -> null
                        }
                        renderScopeAndReasoning()
                    }
                )
            }
        }

        fun refreshSettingsForm() {
            settingsContainer.removeAllViews()
            buildSettingsForm(
                activity = activity,
                container = settingsContainer,
                includeEnableToggle = true,
                includeLogRow = true,
                onSaved = {
                    refreshHeader()
                    renderScopeAndReasoning()
                    benchmarkView.refreshConfig()
                    activeTab = Tab.CHAT
                    // Actualizar visibilidad de pestañas
                    chatContainer.visibility = View.VISIBLE
                    benchmarkView.visibility = View.GONE
                    settingsContainer.visibility = View.GONE
                    footer.visibility = View.VISIBLE
                }
            )
        }

        fun renderTabs() {
            tabBarTrack.removeAllViews()
            val items = listOf(
                Tab.CHAT to "Chat",
                Tab.BENCHMARK to "Comparar",
                Tab.SETTINGS to "Ajustes"
            )
            val activeBg = if (p.isDark) p.surfaceContainerHighest else p.surfaceContainerLowest
            for ((tab, label) in items) {
                val selected = activeTab == tab
                val btn = TextView(activity).apply {
                    text = label
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    typeface = if (selected) NexoUi.semiBold(activity) else NexoUi.medium(activity)
                    setTextColor(if (selected) p.text else p.muted)
                    minHeight = dp(34f)
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                    background = if (selected) {
                        ThemeManager.rounded(activity, activeBg, 12f, ThemeManager.hairline(p))
                    } else {
                        ThemeManager.ripple(activity, null, 12f)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        if (activeTab == tab) return@setOnClickListener
                        activeTab = tab
                        chatContainer.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
                        benchmarkView.visibility = if (tab == Tab.BENCHMARK) View.VISIBLE else View.GONE
                        settingsContainer.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
                        footer.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
                        if (tab == Tab.SETTINGS) refreshSettingsForm()
                        if (tab == Tab.BENCHMARK) benchmarkView.refreshConfig()
                        renderTabs()
                        bodyScroll.post { bodyScroll.scrollTo(0, 0) }
                    }
                }
                tabBarTrack.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            chatContainer.visibility = if (activeTab == Tab.CHAT) View.VISIBLE else View.GONE
            benchmarkView.visibility = if (activeTab == Tab.BENCHMARK) View.VISIBLE else View.GONE
            settingsContainer.visibility = if (activeTab == Tab.SETTINGS) View.VISIBLE else View.GONE
            footer.visibility = if (activeTab == Tab.CHAT) View.VISIBLE else View.GONE
        }

        // Ejecutar consulta contextual o libre delegando al controlador persistente
        fun dispatchPrompt(userTask: String, logLabel: String, forceScope: String? = null) {
            val s = NexoAiConfig.read(activity)
            val apiKey = NexoAiConfig.apiKey(activity)
            if (!s.enabled) {
                Toastish.show(activity, "Activa Nexo AI en Ajustes para enviar consultas.")
                activeTab = Tab.SETTINGS
                refreshSettingsForm()
                renderTabs()
                return
            }
            val cfgErr = NexoAiConfig.validate(s, apiKey)
            if (cfgErr != null) {
                Toastish.show(activity, cfgErr)
                activeTab = Tab.SETTINGS
                refreshSettingsForm()
                renderTabs()
                return
            }
            if (!s.consentAccepted) {
                showConsentSheet(activity) {
                    NexoAiConfig.setConsentAccepted(activity, true)
                    refreshHeader()
                    dispatchPrompt(userTask, logLabel, forceScope)
                }
                return
            }

            val chosenScope = forceScope ?: scope
            val currentBinding = NexoAiPageAgent.currentActiveBinding()

            when (chosenScope) {
                "free" -> {
                    NexoAiController.sendPrompt(
                        settings = s,
                        apiKey = apiKey,
                        userTask = userTask,
                        untrustedContext = "",
                        scope = "free",
                        pageBinding = null,
                        reasoningEffort = reasoningEffort,
                        preferStreaming = true,
                        logLabel = logLabel
                    )
                    scrollToBottom()
                }
                "tabs" -> {
                    NexoAiPageAgent.captureOpenTabsContext(activity, s.maxContextChars, s.allowPrivateTabs) { ctx ->
                        NexoAiController.sendPrompt(
                            settings = s,
                            apiKey = apiKey,
                            userTask = userTask,
                            untrustedContext = ctx,
                            scope = "tabs",
                            pageBinding = currentBinding,
                            reasoningEffort = reasoningEffort,
                            preferStreaming = true,
                            logLabel = logLabel
                        )
                        scrollToBottom()
                    }
                }
                else -> {
                    NexoAiPageAgent.captureActiveSnapshot(activity, s.maxContextChars, s.allowPrivateTabs) { snap, err ->
                        if (err != null) {
                            if (chosenScope == "page" && pageHost.isBlank()) {
                                // En página de inicio, permite conversar sin contexto de página
                                NexoAiController.sendPrompt(
                                    settings = s,
                                    apiKey = apiKey,
                                    userTask = userTask,
                                    untrustedContext = "",
                                    scope = "free",
                                    pageBinding = null,
                                    reasoningEffort = reasoningEffort,
                                    preferStreaming = true,
                                    logLabel = logLabel
                                )
                                scrollToBottom()
                                return@captureActiveSnapshot
                            }
                            Toastish.show(activity, err)
                            return@captureActiveSnapshot
                        }
                        if (chosenScope == "selection" && snap?.selection.isNullOrBlank()) {
                            Toastish.show(activity, "Selecciona texto en la página antes de usar «Selección».")
                            return@captureActiveSnapshot
                        }
                        NexoAiController.sendPrompt(
                            settings = s,
                            apiKey = apiKey,
                            userTask = userTask,
                            untrustedContext = snap?.toContextText(chosenScope).orEmpty(),
                            scope = chosenScope,
                            pageBinding = snap?.binding ?: currentBinding,
                            reasoningEffort = reasoningEffort,
                            preferStreaming = true,
                            logLabel = logLabel
                        )
                        scrollToBottom()
                    }
                }
            }
        }

        fun renderEmptyState() {
            emptyStateBox.removeAllViews()
            val heroCard = NexoUi.card(activity, paddingDp = 14f)
            heroCard.addView(TextView(activity).apply {
                text = if (pageHost.isBlank()) "¿En qué te ayudo hoy?" else "Analiza $pageHost o pregunta lo que necesites"
                textSize = 15.5f
                typeface = NexoUi.bold(activity)
                setTextColor(p.text)
            })
            heroCard.addView(TextView(activity).apply {
                text = "Puedes minimizar este panel en cualquier momento: tus consultas siguen activas y el historial se conserva."
                textSize = 12f
                typeface = NexoUi.regular(activity)
                setTextColor(p.muted)
                setPadding(0, dp(4f), 0, 0)
                setLineSpacing(dp(2f).toFloat(), 1f)
            })
            emptyStateBox.addView(heroCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) })

            val tileBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
            val tileStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
            QUICK_ACTIONS.chunked(2).forEach { pair ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { idx, qa ->
                    val cell = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                        background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, tileBg, 16f, tileStroke), 16f)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { dispatchPrompt(qa.prompt, qa.logLabel, qa.forceScope) }
                        addView(ImageView(activity).apply {
                            setImageResource(qa.icon)
                            imageTintList = ColorStateList.valueOf(if (p.isDark) p.onAccentSoft else p.accent)
                            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
                            background = ThemeManager.rounded(activity, p.accentSoft, 10f)
                        }, LinearLayout.LayoutParams(dp(32f), dp(32f)).apply { bottomMargin = dp(6f) })
                        addView(TextView(activity).apply {
                            text = qa.title
                            textSize = 13f
                            typeface = NexoUi.semiBold(activity)
                            setTextColor(p.text)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        addView(TextView(activity).apply {
                            text = qa.subtitle
                            textSize = 11.5f
                            typeface = NexoUi.regular(activity)
                            setTextColor(p.muted)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(0, dp(2f), 0, 0)
                        })
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx > 0) marginStart = dp(8f)
                        bottomMargin = dp(8f)
                    })
                }
                emptyStateBox.addView(row)
            }
        }

        fun renderSetupBanner() {
            val s = NexoAiConfig.read(activity)
            val keyOk = NexoAiConfig.validate(s, NexoAiConfig.apiKey(activity)) == null
            setupBanner.removeAllViews()
            if (s.enabled && keyOk) {
                setupBanner.visibility = View.GONE
                return
            }
            setupBanner.visibility = View.VISIBLE
            setupBanner.addView(TextView(activity).apply {
                text = if (!s.enabled) "Nexo AI está desactivado (opt-in)" else "Falta configurar tu proveedor o clave API"
                textSize = 13f
                typeface = NexoUi.semiBold(activity)
                setTextColor(p.text)
            })
            setupBanner.addView(TextView(activity).apply {
                text = "Tus claves se cifran en Android Keystore y nunca se envía ninguna página automáticamente."
                textSize = 11.5f
                typeface = NexoUi.regular(activity)
                setTextColor(p.muted)
                setPadding(0, dp(2f), 0, dp(8f))
            })
            setupBanner.addView(NexoUi.chip(activity, "Configurar proveedor y clave", true) {
                activeTab = Tab.SETTINGS
                refreshSettingsForm()
                renderTabs()
            })
        }

        fun buildMessageBubble(msg: NexoAiModels.AiMessage, convId: String): View {
            if (msg.role == NexoAiModels.Role.USER) {
                val wrap = FrameLayout(activity).apply {
                    setPadding(dp(36f), dp(2f), 0, dp(8f))
                }
                val bubble = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                    background = ThemeManager.rounded(activity, p.primary.container, 18f)
                }
                val scopeBadge = when (msg.scope) {
                    "selection" -> "Selección"
                    "tabs" -> "Pestañas"
                    "free" -> "Libre"
                    else -> msg.pageBinding?.host?.takeIf { it.isNotBlank() } ?: "Página"
                }
                bubble.addView(TextView(activity).apply {
                    text = scopeBadge
                    textSize = 10.5f
                    typeface = NexoUi.semiBold(activity)
                    setTextColor(ColorUtils.setAlphaComponent(p.primary.onContainer, 190))
                })
                bubble.addView(TextView(activity).apply {
                    text = msg.text
                    textSize = 14f
                    typeface = NexoUi.medium(activity)
                    setTextColor(p.primary.onContainer)
                    setPadding(0, dp(2f), 0, 0)
                })
                wrap.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END))
                return wrap
            }

            // Burbuja del asistente
            val cardBg = if (p.isAmoled) 0xFF111520.toInt() else if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
            val cardStroke = when (msg.status) {
                NexoAiModels.MessageStatus.ERROR, NexoAiModels.MessageStatus.INTERRUPTED -> ColorUtils.setAlphaComponent(p.danger, 120)
                NexoAiModels.MessageStatus.SENDING, NexoAiModels.MessageStatus.STREAMING -> ColorUtils.setAlphaComponent(p.accent, 130)
                else -> ThemeManager.hairline(p)
            }
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(10f))
                background = ThemeManager.rounded(activity, cardBg, 18f, cardStroke)
            }

            // Cabecera de respuesta: modelo + estado
            val topMeta = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topMeta.addView(TextView(activity).apply {
                text = (msg.model ?: "Nexo AI").substringAfterLast('/')
                textSize = 11.5f
                typeface = NexoUi.semiBold(activity)
                setTextColor(p.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val statusLabel = when (msg.status) {
                NexoAiModels.MessageStatus.SENDING -> "Consultando…" to NexoUi.Tone.INFO
                NexoAiModels.MessageStatus.STREAMING -> "Respondiendo en vivo…" to NexoUi.Tone.ACCENT
                NexoAiModels.MessageStatus.CANCELLED -> "Detenida" to NexoUi.Tone.WARNING
                NexoAiModels.MessageStatus.INTERRUPTED -> "Interrumpida" to NexoUi.Tone.WARNING
                NexoAiModels.MessageStatus.ERROR -> "Error" to NexoUi.Tone.DANGER
                NexoAiModels.MessageStatus.COMPLETED -> null
            }
            statusLabel?.let { (lbl, tone) ->
                topMeta.addView(NexoUi.statusPill(activity, lbl, tone))
            }
            card.addView(topMeta)

            if (msg.text.isNotBlank()) {
                card.addView(TextView(activity).apply {
                    text = msg.text
                    textSize = 14.5f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.text)
                    setTextIsSelectable(true)
                    setLineSpacing(dp(4f).toFloat(), 1f)
                    setPadding(0, dp(6f), 0, dp(4f))
                })
            } else if (msg.status == NexoAiModels.MessageStatus.SENDING) {
                card.addView(TextView(activity).apply {
                    text = "Analizando tu solicitud… Puedes minimizar el panel y volver después."
                    textSize = 13f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.muted)
                    setPadding(0, dp(6f), 0, dp(4f))
                })
            }

            if (!msg.errorMessage.isNullOrBlank()) {
                card.addView(TextView(activity).apply {
                    text = msg.errorMessage
                    textSize = 12.5f
                    typeface = NexoUi.medium(activity)
                    setTextColor(if (msg.status == NexoAiModels.MessageStatus.ERROR) p.danger else p.muted)
                    setPadding(0, dp(4f), 0, dp(4f))
                })
            }

            // Métricas reales medidas (sin datos inventados)
            val metricsLine = msg.metrics?.formatCompact(msg.usage).orEmpty()
                .ifBlank { msg.usage?.format().orEmpty() }
            if (metricsLine.isNotBlank()) {
                card.addView(TextView(activity).apply {
                    text = metricsLine
                    textSize = 10.5f
                    typeface = NexoUi.mono(activity)
                    setTextColor(p.muted)
                    setPadding(0, dp(4f), 0, dp(4f))
                })
            }

            // Acciones propuestas vinculadas de forma segura al PageBinding original
            if (msg.actions.isNotEmpty()) {
                val actionsWrap = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4f), 0, dp(2f))
                }
                val currentBinding = NexoAiPageAgent.currentActiveBinding()
                val samePage = NexoAiPageAgent.isBindingMatchingCurrent(msg.pageBinding, currentBinding)
                if (!samePage && msg.pageBinding != null && msg.actions.any { it.type != "open_link" }) {
                    actionsWrap.addView(TextView(activity).apply {
                        text = "Las acciones de página pertenecen a ${msg.pageBinding.host.ifBlank { "otra página" }} y no se ejecutarán en la pestaña actual."
                        textSize = 11f
                        typeface = NexoUi.regular(activity)
                        setTextColor(p.muted)
                        setPadding(0, 0, 0, dp(4f))
                    })
                }
                val actionChips = msg.actions.map { act ->
                    NexoUi.chip(activity, act.label, false) {
                        NexoAiPageAgent.confirmAndExecuteAction(activity, act, msg.pageBinding)
                    }
                }
                actionsWrap.addView(NexoUi.chipRow(activity, actionChips, sidePaddingDp = 0f))
                card.addView(actionsWrap)
            }

            // Barra de acciones de la respuesta (Detener / Copiar / Reintentar)
            val controls = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4f), 0, 0)
            }
            if (msg.isInFlight && msg.requestId != null) {
                controls.addView(NexoUi.chip(activity, "Detener", false) {
                    NexoAiController.cancelRequest(msg.requestId)
                })
            } else {
                if (msg.text.isNotBlank()) {
                    controls.addView(NexoUi.chip(activity, "Copiar", false) {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Nexo AI", msg.text))
                        Toastish.show(activity, "Respuesta copiada.")
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(6f)
                    })
                }
                if ((msg.status == NexoAiModels.MessageStatus.ERROR ||
                        msg.status == NexoAiModels.MessageStatus.INTERRUPTED ||
                        msg.status == NexoAiModels.MessageStatus.CANCELLED) && !msg.retryPrompt.isNullOrBlank()
                ) {
                    controls.addView(NexoUi.chip(activity, "Reintentar", true) {
                        NexoAiController.selectConversation(convId)
                        dispatchPrompt(msg.retryPrompt, "reintentar", msg.scope)
                    })
                }
            }
            if (controls.childCount > 0) card.addView(controls)

            return card.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10f) }
            }
        }

        fun renderFromSnapshot(snap: NexoAiModels.ControllerSnapshot) {
            if (activity.isFinishing || activity.isDestroyed) return

            // Carril de conversaciones persistentes
            threadRail.removeAllViews()
            threadRail.addView(NexoUi.chip(activity, "+ Nueva", false) {
                NexoAiController.newConversation(pageHost)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            })
            for (conv in snap.conversations.take(8)) {
                val selected = conv.id == snap.activeConversationId
                val prefix = if (conv.hasInFlight) "● " else ""
                val title = (prefix + conv.title).take(22)
                val chip = NexoUi.chip(activity, title, selected) {
                    NexoAiController.selectConversation(conv.id)
                }
                chip.setOnLongClickListener {
                    if (snap.conversations.size > 1 || conv.messages.isNotEmpty()) {
                        NexoAiController.deleteConversation(conv.id)
                        Toastish.show(activity, "Conversación eliminada.")
                    }
                    true
                }
                threadRail.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6f)
                })
            }

            val conv = snap.activeConversation
            val generating = snap.activeRequestForCurrentConversation != null
            updateSendButton(generating)

            val msgs = conv?.messages.orEmpty()
            threadRailWrap.visibility = if (snap.conversations.size > 1 || msgs.isNotEmpty()) View.VISIBLE else View.GONE
            emptyStateBox.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
            if (msgs.isEmpty() && emptyStateBox.childCount == 0) renderEmptyState()

            val renderKey = "${conv?.id}|${msgs.size}|${msgs.lastOrNull()?.status}|${msgs.lastOrNull()?.text?.length}"
            messagesBox.removeAllViews()
            if (conv != null) {
                for (m in msgs) {
                    messagesBox.addView(buildMessageBubble(m, conv.id))
                }
            }
            if (renderKey != lastRenderedKey && msgs.isNotEmpty()) {
                lastRenderedKey = renderKey
                if (activeTab == Tab.CHAT) scrollToBottom()
            }
        }

        // Acciones de envío / cancelación explícita
        fun submitOrStop() {
            val activeReq = NexoAiController.snapshot().activeRequestForCurrentConversation
            if (activeReq != null) {
                NexoAiController.cancelRequest(activeReq.requestId)
                Toastish.show(activity, "Generación detenida.")
                return
            }
            val q = promptField.text.toString().trim()
            if (q.isEmpty()) {
                Toastish.show(activity, "Escribe una pregunta o elige una acción rápida.")
                return
            }
            promptField.setText("")
            dispatchPrompt(q, "consulta")
        }

        sendOrStopBtn.setOnClickListener { submitOrStop() }
        promptField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitOrStop()
                true
            } else false
        }

        modelPill.setOnClickListener {
            activeTab = Tab.SETTINGS
            refreshSettingsForm()
            renderTabs()
        }

        fun applyExpandedState(expanded: Boolean, animated: Boolean) {
            isExpanded = expanded
            sheetHandle.setExpanded(expanded, animated)
            expandBtn.setImageResource(if (expanded) R.drawable.ic_ai_collapse else R.drawable.ic_ai_expand)
            expandBtn.contentDescription = if (expanded) "Reducir panel de Nexo AI" else "Expandir Nexo AI a pantalla completa"
            NexoAiController.setPanelState(if (expanded) NexoAiModels.PanelState.EXPANDED else NexoAiModels.PanelState.PARTIAL)
        }

        expandBtn.setOnClickListener { applyExpandedState(!isExpanded, animated = true) }

        refreshHeader()
        renderScopeAndReasoning()
        renderSetupBanner()
        renderEmptyState()
        if (activeTab == Tab.SETTINGS) refreshSettingsForm()
        renderTabs()

        sheetHandle = NexoSheets.showAdaptiveFixed(
            activity = activity,
            headerView = header,
            bodyContent = rootBody,
            footerView = footer,
            initialExpanded = isExpanded,
            onExpandGesture = {
                if (!isExpanded) applyExpandedState(true, animated = true)
            }
        )
        bodyScroll = sheetHandle.scrollView
        @Suppress("DEPRECATION")
        sheetHandle.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val observer: (NexoAiModels.ControllerSnapshot) -> Unit = { snap ->
            renderFromSnapshot(snap)
        }
        NexoAiController.setPanelState(if (isExpanded) NexoAiModels.PanelState.EXPANDED else NexoAiModels.PanelState.PARTIAL)
        NexoAiController.observe(observer)
        benchmarkView.attach()

        // CRÍTICO: Minimizar/cerrar el panel JAMÁS cancela consultas ni borra conversaciones.
        sheetHandle.dialog.setOnDismissListener {
            NexoAiController.unobserve(observer)
            benchmarkView.detach()
            NexoAiController.setPanelState(NexoAiModels.PanelState.MINIMIZED)
        }
    }

    /** Hoja de consentimiento explícito antes de enviar contenido a un proveedor externo. */
    private fun showConsentSheet(activity: Activity, onAccept: () -> Unit) {
        lateinit var dialog: Dialog
        val body = NexoUi.column(activity)
        body.addView(NexoUi.note(
            activity,
            "Nexo AI solo envía contenido saneado de la página cuando tú pulsas una acción o envías un mensaje. " +
                "Se excluyen contraseñas, campos sensibles y cookies. Tus claves API se guardan cifradas en Android Keystore."
        ))
        body.addView(NexoUi.button(activity, "Aceptar y continuar", primary = true) {
            dialog.dismiss()
            onAccept()
        }, NexoUi.buttonParams(activity, 12f))
        body.addView(NexoUi.button(activity, "Cancelar", primary = false) {
            dialog.dismiss()
        }, NexoUi.buttonParams(activity, 6f))
        dialog = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, "Privacidad de Nexo AI", "Consentimiento explícito"), body)
        )
    }

    /** Vista de ajustes dedicada (también accesible desde fuera del asistente). */
    fun showSettingsSheet(activity: Activity, onSaved: () -> Unit = {}) {
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        buildSettingsForm(
            activity = activity,
            container = body,
            includeEnableToggle = false,
            includeLogRow = true,
            onSaved = {
                sheet.dismiss()
                onSaved()
            }
        )
        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Configurar Nexo AI", "Claves cifradas en Keystore"),
                body
            )
        )
        @Suppress("DEPRECATION")
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun buildSettingsForm(
        activity: Activity,
        container: LinearLayout,
        includeEnableToggle: Boolean,
        includeLogRow: Boolean,
        onSaved: () -> Unit
    ) {
        val current = NexoAiConfig.read(activity)
        var enabled = current.enabled
        var provider = current.provider
        var allowPrivate = current.allowPrivateTabs
        var maxContext = current.maxContextChars
        var timeoutSec = current.timeoutSeconds

        if (includeEnableToggle) {
            val masterCard = NexoUi.card(activity, paddingDp = 4f)
            masterCard.addView(NexoUi.switchRow(
                activity,
                "Activar Nexo AI",
                "Solo envía contexto saneado cuando tú pulses una acción.",
                enabled
            ) { enabled = it })
            container.addView(masterCard)
        }

        val providerHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val suggestedHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (modelView, modelField) = NexoUi.field(
            activity,
            "Modelo activo",
            CaptionProtocol.AI_MODELS[provider] ?: "Nombre del modelo",
            current.model
        )
        val (endpointView, endpointField) = NexoUi.field(
            activity,
            "Endpoint API (solo HTTPS o localhost)",
            "https://servidor/v1",
            current.customEndpoint
        )
        val (keyView, keyField) = NexoUi.field(
            activity,
            "Clave API (cifrada en Android Keystore)",
            "Pega tu clave",
            NexoAiConfig.apiKey(activity)
        )
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        fun renderSuggestedModels() {
            suggestedHolder.removeAllViews()
            val list = NexoAiClient.suggestedModels(provider)
            val currentText = modelField.text.toString().trim()
            suggestedHolder.addView(NexoUi.chipRow(activity, list.map { m ->
                NexoUi.chip(activity, m.substringAfterLast('/'), currentText == m) {
                    modelField.setText(m)
                    renderSuggestedModels()
                }
            }, sidePaddingDp = 0f))
        }

        fun renderProviders() {
            providerHolder.removeAllViews()
            providerHolder.addView(NexoUi.chipRow(activity, NexoAiConfig.PROVIDERS.map { (id, label) ->
                NexoUi.chip(activity, label, provider == id) {
                    provider = id
                    endpointView.visibility = if (provider == "custom") View.VISIBLE else View.GONE
                    modelField.hint = CaptionProtocol.AI_MODELS[provider] ?: "Nombre del modelo"
                    renderProviders()
                    renderSuggestedModels()
                }
            }, sidePaddingDp = 0f))
            endpointView.visibility = if (provider == "custom") View.VISIBLE else View.GONE
        }
        renderProviders()
        renderSuggestedModels()

        container.addView(NexoUi.section(activity, "Proveedor de IA"))
        container.addView(providerHolder)
        container.addView(modelView)
        container.addView(suggestedHolder)
        container.addView(endpointView)
        container.addView(keyView)

        container.addView(NexoUi.section(activity, "Presupuesto de contexto"))
        val ctxHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderCtx() {
            ctxHolder.removeAllViews()
            ctxHolder.addView(NexoUi.chipRow(activity, listOf(4000 to "4 KB", 8000 to "8 KB", 12000 to "12 KB", 16000 to "16 KB").map { (v, label) ->
                NexoUi.chip(activity, label, maxContext == v) { maxContext = v; renderCtx() }
            }, sidePaddingDp = 0f))
        }
        renderCtx()
        container.addView(ctxHolder)

        container.addView(NexoUi.section(activity, "Tiempo máximo de espera"))
        val timeoutHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderTimeouts() {
            timeoutHolder.removeAllViews()
            timeoutHolder.addView(NexoUi.chipRow(activity, listOf(20 to "20 s", 35 to "35 s", 60 to "60 s", 90 to "90 s").map { (v, label) ->
                NexoUi.chip(activity, label, timeoutSec == v) { timeoutSec = v; renderTimeouts() }
            }, sidePaddingDp = 0f))
        }
        renderTimeouts()
        container.addView(timeoutHolder)

        val privacyCard = NexoUi.card(activity, paddingDp = 4f)
        privacyCard.addView(NexoUi.switchRow(
            activity,
            "Permitir en pestañas privadas",
            "Desactivado por defecto para máxima privacidad.",
            allowPrivate
        ) { allowPrivate = it })
        if (includeLogRow) {
            privacyCard.addView(NexoUi.insetDivider(activity))
            privacyCard.addView(NexoUi.actionRow(
                activity,
                R.drawable.ic_menu_history,
                "Registro local de acciones",
                "Historial saneado sin claves ni contraseñas"
            ) {
                showLogSheet(activity)
            })
        }
        container.addView(privacyCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = NexoUi.dp(activity, 12f) })

        val errorView = TextView(activity).apply {
            textSize = 12.5f
            typeface = NexoUi.medium(activity)
            setTextColor(ThemeManager.palette(activity).danger)
            visibility = View.GONE
            setPadding(NexoUi.dp(activity, 4f), NexoUi.dp(activity, 8f), NexoUi.dp(activity, 4f), 0)
        }
        container.addView(errorView)

        container.addView(NexoUi.button(activity, "Guardar configuración", primary = true) {
            val next = current.copy(
                enabled = enabled,
                provider = provider,
                model = modelField.text.toString().trim(),
                customEndpoint = if (provider == "custom") endpointField.text.toString().trim() else "",
                timeoutSeconds = timeoutSec,
                maxContextChars = maxContext,
                allowPrivateTabs = allowPrivate
            )
            val key = keyField.text.toString().trim()
            if (next.enabled) {
                val err = NexoAiConfig.validate(next, key)
                if (err != null) {
                    errorView.text = err
                    errorView.visibility = View.VISIBLE
                    return@button
                }
            }
            NexoAiConfig.save(activity, next)
            NexoAiConfig.setApiKey(activity, key)
            Toastish.show(activity, "Configuración de Nexo AI guardada.")
            onSaved()
        }, NexoUi.buttonParams(activity, 14f))
    }

    private fun showLogSheet(activity: Activity) {
        val entries = NexoAiConfig.readLog(activity)
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        val text = if (entries.isEmpty()) {
            "Sin acciones registradas todavía."
        } else {
            entries.reversed().joinToString("\n") { e ->
                "[${fmt.format(Date(e.timestampMs))}] ${e.action} (${e.host.ifBlank { "general" }}) · ${e.status}"
            }
        }
        lateinit var logDialog: Dialog
        val body = NexoUi.column(activity)
        body.addView(NexoUi.note(activity, text))
        if (entries.isNotEmpty()) {
            body.addView(NexoUi.button(activity, "Limpiar registro", danger = true) {
                NexoAiConfig.clearLog(activity)
                logDialog.dismiss()
                Toastish.show(activity, "Registro limpiado.")
            }, NexoUi.buttonParams(activity, 10f))
        }
        body.addView(NexoUi.button(activity, "Cerrar", primary = false) {
            logDialog.dismiss()
        }, NexoUi.buttonParams(activity, 6f))
        logDialog = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, "Registro local de Nexo AI", "Saneado sin claves ni contraseñas"), body)
        )
    }
}
