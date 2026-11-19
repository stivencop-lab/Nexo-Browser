package com.nexo.browser.ai

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
 * Panel nativo de Nexo AI (hoja inferior sin recargar la página activa).
 *
 * Permite:
 * - Resumir la página, explicar selección, traducir texto, Q&A sobre la página,
 *   comparar pestañas, extraer tablas/datos, listar enlaces clave e inspeccionar formularios.
 * - Configurar proveedor (OpenAI, Claude, OpenRouter, Gemini, Groq, DeepSeek, Mistral, xAI, Propio),
 *   modelo, endpoint, clave cifrada (Android Keystore), timeout y presupuesto de contexto.
 * - Cancelar peticiones en curso, ver uso real de tokens si la API lo reporta y
 *   apagar Nexo AI por completo.
 */
object NexoAiSheet {
    private val io by lazy { Executors.newSingleThreadExecutor() }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun show(activity: Activity) {
        val p = ThemeManager.palette(activity)
        var settings = NexoAiConfig.read(activity)
        var scope = "page" // "page" | "selection" | "tabs"
        var activeCancel: NexoAiClient.CancelHandle? = null
        var requestGen = 0L
        var lastResponseText = ""
        var lastSnapshot: NexoAiPageAgent.PageSnapshot? = null

        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)

