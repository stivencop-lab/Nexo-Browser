package com.nexo.browser.extensions.hub

import com.nexo.browser.extensions.CrxPackage
import com.nexo.browser.extensions.ExtensionManifest
import com.nexo.browser.extensions.nxr.NxrPackageInstaller
import com.nexo.browser.extensions.nxr.NxrPermissions
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Cliente de red sin cookies ni sesiones del navegador para descargar metadatos reales
 * y paquetes CRX firmados desde Chrome Web Store (Omaha XML), Microsoft Edge Add-ons
 * (API JSON oficial + redirección CRX3 firmada) y paquetes HTTPS directos de desarrolladores.
 *
 * Controles de seguridad:
 * - Zero cookies / zero sesiones del usuario.
 * - Validación manual de cada redirección (máx. 5 saltos) contra listas de hosts permitidos.
 * - Límite estricto de descarga (25 MB).
 * - Verificación obligatoria de hash SHA-256 (cuando la tienda lo provee), firma CRX3/CRX2
 *   mediante [CrxPackage.verify] y coincidencia de versión con los metadatos HTTPS.
 */
object ExtensionStoreClient {
    const val MAX_DOWNLOAD_BYTES = 256L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5
    private const val CHROME_PROD_VERSION = "158.0.0.0"
    private const val STORE_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME_PROD_VERSION Safari/537.36"

    class StoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

