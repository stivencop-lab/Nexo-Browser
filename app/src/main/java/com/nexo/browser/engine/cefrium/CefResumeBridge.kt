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

/**
 * Red de seguridad temporal para recuperación de superficie tras segundo plano.
 *
 * Desde Cefrium 0.22.0 el foco entre `EditText` nativos y el navegador lo
 * gestiona `CefriumSurfaceContainer`, y PiP gestiona `onPause()` nativamente.
 * Si el watchdog nunca se dispara en hardware real con 0.22.0 (ver
 * `EngineDiagnostics` → `watchdog=0`), esta clase podrá retirarse por completo.
 */
internal class CefResumeBridge(
    private val overlay: ImageView,
    private val invalidateContainer: () -> Unit,
    private val frameAvailable: () -> Unit,
    private val resolveSurface: () -> SurfaceView?,
    private val isMediaActive: () -> Boolean,
    private val onRecoveryChanged: (Boolean) -> Unit,
    private val onRecoveryFailed: () -> Unit,
    private val recreateBrowser: (CefriumBrowser) -> Boolean
) : SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private var browser: CefriumBrowser? = null
    private var surface: SurfaceView? = null
    private var tabId: String? = null
    private var resumed = false
    private var pip = false
    private var stopped = false
    private var recovering = false
    private var rebuilt = false
    private var surfaceDestroyedWhilePip = false
    private var copyEverSucceeded = false
    private var documentAvailable = false
    private var armed = false
    private var generation = 0L
    private var snapshot: Bitmap? = null
    private var snapshotIsBlack = false
    private var snapshotRequest = 0L
    private var wakeTime = 0L

    private val watchdog = Runnable {
        if (!resumed || !recovering || pip) return@Runnable
        val target = browser?.takeUnless { it.isClosed } ?: run {
            onRecoveryFailed()
            revealNow()
            return@Runnable
        }
        val elapsed = SystemClock.uptimeMillis() - wakeTime
        val media = isMediaActive()
        val action = CefResumePolicy.watchdogAction(
            rebuilt = rebuilt,
            mediaActive = media,
            copyEverSucceeded = copyEverSucceeded
        )
        when (action) {
            CefResumePolicy.WatchdogAction.RECREATE_BROWSER -> {
                rebuilt = true
                EngineDiagnostics.recordWatchdogRecreation()
                EngineDiagnostics.log(
                    "CefResumeBridge watchdog disparado tras ${elapsed}ms " +
                        "(media=$media, copyOk=$copyEverSucceeded, doc=$documentAvailable); recreando navegador"
                )
                Log.w(TAG, "No usable surface buffer after $elapsed ms. Recreating Cefrium browser.")
                cancelProbe()
                if (!recreateBrowser(target)) {
                    Log.w(TAG, "Cefrium browser recreation failed; revealing live surface.")
                    onRecoveryFailed()
                    revealNow()
                } else {
                    wakeIfReady()
                }
            }
            CefResumePolicy.WatchdogAction.REVEAL -> {
                EngineDiagnostics.log(
                    "CefResumeBridge watchdog reveló superficie tras ${elapsed}ms " +
                        "(rebuilt=$rebuilt, media=$media, copyOk=$copyEverSucceeded)"
                )
                Log.w(TAG, "Watchdog revealing live surface (rebuilt=$rebuilt, media=$media, copyOk=$copyEverSucceeded).")
                if (!canProbe()) onRecoveryFailed()
                revealNow()
            }
        }
    }
    private val probe = Runnable { probeSurface() }

    fun selectTab(id: String) {
        if (tabId == id) return
        tabId = id
        snapshotRequest++
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
        stopped = true
        if (pip) return
        if (browser != null) cover()
    }

    fun onResume() {
        stopped = false
        resumed = true
        if (!pip) {
            // Reasegurar visibilidad del compositor aunque no hubiera cubierta pendiente (p. ej. tras cerrar PiP).
            ensureBrowserVisible()
        }
        wakeIfReady()
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        // Cefrium 0.22.0 gestiona el foco de ventana en CefriumSurfaceContainer sin robarlo a EditText.
        if (hasFocus) wakeIfReady()
    }

    fun onPictureInPictureChanged(enabled: Boolean) {
        pip = enabled
        if (enabled) {
            surfaceDestroyedWhilePip = false
            finishRecovery()
        } else {
            if (CefResumePolicy.needsRecoveryAfterPipExit(stopped, surfaceDestroyedWhilePip)) {
                surfaceDestroyedWhilePip = false
                markRecoveringWithoutBlackCover()
            }
            wakeIfReady()
        }
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
        snapshotRequest++
        clearSnapshot()
    }

    fun onDocumentAvailable(target: CefriumBrowser) {
        if (target !== browser) return
        documentAvailable = true
        if (recovering) wakeIfReady()
    }

    fun release() {
        bind(null, null)
        tabId = null
        finishRecovery()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (holder === surface?.holder) {
            if (resumed && !pip) ensureBrowserVisible()
            wakeIfReady()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (holder === surface?.holder && width > 0 && height > 0) {
            if (CefResumePolicy.shouldResumeOnSurfaceChanged(resumed, recovering, pip)) {
                ensureBrowserVisible()
            }
            wakeIfReady()
            if (!recovering) frameAvailable()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (holder !== surface?.holder) return
        cancelProbe()
        if (pip) {
            surfaceDestroyedWhilePip = true
        } else {
            cover()
            runCatching { browser?.takeUnless { it.isClosed }?.setPageVisible(false) }
            wakeIfReady()
        }
    }

    private fun currentSurface(): SurfaceView? {
        val cur = surface
        if (cur != null && surfaceReady(cur)) return cur
        val fresh = resolveSurface()
        if (fresh != null && fresh !== cur) {
            cur?.holder?.removeCallback(this)
            surface = fresh
            fresh.holder.addCallback(this)
        }
        return surface
    }

    private fun surfaceReady(view: SurfaceView): Boolean =
        view.holder.surface.isValid && view.holder.surfaceFrame.width() > 0 &&
            view.holder.surfaceFrame.height() > 0 && view.width > 0 && view.height > 0

    private fun ensureBrowserVisible() {
        val target = browser?.takeUnless { it.isClosed } ?: return
        val view = currentSurface()
        if (view == null || !surfaceReady(view)) return
        runCatching {
            target.onResume()
            target.setPageVisible(true)
            target.invalidate()
            invalidateContainer()
        }.onFailure { Log.w(TAG, "Cefrium visibility restore failed", it) }
    }

    private fun markRecoveringWithoutBlackCover() {
        if (!recovering) rebuilt = false
        recovering = true
        copyEverSucceeded = false
        onRecoveryChanged(true)
        overlay.animate().cancel()
        overlay.visibility = View.INVISIBLE
        overlay.setImageDrawable(null)
    }

    private fun cover() {
        if (!recovering) rebuilt = false
        recovering = true
        copyEverSucceeded = false
        onRecoveryChanged(true)
        overlay.animate().cancel()
        val snap = snapshot?.takeUnless { it.isRecycled }
        if (snap != null && !snapshotIsBlack) {
            overlay.alpha = 1f
            overlay.setImageBitmap(snap)
            overlay.visibility = View.VISIBLE
        } else {
            // Jamás tapar la superficie viva con un rectángulo negro opaco cuando no hay captura válida.
            overlay.visibility = View.INVISIBLE
            overlay.setImageDrawable(null)
        }
    }

    private fun canProbe(): Boolean = CefResumePolicy.shouldWake(
        resumed = resumed,
        recovering = recovering,
        pip = pip,
        surfaceReady = currentSurface()?.let(::surfaceReady) == true
    )

    private fun wakeIfReady() {
        if (armed || !resumed || !recovering || pip) return
        val timeout = CefResumePolicy.watchdogTimeoutMs(isMediaActive())
        val target = browser?.takeUnless { it.isClosed }
        if (target == null || !canProbe()) {
            if (!main.hasCallbacks(watchdog)) {
                wakeTime = SystemClock.uptimeMillis()
                main.postDelayed(watchdog, timeout)
            }
            return
        }
        main.removeCallbacks(watchdog)
        runCatching {
            target.onResume()
            target.setPageVisible(true)
            target.invalidate()
            invalidateContainer()
        }.onFailure { Log.w(TAG, "Cefrium wakeup failed", it) }
        wakeTime = SystemClock.uptimeMillis()
        copyEverSucceeded = false
        armed = true
        main.postDelayed(watchdog, timeout)
        main.postDelayed(probe, PROBE_INTERVAL_MS)
    }

    private fun probeSurface() {
        val view = currentSurface() ?: return
        if (!armed || !canProbe()) return
        val expected = generation
        copy(view, 24, 32) { bitmap, black ->
            bitmap?.recycle()
            if (generation != expected || !armed || !canProbe()) return@copy
            if (bitmap != null) copyEverSucceeded = true
            val elapsed = SystemClock.uptimeMillis() - wakeTime
            val accepted = CefResumePolicy.acceptFrame(
                copySucceeded = bitmap != null,
                isBlack = black,
                snapshotIsBlack = snapshotIsBlack,
                rebuilt = rebuilt,
                documentAvailable = documentAvailable,
                mediaActive = isMediaActive(),
                elapsedMs = elapsed
            )
            if (accepted) {
                EngineDiagnostics.recordSurfaceRecovery()
                Log.d(TAG, "Surface buffer available in $elapsed ms (black=$black, media=${isMediaActive()})")
                revealNow()
            } else {
                main.postDelayed(probe, PROBE_INTERVAL_MS)
            }
        }
    }

    private fun captureSnapshot() {
        val view = currentSurface() ?: return
        if (!surfaceReady(view)) return
        val request = ++snapshotRequest
        val target = browser
        val started = SystemClock.uptimeMillis()
        copy(view, (view.width * 0.65f).toInt().coerceAtLeast(1), (view.height * 0.65f).toInt().coerceAtLeast(1)) { bitmap, black ->
            if (bitmap == null) return@copy
            if (request != snapshotRequest || target !== browser || pip) {
                bitmap.recycle()
                return@copy
            }
            Log.d(TAG, "Surface snapshot in ${SystemClock.uptimeMillis() - started} ms (black=$black)")
            val previous = snapshot
            snapshot = bitmap
            snapshotIsBlack = black
            if (recovering && overlay.visibility == View.VISIBLE && overlay.alpha == 1f && !black) {
                overlay.setImageBitmap(bitmap)
            }
            if (previous != null && previous !== bitmap && !previous.isRecycled) {
                if (overlay.visibility != View.VISIBLE) previous.recycle()
            }
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

    private fun revealNow() {
        frameAvailable()
        crossfade()
    }

    private fun crossfade() {
        cancelProbe()
        recovering = false
        rebuilt = false
        copyEverSucceeded = false
        onRecoveryChanged(false)
        if (overlay.visibility != View.VISIBLE) {
            overlay.animate().cancel()
            overlay.setImageDrawable(null)
            return
        }
        overlay.animate().alpha(0f).setDuration(90L).withEndAction {
            overlay.visibility = View.INVISIBLE
            overlay.setImageDrawable(null)
        }.start()
    }

    private fun finishRecovery() {
        cancelProbe()
        recovering = false
        rebuilt = false
        copyEverSucceeded = false
        onRecoveryChanged(false)
        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.visibility = View.INVISIBLE
        overlay.setImageDrawable(null)
        clearSnapshot()
    }

    private fun clearSnapshot() {
        val old = snapshot
        snapshot = null
        snapshotIsBlack = false
        if (old != null && !old.isRecycled) {
            overlay.setImageDrawable(null)
            old.recycle()
        }
    }

    private fun cancelProbe() {
        generation++
        armed = false
        main.removeCallbacks(probe)
        main.removeCallbacks(watchdog)
    }

    internal companion object {
        const val TAG = "NexoResumeBridge"
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
