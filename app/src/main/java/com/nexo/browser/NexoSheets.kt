package com.nexo.browser

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Acción de una hoja: icono, texto y si está activa (resaltada). */
data class SheetAction(
    val icon: Int,
    val label: String,
    val active: Boolean = false,
    val danger: Boolean = false,
    val subtitle: String? = null,
    val checked: Boolean? = null,
    val onClick: () -> Unit
)

/**
 * Hojas inferiores redondeadas (estilo de los navegadores actuales) que
 * sustituyen al menú emergente y a los diálogos de lista del sistema.
 */
object NexoSheets {
    fun actionGroups(activity: Activity, groups: List<MenuSections.Group>, dismiss: () -> Unit): View =
        NexoUi.column(activity, horizontalDp = 16f).apply {
            groups.forEach { group ->
                addView(NexoUi.section(activity, group.title))
                addView(NexoUi.card(activity).apply {
                    setPadding(0, 0, 0, 0)
                    group.actions.forEach { action ->
                        addView(NexoUi.actionRow(activity, action.icon, action.label, action.subtitle, action.danger) {
                            dismiss(); action.onClick()
                        })
                    }
                })
            }
        }
    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /**
     * Muestra [content] en una hoja anclada abajo con esquinas superiores de
     * 28dp. Se cierra tocando fuera, con Atrás o arrastrándola hacia abajo.
     */
    fun show(activity: Activity, content: View): Dialog {
        val p = ThemeManager.palette(activity)
        val dialog = Dialog(activity)
        WindowPrivacy.inherit(dialog.window, activity.window)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val radius = dp(activity, 28f).toFloat()
        val sheet = DragDismissLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.surfaceContainerHigh)
                if (p.isAmoled) setStroke(dp(activity, 1f), p.outlineVariant)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            setPadding(0, dp(activity, 10f), 0, dp(activity, 16f))
        }

        // Asa superior: indica que la hoja se puede arrastrar.
        sheet.addView(
            View(activity).apply {
                background = ThemeManager.rounded(activity, p.outline, 3f)
                contentDescription = "Arrastra hacia abajo para cerrar"
                layoutParams = LinearLayout.LayoutParams(dp(activity, 36f), dp(activity, 4f)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(activity, 10f)
                }
            }
        )

        val scroll = MaxHeightScrollView(activity, (activity.resources.displayMetrics.heightPixels * 0.82f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        sheet.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        sheet.scrollable = scroll
        sheet.dimWindow = dialog.window
        sheet.onDismiss = { if (dialog.isShowing) runCatching { dialog.dismiss() } }

        dialog.setContentView(sheet)
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val bottom = maxOf(safe.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            sheet.setPadding(safe.left, dp(activity, 8f), safe.right, dp(activity, 16f) + bottom)
            val available = activity.window.decorView.height - safe.top - bottom - dp(activity, 64f)
            if (available > 0) scroll.limitHeight(available)
            WindowInsetsCompat.CONSUMED
        }
        val sheetWidth = minOf(activity.resources.displayMetrics.widthPixels, dp(activity, UiLayout.SHEET_MAX_DP.toFloat()))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setWindowAnimations(0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.45f)
            navigationBarColor = Color.TRANSPARENT
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !p.isDark
                isAppearanceLightNavigationBars = !p.isDark
            }
        }
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        ViewCompat.requestApplyInsets(sheet)
        return dialog
    }

    /** Encabezado de hoja: título y subtítulo opcionales. */
    fun header(activity: Activity, title: String, subtitle: String? = null): View {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 24f), dp(activity, 4f), dp(activity, 24f), dp(activity, 12f))
            addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTextColor(p.text)
                typeface = Typeface.create(activity.resources.getFont(R.font.inter_variable), Typeface.BOLD)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(p.muted)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(0, dp(activity, 2f), 0, 0)
                })
            }
        }
    }

    /** Tarjetas primarias superiores: horizontales para 2 acciones, mosaicos verticales para 3 o más. */
    fun primaryActions(activity: Activity, actions: List<SheetAction>, dismiss: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        val compactVertical = actions.size >= 3
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(activity, 16f), 0, dp(activity, 16f), dp(activity, 12f))
            actions.forEachIndexed { index, action ->
                val cell = LinearLayout(activity).apply {
                    orientation = if (compactVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    gravity = if (compactVertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                    minimumHeight = dp(activity, if (compactVertical) 76f else 72f)
                    val hPad = dp(activity, if (compactVertical) 8f else 12f)
                    val vPad = dp(activity, 10f)
                    setPadding(hPad, vPad, hPad, vPad)
                    background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, p.surfaceContainerHighest, 16f, p.outlineVariant), 16f)
                    isClickable = true; isFocusable = true; contentDescription = action.label
                    setOnClickListener { dismiss(); action.onClick() }
                    addView(ImageView(activity).apply {
                        setImageResource(action.icon)
                        imageTintList = ColorStateList.valueOf(if (action.danger) p.error.onContainer else p.onAccentSoft)
                        setPadding(dp(activity, 8f), dp(activity, 8f), dp(activity, 8f), dp(activity, 8f))
                        background = ThemeManager.rounded(activity, if (action.danger) p.error.container else p.accentSoft, 12f)
                    }, LinearLayout.LayoutParams(dp(activity, 36f), dp(activity, 36f)).apply {
                        if (compactVertical) bottomMargin = dp(activity, 6f) else marginEnd = dp(activity, 9f)
                    })
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = if (compactVertical) Gravity.CENTER_HORIZONTAL else Gravity.START
                        addView(TextView(activity).apply {
                            text = action.label
                            textSize = if (compactVertical) 11.5f else 12f
                            gravity = if (compactVertical) Gravity.CENTER else Gravity.START
                            typeface = Typeface.create(activity.resources.getFont(R.font.inter_variable), Typeface.BOLD)
                            setTextColor(p.text)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        addView(TextView(activity).apply {
                            text = action.subtitle ?: if (index == 0) "Inicio rápido" else "Sin historial"
                            textSize = 10f
                            gravity = if (compactVertical) Gravity.CENTER else Gravity.START
                            setTextColor(p.muted)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(0, dp(activity, 2f), 0, 0)
                        })
                    }, if (compactVertical) {
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    } else {
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                }
                addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(activity, 8f)
                })
            }
        }
    }

    /** Cuadrícula de acciones con iconos en burbujas redondeadas. */
    fun grid(
        activity: Activity,
        actions: List<SheetAction>,
        columns: Int = 4,
        dismiss: () -> Unit
    ): View {
        val p = ThemeManager.palette(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12f), 0, dp(activity, 12f), 0)
        }

        val available = minOf(activity.resources.configuration.screenWidthDp, UiLayout.SHEET_MAX_DP) - 32
        val count = minOf(columns, UiLayout.actionColumns(available.toFloat(), activity.resources.configuration.fontScale))
        actions.chunked(count).forEach { rowActions ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = count.toFloat()
            }
            rowActions.forEach { action ->
                val tint = when {
                    action.danger -> p.danger
                    action.active -> if (p.isDark) p.text else p.accent
                    else -> p.text
                }


                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    minimumHeight = dp(activity, 68f)
                    setPadding(dp(activity, 4f), dp(activity, 12f), dp(activity, 4f), dp(activity, 10f))
                    background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, if (action.active) p.accentSoft else p.surface, 16f, p.outline), 16f)
                    isClickable = true
                    isFocusable = true
                    contentDescription = action.label
                    isSelected = action.checked ?: action.active
                    action.checked?.let { checked ->
                        ViewCompat.setAccessibilityDelegate(this, object : androidx.core.view.AccessibilityDelegateCompat() {
                            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.isCheckable = true
                                info.isChecked = checked
                            }
                        })
                    }
                    setOnClickListener {
                        dismiss()
                        action.onClick()
                    }
                }
                cell.addView(ImageView(activity).apply {
                    setImageResource(action.icon)
                    imageTintList = ColorStateList.valueOf(tint)
                    scaleType = ImageView.ScaleType.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(activity, 22f), dp(activity, 22f))
                })
                cell.addView(TextView(activity).apply {
                    text = action.label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(if (action.danger) p.danger else p.text)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(activity, 2f), dp(activity, 6f), dp(activity, 2f), 0)
                })
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(activity, 4f), dp(activity, 4f), dp(activity, 4f), dp(activity, 4f))
                })
            }
            root.addView(row)
        }
        return root
    }

    /** Lista vertical de acciones con icono a la izquierda. */
    fun list(
        activity: Activity,
        actions: List<SheetAction>,
        dismiss: () -> Unit
    ): View {
        val p = ThemeManager.palette(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 8f), 0, dp(activity, 8f), 0)
        }
        actions.forEach { action ->
            val color = if (action.danger) p.danger else if (action.active) p.accent else p.text
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 52f)
                setPadding(dp(activity, 16f), dp(activity, 8f), dp(activity, 16f), dp(activity, 8f))
                background = ThemeManager.ripple(activity, null, 16f)
                isClickable = true
                isFocusable = true
                contentDescription = action.label
                setOnClickListener {
                    dismiss()
                    action.onClick()
                }
            }
            row.addView(ImageView(activity).apply {
                setImageResource(action.icon)
                imageTintList = ColorStateList.valueOf(if (action.danger) p.danger else p.muted)
                layoutParams = LinearLayout.LayoutParams(dp(activity, 22f), dp(activity, 22f)).apply {
                    marginEnd = dp(activity, 18f)
                }
            })
            row.addView(TextView(activity).apply {
                text = action.label
                textSize = 15f
                setTextColor(color)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
        }
        return root
    }

    fun column(activity: Activity, vararg views: View): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { addView(it) }
        }
}

/** ScrollView con altura máxima: la hoja nunca tapa toda la pantalla. */
private class MaxHeightScrollView(context: android.content.Context, private var maxHeight: Int) : ScrollView(context) {
    fun limitHeight(value: Int) {
        if (value != maxHeight) { maxHeight = value; requestLayout() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (size > 0) minOf(size, maxHeight) else maxHeight
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
    }
}
