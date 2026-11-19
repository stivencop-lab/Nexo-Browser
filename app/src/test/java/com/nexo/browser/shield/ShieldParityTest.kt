package com.nexo.browser.shield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Los mismos casos que `src/test/js/phantom-blocking.test.cjs`, contra el
 * motor Kotlin: la variante Chromium debe bloquear lo mismo que la extensión.
 */
class ShieldParityTest {
    private val assets = File("src/main/assets/extensions/phantomshield")
    private val bundled = BundledData.parse { File(assets, it).readText() }
    private fun build(texts: Map<String, String> = emptyMap()) = FilterLists.build(bundled, texts)

    @Test fun cosmeticEngineSeparatesSiteRulesGenericLookupsAndAlwaysOnSelectors() {
        val cosmetic = CosmeticEngine()
        assertTrue(cosmetic.addLine("news.example.com,blog.example.org##.promo-box"))
        assertTrue(cosmetic.addLine("##.ad-leaderboard"))
        assertTrue(cosmetic.addLine("###sponsor-top"))
        assertTrue(cosmetic.addLine("##a[href^=\"https://ads.example.net/\"]"))
        val site = cosmetic.forHost("www.news.example.com")
        assertEquals(listOf(".promo-box", "a[href^=\"https://ads.example.net/\"]"), site.selectors)
        assertTrue(site.generic)
        assertEquals(listOf("a[href^=\"https://ads.example.net/\"]"), cosmetic.forHost("other.example.net").selectors)
        assertEquals(listOf(".ad-leaderboard", "#sponsor-top"),
            cosmetic.lookup("other.example.net", listOf("content", "ad-leaderboard"), listOf("main", "sponsor-top")))
        assertEquals(emptyList<String>(), cosmetic.lookup("other.example.net", listOf("content"), listOf("main")))
    }

    @Test fun cosmeticExceptionsAndHideExceptionsAreHonoured() {
        val cosmetic = CosmeticEngine()
        cosmetic.addLine("##.ad-box")
        cosmetic.addLine("shop.example.com##.promo")
        cosmetic.addLine("shop.example.com#@#.promo")
        cosmetic.addLine("docs.example.org#@#.ad-box")
        assertEquals(emptyList<String>(), cosmetic.forHost("shop.example.com").selectors)
        assertEquals(emptyList<String>(), cosmetic.lookup("docs.example.org", listOf("ad-box"), emptyList()))
        assertEquals(listOf(".ad-box"), cosmetic.lookup("news.example.org", listOf("ad-box"), emptyList()))
        assertTrue(cosmetic.addHideException("@@||fragile.example^\$generichide"))
        assertFalse(cosmetic.forHost("www.fragile.example").generic)
        assertEquals(emptyList<String>(), cosmetic.lookup("www.fragile.example", listOf("ad-box"), emptyList()))
        assertTrue(cosmetic.addHideException("@@||bank.example^\$elemhide,document"))
        cosmetic.addLine("bank.example##.banner")
        assertEquals(emptyList<String>(), cosmetic.forHost("bank.example").selectors)
        assertFalse(cosmetic.addHideException("@@||cdn.example^\$generichide,domain=a.example"))
    }

    @Test fun proceduralFiltersScriptletsAndStyleInjectionsAreNeverCss() {
        val cosmetic = CosmeticEngine()
        for (line in listOf("example.com##+js(set, a, b)", "example.com##.x:has-text(Ad)", "example.com##.x:style(color: red)",
            "example.com##^script:has-text(x)", "example.com#?#.x:-abp-has(.y)", "example.com#\$#abort-on-property-read x",
            "example.com##.x { display: block }", "example.*##.x", "~example.com##.x")) {
            assertFalse(line, cosmetic.addLine(line))
        }
        assertEquals(0, cosmetic.count())
        assertTrue(cosmetic.addLine("example.com##div:has(> .sponsored-label)"))
    }

