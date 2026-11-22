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
        ): DownloadResponse =
            request(url, headers, referrer, CefriumProfiles.forSession(isPrivate, burnerId), null)
    }

    /** Bloquea: nunca en el hilo principal (las cookies de Cefrium responden en él). */
    fun request(url: String, headers: Map<String, String>, referrer: String?, profile: CefriumProfile, agent: String?): DownloadResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Descarga en el hilo principal" }
        var currentUrl = url
        var currentReferrer = referrer?.takeIf { UrlSafety.isWebUrl(it) }
        var redirects = 0

        while (true) {
            if (!UrlSafety.isWebUrl(currentUrl)) throw IOException("Este recurso no se puede descargar directamente")
            val builder = Request.Builder().url(currentUrl).header("User-Agent", agent ?: userAgent)
            headers.forEach { (name, value) ->
                if (!name.equals("Cookie", ignoreCase = true) && !name.equals("Authorization", ignoreCase = true)) {
                    builder.header(name, value)
                }
            }
            cookieHeader(profile, currentUrl)?.let { builder.header("Cookie", it) }
            currentReferrer?.let { builder.header("Referer", it) }

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
 * Archivo que generó la página (`blob:`, `data:`): Chromium lo dejó en la
 * caché de la app y el gestor de Nexo lo copia a Descargas. El temporal se
 * borra al terminar de leerlo o al descartarlo.
 */
internal class PageGeneratedDownload(
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

        /** Archivo temporal para la descarga [id]; los que quedaron de otra sesión se borran. */
        fun reserve(context: android.content.Context, id: Int): java.io.File {
            val folder = java.io.File(context.cacheDir, FOLDER).apply { mkdirs() }
            val day = 24L * 3600 * 1000
            folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > day }?.forEach { it.delete() }
            return java.io.File(folder, "${System.currentTimeMillis()}-$id.part")
        }
    }
}

/** Descarga que Chromium iba a guardar: se cancela allí y la hace el gestor de Nexo. */
internal class CefriumDownload(
    override val uri: String,
    override val suggestedName: String?,
    override val mimeType: String?,
    private val referrer: String?,
    private val profile: CefriumProfile,
    private val userAgent: String?
) : EngineDownload {
    override fun discard() = Unit

    override fun open(): DownloadResponse {
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
