package com.nexo.browser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.PI
import kotlin.math.sin

/**
 * Cristal Líquido de Nexo UI 7.0.
 *
 * - API 31+: si hay un [backdrop] dibujable (vistas nativas, p. ej. la página de inicio), se
 *   graba en un RenderNode con `RenderEffect.createBlurEffect(26, 26, CLAMP)` y se pinta bajo
 *   una capa translúcida (oscuro #141418 · 72 %, Paper #FFFFFF · 78 %).
 * - Sin desenfoque posible (API < 31 o superficie de Chromium): relleno elegante al 92 %.
 * - Borde especular: trazo interior superior de 0.5dp (blanco 22 % oscuro / 95 % Paper) que
 *   se desvanece hacia la mitad, más una elevación suave.
 * - [smoked]: vidrio ahumado discreto para el modo privado.
 */
class NexoGlassDrawable(
    context: Context,
    private val palette: BrowserPalette,
    radiusDp: Float,
    private val specularTop: Boolean = true,
    smoked: Boolean = false
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val radius = radiusDp * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (0.5f * density).coerceAtLeast(1f)
    }
    private val rect = RectF()
    private val clip = Path()
    private var blurNode: RenderNode? = null
    private var backdropView: View? = null
    private var hostLocation = IntArray(2)
    private var sourceLocation = IntArray(2)

    var smoked: Boolean = smoked
        set(value) { if (field != value) { field = value; invalidateSelf() } }

    /** Vista nativa situada detrás del cristal que se desenfoca en tiempo real (API 31+). */
    fun setBackdrop(view: View?) {
        if (backdropView === view) return
        backdropView = view
        if (view == null) blurNode = null
        invalidateSelf()
    }

    private val blurring: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && backdropView?.let { it.isAttachedToWindow && it.visibility == View.VISIBLE && it.width > 0 } == true

    private fun fillColor(): Int {
        val alpha = if (blurring) (if (palette.isDark) 0.72f else 0.78f) else 0.92f
        val base = when {
            smoked && palette.isDark -> 0xFF050507.toInt()
            smoked -> 0xFFDAD8D1.toInt()
            palette.isDark -> 0xFF141418.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val oledBase = if (palette.isAmoled && !smoked) 0xFF0E0E11.toInt() else base
        return ColorUtils.setAlphaComponent(oledBase, (alpha * 255).toInt())
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        rect.set(b)
        val r = radius.coerceAtMost(rect.height() / 2f)
        if (blurring && canvas.isHardwareAccelerated) drawBackdrop(canvas, r)
        fill.color = fillColor()
        canvas.drawRoundRect(rect, r, r, fill)
        if (specularTop) {
            val top = if (palette.isDark) (if (smoked) 0x26FFFFFF else ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 56))
            else ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 242)
            edge.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * 0.55f,
                top, 0x00FFFFFF, Shader.TileMode.CLAMP)
            val inset = edge.strokeWidth / 2f
            canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, r, r, edge)
        }
        if (!palette.isDark) {
            // Paper: el cristal blanco sobre páginas blancas necesita un contorno casi invisible.
            edge.shader = null
            edge.color = ColorUtils.setAlphaComponent(palette.text, 16)
            val inset = edge.strokeWidth / 2f
            canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, r, r, edge)
        }
    }

    private fun drawBackdrop(canvas: Canvas, r: Float) {
        if (Build.VERSION.SDK_INT < 31) return
        val source = backdropView ?: return
        val host = callback as? View ?: return
        // Evita recursión si el cristal vive dentro de su propio fondo.
        var parent = host.parent
        while (parent != null) { if (parent === source) return; parent = parent.parent }
        val node = blurNode ?: RenderNode("nexo-glass").also {
            it.setRenderEffect(RenderEffect.createBlurEffect(26f, 26f, Shader.TileMode.CLAMP))
            blurNode = it
        }
        node.setPosition(0, 0, source.width, source.height)
        val recording = node.beginRecording()
        source.draw(recording)
        node.endRecording()
        host.getLocationInWindow(hostLocation)
        source.getLocationInWindow(sourceLocation)
        val save = canvas.save()
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.translate((sourceLocation[0] - hostLocation[0]).toFloat(), (sourceLocation[1] - hostLocation[1]).toFloat())
        canvas.drawRenderNode(node)
        canvas.restoreToCount(save)
    }

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) return
        outline.setRoundRect(bounds, radius.coerceAtMost(bounds.height() / 2f))
        outline.alpha = 0.92f
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Utilidades de material: elevación suave, sombra Paper y desenfoque de ventanas. */
object NexoGlass {
    /** Elevación de cristal flotante con contorno redondeado (sombra suave, nunca negra). */
    fun float(view: View, p: BrowserPalette, elevationDp: Float = if (p.isDark) 4f else 10f) {
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.elevation = elevationDp * view.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 28) {
            val spot = if (p.isDark) 0x66000000 else 0x3D000000
            view.outlineSpotShadowColor = spot
            view.outlineAmbientShadowColor = if (p.isDark) 0x33000000 else 0x14000000
        }
    }

    /** Paper: superficie blanca sin borde con sombra difusa (≈ 0 2dp 14dp · 4.5 %). */
    fun paperShadow(view: View, p: BrowserPalette) {
        if (p.isDark) { view.elevation = 0f; return }
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.elevation = 3f * view.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 28) {
            view.outlineSpotShadowColor = 0x30000000
            view.outlineAmbientShadowColor = 0x0C000000
        }
    }

    /**
     * Mantiene vivo el desenfoque del [glass] cuando su fondo [backdrop] cambia (desplazamiento,
     * imágenes nuevas). Solo invalida si el fondo está realmente sucio: coste casi nulo en reposo.
     */
    fun follow(glass: View, backdrop: View) {
        if (Build.VERSION.SDK_INT < 31) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            @Suppress("DEPRECATION")
            if (backdrop.visibility == View.VISIBLE && backdrop.isDirty) glass.invalidate()
            true
        }
        glass.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /** Hoja sobre la web: desenfoque real de lo que hay detrás (incluida la superficie de Chromium). */
    fun blurBehind(dialog: Dialog, activity: Activity, radiusDp: Float = 26f) {
        if (Build.VERSION.SDK_INT < 31) return
        val window = dialog.window ?: return
        val wm = activity.getSystemService(WindowManager::class.java) ?: return
        if (!wm.isCrossWindowBlurEnabled) return
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply {
            blurBehindRadius = (radiusDp * activity.resources.displayMetrics.density).toInt()
        }
    }
}

