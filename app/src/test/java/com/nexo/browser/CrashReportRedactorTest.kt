package com.nexo.browser

import org.junit.Assert.*
import org.junit.Test

class CrashReportRedactorTest {
    @Test fun urlsCannotExposeCredentialsOrPrivatePaths() {
        val clean = CrashReportRedactor.redact("Failure HTTPS://alice:p4ss@example.com/private/invoice?token=secret#fragment at https://[::1]/other")
        for (value in listOf("alice", "p4ss", "invoice", "secret", "fragment", "::1", "other")) assertFalse(clean.contains(value))
        assertEquals("Failure <url-redacted> at <url-redacted>", clean)
    }

    @Test fun tokensHeadersAndAppPathsAreRemovedWhileFramesRemainUseful() {
        val clean = CrashReportRedactor.redact("Authorization: Bearer sensitive\npassword=value&api_key=another\n/data/user/0/com.nexo.browser/cache/private.json\n at Browser.load(Browser.kt:42)")
        assertFalse(clean.contains("sensitive")); assertFalse(clean.contains("value")); assertFalse(clean.contains("another"))
        assertFalse(clean.contains("private.json"))
        assertTrue(clean.contains("Browser.load(Browser.kt:42)"))
    }

    @Test fun engineDiagnosticsSanitizesApiKeysAndParsesMetrics() {
        val raw = "Pestaña: https://user:pw@bank.example/tx?token=abc sk-proj-1234567890abcdef AIzaSyD1234567890abcdef12345"
        val clean = com.nexo.browser.engine.cefrium.EngineDiagnostics.sanitizeReport(raw)
        assertFalse(clean.contains("bank.example"))
        assertFalse(clean.contains("sk-proj-1234567890abcdef"))
        assertFalse(clean.contains("AIzaSyD1234567890abcdef12345"))
        assertTrue(clean.contains("<url-redacted>"))
        assertTrue(clean.contains("<secret-redacted>"))

        val metrics = com.nexo.browser.engine.cefrium.EngineDiagnostics.parsePageMetrics(
            org.json.JSONObject.quote("""{"domNodes":420,"domInteractiveMs":185,"loadEventMs":410}""")
        )
        assertEquals("DOM=420 nodos · domInteractive=185ms · loadEvent=410ms", metrics)
        assertNull(com.nexo.browser.engine.cefrium.EngineDiagnostics.parsePageMetrics("null"))
    }
}
