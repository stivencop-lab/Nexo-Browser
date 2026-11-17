package com.nexo.browser.engine.cefrium

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ImportedCookiesTest {
    private val now = 1_790_000_000_000L // ms
    private fun cookie(
        name: String = "sid", value: String = "abc", host: String = "example.com", path: String = "/",
        expiry: Long = now / 1000 + 3600, secure: Boolean = true, httpOnly: Boolean = true,
        sameSite: Int = 1, scheme: Int = 2, origin: String = ""
    ) = ImportedCookie(name, value, host, path, expiry, secure, httpOnly, sameSite, scheme, origin)

    @Test fun hostOnlyCookieKeepsAttributes() {
        val (url, header) = cookie().toSetCookie(now)!!
        assertEquals("https://example.com/", url)
        assertTrue(header.startsWith("sid=abc; Path=/; Expires="))
        assertTrue(header.endsWith("; Secure; HttpOnly; SameSite=Lax"))
        assertFalse(header.contains("Domain="))
    }

    @Test fun domainCookieUsesDomainAttribute() {
        val (url, header) = cookie(host = ".Example.com", sameSite = 2).toSetCookie(now)!!
        assertEquals("https://example.com/", url)
        assertTrue(header.contains("; Domain=example.com"))
        assertTrue(header.contains("SameSite=Strict"))
    }

    @Test fun millisecondExpiryIsDetected() {
        val seconds = cookie(expiry = now / 1000 + 60)
        val millis = cookie(expiry = now + 60_000)
        assertEquals(seconds.expiryMillis, millis.expiryMillis)
        assertNull(cookie(expiry = now - 1).toSetCookie(now))
        assertNull(cookie(expiry = now / 1000 - 1).toSetCookie(now))
    }

    @Test fun privateBurnerAndPartitionedCookiesAreSkipped() {
        assertNull(cookie(origin = "^privateBrowsingId=1").toSetCookie(now))
        assertNull(cookie(origin = "^userContextId=7").toSetCookie(now))
        assertNull(cookie(origin = "^partitionKey=%28https%2Cexample.org%29").toSetCookie(now))
    }

    @Test fun plainHttpCookieUsesHttpUrl() {
        val (url, header) = cookie(secure = false, scheme = 1, sameSite = 0).toSetCookie(now)!!
        assertEquals("http://example.com/", url)
        assertFalse(header.contains("Secure"))
        assertFalse("SameSite=None sin Secure se rechazaría", header.contains("SameSite"))
    }

    @Test fun invalidCookiesAreSkipped() {
        assertNull(cookie(name = "a;b").toSetCookie(now))
        assertNull(cookie(value = "x\ny").toSetCookie(now))
        assertNull(cookie(host = "bad host").toSetCookie(now))
        assertNull(cookie(name = "__Host-id", host = ".example.com").toSetCookie(now))
        assertNull(cookie(name = "__Secure-id", secure = false).toSetCookie(now))
    }

    @Test fun expiryIsCappedAt400Days() {
        val (_, header) = cookie(expiry = now / 1000 + 5L * 365 * 24 * 3600).toSetCookie(now)!!
        val expected = ImportedCookie.httpDate(now + 400L * 24 * 3600 * 1000)
        assertTrue(header.contains("Expires=$expected"))
    }

    @Test fun customPathIsKept() {
        val (url, header) = cookie(path = "/app").toSetCookie(now)!!
        assertEquals("https://example.com/app", url)
        assertTrue(header.contains("; Path=/app;"))
    }
}
