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
 * Hojas inferiores Nexo UI 7.0 «Liquid Craft»: lámina de radio superior continuo 34dp,
 * desenfoque real de lo que hay detrás (API 31+), apertura con muelle, tarjetas agrupadas
 * r:22 sin bordes, iconos desnudos y divisores inset. Soporta cabecera y pie fijos (Nexo AI).
 */
object NexoSheets {
    /** Listas agrupadas: tarjeta r:22 sin borde, iconos desnudos y divisores inset (sin rótulos). */
    fun actionGroups(activity: Activity, groups: List<MenuSections.Group>, dismiss: () -> Unit): View =
        NexoUi.column(activity, horizontalDp = NexoUi.Tokens.SHEET_PADDING).apply {
            val p = ThemeManager.palette(activity)
            val cardBg = groupFill(p)
            groups.forEachIndexed { index, group ->
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = ThemeManager.rounded(activity, cardBg, NexoUi.Tokens.RADIUS_CARD)
                    clipToOutline = true
                    contentDescription = group.title
                    NexoGlass.paperShadow(this, p)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) topMargin = dp(activity, 10f)
                    }
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

    /** Relleno de tarjetas agrupadas dentro de una hoja (un escalón sobre la hoja). */
    fun groupFill(p: BrowserPalette): Int = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest

    /** Lámina de la hoja: radio superior continuo de 34dp, sin bordes. */
    private fun sheetBackground(activity: Activity): GradientDrawable {
        val p = ThemeManager.palette(activity)
        val radius = dp(activity, NexoUi.Tokens.RADIUS_SHEET).toFloat()
        val sheetColor = if (p.isDark) p.surfaceContainerLow else p.background
        return GradientDrawable().apply {
            setColor(sheetColor)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }
    }