/** Física de movimiento Liquid Craft: muelles críticamente amortiguados (380 / 0.82). */
object NexoMotion {
    const val STIFFNESS = 380f
    const val DAMPING = 0.82f

    fun spring(view: View, property: DynamicAnimation.ViewProperty, target: Float, onEnd: (() -> Unit)? = null): SpringAnimation {
        val key = when (property) {
            DynamicAnimation.TRANSLATION_Y -> R.id.nexo_spring_ty
            DynamicAnimation.SCALE_X -> R.id.nexo_spring_sx
            DynamicAnimation.SCALE_Y -> R.id.nexo_spring_sy
            DynamicAnimation.ALPHA -> R.id.nexo_spring_alpha
            else -> R.id.nexo_spring_tx
        }
        @Suppress("UNCHECKED_CAST")
        val anim = (view.getTag(key) as? SpringAnimation) ?: SpringAnimation(view, property).also { view.setTag(key, it) }
        anim.spring = SpringForce(target).setStiffness(STIFFNESS).setDampingRatio(DAMPING)
        if (onEnd != null) {
            val listener = object : DynamicAnimation.OnAnimationEndListener {
                override fun onAnimationEnd(animation: DynamicAnimation<*>?, canceled: Boolean, value: Float, velocity: Float) {
                    animation?.removeEndListener(this)
                    if (!canceled) onEnd()
                }
            }
            anim.addEndListener(listener)
        }
        if (NexoUi.reducedMotion(view.context)) {
            anim.cancel()
            property.setValue(view, target)
            onEnd?.invoke()
        } else {
            anim.animateToFinalPosition(target)
        }
        return anim
    }

