package com.nexo.browser

import com.nexo.browser.media.MediaSitePrefs
import com.nexo.browser.media.NexoMediaEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexoMediaEngineTest {
    @Test
    fun clampSpeedEnforcesSupportedRange() {
        assertEquals(0.5f, MediaSitePrefs.clampSpeed(0.1f), 0.001f)
        assertEquals(1.5f, MediaSitePrefs.clampSpeed(1.5f), 0.001f)
        assertEquals(2.5f, MediaSitePrefs.clampSpeed(5.0f), 0.001f)
        assertEquals(1.0f, MediaSitePrefs.clampSpeed(Float.NaN), 0.001f)
    }

    @Test
    fun clampSeekStepFallsBackToDefaultWhenUnsupported() {
        assertEquals(5, MediaSitePrefs.clampSeekStep(5))
        assertEquals(15, MediaSitePrefs.clampSeekStep(15))
        assertEquals(30, MediaSitePrefs.clampSeekStep(30))
        assertEquals(10, MediaSitePrefs.clampSeekStep(99))
    }

    @Test
    fun clampOffsetAndFormatOffset() {
        assertEquals(-5000, MediaSitePrefs.clampOffsetMs(-9999))
        assertEquals(5000, MediaSitePrefs.clampOffsetMs(9999))
        assertEquals("+0.50 s", NexoMediaEngine.formatOffset(500))
        assertEquals("-0.25 s", NexoMediaEngine.formatOffset(-250))
        assertEquals("0.00 s", NexoMediaEngine.formatOffset(0))
    }

    @Test
    fun normalizeHostStripsSchemeAndWww() {
        assertEquals("youtube.com", MediaSitePrefs.normalizeHost("https://www.YouTube.com/watch?v=1"))
        assertEquals("vimeo.com", MediaSitePrefs.normalizeHost("vimeo.com"))
        assertEquals("", MediaSitePrefs.normalizeHost("   "))
    }

    @Test
    fun selectCuesAtTimeAppliesOffsetWithoutModifyingMediaTime() {
        val cues = listOf(
            NexoMediaEngine.TimedCue(0, 1.0, 3.0, "Primera línea"),
            NexoMediaEngine.TimedCue(1, 3.5, 6.0, "Segunda línea")
        )
        // Sin offset en t=2.8 -> primera línea
        assertEquals(listOf(0), NexoMediaEngine.selectCuesAtTime(cues, 2.8, 0).map { it.id })
        // Adelantar subtítulos +1.0 s -> en t=2.8 corresponde t_shifted=3.8 -> segunda línea
        assertEquals(listOf(1), NexoMediaEngine.selectCuesAtTime(cues, 2.8, 1000).map { it.id })
        // Retrasar subtítulos -1.0 s -> en t=4.0 corresponde t_shifted=3.0 (límite exclusivo) -> vacío
        assertTrue(NexoMediaEngine.selectCuesAtTime(cues, 4.0, -1000).isEmpty())
        // Tiempo negativo resultante se acota a 0.0 sin fallar
        assertTrue(NexoMediaEngine.selectCuesAtTime(cues, 0.2, -5000).isEmpty())
    }

    @Test
    fun siteConfigDefaultDetection() {
        assertTrue(MediaSitePrefs.SiteConfig().isDefault())
        assertFalse(MediaSitePrefs.SiteConfig(speed = 1.25f).isDefault())
        assertFalse(MediaSitePrefs.SiteConfig(captionOffsetMs = -250).isDefault())
    }
}
