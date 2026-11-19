package com.nexo.browser

import com.nexo.browser.ai.NexoAiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SiteControlTest {
    @Test
    fun httpsConnectionIsHonestAndNeverPromisesAbsoluteSecurity() {
        val info = SiteControlModel.inspectConnection("https://www.example.com/path?a=1")
        assertEquals(SiteControlModel.ConnectionKind.HTTPS, info.kind)
        assertEquals("example.com", info.host)
        assertEquals("https://www.example.com", info.origin)
        assertEquals("HTTPS", info.badge)
        assertTrue(info.explanation.contains("no garantiza", ignoreCase = true))
        assertFalse(info.explanation.contains("100% segur", ignoreCase = true))
    }

    @Test
    fun httpAndSslBlockedAndInternalPagesAreClassifiedAccurately() {
        val http = SiteControlModel.inspectConnection("http://insecure.example.org:8080/login")
        assertEquals(SiteControlModel.ConnectionKind.HTTP, http.kind)
        assertEquals("http://insecure.example.org:8080", http.origin)
        assertEquals(NexoUi.Tone.WARNING, http.tone)

        val ssl = SiteControlModel.inspectConnection("https://badcert.example.org/", sslBlockedHost = "badcert.example.org")
        assertEquals(SiteControlModel.ConnectionKind.SSL_BLOCKED, ssl.kind)
        assertEquals(NexoUi.Tone.DANGER, ssl.tone)

        val home = SiteControlModel.inspectConnection("nexo://home")
        assertEquals(SiteControlModel.ConnectionKind.INTERNAL, home.kind)
        assertNull(home.origin)
    }

    @Test
    fun zoomStepsAndCefriumLogarithmicZoomConversionAreAccurate() {
        assertEquals(0.0, SiteControlModel.zoomPercentToCefriumLevel(100), 1e-9)
        assertTrue(abs(SiteControlModel.zoomPercentToCefriumLevel(120) - 1.0) < 1e-6)
        assertEquals(75, SiteControlModel.clampZoomPercent(20))
        assertEquals(200, SiteControlModel.clampZoomPercent(400))
        assertEquals(115, SiteControlModel.nextZoomStep(100, +1))
        assertEquals(85, SiteControlModel.nextZoomStep(100, -1))
        assertEquals(100, SiteControlModel.nextZoomStep(150, 0))
    }

    @Test
    fun aiHostNormalizationHandlesSubdomainsAndTrailingDots() {
        assertEquals("example.com", NexoAiConfig.normalizeHost("  WWW.Example.COM. "))
        assertEquals("", NexoAiConfig.normalizeHost("   "))
    }
}
