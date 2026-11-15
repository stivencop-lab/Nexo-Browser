package com.nexo.browser

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

class NativeImageNetworkTest {
    private fun dns(vararg literals: String) = object : Dns {
        override fun lookup(hostname: String) = literals.map { InetAddress.getByName(it) }
    }

    @Test fun automaticImagePolicyRejectsLocalCredentialsAndInsecureTargets() {
        for (raw in listOf("http://example.com/icon", "https://localhost/icon", "https://localhost./icon",
            "https://router.local/icon", "https://user:password@example.com/icon", "https://127.0.0.1/icon",
            "https://[::1]/icon", "https://[::ffff:192.168.1.1]/icon", "https://999.1.2.3/icon", "https://example.com/" + "x".repeat(8192))) {
            assertNull(raw, NativeImagePolicy.url(raw))
        }
        assertNotNull(NativeImagePolicy.url("https://cdn.example.com/icon.png"))
        // This policy must not prevent intentional intranet browsing in Gecko.
        assertTrue(UrlSafety.isWebUrl("http://192.168.1.1/"))
    }

    @Test fun allDnsAnswersMustBePublicIncludingIpv6AndMappedAddresses() {
        for (address in listOf("0.0.0.0", "127.2.3.4", "10.1.2.3", "172.31.2.3", "192.168.2.3",
            "169.254.169.254", "100.64.0.1", "198.19.1.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fe80::1", "ff02::1", "::ffff:10.0.0.1", "2001:db8::1",
            "64:ff9b::192.168.1.1", "64:ff9b::127.0.0.1")) {
            assertFalse(address, NativeImagePolicy.isPublic(InetAddress.getByName(address)))
        }
        for (address in listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "64:ff9b::8.8.8.8")) {
            assertTrue(address, NativeImagePolicy.isPublic(InetAddress.getByName(address)))
        }
        val guarded = NativeImagePolicy.publicDns(dns("1.1.1.1", "192.168.1.1"))
        assertThrows(UnknownHostException::class.java) { guarded.lookup("mixed.example") }
        assertThrows(UnknownHostException::class.java) { NativeImagePolicy.publicDns(dns()).lookup("empty.example") }
    }

    @Test fun connectorRejectsDnsRebindingBeforeConnecting() {
        var lookups = 0
        val changing = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName(if (lookups++ == 0) "1.1.1.1" else "127.0.0.1"))
        }
        val client = NativeImageNetwork.client(changing).newBuilder().proxy(Proxy.NO_PROXY).build()
        assertThrows(UnknownHostException::class.java) {
            client.newCall(Request.Builder().url("https://images.example/icon.png").build()).execute().close()
        }
        assertEquals(2, lookups)
    }

    private fun transport(respond: (Request) -> Response): OkHttpClient =
        NativeImageNetwork.client(dns("1.1.1.1")).newBuilder().addInterceptor { respond(it.request()) }.build()

    private fun response(request: Request, code: Int, body: String = "", location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .body(body.toResponseBody()).apply { if (location != null) header("Location", location) }.build()

    @Test fun redirectsCannotReachHttpPrivateAddressesOrAnotherRegistrableSite() {
        for (location in listOf("http://cdn.example.com/icon", "https://127.0.0.1/icon", "https://user:pass@cdn.example.com/icon")) {
            var calls = 0
            val client = transport { calls++; response(it, 302, location = location) }
            assertNull(NativeImageNetwork.fetch("https://example.com/icon", 100, "image/*", transport = client))
            assertEquals(1, calls)
        }
        var calls = 0
        val client = transport { calls++; response(it, 302, location = "https://attacker.co.uk/") }
        assertNull(NativeImageNetwork.fetch("https://victim.co.uk/", 100, "text/html", sameSiteOnly = true, transport = client))
        assertEquals(1, calls)
    }

    @Test fun responseAndRedirectLimitsAreEnforcedWithoutCredentials() {
        var calls = 0
        val loop = transport { calls++; response(it, 302, location = "/next") }
        assertNull(NativeImageNetwork.fetch("https://example.com/icon", 10, "image/*", transport = loop))
        assertEquals(4, calls)
        val large = transport {
            assertNull(it.header("Authorization")); assertNull(it.header("Cookie"))
            response(it, 200, "abcdefghijklmnop")
        }
        assertNull(NativeImageNetwork.fetch("https://example.com/icon", 10, "image/*", transport = large))
        assertArrayEquals("abcdefghij".toByteArray(), NativeImageNetwork.fetch("https://example.com/", 10, "text/html", truncate = true, transport = large)!!.bytes)
        assertThrows(IOException::class.java) {
            NativeImageNetwork.client(dns("1.1.1.1")).newCall(Request.Builder().url("https://127.0.0.1/").build()).execute().close()
        }
    }
}
