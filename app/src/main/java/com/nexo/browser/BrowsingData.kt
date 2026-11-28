package com.nexo.browser

import android.content.Context
import com.nexo.browser.engine.Engines

/** Borrado de datos de navegación compartido por Ajustes y "Borrar datos al salir". */
object BrowsingData {
    fun clear(context: Context, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        val app = context.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var remaining = 3
        var error: String? = null
        fun finish(ok: Boolean, message: String) {
            main.post {
                if (!ok && error == null) error = message
                if (--remaining == 0) onDone(error == null, error)
            }
        }
        Engines.current.clearAllData(app) { ok, message -> finish(ok, message ?: "Datos del motor") }
        HistoryStore.clear(app) { ok -> finish(ok, "Historial") }
        TabPreviewStore.clear(app) { ok -> finish(ok, "Miniaturas") }
    }
}