    @Test fun bundledCosmeticRulesCoverYouTubeOnMobileAndDesktop() {
        val cosmetic = build().cosmetic
        val mobile = cosmetic.forHost("m.youtube.com").selectors
        for (selector in listOf("ad-slot-renderer", "ytm-promoted-sparkles-web-renderer", "ytd-ad-slot-renderer", "#player-ads")) {
            assertTrue(selector, selector in mobile)
        }
        assertFalse("ad-slot-renderer" in cosmetic.forHost("example.org").selectors)
        assertEquals(listOf(".adsbygoogle"), cosmetic.lookup("example.org", listOf("adsbygoogle"), emptyList()))
    }

    @Test fun downloadedListsFeedBothEnginesAndPlainDomainsNeedNoRuleObjects() {
        val text = listOf("[Adblock Plus 2.0]", "! comment", "||ads.tracker-one.example^", "||cdn.example.com/banners/\$image,third-party",
            "@@||cdn.example.com/banners/logo.png", "##.sidebar-ad", "news.example.org##.native-ad", "@@||fragile.example^\$generichide",
            "||redirected.example^\$redirect=noopjs", "example.com##+js(nowoif)", "||uses-from.example^\$script,from=site.example").joinToString("\n")
        val result = build(mapOf("easylist" to text))
        val network = result.network
        val cosmetic = result.cosmetic
        assertTrue(network.shouldBlock("https://x.ads.tracker-one.example/p.gif", "image", "https://site.example/", false))
        assertTrue("ads.tracker-one.example" in network.plainDomains)
        assertNull("dedupe index is released after loading", network.ruleKeys)
        assertTrue(network.shouldBlock("https://cdn.example.com/banners/top.png", "image", "https://site.example/", false))
        assertFalse(network.shouldBlock("https://cdn.example.com/banners/logo.png", "image", "https://site.example/", false))
        assertFalse(network.shouldBlock("https://redirected.example/x.js", "script", "https://site.example/", false))
        assertTrue(network.shouldBlock("https://uses-from.example/a.js", "script", "https://site.example/", false))
        assertFalse(network.shouldBlock("https://uses-from.example/a.js", "script", "https://other.example/", false))
        assertEquals(listOf(".sidebar-ad"), cosmetic.lookup("site.example", listOf("sidebar-ad"), emptyList()))
        assertTrue(".native-ad" in cosmetic.forHost("news.example.org").selectors)
        assertFalse(cosmetic.forHost("fragile.example").generic)
        assertTrue(result.networkCount > 300 && result.cosmeticCount > 100)
    }

    @Test fun patternRulesCompileTheirRegexOnlyWhenFirstEvaluated() {
        val network = NetworkEngine()
        val rule = network.createRule("||cdn.example.com/adserver/*/banner\$image")!!
        assertFalse(network.isCompiled(rule))
        network.addRule(rule)
        assertTrue(network.shouldBlock("https://cdn.example.com/adserver/9/banner", "image", "https://site.example/", false))
        assertTrue(network.isCompiled(rule))
        assertNull(network.createRule("||bad host/path"))
    }

    @Test fun bundledDomainListsAreValidHostnamesAndAvoidBreakageProneServices() {
        val all = bundled.adDomains + bundled.trackerDomains + bundled.popupDomains
        assertTrue(bundled.adDomains.size >= 200 && bundled.trackerDomains.size >= 70)
        val valid = Regex("""^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])\.[a-z0-9-]+$""")
        for (domain in all) assertTrue(domain, valid.matches(domain))
        for (risky in listOf("facebook.net", "connect.facebook.net", "sentry-cdn.com", "googletagmanager.com", "google.com",
            "youtube.com", "gstatic.com", "googlevideo.com")) assertFalse(risky, risky in all)
    }

    // --- Fondo (onBeforeRequest) ---

    private val engine = build().network
    private val settings = ShieldSettings()
    private fun decide(url: String, type: String, top: String = "news.example.org", doc: String = "https://news.example.org/story",
                       s: ShieldSettings = settings) =
        ShieldPolicy.decide(engine, s, bundled.popupDomains, url, type, if (type == "main_frame") UrlParts.host(url) else top, doc)

