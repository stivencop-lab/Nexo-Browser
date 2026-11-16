package com.nexo.browser.engine.cefrium

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cefrium.CefriumBrowser
import com.nexo.browser.PullToRefreshTarget
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineView

/**
 * Vista de páginas: muestra la superficie del navegador de la pestaña activa.
 * Cada pestaña viva tiene su navegador; al cambiar de pestaña solo cambia la
 * superficie que cuelga de aquí. La pantalla completa de un video la dibuja
 * Chromium en la misma superficie: Nexo solo oculta su interfaz.
 */
class CefriumPageView(context: Context) : FrameLayout(context), EngineView, PullToRefreshTarget {
    private var current: CefriumEngineSession? = null

    /** Chromium hace su propio "deslizar para recargar" (ver Ajustes); el de Nexo no interviene. */
    override val canPullToRefresh: Boolean get() = false

    override fun attach(session: EngineSession, background: Int) {
        val target = session as CefriumEngineSession
        setBackgroundColor(background)
        (context as? Activity)?.let { CefriumEngine.register(it) }
        if (current !== target) current?.let { detach(it) }
        current = target
        target.host = this
        target.ensureBrowser()
        attachBrowser(target)
        target.browser?.setBackgroundColor(background)
    }

    /** El navegador de [session] (nuevo o recreado) pasa a esta vista si es la pestaña mostrada. */
    internal fun attachBrowser(session: CefriumEngineSession) {
        if (current !== session) return
        val surface = session.browser?.surfaceContainer ?: return
        if (surface.parent === this) return
        (surface.parent as? ViewGroup)?.removeView(surface)
        removeAllViews()
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** El navegador de [session] se cierra o se recrea. */
    internal fun detachBrowser(session: CefriumEngineSession, browser: CefriumBrowser) {
        val surface = runCatching { browser.surfaceContainer }.getOrNull() ?: return
        if (surface.parent === this) removeView(surface)
    }

    override fun release() {
        current?.let { detach(it) }
        current = null
    }

    /** La pestaña se cierra o deja de mostrarse aquí. */
    internal fun detach(session: CefriumEngineSession) {
        if (session.host === this) session.host = null
        session.browser?.let { detachBrowser(session, it) }
        if (current === session) current = null
    }

    /** Copia de lo que se ve (incluidos los videos), con PixelCopy dentro de Cefrium. */
    override fun capture(onResult: (Bitmap?) -> Unit) {
        val browser = current?.browser
        if (browser == null || width <= 0 || height <= 0) { onResult(null); return }
        runCatching { browser.captureBitmap { bitmap -> onResult(bitmap) } }.onFailure { onResult(null) }
    }

    override fun onPictureInPictureModeChanged(inPictureInPicture: Boolean) = Unit
}
