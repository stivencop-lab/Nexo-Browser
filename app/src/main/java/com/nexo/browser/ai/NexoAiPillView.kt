package com.nexo.browser.ai

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.ThemeManager

/**
 * Indicador flotante compacto dentro de la interfaz de Nexo (sin permisos de overlay del sistema).
 *
 * Aparece únicamente cuando el usuario minimiza Nexo AI con una conversación o consulta activa:
 * - Muestra estados reales: Generando / Respuesta lista / Error / Disponible.
 * - Un toque restaura exactamente la conversación sin reconstruirla ni cancelar nada.
 * - El botón `×` oculta la vista flotante sin borrar el historial ni detener consultas.
 */
class NexoAiPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onRestore: () -> Unit = {}
    var onDismissPill: () -> Unit = {}

    private val dotView: View
    private val sparkIcon: ImageView
    private val labelView: TextView
    private val dismissBtn: ImageButton
    private var currentlyVisible = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        val d = resources.displayMetrics.density
        val dp = { v: Float -> (v * d).toInt() }

        minimumHeight = dp(44f)
        setPadding(dp(12f), dp(4f), dp(4f), dp(4f))

        dotView = View(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        sparkIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_nexo_ai_spark)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        labelView = TextView(context).apply {
            textSize = 12.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8f), 0, dp(6f), 0)
        }
        dismissBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Ocultar indicador de Nexo AI sin borrar historial"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener { onDismissPill() }
        }

        addView(dotView, LayoutParams(dp(8f), dp(8f)).apply { marginEnd = dp(8f) })
        addView(sparkIcon, LayoutParams(dp(18f), dp(18f)))
        addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(dismissBtn, LayoutParams(dp(36f), dp(36f)))

        setOnClickListener { onRestore() }
        visibility = GONE
    }

    fun render(activity: Activity, snapshot: NexoAiModels.ControllerSnapshot) {
        val show = snapshot.minimizedPillVisible && snapshot.panelState == NexoAiModels.PanelState.MINIMIZED
        if (!show) {
            hideAnimated()
            return
        }

        val p = ThemeManager.palette(activity)
        val surfaceBg = when {
            p.isAmoled -> 0xFF101521.toInt()
            p.isDark -> p.surfaceContainerHighest
            else -> p.surfaceContainerLowest
        }

        val (dotColor, iconTint, titleText, strokeColor) = when (snapshot.indicatorState) {
            NexoAiModels.IndicatorState.GENERATING -> Quad(
                p.accent,
                p.accent,
                "Nexo AI · Generando…",
                ColorUtils.setAlphaComponent(p.accent, 145)
            )
            NexoAiModels.IndicatorState.READY -> Quad(
                p.success.color,
                p.accent,
                "Nexo AI · Respuesta lista",
                ColorUtils.setAlphaComponent(p.success.color, 150)
            )
            NexoAiModels.IndicatorState.ERROR -> Quad(
                p.danger,
                p.danger,
                "Nexo AI · Error",
                ColorUtils.setAlphaComponent(p.danger, 150)
            )
            NexoAiModels.IndicatorState.IDLE -> Quad(
                p.muted,
                p.onAccentSoft,
                "Nexo AI · Retomar chat",
                ThemeManager.hairline(p)
            )
        }

        background = ThemeManager.ripple(
            activity,
            ThemeManager.rounded(activity, surfaceBg, 22f, strokeColor),
            22f
        )
        elevation = NexoUi.dp(activity, if (p.isDark) 3f else 6f).toFloat()
        dotView.background = ThemeManager.rounded(activity, dotColor, 4f)
        sparkIcon.imageTintList = ColorStateList.valueOf(iconTint)
        labelView.text = titleText
        labelView.typeface = NexoUi.semiBold(activity)
        labelView.setTextColor(p.text)
        dismissBtn.background = ThemeManager.ripple(activity, null, 18f)
        dismissBtn.imageTintList = ColorStateList.valueOf(p.muted)
        contentDescription = "$titleText. Toca para restaurar conversación."

        showAnimated()
    }

    private fun showAnimated() {
        if (currentlyVisible && visibility == VISIBLE) return
        currentlyVisible = true
        animate().cancel()
        visibility = VISIBLE
        if (!ValueAnimator.areAnimatorsEnabled()) {
            alpha = 1f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            return
        }
        alpha = 0f
        translationY = 18f * resources.displayMetrics.density
        scaleX = 0.94f
        scaleY = 0.94f
        animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideAnimated() {
        if (!currentlyVisible && visibility == GONE) return
        currentlyVisible = false
        animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            visibility = GONE
            return
        }
        animate()
            .alpha(0f)
            .translationY(12f * resources.displayMetrics.density)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(140L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!currentlyVisible) visibility = GONE
            }
            .start()
    }

    private data class Quad(val a: Int, val b: Int, val c: String, val d: Int)
}
