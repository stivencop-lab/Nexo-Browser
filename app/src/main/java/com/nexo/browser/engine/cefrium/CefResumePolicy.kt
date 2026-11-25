package com.nexo.browser.engine.cefrium

/**
 * Reglas puras de recuperación de superficie tras segundo plano / PiP / video.
 * Sin dependencias de Android para poder probar todos los escenarios en la JVM.
 */
internal object CefResumePolicy {
    /** Tiempo máximo antes de considerar recrear un navegador sin buffer (normal vs. con video). */
    const val WATCHDOG_NORMAL_MS = 1400L
    const val WATCHDOG_MEDIA_MS = 2200L
    /**
     * Si PixelCopy devuelve éxito con píxeles negros sobre video (decodificador hardware / DRM /
     * bandas negras) o página OLED pura tras este plazo, se acepta y se revela la superficie real.
     */
    const val MEDIA_DARK_ACCEPT_MS = 220L
    const val DARK_PAGE_ACCEPT_MS = 550L

    enum class WatchdogAction {
        /** Destruir y recrear el navegador Cefrium (solo si no hubo buffer válido y aún no se recreó). */
        RECREATE_BROWSER,
        /** Retirar la cubierta y mostrar la superficie viva; jamás dejar el telón negro fijo. */
        REVEAL
    }

    /**
     * Decide si el puente puede despertar al compositor Chromium.
     * No exige `hasWindowFocus()` para no quedar bloqueado cuando aparece un diálogo del sistema
     * (permisos de notificación, panel de notificaciones o pantalla dividida).
     */
    fun shouldWake(
        resumed: Boolean,
        recovering: Boolean,
        pip: Boolean,
        surfaceReady: Boolean
    ): Boolean = resumed && recovering && !pip && surfaceReady

    /** Timeout adaptado: la reinicialización de MediaCodec tras volver de fondo tarda más. */
    fun watchdogTimeoutMs(mediaActive: Boolean): Long =
        if (mediaActive) WATCHDOG_MEDIA_MS else WATCHDOG_NORMAL_MS

    /** Decide si la muestra de PixelCopy permite retirar la cubierta y mostrar el SurfaceView. */
    fun acceptFrame(
        copySucceeded: Boolean,
        isBlack: Boolean,
        snapshotIsBlack: Boolean,
        rebuilt: Boolean,
        documentAvailable: Boolean,
        mediaActive: Boolean,
        elapsedMs: Long
    ): Boolean {
        if (!copySucceeded) return false
        if (!isBlack || snapshotIsBlack || (rebuilt && documentAvailable)) return true
        if (mediaActive && elapsedMs >= MEDIA_DARK_ACCEPT_MS) return true
        if (documentAvailable && elapsedMs >= DARK_PAGE_ACCEPT_MS) return true
        return false
    }

    /**
     * Acción al vencer el watchdog:
     * - Si ya se recreó una vez: REVEAL (nunca atrapar la cubierta negra sobre la superficie).
     * - Si hay video activo y PixelCopy sí obtuvo buffer (escena oscura/DRM): REVEAL sin destruir el video.
     * - En otro caso (superficie realmente muerta sin buffer): RECREATE_BROWSER.
     */
    fun watchdogAction(
        rebuilt: Boolean,
        mediaActive: Boolean,
        copyEverSucceeded: Boolean
    ): WatchdogAction {
        if (rebuilt) return WatchdogAction.REVEAL
        if (mediaActive && copyEverSucceeded) return WatchdogAction.REVEAL
        return WatchdogAction.RECREATE_BROWSER
    }

    /**
     * Al salir de PiP mientras la Activity estaba detenida o la superficie fue destruida durante PiP,
     * se debe marcar recuperación pendiente para garantizar `onResume()` + `setPageVisible(true)` al volver.
     */
    fun needsRecoveryAfterPipExit(
        stopped: Boolean,
        surfaceDestroyedWhilePip: Boolean
    ): Boolean = stopped || surfaceDestroyedWhilePip

    /**
     * Un redimensionado normal del SurfaceView (`surfaceChanged`, p. ej. al aparecer el teclado o
     * plegar barras) NO debe invocar `onResume()` si no había recuperación pendiente.
     * El foco entre campos nativos (`EditText`) y el navegador lo gestiona Cefrium 0.22.0
     * nativamente desde `CefriumSurfaceContainer`.
     */
    fun shouldResumeOnSurfaceChanged(
        resumed: Boolean,
        recovering: Boolean,
        pip: Boolean
    ): Boolean = resumed && recovering && !pip
}
