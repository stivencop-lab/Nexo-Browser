package com.nexo.browser.engine.cefrium

import android.os.Looper
import com.cefrium.CefriumProfile
import com.nexo.browser.UrlSafety
import com.nexo.browser.engine.DownloadFetcher
import com.nexo.browser.engine.DownloadResponse
import com.nexo.browser.engine.EngineDownload
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Descargas directas que el usuario pidió desde el menú de un enlace.
 * Las descargas entregadas por Chromium conservan la petición en el motor.
 */
internal object CefriumDownloads {
    private const val MAX_REDIRECTS = 5

    private val client = OkHttpClient.Builder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .authenticator(okhttp3.Authenticator.NONE)
        .proxyAuthenticator(okhttp3.Authenticator.NONE)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile var userAgent: String = UserAgents.mobile()

    val fetcher: DownloadFetcher = object : DownloadFetcher {
        override fun fetch(
            url: String,
            headers: Map<String, String>,
            referrer: String?,
            isPrivate: Boolean,
            burnerId: String?
        ): DownloadResponse {
            if (burnerId != null) throw IOException("La descarga Burner debe usar el navegador de esa identidad.")
            return request(url, headers, referrer, CefriumProfiles.forSession(isPrivate, null))
        }
    }

    /** Bloquea: nunca en el hilo principal (las cookies de Cefrium responden en él). */
    private fun request(url: String, headers: Map<String, String>, referrer: String?, profile: CefriumProfile): DownloadResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Descarga en el hilo principal" }
        var currentUrl = url
        var currentReferrer = referrer?.takeIf { UrlSafety.isWebUrl(it) }
        var redirects = 0

        while (true) {
            if (!UrlSafety.isWebUrl(currentUrl)) throw IOException("Este recurso no se puede descargar directamente")
            val destination = currentUrl.toHttpUrlOrNull() ?: throw IOException("URL de descarga inválida")
            val builder = Request.Builder().url(destination).header("User-Agent", userAgent)
            headers.forEach { (name, value) ->
                if (!name.equals("Cookie", ignoreCase = true) && !name.equals("Authorization", ignoreCase = true)) {
                    builder.header(name, value)
                }
            }
            cookieHeader(profile, currentUrl)?.let { builder.header("Cookie", it) }
            currentReferrer?.toHttpUrlOrNull()?.let { origin ->
                if (origin.scheme == destination.scheme && origin.host == destination.host && origin.port == destination.port) {
                    builder.header("Referer", origin.newBuilder().username("").password("").fragment(null).build().toString())
                }
            }

            val response = client.newCall(builder.build()).execute()
            val code = response.code
            val location = response.header("Location")
            if (code in setOf(301, 302, 303, 307, 308) && !location.isNullOrBlank()) {
                val resolved = response.request.url.resolve(location)
                val prevUrl = response.request.url
                response.close()
                if (resolved == null || !UrlSafety.isWebUrl(resolved.toString())) {
                    throw IOException("Redirección de descarga no permitida")
                }
                if (prevUrl.isHttps && !resolved.isHttps) {
                    throw IOException("Redirección insegura de HTTPS a HTTP bloqueada")
                }
                if (++redirects > MAX_REDIRECTS) {
                    throw IOException("Demasiadas redirecciones al descargar")
                }
                val sameOrigin = prevUrl.scheme == resolved.scheme &&
                    prevUrl.host.equals(resolved.host, ignoreCase = true) &&
                    prevUrl.port == resolved.port
                currentReferrer = if (sameOrigin) prevUrl.toString() else null
                currentUrl = resolved.toString()
                continue
            }
            val merged = response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") }
            return DownloadResponse(response.request.url.toString(), response.code, merged, response.body?.byteStream())
        }
    }

    private fun cookieHeader(profile: CefriumProfile, url: String): String? {
        val future = CompletableFuture<String>()
        runCatching { profile.cookieManager.getCookie(url) { future.complete(it) } }
            .onFailure { return null }
        return runCatching { future.get(5, TimeUnit.SECONDS) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Archivo guardado por Chromium con la petición original. El gestor lo copia
 * desde la caché a Descargas. El temporal se
 * borra al terminar de leerlo o al descartarlo.
 */
internal class CefriumFileDownload(
    override val uri: String,
    private val file: java.io.File,
    override val suggestedName: String?,
    override val mimeType: String?
) : EngineDownload {
    override fun discard() {
        file.delete()
    }

    override fun open(): DownloadResponse {
        if (!file.isFile) throw IOException("La página no terminó de generar el archivo")
        val headers = mutableMapOf("Content-Length" to file.length().toString())
        val name = suggestedName?.replace("\"", "")?.takeIf { it.isNotBlank() }
        if (name != null) headers["Content-Disposition"] = "attachment; filename=\"$name\""
        if (!mimeType.isNullOrBlank()) headers["Content-Type"] = mimeType
        val body = object : java.io.FileInputStream(file) {
            override fun close() {
                super.close()
                file.delete()
            }
        }
        return DownloadResponse(uri, 200, headers, body)
    }

    companion object {
        private const val FOLDER = "page-downloads"

        private val sessionPrefix = "download-${java.util.UUID.randomUUID()}-"

        fun clearOrphaned(context: android.content.Context) {
            java.io.File(context.cacheDir, FOLDER).listFiles()?.forEach { file ->
                if (file.name.startsWith("download-") && !file.name.startsWith(sessionPrefix) &&
                    (file.name.endsWith(".part") || file.name.endsWith(".part.crdownload"))) file.delete()
            }
        }

        fun reserve(context: android.content.Context, id: Int): java.io.File {
            val folder = java.io.File(context.cacheDir, FOLDER).apply { mkdirs() }
            return java.io.File(folder, "$sessionPrefix$id-${java.util.UUID.randomUUID()}.part")
        }
    }
}
