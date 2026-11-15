package com.nexo.browser

import com.nexo.browser.engine.FakeEngineSession
import com.nexo.browser.engine.chromium.UserAgents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromiumRoutingTest {
    private val messages = mutableListOf<String>()

    init {
        TabRouting.notify = { messages += it }
    }

    @Test fun proxiedTabsNeverFallBackToADirectConnection() {
        val session = FakeEngineSession()
        val tab = BrowserTab(url = "https://example.com/", networkProfile = TabNetworkProfile("socks", "127.0.0.1", 9050))
        TabRouting.reserve(session)
        var loaded = false
        TabRouting.prepare(tab, session) { loaded = true }
        assertFalse(loaded)
        assertTrue(TabRouting.blocked(session))
        assertTrue(TabRouting.preparing(session))
        assertEquals("Ruta bloqueada", tab.networkState)
        assertTrue(messages.single().contains("no abrió la página por conexión directa"))
        TabRouting.forget(session)
        assertFalse(TabRouting.blocked(session))
    }

    @Test fun burnerAndDirectTabsOpenWithoutSyntheticHistory() {
        val burner = FakeEngineSession()
        val tab = BrowserTab(url = "https://example.com/", isPrivate = true, burnerId = "burner-1")
        TabRouting.reserve(burner)
        var loaded = false
        TabRouting.prepare(tab, burner) { loaded = true }
        assertTrue(loaded)
        assertFalse(TabRouting.preparing(burner))
        assertFalse(TabRouting.synthetic(burner))
        assertFalse(TabRouting.bootstrap(burner, "https://example.com/"))
        assertEquals("Burner aislado", tab.networkState)

        var plain = false
        TabRouting.prepare(BrowserTab(url = "https://example.org/"), FakeEngineSession()) { plain = true }
        assertTrue(plain)
    }

    @Test fun userAgentsHideWebViewMarkersAndOfferDesktop() {
        val base = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/AP4A; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/142.0.7444.90 Mobile Safari/537.36"
        val mobile = UserAgents.mobile(base)
        assertFalse(mobile.contains("; wv"))
        assertFalse(mobile.contains("Version/4.0"))
        assertTrue(mobile.contains("Chrome/142.0.7444.90 Mobile Safari/537.36"))
        val desktop = UserAgents.desktop(base)
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.7444.90 Safari/537.36",
            desktop
        )
        assertFalse(desktop.contains("Mobile"))
    }
}
