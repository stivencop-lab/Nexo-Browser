package com.nexo.browser

import com.nexo.browser.media.MediaGestures
import com.nexo.browser.media.MediaGestures.Drag
import com.nexo.browser.media.MediaGestures.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGesturesTest {
    @Test
    fun zonesLeaveThePagesOwnControlsAlone() {
        assertEquals(Zone.LEFT, MediaGestures.zone(100f, 500f, 1000, 1000))
        assertEquals(Zone.CENTER, MediaGestures.zone(500f, 500f, 1000, 1000))
        assertEquals(Zone.RIGHT, MediaGestures.zone(900f, 500f, 1000, 1000))
        assertEquals("barra inferior del reproductor", Zone.PAGE, MediaGestures.zone(500f, 900f, 1000, 1000))
        assertEquals("barra superior", Zone.PAGE, MediaGestures.zone(900f, 50f, 1000, 1000))
        assertEquals(Zone.PAGE, MediaGestures.zone(1f, 1f, 0, 0))
    }

    @Test
    fun dragKindDependsOnDirectionAndSide() {
        assertEquals(Drag.BRIGHTNESS, MediaGestures.drag(Zone.LEFT, 2f, 80f, 8f))
        assertEquals(Drag.VOLUME, MediaGestures.drag(Zone.RIGHT, -3f, -80f, 8f))
        assertEquals("vertical en el centro no es gesto", Drag.NONE, MediaGestures.drag(Zone.CENTER, 0f, 80f, 8f))
        assertEquals(Drag.SCRUB, MediaGestures.drag(Zone.CENTER, 90f, 5f, 8f))
        assertEquals(Drag.SCRUB, MediaGestures.drag(Zone.LEFT, -90f, 5f, 8f))
        assertEquals("diagonal ambigua", Drag.NONE, MediaGestures.drag(Zone.CENTER, 40f, 40f, 8f))
        assertEquals("bajo el umbral", Drag.NONE, MediaGestures.drag(Zone.LEFT, 0f, 10f, 8f))
        assertEquals(Drag.NONE, MediaGestures.drag(Zone.PAGE, 300f, 0f, 8f))
    }

    @Test
    fun scrubScalesWithDurationAndStaysInBounds() {
        assertEquals(45, MediaGestures.scrubSeconds(500f, 1000, 0.0))
        assertEquals(15, MediaGestures.scrubSeconds(500f, 1000, 20.0))
        assertEquals(-150, MediaGestures.scrubSeconds(-500f, 1000, 7200.0))
        assertEquals(0, MediaGestures.scrubSeconds(Float.NaN, 1000, 60.0))
        assertEquals(0.0, MediaGestures.scrubTarget(10.0, -30, 100.0), 0.0)
        assertEquals(100.0, MediaGestures.scrubTarget(90.0, 30, 100.0), 0.0)
        assertEquals(130.0, MediaGestures.scrubTarget(100.0, 30, 0.0), 0.0)
    }

    @Test
    fun doubleTapAndChainedTaps() {
        assertTrue(MediaGestures.continuesSeek(Zone.RIGHT, 1_250, 1_000, Zone.RIGHT, 0, null))
        assertFalse("demasiado tarde", MediaGestures.continuesSeek(Zone.RIGHT, 1_400, 1_000, Zone.RIGHT, 0, null))
        assertFalse("otro lado", MediaGestures.continuesSeek(Zone.LEFT, 1_250, 1_000, Zone.RIGHT, 0, null))
        assertFalse("el centro no salta", MediaGestures.continuesSeek(Zone.CENTER, 1_250, 1_000, Zone.CENTER, 0, null))
        assertTrue("toques seguidos tras saltar", MediaGestures.continuesSeek(Zone.LEFT, 5_000, 0, null, 5_500, Zone.LEFT))
        assertFalse(MediaGestures.continuesSeek(Zone.RIGHT, 5_000, 0, null, 5_500, Zone.LEFT))
    }

    @Test
    fun levelsAreClamped() {
        assertEquals(0.02f, MediaGestures.brightness(0.1f, -1f), 0f)
        assertEquals(1f, MediaGestures.brightness(0.9f, 0.5f), 0f)
        assertEquals(15, MediaGestures.volume(10, 1f, 15))
        assertEquals(0, MediaGestures.volume(3, -1f, 15))
        assertEquals(3, MediaGestures.volume(3, Float.NaN, 15))
    }

    @Test
    fun fitCycles() {
        assertEquals(MediaGestures.Fit.COVER, MediaGestures.Fit.CONTAIN.next())
        assertEquals(MediaGestures.Fit.CONTAIN, MediaGestures.Fit.FILL.next())
    }
}
