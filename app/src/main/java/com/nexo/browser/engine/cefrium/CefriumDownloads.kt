package com.nexo.browser.engine.cefrium

import android.os.Looper
import com.cefrium.CefriumProfile
import com.nexo.browser.UrlSafety
import com.nexo.browser.engine.DownloadFetcher
import com.nexo.browser.engine.DownloadResponse
import com.nexo.browser.engine.EngineDownload
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Descargas de Nexo: OkHttp con las cookies del perfil de la pestaña (normal,
 * privado o Burner). Así la descarga pasa por el gestor de Nexo (pausas,
 * tramos en paralelo, carpeta elegida) en lugar del de Chromium.
 */
internal object CefriumDownloads {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile var userAgent: String = UserAgents.mobile()

    val fetcher: DownloadFetcher = object : DownloadFetcher {
        override fun fetch(url: String, headers: Map<String, String>, referrer: String?, isPrivate: Boolean): DownloadResponse =
            request(url, headers, referrer, if (isPrivate) CefriumProfile.incognito() else CefriumProfile.getDefault(), null)
    }

    /** Bloquea: nunca en el hilo principal (las cookies de Cefrium responden en él). */
    fun request(url: String, headers: Map<String, String>, referrer: String?, profile: CefriumProfile, agent: String?): DownloadResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Descarga en el hilo principal" }
        if (!UrlSafety.isWebUrl(url)) throw IOException("Este recurso no se puede descargar directamente")
        val builder = Request.Builder().url(url).header("User-Agent", agent ?: userAgent)
        headers.forEach { (name, value) -> builder.header(name, value) }
        cookieHeader(profile, url)?.let { builder.header("Cookie", it) }
        referrer?.takeIf { UrlSafety.isWebUrl(it) }?.let { builder.header("Referer", it) }
        val response = client.newCall(builder.build()).execute()
        val merged = response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") }
        return DownloadResponse(response.request.url.toString(), response.code, merged, response.body?.byteStream())
    }

    private fun cookieHeader(profile: CefriumProfile, url: String): String? {
        val future = CompletableFuture<String>()
        runCatching { profile.cookieManager.getCookie(url) { future.complete(it) } }
            .onFailure { return null }
        return runCatching { future.get(5, TimeUnit.SECONDS) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/** Descarga que Chromium iba a guardar: se cancela allí y la hace el gestor de Nexo. */
internal class CefriumDownload(
    override val uri: String,
    private val suggestedName: String?,
    private val mimeType: String?,
    private val referrer: String?,
    private val profile: CefriumProfile,
    private val userAgent: String?
) : EngineDownload {
    override fun discard() = Unit

    override fun open(): DownloadResponse {
        if (uri.startsWith("blob:") || uri.startsWith("data:")) {
            throw IOException("Las descargas generadas por la página (blob/data) todavía no están disponibles en esta versión")
        }
        val response = CefriumDownloads.request(uri, emptyMap(), referrer, profile, userAgent)
        // El nombre y el tipo que Chromium ya conoce sirven si el servidor no los repite.
        val headers = response.headers.toMutableMap()
        if (headers.keys.none { it.equals("Content-Disposition", true) } && !suggestedName.isNullOrBlank()) {
            headers["Content-Disposition"] = "attachment; filename=\"" + suggestedName.replace("\"", "") + "\""
        }
        if (headers.keys.none { it.equals("Content-Type", true) } && !mimeType.isNullOrBlank()) {
            headers["Content-Type"] = mimeType
        }
        return DownloadResponse(response.uri, response.statusCode, headers, response.body)
    }
}