    @Test fun pagesTheUserOpensAreNotCancelledOnlyPopupNetworksAre() {
        assertEquals(ShieldDecision.Allow, decide("https://www.googleadservices.com/pagead/aclk?sa=L", "main_frame"))
        assertEquals(ShieldDecision.Allow, decide("https://www.taboola.com/", "main_frame"))
        assertEquals(ShieldDecision.BlockPage, decide("https://popads.net/landing", "main_frame"))
        assertEquals(ShieldDecision.Block, decide("https://doubleclick.net/ad.js", "script"))
    }

    @Test fun youTubeAdEndpointsAreBlockedWhilePlaybackContinues() {
        val top = "m.youtube.com"; val doc = "https://m.youtube.com/watch?v=abc"
        for (url in listOf("https://m.youtube.com/pagead/adview?ai=1", "https://www.youtube.com/api/stats/ads?ver=2",
            "https://www.youtube.com/get_midroll_info?ei=1", "https://www.youtube.com/youtubei/v1/player/ad_break?key=1",
            "https://www.youtube.com/ptracking?html5=1")) {
            assertEquals(url, ShieldDecision.Block, decide(url, "xmlhttprequest", top, doc))
        }
        for (url in listOf("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", "https://m.youtube.com/youtubei/v1/next",
            "https://rr2---sn-abc.googlevideo.com/videoplayback?expire=1", "https://i.ytimg.com/vi/abc/hq720.jpg")) {
            assertEquals(url, ShieldDecision.Allow, decide(url, if ("ytimg" in url) "image" else "xmlhttprequest", top, doc))
        }
    }

    @Test fun disabledSitesAndGlobalSwitchAllowEverything() {
        val paused = ShieldSettings(disabledDomains = listOf("news.example.org"))
        assertEquals(ShieldDecision.Allow, decide("https://doubleclick.net/ad.js", "script", s = paused))
        assertEquals(ShieldDecision.Allow, decide("https://doubleclick.net/ad.js", "script", s = ShieldSettings(adblockEnabled = false)))
        assertEquals(ShieldDecision.Block, decide("https://doubleclick.net/ad.js", "script", top = "other.example"))
    }

    @Test fun surrogatesReplaceExactTrackerScripts() {
        assertEquals(ShieldDecision.Surrogate("web-accessible/ga-surrogate.js"),
            decide("https://www.google-analytics.com/analytics.js", "script"))
        assertEquals(ShieldDecision.Surrogate("web-accessible/noop.js"),
            decide("https://connect.facebook.net/en_US/fbevents.js", "script"))
        assertEquals(ShieldDecision.Allow, decide("https://connect.facebook.net/en_US/sdk.js", "script"))
    }

    @Test fun trackingParametersAreStripped() {
        assertEquals("https://shop.example/p?id=4#top",
            ShieldPolicy.stripTrackingParams("https://shop.example/p?utm_source=x&id=4&fbclid=abc#top"))
        assertEquals("https://shop.example/p", ShieldPolicy.stripTrackingParams("https://shop.example/p?gclid=1"))
        assertNull(ShieldPolicy.stripTrackingParams("https://shop.example/p?id=4"))
        assertNull(ShieldPolicy.stripTrackingParams("https://shop.example/p"))
    }

    @Test fun popupRulesOnlyApplyToNewWindowsFromOtherSites() {
        val result = build(mapOf("easylist" to "||popnet.example^\$popup\n" + "! pad\n".repeat(40)))
        val net = result.network
        assertTrue(ShieldPolicy.blocksPopup(net, settings, bundled.popupDomains, "https://popnet.example/x", "https://news.example.org/"))
        assertFalse("not as a resource", net.shouldBlock("https://popnet.example/x", "script", "https://news.example.org/", false))
        assertTrue(ShieldPolicy.blocksPopup(net, settings, bundled.popupDomains, "https://popads.net/x", "https://news.example.org/"))
        assertFalse(ShieldPolicy.blocksPopup(net, ShieldSettings(popupBlockEnabled = false), bundled.popupDomains,
            "https://popnet.example/x", "https://news.example.org/"))
        assertFalse(ShieldPolicy.blocksPopup(net, settings, bundled.popupDomains, "https://login.example.org/", "https://news.example.org/"))
    }