    private fun dragHandle(activity: Activity): View {
        val p = ThemeManager.palette(activity)
        val handleColor = ColorUtils.setAlphaComponent(p.muted, 96)
        return View(activity).apply {
            background = ThemeManager.rounded(activity, handleColor, 3f)
            contentDescription = "Arrastra hacia abajo para cerrar"
            layoutParams = LinearLayout.LayoutParams(dp(activity, 36f), dp(activity, 5f)).apply {
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

    /** Controlador de hoja adaptable de 3 estados (Minimizado / Parcial / Expandido) sin reconstruir vistas. */
    class AdaptiveSheetHandle(
        val dialog: Dialog,
        val scrollView: ScrollView,
        private val sheetLayout: DragDismissLayout,
        private val applyRatio: (Float, Boolean) -> Unit
    ) {
        var isExpanded: Boolean = false
            private set

        fun setExpanded(expanded: Boolean, animated: Boolean = true) {
            isExpanded = expanded
            applyRatio(if (expanded) 0.94f else 0.66f, animated)
        }

        fun dismissAnimated() {
            sheetLayout.dismissAnimated()
        }
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
        val handle = showAdaptiveFixed(
            activity = activity,
            headerView = headerView,
            bodyContent = bodyContent,
            footerView = footerView,
            initialExpanded = heightRatio >= 0.85f
        )
        return handle.dialog to handle.scrollView
    }

    /**
     * Hoja adaptable de 3 estados (Minimizado → Panel parcial → Pantalla expandida) con gestos
     * bidireccionales y animaciones suaves que respetan la reducción de movimiento.
     */
    fun showAdaptiveFixed(
        activity: Activity,
        headerView: View,
        bodyContent: View,
        footerView: View? = null,
        initialExpanded: Boolean = false,
        onExpandGesture: (() -> Unit)? = null
    ): AdaptiveSheetHandle {
        val p = ThemeManager.palette(activity)
        val dialog = Dialog(activity)
        WindowPrivacy.inherit(dialog.window, activity.window)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        var activeRatio = if (initialExpanded) 0.94f else 0.66f
        var lastSafeTop = 0
        var lastBottom = 0
        var heightAnimator: android.animation.ValueAnimator? = null

        val screenH = activity.resources.displayMetrics.heightPixels.coerceAtLeast(dp(activity, 520f))
        val initialBodyPx = ((screenH * activeRatio).toInt() - dp(activity, 150f)).coerceAtLeast(dp(activity, 200f))

        val sheet = DragDismissLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBackground(activity)
            setPadding(0, dp(activity, 10f), 0, dp(activity, 8f))
            baseDim = if (p.isDark) 0.5f else 0.28f
        }
        sheet.addView(dragHandle(activity))
        sheet.addView(headerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = MaxHeightScrollView(activity, initialBodyPx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(bodyContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sheet.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (footerView != null) {
            sheet.addView(footerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun computeTargetBodyHeight(ratio: Float): Int {
            val decorH = activity.window.decorView.height.takeIf { it > 0 } ?: screenH
            val usable = (decorH - lastSafeTop - lastBottom).coerceAtLeast(dp(activity, 320f))
            val chromeAllowance = dp(activity, if (footerView != null) 168f else 108f)
            return ((usable * ratio).toInt() - chromeAllowance).coerceAtLeast(dp(activity, 150f))
        }

        var activeSpring: androidx.dynamicanimation.animation.SpringAnimation? = null

        fun applyRatio(ratio: Float, animated: Boolean) {
            activeRatio = ratio
            val targetH = computeTargetBodyHeight(ratio)
            heightAnimator?.cancel()
            activeSpring?.cancel()
            activeSpring = null
            val startH = scroll.currentMaxHeight()
            if (!animated || !android.animation.ValueAnimator.areAnimatorsEnabled() || kotlin.math.abs(targetH - startH) < 8) {
                scroll.limitHeight(targetH, exact = true)
                return
            }
            // Muelle Liquid Craft sobre la altura (sin curvas accelerate/decelerate).
            heightAnimator = null
            val holder = androidx.dynamicanimation.animation.FloatValueHolder(startH.toFloat())
            activeSpring = androidx.dynamicanimation.animation.SpringAnimation(holder).apply {
                spring = androidx.dynamicanimation.animation.SpringForce(targetH.toFloat())
                    .setStiffness(NexoMotion.STIFFNESS).setDampingRatio(NexoMotion.DAMPING)
                addUpdateListener { _, value, _ -> scroll.limitHeight(value.toInt(), exact = true) }
                start()
            }
        }

        sheet.scrollable = scroll
        sheet.dimWindow = dialog.window
        sheet.onDismiss = { if (dialog.isShowing) runCatching { dialog.dismiss() } }
        sheet.onExpandUp = onExpandGesture

        dialog.setContentView(sheet)
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            lastSafeTop = safe.top
            lastBottom = maxOf(safe.bottom, ime)
            sheet.setPadding(safe.left, dp(activity, 10f), safe.right, dp(activity, 8f) + lastBottom)
            scroll.limitHeight(computeTargetBodyHeight(activeRatio), exact = true)
            WindowInsetsCompat.CONSUMED
        }
        configureAndShow(activity, dialog, sheet, p)
        scroll.limitHeight(computeTargetBodyHeight(activeRatio), exact = true)
        return AdaptiveSheetHandle(dialog, scroll, sheet, ::applyRatio)
    }

    private fun configureAndShow(activity: Activity, dialog: Dialog, sheet: DragDismissLayout, p: BrowserPalette) {
        val sheetWidth = minOf(activity.resources.displayMetrics.widthPixels, dp(activity, UiLayout.SHEET_MAX_DP.toFloat()))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setWindowAnimations(0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if (p.isDark) 0.5f else 0.28f)
            @Suppress("DEPRECATION")
            navigationBarColor = Color.TRANSPARENT
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = !p.isDark
                isAppearanceLightNavigationBars = !p.isDark
            }
        }
        dialog.setCanceledOnTouchOutside(true)
        NexoGlass.blurBehind(dialog, activity)
        dialog.show()
        dialog.window?.setLayout(sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        ViewCompat.requestApplyInsets(sheet)
        sheet.animateEnter()
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
                textSize = 20f
                letterSpacing = -0.025f
                setTextColor(p.text)
                typeface = NexoUi.semiBold(activity)
                isAccessibilityHeading = true
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

    /**
     * Píldoras táctiles de sesión (Nueva · Privada · Burner): icono desnudo y una sola palabra,
     * sin subtítulos. Radio concéntrico: 34 (hoja) − 12 (padding) = 22.
     */
    fun primaryActions(activity: Activity, actions: List<SheetAction>, dismiss: () -> Unit): View {
        val p = ThemeManager.palette(activity)
        val tileBg = groupFill(p)
        val radius = NexoUi.Tokens.inner(NexoUi.Tokens.RADIUS_SHEET, NexoUi.Tokens.SHEET_PADDING)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(activity, NexoUi.Tokens.SHEET_PADDING), 0, dp(activity, NexoUi.Tokens.SHEET_PADDING), dp(activity, 10f))
            actions.forEachIndexed { index, action ->
                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(activity, 76f)
                    setPadding(dp(activity, 8f), dp(activity, 12f), dp(activity, 8f), dp(activity, 12f))
                    background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, tileBg, radius), radius)
                    NexoGlass.paperShadow(this, p)
                    isClickable = true; isFocusable = true
                    contentDescription = listOfNotNull(action.label, action.subtitle).joinToString(". ")
                    setOnClickListener { NexoMotion.pulse(this, 0.96f); dismiss(); action.onClick() }
                    addView(ImageView(activity).apply {
                        setImageResource(action.icon)
                        imageTintList = ColorStateList.valueOf(if (action.danger) p.danger else p.text)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(activity, 20f), dp(activity, 20f)).apply { bottomMargin = dp(activity, 8f) })
                    addView(TextView(activity).apply {
                        text = action.label
                        textSize = 13f
                        gravity = Gravity.CENTER
                        typeface = NexoUi.medium(activity)
                        setTextColor(p.text)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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

        val idleBg = groupFill(p)

        actions.chunked(count).forEach { rowActions ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = count.toFloat()
            }
            rowActions.forEach { action ->
                // Activo = píldora de tinta sólida (máximo contraste), nunca un recuadro de color.
                val tint = when {
                    action.danger -> p.danger
                    action.active -> p.background
                    else -> p.text
                }
                val cellBg = if (action.active) p.text else idleBg

                val cell = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    minimumHeight = dp(activity, 64f)
                    setPadding(dp(activity, 4f), dp(activity, 11f), dp(activity, 4f), dp(activity, 10f))
                    background = ThemeManager.ripple(activity, ThemeManager.rounded(activity, cellBg, 18f), 18f)
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
                    layoutParams = LinearLayout.LayoutParams(dp(activity, 20f), dp(activity, 20f))
                })
                cell.addView(TextView(activity).apply {
                    text = action.label
                    textSize = 12f
                    typeface = NexoUi.medium(activity)
                    gravity = Gravity.CENTER
                    setTextColor(when {
                        action.danger -> p.danger
                        action.active -> p.background
                        else -> p.muted
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
                layoutParams = LinearLayout.LayoutParams(dp(activity, 20f), dp(activity, 20f)).apply {
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

/** ScrollView con altura máxima o fija animable para hojas adaptables de 3 estados. */
private class MaxHeightScrollView(context: android.content.Context, private var maxHeight: Int) : ScrollView(context) {
    private var exactMode: Boolean = false
    fun currentMaxHeight(): Int = maxHeight
    fun limitHeight(value: Int, exact: Boolean = false) {
        if (value != maxHeight || exact != exactMode) {
            maxHeight = value
            exactMode = exact
            requestLayout()
        }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (size > 0) minOf(size, maxHeight) else maxHeight
        val mode = if (exactMode) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, mode))
    }
}
