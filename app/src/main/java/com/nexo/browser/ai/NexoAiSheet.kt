package com.nexo.browser.ai

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
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
import java.util.concurrent.Executors

/**
 * Panel conversacional nativo de Nexo AI (UI 5.0).
 *
 * Composición:
 * - Cabecera fija con identidad Nexo AI, dominio/modelo activo, acceso a configuración
 *   en vista secundaria integrada y cierre rápido.
 * - Cuerpo desplazable con acciones rápidas en cuadrícula limpia (que se compactan tras
 *   la primera consulta), hilo de mensajes legible, estado de procesamiento y controles contextuales.
 * - Barra inferior fija siempre accesible con selector de alcance, campo de entrada moderno
 *   y botón Enviar que cambia a Detener durante la generación.
 *
 * Conserva consentimiento explícito, saneado local, protección de claves en Android Keystore
 * y permisos por sitio. Nunca envía datos automáticamente al abrir el panel.
 */
object NexoAiSheet {
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val main by lazy { Handler(Looper.getMainLooper()) }

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

    fun show(activity: Activity) {
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        var settings = NexoAiConfig.read(activity)
        var scope = "page" // "page" | "selection" | "tabs"
        var activeCancel: NexoAiClient.CancelHandle? = null
        var requestGen = 0L
        var lastSnapshot: NexoAiPageAgent.PageSnapshot? = null
        var lastPrompt = ""
        var lastLabel = ""
        var showingSettings = false
        var hasMessages = false

        lateinit var sheet: Dialog
        lateinit var bodyScroll: ScrollView

        val pageHost = runCatching { android.net.Uri.parse(TabManager.activeTab()?.url.orEmpty()).host }
            .getOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()

        fun scrollToBottom() {
            bodyScroll.post { bodyScroll.fullScroll(View.FOCUS_DOWN) }
        }

        // ==================== CABECERA FIJA ====================
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(2f), dp(8f), dp(10f))
        }
        val brandBadge = FrameLayout(activity).apply {
            background = ThemeManager.rounded(activity, p.primary.container, 13f)
        }
        val brandIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_nexo_ai_spark)
            imageTintList = ColorStateList.valueOf(p.primary.onContainer)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        brandBadge.addView(brandIcon, FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER))

        val backFromSettingsBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_nav_back)
            contentDescription = "Volver a Nexo AI"
            ThemeManager.styleIconButton(activity, this)
            visibility = View.GONE
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(8f), 0)
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
        val headerSubtitle = TextView(activity).apply {
            textSize = 12f
            typeface = NexoUi.medium(activity)
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1f), 0, 0)
        }
        titleCol.addView(headerTitle)
        titleCol.addView(headerSubtitle)

        val settingsBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_settings)
            contentDescription = "Configurar Nexo AI"
            ThemeManager.styleIconButton(activity, this)
        }
        val closeBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Cerrar Nexo AI"
            ThemeManager.styleIconButton(activity, this)
            setOnClickListener { sheet.dismiss() }
        }

        headerRow.addView(backFromSettingsBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        headerRow.addView(brandBadge, LinearLayout.LayoutParams(dp(38f), dp(38f)))
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(settingsBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        header.addView(headerRow)
        header.addView(NexoUi.divider(activity, 0f))

        fun refreshHeader() {
            val hostLabel = if (pageHost.isBlank()) "Página de inicio" else pageHost
            if (showingSettings) {
                backFromSettingsBtn.visibility = View.VISIBLE
                brandBadge.visibility = View.GONE
                settingsBtn.visibility = View.GONE
                headerTitle.text = "Configuración de Nexo AI"
                headerSubtitle.text = "Proveedor, modelo y claves en Keystore"
                headerSubtitle.setTextColor(p.muted)
            } else {
                backFromSettingsBtn.visibility = View.GONE
                brandBadge.visibility = View.VISIBLE
                settingsBtn.visibility = View.VISIBLE
                headerTitle.text = "Nexo AI"
                val keyOk = NexoAiConfig.validate(settings, NexoAiConfig.apiKey(activity)) == null
                val modelTag = if (settings.enabled && settings.consentAccepted && keyOk) {
                    settings.effectiveModel().ifBlank { settings.provider }
                } else if (!settings.enabled) {
                    "Desactivado"
                } else {
                    "Configurar clave API"
                }
                headerSubtitle.text = "$hostLabel · $modelTag"
                headerSubtitle.setTextColor(if (keyOk && settings.enabled) p.muted else p.accent)
            }
        }

        // ==================== CUERPO (ASISTENTE + AJUSTES INTEGRADOS) ====================
        val rootBody = FrameLayout(activity)
        val assistantContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        val settingsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(16f))
            visibility = View.GONE
        }
        rootBody.addView(assistantContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rootBody.addView(settingsContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // --- Tarjeta única de activación y consentimiento (evita botones apilados redundantes) ---
        val gateCard = NexoUi.card(activity, paddingDp = 16f)
        val gateTitle = TextView(activity).apply {
            textSize = 14.5f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
        }
        val gateBody = TextView(activity).apply {
            textSize = 12.5f
            typeface = NexoUi.regular(activity)
            setLineSpacing(0f, 1.28f)
            setTextColor(p.muted)
            setPadding(0, dp(4f), 0, dp(12f))
        }
        lateinit var gateActionBtn: TextView
        fun refreshGate() {
            val needEnable = !settings.enabled
            val needConsent = !settings.consentAccepted
            if (!needEnable && !needConsent) {
                gateCard.visibility = View.GONE
                return
            }
            gateCard.visibility = View.VISIBLE
            gateTitle.text = if (needEnable && needConsent) {
                "Privacidad bajo tu control"
            } else if (needEnable) {
                "Nexo AI está pausado"
            } else {
                "Autoriza el envío bajo demanda"
            }
            gateBody.text = "El texto visible saneado solo se envía cuando pulsas una acción. Nunca lee contraseñas, cookies, tarjetas ni pestañas privadas."
            gateActionBtn.text = when {
                needEnable && needConsent -> "Activar y autorizar Nexo AI"
                needEnable -> "Activar Nexo AI"
                else -> "Entiendo y autorizo"
            }
        }
        gateActionBtn = NexoUi.button(activity, "Activar y autorizar Nexo AI", primary = true) {
            if (!settings.enabled) NexoAiConfig.setEnabled(activity, true)
            if (!settings.consentAccepted) NexoAiConfig.setConsentAccepted(activity, true)
            settings = NexoAiConfig.read(activity)
            refreshGate()
            refreshHeader()
            Toastish.show(activity, "Nexo AI listo para usar.")
        }
        val gateHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_menu_shield)
                imageTintList = ColorStateList.valueOf(p.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(8f) })
            addView(gateTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        gateCard.addView(gateHeaderRow)
        gateCard.addView(gateBody)
        gateCard.addView(gateActionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        assistantContainer.addView(gateCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12f)
        })
        refreshGate()

        // --- Tarjeta de bloqueo por sitio ---
        val hostGateCard = NexoUi.card(activity, paddingDp = 14f).apply {
            visibility = if (pageHost.isNotBlank() && !NexoAiConfig.isHostAllowed(activity, pageHost)) View.VISIBLE else View.GONE
        }
        if (pageHost.isNotBlank()) {
            hostGateCard.addView(NexoUi.statusPill(activity, "Bloqueado en $pageHost", NexoUi.Tone.WARNING))
            hostGateCard.addView(NexoUi.note(
                activity,
                "El acceso de Nexo AI a este dominio está desactivado en Control del sitio."
            ))
            hostGateCard.addView(NexoUi.button(activity, "Permitir en $pageHost") {
                NexoAiConfig.setHostAllowed(activity, pageHost, true)
                hostGateCard.visibility = View.GONE
                Toastish.show(activity, "Nexo AI permitido en $pageHost.")
            }, NexoUi.buttonParams(activity, 8f))
            assistantContainer.addView(hostGateCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12f)
            })
        }

        // --- Acciones rápidas (cuadrícula editorial 2 columnas -> rail compacto tras consultar) ---
        val quickSectionLabel = TextView(activity).apply {
            text = "ACCIONES RÁPIDAS"
            textSize = 11f
            letterSpacing = 0.085f
            typeface = NexoUi.bold(activity)
            setTextColor(p.muted)
            setPadding(dp(2f), 0, 0, dp(8f))
        }
        val quickGridHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val quickRailHolder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        assistantContainer.addView(quickSectionLabel)
        assistantContainer.addView(quickGridHolder)
        assistantContainer.addView(quickRailHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

        // --- Hilo conversacional ---
        val threadContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        assistantContainer.addView(threadContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4f)
        })

        // --- Indicador de procesamiento limpio ---
        val thinkingCard = NexoUi.card(activity, paddingDp = 14f).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val thinkingDot = ImageView(activity).apply {
            setImageResource(R.drawable.ic_nexo_ai_spark)
            imageTintList = ColorStateList.valueOf(p.accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val thinkingLabel = TextView(activity).apply {
            textSize = 13.5f
            typeface = NexoUi.medium(activity)
            setTextColor(p.text)
            setPadding(dp(10f), 0, 0, 0)
        }
        thinkingCard.addView(thinkingDot, LinearLayout.LayoutParams(dp(18f), dp(18f)))
        thinkingCard.addView(thinkingLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        assistantContainer.addView(thinkingCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })

        // ==================== PIE FIJO (ALCANCE + COMPOSER MODERNO) ====================
        val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        footer.addView(NexoUi.divider(activity, 0f))

        val scopeHolder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4f), 0, 0)
        }
        val scopes = listOf("page" to "Página actual", "selection" to "Selección", "tabs" to "Pestañas")
        fun renderScopes() {
            scopeHolder.removeAllViews()
            scopeHolder.addView(NexoUi.chipRow(activity, scopes.map { (id, label) ->
                NexoUi.chip(activity, label, scope == id) {
                    scope = id
                    renderScopes()
                }
            }, sidePaddingDp = 16f))
        }
        renderScopes()
        footer.addView(scopeHolder)

        val composerSurface = if (p.isDark) p.surfaceContainerHighest else p.surfaceContainerLowest
        val composerStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        val composerPill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeManager.rounded(activity, composerSurface, 26f, composerStroke)
            setPadding(dp(16f), dp(4f), dp(6f), dp(4f))
            minimumHeight = dp(52f)
        }
        val promptField = EditText(activity).apply {
            hint = "Pregúntale a Nexo AI sobre esta página…"
            textSize = 14.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            background = null
            maxLines = 4
            minLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(0, dp(10f), dp(8f), dp(10f))
        }
        val sendOrStopBtn = ImageButton(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Enviar consulta a Nexo AI"
        }

        fun updateSendButton(generating: Boolean) {
            val bgColor = if (generating) p.error.container else p.primary.color
            val fgColor = if (generating) p.error.onContainer else p.primary.onColor
            val iconRes = if (generating) R.drawable.ic_ai_stop else R.drawable.ic_ai_send
            val fill = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            val inset = InsetDrawable(fill, dp(4f))
            val rippleColor = ColorUtils.setAlphaComponent(fgColor, 48)
            sendOrStopBtn.background = RippleDrawable(ColorStateList.valueOf(rippleColor), inset, inset)
            sendOrStopBtn.setImageResource(iconRes)
            sendOrStopBtn.imageTintList = ColorStateList.valueOf(fgColor)
            sendOrStopBtn.contentDescription = if (generating) "Detener generación" else "Enviar consulta a Nexo AI"
        }
        updateSendButton(false)

        composerPill.addView(promptField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composerPill.addView(sendOrStopBtn, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        footer.addView(composerPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16f), dp(4f), dp(16f), dp(6f))
        })

        // ==================== CAMBIO DE VISTA ASISTENTE <-> AJUSTES ====================
        lateinit var openInlineSettings: () -> Unit

        fun setSettingsMode(enabled: Boolean) {
            showingSettings = enabled
            assistantContainer.visibility = if (enabled) View.GONE else View.VISIBLE
            footer.visibility = if (enabled) View.GONE else View.VISIBLE
            settingsContainer.visibility = if (enabled) View.VISIBLE else View.GONE
            refreshHeader()
            bodyScroll.post { bodyScroll.scrollTo(0, 0) }
        }

        openInlineSettings = {
            settingsContainer.removeAllViews()
            buildSettingsForm(
                activity = activity,
                container = settingsContainer,
                includeEnableToggle = true,
                includeLogRow = true,
                onSaved = {
                    settings = NexoAiConfig.read(activity)
                    refreshGate()
                    setSettingsMode(false)
                }
            )
            setSettingsMode(true)
        }

        settingsBtn.setOnClickListener { openInlineSettings() }
        backFromSettingsBtn.setOnClickListener { setSettingsMode(false) }
        headerSubtitle.setOnClickListener {
            if (!showingSettings && NexoAiConfig.validate(settings, NexoAiConfig.apiKey(activity)) != null) {
                openInlineSettings()
            }
        }

        // ==================== BURBUJAS DEL HILO ====================
        fun appendUserBubble(text: String) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            val r = dp(18f).toFloat()
            val tail = dp(6f).toFloat()
            val bubble = TextView(activity).apply {
                this.text = text
                textSize = 14f
                typeface = NexoUi.regular(activity)
                setLineSpacing(0f, 1.28f)
                setTextColor(p.primary.onContainer)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = GradientDrawable().apply {
                    setColor(p.primary.container)
                    cornerRadii = floatArrayOf(r, r, r, r, tail, tail, r, r)
                }
            }
            val maxW = (activity.resources.displayMetrics.widthPixels * 0.82f).toInt()
            bubble.maxWidth = maxW
            row.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            threadContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
        }

        fun appendErrorCard(message: String, showSettingsCta: Boolean, retryAction: (() -> Unit)? = null) {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = ThemeManager.rounded(activity, p.error.container, 18f)
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            }
            card.addView(TextView(activity).apply {
                text = message
                textSize = 13.5f
                typeface = NexoUi.medium(activity)
                setLineSpacing(0f, 1.25f)
                setTextColor(p.error.onContainer)
            })
            val ctas = mutableListOf<View>()
            if (showSettingsCta) {
                ctas += NexoUi.chip(activity, "Configurar proveedor", false) { openInlineSettings() }
            }
            if (retryAction != null) {
                ctas += NexoUi.chip(activity, "Reintentar", false) { retryAction() }
            }
            if (ctas.isNotEmpty()) {
                card.addView(NexoUi.chipRow(activity, ctas, sidePaddingDp = 0f), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6f) })
            }
            threadContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
            scrollToBottom()
        }

        lateinit var runTask: (String, String, String?) -> Unit

        fun appendAssistantBubble(res: NexoAiClient.AiResponse, promptUsed: String, labelUsed: String) {
            val card = NexoUi.card(activity, paddingDp = 15f)
            val answerView = TextView(activity).apply {
                text = res.text
                textSize = 14.5f
                typeface = NexoUi.regular(activity)
                setLineSpacing(0f, 1.38f)
                setTextColor(p.text)
                setTextIsSelectable(true)
            }
            val metaView = TextView(activity).apply {
                text = res.usage?.let { "Tokens · ${it.format()}" }
                    ?: "${settings.provider} · ${settings.effectiveModel().ifBlank { "modelo" }}"
                textSize = 11.5f
                typeface = NexoUi.medium(activity)
                setTextColor(p.muted)
                setPadding(0, dp(10f), 0, dp(4f))
            }
            val chips = mutableListOf<View>()
            chips += NexoUi.chip(activity, "Copiar", false) {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Nexo AI", res.text))
                Toastish.show(activity, "Respuesta copiada.")
            }
            if (promptUsed.isNotBlank()) {
                chips += NexoUi.chip(activity, "Reintentar", false) {
                    runTask(promptUsed, labelUsed, null)
                }
            }
            if (res.actions.isNotEmpty()) {
                res.actions.forEach { proposed ->
                    val prefix = if (proposed.requiresConfirmation) "Confirmar: " else ""
                    chips += NexoUi.chip(activity, "$prefix${proposed.label}", false) {
                        NexoAiPageAgent.confirmAndExecuteAction(activity, proposed)
                    }
                }
            } else {
                lastSnapshot?.links?.firstOrNull()?.let { (label, href) ->
                    chips += NexoUi.chip(activity, "Abrir enlace principal", false) {
                        NexoAiPageAgent.confirmAndExecuteAction(
                            activity,
                            NexoAiClient.ProposedAction("open_link", label, href)
                        )
                    }
                }
                lastSnapshot?.headings?.firstOrNull()?.let { h ->
                    chips += NexoUi.chip(activity, "Resaltar encabezado", false) {
                        NexoAiPageAgent.confirmAndExecuteAction(
                            activity,
                            NexoAiClient.ProposedAction("highlight", h, h)
                        )
                    }
                }
            }
            card.addView(answerView)
            card.addView(metaView)
            card.addView(NexoUi.chipRow(activity, chips, sidePaddingDp = 0f))
            threadContainer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8f)
            })
            scrollToBottom()
        }

        // ==================== EJECUCIÓN DE CONSULTAS ====================
        fun compactQuickActions() {
            if (hasMessages) return
            hasMessages = true
            quickSectionLabel.visibility = View.GONE
            quickGridHolder.visibility = View.GONE
            quickRailHolder.visibility = View.VISIBLE
        }

        runTask = { taskPrompt, taskLabel, displayTitle ->
            settings = NexoAiConfig.read(activity)
            if (!settings.enabled || !settings.consentAccepted) {
                refreshGate()
                Toastish.show(activity, "Activa y autoriza Nexo AI arriba primero.")
                bodyScroll.post { bodyScroll.smoothScrollTo(0, 0) }
            } else {
                val currentScope = scope
                val activeHost = runCatching { android.net.Uri.parse(TabManager.activeTab()?.url.orEmpty()).host }
                    .getOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                if (currentScope != "tabs" && activeHost.isNotBlank() && !NexoAiConfig.isHostAllowed(activity, activeHost)) {
                    hostGateCard.visibility = View.VISIBLE
                    appendErrorCard("Nexo AI está bloqueado en $activeHost. Permítelo arriba o en Control del sitio.", false)
                } else {
                    val key = NexoAiConfig.apiKey(activity)
                    val validation = NexoAiConfig.validate(settings, key)
                    if (validation != null) {
                        appendErrorCard(validation, showSettingsCta = true)
                    } else {
                        compactQuickActions()
                        appendUserBubble(displayTitle ?: taskPrompt)

                        val myGen = ++requestGen
                        lastPrompt = taskPrompt
                        lastLabel = taskLabel
                        activeCancel?.cancel()
                        val handle = NexoAiClient.CancelHandle()
                        activeCancel = handle
                        thinkingLabel.text = "Consultando ${settings.provider}…"
                        thinkingCard.visibility = View.VISIBLE
                        updateSendButton(true)
                        scrollToBottom()

                        fun finishLoading() {
                            if (activeCancel === handle) {
                                activeCancel = null
                                thinkingCard.visibility = View.GONE
                                updateSendButton(false)
                            }
                        }

                        fun dispatchWithContext(contextText: String) {
                            if (myGen != requestGen || handle.isCancelled) return
                            io.execute {
                                val outcome = NexoAiClient.execute(settings, key, taskPrompt, contextText, handle)
                                main.post {
                                    if (activity.isFinishing || activity.isDestroyed) return@post
                                    if (myGen != requestGen || handle.isCancelled) return@post
                                    finishLoading()
                                    outcome.onSuccess { res ->
                                        appendAssistantBubble(res, taskPrompt, taskLabel)
                                        NexoAiConfig.appendLog(activity, taskLabel, activeHost, "ok")
                                    }.onFailure { err ->
                                        appendErrorCard(
                                            err.message ?: "No se pudo obtener respuesta del proveedor.",
                                            showSettingsCta = false,
                                            retryAction = { runTask(taskPrompt, taskLabel, displayTitle) }
                                        )
                                        NexoAiConfig.appendLog(activity, taskLabel, activeHost, "error")
                                    }
                                }
                            }
                        }

                        if (currentScope == "tabs") {
                            NexoAiPageAgent.captureOpenTabsContext(activity, settings.maxContextChars, settings.allowPrivateTabs) { tabsContext ->
                                if (myGen != requestGen || handle.isCancelled) return@captureOpenTabsContext
                                dispatchWithContext(tabsContext)
                            }
                        } else {
                            NexoAiPageAgent.captureActiveSnapshot(activity, settings.maxContextChars, settings.allowPrivateTabs) { snap, errMsg ->
                                if (myGen != requestGen || handle.isCancelled) return@captureActiveSnapshot
                                if (errMsg != null || snap == null) {
                                    finishLoading()
                                    appendErrorCard(errMsg ?: "No se pudo leer el contexto de esta pestaña.", false)
                                    return@captureActiveSnapshot
                                }
                                lastSnapshot = snap
                                if (currentScope == "selection" && snap.selection.isBlank()) {
                                    finishLoading()
                                    appendErrorCard("Selecciona primero un texto en la página o cambia el alcance a «Página actual».", false)
                                    return@captureActiveSnapshot
                                }
                                dispatchWithContext(snap.toContextText(currentScope))
                            }
                        }
                    }
                }
            }
        }

        // Poblar cuadrícula 2 columnas + rail compacto de acciones rápidas
        val fontScale = activity.resources.configuration.fontScale
        val cols = if (fontScale >= 1.6f) 1 else 2
        val tileBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
        val tileStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        QUICK_ACTIONS.chunked(cols).forEachIndexed { rowIdx, rowItems ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { colIdx, qa ->
                val tile = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    minimumHeight = dp(76f)
                    setPadding(dp(12f), dp(11f), dp(12f), dp(11f))
                    background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, tileBg, 16f, tileStroke), 16f)
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${qa.title}. ${qa.subtitle}"
                    setOnClickListener {
                        qa.forceScope?.let { scope = it; renderScopes() }
                        runTask(qa.prompt, qa.logLabel, qa.title)
                    }
                }
                val topRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val iconWell = FrameLayout(activity).apply {
                    background = ThemeManager.rounded(activity, p.primary.container, 9f)
                    addView(ImageView(activity).apply {
                        setImageResource(qa.icon)
                        imageTintList = ColorStateList.valueOf(p.primary.onContainer)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(dp(15f), dp(15f), Gravity.CENTER))
                }
                val label = TextView(activity).apply {
                    text = qa.title
                    textSize = 13.5f
                    typeface = NexoUi.semiBold(activity)
                    setTextColor(p.text)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(8f), 0, 0, 0)
                }
                topRow.addView(iconWell, LinearLayout.LayoutParams(dp(26f), dp(26f)))
                topRow.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val sub = TextView(activity).apply {
                    text = qa.subtitle
                    textSize = 11.5f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.muted)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(6f), 0, 0)
                }
                tile.addView(topRow)
                tile.addView(sub)
                row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (colIdx > 0) marginStart = dp(8f)
                })
            }
            if (rowItems.size < cols) {
                row.addView(View(activity), LinearLayout.LayoutParams(0, 1, (cols - rowItems.size).toFloat()).apply {
                    marginStart = dp(8f)
                })
            }
            quickGridHolder.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIdx > 0) topMargin = dp(8f)
            })
        }

        quickRailHolder.addView(NexoUi.chipRow(activity, QUICK_ACTIONS.map { qa ->
            NexoUi.chip(activity, qa.title, false) {
                qa.forceScope?.let { scope = it; renderScopes() }
                runTask(qa.prompt, qa.logLabel, qa.title)
            }
        }, sidePaddingDp = 0f))

        fun submitPrompt() {
            val active = activeCancel
            if (active != null) {
                requestGen++
                active.cancel()
                activeCancel = null
                thinkingCard.visibility = View.GONE
                updateSendButton(false)
                Toastish.show(activity, "Generación detenida.")
                return
            }
            val q = promptField.text.toString().trim()
            if (q.isEmpty()) {
                Toastish.show(activity, "Escribe una pregunta o elige una acción rápida.")
                return
            }
            promptField.setText("")
            runTask(q, "consulta", null)
        }

        sendOrStopBtn.setOnClickListener { submitPrompt() }
        promptField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitPrompt()
                true
            } else false
        }

        refreshHeader()
        val (createdSheet, scrollView) = NexoSheets.showFixed(
            activity = activity,
            headerView = header,
            bodyContent = rootBody,
            footerView = footer,
            heightRatio = 0.72f
        )
        sheet = createdSheet
        bodyScroll = scrollView
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.setOnDismissListener {
            requestGen++
            activeCancel?.cancel()
            activeCancel = null
        }
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
        val (modelView, modelField) = NexoUi.field(
            activity,
            "Modelo",
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

        fun renderProviders() {
            providerHolder.removeAllViews()
            providerHolder.addView(NexoUi.chipRow(activity, NexoAiConfig.PROVIDERS.map { (id, label) ->
                NexoUi.chip(activity, label, provider == id) {
                    provider = id
                    endpointView.visibility = if (provider == "custom") View.VISIBLE else View.GONE
                    modelField.hint = CaptionProtocol.AI_MODELS[provider] ?: "Nombre del modelo"
                    renderProviders()
                }
            }, sidePaddingDp = 0f))
            endpointView.visibility = if (provider == "custom") View.VISIBLE else View.GONE
        }
        renderProviders()

        container.addView(NexoUi.section(activity, "Proveedor de IA"))
        container.addView(providerHolder)
        container.addView(modelView)
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
            timeoutHolder.addView(NexoUi.chipRow(activity, listOf(20 to "20 s", 35 to "35 s", 60 to "60 s").map { (v, label) ->
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
                showLogDialog(activity)
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

    private fun showLogDialog(activity: Activity) {
        val entries = NexoAiConfig.readLog(activity)
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        val text = if (entries.isEmpty()) {
            "Sin acciones registradas todavía."
        } else {
            entries.reversed().joinToString("\n") { e ->
                "[${fmt.format(Date(e.timestampMs))}] ${e.action} (${e.host.ifBlank { "general" }}) · ${e.status}"
            }
        }
        AlertDialog.Builder(activity)
            .setTitle("Registro local de Nexo AI")
            .setMessage(text)
            .setNeutralButton("Limpiar") { _, _ ->
                NexoAiConfig.clearLog(activity)
                Toastish.show(activity, "Registro limpiado.")
            }
            .setPositiveButton("Cerrar", null)
            .show()
    }
}
