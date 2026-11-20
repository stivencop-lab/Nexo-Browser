package com.nexo.browser

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Sistema de componentes y tokens Nexo UI 5.0:
 * tipografía unificada (Inter + JetBrains Mono), elevación tonal sin exceso de bordes,
 * píldoras estilizadas con objetivo táctil accesible de 48dp, tarjetas, métricas y controles.
 */
object NexoUi {
    /** Tokens unificados de Nexo UI 5.0. */
    object Tokens {
        const val RADIUS_SM = 12f
        const val RADIUS_MD = 16f
        const val RADIUS_CARD = 20f
        const val RADIUS_PILL = 24f
        const val RADIUS_SHEET = 28f

        const val TEXT_DISPLAY = 24f
        const val TEXT_TITLE_LG = 19f
        const val TEXT_TITLE = 16.5f
        const val TEXT_BODY = 14.5f
        const val TEXT_SECONDARY = 12.5f
        const val TEXT_LABEL = 11.5f
        const val TEXT_MICRO = 10.5f

        const val ANIM_FAST_MS = 120L
        const val ANIM_STD_MS = 180L
    }

    /** Tono semántico unificado para insignias, estados y métricas. */
    enum class Tone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER, INFO }

    fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /** Respeta la preferencia del sistema para reducir o desactivar animaciones. */
    fun reducedMotion(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** Devuelve 0 ms si el usuario desactivó animaciones en el sistema. */
    fun animDuration(context: Context, baseMs: Long): Long =
        if (reducedMotion(context)) 0L else baseMs

    /** Devuelve (fondo contenedor, color de texto) con contraste AA/AAA garantizado. */
    fun toneColors(p: BrowserPalette, tone: Tone): Pair<Int, Int> = when (tone) {
        Tone.NEUTRAL -> p.surfaceContainerHighest to p.text
        Tone.ACCENT -> p.primary.container to p.primary.onContainer
        Tone.SUCCESS -> p.success.container to p.success.onContainer
        Tone.WARNING -> p.warning.container to p.warning.onContainer
        Tone.DANGER -> p.error.container to p.error.onContainer
        Tone.INFO -> p.info.container to p.info.onContainer
    }

    fun regular(activity: Activity): Typeface = ThemeManager.fontRegular(activity)
    fun medium(activity: Activity): Typeface = ThemeManager.fontMedium(activity)
    fun semiBold(activity: Activity): Typeface = ThemeManager.fontSemiBold(activity)
    fun bold(activity: Activity): Typeface = ThemeManager.fontBold(activity)
    fun mono(activity: Activity): Typeface = ThemeManager.fontMono(activity)

    /** Contenedor vertical con los márgenes laterales de las hojas. */
    fun column(activity: Activity, horizontalDp: Float = 18f): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, horizontalDp), 0, dp(activity, horizontalDp), dp(activity, 6f))
        }

    /** Título de sección pequeño, en mayúsculas con tracking limpio. */
    fun section(activity: Activity, label: String): TextView {
        val p = ThemeManager.palette(activity)
        return TextView(activity).apply {
            text = label.uppercase()
            textSize = 11f
            letterSpacing = 0.085f
            typeface = bold(activity)
            setTextColor(p.muted)
            setPadding(dp(activity, 4f), dp(activity, 16f), 0, dp(activity, 8f))
        }
    }

    /** Texto de ayuda breve. */
    fun note(activity: Activity, value: String): TextView {
        val p = ThemeManager.palette(activity)
        return TextView(activity).apply {
            text = value
            textSize = Tokens.TEXT_SECONDARY
            typeface = regular(activity)
            setLineSpacing(0f, 1.2f)
            setTextColor(p.muted)
            setPadding(dp(activity, 4f), dp(activity, 6f), dp(activity, 4f), dp(activity, 4f))
        }
    }

    /** Separador hairline de 1dp respetando el contorno sutil de la paleta. */
    fun divider(activity: Activity, horizontalMarginDp: Float = 16f): View =
        View(activity).apply {
            setBackgroundColor(ThemeManager.hairline(ThemeManager.palette(activity)))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 1f).coerceAtLeast(1)
            ).apply {
                marginStart = dp(activity, horizontalMarginDp)
                marginEnd = dp(activity, horizontalMarginDp)
            }
        }

    /** Separador inset alineado con el texto de filas con icono (68dp start). */
    fun insetDivider(activity: Activity): View =
        View(activity).apply {
            setBackgroundColor(ThemeManager.hairline(ThemeManager.palette(activity)))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(activity, 1f).coerceAtLeast(1)
            ).apply {
                marginStart = dp(activity, 68f)
                marginEnd = dp(activity, 16f)
            }
        }

    /** Píldora compacta de estado semántico (p. ej. "HTTPS", "Activa", "Duplicada ×2"). */
    fun statusPill(activity: Activity, label: String, tone: Tone = Tone.NEUTRAL): TextView {
        val p = ThemeManager.palette(activity)
        val (bg, fg) = toneColors(p, tone)
        return TextView(activity).apply {
            text = label
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = bold(activity)
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(activity, 9f), dp(activity, 3.5f), dp(activity, 9f), dp(activity, 3.5f))
            background = ThemeManager.rounded(activity, bg, 12f)
        }
    }

    /** Tarjeta compacta de métrica (valor tabular destacado + etiqueta). */
    fun metricTile(activity: Activity, value: String, label: String, tone: Tone = Tone.NEUTRAL): LinearLayout {
        val p = ThemeManager.palette(activity)
        val isNeutral = tone == Tone.NEUTRAL
        val (bg, fg) = if (isNeutral) {
            (if (p.isDark) p.surfaceContainerLow else p.surfaceContainerLowest) to p.text
        } else {
            toneColors(p, tone)
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ThemeManager.rounded(activity, bg, 18f, ThemeManager.hairline(p))
            setPadding(dp(activity, 10f), dp(activity, 12f), dp(activity, 10f), dp(activity, 12f))
            addView(TextView(activity).apply {
                text = value
                textSize = 19f
                typeface = bold(activity)
                fontFeatureSettings = "tnum"
                setTextColor(fg)
                maxLines = 1
            })
            addView(TextView(activity).apply {
                text = label
                textSize = 11.5f
                typeface = medium(activity)
                setTextColor(if (isNeutral) p.muted else fg)
                alpha = if (isNeutral) 1f else 0.88f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(activity, 2f), 0, 0)
            })
        }
    }

    /** Estado vacío o de error coherente con icono en burbuja, título, subtítulo y CTA opcional. */
    fun emptyState(
        activity: Activity,
        icon: Int,
        title: String,
        subtitle: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ): LinearLayout {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 28f), dp(activity, 36f), dp(activity, 28f), dp(activity, 36f))
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(p.onAccentSoft)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(activity, 15f), dp(activity, 15f), dp(activity, 15f), dp(activity, 15f))
                background = ThemeManager.rounded(activity, p.accentSoft, 20f)
            }, LinearLayout.LayoutParams(dp(activity, 56f), dp(activity, 56f)).apply {
                bottomMargin = dp(activity, 14f)
            })
            addView(TextView(activity).apply {
                text = title
                textSize = 16.5f
                typeface = semiBold(activity)
                gravity = Gravity.CENTER
                setTextColor(p.text)
            })
            addView(TextView(activity).apply {
                text = subtitle
                textSize = 13.5f
                typeface = regular(activity)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
                setTextColor(p.muted)
                setPadding(0, dp(activity, 6f), 0, 0)
            })
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                addView(button(activity, actionLabel, primary = true) { onAction() }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 18f) })
            }
        }
    }

    /** Barra de búsqueda tipo píldora tonal con icono y botón de limpieza instantánea. */
    fun searchBar(
        activity: Activity,
        hint: String,
        initial: String = "",
        onTextChanged: (String) -> Unit
    ): Pair<LinearLayout, EditText> {
        val p = ThemeManager.palette(activity)
        val fill = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainer
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 48f)
            background = ThemeManager.rounded(activity, fill, 24f, ThemeManager.hairline(p))
            setPadding(dp(activity, 14f), 0, dp(activity, 6f), 0)
        }
        container.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(p.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(activity, 18f), dp(activity, 18f)).apply {
            marginEnd = dp(activity, 10f)
        })
        lateinit var clearBtn: ImageButton
        val edit = EditText(activity).apply {
            this.hint = hint
            setText(initial)
            setSingleLine(true)
            textSize = 14.5f
            typeface = regular(activity)
            background = null
            setTextColor(p.text)
            setHintTextColor(p.muted)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
            minHeight = dp(activity, 48f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString().orEmpty()
                    clearBtn.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                    onTextChanged(value)
                }
            })
        }
        clearBtn = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Limpiar búsqueda"
            visibility = if (initial.isEmpty()) View.GONE else View.VISIBLE
            ThemeManager.styleIconButton(activity, this)
            setOnClickListener { edit.setText("") }
        }
        container.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(clearBtn, LinearLayout.LayoutParams(dp(activity, 40f), dp(activity, 40f)))
        return container to edit
    }

    /** Tarjeta tonal limpia: elevación por superficie con hairline sutil (sin borde pesado). */
    fun card(activity: Activity, paddingDp: Float = 16f): LinearLayout {
        val p = ThemeManager.palette(activity)
        val fill = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
        val pad = dp(activity, paddingDp)
        val vPad = dp(activity, if (paddingDp >= 14f) paddingDp - 2f else paddingDp)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, fill, Tokens.RADIUS_CARD, ThemeManager.hairline(p))
            clipToOutline = true
            setPadding(pad, vPad, pad, vPad)
        }
    }

    /**
     * Píldora seleccionable estilizada (36dp visuales dentro de un objetivo táctil de 48dp).
     * Sin borde pesado en reposo; en estado seleccionado adopta el acento suave del tema.
     */
    fun chip(activity: Activity, label: String, selected: Boolean, dotColor: Int? = null, onClick: () -> Unit): TextView {
        val p = ThemeManager.palette(activity)
        val vInset = dp(activity, 6f)
        val fill = if (selected) p.accentSoft else p.surfaceContainerHigh
        val stroke = when {
            selected -> ColorUtils.setAlphaComponent(p.accent, 110)
            p.isAmoled -> ThemeManager.hairline(p)
            else -> null
        }
        val pillDrawable = InsetDrawable(ThemeManager.rounded(activity, fill, 18f, stroke), 0, vInset, 0, vInset)
        val maskDrawable = InsetDrawable(ThemeManager.rounded(activity, Color.WHITE, 18f), 0, vInset, 0, vInset)
        val rippleColor = ColorUtils.setAlphaComponent(if (selected) p.onAccentSoft else p.text, 30)

        return TextView(activity).apply {
            text = label
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(activity, 210f)
            gravity = Gravity.CENTER
            minHeight = dp(activity, 48f)
            minWidth = dp(activity, 48f)
            isSelected = selected
            typeface = if (selected) semiBold(activity) else medium(activity)
            setTextColor(if (selected) p.onAccentSoft else p.text)
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), pillDrawable, maskDrawable)
            setPadding(dp(activity, if (dotColor != null) 12f else 15f), vInset, dp(activity, 15f), vInset)
            if (dotColor != null) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(dotColor)
                        setSize(dp(activity, 8f), dp(activity, 8f))
                    }, null, null, null
                )
                compoundDrawablePadding = dp(activity, 7f)
            }
            isClickable = true
            isFocusable = true
            contentDescription = label + if (selected) " (seleccionado)" else ""
            setOnClickListener { onClick() }
        }
    }

    /** Fila horizontal desplazable de píldoras. */
    fun chipRow(activity: Activity, chips: List<View>, sidePaddingDp: Float = 0f): HorizontalScrollView {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(activity, sidePaddingDp), 0, dp(activity, sidePaddingDp), 0)
        }
        chips.forEachIndexed { index, chip ->
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = dp(activity, 8f)
            })
        }
        return HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(row)
        }
    }

    /** Campo de texto con etiqueta encima. */
    fun field(activity: Activity, label: String, hint: String, value: String, numeric: Boolean = false): Pair<View, EditText> {
        val p = ThemeManager.palette(activity)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(activity).apply {
            text = label
            textSize = 12f
            typeface = medium(activity)
            setTextColor(p.muted)
            setPadding(dp(activity, 6f), dp(activity, 6f), 0, dp(activity, 5f))
        })
        val edit = EditText(activity).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            textSize = 14.5f
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ThemeManager.styleEdit(activity, this)
            minHeight = dp(activity, 48f)
            setPadding(dp(activity, 16f), dp(activity, 8f), dp(activity, 16f), dp(activity, 8f))
        }
        box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box to edit
    }

    /** Dos vistas en la misma fila con pesos. */
    fun pair(activity: Activity, first: View, firstWeight: Float, second: View, secondWeight: Float): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, firstWeight))
            addView(second, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, secondWeight).apply {
                marginStart = dp(activity, 8f)
            })
        }

    /** Fila con título, descripción e interruptor. */
    fun switchRow(activity: Activity, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val p = ThemeManager.palette(activity)
        val toggle = Switch(activity).apply {
            isChecked = checked
            contentDescription = listOfNotNull(title, subtitle).joinToString(". ")
            minHeight = dp(activity, 48f)
            ThemeManager.styleSwitch(activity, this)
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 56f)
            setPadding(dp(activity, 12f), dp(activity, 8f), dp(activity, 12f), dp(activity, 8f))
            // The Switch is the single accessibility/checkable node for this row.
            setOnClickListener { toggle.toggle() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(TextView(activity).apply {
                    text = title
                    textSize = 14.5f
                    typeface = medium(activity)
                    setTextColor(p.text)
                })
                if (!subtitle.isNullOrBlank()) addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 12.5f
                    typeface = regular(activity)
                    setLineSpacing(0f, 1.15f)
                    setTextColor(p.muted)
                    setPadding(0, dp(activity, 2f), dp(activity, 12f), 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
        }
    }

    /** Fila de acción: icono en burbuja tonal, título, descripción opcional y chevron. */
    fun actionRow(
        activity: Activity,
        icon: Int,
        title: String,
        subtitle: String? = null,
        danger: Boolean = false,
        onClick: () -> Unit
    ): LinearLayout {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(activity, 64f)
            setPadding(dp(activity, 16f), dp(activity, 12f), dp(activity, 16f), dp(activity, 12f))
            background = ThemeManager.ripple(activity, null, 16f)
            isClickable = true
            isFocusable = true
            contentDescription = listOfNotNull(title, subtitle).joinToString(". ")
            setOnClickListener { onClick() }
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(if (danger) p.error.onContainer else p.onAccentSoft)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(activity, 10f), dp(activity, 10f), dp(activity, 10f), dp(activity, 10f))
                background = ThemeManager.rounded(activity, if (danger) p.error.container else p.accentSoft, 13f)
            }, LinearLayout.LayoutParams(dp(activity, 40f), dp(activity, 40f)).apply { marginEnd = dp(activity, 12f) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(TextView(activity).apply {
                    text = title; textSize = 14.5f; maxLines = 2
                    setTextColor(if (danger) p.danger else p.text)
                    typeface = semiBold(activity)
                })
                if (!subtitle.isNullOrBlank()) addView(TextView(activity).apply {
                    text = subtitle; textSize = 12.5f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    typeface = regular(activity)
                    setTextColor(p.muted); setPadding(0, dp(activity, 2f), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_nav_chevron)
                imageTintList = ColorStateList.valueOf(p.muted)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(activity, 20f), dp(activity, 20f)))
        }
    }

    /** Botón ancho (principal o tonal) con altura adaptable (mínimo 48dp). */
    fun button(activity: Activity, label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): Button =
        Button(activity).apply {
            text = label
            textSize = 14.5f
            minHeight = 0
            minimumHeight = 0
            ThemeManager.styleButton(activity, this, primary = primary, danger = danger)
            setOnClickListener { onClick() }
        }

    fun buttonParams(activity: Activity, topDp: Float = 10f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, topDp)
        }

    /** Pide un nombre en una hoja con campo y botón Guardar. */
    fun askText(
        activity: Activity,
        title: String,
        hint: String,
        initial: String = "",
        action: String = "Guardar",
        onSave: (String) -> Unit
    ) {
        val box = column(activity)
        lateinit var sheet: android.app.Dialog
        val (fieldView, edit) = field(activity, hint, hint, initial)
        box.addView(fieldView)
        box.addView(button(activity, action, primary = true) {
            val value = edit.text.toString()
            if (value.isBlank()) { edit.error = "Escribe un nombre."; return@button }
            sheet.dismiss(); onSave(value)
        }, buttonParams(activity, 16f))
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, title), box))
        sheet.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        edit.requestFocus()
        edit.setSelection(edit.text.length)
    }
}
