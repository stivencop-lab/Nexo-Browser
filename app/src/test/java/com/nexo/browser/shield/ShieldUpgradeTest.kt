package com.nexo.browser.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM para las mejoras de PhantomShield:
 * motor RE2J anti-ReDoS, bloqueador de criptomineros, niveles de protección,
 * veto funcional, limpieza ampliada de rastreo/redirecciones y excepciones por dominio.
 */
class ShieldUpgradeTest {

    @Test
    fun networkEngineUsesLinearTimeRe2jAgainstPathologicalWildcards() {
        val engine = NetworkEngine()
        val rule = engine.createRule("*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b")!!
        engine.addRule(rule)
        val evilUrl = "https://example.com/" + "a".repeat(200) + "c"
        val startMs = System.currentTimeMillis()
        val blocked = engine.shouldBlock(evilUrl, "script", "https://site.example/", false)
        val elapsedMs = System.currentTimeMillis() - startMs
        assertFalse(blocked)
        assertTrue("RE2J debe evaluar en tiempo lineal (< 250ms), tardó ${elapsedMs}ms", elapsedMs < 250L)
    }

    @Test
    fun cryptominerBlockingRespectsSettingAndAllowlist() {
        val engine = NetworkEngine()
        val url = "https://coinhive.com/lib/coinhive.min.js"
        assertEquals(
            ShieldDecision.Block,
            ShieldPolicy.decide(engine, ShieldSettings(cryptominerEnabled = true), emptyList(), url, "script", "news.example", "https://news.example/")
        )
        assertEquals(
            ShieldDecision.Allow,
            ShieldPolicy.decide(engine, ShieldSettings(cryptominerEnabled = false), emptyList(), url, "script", "news.example", "https://news.example/")
        )
        assertEquals(
            ShieldDecision.Allow,
            ShieldPolicy.decide(
                engine,
                ShieldSettings(cryptominerEnabled = true, allowedDomains = listOf("coinhive.com")),
                emptyList(), url, "script", "news.example", "https://news.example/"
            )
        )
    }

    @Test
    fun strictAndAggressiveLevelsBlockTelemetryWhileSafetyVetoProtectsAuthAndMedia() {
        val engine = NetworkEngine()
        val pingUrl = "https://metrics.thirdparty.example/collect"
        // Balanced permite ping no listado; Strict/Aggressive lo bloquea.
        assertEquals(
            ShieldDecision.Allow,
            ShieldPolicy.decide(engine, ShieldSettings(protectionLevel = ShieldSettings.LEVEL_BALANCED), emptyList(), pingUrl, "ping", "site.example", "https://site.example/")
        )
        assertEquals(
            ShieldDecision.Block,
            ShieldPolicy.decide(engine, ShieldSettings(protectionLevel = ShieldSettings.LEVEL_STRICT), emptyList(), pingUrl, "ping", "site.example", "https://site.example/")
        )
        assertEquals(
            ShieldDecision.Block,
            ShieldPolicy.decide(engine, ShieldSettings(protectionLevel = ShieldSettings.LEVEL_AGGRESSIVE), emptyList(), "https://tracker.thirdparty.example/analytics/track", "xmlhttprequest", "site.example", "https://site.example/")
        )
        // El veto funcional protege OAuth, captcha y streams HLS/DASH.
        assertTrue(ShieldPolicy.isProtectedSafetyUrl("https://accounts.google.com/o/oauth2/auth"))
        assertTrue(ShieldPolicy.isProtectedSafetyUrl("https://challenges.cloudflare.com/turnstile/v0/api.js"))
        assertTrue(ShieldPolicy.isProtectedSafetyUrl("https://cdn.video.example/live/playlist.m3u8"))
        assertEquals(
            ShieldDecision.Allow,
            ShieldPolicy.decide(engine, ShieldSettings(protectionLevel = ShieldSettings.LEVEL_AGGRESSIVE), emptyList(), "https://accounts.google.com/collect", "ping", "site.example", "https://site.example/")
        )
    }

    @Test
    fun trackingStrippingAndBounceRedirectUnwrappingWorkSafely() {
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            ShieldPolicy.stripTrackingParams("https://youtu.be/dQw4w9WgXcQ?si=abcdef123456")
        )
        // En sitios genéricos, `si` NO se toca para evitar romper apps web.
        assertNull(ShieldPolicy.stripTrackingParams("https://docs.example.org/search?si=1"))

        assertEquals(
            "https://example.org/article?id=7",
            ShieldPolicy.unwrapBounceRedirect("https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Farticle%3Fid%3D7&h=AT0")
        )
        assertEquals(
            "https://news.example.com/story",
            ShieldPolicy.unwrapBounceRedirect("https://out.reddit.com/?url=https%3A%2F%2Fnews.example.com%2Fstory")
        )
        // Rechaza destinos no HTTP(S) como javascript:
        assertNull(ShieldPolicy.unwrapBounceRedirect("https://l.facebook.com/l.php?u=javascript%3Aalert(1)"))
    }
}
