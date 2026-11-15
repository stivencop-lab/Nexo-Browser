package com.nexo.browser.engine.chromium

import android.webkit.CookieManager
import com.nexo.browser.UrlSafety
import com.nexo.browser.engine.DownloadFetcher
import com.nexo.browser.engine.DownloadResponse
import com.nexo.browser.engine.EngineDownload
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Descargas fuera de WebView: OkHttp con las cookies del perfil de la
 * pestaña (normal o privado). WebView no entrega el cuerpo de una descarga,
 * solo su dirección.
 */
internal object ChromiumDownloads {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile var userAgent: String = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"

    val fetcher: DownloadFetcher = object : DownloadFetcher {
        override fun fetch(url: String, headers: Map<String, String>, referrer: String?, isPrivate: Boolean): DownloadResponse =
            request(url, headers, referrer, ChromiumProfiles.cookieManagerForDownloads(isPrivate), null)
    }

    /** [cookies] `null`: sin cookies (nunca las del perfil normal en una pestaña privada). */
    fun request(url: String, headers: Map<String, String>, referrer: String?, cookies: CookieManager?, agent: String?): DownloadResponse {
        if (!UrlSafety.isWebUrl(url)) throw IOException("Este recurso no se puede descargar directamente")
        val builder = Request.Builder().url(url).header("User-Agent", agent ?: userAgent)
        headers.forEach { (name, value) -> builder.header(name, value) }
        cookies?.getCookie(url)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        referrer?.takeIf { UrlSafety.isWebUrl(it) }?.let { builder.header("Referer", it) }
        val response = client.newCall(builder.build()).execute()
        val merged = response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") }
        return DownloadResponse(response.request.url.toString(), response.code, merged, response.body?.byteStream())
    }
}

/** Descarga que WebView no muestra: se pide de nuevo con OkHttp. */
internal class ChromiumDownload(
    override val uri: String,
    private val userAgent: String?,
    private val contentDisposition: String?,
    private val mimeType: String?,
    private val referrer: String?,
    /** Perfil de la pestaña que inició la descarga (`null` = normal). */
    private val profileName: String?
) : EngineDownload {
    override fun discard() = Unit

    override fun open(): DownloadResponse {
        if (uri.startsWith("blob:") || uri.startsWith("data:")) {
            throw IOException("Las descargas generadas por la página (blob/data) todavía no están disponibles en esta versión")
        }
        val response = ChromiumDownloads.request(uri, emptyMap(), referrer, ChromiumProfiles.cookieManager(profileName), userAgent)
        // El nombre y el tipo que WebView ya conoce sirven si el servidor no los repite.
        val headers = response.headers.toMutableMap()
        if (headers.keys.none { it.equals("Content-Disposition", true) } && !contentDisposition.isNullOrBlank()) {
            headers["Content-Disposition"] = contentDisposition
        }
        if (headers.keys.none { it.equals("Content-Type", true) } && !mimeType.isNullOrBlank()) {
            headers["Content-Type"] = mimeType
        }
        return DownloadResponse(response.uri, response.statusCode, headers, response.body)
    }
}
