package com.nexo.browser

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Trace
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import kotlin.math.roundToInt

/** A single downscaled capture serves all the floating controls. At most one capture per vsync. */
internal class NexoGlassBackdrop(
    private val owner: View,
    private val surface: () -> SurfaceView?,
    private val invalidateGlass: () -> Unit
) {
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { capture() }
    private val stackBlur = if (Build.VERSION.SDK_INT < 31) StackBlur() else null
    private var running = false
    private var paused = false
    private var scheduled = false
    private var dirty = false
    private var copying = false
    private var generation = 0L
    private var lastCaptureMs = 0L
    private var spare: Bitmap? = null

    var bitmap: Bitmap? = null
        private set
    var version = 0L
        private set

    var intensity = GlassIntensity.BALANCED
        set(value) {
            if (field == value) return
            field = value
            markDirty()
        }

    fun setRunning(enabled: Boolean) {
        if (running == enabled) return
        running = enabled
        if (enabled) markDirty() else reset()
    }

    /** Pausa temporalmente PixelCopy mientras CefResumeBridge recupera la superficie. */
    fun setPaused(pause: Boolean) {
        if (paused == pause) return
        paused = pause
        if (pause) {
            choreographer.removeFrameCallback(frameCallback)
            scheduled = false
            generation++
        } else if (running) markDirty()
    }

    fun reset() {
        generation++
        bitmap = null
        spare = null
        version++
        choreographer.removeFrameCallback(frameCallback)
        scheduled = false
        dirty = false
        invalidateGlass()
    }

    fun markDirty() {
        dirty = true
        if (running && !paused && !scheduled && !copying) {
            val delay = if (bitmap == null) 0L else
                (lastCaptureMs + MIN_INTERVAL_MS - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L)
            scheduled = true
            choreographer.postFrameCallbackDelayed(frameCallback, delay)
        }
    }

    private fun capture() {
        scheduled = false
        if (!running || paused || !dirty) return
        val now = android.os.SystemClock.uptimeMillis()
        if (bitmap != null && now - lastCaptureMs < MIN_INTERVAL_MS) {
            markDirty()
            return
        }
        dirty = false
        val view = surface() ?: return
        if (!view.holder.surface.isValid || view.width <= 0 || view.height <= 0) return
        lastCaptureMs = now
        val width = (view.width * SCALE).roundToInt().coerceAtLeast(1)
        val height = (view.height * SCALE).roundToInt().coerceAtLeast(1)
        val target = spare?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        spare = null
        val expected = generation
        val radius = (intensity.radiusDp * owner.resources.displayMetrics.density * SCALE).roundToInt().coerceAtMost(80)
        copying = true
        val traceId = System.identityHashCode(this)
        Trace.beginAsyncSection("NexoGlass PixelCopy", traceId)
        try {
            PixelCopy.request(view, target, { code ->
                val success = code == PixelCopy.SUCCESS
                try {
                    if (success) stackBlur?.apply(target, radius)
                } finally {
                    Trace.endAsyncSection("NexoGlass PixelCopy", traceId)
                }
                owner.post {
                    copying = false
                    if (running && !paused && expected == generation && view === surface()) {
                        if (success) {
                            spare = bitmap
                            bitmap = target
                            version++
                            invalidateGlass()
                        } else {
                            spare = target
                        }
                    }
                    if (dirty) markDirty()
                }
            }, worker)
        } catch (_: IllegalArgumentException) {
            Trace.endAsyncSection("NexoGlass PixelCopy", traceId)
            copying = false
            spare = target
        }
    }

    private companion object {
        const val SCALE = 0.25f
        const val MIN_INTERVAL_MS = 140L
        val worker by lazy { Handler(HandlerThread("NexoGlassBackdrop").apply { start() }.looper) }
    }
}

/** Separable triangular StackBlur using moving sums and reusable pixel arrays. */
private class StackBlur {
    private var pixels = IntArray(0)
    private var intermediate = IntArray(0)

    fun apply(bitmap: Bitmap, radius: Int) {
        if (radius <= 0) return
        val width = bitmap.width
        val height = bitmap.height
        if (pixels.size < width * height) {
            pixels = IntArray(width * height)
            intermediate = IntArray(pixels.size)
        }
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        blurPass(pixels, intermediate, width, height, 1, width, radius)
        blurPass(intermediate, pixels, height, width, width, 1, radius)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun blurPass(
        input: IntArray, output: IntArray, length: Int, lines: Int,
        step: Int, lineStep: Int, radius: Int
    ) {
        val divisor = (radius + 1) * (radius + 1)
        // Separate the channels with 21 bits each, so no channel carries into its neighbour.
        val mask = 0x1FFFFFL
        fun channels(color: Int): Long =
            ((color.toLong() and 0xFF0000L) shl 26) or
                ((color.toLong() and 0xFF00L) shl 13) or (color.toLong() and 0xFFL)
        for (line in 0 until lines) {
            val start = line * lineStep
            var sum = 0L
            var left = 0L
            var right = 0L
            for (i in -radius..radius) {
                val sample = channels(input[start + i.coerceIn(0, length - 1) * step])
                sum += sample * (radius + 1 - kotlin.math.abs(i))
                if (i <= 0) left += sample else right += sample
            }
            for (i in 0 until length) {
                val red = ((sum ushr 42) / divisor).toInt()
                val green = (((sum ushr 21) and mask) / divisor).toInt()
                val blue = ((sum and mask) / divisor).toInt()
                output[start + i * step] = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                val outgoing = channels(input[start + (i - radius).coerceAtLeast(0) * step])
                val center = channels(input[start + (i + 1).coerceAtMost(length - 1) * step])
                val incoming = channels(input[start + (i + radius + 1).coerceAtMost(length - 1) * step])
                sum += right + incoming - left
                left += center - outgoing
                right += incoming - center
            }
        }
    }
}
