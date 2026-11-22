package com.nexo.browser.ai

import android.app.Activity
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nexo.browser.NexoApertureDrawable
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import java.text.DateFormat
import java.util.Date

/**
 * Panel lateral izquierdo integrado dentro de Nexo AI:
 * acceso inmediato a "+ Nuevo chat", Modo Agente en vivo, secciones (Chat, Comparar, Ajustes),
 * búsqueda instantánea e historial completo agrupado (Fijados, Hoy, Ayer, Últimos 7 días, Anteriores).
 */
internal class NexoAiSidebarDrawer(
    private val activity: Activity,
    private val onNewChat: () -> Unit,
    private val onSelectConversation: (String) -> Unit,
    private val onSelectAgentMode: () -> Unit,
    private val onSelectLongTaskMode: () -> Unit,
    private val onSelectQuickChat: () -> Unit,
    private val onSelectTab: (NexoAiSheet.Tab) -> Unit,
    private val onClose: () -> Unit
) {
    private val p = ThemeManager.palette(activity)
    private val dp = { v: Float -> NexoUi.dp(activity, v) }

    private var query = ""
    private var currentTab: NexoAiSheet.Tab = NexoAiSheet.Tab.CHAT
    private var currentScope: String = "page"
    private var lastSnapshot: NexoAiModels.ControllerSnapshot? = null

    private val modesBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val historyList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val footerCount = TextView(activity).apply {
        textSize = 11.5f
        typeface = NexoUi.regular(activity)
        setTextColor(p.muted)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val clearAllBtn = TextView(activity).apply {
        text = "Borrar"
        textSize = 12f
        typeface = NexoUi.medium(activity)
        setTextColor(p.danger)
        gravity = Gravity.CENTER
        minHeight = dp(40f)
        setPadding(dp(10f), 0, dp(10f), 0)
        background = ThemeManager.ripple(activity, null, 12f)
        setOnClickListener {
            NexoAiHistorySheet.confirm(
                activity,
                "¿Borrar todo el historial de Nexo AI?",
                "Se eliminarán todas las conversaciones guardadas en este dispositivo. No se puede deshacer.",
                "Borrar todo"
            ) {
                NexoAiController.deleteAllConversations()
                Toastish.show(activity, "Historial de Nexo AI borrado.")
            }
        }
    }

    private val searchField = EditText(activity).apply {
        hint = "Buscar chats…"
        textSize = 14f
        typeface = NexoUi.regular(activity)
        setTextColor(p.text)
        setHintTextColor(p.muted)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        minHeight = dp(42f)
        setPadding(dp(12f), 0, dp(12f), 0)
        background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_PILL)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        compoundDrawablePadding = dp(8f)
        compoundDrawableTintList = ColorStateList.valueOf(p.muted)
    }

    val view: View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14f), dp(10f), dp(14f), dp(8f))

        // Cabecera del panel lateral
        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(2f), 0, dp(8f))
        }
        val aperture = ImageView(activity).apply {
            setImageDrawable(NexoApertureDrawable(activity, 18f).apply { setColor(p.text) })
            scaleType = ImageView.ScaleType.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val brandTitle = TextView(activity).apply {
            text = "Nexo AI"
            textSize = 16f
            letterSpacing = -0.025f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
            setPadding(dp(8f), 0, 0, 0)
        }
        val closeBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Cerrar panel lateral"
            ThemeManager.styleIconButton(activity, this)
            setOnClickListener { onClose() }
        }
        topRow.addView(aperture, LinearLayout.LayoutParams(dp(22f), dp(22f)))
        topRow.addView(brandTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(closeBtn, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        addView(topRow)

        // Acción principal: Nuevo chat
        val newChatBtn = NexoUi.button(activity, "+ Nuevo chat", primary = true) {
            onNewChat()
            onClose()
        }
        addView(newChatBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10f)
        })

        // Buscador del historial
        addView(searchField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8f)
        })

        // Cuerpo desplazable: Modos/herramientas + historial agrupado
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(modesBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8f)
                })
                addView(NexoUi.divider(activity, 0f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.6f).coerceAtLeast(1)).apply {
                    bottomMargin = dp(6f)
                })
                addView(historyList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
        }
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Pie compacto con contador y borrado
        addView(NexoUi.divider(activity, 0f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(0.6f).coerceAtLeast(1)).apply {
            topMargin = dp(6f)
        })
        val bottomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), 0, 0)
            addView(footerCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearAllBtn)
        }
        addView(bottomRow)
    }

    init {
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                lastSnapshot?.let { render(it, currentTab, currentScope) }
            }
        })
    }

    fun render(snap: NexoAiModels.ControllerSnapshot, tab: NexoAiSheet.Tab, scope: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        lastSnapshot = snap
        currentTab = tab
        currentScope = scope

        // 1. Modos y herramientas del panel izquierdo
        modesBox.removeAllViews()
        val agentRunning = snap.activeAgentRequest != null
        val longRunning = snap.activeAgentRequest?.longTask == true
        val agentActive = (tab == NexoAiSheet.Tab.CHAT && scope == "agent") || (agentRunning && !longRunning)
        val longActive = (tab == NexoAiSheet.Tab.CHAT && scope == "long_agent") || longRunning
        modesBox.addView(navRow(
            icon = R.drawable.ic_nexo_aperture,
            title = "Agente en vivo",
            subtitle = if (agentRunning && !longRunning) snap.activeAgentRequest?.currentStepLabel?.ifBlank { "Controlando navegador…" } else "Navega, volumen, medios y pestañas",
            badge = if (agentRunning && !longRunning) "En vivo" else if (agentActive) "Activo" else null,
            badgeTone = if (agentRunning && !longRunning) NexoUi.Tone.SUCCESS else NexoUi.Tone.ACCENT,
            selected = agentActive
        ) {
            onSelectAgentMode()
            onClose()
        })
        val openTasksCount = snap.activeTasks.count { !it.completed }
        modesBox.addView(navRow(
            icon = R.drawable.ic_menu_workspace,
            title = "Tareas largas (20 pasos)",
            subtitle = when {
                longRunning -> snap.activeAgentRequest?.let { "${it.progressPercent ?: 0}% · ${it.currentStepLabel}" }
                openTasksCount > 0 -> "$openTasksCount tarea(s) con progreso guardado"
                else -> "Investigación multi-paso con hitos"
            },
            badge = when {
                longRunning -> "En curso"
                openTasksCount > 0 -> "$openTasksCount"
                longActive -> "Activo"
                else -> null
            },
            badgeTone = if (longRunning) NexoUi.Tone.SUCCESS else NexoUi.Tone.INFO,
            selected = longActive
        ) {
            onSelectLongTaskMode()
            onClose()
        })
        modesBox.addView(navRow(
            icon = R.drawable.ic_menu_reader,
            title = "Chat con la página",
            subtitle = null,
            badge = null,
            badgeTone = NexoUi.Tone.NEUTRAL,
            selected = tab == NexoAiSheet.Tab.CHAT && scope != "agent" && scope != "long_agent"
        ) {
            onSelectQuickChat()
            onClose()
        })
        modesBox.addView(navRow(
            icon = R.drawable.ic_ai_benchmark,
            title = "Comparar modelos",
            subtitle = null,
            badge = null,
            badgeTone = NexoUi.Tone.NEUTRAL,
            selected = tab == NexoAiSheet.Tab.BENCHMARK
        ) {
            onSelectTab(NexoAiSheet.Tab.BENCHMARK)
            onClose()
        })
        modesBox.addView(navRow(
            icon = R.drawable.ic_menu_settings,
            title = "Ajustes y API",
            subtitle = null,
            badge = null,
            badgeTone = NexoUi.Tone.NEUTRAL,
            selected = tab == NexoAiSheet.Tab.SETTINGS
        ) {
            onSelectTab(NexoAiSheet.Tab.SETTINGS)
            onClose()
        })

        // 2. Tareas largas activas/reanudables (si existen)
        val pendingTasks = snap.activeTasks.filter { !it.completed }.take(3)
        if (pendingTasks.isNotEmpty()) {
            modesBox.addView(TextView(activity).apply {
                text = "Tareas en progreso"
                textSize = 11.5f
                typeface = NexoUi.semiBold(activity)
                setTextColor(p.muted)
                setPadding(dp(6f), dp(8f), dp(6f), dp(2f))
            })
            for (task in pendingTasks) {
                modesBox.addView(navRow(
                    icon = R.drawable.ic_menu_play,
                    title = task.goal,
                    subtitle = "${task.progressPercent}% · ${task.lastCheckpoint}",
                    badge = "${task.progressPercent}%",
                    badgeTone = NexoUi.Tone.ACCENT,
                    selected = task.conversationId == snap.activeConversationId
                ) {
                    onSelectConversation(task.conversationId)
                    onClose()
                })
            }
        }

        // 2. Historial de conversaciones
        val saved = snap.conversations.count { it.messages.isNotEmpty() }
        footerCount.text = when (saved) {
            0 -> "Sin chats guardados"
            1 -> "1 chat local"
            else -> "$saved chats locales"
        }
        clearAllBtn.visibility = if (saved > 0) View.VISIBLE else View.GONE

        historyList.removeAllViews()
        val sections = NexoAiHistory.sections(snap.conversations, query, System.currentTimeMillis())
        if (sections.isEmpty()) {
            historyList.addView(TextView(activity).apply {
                text = if (query.isBlank()) "Tus conversaciones aparecerán aquí." else "Sin resultados para «${query.trim()}»."
                textSize = 13f
                typeface = NexoUi.regular(activity)
                setTextColor(p.muted)
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(20f), dp(8f), dp(20f))
            })
            return
        }
        for ((group, convs) in sections) {
            historyList.addView(TextView(activity).apply {
                text = group.label
                textSize = 11.5f
                typeface = NexoUi.semiBold(activity)
                setTextColor(p.muted)
                setPadding(dp(6f), dp(10f), dp(6f), dp(4f))
            })
            for (conv in convs) {
                historyList.addView(historyRow(conv, conv.id == snap.activeConversationId && tab == NexoAiSheet.Tab.CHAT))
            }
        }
    }

    private fun navRow(
        icon: Int,
        title: String,
        subtitle: String?,
        badge: String?,
        badgeTone: NexoUi.Tone,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        val fill = if (selected) ThemeManager.rounded(activity, NexoSheets.groupFill(p), 14f) else null
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44f)
            setPadding(dp(10f), dp(6f), dp(8f), dp(6f))
            background = ThemeManager.ripple(activity, fill, 14f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }

            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(if (selected && !p.isMonochrome) p.accent else p.text)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginEnd = dp(10f) })

            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(activity).apply {
                text = title
                textSize = 13.5f
                typeface = if (selected) NexoUi.semiBold(activity) else NexoUi.medium(activity)
                setTextColor(p.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!subtitle.isNullOrBlank()) {
                col.addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 11.5f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.muted)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            if (!badge.isNullOrBlank()) {
                addView(NexoUi.statusPill(activity, badge, badgeTone), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6f) })
            }
        }
    }

    private fun historyRow(conv: NexoAiModels.AiConversation, active: Boolean): View {
        val fill = if (active) ThemeManager.rounded(activity, NexoSheets.groupFill(p), 14f) else null
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52f)
            setPadding(dp(10f), dp(7f), dp(2f), dp(7f))
            background = ThemeManager.ripple(activity, fill, 14f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onSelectConversation(conv.id)
                onClose()
            }
            setOnLongClickListener {
                NexoAiHistorySheet.options(activity, conv)
                true
            }
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (conv.pinned) {
            titleRow.addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_menu_pin)
                imageTintList = ColorStateList.valueOf(if (p.isMonochrome) p.text else p.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(12f), dp(12f)).apply { marginEnd = dp(4f) })
        }
        titleRow.addView(TextView(activity).apply {
            text = (if (conv.hasInFlight) "● " else "") + conv.title
            textSize = 13.5f
            typeface = if (active) NexoUi.semiBold(activity) else NexoUi.medium(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        texts.addView(titleRow)

        val snippet = NexoAiHistory.lastSnippet(conv, maxChars = 64)
        val meta = listOfNotNull(
            conv.hostHint.takeIf { it.isNotBlank() },
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(conv.updatedAtMs))
        ).joinToString(" · ")
        val sub = snippet.ifBlank { meta }
        if (sub.isNotBlank()) {
            texts.addView(TextView(activity).apply {
                text = sub
                textSize = 11.5f
                typeface = NexoUi.regular(activity)
                setTextColor(p.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_nav_menu)
            imageTintList = ColorStateList.valueOf(p.muted)
            scaleType = ImageView.ScaleType.CENTER
            scaleX = 0.82f
            scaleY = 0.82f
            contentDescription = "Opciones para «${conv.title}»"
            background = ThemeManager.ripple(activity, null, 20f)
            setOnClickListener { NexoAiHistorySheet.options(activity, conv) }
        }, LinearLayout.LayoutParams(dp(38f), dp(42f)))
        return row
    }
}
