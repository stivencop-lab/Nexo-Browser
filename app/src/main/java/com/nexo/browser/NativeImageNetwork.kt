package com.nexo.browser

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Automatic anonymous image requests, never the user's Gecko navigation or API clients. */
object NativeImagePolicy {
    private val ipv4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
    private val nat64Prefix = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

    fun url(raw: String): HttpUrl? = raw.takeIf { it.length <= 8192 }?.toHttpUrlOrNull()?.takeIf {
        val host = it.host.trimEnd('.')
        val publicLiteral = when {
            ipv4.matches(host) -> {
                val parts = host.split('.').map(String::toInt)
                parts.all { octet -> octet in 0..255 } &&
                    isPublic(InetAddress.getByAddress(ByteArray(4) { index -> parts[index].toByte() }))
            }
            host.contains(':') -> runCatching { isPublic(InetAddress.getByName(host)) }.getOrDefault(false)
            else -> true
        }
        publicLiteral && it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
            host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")
    }

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        // The well-known NAT64 prefix is common on mobile IPv6-only networks.
        // Validate its embedded IPv4 destination instead of rejecting public images there.
        if (bytes.size == 16 && nat64Prefix.indices.all { bytes[it] == nat64Prefix[it] }) {
            return isPublic(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 255; val b = bytes[1].toInt() and 255; val c = bytes[2].toInt() and 255
            return a != 0 && a != 10 && a != 127 && a < 224 &&
                !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
                !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
                !(a == 192 && b == 0 && (c == 0 || c == 2)) &&
                !(a == 198 && b in 18..19) && !(a == 198 && b == 51 && c == 100) &&
                !(a == 203 && b == 0 && c == 113)
        }
        // Global unicast only, excluding documentation and transition ranges.
        return bytes.size == 16 && (bytes[0].toInt() and 255) in 0x20..0x3f &&
            !(bytes[0].toInt() and 255 == 0x20 && bytes[1].toInt() and 255 == 0x01 && bytes[2].toInt() and 255 == 0x0d && bytes[3].toInt() and 255 == 0xb8) &&
            !(bytes[0].toInt() and 255 == 0x20 && bytes[1].toInt() and 255 == 0x02)
    }

    /** Check every answer, and return those exact addresses to OkHttp's connector. */
    fun publicDns(delegate: Dns): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = delegate.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublic(it) }) {
                throw UnknownHostException("Automatic image request requires a public address")
            }
            return addresses
        }
    }
}

object NativeImageNetwork {
    data class Resource(val url: String, val bytes: ByteArray)

    // Dedicated anonymous pool: no cookies, credentials, disk response cache or automatic redirects.
    // Authenticated caption clients keep their own privacy/timeout requirements.
    internal fun client(dns: Dns = Dns.SYSTEM): OkHttpClient = OkHttpClient.Builder()
        .dns(NativeImagePolicy.publicDns(dns))
        .addInterceptor { chain ->
            val url = NativeImagePolicy.url(chain.request().url.toString())
                ?: throw IOException("Automatic image request requires anonymous HTTPS")
            // Also validate literal IPs (which OkHttp need not send through Dns) and proxy CONNECT targets.
            NativeImagePolicy.publicDns(dns).lookup(url.host)
            chain.proceed(chain.request())
        }
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

    private val shared by lazy { client() }

    internal fun fetch(start: String, limit: Int, accept: String, sameSiteOnly: Boolean = false, truncate: Boolean = false, transport: OkHttpClient = shared): Resource? {
        val first = NativeImagePolicy.url(start) ?: return null
        var current = first
        repeat(4) {
            val request = Request.Builder().url(current).header("Accept", accept)
                .header("User-Agent", "NexoBrowser").build()
            transport.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    if (it == 3) return null
                    val next = current.resolve(response.header("Location") ?: return null) ?: return null
                    current = NativeImagePolicy.url(next.toString()) ?: return null
                    if (sameSiteOnly && !SiteIconDiscovery.sameSite(first.host, current.host)) return null
                    return@repeat
                }
                if (response.code != 200) return null
                val body = response.body ?: return null
                val type = body.contentType()?.toString().orEmpty()
                if (accept.startsWith("image") && (type.contains("svg", true) || type.startsWith("text/", true))) return null
                if (!truncate && body.contentLength() > limit) return null
                val bytes = body.byteStream().use { input ->
                    val out = ByteArrayOutputStream(minOf(limit, 8192))
                    val buffer = ByteArray(8192)
                    while (out.size() <= limit) {
                        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                if (bytes.size > limit && !truncate) return null
                return Resource(current.toString(), if (bytes.size > limit) bytes.copyOf(limit) else bytes)
            }
        }
        return null
    }
}
