package com.nexo.browser.engine.cefrium

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageView
import com.cefrium.CefriumBrowser

/** Coordinates the activity, the Android surface and Cefrium's windowed compositor. */
internal class CefResumeBridge(
    private val overlay: ImageView,
    private val invalidateContainer: () -> Unit,
    private val frameAvailable: () -> Unit,
    private val recreateBrowser: (CefriumBrowser) -> Boolean
) : SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private var browser: CefriumBrowser? = null
    private var surface: SurfaceView? = null
    private var tabId: String? = null
    private var resumed = false
    private var focused = false
    private var pip = false
    private var stopped = false
    private var recovering = false
    private var rebuilt = false
    private var documentAvailable = false
    private var armed = false
    private var generation = 0L
    private var snapshot: Bitmap? = null
    private var snapshotIsBlack = false
    private var snapshotRequest = 0L
    private var wakeTime = 0L

    private val watchdog = Runnable {
        if (canProbe()) {
            val target = browser ?: return@Runnable
            if (!rebuilt) {
                rebuilt = true
                Log.w(TAG, "No usable surface buffer after ${WATCHDOG_MS} ms. Recreating Cefrium browser.")
                cancelProbe()
                if (!recreateBrowser(target)) Log.w(TAG, "Cefrium browser recreation failed.")
            } else {
                // Keep the cover over a failed surface. A subsequent load, focus
                // change or surface creation can attempt presentation again.
                cancelProbe()
                Log.w(TAG, "Replacement surface has no usable buffer.")
            }
        }
    }
    private val probe = Runnable { probeSurface() }
    private val prefetch = Runnable {
        if (resumed && !recovering && !pip && snapshot == null) captureSnapshot()
    }

    fun selectTab(id: String) {
        if (tabId == id) return
        tabId = id
        snapshotRequest++
        main.removeCallbacks(prefetch)
        finishRecovery()
    }

    fun bind(target: CefriumBrowser?, view: SurfaceView?) {
        if (target === browser && view === surface) return
        cancelProbe()
        snapshotRequest++
        surface?.holder?.removeCallback(this)
        if (browser !== target) documentAvailable = false
        browser = target
        surface = view
        view?.holder?.addCallback(this)
        wakeIfReady()
    }

    fun onPause() {
        if (!pip && !recovering && !stopped) captureSnapshot()
        resumed = false
        cancelProbe()
    }

    fun onStop() {
        if (pip) return
        stopped = true
        if (browser != null) cover()
    }

    fun onResume(hasWindowFocus: Boolean) {
        stopped = false
        resumed = true
        focused = hasWindowFocus
        wakeIfReady()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        if (hasFocus) wakeIfReady() else cancelProbe()
    }

    fun onPictureInPictureChanged(enabled: Boolean) {
        pip = enabled
        if (enabled) finishRecovery() else wakeIfReady()
    }

    fun onRendererTerminated(target: CefriumBrowser) {
        if (target === browser && !pip) {
            cancelProbe()
            cover()
        }
    }

    fun onDocumentChanged(target: CefriumBrowser) {
        if (target !== browser) return
        documentAvailable = false
        if (recovering) return
        main.removeCallbacks(prefetch)
        snapshotRequest++
        snapshot = null
        snapshotIsBlack = false
    }

    fun onDocumentAvailable(target: CefriumBrowser) {
        if (target !== browser) return
        documentAvailable = true
        if (recovering) wakeIfReady()
        else if (resumed && !pip) {
            main.removeCallbacks(prefetch)
            main.postDelayed(prefetch, 150)
        }
    }

    fun release() {
        bind(null, null)
        tabId = null
        main.removeCallbacks(prefetch)
        finishRecovery()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (holder === surface?.holder) wakeIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (holder === surface?.holder && width > 0 && height > 0) {
            wakeIfReady()
            if (!recovering) frameAvailable()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (holder !== surface?.holder) return
        cancelProbe()
        if (!pip) {
            cover()
            runCatching { browser?.takeUnless { it.isClosed }?.setPageVisible(false) }
        }
    }

    private fun surfaceReady(view: SurfaceView): Boolean =
        view.holder.surface.isValid && view.holder.surfaceFrame.width() > 0 &&
            view.holder.surfaceFrame.height() > 0 && view.width > 0 && view.height > 0

    private fun cover() {
        if (!recovering) rebuilt = false
        recovering = true
        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.setImageBitmap(snapshot)
        overlay.visibility = View.VISIBLE
    }

    private fun canProbe(): Boolean =
        resumed && focused && recovering && !pip && surface?.let(::surfaceReady) == true

    private fun wakeIfReady() {
        if (armed || !canProbe()) return
        val target = browser?.takeUnless { it.isClosed } ?: return
        runCatching {
            target.setPageVisible(true)
            target.setFocus(true)
            // In Surface mode Cefrium takes its viewport size from the holder.
            target.invalidate()
            invalidateContainer()
        }.onFailure { Log.w(TAG, "Cefrium wakeup failed", it) }
        wakeTime = SystemClock.uptimeMillis()
        armed = true
        main.postDelayed(watchdog, WATCHDOG_MS)
        main.postDelayed(probe, PROBE_INTERVAL_MS)
    }

    private fun probeSurface() {
        val view = surface ?: return
        if (!armed || !canProbe()) return
        val expected = generation
        // Success proves the Surface supplies a buffer. A tiny sample rejects a
        // black buffer when the document was previously visible and non-black.
        copy(view, 24, 32) { bitmap, black ->
            bitmap?.recycle()
            if (generation != expected || !armed || !canProbe()) return@copy
            if (bitmap != null && (!black || snapshotIsBlack || rebuilt && documentAvailable)) {
                Log.d(TAG, "Surface buffer available in ${SystemClock.uptimeMillis() - wakeTime} ms")
                frameAvailable()
                crossfade()
            } else {
                main.postDelayed(probe, PROBE_INTERVAL_MS)
            }
        }
    }

    private fun captureSnapshot() {
        val view = surface ?: return
        if (!surfaceReady(view)) return
        val request = ++snapshotRequest
        val target = browser
        val started = SystemClock.uptimeMillis()
        copy(view, (view.width * 0.75f).toInt().coerceAtLeast(1), (view.height * 0.75f).toInt().coerceAtLeast(1)) { bitmap, black ->
            if (bitmap == null) return@copy
            if (request != snapshotRequest || target !== browser || pip || black && snapshot != null && !snapshotIsBlack) {
                bitmap.recycle()
                return@copy
            }
            Log.d(TAG, "Surface snapshot in ${SystemClock.uptimeMillis() - started} ms")
            snapshot = bitmap
            snapshotIsBlack = black
            if (recovering && overlay.alpha == 1f) overlay.setImageBitmap(bitmap)
        }
    }

    private fun copy(view: SurfaceView, width: Int, height: Int, result: (Bitmap?, Boolean) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        try {
            PixelCopy.request(view, bitmap, { status ->
                val success = status == PixelCopy.SUCCESS
                val black = success && isBlack(bitmap)
                if (!success) bitmap.recycle()
                main.post { result(bitmap.takeIf { success }, black) }
            }, pixelCopyHandler)
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            result(null, false)
        }
    }

    private fun crossfade() {
        cancelProbe()
        recovering = false
        rebuilt = false
        overlay.animate().alpha(0f).setDuration(90L).withEndAction {
            overlay.visibility = View.INVISIBLE
            overlay.setImageDrawable(null)
        }.start()
    }

    private fun finishRecovery() {
        cancelProbe()
        recovering = false
        rebuilt = false
        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.visibility = View.INVISIBLE
        overlay.setImageDrawable(null)
        snapshot = null
        snapshotIsBlack = false
    }

    private fun cancelProbe() {
        generation++
        armed = false
        main.removeCallbacks(probe)
        main.removeCallbacks(watchdog)
    }

    private companion object {
        const val TAG = "NexoResumeBridge"
        const val WATCHDOG_MS = 450L
        const val PROBE_INTERVAL_MS = 32L
        val pixelCopyHandler by lazy {
            Handler(HandlerThread("NexoPixelCopy").apply { start() }.looper)
        }

        fun isBlack(bitmap: Bitmap): Boolean {
            for (y in 0..5) for (x in 0..5) {
                if (bitmap.getPixel(x * (bitmap.width - 1) / 5, y * (bitmap.height - 1) / 5) != android.graphics.Color.BLACK) {
                    return false
                }
            }
            return true
        }
    }
}
