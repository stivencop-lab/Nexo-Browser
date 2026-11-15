package com.nexo.browser.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchPatternTest {
    private fun pattern(value: String) = requireNotNull(MatchPattern.parse(value)) { value }

    @Test fun allUrlsCoversWebAndFileButNotInternalSchemes() {
        val all = pattern("<all_urls>")
        assertTrue(all.matchesAllHosts)
        assertTrue(all.matches("https://example.com/"))
        assertTrue(all.matches("http://example.com:8080/a?b"))
        assertTrue(all.matches("file:///sdcard/a.html"))
        assertTrue(all.matches("wss://example.com/socket"))
        assertFalse(all.matches("chrome-extension://abc/page.html"))
        assertFalse(all.matches("javascript:alert(1)"))
        assertFalse(all.matches("about:blank"))
    }

    @Test fun wildcardSchemeAndSubdomainsIncludeTheDomainItself() {
        val p = pattern("*://*.example.com/*")
        assertTrue(p.matches("https://example.com/"))
        assertTrue(p.matches("http://a.b.example.com/x?y=1"))
        assertTrue(p.matches("https://example.com:8443/"))
        assertTrue(p.matches("ws://live.example.com/feed"))
        assertTrue(p.matches("HTTPS://WWW.Example.COM./path"))
        assertFalse(p.matches("https://badexample.com/"))
        assertFalse(p.matches("https://example.com.evil.net/"))
        assertFalse(p.matches("ftp://example.com/"))
    }

    @Test fun pathIsComparedWithQueryButNotFragment() {
        val p = pattern("https://example.com/foo*")
        assertTrue(p.matches("https://example.com/foo"))
        assertTrue(p.matches("https://example.com/foobar"))
        assertTrue(p.matches("https://example.com/foo?x=1#frag"))
        assertFalse(p.matches("https://example.com/bar/foo"))
        assertFalse(p.matches("http://example.com/foo"))
        val root = pattern("*://*/")
        assertTrue(root.matches("https://any.site/"))
        assertTrue(root.matches("https://any.site"))
        assertFalse(root.matches("https://any.site/page"))
    }

    @Test fun portsAreExactUnlessWildcard() {
        val p = pattern("http://localhost:8080/*")
        assertTrue(p.matches("http://localhost:8080/app"))
        assertFalse(p.matches("http://localhost/app"))
        assertFalse(p.matches("http://localhost:9090/app"))
        assertTrue(pattern("http://localhost:*/*").matches("http://localhost:9090/app"))
        assertTrue(pattern("https://example.com:443/*").matches("https://example.com/"))
    }

    @Test fun urlsWithCharactersThatJavaUriRejectsStillMatch() {
        val p = pattern("*://example.com/*")
        assertTrue(p.matches("https://example.com/a?b=c|d e&q=ñ"))
        assertTrue(p.matches("https://user:pass@example.com/x"))
    }

    @Test fun invalidPatternsAreRejected() {
        listOf(
            "example.com/*", "https://*foo.com/*", "https://foo.*.com/*", "chrome://settings/*",
            "http://example.com", "https:///*", "http://example.com:99999/*", "moz-extension://x/*", ""
        ).forEach { assertNull(it, MatchPattern.parse(it)) }
        assertNotNull(MatchPattern.parse("file:///*"))
    }

    @Test fun contentScriptRulesCombineMatchesExcludesAndGlobs() {
        val matcher = UrlMatcher(
            matches = listOf("*://*.example.com/*"),
            excludeMatches = listOf("*://admin.example.com/*"),
            includeGlobs = listOf("*example.com/news/*", "*example.com/blog?"),
            excludeGlobs = listOf("*draft*")
        )
        assertTrue(matcher.matches("https://www.example.com/news/today"))
        assertTrue(matcher.matches("https://www.example.com/blog2"))
        assertFalse(matcher.matches("https://www.example.com/shop"))
        assertFalse(matcher.matches("https://admin.example.com/news/x"))
        assertFalse(matcher.matches("https://www.example.com/news/draft-1"))
        assertTrue(UrlMatcher(listOf("not a pattern")).isEmpty)
    }
}
