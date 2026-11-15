package com.nexo.browser

import android.content.Context

/** Contexto de la aplicación para objetos que viven fuera de una Activity. */
object AppContext {
    @Volatile private var app: Context? = null

    fun initialize(context: Context) {
        app = context.applicationContext
    }

    fun get(): Context =
        requireNotNull(app) { "AppContext no inicializado" }
}
