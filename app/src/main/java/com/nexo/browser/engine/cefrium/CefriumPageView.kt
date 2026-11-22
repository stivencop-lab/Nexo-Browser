package com.nexo.browser.engine.cefrium

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.cefrium.CefriumBrowser
import com.nexo.browser.BrowserMediaController
import com.nexo.browser.PullToRefreshTarget
import com.nexo.browser.TabManager
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineView
import kotlin.math.roundToInt

/**
 * Vista de páginas: muestra la superficie del navegador de la pestaña activa.
 * Cada pestaña viva tiene su navegador; al cambiar de pestaña solo cambia la
 * superficie que cuelga de aquí. La pantalla completa de un video la dibuja
 * Chromium en la misma superficie: Nexo solo oculta su interfaz.
 */
class CefriumPageView(context: Context) : FrameLayout(context), EngineView, PullToRefreshTarget {
    private val main = Handler(Looper.getMainLooper())
    private var current: CefriumEngineSession? = null
    internal var renderingSurface: SurfaceView? = null
        private set
    private val snapshotOverlay = ImageView(context).apply {
        visibility = View.INVISIBLE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_XY
    }
    internal var onBackdropInvalidated: (() -> Unit)? = null
    internal var onRecoveryStateChanged: ((Boolean) -> Unit)? = null
    internal var isNativeInputFocused: () -> Boolean = { false }
    private fun hasNativeTextInputFocus(): Boolean =
        isNativeInputFocused() || (context as? Activity)?.currentFocus is android.widget.EditText

    private val resumeBridge = CefResumeBridge(
        overlay = snapshotOverlay,
        invalidateContainer = ::postInvalidateOnAnimation,
        frameAvailable = { onBackdropInvalidated?.invoke() },
        resolveSurface = ::resolveSurface,
        isMediaActive = {
            val activeTab = TabManager.activeTab()
            activeTab != null && BrowserMediaController.shouldKeepAlive(activeTab.id, 10_000L)
        },
        onRecoveryChanged = { recovering -> onRecoveryStateChanged?.invoke(recovering) },
        shouldFocusBrowser = { !hasNativeTextInputFocus() },
        recreateBrowser = { browser -> current?.recreateSurface(browser) == true }
    )

    init {
        addView(snapshotOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    internal fun selectTab(id: String) = resumeBridge.selectTab(id)
    internal fun onHostPause() = resumeBridge.onPause()
    internal fun onHostStop() = resumeBridge.onStop()
    internal fun onHostResume() = resumeBridge.onResume(hasWindowFocus())
    internal fun onRendererTerminated(browser: CefriumBrowser) = resumeBridge.onRendererTerminated(browser)
    internal fun onDocumentChanged(browser: CefriumBrowser) = resumeBridge.onDocumentChanged(browser)
    internal fun onDocumentAvailable(browser: CefriumBrowser) = resumeBridge.onDocumentAvailable(browser)

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        resumeBridge.onWindowFocusChanged(hasWindowFocus)
    }

    /** Chromium hace su propio "deslizar para recargar" (ver Ajustes); el de Nexo no interviene. */
    override val canPullToRefresh: Boolean get() = false

    override fun attach(session: EngineSession, background: Int) {
        val target = session as CefriumEngineSession
        setBackgroundColor(background)
        snapshotOverlay.setBackgroundColor(background)
        (context as? Activity)?.let { CefriumEngine.register(it) }
        if (current !== target) current?.let { detach(it) }
        current = target
        target.host = this
        target.ensureBrowser()
        target.browser?.setBackgroundColor(background)
        attachBrowser(target)
    }

    /** El navegador de [session] (nuevo o recreado) pasa a esta vista si es la pestaña mostrada. */
    internal fun attachBrowser(session: CefriumEngineSession) {
        if (current !== session) return
        val browser = session.browser ?: return
        val container = browser.surfaceContainer
        if (container.parent !== this) {
            (container.parent as? ViewGroup)?.removeView(container)
            addView(container, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        renderingSurface = findSurface(container)
        resumeBridge.bind(browser, renderingSurface)
        // Garantiza que una pestaña recién activada o tomada del SpareRendererPool sea visible inmediatamente.
        if (session.isActiveSession() && !browser.isClosed) {
            runCatching {
                if (!hasNativeTextInputFocus()) browser.onResume()
                browser.setPageVisible(true)
                browser.invalidate()
            }
        }
    }

    /** El navegador de [session] se cierra o se recrea. */
    internal fun detachBrowser(session: CefriumEngineSession, browser: CefriumBrowser) {
        val surface = runCatching { browser.surfaceContainer }.getOrNull() ?: return
        if (surface.parent === this) {
            renderingSurface = null
            resumeBridge.bind(null, null)
            removeView(surface)
        }
    }

    override fun release() {
        current?.let { detach(it) }
        current = null
        resumeBridge.release()
    }

    /** La pestaña se cierra o deja de mostrarse aquí. */
    internal fun detach(session: CefriumEngineSession) {
        if (session.host === this) session.host = null
        session.browser?.let { detachBrowser(session, it) }
        if (current === session) current = null
    }

    /**
     * Copia rápida y fiable de lo que se ve (incluidos videos hardware).
     * Usa PixelCopy escalado directamente sobre el SurfaceView (rápido y bajo consumo de RAM)
     * con respaldo a `browser.captureBitmap` si la superficie aún no está lista.
     */
    override fun capture(onResult: (Bitmap?) -> Unit) {
        val browser = current?.browser
        if (browser == null || width <= 0 || height <= 0) {
            onResult(null)
            return
        }
        val surface = resolveSurface()
        if (surface != null && surface.holder.surface.isValid && surface.width > 0 && surface.height > 0) {
            val targetW = minOf(CAPTURE_MAX_WIDTH, surface.width).coerceAtLeast(1)
            val scale = targetW.toFloat() / surface.width.toFloat()
            val targetH = (surface.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = runCatching { Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888) }.getOrNull()
            if (bitmap != null) {
                try {
                    PixelCopy.request(surface, bitmap, { status ->
                        if (status == PixelCopy.SUCCESS) {
                            main.post { onResult(bitmap) }
                        } else {
                            bitmap.recycle()
                            main.post { captureViaBrowser(browser, onResult) }
                        }
                    }, CefResumeBridge.pixelCopyHandler)
                    return
                } catch (_: IllegalArgumentException) {
                    bitmap.recycle()
                }
            }
        }
        captureViaBrowser(browser, onResult)
    }

    private fun captureViaBrowser(browser: CefriumBrowser, onResult: (Bitmap?) -> Unit) {
        if (browser.isClosed) {
            onResult(null)
            return
        }
        runCatching {
            browser.captureBitmap { bitmap -> main.post { onResult(bitmap) } }
        }.onFailure { onResult(null) }
    }

    override fun onPictureInPictureModeChanged(inPictureInPicture: Boolean) =
        resumeBridge.onPictureInPictureChanged(inPictureInPicture)

    private fun resolveSurface(): SurfaceView? {
        val existing = renderingSurface
        if (existing != null && existing.holder.surface.isValid && existing.width > 0 && existing.height > 0) {
            return existing
        }
        val container = runCatching { current?.browser?.surfaceContainer }.getOrNull() ?: return existing
        val found = findSurface(container)
        if (found != null) renderingSurface = found
        return found ?: existing
    }

    private fun findSurface(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findSurface(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private companion object {
        const val CAPTURE_MAX_WIDTH = 480
    }
}
