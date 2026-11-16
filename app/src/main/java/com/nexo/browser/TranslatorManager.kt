package com.nexo.browser

import android.content.Context
import com.nexo.browser.engine.EngineSession

/**
 * Traducción de páginas. La decisión es traducir en
 * la nube con el proveedor de subtítulos ya configurado; llega en la fase
 * del puente de páginas.
 */
object TranslatorManager {
    fun initialize(context: Context) = Unit

    fun forget(session: EngineSession) = Unit

    fun translateActive(context: Context, callback: (Boolean, String) -> Unit) {
        callback(false, "La traducción de páginas llegará en una próxima versión de Nexo.")
    }
}
