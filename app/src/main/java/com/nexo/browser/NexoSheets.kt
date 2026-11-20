package com.nexo.browser

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
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
import androidx.core.graphics.ColorUtils
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
 * Hojas inferiores Nexo UI 5.0:
 * superficies tonales limpias sin bordes excesivos, divisores inset alineados al texto,
 * cuadrícula de acciones equilibrada sin elementos huérfanos y soporte para hojas con
 * cabecera y barra inferior fijas (p. ej. Nexo AI).
 */
object NexoSheets {
    fun actionGroups(activity: Activity, groups: List<MenuSections.Group>, dismiss: () -> Unit): View =
        NexoUi.column(activity, horizontalDp = 16f).apply {
            val p = ThemeManager.palette(activity)
            val cardBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
            val stroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
            groups.forEach { group ->
                addView(NexoUi.section(activity, group.title))
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ThemeManager.rounded(activity, cardBg, NexoUi.Tokens.RADIUS_CARD, stroke)
                    clipToOutline = true
                }
                group.actions.forEachIndexed { idx, action ->
                    if (idx > 0) card.addView(NexoUi.insetDivider(activity))
                    card.addView(NexoUi.actionRow(activity, action.icon, action.label, action.subtitle, action.danger) {
                        dismiss(); action.onClick()
                    })
                }
                addView(card)
            }
        }

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun sheetBackground(activity: Activity): GradientDrawable {
        val p = ThemeManager.palette(activity)
        val radius = dp(activity, NexoUi.Tokens.RADIUS_SHEET).toFloat()
        val sheetColor = when {
            p.isAmoled -> 0xFF0C0E13.toInt()
            p.isDark -> p.surfaceContainer
            else -> p.surfaceContainerLow
        }
        return GradientDrawable().apply {
            setColor(sheetColor)
            if (p.isAmoled) setStroke(dp(activity, 1f).coerceAtLeast(1), ThemeManager.hairline(p))
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
    }

    private fun dragHandle(activity: Activity): View {
        val p = ThemeManager.palette(activity)
        val handleColor = ColorUtils.setAlphaComponent(p.muted, 110)
        return View(activity).apply {
            background = ThemeManager.rounded(activity, handleColor, 3f)
            contentDescription = "Arrastra hacia abajo para cerrar"
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36f), dp(activity, 4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(activity, 10f)
            }
        }
    }

    /**
     * Muestra [content] en una hoja anclada abajo con esquinas superiores de
     * 28dp. Se cierra tocando fuera, con Atrás o arrastrándola hacia abajo.
     */
    fun show(activity: Activity, content: View): Dialog {
        val p = ThemeManager.palette(activity)
        val dialog = Dialog(activity)
        WindowPrivacy.inherit(dialog.window, activity.window)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = DragDismissLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBackground(activity)
            setPadding(0, dp(activity, 10f), 0, dp(activity, 16f))
        }
        sheet.addView(dragHandle(activity))

        val scroll = MaxHeightScrollView(activity, (activity.resources.displayMetrics.heightPixels * 0.84f).toInt()).apply {
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
            sheet.setPadding(safe.left, dp(activity, 10f), safe.right, dp(activity, 16f) + bottom)
            val available = activity.window.decorView.height - safe.top - bottom - dp(activity, 64f)
            if (available > 0) scroll.limitHeight(available)
            WindowInsetsCompat.CONSUMED
        }
        configureAndShow(activity, dialog, sheet, p)
        return dialog
    }

    /**
     * Hoja moderna con cabecera fija, cuerpo desplazable y pie fijo (adaptada al teclado),
     * ideal para experiencias conversacionales como Nexo AI.
     */
    fun showFixed(
        activity: Activity,
        headerView: View,
        bodyContent: View,
        footerView: View? = null,
        heightRatio: Float = 0.86f
    ): Pair<Dialog, ScrollView> {
        val p = ThemeManager.palette(activity)
        val dialog = Dialog(activity)
        WindowPrivacy.inherit(dialog.window, activity.window)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val maxBodyPx = (activity.resources.displayMetrics.heightPixels * heightRatio).toInt()
        val sheet = DragDismissLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBackground(activity)
            setPadding(0, dp(activity, 10f), 0, dp(activity, 8f))
        }
        sheet.addView(dragHandle(activity))
        sheet.addView(headerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = MaxHeightScrollView(activity, maxBodyPx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = false
            addView(bodyContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (footerView != null) {
            sheet.addView(footerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        sheet.scrollable = scroll
        sheet.dimWindow = dialog.window
        sheet.onDismiss = { if (dialog.isShowing) runCatching { dialog.dismiss() } }

        dialog.setContentView(sheet)
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bottom = maxOf(safe.bottom, ime)
            sheet.setPadding(safe.left, dp(activity, 10f), safe.right, dp(activity, 8f) + bottom)
            val available = activity.window.decorView.height - safe.top - bottom - dp(activity, 180f)
            if (available > dp(activity, 120f)) scroll.limitHeight(available)
            WindowInsetsCompat.CONSUMED
        }
        configureAndShow(activity, dialog, sheet, p)
        return dialog to scroll
    }

    private fun configureAndShow(activity: Activity, dialog: Dialog, sheet: View, p: BrowserPalette) {
        val sheetWidth = minOf(activity.resources.displayMetrics.widthPixels, dp(activity, UiLayout.SHEET_MAX_DP.toFloat()))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setWindowAnimations(0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (p.isDark) 0.56f else 0.42f)
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
    }

    /** Encabezado de hoja: título, subtítulo y píldora de estado opcionales. */
    fun header(
        activity: Activity,
        title: String,
        subtitle: String? = null,
        badge: String? = null,
        badgeTone: NexoUi.Tone = NexoUi.Tone.ACCENT
    ): View {
        val p = ThemeManager.palette(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20f), dp(activity, 4f), dp(activity, 20f), dp(activity, 12f))
            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(activity).apply {
                text = title
                textSize = 18.5f
                letterSpacing = -0.01f
                setTextColor(p.text)
                typeface = NexoUi.bold(activity)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (!badge.isNullOrBlank()) {
                topRow.addView(NexoUi.statusPill(activity, badge, badgeTone), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(activity, 8f) })
            }
            addView(topRow)
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(activity).apply {
                    text = subtitle
                    textSize = 12.5f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.muted)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(0, dp(activity, 2f), 0, 0)
                })
            }
        }
    }

    /** Tarjetas primarias superiores: limpias, tonales y sin exceso de contornos. */
    fun primaryActions(activity: Activity, actions: List<SheetAction>, dismiss: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        val compactVertical = actions.size >= 3
        val tileBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
        val tileStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(activity, 16f), 0, dp(activity, 16f), dp(activity, 12f))
            actions.forEachIndexed { index, action ->
                val cell = LinearLayout(activity).apply {
                    orientation = if (compactVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    gravity = if (compactVertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
                    minimumHeight = dp(activity, if (compactVertical) 80f else 72f)
                    val hPad = dp(activity, if (compactVertical) 8f else 12f)
                    val vPad = dp(activity, 11f)
                    setPadding(hPad, vPad, hPad, vPad)
                    background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, tileBg, 18f, tileStroke), 18f)
                    isClickable = true; isFocusable = true; contentDescription = action.label
                    setOnClickListener { dismiss(); action.onClick() }
                    addView(ImageView(activity).apply {
                        setImageResource(action.icon)
                        imageTintList = ColorStateList.valueOf(if (action.danger) p.error.onContainer else p.onAccentSoft)
                        setPadding(dp(activity, 9f), dp(activity, 9f), dp(activity, 9f), dp(activity, 9f))
                        background = ThemeManager.rounded(activity, if (action.danger) p.error.container else p.accentSoft, 13f)
                    }, LinearLayout.LayoutParams(dp(activity, 38f), dp(activity, 38f)).apply {
                        if (compactVertical) bottomMargin = dp(activity, 7f) else marginEnd = dp(activity, 10f)
                    })
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = if (compactVertical) Gravity.CENTER_HORIZONTAL else Gravity.START
                        addView(TextView(activity).apply {
                            text = action.label
                            textSize = if (compactVertical) 12f else 12.5f
                            gravity = if (compactVertical) Gravity.CENTER else Gravity.START
                            typeface = NexoUi.semiBold(activity)
                            setTextColor(p.text)
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        })
                        addView(TextView(activity).apply {
                            text = action.subtitle ?: if (index == 0) "Inicio rápido" else "Sin historial"
                            textSize = 10.5f
                            typeface = NexoUi.regular(activity)
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

    /** Cuadrícula de acciones tonales; evita elementos huérfanos en filas de 4 en móviles estándar. */
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
        val fontScale = activity.resources.configuration.fontScale
        val computed = UiLayout.actionColumns(available.toFloat(), fontScale)
        val count = if (columns == 4 && actions.size == 4 && available >= 320 && fontScale <= 1.15f) {
            4
        } else {
            minOf(columns, computed)
        }

        val idleBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
        val idleStroke = if (p.isAmoled || !p.isDark) ThemeManager.hairline(p) else null

        actions.chunked(count).forEach { rowActions ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = count.toFloat()
            }
            rowActions.forEach { action ->
                val tint = when {
                    action.danger -> p.danger
                    action.active -> p.onAccentSoft
                    else -> p.text
                }
                val cellBg = if (action.active) p.accentSoft else idleBg
                val cellStroke = if (action.active) ColorUtils.setAlphaComponent(p.accent, 110) else idleStroke

                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    minimumHeight = dp(activity, 68f)
                    setPadding(dp(activity, 4f), dp(activity, 11f), dp(activity, 4f), dp(activity, 10f))
                    background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, cellBg, 16f, cellStroke), 16f)
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
                    textSize = 11.5f
                    typeface = if (action.active) NexoUi.semiBold(activity) else NexoUi.medium(activity)
                    gravity = Gravity.CENTER
                    setTextColor(when {
                        action.danger -> p.danger
                        action.active -> p.onAccentSoft
                        else -> p.text
                    })
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
                setPadding(dp(activity, 16f), dp(activity, 9f), dp(activity, 16f), dp(activity, 9f))
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
                imageTintList = ColorStateList.valueOf(if (action.danger) p.danger else if (action.active) p.accent else p.muted)
                layoutParams = LinearLayout.LayoutParams(dp(activity, 22f), dp(activity, 22f)).apply {
                    marginEnd = dp(activity, 16f)
                }
            })
            row.addView(TextView(activity).apply {
                text = action.label
                textSize = 15f
                typeface = NexoUi.medium(activity)
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
