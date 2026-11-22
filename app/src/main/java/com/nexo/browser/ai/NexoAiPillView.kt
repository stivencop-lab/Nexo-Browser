package com.nexo.browser.ai

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.InsetDrawable
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
import com.nexo.browser.NexoGlassBackdrop
import com.nexo.browser.NexoGlassDrawable
import com.nexo.browser.NexoMotion
import com.nexo.browser.NexoTokens
import com.nexo.browser.NexoUi
import com.nexo.browser.R
import com.nexo.browser.ThemeManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * «Dynamic Live Pill» de Nexo AI: cristal líquido compacto (34 dp visuales en área táctil de 48 dp),
 * coordinado con el plegado de la barra inferior y oculto automáticamente en pantalla completa / PiP.
 *
 * - En modo compacto (al desplazarse hacia abajo o compartir espacio con Mini video / CC) se pliega
 *   en una cápsula discreta sin etiqueta larga para no estorbar la lectura ni solaparse.
 */
class NexoAiPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    var onRestore: () -> Unit = {}
    var onDismissPill: () -> Unit = {}
    var onStopAgent: () -> Unit = {}

    private val dotView: BreathingDot
    private val apertureView: ImageView
    private val aperture = NexoApertureDrawable(context, 16f)
    private val bars: FluidBars
    private val labelView: TextView
    private val dismissBtn: ImageButton
    private var currentlyVisible = false
    private var suppressed = false
    private var compactMode = false
    private var agentLiveActive = false
    private var restingOffsetY = 0f
    private var lastSnapshot: NexoAiModels.ControllerSnapshot? = null
    private var webBackdrop: NexoGlassBackdrop? = null
    private var backdropSource: View? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        val d = resources.displayMetrics.density
        val dp = { v: Float -> (v * d).toInt() }

        minimumHeight = dp(48f)
        setPadding(dp(12f), 0, dp(2f), 0)

        dotView = BreathingDot(context)
        apertureView = ImageView(context).apply {
            setImageDrawable(aperture)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        bars = FluidBars(context)
        labelView = TextView(context).apply {
            textSize = 12.5f
            maxLines = 1
            maxWidth = dp(210f)
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6f), 0, dp(2f), 0)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        dismissBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_menu_close)
            contentDescription = "Ocultar indicador de Nexo AI sin borrar historial"
            scaleType = ImageView.ScaleType.CENTER
            scaleX = 0.76f
            scaleY = 0.76f
            setOnClickListener {
                if (agentLiveActive) onStopAgent() else onDismissPill()
            }
        }

        addView(dotView, LayoutParams(dp(7f), dp(7f)).apply { marginEnd = dp(8f) })
        addView(apertureView, LayoutParams(dp(16f), dp(16f)))
        addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bars, LayoutParams(dp(16f), dp(12f)).apply { marginStart = dp(4f); marginEnd = dp(2f) })
        addView(dismissBtn, LayoutParams(dp(40f), dp(44f)))

        setOnClickListener { NexoMotion.pulse(this, 0.96f); onRestore() }
        visibility = GONE
    }

    /** Conecta el desenfoque en vivo de la página web (Liquid Glass). */
    internal fun bindWebBackdrop(backdrop: NexoGlassBackdrop?, source: View?) {
        webBackdrop = backdrop
        backdropSource = source
    }

    /** Oculta la píldora en modos inmersivos (pantalla completa de video o PiP). */
    fun setSuppressed(hide: Boolean) {
        if (suppressed == hide) return
        suppressed = hide
        if (hide) {
            hideAnimated()
        } else {
            val snap = lastSnapshot ?: return
            if (snap.minimizedPillVisible && snap.panelState == NexoAiModels.PanelState.MINIMIZED) {
                showAnimated()
            }
        }
    }

    /** Modo compacto al recoger la barra inferior o al convivir con Mini video / CC. */
    fun setCompact(compact: Boolean) {
        if (compactMode == compact) return
        compactMode = compact
        applyCompactLayout()
    }

    /** Desplazamiento vertical coordinado con el plegado de la barra inferior. */
    fun setChromeOffsetY(offsetY: Float, animated: Boolean = true) {
        restingOffsetY = offsetY
        if (!currentlyVisible || visibility != VISIBLE) return
        if (animated) {
            NexoMotion.spring(this, DynamicAnimation.TRANSLATION_Y, restingOffsetY)
        } else {
            translationY = restingOffsetY
        }
    }

    private fun applyCompactLayout() {
        val d = resources.displayMetrics.density
        val dp = { v: Float -> (v * d).toInt() }
        // Mientras el Agente está ejecutando acciones en vivo, mantener visible la etiqueta y el botón Detener.
        val collapseText = compactMode && !agentLiveActive
        labelView.visibility = if (collapseText) GONE else VISIBLE
        dismissBtn.visibility = if (collapseText) GONE else VISIBLE
        if (collapseText) {
            setPadding(dp(11f), 0, dp(11f), 0)
        } else {
            setPadding(dp(12f), 0, dp(2f), 0)
        }
        val targetAlpha = if (collapseText) 0.90f else 1f
        if (currentlyVisible && visibility == VISIBLE) {
            NexoMotion.spring(this, DynamicAnimation.ALPHA, targetAlpha)
        }
    }

    fun render(activity: Activity, snapshot: NexoAiModels.ControllerSnapshot) {
        lastSnapshot = snapshot
        val agentReq = snapshot.activeAgentRequest
        agentLiveActive = agentReq != null
        val show = !suppressed &&
            (snapshot.minimizedPillVisible || agentLiveActive) &&
            snapshot.panelState == NexoAiModels.PanelState.MINIMIZED
        if (!show) {
            hideAnimated()
            return
        }
        val p = ThemeManager.palette(activity)
        val live = if (p.isMonochrome) p.success.color else p.accent
        val (dot, title) = if (agentReq != null) {
            val stepPrefix = if (agentReq.currentStepIndex > 0) "Agente ${agentReq.currentStepIndex}/${agentReq.maxSteps}" else "Agente"
            val stepDetail = agentReq.currentStepLabel.ifBlank { "Ejecutando…" }
            live to "$stepPrefix · $stepDetail"
        } else {
            when (snapshot.indicatorState) {
                NexoAiModels.IndicatorState.GENERATING -> live to "Escribiendo…"
                NexoAiModels.IndicatorState.READY -> p.success.color to "Listo · Ver"
                NexoAiModels.IndicatorState.ERROR -> p.danger to "Error"
                NexoAiModels.IndicatorState.IDLE -> p.muted to "Nexo AI"
            }
        }
        val streaming = (snapshot.indicatorState == NexoAiModels.IndicatorState.GENERATING || agentLiveActive) &&
            !NexoUi.reducedMotion(activity)

        val glass = NexoGlassDrawable(activity, p, NexoTokens.PILL_RADIUS, smoked = true).also {
            val wb = webBackdrop
            val src = backdropSource
            if (wb != null && src != null) it.setWebBackdrop(wb, src, this)
        }
        val insetY = (7f * resources.displayMetrics.density).toInt()
        background = InsetDrawable(glass, 0, insetY, 0, insetY)
        NexoGlass.float(this, p, if (p.isDark) 4f else 7f)
        dotView.color = dot
        dotView.breathing = streaming
        aperture.setColor(p.text)
        aperture.setBreathing(streaming)
        bars.color = p.text
        bars.visibility = if (streaming) VISIBLE else GONE
        bars.running = streaming
        labelView.text = title
        labelView.typeface = NexoUi.medium(activity)
        labelView.setTextColor(p.text)
        dismissBtn.setImageResource(if (agentLiveActive) R.drawable.ic_ai_stop else R.drawable.ic_menu_close)
        dismissBtn.contentDescription = if (agentLiveActive) {
            "Detener agente en ejecución"
        } else {
            "Ocultar indicador de Nexo AI sin borrar historial"
        }
        dismissBtn.background = ThemeManager.ripple(activity, null, NexoUi.Tokens.RADIUS_PILL)
        dismissBtn.imageTintList = ColorStateList.valueOf(if (agentLiveActive) p.danger else p.muted)
        contentDescription = "Nexo AI: $title. Toca para ver el detalle."
        applyCompactLayout()
        showAnimated()
    }

    private fun showAnimated() {
        if (currentlyVisible && visibility == VISIBLE) return
        currentlyVisible = true
        visibility = VISIBLE
        alpha = 0f
        translationY = restingOffsetY + 14f * resources.displayMetrics.density
        scaleX = 0.92f
        scaleY = 0.92f
        NexoMotion.spring(this, DynamicAnimation.ALPHA, if (compactMode) 0.90f else 1f)
        NexoMotion.spring(this, DynamicAnimation.TRANSLATION_Y, restingOffsetY)
        NexoMotion.spring(this, DynamicAnimation.SCALE_X, 1f)
        NexoMotion.spring(this, DynamicAnimation.SCALE_Y, 1f)
    }

    private fun hideAnimated() {
        if (!currentlyVisible && visibility == GONE) return
        currentlyVisible = false
        bars.running = false
        aperture.setBreathing(false)
        NexoMotion.spring(this, DynamicAnimation.TRANSLATION_Y, restingOffsetY + 12f * resources.displayMetrics.density)
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
