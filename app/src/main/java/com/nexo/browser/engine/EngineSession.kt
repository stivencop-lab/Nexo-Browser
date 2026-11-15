package com.nexo.browser.engine

import android.app.Activity
import android.graphics.Bitmap
import java.io.InputStream

/**
 * Pestaña viva de un motor web, sin tipos del motor.
 *
 * Nexo identifica cada pestaña abierta por esta instancia (subtítulos,
 * permisos, diálogos y medios la usan como clave), así que un motor debe
 * devolver siempre el mismo objeto para la misma pestaña.
 */
interface EngineSession {
    /** El motor ya creó la pestaña y acepta navegaciones. */
    val isOpen: Boolean

    /** Receptor de los eventos de la pestaña; `null` al cerrarla. */
    var observer: EngineObserver?

    val finder: EngineFinder

    fun loadUrl(url: String)
    fun reload(bypassCache: Boolean = false)
    fun goBack()
    fun goForward()

    /** Pestaña visible (o con medios que deben seguir vivos) frente a dormida. */
    fun setActive(active: Boolean)
    fun setFocused(focused: Boolean)
    fun setHighPriority(high: Boolean)
    fun setSuspendMediaWhenInactive(suspend: Boolean)
    fun setDesktopMode(enabled: Boolean)
    fun setTrackingProtection(enabled: Boolean)

    fun exitFullScreen()

    /** Pide al motor que entregue ya el estado de navegación pendiente. */
    fun flushState()

    /** Olvida el historial atrás/adelante (rutas que cargaron una página interna). */
    fun purgeHistory()
    fun restoreState(state: EngineSessionState)

    /** Página completa como PDF; [onResult] recibe el flujo o el error. */
    fun saveAsPdf(onResult: (InputStream?, Throwable?) -> Unit)

    /**
     * Motores sin PDF directo: abre el diálogo de impresión del sistema
     * ("Guardar como PDF"). Devuelve false si el motor usa [saveAsPdf].
     */
    fun printWithSystemDialog(activity: Activity, title: String): Boolean = false

    fun close()
}

/** Historial serializado de una pestaña, en el formato propio del motor. */
interface EngineSessionState {
    /** URL de la entrada actual, si el motor la conoce. */
    val currentUri: String?

    fun serialize(): String
}

/** Buscar en la página. */
interface EngineFinder {
    fun find(term: String, backwards: Boolean, onResult: (FindResult?) -> Unit)
    fun clear()
}

data class FindResult(val found: Boolean, val current: Int, val total: Int)

/** Vista que muestra una [EngineSession]. */
interface EngineView {
    fun attach(session: EngineSession, background: Int)
    fun release()
    fun capture(onResult: (Bitmap?) -> Unit)
    fun onPictureInPictureModeChanged(inPictureInPicture: Boolean)
}

/** Lo que había bajo el dedo en una pulsación larga. */
data class EngineContextElement(
    val linkUri: String?,
    val srcUri: String?,
    val title: String?,
    val altText: String?,
    val linkText: String?
)

/** Navegación que el motor propone antes de hacerla. */
data class EngineLoadRequest(
    val uri: String,
    val hasUserGesture: Boolean,
    val isDirectNavigation: Boolean,
    val opensNewWindow: Boolean
)

enum class LoadDecision {
    /** Que el motor haga lo de siempre. */
    DEFAULT,
    ALLOW,
    DENY,

    /** La decisión llegará después por el callback de [EngineObserver.onLoadRequest]. */
    PENDING
}

/** Respuesta que el motor no puede mostrar y que Nexo debe descargar. */
interface EngineDownload {
    val uri: String
    fun discard()

    /** Respuesta lista para leer. Bloquea: llamar fuera del hilo principal. */
    fun open(): DownloadResponse
}

/** Respuesta HTTP de una descarga, sin tipos del motor. */
class DownloadResponse(
    val uri: String,
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: InputStream?
) {
    fun close() {
        runCatching { body?.close() }
    }
}

/** Peticiones de descarga con las cookies del perfil normal o privado. */
interface DownloadFetcher {
    /** Bloquea: llamar fuera del hilo principal. Lanza IOException si no hay respuesta. */
    fun fetch(url: String, headers: Map<String, String>, referrer: String?, isPrivate: Boolean): DownloadResponse
}

/** Reproductor multimedia de una página, controlable desde la notificación. */
interface EngineMedia {
    val isActive: Boolean
    fun play()
    fun pause()
    fun stop()
}

enum class MediaEvent { ACTIVATED, DEACTIVATED, PLAY, PAUSE, STOP, METADATA }

enum class ProcessExit { CRASH, KILL }

/**
 * Eventos de una pestaña. Todos llegan en el hilo principal y con la sesión
 * que los produce, para descartar los de una sesión ya sustituida.
 */
interface EngineObserver {
    fun onLocationChange(session: EngineSession, url: String?, hasUserGesture: Boolean) = Unit

    /**
     * [decide] solo se usa si se devuelve [LoadDecision.PENDING]; debe llamarse
     * una única vez con `true` (permitir) o `false` (bloquear).
     */
    fun onLoadRequest(
        session: EngineSession,
        request: EngineLoadRequest,
        decide: (Boolean) -> Unit
    ): LoadDecision = LoadDecision.DEFAULT

    /** Ventana nueva pedida por la página; devuelve una sesión sin abrir o `null` para bloquearla. */
    fun onNewWindow(session: EngineSession, uri: String): EngineSession? = null

    fun onCanGoBack(session: EngineSession, canGoBack: Boolean) = Unit
    fun onCanGoForward(session: EngineSession, canGoForward: Boolean) = Unit
    fun onTitle(session: EngineSession, title: String?) = Unit
    fun onPageStart(session: EngineSession, url: String) = Unit
    fun onProgress(session: EngineSession, progress: Int) = Unit
    fun onPageStop(session: EngineSession, success: Boolean) = Unit
    fun onStateChange(session: EngineSession, state: EngineSessionState) = Unit
    fun onFirstContentfulPaint(session: EngineSession) = Unit
    fun onProcessExit(session: EngineSession, exit: ProcessExit) = Unit
    fun onContextMenu(session: EngineSession, element: EngineContextElement) = Unit
    fun onDownload(session: EngineSession, download: EngineDownload) = Unit
    fun onCloseRequest(session: EngineSession) = Unit
    fun onFullScreen(session: EngineSession, fullScreen: Boolean) = Unit
    fun onMedia(session: EngineSession, event: MediaEvent, media: EngineMedia, title: String?) = Unit
}

/** Cómo se crea una pestaña. */
data class SessionParams(
    val isPrivate: Boolean,
    /** Contenedor aislado (Burner); `null` para el perfil normal o privado común. */
    val contextId: String?,
    val desktopMode: Boolean,
    val suspendMediaWhenInactive: Boolean,
    val trackingProtection: Boolean
)
