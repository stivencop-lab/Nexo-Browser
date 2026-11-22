package com.nexo.browser.ai

import android.app.Activity
import android.app.Dialog
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
import android.widget.TextView
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.SheetAction
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import java.text.DateFormat
import java.util.Date

/**
 * Gestor de historial de chats de Nexo AI:
 * crear nuevo chat, buscar entre títulos/sitios/mensajes, fijar chats importantes,
 * abrir cualquier conversación con extracto del último mensaje, renombrar y eliminar.
 * Todo vive localmente en este dispositivo (`files/nexo_ai/conversations.json`).
 */
object NexoAiHistorySheet {

    fun show(activity: Activity, hostHint: String = "") {
        if (activity.isFinishing || activity.isDestroyed) return
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        lateinit var sheet: Dialog
        var query = ""

        val subtitle = TextView(activity).apply {
            textSize = 12.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            setPadding(dp(20f - NexoUi.Tokens.SHEET_PADDING), 0, 0, 0)
        }

        val newChatBtn = NexoUi.button(activity, "+ Nuevo chat", primary = true) {
            NexoAiController.newConversation(hostHint)
            sheet.dismiss()
        }

        val search = EditText(activity).apply {
            hint = "Buscar chats, sitios o mensajes…"
            textSize = 14.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            minHeight = dp(48f)
            setPadding(dp(16f), 0, dp(16f), 0)
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_PILL)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
            compoundDrawablePadding = dp(10f)
            compoundDrawableTintList = ColorStateList.valueOf(p.muted)
        }

        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val clearAll = NexoUi.button(activity, "Borrar todo el historial", danger = true) {
            confirm(
                activity,
                "¿Borrar todo el historial de Nexo AI?",
                "Se eliminarán todas las conversaciones guardadas en este dispositivo. No se puede deshacer.",
                "Borrar todo"
            ) {
                NexoAiController.deleteAllConversations()
                Toastish.show(activity, "Historial de Nexo AI borrado.")
            }
        }

