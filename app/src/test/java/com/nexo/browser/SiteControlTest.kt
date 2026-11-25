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

    @Test
    fun siteDataMatchingAndStorageSummaryFormat() {
        assertTrue(SiteControlModel.siteDataMatches("example.com", "https://www.example.com", "example.com"))
        assertTrue(SiteControlModel.siteDataMatches("www.example.com", "https://example.com", "example.com"))
        assertTrue(SiteControlModel.siteDataMatches("https://www.example.com", "https://www.example.com", "example.com"))
        assertFalse(SiteControlModel.siteDataMatches("https://other.example.com", "https://www.example.com", "example.com"))
        assertFalse(SiteControlModel.siteDataMatches("https://www.example.com:8443", "https://www.example.com", "example.com"))

        assertEquals("0 B", SiteControlModel.formatStorageBytes(0L))
        assertEquals("512 B", SiteControlModel.formatStorageBytes(512L))
        assertEquals("4 KB", SiteControlModel.formatStorageBytes(4096L))
        assertEquals("1.5 MB", SiteControlModel.formatStorageBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", SiteControlModel.formatStorageBytes(2L * 1024 * 1024 * 1024))

        assertEquals("4 cookies · 1.5 MB almacenados", SiteControlModel.formatSiteDataSummary(4L, (1.5 * 1024 * 1024).toLong()))
        assertEquals("1 cookie guardada", SiteControlModel.formatSiteDataSummary(1L, 0L))
        assertEquals("Política de cookies según Ajustes", SiteControlModel.formatSiteDataSummary(null, null))
    }

    @Test
    fun navigationHistoryBuildsRelativeBackAndForwardOffsets() {
        val entries = listOf(
            Triple(false, "https://one.example/", "Page One"),
            Triple(false, "https://two.example/", "Page Two"),
            Triple(true, "https://three.example/", "Current"),
            Triple(false, "https://four.example/", "Page Four"),
            Triple(false, "https://five.example/", "")
        )
        val back = SiteControlModel.buildHistoryItems(entries, backwards = true)
        assertEquals(listOf(-1, -2), back.map { it.offset })
        assertEquals(listOf("Page Two", "Page One"), back.map { it.title })

        val fwd = SiteControlModel.buildHistoryItems(entries, backwards = false)
        assertEquals(listOf(1, 2), fwd.map { it.offset })
        assertEquals(listOf("https://four.example/", "https://five.example/"), fwd.map { it.url })

        assertTrue(SiteControlModel.buildHistoryItems(emptyList(), backwards = true).isEmpty())
    }
}
