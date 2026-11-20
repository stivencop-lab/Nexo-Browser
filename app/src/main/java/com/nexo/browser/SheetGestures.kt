package com.nexo.browser

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs

/**
 * Hoja inferior con entrada animada natural, cierre deslizando hacia abajo y expansión
 * opcional deslizando hacia arriba (experiencia 3 estados en Nexo AI: Minimizado → Parcial → Expandido).
 * Respeta la preferencia del sistema de reducción de movimiento (`ValueAnimator.areAnimatorsEnabled()`).
 */
class DragDismissLayout(context: Context) : LinearLayout(context) {
    /** Se llama una vez, al terminar la animación de salida. */
    var onDismiss: () -> Unit = {}

    /** Opcional: gesto de arrastre hacia arriba desde el asa/cabecera para expandir a pantalla completa. */
    var onExpandUp: (() -> Unit)? = null

    /** Contenido desplazable: mientras no esté arriba, el gesto es suyo. */
    var scrollable: View? = null

    /** > 0: solo arrastra un gesto que empiece en esta franja superior (px). */
    var dragZonePx: Int = 0

    /** Ventana cuyo oscurecido se aclara mientras se arrastra. */
    var dimWindow: Window? = null
    var baseDim = 0.45f

    private val config = ViewConfiguration.get(context)
    private val slop = config.scaledTouchSlop
    private val expandThresholdPx = 54f * resources.displayMetrics.density
    private val flingVelocity = 900f * resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var lastY = 0f
    private var candidate = false
    private var dragging = false
    private var dismissed = false
    private var tracker: VelocityTracker? = null
    private var settle: SpringAnimation? = null

    private fun inZone(event: MotionEvent): Boolean =
        dragZonePx <= 0 || event.y <= dragZonePx

    private fun contentAtTop(): Boolean = scrollable?.canScrollVertically(-1) != true

    /** Animación breve y natural de entrada desde abajo (respeta reducción de movimiento). */
    fun animateEnter() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            translationY = 0f
            alpha = 1f
            return
        }
        val travel = (56f * resources.displayMetrics.density)
        translationY = travel
        alpha = 0.01f
        post {
            if (dismissed) return@post
            animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (dismissed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                lastY = event.rawY
                dragging = false
                candidate = inZone(event)
                settle?.cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                if (!candidate || event.pointerCount > 1) return false
                val dy = event.rawY - startY
                val dx = event.rawX - startX
                if (dy > slop && dy > abs(dx) * 1.4f && (dragZonePx > 0 || contentAtTop())) {
                    beginDrag(event)
                    return true
                }
                if (onExpandUp != null && dy < -expandThresholdPx && abs(dy) > abs(dx) * 1.5f && contentAtTop()) {
                    candidate = false
                    onExpandUp?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> recycle()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dismissed) return true
        tracker?.addMovement(event)
        when (event.actionMasked) {
            // Toques en zonas sin hijo que los consuma (asa, márgenes): seguir el gesto.
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                lastY = event.rawY
                candidate = inZone(event)
                if (tracker == null) tracker = VelocityTracker.obtain().apply { addMovement(event) }
                return candidate
            }
            MotionEvent.ACTION_MOVE -> {
                val totalDy = event.rawY - startY
                if (!dragging) {
                    if (candidate && totalDy > slop && totalDy > abs(event.rawX - startX) * 1.4f) {
                        beginDrag(event)
                    } else if (onExpandUp != null && candidate && totalDy < -expandThresholdPx && abs(totalDy) > abs(event.rawX - startX) * 1.5f) {
                        candidate = false
                        onExpandUp?.invoke()
                        return true
                    } else {
                        return candidate
                    }
                }
                translationY = (translationY + event.rawY - lastY).coerceAtLeast(0f)
                lastY = event.rawY
                updateDim()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) release() else performClick()
                recycle()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) animateTo(0f) {}
                recycle()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginDrag(event: MotionEvent) {
        dragging = true
        lastY = event.rawY
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun release() {
        dragging = false
        val velocity = tracker?.let { it.computeCurrentVelocity(1000); it.yVelocity } ?: 0f
        val travel = height.coerceAtLeast(1)
        if (translationY > travel * 0.33f || velocity > flingVelocity) dismissAnimated()
        else animateTo(0f) {}
    }

    /** Cierre con la misma animación que el gesto (también desde un botón). */
    fun dismissAnimated() {
        if (dismissed) return
        dismissed = true
        dimWindow?.setWindowAnimations(0)
        animateTo(height.toFloat().coerceAtLeast(1f)) { onDismiss() }
    }

    private fun animateTo(target: Float, end: () -> Unit) {
        settle?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            translationY = target
            updateDim()
            end()
            return
        }
        settle = SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, target).apply {
            spring = SpringForce(target).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
            addUpdateListener { _, _, _ -> updateDim() }
            addEndListener { _, cancelled, _, _ -> if (!cancelled) end() }
            start()
        }
    }

    private fun updateDim() {
        val window = dimWindow ?: return
        val progress = (translationY / height.coerceAtLeast(1)).coerceIn(0f, 1f)
        runCatching { window.setDimAmount(baseDim * (1f - progress)) }
    }

    private fun recycle() {
        tracker?.recycle()
        tracker = null
        candidate = false
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        settle?.cancel()
        recycle()
        super.onDetachedFromWindow()
    }
}
