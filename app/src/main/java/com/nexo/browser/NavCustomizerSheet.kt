package com.nexo.browser

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hoja inferior de personalización de la barra de navegación (Clásico, Compacto,
 * Personalizado con selección/orden de acciones y auto-ocultación al desplazarse).
 */
object NavCustomizerSheet {
    fun show(activity: Activity, onChanged: () -> Unit) {
        var mode = NavConfig.mode(activity)
        var actions = NavConfig.actions(activity)
        var autoHide = BrowserPrefs.autoHideBars(activity)

        val body = NexoUi.column(activity)
        val dynamic = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(dynamic)

        fun rebuild() {
            dynamic.removeAllViews()
            val p = ThemeManager.palette(activity)

            dynamic.addView(NexoUi.section(activity, "Modo de barra"))
            val chips = NavConfig.Mode.entries.map { m ->
                NexoUi.chip(activity, m.label, selected = m == mode) {
                    mode = m
                    NavConfig.setMode(activity, m)
                    onChanged()
                    rebuild()
                }
            }
            dynamic.addView(NexoUi.chipRow(activity, chips))
            dynamic.addView(NexoUi.note(activity, mode.description))

            dynamic.addView(NexoUi.section(activity, "Comportamiento"))
            dynamic.addView(NexoUi.card(activity).apply {
                addView(NexoUi.switchRow(
                    activity,
                    "Ocultar barra al desplazarse",
                    "Oculta los controles inferiores al bajar y los recupera al subir (nunca al editar la URL)",
                    autoHide
                ) { enabled ->
                    autoHide = enabled
                    BrowserPrefs.setAutoHideBars(activity, enabled)
                    onChanged()
                })
            })

            if (mode == NavConfig.Mode.CUSTOM) {
                dynamic.addView(NexoUi.section(activity, "Acciones visibles (${actions.size}/${NavConfig.MAX_ACTIONS})"))
                val activeCard = NexoUi.card(activity).apply {
                    setPadding(NexoUi.dp(activity, 8f), NexoUi.dp(activity, 4f), NexoUi.dp(activity, 8f), NexoUi.dp(activity, 4f))
                }
                actions.forEachIndexed { index, action ->
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = NexoUi.dp(activity, 52f)
                        setPadding(NexoUi.dp(activity, 8f), NexoUi.dp(activity, 4f), NexoUi.dp(activity, 4f), NexoUi.dp(activity, 4f))
                    }
                    row.addView(ImageView(activity).apply {
                        setImageResource(NavConfig.iconFor(action))
                        imageTintList = ColorStateList.valueOf(p.accent)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(NexoUi.dp(activity, 20f), NexoUi.dp(activity, 20f)).apply {
                        marginEnd = NexoUi.dp(activity, 12f)
                    })
                    row.addView(TextView(activity).apply {
                        text = action.label
                        textSize = 14.5f
                        setTextColor(p.text)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                    fun smallBtn(icon: Int, desc: String, enabled: Boolean, onClick: () -> Unit) =
                        ImageButton(activity).apply {
                            setImageResource(icon)
                            contentDescription = desc
                            isEnabled = enabled
                            ThemeManager.styleIconButton(activity, this)
                            setOnClickListener { onClick() }
                        }

                    val size = NexoUi.dp(activity, 44f)
                    row.addView(smallBtn(R.drawable.ic_nav_up, "Subir ${action.label}", index > 0) {
                        actions = NavConfig.move(actions, action, -1)
                        NavConfig.setActions(activity, actions)
                        onChanged()
                        rebuild()
                    }, LinearLayout.LayoutParams(size, size))
                    row.addView(smallBtn(R.drawable.ic_nav_down, "Bajar ${action.label}", index < actions.lastIndex) {
                        actions = NavConfig.move(actions, action, 1)
                        NavConfig.setActions(activity, actions)
                        onChanged()
                        rebuild()
                    }, LinearLayout.LayoutParams(size, size))
                    row.addView(smallBtn(R.drawable.ic_menu_close, "Quitar ${action.label}", actions.size > NavConfig.MIN_ACTIONS) {
                        actions = NavConfig.toggle(actions, action)
                        NavConfig.setActions(activity, actions)
                        onChanged()
                        rebuild()
                    }, LinearLayout.LayoutParams(size, size))
                    activeCard.addView(row)
                }
                dynamic.addView(activeCard)

                val available = NavConfig.Action.entries.filterNot { it in actions }
                if (available.isNotEmpty()) {
                    dynamic.addView(NexoUi.section(activity, "Añadir a la barra"))
                    val addChips = available.map { action ->
                        NexoUi.chip(activity, "+ ${action.label}", selected = false) {
                            if (actions.size < NavConfig.MAX_ACTIONS) {
                                actions = NavConfig.toggle(actions, action)
                                NavConfig.setActions(activity, actions)
                                onChanged()
                                rebuild()
                            } else {
                                Toastish.show(activity, "Máximo ${NavConfig.MAX_ACTIONS} acciones en la barra.")
                            }
                        }
                    }
                    dynamic.addView(NexoUi.chipRow(activity, addChips))
                }

                dynamic.addView(NexoUi.button(activity, "Restablecer orden clásico") {
                    actions = NavConfig.DEFAULT_ACTIONS
                    NavConfig.setActions(activity, actions)
                    onChanged()
                    rebuild()
                }, NexoUi.buttonParams(activity, 14f))
            }
        }

        rebuild()
        NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Barra de navegación", "Elige el diseño que mejor se adapte a tu mano"),
                body
            )
        )
    }
}