        // --- Interruptor maestro ---
        val workspaceBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (settings.enabled) View.VISIBLE else View.GONE
        }

        body.addView(NexoUi.switchRow(
            activity,
            "Activar Nexo AI",
            "Envía contexto saneado solo cuando tú pulses una acción. Nunca lee contraseñas, cookies ni tarjetas.",
            settings.enabled
        ) { enabled ->
            NexoAiConfig.setEnabled(activity, enabled)
            settings = NexoAiConfig.read(activity)
            workspaceBox.visibility = if (enabled) View.VISIBLE else View.GONE
        })

        // --- Consentimiento explícito ---
        val consentBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (!settings.consentAccepted) View.VISIBLE else View.GONE
        }
        consentBox.addView(NexoUi.note(
            activity,
            "Aviso de privacidad: Nexo AI envía únicamente el texto visible saneado de la pestaña (sin contraseñas, " +
                "campos ocultos, cookies ni pestañas privadas por defecto) al proveedor que tú configures con tu propia clave API."
        ))
        consentBox.addView(NexoUi.button(activity, "Entiendo y autorizo Nexo AI", primary = true) {
            NexoAiConfig.setConsentAccepted(activity, true)
            settings = NexoAiConfig.read(activity)
            consentBox.visibility = View.GONE
            Toastish.show(activity, "Consentimiento guardado.")
        }, NexoUi.buttonParams(activity, 8f))
        workspaceBox.addView(consentBox)

        // --- Alcance del contexto ---
        workspaceBox.addView(NexoUi.section(activity, "Alcance del contexto"))
        val scopeHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scopes = listOf("page" to "Página actual", "selection" to "Selección", "tabs" to "Comparar pestañas")
        fun renderScopes() {
            scopeHolder.removeAllViews()
            scopeHolder.addView(NexoUi.chipRow(activity, scopes.map { (id, label) ->
                NexoUi.chip(activity, label, scope == id) {
                    scope = id
                    renderScopes()
                }
            }))
        }
        renderScopes()
        workspaceBox.addView(scopeHolder)

        // --- Campo de pregunta + estado + respuesta ---
        val (promptView, promptField) = NexoUi.field(activity, "Pregunta o tarea sobre la navegación", "Ej. Resume los puntos clave o extrae precios…", "")
        val statusView = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(p.muted)
            visibility = View.GONE
            setPadding(0, NexoUi.dp(activity, 8f), 0, 0)
        }
        val resultCard = NexoUi.card(activity).apply { visibility = View.GONE }
        val resultText = TextView(activity).apply {
            textSize = 14f
            setTextColor(p.text)
            setTextIsSelectable(true)
        }
        val usageText = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(p.muted)
            setPadding(0, NexoUi.dp(activity, 8f), 0, 0)
        }
        val actionsBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, NexoUi.dp(activity, 8f), 0, 0)
        }
        resultCard.addView(resultText)
        resultCard.addView(usageText)
        resultCard.addView(actionsBox)

        val cancelBtn = NexoUi.button(activity, "Cancelar solicitud", danger = true) {
            requestGen++
            activeCancel?.cancel()
            activeCancel = null
            cancelBtn.visibility = View.GONE
            statusView.text = "Solicitud cancelada."
        }.apply { visibility = View.GONE }

        fun runTask(taskPrompt: String, taskLabel: String) {
            settings = NexoAiConfig.read(activity)
            if (!settings.enabled) {
                Toastish.show(activity, "Activa Nexo AI primero.")
                return
            }
            if (!settings.consentAccepted) {
                Toastish.show(activity, "Acepta primero el aviso de privacidad de Nexo AI.")
                return
            }
            val key = NexoAiConfig.apiKey(activity)
            val validation = NexoAiConfig.validate(settings, key)
            if (validation != null) {
                statusView.text = validation
                statusView.visibility = View.VISIBLE
                showSettingsSheet(activity) { settings = NexoAiConfig.read(activity) }
                return
            }

            val myGen = ++requestGen
            activeCancel?.cancel()
            val handle = NexoAiClient.CancelHandle()
            activeCancel = handle
            statusView.text = "Extrayendo contexto seguro y consultando ${settings.provider}…"
            statusView.visibility = View.VISIBLE
            cancelBtn.visibility = View.VISIBLE
            resultCard.visibility = View.GONE

            val currentScope = scope
            val activeHost = runCatching { android.net.Uri.parse(TabManager.activeTab()?.url.orEmpty()).host }.getOrNull().orEmpty()

            fun dispatchWithContext(contextText: String) {
                if (myGen != requestGen || handle.isCancelled) return
                io.execute {
                    val outcome = NexoAiClient.execute(settings, key, taskPrompt, contextText, handle)
                    main.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        if (myGen != requestGen || handle.isCancelled) return@post
                        if (activeCancel === handle) {
                            activeCancel = null
                            cancelBtn.visibility = View.GONE
                        }
                        outcome.onSuccess { res ->
                            lastResponseText = res.text
                            statusView.visibility = View.GONE
                            resultText.text = res.text
                            usageText.text = res.usage?.let { "Tokens reportados por API: ${it.format()}" }
                                ?: "Uso de tokens no reportado por el proveedor."
                            actionsBox.removeAllViews()
                            val chips = mutableListOf<View>()
                            chips += NexoUi.chip(activity, "Copiar respuesta", false) {
                                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Nexo AI", lastResponseText))
                                Toastish.show(activity, "Respuesta copiada.")
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
                            actionsBox.addView(NexoUi.chipRow(activity, chips))
                            resultCard.visibility = View.VISIBLE
                            NexoAiConfig.appendLog(activity, taskLabel, activeHost, "ok")
                        }.onFailure { err ->
                            statusView.text = err.message ?: "Error al consultar el proveedor."
                            statusView.visibility = View.VISIBLE
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
                return
            }

            NexoAiPageAgent.captureActiveSnapshot(activity, settings.maxContextChars, settings.allowPrivateTabs) { snap, errMsg ->
                if (myGen != requestGen || handle.isCancelled) return@captureActiveSnapshot
                if (errMsg != null || snap == null) {
                    activeCancel = null
                    cancelBtn.visibility = View.GONE
                    statusView.text = errMsg ?: "No se pudo leer el contexto de esta pestaña."
                    statusView.visibility = View.VISIBLE
                    return@captureActiveSnapshot
                }
                lastSnapshot = snap
                if (currentScope == "selection" && snap.selection.isBlank()) {
                    activeCancel = null
                    cancelBtn.visibility = View.GONE
                    statusView.text = "Selecciona primero un fragmento de texto en la página o cambia el alcance a «Página actual»."
                    statusView.visibility = View.VISIBLE
                    return@captureActiveSnapshot
                }
                dispatchWithContext(snap.toContextText(currentScope))
            }
        }

        // --- Acciones rápidas ---
        workspaceBox.addView(NexoUi.section(activity, "Acciones rápidas"))
        workspaceBox.addView(NexoUi.chipRow(activity, listOf(
            NexoUi.chip(activity, "Resumir página", false) {
                runTask("Resume esta página en viñetas claras con las ideas principales.", "resumir")
            },
            NexoUi.chip(activity, "Explicar selección", false) {
                scope = "selection"; renderScopes()
                runTask("Explica de forma clara y sencilla el texto seleccionado.", "explicar_seleccion")
            },
            NexoUi.chip(activity, "Traducir al español", false) {
                runTask("Traduce el contenido relevante al español conservando el sentido.", "traducir_ai")
            },
            NexoUi.chip(activity, "Extraer tablas y datos", false) {
                runTask("Extrae los datos estructurados, precios, fechas o tablas clave en formato ordenado.", "extraer_datos")
            },
            NexoUi.chip(activity, "Enlaces clave", false) {
                runTask("Identifica los enlaces más importantes de esta página y explica para qué sirve cada uno.", "enlaces_clave")
            },
            NexoUi.chip(activity, "Comparar pestañas", false) {
                scope = "tabs"; renderScopes()
                runTask("Compara las pestañas abiertas, agrúpalas por tema y sugiere próximos pasos.", "comparar_pestanas")
            }
        )))

        workspaceBox.addView(promptView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = NexoUi.dp(activity, 10f)
        })
        workspaceBox.addView(NexoUi.button(activity, "Consultar a Nexo AI", primary = true) {
            val q = promptField.text.toString().trim()
            if (q.isEmpty()) {
                Toastish.show(activity, "Escribe una pregunta o elige una acción rápida.")
                return@button
            }
            runTask(q, "consulta")
        }, NexoUi.buttonParams(activity, 8f))
        workspaceBox.addView(cancelBtn, NexoUi.buttonParams(activity, 6f))
        workspaceBox.addView(statusView)
        workspaceBox.addView(resultCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = NexoUi.dp(activity, 10f)
        })

        // --- Configuración y registro local ---
        workspaceBox.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_settings,
            "Configurar proveedor y clave API",
            "${settings.provider} · ${settings.effectiveModel().ifBlank { "sin modelo" }}"
        ) {
            showSettingsSheet(activity) { settings = NexoAiConfig.read(activity) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = NexoUi.dp(activity, 10f)
        })
        workspaceBox.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_history,
            "Registro local de acciones",
            "Historial saneado sin claves ni contraseñas"
        ) {
            showLogDialog(activity)
        })

        body.addView(workspaceBox)

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Nexo AI", "Asistente integrado sin recargar la página"),
                body
            )
        )
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.setOnDismissListener {
            requestGen++
            activeCancel?.cancel()
            activeCancel = null
        }
    }

    fun showSettingsSheet(activity: Activity, onSaved: () -> Unit = {}) {
        val current = NexoAiConfig.read(activity)
        var provider = current.provider
        var allowPrivate = current.allowPrivateTabs
        var maxContext = current.maxContextChars
        var timeoutSec = current.timeoutSeconds

        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        val providerHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (modelView, modelField) = NexoUi.field(activity, "Modelo", CaptionProtocol.AI_MODELS[provider] ?: "Nombre del modelo", current.model)
        val (endpointView, endpointField) = NexoUi.field(activity, "Endpoint API (solo HTTPS o localhost)", "https://servidor/v1", current.customEndpoint)
        val (keyView, keyField) = NexoUi.field(activity, "Clave API (cifrada en Android Keystore)", "Pega tu clave", NexoAiConfig.apiKey(activity))
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
            }))
            endpointView.visibility = if (provider == "custom") View.VISIBLE else View.GONE
        }
        renderProviders()

        body.addView(NexoUi.section(activity, "Proveedor de IA"))
        body.addView(providerHolder)
        body.addView(modelView)
        body.addView(endpointView)
        body.addView(keyView)

        body.addView(NexoUi.section(activity, "Presupuesto máximo de contexto"))
        val ctxHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderCtx() {
            ctxHolder.removeAllViews()
            ctxHolder.addView(NexoUi.chipRow(activity, listOf(4000 to "4 KB", 8000 to "8 KB", 12000 to "12 KB", 16000 to "16 KB").map { (v, label) ->
                NexoUi.chip(activity, label, maxContext == v) { maxContext = v; renderCtx() }
            }))
        }
        renderCtx()
        body.addView(ctxHolder)

        body.addView(NexoUi.section(activity, "Tiempo máximo de espera"))
        val timeoutHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderTimeouts() {
            timeoutHolder.removeAllViews()
            timeoutHolder.addView(NexoUi.chipRow(activity, listOf(20 to "20 s", 35 to "35 s", 60 to "60 s").map { (v, label) ->
                NexoUi.chip(activity, label, timeoutSec == v) { timeoutSec = v; renderTimeouts() }
            }))
        }
        renderTimeouts()
        body.addView(timeoutHolder)

        body.addView(NexoUi.switchRow(
            activity,
            "Permitir en pestañas privadas",
            "Desactivado por defecto: las pestañas privadas no envían contexto a proveedores externos.",
            allowPrivate
        ) { allowPrivate = it })

        val errorView = TextView(activity).apply {
            textSize = 12.5f
            setTextColor(ThemeManager.palette(activity).danger)
            visibility = View.GONE
        }
        body.addView(errorView)

        body.addView(NexoUi.button(activity, "Guardar configuración", primary = true) {
            val next = current.copy(
                provider = provider,
                model = modelField.text.toString().trim(),
                customEndpoint = if (provider == "custom") endpointField.text.toString().trim() else "",
                timeoutSeconds = timeoutSec,
                maxContextChars = maxContext,
                allowPrivateTabs = allowPrivate
            )
            val key = keyField.text.toString().trim()
            val err = NexoAiConfig.validate(next, key)
            if (err != null) {
                errorView.text = err
                errorView.visibility = View.VISIBLE
                return@button
            }
            NexoAiConfig.save(activity, next)
            NexoAiConfig.setApiKey(activity, key)
            sheet.dismiss()
            onSaved()
            Toastish.show(activity, "Configuración de Nexo AI guardada.")
        }, NexoUi.buttonParams(activity, 12f))

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, "Configurar Nexo AI", "Claves cifradas en Keystore"), body)
        )
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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