    /** Pulsación física breve (escala y vuelta) para respuestas táctiles. */
    fun pulse(view: View, to: Float = 0.92f) {
        if (NexoUi.reducedMotion(view.context)) return
        view.scaleX = to
        view.scaleY = to
        spring(view, DynamicAnimation.SCALE_X, 1f)
        spring(view, DynamicAnimation.SCALE_Y, 1f)
    }
}

/**
 * Nexo Aperture: dos arcos concéntricos entrelazados (trazo 1.7dp, extremos redondos)
 * alrededor de un punto central que respira suavemente durante el streaming.
 * Sustituye a la chispa de 4 puntas: ninguna estética de "IA mágica".
 */
class NexoApertureDrawable(context: Context, sizeDp: Float = 20f) : Drawable(), Animatable {
    private val density = context.resources.displayMetrics.density
    private val size = (sizeDp * density).toInt()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.7f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val status = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var dotColor: Int? = null
    private var running = false
    private var startedAt = 0L

    fun setColor(color: Int) {
        stroke.color = color
        core.color = color
        invalidateSelf()
    }

    fun setStatusDot(color: Int?) { dotColor = color; invalidateSelf() }

    fun setBreathing(on: Boolean) { if (on) start() else stop() }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val unit = minOf(b.width(), b.height()) / 24f
        val phase = if (running) ((SystemClock.uptimeMillis() - startedAt) % 1800L) / 1800f else 0f
        val breathe = if (running) (0.5f + 0.5f * sin(phase * 2f * PI.toFloat())) else 0f
        // Arco exterior: 270° abiertos arriba a la derecha; arco interior: 220° opuesto.
        val outer = 8.4f * unit
        oval.set(cx - outer, cy - outer, cx + outer, cy + outer)
        canvas.drawArc(oval, -30f + phase * 360f * 0.08f, 270f, false, stroke)
        val inner = 4.6f * unit
        oval.set(cx - inner, cy - inner, cx + inner, cy + inner)
        canvas.drawArc(oval, 150f - phase * 360f * 0.08f, 220f, false, stroke)
        val coreR = (1.55f + 0.55f * breathe) * unit
        core.alpha = (255 * (0.78f + 0.22f * breathe)).toInt()
        canvas.drawCircle(cx, cy, coreR, core)
        dotColor?.let {
            status.color = it
            val r = 2.6f * unit
            canvas.drawCircle(b.right - r - 0.4f * unit, b.top + r + 0.4f * unit, r, status)
        }
        if (running) invalidateSelf()
    }

    override fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    override fun stop() { running = false; invalidateSelf() }
    override fun isRunning() = running
    override fun setAlpha(alpha: Int) { stroke.alpha = alpha; core.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { stroke.colorFilter = colorFilter; core.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Monograma "N" geométrico monocromático: losa de tinta sólida con la N calada en el color del
 * lienzo. Sin degradados ni brillos.
 */
class NexoMonogramDrawable(context: Context, private val ink: Int, private val cut: Int, radiusDp: Float = 10f) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val radius = radiusDp * density
    private val slab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cut }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        canvas.drawRoundRect(rect, radius, radius, slab)
        val s = minOf(rect.width(), rect.height())
        val x = rect.centerX() - s / 2f
        val y = rect.centerY() - s / 2f
        // Misma geometría que el icono de la app (rejilla 36): tallos de 3u y diagonal limpia.
        fun px(v: Float) = x + v / 36f * s
        fun py(v: Float) = y + v / 36f * s
        path.reset()
        path.moveTo(px(11.5f), py(25.5f))
        path.lineTo(px(11.5f), py(10.5f))
        path.lineTo(px(14.6f), py(10.5f))
        path.lineTo(px(21.4f), py(20.2f))
        path.lineTo(px(21.4f), py(10.5f))
        path.lineTo(px(24.5f), py(10.5f))
        path.lineTo(px(24.5f), py(25.5f))
        path.lineTo(px(21.4f), py(25.5f))
        path.lineTo(px(14.6f), py(15.8f))
        path.lineTo(px(14.6f), py(25.5f))
        path.close()
        canvas.drawPath(path, glyph)
    }

    override fun setAlpha(alpha: Int) { slab.alpha = alpha; glyph.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