    @Test fun importantBeatsExceptionsAndBadfilterDisablesRules() {
        val text = listOf("||ads.example^\$important", "@@||ads.example^", "||track.example^", "||track.example^\$badfilter",
            "||cdn.site.example/banner/*\$image", "@@||cdn.site.example/banner/ok\$image").joinToString("\n")
        val result = build(mapOf("easylist" to text))
        val net = result.network
        // Mismos totales que el motor JS con esta lista (comprobado con node: 322 de red, 113 cosméticas).
        assertEquals(322, result.networkCount)
        assertEquals(113, result.cosmeticCount)
        assertEquals(listOf(228, 84, 27, 113), listOf(bundled.adDomains.size, bundled.trackerDomains.size,
            bundled.popupDomains.size, bundled.cosmeticRules.size))
        assertTrue(net.shouldBlock("https://ads.example/a.js", "script", "https://site.example/", false))
        assertFalse(net.shouldBlock("https://track.example/p", "image", "https://site.example/", false))
        assertTrue(net.shouldBlock("https://cdn.site.example/banner/x.png", "image", "https://site.example/", false))
        assertFalse(net.shouldBlock("https://cdn.site.example/banner/ok.png", "image", "https://site.example/", false))
    }

    @Test fun unknownTypesSkipTypedRulesButKeepPlainOnes() {
        val net = build(mapOf("easylist" to "||typed.example^\$script\n||cdn.example/~ad~^\$~image\n||plain.example/ads/")).network
        assertFalse(net.shouldBlock("https://typed.example/x", NetworkEngine.UNKNOWN, "https://site.example/", false))
        assertTrue(net.shouldBlock("https://typed.example/x.js", ShieldPolicy.inferType("https://typed.example/x.js", false, "*/*", null),
            "https://site.example/", false))
        assertTrue(net.shouldBlock("https://plain.example/ads/1", NetworkEngine.UNKNOWN, "https://site.example/", false))
    }

    @Test fun requestTypesAreInferred() {
        assertEquals("main_frame", ShieldPolicy.inferType("https://a.example/", true, null, null))
        assertEquals("sub_frame", ShieldPolicy.inferType("https://a.example/f", false, "text/html,application/xhtml+xml", null))
        assertEquals("image", ShieldPolicy.inferType("https://a.example/i", false, "image/avif,image/webp,*/*", null))
        assertEquals("stylesheet", ShieldPolicy.inferType("https://a.example/s.css?v=1", false, "*/*", null))
        assertEquals("script", ShieldPolicy.inferType("https://a.example/x", false, "*/*", "script"))
        assertEquals(NetworkEngine.UNKNOWN, ShieldPolicy.inferType("https://a.example/api", false, "*/*", null))
    }

    @Test fun bundledArraysParseStringsAndComments() {
        val js = "window.X = [\n  // comentario\n  \"a.example\", 'b.example', /* c */ \"##[id^='x']\",\n  \"say \\\"hi\\\"\"\n];"
        assertEquals(listOf("a.example", "b.example", "##[id^='x']", "say \"hi\""), BundledData.array(js, "X"))
    }

    @Test fun decisionLruCacheEvictsOldestAndClearsCleanly() {
        val cache = PhantomShield.DecisionLruCache(maxEntries = 3)
        cache.put("a", ShieldDecision.Allow)
        cache.put("b", ShieldDecision.Block)
        cache.put("c", ShieldDecision.Allow)
        // Touch "a" so "b" becomes least-recently-used
        assertEquals(ShieldDecision.Allow, cache.get("a"))
        cache.put("d", ShieldDecision.Block)
        assertEquals(3, cache.size())
        assertEquals(null, cache.get("b"))
        assertEquals(ShieldDecision.Allow, cache.get("a"))
        assertEquals(ShieldDecision.Block, cache.get("d"))
        cache.clear()
        assertEquals(0, cache.size())
    }
}
