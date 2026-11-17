package com.nexo.browser.shield

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldAiTest {
    private fun config(vararg pairs: Pair<String, Any>) = JSONObject().apply {
        put("enabled", true); put("consent", true); put("endpoint", "https://api.openai.com/v1"); put("model", "m")
        pairs.forEach { put(it.first, it.second) }
    }

    @Test
    fun publicHostMatchesTheExtension() {
        assertTrue(ShieldAi.publicHost("news.example.com".replace("example", "sample")))
        assertFalse(ShieldAi.publicHost("localhost"))
        assertFalse(ShieldAi.publicHost("192.168.1.1"))
        assertFalse(ShieldAi.publicHost("router.lan"))
        assertFalse(ShieldAi.publicHost("Mayus.com"))
        assertFalse(ShieldAi.publicHost(null))
    }

    @Test
    fun aiNeedsConsentEndpointAndAnAuthorizedSite() {
        assertFalse(ShieldAi.Config.from(null).allows("news.com"))
        assertFalse(ShieldAi.Config.from(config("consent" to false, "scope" to "public")).allows("news.com"))
        assertFalse(ShieldAi.Config.from(config("endpoint" to "http://api.x.com", "scope" to "public")).allows("news.com"))
        val selected = ShieldAi.Config.from(config("allowedHosts" to JSONArray().put("news.com")))
        assertTrue(selected.allows("news.com"))
        assertFalse(selected.allows("www.news.com"))
        assertTrue(ShieldAi.Config.from(config("allowedHosts" to JSONArray().put("news.com"), "includeSubdomains" to true)).allows("www.news.com"))
        val public = ShieldAi.Config.from(config("scope" to "public", "excludedHosts" to JSONArray().put("bank.com"), "detail" to "rich"))
        assertTrue(public.allows("any-site.com"))
        assertFalse(public.allows("online.bank.com"))
        assertFalse(public.allows("intranet"))
        assertEquals("rich", public.detail)
    }

    @Test
    fun learnedDomainsBlockOnlyCurrentBlockedEntriesAndRespectNeverBlock() {
        val now = 1_000_000_000_000L
        val day = 86_400_000L
        val raw = JSONObject().put("salt", "abc123").put("allowed", JSONArray().put("allowed-ads.com")).put("entries", JSONArray()
            .put(JSONObject().put("domain", "adnet.com").put("state", "blocked").put("first", now - day).put("last", now - day))
            .put(JSONObject().put("domain", "old-ads.com").put("state", "blocked").put("first", now - 200 * day).put("last", now - 100 * day))
            .put(JSONObject().put("domain", "watch.com").put("state", "watch").put("first", now).put("last", now))
            .put(JSONObject().put("domain", "allowed-ads.com").put("state", "blocked").put("first", now).put("last", now)))
        val learned = ShieldAi.Learned.from(raw, now)
        assertEquals(setOf("adnet.com"), learned.blocked)
        assertEquals(1, learned.watching)
        assertTrue(learned.blocks("cdn.adnet.com"))
        assertFalse(learned.blocks("old-ads.com"))
        assertFalse(learned.blocks("allowed-ads.com"))
        assertFalse(learned.blocks("notadnet.com"))
    }

    @Test
    fun tabStateFollowsDetectorReports() {
        val state = ShieldAi.TabState()
        state.onClassified(JSONObject().put("ok", true).put("learned", 2).put("decisions", JSONArray()
            .put(JSONObject().put("id", "a").put("hide", true)).put(JSONObject().put("id", "b").put("deferred", true))), 1)
        assertEquals(1, state.scans); assertEquals(1, state.analyzed); assertEquals("ok", state.code); assertEquals(2, state.learned)
        assertEquals(3, state.onPage(JSONObject().put("hidden", 3).put("sources", JSONObject().put("model", 3)).put("code", "ok"), 2))
        assertEquals(3, state.hidden); assertEquals(3, state.model)
        assertEquals(-3, state.onPage(JSONObject().put("restored", true).put("code", "reset"), 3))
        assertEquals(0, state.hidden); assertEquals("reset", state.code)
        state.onClassified(JSONObject().put("ok", false).put("code", "auth"), 4)
        assertEquals("auth", state.code)
        assertTrue(state.toJson().getBoolean("active"))
    }
}
