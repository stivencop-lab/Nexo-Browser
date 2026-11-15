package com.nexo.browser

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.EngineView
import com.nexo.browser.engine.gecko.gecko
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController

/**
 * GeckoView que pregunta a Gecko, al tocar, si la página está arriba del todo
 * y si el gesto lo maneja el navegador (no un mapa, juego o carrusel).
 */
class NexoGeckoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GeckoView(context, attrs), EngineView, PullToRefreshTarget {
    override fun attach(session: EngineSession, background: Int) {
        val gecko = session.gecko
        // Fondo del tema mientras la página pinta (evita el destello blanco).
        runCatching { gecko.compositorController.setClearColor(background) }
        coverUntilFirstPaint(background)
        setSession(gecko)
    }

    override fun release() {
        releaseSession()
    }

    override fun onPictureInPictureModeChanged(inPictureInPicture: Boolean) {
        runCatching { session?.compositorController?.onPipModeChanged(inPictureInPicture) }
    }

    override fun capture(onResult: (android.graphics.Bitmap?) -> Unit) {
        runCatching { capturePixels().accept({ onResult(it) }, { onResult(null) }) }
            .onFailure { onResult(null) }
    }

    /** true solo cuando un deslizamiento hacia abajo debe recargar la página. */
    @Volatile override var canPullToRefresh = false
        private set

    private var touchEpoch = 0L
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            ++touchEpoch
            canPullToRefresh = false
        }
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        canPullToRefresh = false
        val epoch = ++touchEpoch
        val touchedSession = session
        onTouchEventForDetailResult(event).accept { detail ->
            if (epoch != touchEpoch || session !== touchedSession || detail == null) return@accept
            val handledByBrowser = detail.handledResult() == PanZoomController.INPUT_RESULT_HANDLED
            val canScrollUp = detail.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP != 0
            val canOverscroll = detail.overscrollDirections() and PanZoomController.OVERSCROLL_FLAG_VERTICAL != 0
            canPullToRefresh = handledByBrowser && !canScrollUp && canOverscroll
        }
        return true
    }
}

