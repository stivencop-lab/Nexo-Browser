package com.nexo.browser.engine

import android.content.Context
import android.content.res.Configuration
import com.nexo.browser.engine.gecko.GeckoEngine

/** Motor web de Nexo: crea pestañas y gestiona los datos de navegación. */
interface BrowserEngine {
    /** Nombre y versión visibles en Ajustes. */
    val displayName: String

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
}

object Engines {
    val current: BrowserEngine get() = GeckoEngine
}
