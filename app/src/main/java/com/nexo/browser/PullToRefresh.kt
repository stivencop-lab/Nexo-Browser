package com.nexo.browser

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.min

/** Vista de página que sabe si un deslizamiento hacia abajo debe recargar. */
interface PullToRefreshTarget {
    /** true solo cuando la página está arriba y el gesto no lo usa la propia web. */
    val canPullToRefresh: Boolean
}

/**
 * Contenedor del navegador con "deslizar hacia abajo para recargar": un
 * círculo con el icono de recarga baja con el dedo y, al soltarlo pasado el
 * umbral, recarga la pestaña.
 */
class PullRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var enabledForPage: () -> Boolean = { true }
    var onRefresh: () -> Unit = {}

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 76f * density
    private val maxPull = 120f * density
    private val size = (40 * density).toInt()
    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var refreshing = false
    private var animator: ValueAnimator? = null

    private val indicator = ImageView(context).apply {
        setImageResource(R.drawable.ic_nav_reload)
        scaleType = ImageView.ScaleType.CENTER
        elevation = 6f * density
        alpha = 0f
        visibility = GONE
    }

    /**
     * Gestos del reproductor en pantalla completa: ven cada toque antes que la página y deciden
     * si lo dejan pasar (`pass`) o lo consumen. Una vista hermana encima no puede hacerlo: al no
     * aceptar el primer toque deja de recibir el resto del gesto.
     */
    var touchFilter: ((MotionEvent, (MotionEvent) -> Boolean) -> Boolean)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val filter = touchFilter ?: return super.dispatchTouchEvent(ev)
        return filter(ev) { super.dispatchTouchEvent(it) }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // Siempre por encima de la página y de los controles flotantes.
        addView(indicator, LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        indicator.translationY = -size.toFloat()
    }

    fun applyPalette(surface: Int, accent: Int, outline: Int) {
        indicator.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(surface)
            setStroke((density).toInt().coerceAtLeast(1), outline)
        }
        indicator.imageTintList = ColorStateList.valueOf(accent)
    }

    /** La página terminó de cargar: oculta el indicador si estaba girando. */
    fun finishRefresh() {
        if (!refreshing) return
        refreshing = false
        hideIndicator()
    }

    private fun pageView(): PullToRefreshTarget? =
        (0 until childCount).map { getChildAt(it) }.firstOrNull { it is PullToRefreshTarget } as? PullToRefreshTarget

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (pulling) return true
                if (refreshing || event.pointerCount > 1 || !enabledForPage()) return false
                val view = pageView() ?: return false
                val dy = event.y - downY
                val dx = event.x - downX
                if (dy > touchSlop * 2 && dy > abs(dx) * 1.6f && view.canPullToRefresh) {
                    pulling = true
                    downY = event.y
                    animator?.cancel()
                    indicator.visibility = VISIBLE
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pulling) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> showPull(event.y - downY)
            MotionEvent.ACTION_UP -> {
                pulling = false
                val distance = dampen(event.y - downY)
                if (distance >= threshold) startRefresh() else hideIndicator()
            }
            MotionEvent.ACTION_CANCEL -> {
                pulling = false
                hideIndicator()
            }
        }
        return true
    }

    /** Resistencia progresiva: el círculo baja menos que el dedo. */
    private fun dampen(raw: Float): Float {
        if (raw <= 0f) return 0f
        return min(maxPull, raw * 0.55f)
    }

    private fun showPull(raw: Float) {
        val distance = dampen(raw)
        val progress = (distance / threshold).coerceIn(0f, 1f)
        indicator.translationY = distance - size * 0.5f
        indicator.alpha = 0.35f + progress * 0.65f
        indicator.rotation = progress * 270f
        val scale = 0.6f + progress * 0.4f
        indicator.scaleX = scale
        indicator.scaleY = scale
        indicator.imageAlpha = if (progress >= 1f) 255 else 170
    }

    private fun startRefresh() {
        refreshing = true
        indicator.animate().translationY(threshold * 0.55f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(150L).start()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            interpolator = null
            addUpdateListener { indicator.rotation = it.animatedValue as Float }
            start()
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        onRefresh()
        // Si la carga no avisa (página en caché), no dejar el indicador girando.
        postDelayed({ finishRefresh() }, 2500L)
    }

    private fun hideIndicator() {
        animator?.cancel()
        animator = null
        indicator.animate().translationY(-size.toFloat()).alpha(0f).scaleX(0.6f).scaleY(0.6f)
            .setDuration(180L).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!pulling && !refreshing) indicator.visibility = GONE }
            .start()
    }
}
