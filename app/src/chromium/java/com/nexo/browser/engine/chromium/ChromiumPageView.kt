package com.nexo.browser.engine.chromium

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.nexo.browser.PullToRefreshTarget
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineView

/**
 * WebView de una pestaña. Cada pestaña viva tiene la suya; se crea con un
 * contexto intercambiable para no retener la Activity cuando no está en
 * pantalla (WebView necesita la Activity para sus menús desplegables).
 */
@SuppressLint("ViewConstructor")
internal class NexoWebView(val contextWrapper: MutableContextWrapper) : WebView(contextWrapper) {
    /** La página llegó arriba y el dedo siguió bajando: el gesto es de Nexo. */
    @Volatile var overscrolledAtTop = false
        private set

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            overscrolledAtTop = false
        }
        return super.onTouchEvent(event)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        // Solo el desplazamiento del documento: un mapa o carrusel no desborda la página.
        if (clampedY && scrollY == 0) overscrolledAtTop = true
    }

    fun useActivity(activity: Context?) {
        contextWrapper.baseContext = activity ?: context.applicationContext
    }
}

/** Vista de página de Chromium: muestra la WebView de la pestaña activa y su video a pantalla completa. */
class ChromiumPageView(context: Context) : FrameLayout(context), EngineView, PullToRefreshTarget {
    private var current: ChromiumEngineSession? = null
    private var fullScreenView: View? = null

    override val canPullToRefresh: Boolean
        get() = fullScreenView == null && current?.webView?.overscrolledAtTop == true

    override fun attach(session: EngineSession, background: Int) {
        val target = session as ChromiumEngineSession
        setBackgroundColor(background)
        if (current === target && target.webView.parent === this) return
        current?.let { detach(it) }
        current = target
        val webView = target.webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.setBackgroundColor(background)
        webView.useActivity(context as? Activity)
        addView(webView, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        target.host = this
        target.customView?.let { showFullScreen(it) }
    }

    override fun release() {
        current?.let { detach(it) }
        current = null
    }

    /** La pestaña se cierra o deja de mostrarse aquí. */
    internal fun detach(session: ChromiumEngineSession) {
        if (session.host === this) session.host = null
        hideFullScreen()
        if (session.webView.parent === this) removeView(session.webView)
        session.webView.useActivity(null)
        if (current === session) current = null
    }

    internal fun showFullScreen(view: View) {
        if (fullScreenView === view) return
        hideFullScreen()
        (view.parent as? ViewGroup)?.removeView(view)
        fullScreenView = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    internal fun hideFullScreen() {
        fullScreenView?.let { removeView(it) }
        fullScreenView = null
    }

    /**
     * PixelCopy copia lo que se ve, incluidos los videos (que dibujan en su
     * propia superficie); si la ventana no está disponible, se dibuja la WebView.
     */
    override fun capture(onResult: (Bitmap?) -> Unit) {
        val webView = current?.webView
        if (webView == null || webView.width <= 0 || webView.height <= 0) { onResult(null); return }
        val window = (context as? Activity)?.window
        val bitmap = runCatching { Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888) }
            .getOrElse { onResult(null); return }
        if (window == null || !webView.isAttachedToWindow) {
            onResult(drawInto(webView, bitmap))
            return
        }
        val location = IntArray(2).also { webView.getLocationInWindow(it) }
        val rect = Rect(location[0], location[1], location[0] + webView.width, location[1] + webView.height)
        runCatching {
            PixelCopy.request(window, rect, bitmap, { result ->
                onResult(if (result == PixelCopy.SUCCESS) bitmap else drawInto(webView, bitmap))
            }, Handler(Looper.getMainLooper()))
        }.onFailure { onResult(drawInto(webView, bitmap)) }
    }

    private fun drawInto(view: View, bitmap: Bitmap): Bitmap? =
        runCatching { view.draw(Canvas(bitmap)); bitmap }.getOrNull()

    override fun onPictureInPictureModeChanged(inPictureInPicture: Boolean) = Unit
}
