package com.nexo.browser

import android.app.Activity
import android.content.Intent
import java.lang.ref.WeakReference

/**
 * Pantalla del navegador capaz de mostrar lo que piden las páginas web:
 * diálogos, permisos de Android e intents externos.
 */
interface BrowserHost {
    val hostActivity: Activity

    /** Pide permisos de Android en tiempo de ejecución. */
    fun requestAndroidPermissions(permissions: Array<String>, onResult: (Boolean) -> Unit)

    /**
     * Abre otra app sin que la salida se tome como "Inicio" (que activaría
     * el mini video). Lanza ActivityNotFoundException si nadie la atiende.
     */
    fun launch(intent: Intent)

    /** Evita activar PiP automático cuando el propio motor abre un selector del sistema (cámara/archivos). */
    fun suppressNextAutoPip()
}

object BrowserHosts {
    private var ref = WeakReference<BrowserHost>(null)

    fun attach(host: BrowserHost) {
        ref = WeakReference(host)
    }

    /** Solo desvincula si [host] sigue siendo el actual (evita carreras al recrear). */
    fun detach(host: BrowserHost) {
        if (ref.get() === host) ref = WeakReference(null)
    }

    fun current(): BrowserHost? =
        ref.get()?.takeIf { host ->
            val activity = host.hostActivity
            !activity.isFinishing && !activity.isDestroyed
        }
}
