package com.nexo.browser.engine

import android.content.Context
import android.content.res.Configuration
import android.view.View

/** Motor web de Nexo: crea pestañas y gestiona los datos de navegación. */
interface BrowserEngine {
    /** Nombre y versión visibles en Ajustes. */
    val displayName: String

    /** Arranque del motor antes de restaurar pestañas (p. ej. borrar perfiles privados antiguos). */
    fun initialize(context: Context) = Unit

    /** Vista que muestra la pestaña activa; implementa [EngineView]. */
    fun createView(context: Context): View

    /**
     * El motor puede aislar pestañas privadas y Burner (cookies y almacenamiento
     * propios). Si no, Nexo no las abre: compartirían datos con las normales.
     */
    fun supportsIsolatedTabs(context: Context): Boolean = true

    /** Se cerró la última pestaña privada (sin Burner): sus datos deben desaparecer. */
    fun onPrivateSessionEnded(context: Context) = Unit

    /** Descargas iniciadas por Nexo (menú contextual, tramos de descargas en paralelo). */
    val downloadFetcher: DownloadFetcher

    /** Crea una pestaña sin abrir; el llamante asigna su [EngineSession.observer] antes de [open]. */
    fun createSession(context: Context, params: SessionParams): EngineSession

    fun open(context: Context, session: EngineSession)

    fun parseState(raw: String): EngineSessionState?

    /** Conexión anticipada a una URL que el usuario va a abrir. */
    fun speculativeConnect(context: Context, url: String)

    /** Borra cookies, caché y almacenamiento web de todos los perfiles. */
    fun clearAllData(context: Context, onDone: (Boolean, String?) -> Unit)

    /** Borra los datos de un contenedor aislado (Burner). */
    fun clearContextData(context: Context, contextId: String)

    /** Vuelve a aplicar los ajustes globales (DNS, cookies, rastreo, tema…). */
    fun applySettings(context: Context)

    /** La Activity gestiona la rotación y el tamaño: el motor debe enterarse. */
    fun onConfigurationChanged(context: Context, newConfig: Configuration)

    /** Ajustes de audio para que la captura de reproducción (subtítulos) oiga la página. */
    fun applyAudioSettings(context: Context) = Unit

    /** El motor ya arrancó en este proceso (la transferencia de perfil exige que no). */
    fun isStarted(): Boolean

    /** Detiene el motor antes de transferir el perfil; [done] se llama al terminar. */
    fun shutdownForTransfer(done: () -> Unit)
}
