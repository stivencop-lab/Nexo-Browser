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
}