        fun render(snap: NexoAiModels.ControllerSnapshot) {
            if (activity.isFinishing || activity.isDestroyed) return
            val saved = snap.conversations.count { it.messages.isNotEmpty() }
            subtitle.text = when (saved) {
                0 -> "Sin conversaciones guardadas todavía"
                1 -> "1 chat guardado · solo en este dispositivo"
                else -> "$saved chats guardados · solo en este dispositivo"
            }
            clearAll.visibility = if (saved > 0) View.VISIBLE else View.GONE
            search.visibility = if (saved > 0 || query.isNotEmpty()) View.VISIBLE else View.GONE
            list.removeAllViews()
            val sections = NexoAiHistory.sections(snap.conversations, query, System.currentTimeMillis())
            if (sections.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = if (query.isBlank()) "Tus chats con Nexo AI aparecerán aquí para retomarlos cuando quieras." else "Nada coincide con «${query.trim()}»."
                    textSize = 14f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.muted)
                    gravity = Gravity.CENTER
                    setPadding(dp(16f), dp(28f), dp(16f), dp(28f))
                })
                return
            }
            for ((group, convs) in sections) {
                list.addView(NexoUi.section(activity, group.label))
                for (conv in convs) {
                    list.addView(row(activity, conv, conv.id == snap.activeConversationId) {
                        NexoAiController.selectConversation(conv.id)
                        sheet.dismiss()
                    })
                }
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render(NexoAiController.snapshot())
            }
        })

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(NexoUi.Tokens.SHEET_PADDING), 0, dp(NexoUi.Tokens.SHEET_PADDING), dp(8f))
        }
        body.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) })
        body.addView(newChatBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) })
        body.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) })
        body.addView(list)
        body.addView(clearAll, NexoUi.buttonParams(activity, 16f))

        val observer: (NexoAiModels.ControllerSnapshot) -> Unit = { render(it) }
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Chats de Nexo AI"), body))
        NexoAiController.observe(observer)
        sheet.setOnDismissListener { NexoAiController.unobserve(observer) }
    }

    private fun row(activity: Activity, conv: NexoAiModels.AiConversation, active: Boolean, onOpen: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(68f)
            setPadding(dp(12f), dp(10f), dp(4f), dp(10f))
            background = ThemeManager.ripple(
                activity,
                if (active) ThemeManager.rounded(activity, NexoSheets.groupFill(p), 16f) else null,
                16f
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpen() }
            setOnLongClickListener {
                options(activity, conv)
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
            }, LinearLayout.LayoutParams(dp(14f), dp(14f)).apply { marginEnd = dp(5f) })
        }
        titleRow.addView(TextView(activity).apply {
            text = (if (conv.hasInFlight) "● " else "") + conv.title
            textSize = 15f
            typeface = NexoUi.medium(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        texts.addView(titleRow)

        val snippet = NexoAiHistory.lastSnippet(conv, maxChars = 90)
        if (snippet.isNotBlank()) {
            texts.addView(TextView(activity).apply {
                text = snippet
                textSize = 13f
                typeface = NexoUi.regular(activity)
                setTextColor(p.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, 0)
            })
        }

        texts.addView(TextView(activity).apply {
            val questions = conv.messages.count { it.role == NexoAiModels.Role.USER }
            val shortModel = conv.model.substringAfterLast('/').take(18).takeIf { it.isNotBlank() }
            text = listOfNotNull(
                conv.hostHint.takeIf { it.isNotBlank() },
                shortModel,
                if (questions == 1) "1 turno" else "$questions turnos",
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(conv.updatedAtMs))
            ).joinToString(" · ")
            textSize = 11.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3f), 0, 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_nav_menu)
            imageTintList = ColorStateList.valueOf(p.muted)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Opciones para «${conv.title}»"
            background = ThemeManager.ripple(activity, null, 24f)
            setOnClickListener { options(activity, conv) }
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        return row
    }

    /** Menú de acciones por chat (fijar, renombrar, eliminar). */
    fun options(activity: Activity, conv: NexoAiModels.AiConversation) {
        lateinit var sheet: Dialog
        sheet = NexoSheets.show(activity, NexoSheets.column(
            activity,
            NexoSheets.header(activity, conv.title),
            NexoSheets.list(activity, listOf(
                SheetAction(R.drawable.ic_menu_pin, if (conv.pinned) "Desfijar chat" else "Fijar arriba") {
                    NexoAiController.togglePinConversation(conv.id)
                    Toastish.show(activity, if (conv.pinned) "Chat desfijado." else "Chat fijado arriba.")
                },
                SheetAction(R.drawable.ic_menu_edit, "Renombrar") { rename(activity, conv) },
                SheetAction(R.drawable.ic_menu_delete, "Eliminar conversación", danger = true) { confirmDelete(activity, conv) }
            )) { sheet.dismiss() }
        ))
    }

    fun confirmDelete(activity: Activity, conv: NexoAiModels.AiConversation) {
        confirm(activity, "¿Eliminar esta conversación?", "«${conv.title}» se borrará de este dispositivo.", "Eliminar") {
            NexoAiController.deleteConversation(conv.id)
            Toastish.show(activity, "Conversación eliminada.")
        }
    }

    private fun rename(activity: Activity, conv: NexoAiModels.AiConversation) {
        if (activity.isFinishing || activity.isDestroyed) return
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        lateinit var dialog: Dialog
        val field = EditText(activity).apply {
            setText(conv.title)
            setSelection(text.length)
            isSingleLine = true
            textSize = 15f
            typeface = NexoUi.regular(activity)
            setTextColor(p.text)
            setHintTextColor(p.muted)
            minHeight = dp(48f)
            setPadding(dp(14f), 0, dp(14f), 0)
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val body = NexoUi.column(activity)
        body.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(NexoUi.button(activity, "Guardar nombre", primary = true) {
            NexoAiController.renameConversation(conv.id, field.text.toString())
            dialog.dismiss()
        }, NexoUi.buttonParams(activity, 14f))
        body.addView(NexoUi.button(activity, "Cancelar", primary = false) {
            dialog.dismiss()
        }, NexoUi.buttonParams(activity, 6f))
        dialog = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Renombrar chat"), body))
    }

    fun confirm(activity: Activity, title: String, message: String, action: String, onConfirm: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        lateinit var dialog: Dialog
        val body = NexoUi.column(activity)
        body.addView(NexoUi.note(activity, message))
        body.addView(NexoUi.button(activity, action, danger = true) {
            dialog.dismiss()
            onConfirm()
        }, NexoUi.buttonParams(activity, 14f))
        body.addView(NexoUi.button(activity, "Cancelar", primary = false) {
            dialog.dismiss()
        }, NexoUi.buttonParams(activity, 6f))
        dialog = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, title), body))
    }
}
