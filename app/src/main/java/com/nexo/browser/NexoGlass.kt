package com.nexo.browser

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.RequiresApi
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

/** Tint and refraction over either a native view or the shared Chromium surface capture. */
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
    private val noise = Paint().apply {
        if (Build.VERSION.SDK_INT < 31) shader = noiseShader
    }
    private val sampling = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val bitmapBounds = RectF()
    private val clip = Path()
    private val intensity = GlassPreferences.intensity
    private val blur = if (Build.VERSION.SDK_INT >= 31) BlurNode(intensity.radiusDp * density) else null
    private var backdropView: View? = null
    private var webBackdrop: NexoGlassBackdrop? = null
    private var host: View? = null
    private val hostLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private var nativeDirty = true
    var smoked: Boolean = smoked
        set(value) { if (field != value) { field = value; updateEdge(); invalidateSelf() } }

    fun setBackdrop(view: View?, chrome: View? = null) {
        if (backdropView === view && host === chrome) return
        backdropView = view
        host = chrome
        markBackdropDirty()
    }

    internal fun setWebBackdrop(backdrop: NexoGlassBackdrop?, viewport: View, chrome: View) {
        webBackdrop = backdrop
        backdropView = viewport
        host = chrome
        markBackdropDirty()
    }

    internal fun markBackdropDirty() {
        nativeDirty = true
        invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rect.set(bounds)
        val r = radius.coerceAtMost(rect.height() / 2f)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        nativeDirty = true
        updateEdge()
    }

    private fun updateEdge() {
        if (rect.isEmpty || !specularTop) return
        val upper = if (palette.isDark) (if (smoked) 0x26FFFFFF else 0x61FFFFFF) else 0xE0FFFFFF.toInt()
        val lower = if (palette.isDark) 0x1CFFFFFF else 0x210D0803
        edge.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            intArrayOf(upper, 0x00FFFFFF, 0x00FFFFFF, lower),
            floatArrayOf(0f, 0.40f, 0.75f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        val blurred = drawBackdrop(canvas)
        val base = when {
            smoked && palette.isDark -> 0xFF050507.toInt()
            smoked -> 0xFFDAD8D1.toInt()
            palette.isAmoled -> NexoTokens.OLED
            palette.isDark -> 0xFF141418.toInt()
            else -> NexoTokens.PAPER
        }
        val alpha = when {
            !blurred -> 235
            palette.isDark -> 143
            else -> 184
        }
        val r = radius.coerceAtMost(rect.height() / 2f)
        fill.color = ColorUtils.setAlphaComponent(base, alpha)
        canvas.drawRoundRect(rect, r, r, fill)
        if (blurred && noise.shader != null) canvas.drawRoundRect(rect, r, r, noise)
        if (specularTop) {
            val inset = edge.strokeWidth / 2f
            canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, r, r, edge)
        }
    }

    private fun drawBackdrop(canvas: Canvas): Boolean {
        val source = backdropView ?: return false
        val chrome = host ?: (callback as? View) ?: return false
        if (!source.isAttachedToWindow || source.visibility != View.VISIBLE || source.width <= 0 || source.height <= 0) return false
        val web = webBackdrop
        val bitmap = web?.bitmap
        if (web != null && bitmap == null || bitmap == null && (blur == null || !canvas.isHardwareAccelerated)) return false
        chrome.getLocationInWindow(hostLocation)
        source.getLocationInWindow(sourceLocation)
        val dx = sourceLocation[0] - hostLocation[0]
        val dy = sourceLocation[1] - hostLocation[1]
        val save = canvas.save()
        canvas.clipPath(clip)
        if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated && blur != null) {
            blur.draw(canvas, source, bitmap, sampling, bitmapBounds, dx, dy, rect,
                web?.version ?: 0, nativeDirty || (web == null && source.isDirty))
        } else if (bitmap != null) {
            bitmapBounds.set(dx.toFloat(), dy.toFloat(), (dx + source.width).toFloat(), (dy + source.height).toFloat())
            canvas.drawBitmap(bitmap, null, bitmapBounds, sampling)
        }
        nativeDirty = false
        canvas.restoreToCount(save)
        return true
    }

    @RequiresApi(31)
    private class BlurNode(radiusPx: Float) {
        private val node = RenderNode("NexoGlass").apply {
            setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
        }
        private val padding = (radiusPx * 2f).toInt()
        private var x = Int.MIN_VALUE
        private var y = Int.MIN_VALUE
        private var version = -1L
        private var sourceWidth = 0
        private var sourceHeight = 0

        fun draw(canvas: Canvas, source: View, bitmap: Bitmap?, sampling: Paint, bitmapBounds: RectF, dx: Int, dy: Int,
                 rect: RectF, frameVersion: Long, dirty: Boolean) {
            val left = rect.left.toInt() - padding
            val top = rect.top.toInt() - padding
            val resized = node.setPosition(left, top, rect.right.toInt() + padding, rect.bottom.toInt() + padding)
            if (dirty || resized || !node.hasDisplayList() || x != dx || y != dy || version != frameVersion ||
                sourceWidth != source.width || sourceHeight != source.height) {
                x = dx
                y = dy
                version = frameVersion
                sourceWidth = source.width
                sourceHeight = source.height
                val recording = node.beginRecording()
                try {
                    recording.translate((dx - left).toFloat(), (dy - top).toFloat())
                    if (bitmap != null) {
                        bitmapBounds.set(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
                        recording.drawBitmap(bitmap, null, bitmapBounds, sampling)
                    } else {
                        source.draw(recording)
                    }
                } finally {
                    node.endRecording()
                }
            }
            canvas.drawRenderNode(node)
        }
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

    private companion object {
        val noiseShader by lazy {
            val random = java.util.Random(90L)
            val speckles = IntArray(32 * 32) {
                val alpha = random.nextInt(6) shl 24
                if (random.nextBoolean()) alpha or 0x00FFFFFF else alpha
            }
            BitmapShader(Bitmap.createBitmap(speckles, 32, 32, Bitmap.Config.ARGB_8888),
                Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
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

    private fun unwrapGlass(drawable: android.graphics.drawable.Drawable?): NexoGlassDrawable? {
        var cur = drawable
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur is NexoGlassDrawable) return cur
            cur = when (cur) {
                is android.graphics.drawable.DrawableWrapper -> cur.drawable
                is android.graphics.drawable.LayerDrawable -> (0 until cur.numberOfLayers)
                    .firstNotNullOfOrNull { unwrapGlass(cur.getDrawable(it)) }
                else -> null
            }
            depth++
        }
        return null
    }

    /**
     * Mantiene vivo el desenfoque del [glass] cuando su fondo [backdrop] cambia (desplazamiento,
     * imágenes nuevas). Desenvuelve InsetDrawable/RippleDrawable y limpia el listener al desmontarse.
     */
    fun follow(glass: View, backdrop: View) {
        if (Build.VERSION.SDK_INT < 31) return
        val listener = ViewTreeObserver.OnPreDrawListener {
            @Suppress("DEPRECATION")
            if (backdrop.visibility == View.VISIBLE && backdrop.isDirty && glass.visibility == View.VISIBLE) {
                unwrapGlass(glass.background)?.markBackdropDirty()
                glass.invalidate()
            }
            true
        }
        var registered = false
        fun attach() {
            if (!registered && glass.viewTreeObserver.isAlive) {
                glass.viewTreeObserver.addOnPreDrawListener(listener)
                registered = true
            }
        }
        fun detach() {
            if (registered && glass.viewTreeObserver.isAlive) {
                glass.viewTreeObserver.removeOnPreDrawListener(listener)
            }
            registered = false
        }
        if (glass.isAttachedToWindow) attach()
        glass.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = attach()
            override fun onViewDetachedFromWindow(v: View) = detach()
        })
    }

    /** Hoja sobre la web: desenfoque real de lo que hay detrás (incluida la superficie de Chromium). */
    fun blurBehind(dialog: Dialog, activity: Activity, radiusDp: Float = GlassPreferences.intensity.radiusDp) {
        if (Build.VERSION.SDK_INT < 31) return
        val window = dialog.window ?: return
        val wm = activity.getSystemService(WindowManager::class.java) ?: return
        if (!wm.isCrossWindowBlurEnabled) return
        window.setBackgroundBlurRadius((radiusDp * activity.resources.displayMetrics.density).toInt())
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
 */
class NexoApertureDrawable(context: Context, sizeDp: Float = 20f) : Drawable(), Animatable {
    private val density = context.resources.displayMetrics.density
    private val size = (sizeDp * density).toInt()
    private val arcs = context.getDrawable(R.drawable.ic_nexo_aperture_arcs)!!.mutate()
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val status = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawingAlpha = 255
    private var dotColor: Int? = null
    private var running = false
    private var startedAt = 0L

    fun setColor(color: Int) {
        arcs.setTint(color)
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
        arcs.draw(canvas)
        val coreR = (1.55f + 0.55f * breathe) * unit
        core.alpha = (drawingAlpha * (0.78f + 0.22f * breathe)).toInt()
        canvas.drawCircle(cx, cy, coreR, core)
        dotColor?.let {
            status.color = it
            val r = 2.6f * unit
            canvas.drawCircle(b.right - r - 0.4f * unit, b.top + r + 0.4f * unit, r, status)
        }
        if (running) invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) {
        val side = minOf(bounds.width(), bounds.height())
        val left = bounds.left + (bounds.width() - side) / 2
        val top = bounds.top + (bounds.height() - side) / 2
        arcs.setBounds(left, top, left + side, top + side)
    }

    override fun start() {
        if (running) return
        running = true
        startedAt = SystemClock.uptimeMillis()
        invalidateSelf()
    }

    override fun stop() { running = false; invalidateSelf() }
    override fun isRunning() = running
    override fun setAlpha(alpha: Int) { drawingAlpha = alpha; arcs.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { arcs.colorFilter = colorFilter; core.colorFilter = colorFilter }
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