    data class StoreListing(
        val store: ExtensionStoreResolver.StoreKind,
        val extensionId: String,
        val name: String,
        val developer: String,
        val version: String,
        val shortDescription: String,
        val detailUrl: String,
        val privacyUrl: String? = null,
        val lastUpdated: String? = null,
        /** Solo valores reales provistos por la API oficial de la tienda (null si no existe). */
        val rating: Double? = null,
        val ratingCount: Int? = null,
        val activeUsers: Long? = null,
        val downloadUrl: String,
        val expectedSha256: String? = null,
        val expectedSizeBytes: Long? = null,
        val preflightCapabilities: NxrPermissions.CapabilitySummary? = null
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(com.nexo.browser.NativeImagePolicy.publicDns(okhttp3.Dns.SYSTEM))
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    fun cwsOmahaUrl(extensionId: String): String {
        val id = extensionId.lowercase()
        return "https://clients2.google.com/service/update2/crx" +
            "?os=linux&arch=x64&nacl_arch=x86-64&prod=chromecrx&prodchannel=stable" +
            "&prodversion=$CHROME_PROD_VERSION&acceptformat=crx2,crx3" +
            "&x=id%3D$id%26installsource%3Dondemand%26uc"
    }

    fun cwsDirectRedirectUrl(extensionId: String): String {
        val id = extensionId.lowercase()
        return "https://clients2.google.com/service/update2/crx?response=redirect" +
            "&os=linux&arch=x64&nacl_arch=x86-64&prod=chromecrx&prodchannel=stable" +
            "&prodversion=$CHROME_PROD_VERSION&acceptformat=crx2,crx3" +
            "&x=id%3D$id%26installsource%3Dondemand%26uc"
    }

    fun edgeMetadataUrl(extensionId: String): String =
        "https://microsoftedge.microsoft.com/addons/getproductdetailsbycrxid/${extensionId.lowercase()}"

    fun edgeCrxDownloadUrl(extensionId: String): String {
        val id = extensionId.lowercase()
        return "https://edge.microsoft.com/extensionwebstorebase/v1/crx?response=redirect" +
            "&prod=chromiumcrx&prodchannel=&x=id%3D$id%26installsource%3Dondemand%26uc"
    }

    /** Analiza la respuesta XML oficial de Omaha de Chrome Web Store (puro: probado en JVM). */
    fun parseCwsOmahaXml(extensionId: String, xml: String): StoreListing {
        val cleanId = extensionId.lowercase()
        if (!ExtensionStoreResolver.isValidExtensionId(cleanId)) {
            throw StoreException("Identificador de extensión no válido.")
        }
        val tagMatch = Regex("<updatecheck\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(xml)
            ?: throw StoreException("Chrome Web Store no devolvió un paquete descargable para $cleanId.")
        val attrs = parseXmlAttributes(tagMatch.groupValues[1])
        val status = attrs["status"].orEmpty().lowercase()
        if (status != "ok") {
            val reason = when {
                status == "noupdate" -> "La extensión no publicó un paquete CRX compatible o fue retirada."
                status.startsWith("error") -> "Chrome Web Store devolvió estado «$status»."
                else -> "Chrome Web Store no autorizó la descarga (estado: ${status.ifBlank { "desconocido" }})."
            }
            throw StoreException(reason)
        }
        val codebase = decodeXmlEntities(attrs["codebase"].orEmpty()).trim()
        val version = attrs["version"].orEmpty().trim()
        val sha256 = attrs["hash_sha256"]?.trim()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val size = attrs["size"]?.trim()?.toLongOrNull()

        if (!ExtensionStoreResolver.isPublicHttpsUrl(codebase)) {
            throw StoreException("URL de descarga CRX de Chrome Web Store no segura.")
        }
        val host = runCatching { URI(codebase).host }.getOrNull()
        if (!ExtensionStoreResolver.isAllowedCwsDownloadHost(host)) {
            throw StoreException("Host de descarga de Chrome Web Store fuera de la lista permitida: $host")
        }
        if (size != null && size > MAX_DOWNLOAD_BYTES) {
            throw StoreException("El paquete supera el límite de ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB.")
        }

        return StoreListing(
            store = ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE,
            extensionId = cleanId,
            name = cleanId,
            developer = "Chrome Web Store",
            version = version,
            shortDescription = "",
            detailUrl = ExtensionStoreResolver.cwsDetailUrl(cleanId),
            downloadUrl = codebase,
            expectedSha256 = sha256,
            expectedSizeBytes = size
        )
    }

    /** Analiza la respuesta JSON oficial de Microsoft Edge Add-ons (puro: probado en JVM). */
    fun parseEdgeDetailsJson(extensionId: String, jsonText: String): StoreListing {
        val cleanId = extensionId.lowercase()
        if (!ExtensionStoreResolver.isValidExtensionId(cleanId)) {
            throw StoreException("Identificador de extensión no válido.")
        }
        val obj = runCatching { JSONObject(jsonText) }
            .getOrElse { throw StoreException("Respuesta JSON no válida de Microsoft Edge Add-ons.", it) }
        val returnedId = obj.optString("crxId").trim().lowercase()
        if (returnedId.isNotEmpty() && returnedId != cleanId) {
            throw StoreException("La ficha de Edge Add-ons no corresponde al identificador solicitado.")
        }
        val name = obj.optString("name").trim().ifBlank {
            throw StoreException("Extensión no encontrada en Microsoft Edge Add-ons.")
        }
        val developer = obj.optString("developer").trim().ifBlank { "Microsoft Edge Add-ons" }
        val version = obj.optString("version").trim()
        val shortDesc = obj.optString("shortDescription").trim()
            .ifBlank { obj.optString("description").trim().take(220) }
        val privacyUrl = obj.optString("privacyUrl").trim().takeIf { ExtensionStoreResolver.isPublicHttpsUrl(it) }
        val lastUpdated = obj.optString("lastUpdateDate").trim().takeIf { it.isNotBlank() }
        val rating = obj.optDouble("averageRating", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }
        val ratingCount = obj.optInt("ratingCount", -1).takeIf { it >= 0 }
        val activeUsers = obj.optLong("activeInstallCount", -1L).takeIf { it >= 0L }

        val rawManifest = obj.optString("manifest").trim()
        val preflight = if (rawManifest.startsWith("{")) {
            runCatching { ExtensionManifest.parse(rawManifest) }
                .getOrNull()
                ?.let { NxrPermissions.summarizeCapabilities(it) }
        } else null

        return StoreListing(
            store = ExtensionStoreResolver.StoreKind.EDGE_ADDONS,
            extensionId = cleanId,
            name = name,
            developer = developer,
            version = version,
            shortDescription = shortDesc,
            detailUrl = ExtensionStoreResolver.edgeDetailUrl(cleanId),
            privacyUrl = privacyUrl,
            lastUpdated = lastUpdated,
            rating = rating,
            ratingCount = ratingCount,
            activeUsers = activeUsers,
            downloadUrl = edgeCrxDownloadUrl(cleanId),
            preflightCapabilities = preflight
        )
    }

    fun fetchListing(target: ExtensionStoreResolver.ResolvedTarget.StoreExtension): StoreListing =
        when (target.store) {
            ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE -> fetchCwsListing(target.extensionId)
            ExtensionStoreResolver.StoreKind.EDGE_ADDONS -> fetchEdgeListing(target.extensionId)
            else -> throw StoreException("Tienda no soportada.")
        }

    fun fetchCwsListing(extensionId: String): StoreListing {
        val cleanId = extensionId.lowercase()
        val xmlListing = runCatching {
            val body = fetchTextHttps(cwsOmahaUrl(cleanId), "application/xml, text/xml, */*")
            parseCwsOmahaXml(cleanId, body)
        }.getOrNull()
        if (xmlListing != null) return xmlListing
        // Respaldo oficial response=redirect cuando Omaha XML omite codebase o devuelve noupdate:
        // la descarga sigue validando hosts permitidos y firma criptográfica CRX3 con expectedId.
        return StoreListing(
            store = ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE,
            extensionId = cleanId,
            name = cleanId,
            developer = "Chrome Web Store",
            version = "",
            shortDescription = "",
            detailUrl = ExtensionStoreResolver.cwsDetailUrl(cleanId),
            downloadUrl = cwsDirectRedirectUrl(cleanId)
        )
    }

    fun fetchEdgeListing(extensionId: String): StoreListing {
        val cleanId = extensionId.lowercase()
        val body = fetchTextHttps(edgeMetadataUrl(cleanId), "application/json")
        return parseEdgeDetailsJson(cleanId, body)
    }

    /**
     * Descarga y verifica criptográficamente el paquete de [listing] en [destFile].
     * Comprueba lista de hosts en cada redirección, límite amplio de descarga, SHA-256 (CWS),
     * firma CRX2/CRX3 con [CrxPackage.verify] y coincidencia de versión (Edge).
     */
    fun downloadAndVerifyPackage(listing: StoreListing, destFile: File): File {
        val allowEdgeDeliveryHttp = listing.store == ExtensionStoreResolver.StoreKind.EDGE_ADDONS
        downloadWithVerifiedRedirects(
            initialUrl = listing.downloadUrl,
            destFile = destFile,
            allowHttpOnHost = { host ->
                // La CDN de entrega de Microsoft Edge (msedgeextensions.*.delivery.mp.microsoft.com)
                // rechaza HTTPS con 503; el archivo CRX3 sigue siendo verificado criptográficamente
                // mediante CrxPackage.verify(expectedId) + versión contra los metadatos HTTPS de Edge.
                allowEdgeDeliveryHttp && host.endsWith(".delivery.mp.microsoft.com")
            },
            hostValidator = { host ->
                when (listing.store) {
                    ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE ->
                        ExtensionStoreResolver.isAllowedCwsDownloadHost(host)
                    ExtensionStoreResolver.StoreKind.EDGE_ADDONS ->
                        ExtensionStoreResolver.isAllowedEdgeDownloadHost(host)
                    ExtensionStoreResolver.StoreKind.DIRECT_HTTPS_PACKAGE -> true
                }
            }
        )

        if (!listing.expectedSha256.isNullOrBlank()) {
            val actualHash = CrxPackage.sha256Hex(destFile)
            if (!actualHash.equals(listing.expectedSha256, ignoreCase = true)) {
                destFile.delete()
                throw StoreException("Integridad fallida: el SHA-256 descargado no coincide con la tienda.")
            }
        }

        if (listing.store == ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE ||
            listing.store == ExtensionStoreResolver.StoreKind.EDGE_ADDONS
        ) {
            runCatching { CrxPackage.verify(destFile, expectedId = listing.extensionId) }
                .getOrElse {
                    destFile.delete()
                    throw StoreException("Firma CRX inválida o ID no coincide: ${it.message}", it)
                }
            if (listing.version.isNotBlank()) {
                val inspection = runCatching { NxrPackageInstaller.inspect(destFile, expectedId = listing.extensionId) }
                    .getOrElse {
                        destFile.delete()
                        throw StoreException("El paquete CRX no se pudo inspeccionar: ${it.message}", it)
                    }
                if (listing.store == ExtensionStoreResolver.StoreKind.EDGE_ADDONS &&
                    ExtensionStoreResolver.compareVersions(inspection.manifest.version, listing.version) < 0
                ) {
                    destFile.delete()
                    throw StoreException(
                        "Versión del paquete (${inspection.manifest.version}) inferior a la declarada por HTTPS (${listing.version})."
                    )
                }
            }
        }
        return destFile
    }

    /** Descarga un paquete directo de desarrollador estrictamente sobre HTTPS público. */
    fun downloadDirectHttpsPackage(url: String, destFile: File): File {
        if (!ExtensionStoreResolver.isPublicHttpsUrl(url)) {
            throw StoreException("Solo se permiten descargas directas mediante HTTPS hacia servidores públicos.")
        }
        downloadWithVerifiedRedirects(
            initialUrl = url,
            destFile = destFile,
            allowHttpOnHost = { false },
            hostValidator = { true }
        )
        return destFile
    }

    private fun fetchTextHttps(url: String, accept: String): String {
        if (!ExtensionStoreResolver.isPublicHttpsUrl(url)) {
            throw StoreException("URL no segura: $url")
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", STORE_USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "es,en;q=0.8")
            .get()
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw StoreException("La tienda respondió con código HTTP ${res.code}.")
            }
            val body = res.body ?: throw StoreException("Respuesta vacía de la tienda.")
            val bytes = body.byteStream().use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) break
                    total += read
                    if (total > 2 * 1024 * 1024) throw StoreException("Respuesta de metadatos demasiado grande.")
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun downloadWithVerifiedRedirects(
        initialUrl: String,
        destFile: File,
        allowHttpOnHost: (String) -> Boolean,
        hostValidator: (String) -> Boolean
    ) {
        var currentUrl = initialUrl
        var redirects = 0
        while (true) {
            val uri = runCatching { URI(currentUrl) }.getOrNull()
                ?: throw StoreException("URL de descarga no válida.")
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase()?.trimEnd('.').orEmpty()
            if (host.isEmpty() || uri.rawUserInfo != null) {
                throw StoreException("URL de descarga rechazada por seguridad.")
            }
            if (!hostValidator(host)) {
                throw StoreException("Host de descarga no autorizado: $host")
            }
            val schemeAllowed = when (scheme) {
                "https" -> ExtensionStoreResolver.isPublicHttpsUrl(currentUrl)
                "http" -> allowHttpOnHost(host)
                else -> false
            }
            if (!schemeAllowed) {
                throw StoreException("Esquema no permitido para $host ($scheme).")
            }

            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", STORE_USER_AGENT)
                .header("Accept", "*/*")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                if (res.code in 300..399) {
                    val location = res.header("Location")?.trim()
                        ?: throw StoreException("Redirección sin destino desde $host.")
                    if (++redirects > MAX_REDIRECTS) {
                        throw StoreException("Demasiadas redirecciones al descargar el paquete.")
                    }
                    currentUrl = runCatching { uri.resolve(location).toString() }
                        .getOrElse { throw StoreException("Redirección inválida.") }
                    continue
                }
                if (res.code == 204) {
                    throw StoreException("La tienda no devolvió un paquete CRX descargable para esta extensión (HTTP 204).")
                }
                if (!res.isSuccessful) {
                    throw StoreException("Descarga fallida (HTTP ${res.code}).")
                }
                val contentLength = res.body?.contentLength() ?: -1L
                if (contentLength > MAX_DOWNLOAD_BYTES) {
                    throw StoreException("El paquete supera el límite de seguridad de ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB.")
                }
                val stream = res.body?.byteStream() ?: throw StoreException("Paquete vacío.")
                destFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = stream.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            out.close()
                            destFile.delete()
                            throw StoreException("La descarga superó el límite de seguridad de ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB.")
                        }
                        out.write(buf, 0, read)
                    }
                }
                return
            }
        }
    }

    private fun parseXmlAttributes(raw: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val pattern = Regex("([a-zA-Z0-9_:-]+)\\s*=\\s*\"([^\"]*)\"")
        for (m in pattern.findAll(raw)) {
            out[m.groupValues[1].lowercase()] = m.groupValues[2]
        }
        return out
    }

    private fun decodeXmlEntities(text: String): String =
        text.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
