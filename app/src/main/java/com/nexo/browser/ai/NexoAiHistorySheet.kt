package com.nexo.browser.ai

import android.app.Activity
import android.app.AlertDialog
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
 * Historial de Nexo AI: buscar, abrir, renombrar y eliminar conversaciones, o borrarlo todo.
 * Todo vive solo en este dispositivo (`files/nexo_ai/conversations.json`).
 */
object NexoAiHistorySheet {

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        lateinit var sheet: Dialog
        var query = ""

        val subtitle = TextView(activity).apply {
            textSize = 12.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            // Alineado con el título de la cabecera (20 dp) dentro del cuerpo (12 dp).
            setPadding(dp(20f - NexoUi.Tokens.SHEET_PADDING), 0, 0, 0)
        }
        val search = EditText(activity).apply {
            hint = "Buscar en conversaciones"
            textSize = 15f
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
            confirm(activity, "¿Borrar todo el historial de Nexo AI?",
                "Se eliminarán todas las conversaciones guardadas en este dispositivo. No se puede deshacer.", "Borrar todo") {
                NexoAiController.deleteAllConversations()
                Toastish.show(activity, "Historial de Nexo AI borrado.")
            }
        }

        fun render(snap: NexoAiModels.ControllerSnapshot) {
            if (activity.isFinishing || activity.isDestroyed) return
            val saved = snap.conversations.count { it.messages.isNotEmpty() }
            subtitle.text = when (saved) {
                0 -> "Sin conversaciones guardadas"
                1 -> "1 conversación · solo en este dispositivo"
                else -> "$saved conversaciones · solo en este dispositivo"
            }
            clearAll.visibility = if (saved > 0) View.VISIBLE else View.GONE
            search.visibility = if (saved > 3 || query.isNotEmpty()) View.VISIBLE else View.GONE
            list.removeAllViews()
            val sections = NexoAiHistory.sections(snap.conversations, query, System.currentTimeMillis())
            if (sections.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = if (query.isBlank()) "Tus conversaciones con Nexo AI aparecerán aquí." else "Nada coincide con «${query.trim()}»."
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
        body.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })
        body.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })
        body.addView(list)
        body.addView(clearAll, NexoUi.buttonParams(activity, 16f))

        val observer: (NexoAiModels.ControllerSnapshot) -> Unit = { render(it) }
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Historial de Nexo AI"), body))
        NexoAiController.observe(observer)
        sheet.setOnDismissListener { NexoAiController.unobserve(observer) }
    }

    private fun row(activity: Activity, conv: NexoAiModels.AiConversation, active: Boolean, onOpen: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60f)
            setPadding(dp(12f), dp(8f), dp(4f), dp(8f))
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
        texts.addView(TextView(activity).apply {
            text = (if (conv.hasInFlight) "● " else "") + conv.title
            textSize = 15f
            typeface = NexoUi.medium(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(activity).apply {
            val questions = conv.messages.count { it.role == NexoAiModels.Role.USER }
            text = listOfNotNull(
                conv.hostHint.takeIf { it.isNotBlank() },
                if (questions == 1) "1 pregunta" else "$questions preguntas",
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(conv.updatedAtMs))
            ).joinToString(" · ")
            textSize = 12.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_delete)
            imageTintList = ColorStateList.valueOf(p.muted)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Eliminar «${conv.title}»"
            background = ThemeManager.ripple(activity, null, 24f)
            setOnClickListener { confirmDelete(activity, conv) }
        }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        return row
    }

    /** Pulsación larga en una conversación (también desde el carril del chat). */
    fun options(activity: Activity, conv: NexoAiModels.AiConversation) {
        lateinit var sheet: Dialog
        sheet = NexoSheets.show(activity, NexoSheets.column(
            activity,
            NexoSheets.header(activity, conv.title),
            NexoSheets.list(activity, listOf(
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
        val field = EditText(activity).apply {
            setText(conv.title)
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val wrap = LinearLayout(activity).apply {
            val pad = NexoUi.dp(activity, 20f)
            setPadding(pad, NexoUi.dp(activity, 8f), pad, 0)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(activity)
            .setTitle("Renombrar conversación")
            .setView(wrap)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar") { _, _ -> NexoAiController.renameConversation(conv.id, field.text.toString()) }
            .show()
    }

    fun confirm(activity: Activity, title: String, message: String, action: String, onConfirm: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(action) { _, _ -> onConfirm() }
            .show()
    }
}
