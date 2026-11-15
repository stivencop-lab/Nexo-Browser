package com.nexo.browser

import android.content.Context
import com.nexo.browser.engine.Engines

/** Borrado de datos de navegación compartido por Ajustes y "Borrar datos al salir". */
object BrowsingData {
    fun clear(context: Context, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        val app = context.applicationContext
        TabManager.closeAllSessionsKeepingState()
        HistoryStore.clear(app)
        TabPreviewStore.clear(app)
        Engines.current.clearAllData(app, onDone)
    }
}
