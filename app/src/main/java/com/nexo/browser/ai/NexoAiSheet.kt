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
 * Panel adaptable de **Nexo AI 5.1** con la presentación de Nexo UI 7.0 «Liquid Craft»
 * (hoja de 34dp sobre la web desenfocada y escalada al 96 %, pestañas con línea deslizante,
 * chat editorial sin burbujas y compositor en píldora flotante):
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
            setPadding(dp(20f), dp(2f), dp(10f), dp(6f))
        }
        // Emblema Aperture desnudo (sin caja ni borde de color); respira durante el streaming.
        val headerAperture = com.nexo.browser.NexoApertureDrawable(activity, 22f).apply { setColor(p.text) }
        val brandBadge = ImageView(activity).apply {
            setImageDrawable(headerAperture)
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10f), 0, dp(6f), 0)
        }
        val headerTitle = TextView(activity).apply {
            text = "Nexo"
            textSize = 17f
            letterSpacing = -0.025f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val modelPill = TextView(activity).apply {
            textSize = 13f
            typeface = NexoUi.regular(activity)
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

        headerRow.addView(brandBadge, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(expandBtn, LinearLayout.LayoutParams(dp(44f), dp(48f)))
        headerRow.addView(minimizeBtn, LinearLayout.LayoutParams(dp(44f), dp(48f)))
        header.addView(headerRow)

        // Pestañas con línea inferior deslizante: Chat | Comparar | Ajustes (sin cajas grises).
        var onTabPicked: (Tab) -> Unit = {}
        val tabBar = NexoUi.UnderlineTabs(activity, listOf(Tab.CHAT to "Chat", Tab.BENCHMARK to "Comparar", Tab.SETTINGS to "Ajustes"), activeTab) { onTabPicked(it) }
        val tabBarWrap = FrameLayout(activity).apply {
            setPadding(dp(20f), 0, dp(20f), 0)
            addView(tabBar.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        header.addView(tabBarWrap)
        header.addView(NexoUi.divider(activity, 0f))

        // ==================== CUERPO MULTISECCIÓN ====================
        val rootBody = FrameLayout(activity)
        val chatContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(14f), dp(20f), dp(12f))
        }
        val benchmarkView = NexoAiBenchmarkView(activity) { scrollToBottom() }
        val settingsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(NexoUi.Tokens.SHEET_PADDING), dp(12f), dp(NexoUi.Tokens.SHEET_PADDING), dp(18f))
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
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
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
            setPadding(dp(NexoUi.Tokens.SHEET_PADDING), dp(6f), dp(NexoUi.Tokens.SHEET_PADDING), dp(8f))
        }
        // Compositor en píldora flotante: [Página ▾] campo [↑].
        val composerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_PILL)
            com.nexo.browser.NexoGlass.paperShadow(this, p)
        }
        val scopeButton = TextView(activity).apply {
            textSize = 13f
            typeface = NexoUi.medium(activity)
            setTextColor(p.text)
            gravity = Gravity.CENTER
            minHeight = dp(48f)
            minWidth = dp(48f)
            setPadding(dp(12f), 0, dp(10f), 0)
            background = ThemeManager.ripple(activity, android.graphics.drawable.InsetDrawable(
                ThemeManager.rounded(activity, if (p.isDark) p.surfaceContainerHighest else p.surfaceContainer, NexoUi.Tokens.RADIUS_PILL), 0, dp(6f), 0, dp(6f)
            ), NexoUi.Tokens.RADIUS_PILL)
            compoundDrawablePadding = dp(4f)
        }
        val promptField = EditText(activity).apply {
            hint = "Preguntar…"
            textSize = 15f
            typeface = NexoUi.regular(activity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            background = null
            maxLines = 5
            minHeight = dp(48f)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(10f), dp(8f), dp(6f), dp(8f))
        }
        val sendOrStopBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_ai_send)
            contentDescription = "Enviar consulta a Nexo AI"
            scaleType = ImageView.ScaleType.CENTER
        }
        composerRow.addView(scopeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)))
        composerRow.addView(promptField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composerRow.addView(sendOrStopBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        footer.addView(composerRow)

        fun updateSendButton(generating: Boolean) {
            if (generating) {
                sendOrStopBtn.setImageResource(R.drawable.ic_ai_stop)
                sendOrStopBtn.contentDescription = "Detener generación actual"
                sendOrStopBtn.background = ThemeManager.ripple(activity, android.graphics.drawable.InsetDrawable(
                    ThemeManager.rounded(activity, p.surfaceContainerHighest, NexoUi.Tokens.RADIUS_PILL), dp(4f)), NexoUi.Tokens.RADIUS_PILL)
                sendOrStopBtn.imageTintList = ColorStateList.valueOf(p.text)
                headerAperture.setBreathing(!NexoUi.reducedMotion(activity))
            } else {
                sendOrStopBtn.setImageResource(R.drawable.ic_ai_send)
                sendOrStopBtn.contentDescription = "Enviar consulta a Nexo AI"
                sendOrStopBtn.background = ThemeManager.ripple(activity, android.graphics.drawable.InsetDrawable(
                    ThemeManager.rounded(activity, p.accent, NexoUi.Tokens.RADIUS_PILL), dp(4f)), NexoUi.Tokens.RADIUS_PILL)
                sendOrStopBtn.imageTintList = ColorStateList.valueOf(p.onAccent)
                headerAperture.setBreathing(false)
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
            modelPill.text = if (s.enabled && keyOk) hostLabel else "$hostLabel · $modelLabel"
            modelPill.setTextColor(if (s.enabled && keyOk) p.muted else p.warning.color)
        }

        // Selector de contexto inline «Página ▾» (y razonamiento si el modelo lo admite).
        fun renderScopeAndReasoning() {
            val scopes = listOf("page" to "Página", "selection" to "Selección", "tabs" to "Pestañas", "free" to "Sin contexto")
            scopeButton.text = scopes.firstOrNull { it.first == scope }?.second ?: "Página"
            scopeButton.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                activity.getDrawable(R.drawable.ic_chevron_down)?.mutate()?.apply {
                    setTint(p.muted); setBounds(0, 0, dp(14f), dp(14f))
                }, null)
            scopeButton.contentDescription = "Contexto: ${scopeButton.text}. Cambiar"
            scopeButton.setOnClickListener { anchor ->
                val menu = android.widget.PopupMenu(activity, anchor)
                scopes.forEachIndexed { i, (_, label) -> menu.menu.add(0, i, i, label).apply { isCheckable = true; isChecked = scopes[i].first == scope } }
                val s = NexoAiConfig.read(activity)
                val reasoning = NexoAiClient.supportsReasoningEffort(s.provider, s.effectiveModel())
                if (reasoning) {
                    listOf(null to "Razonamiento automático", "low" to "Razonamiento bajo", "medium" to "Razonamiento medio", "high" to "Razonamiento alto")
                        .forEachIndexed { i, (_, label) -> menu.menu.add(1, 10 + i, 10 + i, label).apply { isCheckable = true } }
                    menu.menu.setGroupCheckable(1, true, true)
                    menu.menu.findItem(10 + listOf(null, "low", "medium", "high").indexOf(reasoningEffort))?.isChecked = true
                }
                menu.setOnMenuItemClickListener { item ->
                    if (item.itemId < 10) scope = scopes[item.itemId].first
                    else reasoningEffort = listOf(null, "low", "medium", "high")[item.itemId - 10]
                    renderScopeAndReasoning()
                    true
                }
                menu.show()
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
                    tabBar.select(Tab.CHAT, animate = true)
                    // Actualizar visibilidad de pestañas
                    chatContainer.visibility = View.VISIBLE
                    benchmarkView.visibility = View.GONE
                    settingsContainer.visibility = View.GONE
                    footer.visibility = View.VISIBLE
                }
            )
        }

        fun renderTabs() {
            if (tabBar.current != activeTab) tabBar.select(activeTab, animate = true)
            onTabPicked = { tab ->
                if (activeTab != tab) {
                    activeTab = tab
                    if (tab == Tab.SETTINGS) refreshSettingsForm()
                    if (tab == Tab.BENCHMARK) benchmarkView.refreshConfig()
                    renderTabs()
                    bodyScroll.post { bodyScroll.scrollTo(0, 0) }
                }
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
            emptyStateBox.addView(TextView(activity).apply {
                text = if (pageHost.isBlank()) "¿En qué te ayudo?" else "¿Qué quieres saber de $pageHost?"
                textSize = 20f
                letterSpacing = -0.025f
                typeface = NexoUi.semiBold(activity)
                setTextColor(p.text)
                isAccessibilityHeading = true
                setPadding(0, dp(4f), 0, dp(14f))
            })
            // Lista agrupada: iconos lineales desnudos y divisores inset (sin cuadrados de color).
            val group = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
                clipToOutline = true
                com.nexo.browser.NexoGlass.paperShadow(this, p)
            }
            QUICK_ACTIONS.forEachIndexed { index, qa ->
                if (index > 0) group.addView(NexoUi.insetDivider(activity))
                group.addView(NexoUi.actionRow(activity, qa.icon, qa.title) {
                    dispatchPrompt(qa.prompt, qa.logLabel, qa.forceScope)
                })
            }
            emptyStateBox.addView(group, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) })
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
                text = if (!s.enabled) "Nexo AI está desactivado" else "Falta tu proveedor o clave API"
                textSize = 15f
                typeface = NexoUi.medium(activity)
                setTextColor(p.text)
            })
            setupBanner.addView(NexoUi.button(activity, "Configurar", primary = true) {
                activeTab = Tab.SETTINGS
                refreshSettingsForm()
                renderTabs()
            }, NexoUi.buttonParams(activity, 12f))
        }

        // Chat editorial: sin burbujas ni tarjetas contenedoras.
        fun buildMessageBubble(msg: NexoAiModels.AiMessage, convId: String, first: Boolean): View {
            if (msg.role == NexoAiModels.Role.USER) {
                val wrap = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, if (first) dp(2f) else dp(18f), 0, dp(10f))
                }
                if (!first) wrap.addView(NexoUi.divider(activity, 0f), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(0.6f).coerceAtLeast(1)).apply { bottomMargin = dp(18f) })
                val scopeLabel = when (msg.scope) {
                    "selection" -> "Selección"
                    "tabs" -> "Pestañas"
                    "free" -> "Sin contexto"
                    else -> msg.pageBinding?.host?.takeIf { it.isNotBlank() } ?: "Página"
                }
                wrap.addView(TextView(activity).apply {
                    text = "Tú · $scopeLabel"
                    textSize = 12f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.muted)
                })
                wrap.addView(TextView(activity).apply {
                    text = msg.text
                    textSize = 16f
                    letterSpacing = -0.01f
                    typeface = NexoUi.medium(activity)
                    setTextColor(p.text)
                    setTextIsSelectable(true)
                    setPadding(0, dp(4f), 0, 0)
                })
                if (!msg.isInFlight) {
                    wrap.addView(NexoUi.chipRow(activity, listOf(
                        ghostAction(activity, "Editar") {
                            promptField.setText(msg.text)
                            promptField.setSelection(promptField.text.length)
                            promptField.requestFocus()
                        },
                        ghostAction(activity, "Eliminar") {
                            NexoAiHistorySheet.confirm(activity, "¿Eliminar esta pregunta?",
                                "Se borrarán la pregunta y su respuesta de esta conversación.", "Eliminar") {
                                NexoAiController.deleteMessage(convId, msg.id)
                            }
                        }
                    )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) })
                }
                return wrap
            }

            // Respuesta directa sobre el fondo limpio.
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(4f))
            }

            val statusLabel = when (msg.status) {
                NexoAiModels.MessageStatus.SENDING -> "Consultando…" to p.muted
                NexoAiModels.MessageStatus.STREAMING -> "Escribiendo…" to p.muted
                NexoAiModels.MessageStatus.CANCELLED -> "Detenida" to p.warning.color
                NexoAiModels.MessageStatus.INTERRUPTED -> "Interrumpida" to p.warning.color
                NexoAiModels.MessageStatus.ERROR -> "Error" to p.danger
                NexoAiModels.MessageStatus.COMPLETED -> null
            }
            statusLabel?.let { (lbl, color) ->
                card.addView(TextView(activity).apply {
                    text = "${(msg.model ?: "Nexo").substringAfterLast('/')} · $lbl"
                    textSize = 12f
                    typeface = NexoUi.regular(activity)
                    setTextColor(color)
                    setPadding(0, 0, 0, dp(4f))
                })
            }

            if (msg.text.isNotBlank()) {
                card.addView(TextView(activity).apply {
                    text = msg.text
                    textSize = 15.5f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.text)
                    setTextIsSelectable(true)
                    setLineSpacing(0f, 1.45f)
                    setPadding(0, dp(2f), 0, dp(4f))
                })
            } else if (msg.status == NexoAiModels.MessageStatus.SENDING) {
                card.addView(TextView(activity).apply {
                    text = "Analizando…"
                    textSize = 15f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.muted)
                    setPadding(0, dp(2f), 0, dp(4f))
                })
            }

            if (!msg.errorMessage.isNullOrBlank()) {
                card.addView(TextView(activity).apply {
                    text = msg.errorMessage
                    textSize = 13.5f
                    typeface = NexoUi.regular(activity)
                    setTextColor(if (msg.status == NexoAiModels.MessageStatus.ERROR) p.danger else p.muted)
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
                if (actionsWrap.childCount > 0) card.addView(actionsWrap)
            }

            // Acciones fantasma: propuestas de página (Resaltar, Abrir…), Copiar, Reintentar / Detener.
            val ghost = ArrayList<View>()
            if (msg.isInFlight && msg.requestId != null) {
                ghost += ghostAction(activity, "Detener") { NexoAiController.cancelRequest(msg.requestId) }
            } else {
                msg.actions.forEach { act ->
                    ghost += ghostAction(activity, act.label) { NexoAiPageAgent.confirmAndExecuteAction(activity, act, msg.pageBinding) }
                }
                if (msg.text.isNotBlank()) {
                    ghost += ghostAction(activity, "Copiar") {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Nexo AI", msg.text))
                        Toastish.show(activity, "Respuesta copiada.")
                    }
                }
                ghost += ghostAction(activity, "Eliminar") {
                    NexoAiController.deleteMessage(convId, msg.id)
                    Toastish.show(activity, "Respuesta eliminada.")
                }
                if ((msg.status == NexoAiModels.MessageStatus.ERROR ||
                        msg.status == NexoAiModels.MessageStatus.INTERRUPTED ||
                        msg.status == NexoAiModels.MessageStatus.CANCELLED) && !msg.retryPrompt.isNullOrBlank()
                ) {
                    ghost += ghostAction(activity, "Reintentar") {
                        NexoAiController.selectConversation(convId)
                        dispatchPrompt(msg.retryPrompt, "reintentar", msg.scope)
                    }
                }
            }
            if (ghost.isNotEmpty()) card.addView(NexoUi.chipRow(activity, ghost), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) })

            // Telemetría real medida, plegable: «1.84 s · 43 tok/s ▾».
            telemetryChip(activity, msg)?.let { card.addView(it) }

            return card.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }
        }

        fun renderFromSnapshot(snap: NexoAiModels.ControllerSnapshot) {
            if (activity.isFinishing || activity.isDestroyed) return

            // Carril de conversaciones persistentes
            threadRail.removeAllViews()
            threadRail.addView(NexoUi.chip(activity, "Nueva", false) {
                NexoAiController.newConversation(pageHost)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            })
            threadRail.addView(NexoUi.chip(activity, "Historial", false) {
                NexoAiHistorySheet.show(activity)
            }.apply { contentDescription = "Ver, buscar y borrar conversaciones" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6f)
            })
            for (conv in snap.conversations.take(8)) {
                val selected = conv.id == snap.activeConversationId
                val prefix = if (conv.hasInFlight) "● " else ""
                val title = (prefix + conv.title).take(22)
                val chip = NexoUi.chip(activity, title, selected) {
                    NexoAiController.selectConversation(conv.id)
                }
                // Pulsación larga: renombrar o eliminar (con confirmación; antes borraba al instante).
                chip.setOnLongClickListener {
                    if (conv.messages.isNotEmpty()) NexoAiHistorySheet.options(activity, conv)
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
            threadRailWrap.visibility = if (snap.conversations.any { it.messages.isNotEmpty() }) View.VISIBLE else View.GONE
            emptyStateBox.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
            if (msgs.isEmpty() && emptyStateBox.childCount == 0) renderEmptyState()

            val renderKey = "${conv?.id}|${msgs.size}|${msgs.lastOrNull()?.status}|${msgs.lastOrNull()?.text?.length}"
            messagesBox.removeAllViews()
            if (conv != null) {
                msgs.forEachIndexed { i, m ->
                    messagesBox.addView(buildMessageBubble(m, conv.id, first = i == 0))
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
        // La web queda detrás desenfocada (blur-behind de la ventana) y escalada al 96 %.
        val webLayer = activity.findViewById<View>(android.R.id.content)
        webLayer?.let {
            it.pivotX = it.width / 2f
            it.pivotY = it.height * 0.35f
            com.nexo.browser.NexoMotion.spring(it, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 0.96f)
            com.nexo.browser.NexoMotion.spring(it, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 0.96f)
        }
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
            webLayer?.let {
                com.nexo.browser.NexoMotion.spring(it, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_X, 1f)
                com.nexo.browser.NexoMotion.spring(it, androidx.dynamicanimation.animation.DynamicAnimation.SCALE_Y, 1f)
            }
            NexoAiController.unobserve(observer)
            benchmarkView.detach()
            NexoAiController.setPanelState(NexoAiModels.PanelState.MINIMIZED)
        }
    }

    /** Acción fantasma: píldora tonal compacta (48dp táctiles), sin borde ni color de marca. */
    private fun ghostAction(activity: Activity, label: String, onClick: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        val d = { v: Float -> NexoUi.dp(activity, v) }
        return TextView(activity).apply {
            text = label
            textSize = 13f
            typeface = NexoUi.medium(activity)
            setTextColor(p.text)
            gravity = Gravity.CENTER
            minHeight = d(48f)
            minWidth = d(48f)
            setPadding(d(14f), 0, d(14f), 0)
            background = ThemeManager.ripple(activity, android.graphics.drawable.InsetDrawable(
                ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_PILL), 0, d(8f), 0, d(8f)), NexoUi.Tokens.RADIUS_PILL)
            setOnClickListener { onClick() }
        }
    }

    /** Chip de telemetría colapsable en JetBrains Mono tabular; solo métricas medidas de verdad. */
    private fun telemetryChip(activity: Activity, msg: NexoAiModels.AiMessage): View? {
        val m = msg.metrics
        val full = m?.formatCompact(msg.usage).orEmpty().ifBlank { msg.usage?.format().orEmpty() }
        if (full.isBlank()) return null
        val short = if (m != null) listOfNotNull(
            NexoAiModels.AiMetrics.formatDuration(m.totalDurationMs),
            m.tokensPerSecond?.let { String.format(Locale.US, "%.0f tok/s", it) }
        ).joinToString(" · ") else full
        val p = ThemeManager.palette(activity)
        var expanded = false
        return TextView(activity).apply {
            textSize = 12f
            typeface = NexoUi.mono(activity)
            fontFeatureSettings = "tnum"
            setTextColor(p.muted)
            minHeight = NexoUi.dp(activity, 44f)
            gravity = Gravity.CENTER_VERTICAL
            fun bind() {
                text = if (expanded) "$full ▴" else "$short ▾"
                contentDescription = "Telemetría: " + if (expanded) full else short
            }
            bind()
            background = ThemeManager.ripple(activity, null, 12f)
            setOnClickListener { expanded = !expanded; bind() }
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
            NexoSheets.column(activity, NexoSheets.header(activity, "Privacidad de Nexo AI"), body)
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
                NexoSheets.header(activity, "Configurar Nexo AI"),
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
            masterCard.addView(NexoUi.switchRow(activity, "Nexo AI", null, enabled) { enabled = it })
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
            "Clave API",
            "Pega tu clave",
            NexoAiConfig.apiKey(activity)
        )
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyField.typeface = NexoUi.mono(activity)
        // Indicador discreto de almacenamiento seguro junto a la etiqueta.
        ((keyView as LinearLayout).getChildAt(0) as? TextView)?.let { label ->
            val pal = ThemeManager.palette(activity)
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            keyView.removeView(label)
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(activity).apply {
                text = "Android Keystore"
                textSize = 12f
                typeface = NexoUi.medium(activity)
                setTextColor(pal.success.color)
                setCompoundDrawablesRelativeWithIntrinsicBounds(activity.getDrawable(R.drawable.ic_lock)?.mutate()?.apply {
                    setTint(pal.success.color); setBounds(0, 0, NexoUi.dp(activity, 12f), NexoUi.dp(activity, 12f))
                }, null, null, null)
                compoundDrawablePadding = NexoUi.dp(activity, 4f)
            })
            keyView.addView(row, 0)
        }

        fun renderSuggestedModels() {
            suggestedHolder.removeAllViews()
            val list = NexoAiClient.suggestedModels(provider)
            val currentText = modelField.text.toString().trim()
            suggestedHolder.addView(NexoUi.chipRow(activity, list.map { m ->
                NexoUi.chip(activity, m.substringAfterLast('/'), currentText == m) {
                    modelField.setText(m)
                    renderSuggestedModels()
                }.apply { typeface = NexoUi.mono(activity); textSize = 12.5f }
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

        container.addView(NexoUi.section(activity, "Proveedor"))
        container.addView(providerHolder)
        container.addView(modelView)
        container.addView(suggestedHolder)
        container.addView(endpointView)
        container.addView(keyView)

        // «Una API para todo»: esta conexión sirve también a Reader, traducción, subtítulos y PhantomShield.
        val unifiedCard = NexoUi.card(activity, paddingDp = 4f)
        unifiedCard.addView(NexoUi.switchRow(
            activity,
            "Usar esta API para todo",
            "Asistente, Reader, traducción, subtítulos y PhantomShield",
            com.nexo.browser.BrowserPrefs.unifiedAi(activity)
        ) { com.nexo.browser.BrowserPrefs.setUnifiedAi(activity, it) })
        container.addView(unifiedCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = NexoUi.dp(activity, 12f) })

        container.addView(NexoUi.section(activity, "Presupuesto de contexto"))
        val ctxHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderCtx() {
            ctxHolder.removeAllViews()
            ctxHolder.addView(NexoUi.segmented(activity, listOf(4000 to "4 KB", 8000 to "8 KB", 12000 to "12 KB", 16000 to "16 KB"), maxContext, mono = true) {
                maxContext = it; renderCtx()
            })
        }
        renderCtx()
        container.addView(ctxHolder)

        container.addView(NexoUi.section(activity, "Tiempo límite"))
        val timeoutHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderTimeouts() {
            timeoutHolder.removeAllViews()
            timeoutHolder.addView(NexoUi.segmented(activity, listOf(20 to "20 s", 35 to "35 s", 60 to "60 s", 90 to "90 s"), timeoutSec, mono = true) {
                timeoutSec = it; renderTimeouts()
            })
        }
        renderTimeouts()
        container.addView(timeoutHolder)

        val privacyCard = NexoUi.card(activity, paddingDp = 4f)
        privacyCard.addView(NexoUi.switchRow(activity, "Permitir en pestañas privadas", null, allowPrivate) { allowPrivate = it })
        privacyCard.addView(NexoUi.insetDivider(activity))
        privacyCard.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_history,
            "Historial de conversaciones"
        ) {
            NexoAiHistorySheet.show(activity)
        })
        privacyCard.addView(NexoUi.insetDivider(activity))
        privacyCard.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_delete,
            "Borrar historial de conversaciones"
        ) {
            NexoAiHistorySheet.confirm(activity, "¿Borrar todo el historial de Nexo AI?",
                "Se eliminarán todas las conversaciones guardadas en este dispositivo. No se puede deshacer.", "Borrar todo") {
                NexoAiController.deleteAllConversations()
                Toastish.show(activity, "Historial de Nexo AI borrado.")
            }
        })
        if (includeLogRow) {
            privacyCard.addView(NexoUi.insetDivider(activity))
            privacyCard.addView(NexoUi.actionRow(
                activity,
                R.drawable.ic_menu_history,
                "Registro local de acciones"
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

        container.addView(NexoUi.button(activity, "Guardar", primary = true) {
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
            NexoSheets.column(activity, NexoSheets.header(activity, "Registro local de Nexo AI"), body)
        )
    }
}
