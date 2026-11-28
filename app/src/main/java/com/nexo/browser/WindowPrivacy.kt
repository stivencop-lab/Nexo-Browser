package com.nexo.browser

import android.view.Window
import android.view.WindowManager

/** Keep private pages out of system screenshots, recordings and recents thumbnails. */
object WindowPrivacy {
    fun apply(window: Window, private: Boolean) {
        protect(window, private)
        applyAudioCapture(window.context, private)
    }

    fun protect(window: Window?, private: Boolean) {
        if (private) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Igual que FLAG_SECURE protege la imagen, en privado el audio de Nexo solo lo puede capturar
     * el sistema (Subtítulos instantáneos de Android), no otras apps con grabación de pantalla.
     * Mientras Nexo captura su propio audio para los subtítulos en vivo se mantiene abierto.
     */
    fun applyAudioCapture(context: android.content.Context, private: Boolean) {
        val selfCapture = PlaybackAudioCapture.service != null
        val policy = if (private && !selfCapture) android.media.AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM
            else android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL
        runCatching { context.getSystemService(android.media.AudioManager::class.java)?.setAllowedCapturePolicy(policy) }
    }

    fun inherit(child: Window?, parent: Window) {
        protect(child, parent.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}
