package com.nexo.browser.engine.cefrium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM puras para la política de recuperación de superficie ([CefResumePolicy]),
 * garantizando que no vuelva a producirse pantalla negra al retomar páginas o videos.
 */
class CefResumePolicyTest {

    @Test
    fun shouldWakeDoesNotRequireWindowFocus() {
        assertTrue(CefResumePolicy.shouldWake(resumed = true, recovering = true, pip = false, surfaceReady = true))
        assertFalse(CefResumePolicy.shouldWake(resumed = false, recovering = true, pip = false, surfaceReady = true))
        assertFalse(CefResumePolicy.shouldWake(resumed = true, recovering = false, pip = false, surfaceReady = true))
        assertFalse(CefResumePolicy.shouldWake(resumed = true, recovering = true, pip = true, surfaceReady = true))
        assertFalse(CefResumePolicy.shouldWake(resumed = true, recovering = true, pip = false, surfaceReady = false))
    }

    @Test
    fun watchdogTimeoutIsLongerForActiveMedia() {
        assertEquals(CefResumePolicy.WATCHDOG_NORMAL_MS, CefResumePolicy.watchdogTimeoutMs(mediaActive = false))
        assertEquals(CefResumePolicy.WATCHDOG_MEDIA_MS, CefResumePolicy.watchdogTimeoutMs(mediaActive = true))
        assertTrue(CefResumePolicy.watchdogTimeoutMs(mediaActive = true) > CefResumePolicy.watchdogTimeoutMs(mediaActive = false))
    }

    @Test
    fun acceptFrameHandlesVideoDrmAndOledDarkPagesWithoutHanging() {
        // Si PixelCopy falló, nunca aceptar.
        assertFalse(
            CefResumePolicy.acceptFrame(
                copySucceeded = false, isBlack = false, snapshotIsBlack = false,
                rebuilt = false, documentAvailable = true, mediaActive = true, elapsedMs = 1000L
            )
        )
        // Fotograma visible normal -> aceptar de inmediato.
        assertTrue(
            CefResumePolicy.acceptFrame(
                copySucceeded = true, isBlack = false, snapshotIsBlack = false,
                rebuilt = false, documentAvailable = true, mediaActive = false, elapsedMs = 0L
            )
        )
        // Video hardware / DRM negro: espera breve, luego se acepta en >= MEDIA_DARK_ACCEPT_MS.
        assertFalse(
            CefResumePolicy.acceptFrame(
                copySucceeded = true, isBlack = true, snapshotIsBlack = false,
                rebuilt = false, documentAvailable = true, mediaActive = true, elapsedMs = 100L
            )
        )
        assertTrue(
            CefResumePolicy.acceptFrame(
                copySucceeded = true, isBlack = true, snapshotIsBlack = false,
                rebuilt = false, documentAvailable = true, mediaActive = true, elapsedMs = CefResumePolicy.MEDIA_DARK_ACCEPT_MS
            )
        )
        // Página OLED oscura normal: se acepta al superar DARK_PAGE_ACCEPT_MS.
        assertFalse(
            CefResumePolicy.acceptFrame(
                copySucceeded = true, isBlack = true, snapshotIsBlack = false,
                rebuilt = false, documentAvailable = true, mediaActive = false, elapsedMs = 200L
            )
        )
        assertTrue(
            CefResumePolicy.acceptFrame(
                copySucceeded = true, isBlack = true, snapshotIsBlack = false,
                rebuilt = false, documentAvailable = true, mediaActive = false, elapsedMs = CefResumePolicy.DARK_PAGE_ACCEPT_MS
            )
        )
    }

    @Test
    fun watchdogNeverTrapsBlackOverlayAndProtectsLiveVideo() {
        // Si ya se recreó una vez: REVEAL obligatorio (jamás dejar cubierta negra permanente).
        assertEquals(
            CefResumePolicy.WatchdogAction.REVEAL,
            CefResumePolicy.watchdogAction(rebuilt = true, mediaActive = false, copyEverSucceeded = false)
        )
        // Si hay video activo y PixelCopy respondió (escena oscura/DRM): REVEAL sin destruir el reproductor.
        assertEquals(
            CefResumePolicy.WatchdogAction.REVEAL,
            CefResumePolicy.watchdogAction(rebuilt = false, mediaActive = true, copyEverSucceeded = true)
        )
        // Si nunca hubo buffer y no se ha recreado: recrear navegador.
        assertEquals(
            CefResumePolicy.WatchdogAction.RECREATE_BROWSER,
            CefResumePolicy.watchdogAction(rebuilt = false, mediaActive = false, copyEverSucceeded = false)
        )
        assertEquals(
            CefResumePolicy.WatchdogAction.RECREATE_BROWSER,
            CefResumePolicy.watchdogAction(rebuilt = false, mediaActive = true, copyEverSucceeded = false)
        )
    }

    @Test
    fun pipExitTriggersRecoveryWhenStoppedOrSurfaceDestroyed() {
        assertTrue(CefResumePolicy.needsRecoveryAfterPipExit(stopped = true, surfaceDestroyedWhilePip = false))
        assertTrue(CefResumePolicy.needsRecoveryAfterPipExit(stopped = false, surfaceDestroyedWhilePip = true))
        assertFalse(CefResumePolicy.needsRecoveryAfterPipExit(stopped = false, surfaceDestroyedWhilePip = false))
    }

    @Test
    fun browserNeverStealsFocusWhenNativeInputIsFocusedOrOnNormalSurfaceResize() {
        assertTrue(CefResumePolicy.shouldFocusBrowser(windowFocused = true, nativeInputFocused = false))
        assertFalse(CefResumePolicy.shouldFocusBrowser(windowFocused = true, nativeInputFocused = true))
        assertFalse(CefResumePolicy.shouldFocusBrowser(windowFocused = false, nativeInputFocused = false))

        // Un redimensionado normal (p. ej. al abrir el teclado en el omnibox) sin recuperación pendiente no debe invocar onResume/setFocus.
        assertFalse(CefResumePolicy.shouldResumeOnSurfaceChanged(resumed = true, recovering = false, pip = false))
        assertTrue(CefResumePolicy.shouldResumeOnSurfaceChanged(resumed = true, recovering = true, pip = false))
        assertFalse(CefResumePolicy.shouldResumeOnSurfaceChanged(resumed = true, recovering = true, pip = true))
        assertFalse(CefResumePolicy.shouldResumeOnSurfaceChanged(resumed = false, recovering = true, pip = false))
    }
}
