package com.nexo.browser.ai

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import com.nexo.browser.NexoApertureDrawable
import com.nexo.browser.NexoGlass
import com.nexo.browser.NexoGlassDrawable
import com.nexo.browser.NexoMotion
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.ThemeManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * «Dynamic Live Pill» de Nexo AI: cristal ahumado flotante dentro de Nexo (sin overlay del sistema).
 *
 * - Punto que respira + barras fluidas mientras la respuesta llega por SSE.
 * - Estados reales: Escribiendo / Listo / Error / Retomar. Sin bordes de color.
 * - Un toque restaura la conversación tal cual; `×` oculta la píldora sin borrar ni cancelar nada.
 */
class NexoAiPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onRestore: () -> Unit = {}
    var onDismissPill: () -> Unit = {}

    private val dotView: BreathingDot
    private val apertureView: ImageView
    private val aperture = NexoApertureDrawable(context, 18f)
    private val bars: FluidBars
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

        minimumHeight = dp(48f)
        setPadding(dp(14f), 0, dp(2f), 0)

        dotView = BreathingDot(context)
        apertureView = ImageView(context).apply {
            setImageDrawable(aperture)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bars = FluidBars(context)
        labelView = TextView(context).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8f), 0, dp(4f), 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        dismissBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Ocultar indicador de Nexo AI sin borrar historial"
            scaleType = ImageView.ScaleType.CENTER
            scaleX = 0.8f
            scaleY = 0.8f
            setOnClickListener { onDismissPill() }
        }

        addView(dotView, LayoutParams(dp(8f), dp(8f)).apply { marginEnd = dp(10f) })
        addView(apertureView, LayoutParams(dp(18f), dp(18f)))
        addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bars, LayoutParams(dp(18f), dp(14f)).apply { marginEnd = dp(4f) })
        addView(dismissBtn, LayoutParams(dp(44f), dp(44f)))

        setOnClickListener { NexoMotion.pulse(this, 0.96f); onRestore() }
        visibility = GONE
    }

    fun render(activity: Activity, snapshot: NexoAiModels.ControllerSnapshot) {
        val show = snapshot.minimizedPillVisible && snapshot.panelState == NexoAiModels.PanelState.MINIMIZED
        if (!show) {
            hideAnimated()
            return
        }
        val p = ThemeManager.palette(activity)
        val live = if (p.isMonochrome) p.success.color else p.accent
        val (dot, title) = when (snapshot.indicatorState) {
            NexoAiModels.IndicatorState.GENERATING -> live to "Escribiendo…"
            NexoAiModels.IndicatorState.READY -> p.success.color to "Listo · Ver respuesta"
            NexoAiModels.IndicatorState.ERROR -> p.danger to "No se pudo responder"
            NexoAiModels.IndicatorState.IDLE -> p.muted to "Retomar chat"
        }
        val streaming = snapshot.indicatorState == NexoAiModels.IndicatorState.GENERATING && !NexoUi.reducedMotion(activity)

        background = NexoGlassDrawable(activity, p, NexoUi.Tokens.RADIUS_PILL, smoked = true)
        NexoGlass.float(this, p, if (p.isDark) 6f else 10f)
        dotView.color = dot
        // Solo respira mientras llegan tokens: en reposo no se redibuja (batería).
        dotView.breathing = streaming
        aperture.setColor(p.text)
        aperture.setBreathing(streaming)
        bars.color = p.text
        bars.visibility = if (streaming) VISIBLE else GONE
        bars.running = streaming
        labelView.text = title
        labelView.typeface = NexoUi.medium(activity)
        labelView.setTextColor(p.text)
        dismissBtn.background = ThemeManager.ripple(activity, null, NexoUi.Tokens.RADIUS_PILL)
        dismissBtn.imageTintList = ColorStateList.valueOf(p.muted)
        contentDescription = "Nexo AI: $title. Toca para restaurar la conversación."

        showAnimated()
    }

    private fun showAnimated() {
        if (currentlyVisible && visibility == VISIBLE) return
        currentlyVisible = true
        visibility = VISIBLE
        alpha = 0f
        translationY = 18f * resources.displayMetrics.density
        scaleX = 0.92f
        scaleY = 0.92f
        NexoMotion.spring(this, DynamicAnimation.ALPHA, 1f)
        NexoMotion.spring(this, DynamicAnimation.TRANSLATION_Y, 0f)
        NexoMotion.spring(this, DynamicAnimation.SCALE_X, 1f)
        NexoMotion.spring(this, DynamicAnimation.SCALE_Y, 1f)
    }

    private fun hideAnimated() {
        if (!currentlyVisible && visibility == GONE) return
        currentlyVisible = false
        bars.running = false
        aperture.setBreathing(false)
        NexoMotion.spring(this, DynamicAnimation.TRANSLATION_Y, 12f * resources.displayMetrics.density)
        NexoMotion.spring(this, DynamicAnimation.ALPHA, 0f) {
            if (!currentlyVisible) visibility = GONE
        }
    }

    /** Punto de estado que respira suavemente (seno de 1.6 s, sin parpadeos). */
    private class BreathingDot(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var color: Int = 0
            set(value) { field = value; invalidate() }
        var breathing = false
            set(value) { field = value; invalidate() }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val t = if (breathing) (SystemClock.uptimeMillis() % 1600L) / 1600f else 0.25f
            val pulse = 0.5f + 0.5f * sin(t * 2f * PI.toFloat())
            val r = minOf(width, height) / 2f
            paint.color = color
            paint.alpha = (255 * (0.18f + 0.22f * pulse)).toInt()
            canvas.drawCircle(width / 2f, height / 2f, r, paint)
            paint.alpha = 255
            canvas.drawCircle(width / 2f, height / 2f, r * (0.55f + 0.12f * pulse), paint)
            if (breathing && isShown) postInvalidateOnAnimation()
        }
    }

    /** Tres barras fluidas de alturas desfasadas mientras llegan tokens. */
    private class FluidBars(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var color: Int = 0
            set(value) { field = value; paint.color = value; invalidate() }
        var running = false
            set(value) { field = value; invalidate() }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val w = width / 5f
            val now = SystemClock.uptimeMillis() / 1000f
            for (i in 0 until 3) {
                val phase = sin((now * 5.2f + i * 1.1f).toDouble()).toFloat()
                val h = height * (0.35f + 0.3f * (phase + 1f))
                val x = i * 2f * w
                rect.set(x, (height - h) / 2f, x + w, (height + h) / 2f)
                paint.alpha = 210
                canvas.drawRoundRect(rect, w / 2f, w / 2f, paint)
            }
            if (running && isShown) postInvalidateOnAnimation()
        }
    }
}
