package com.nexo.browser

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Barra inferior con gestos, como en los navegadores móviles actuales:
 *  - deslizar a la izquierda/derecha cambia a la pestaña siguiente/anterior;
 *  - deslizar hacia arriba abre el selector de pestañas.
 * Los toques normales siguen llegando a los botones y a la barra de dirección.
 */
class GestureDock @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    /** +1 = pestaña siguiente (deslizar a la izquierda), -1 = anterior. */
    var onSwipeTab: (Int) -> Unit = {}
    var onSwipeUp: () -> Unit = {}
    /** false mientras se escribe en la barra de dirección. */
    var gesturesEnabled: () -> Boolean = { true }

    /**
     * Liquid Split Chrome: el gesto también nace sobre la píldora y sus botones; un toque sigue
     * llegando al hijo y solo un deslizamiento claro (horizontal o hacia arriba) se intercepta.
     */
    var interceptOverChildren: Boolean = false

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val distance = 56f * density
    private val flingVelocity = 700f * density
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var intercepted = false
    private var tracker: VelocityTracker? = null

    private fun hasInteractiveChild(x: Float, y: Float): Boolean {
        var view = findChildAt(x, y)
        while (view != null && view !== this) {
            if (view.isClickable || view.isLongClickable || view.isFocusableInTouchMode) return true
            view = view.parent as? View
        }
        return false
    }

    private fun findChildAt(x: Float, y: Float): View? {
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index)
            if (child.visibility != View.VISIBLE || x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue
            if (child is ViewGroup) {
                val nested = child.findViewAt(x - child.left, y - child.top)
                if (nested != null) return nested
            }
            return child
        }
        return null
    }

    private fun ViewGroup.findViewAt(x: Float, y: Float): View? {
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index)
            if (child.visibility != View.VISIBLE || x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue
            if (child is ViewGroup) child.findViewAt(x - child.left, y - child.top)?.let { return it }
            return child
        }
        return null
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                intercepted = false
                tracking = gesturesEnabled() && (interceptOverChildren || !hasInteractiveChild(event.x, event.y))
                tracker?.recycle()
                tracker = if (tracking) VelocityTracker.obtain().apply { addMovement(event) } else null
            }
            MotionEvent.ACTION_POINTER_DOWN -> recycle()
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || event.pointerCount > 1) return false
                tracker?.addMovement(event)
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                val threshold = if (interceptOverChildren) slop * 2.5f else slop * 2f
                val ratio = if (interceptOverChildren) 1.8f else 1.5f
                // Solo gestos claros: horizontales o hacia arriba, más allá del margen de toque.
                if ((abs(dx) > threshold && abs(dx) > abs(dy) * ratio) || (-dy > threshold && -dy > abs(dx) * ratio)) {
                    intercepted = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!intercepted) {
            // Toques sobre el fondo de la barra: iniciar seguimiento sin interceptar si hay hijo interactivo.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && gesturesEnabled() && !hasInteractiveChild(event.x, event.y)) {
                startX = event.rawX
                startY = event.rawY
                tracking = true
                intercepted = true
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(event) }
                return true
            }
            return super.onTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.pointerCount > 1) {
            recycle()
            return false
        }
        tracker?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Seguimiento elástico: el cristal acompaña al dedo con resistencia.
                val dx = event.rawX - startX
                if (abs(dx) > abs(event.rawY - startY)) translationX = (dx * 0.22f).coerceIn(-distance * 0.6f, distance * 0.6f)
            }
            MotionEvent.ACTION_UP -> {
                if (translationX != 0f) NexoMotion.spring(this, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, 0f)
                val dx = event.rawX - startX
                val dy = event.rawY - startY
                val velocity = tracker?.let { it.computeCurrentVelocity(1000); it.xVelocity to it.yVelocity } ?: (0f to 0f)
                var handled = true
                when {
                    abs(dx) > abs(dy) && (abs(dx) > distance || abs(velocity.first) > flingVelocity) && abs(dx) > slop -> {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSwipeTab(if (dx < 0) 1 else -1)
                    }
                    -dy > abs(dx) && (-dy > distance * 0.8f || -velocity.second > flingVelocity) && -dy > slop -> {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSwipeUp()
                    }
                    else -> handled = false
                }
                if (!handled) performClick()
                recycle()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (translationX != 0f) NexoMotion.spring(this, androidx.dynamicanimation.animation.DynamicAnimation.TRANSLATION_X, 0f)
                recycle()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun recycle() {
        tracker?.recycle()
        tracker = null
        tracking = false
        intercepted = false
    }
}
