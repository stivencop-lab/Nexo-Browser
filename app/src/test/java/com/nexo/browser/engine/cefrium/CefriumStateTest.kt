package com.nexo.browser.engine.cefrium

import com.nexo.browser.TabNetworkProfile
import com.nexo.browser.shield.ShieldPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CefriumStateTest {
    @Test fun navigationHistoryRoundTrips() {
        val nav = byteArrayOf(1, 2, 3, 4, 5)
        val parsed = CefriumState.parse(CefriumState.of("https://example.com/a", nav).serialize())!!
        assertEquals("https://example.com/a", parsed.currentUri)
        assertArrayEquals(nav, parsed.navigation)
    }

    @Test fun oversizedHistoryKeepsOnlyTheUrl() {
        val state = CefriumState.of("https://example.com/", ByteArray(CefriumState.MAX_STATE_BYTES + 1))
        assertNull(state.navigation)
        assertEquals("https://example.com/", CefriumState.parse(state.serialize())!!.currentUri)
    }

    @Test fun olderEnginesOnlyGiveBackTheirUrl() {
        // Estado de la variante WebView (v1): su Bundle no sirve en Chromium embebido.
        val webView = """{"v":1,"url":"https://example.org/","state":"AAAA"}"""
        val parsed = CefriumState.parse(webView)!!
        assertEquals("https://example.org/", parsed.currentUri)
        assertNull(parsed.navigation)
        // Estado de Gecko (sin "url"): no se restaura, la pestaña recarga su dirección.
        assertNull(CefriumState.parse("""{"history":{"entries":[]}}"""))
        assertNull(CefriumState.parse("no es json"))
    }

    @Test fun proxyServersUseChromiumSchemes() {
        assertEquals("socks5://127.0.0.1:9050", CefriumProfiles.proxyServer(TabNetworkProfile("socks", "127.0.0.1", 9050)))
        assertEquals("http://proxy.example:8080", CefriumProfiles.proxyServer(TabNetworkProfile("http", "proxy.example", 8080)))
        assertEquals("socks5://[::1]:1080", CefriumProfiles.proxyServer(TabNetworkProfile("socks", "::1", 1080)))
    }

    @Test fun chromiumResourceTypesMapToShieldTypes() {
        assertEquals("main_frame", ShieldPolicy.typeFromEngine("MAIN_FRAME", "https://a.example/"))
        assertEquals("sub_frame", ShieldPolicy.typeFromEngine("SUB_FRAME", "https://a.example/"))
        assertEquals("script", ShieldPolicy.typeFromEngine("SERVICE_WORKER", "https://a.example/sw"))
        assertEquals("xmlhttprequest", ShieldPolicy.typeFromEngine("XHR", "https://a.example/api"))
        assertEquals("image", ShieldPolicy.typeFromEngine("FAVICON", "https://a.example/favicon.ico"))
        assertEquals("ping", ShieldPolicy.typeFromEngine("PING", "https://a.example/beacon"))
        // OTHER: se deduce de la URL, como antes.
        assertEquals("script", ShieldPolicy.typeFromEngine("OTHER", "https://a.example/x.js"))
    }

    @Test fun desktopUserAgentKeepsTheChromiumVersion() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/158.0.8127.18 Safari/537.36",
            UserAgents.desktop("Chrome/158.0.8127.18")
        )
    }

    @Test fun proxyErrorsReturnActionableSpanishMessages() {
        org.junit.Assert.assertTrue(CefriumProfiles.describeProxyError(-130).contains("-130"))
        org.junit.Assert.assertTrue(CefriumProfiles.describeProxyError(-120).contains("SOCKS"))
        org.junit.Assert.assertTrue(CefriumProfiles.describeProxyError(-111).contains("túnel"))
        org.junit.Assert.assertTrue(CefriumProfiles.describeProxyError(-115).contains("autenticación"))
        org.junit.Assert.assertTrue(CefriumProfiles.describeProxyError(-999).contains("-999"))
    }
}
