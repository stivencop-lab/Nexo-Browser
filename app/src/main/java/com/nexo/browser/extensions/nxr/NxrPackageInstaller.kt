package com.nexo.browser.extensions.nxr

import com.nexo.browser.extensions.CrxPackage
import com.nexo.browser.extensions.ExtensionCompatibility
import com.nexo.browser.extensions.ExtensionManifest
import com.nexo.browser.extensions.ExtensionMessages
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Instalador seguro de paquetes `.crx`, `.xpi` y `.zip` para NXR.
 *
 * Controles de seguridad:
 * - Protección estricta contra Zip Slip (normalización + validación de ruta canónica).
 * - Límites duros de tamaño total descomprimido (10 MB), archivo individual (2 MB) y archivos (200).
 * - Verificación criptográfica CRX2/CRX3 mediante [CrxPackage].
 * - Desinstalación limpia que borra archivos, almacenamiento aislado y permisos.
 * Puro sobre [File]: probado en la JVM.
 */
object NxrPackageInstaller {
    const val MAX_UNCOMPRESSED_BYTES = 10L * 1024L * 1024L
    const val MAX_SINGLE_FILE_BYTES = 2 * 1024 * 1024
    const val MAX_FILES = 200
    private const val META_FILE = "meta.json"
    private const val FILES_DIR = "files"

    class InstallException(message: String) : Exception(message)

    data class Inspection(
        val id: String,
        val sourceType: String,
        val manifest: ExtensionManifest,
        val localizedName: String,
        val localizedDescription: String,
        val capabilities: NxrPermissions.CapabilitySummary,
        val requestedHosts: List<String>,
        val missingFiles: List<String>,
        val fileCount: Int,
        val totalUncompressedBytes: Long
    )

    data class InstalledExtension(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val manifestVersion: Int,
        val sourceType: String,
        val installedAt: Long,
        val iconPath: String?,
        val permissions: List<String>,
        val hostPermissions: List<String>,
        val unsupportedApis: List<String>,
        val limitedApis: Map<String, String>,
        val compatibilityLevel: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("description", description)
            .put("manifestVersion", manifestVersion)
            .put("sourceType", sourceType)
            .put("installedAt", installedAt)
            .put("iconPath", iconPath ?: JSONObject.NULL)
            .put("permissions", JSONArray(permissions))
            .put("hostPermissions", JSONArray(hostPermissions))
            .put("unsupportedApis", JSONArray(unsupportedApis))
            .put("limitedApis", JSONObject().apply { limitedApis.forEach { (k, v) -> put(k, v) } })
            .put("compatibilityLevel", compatibilityLevel)

        companion object {
            fun fromJson(json: JSONObject): InstalledExtension {
                fun list(key: String): List<String> {
                    val arr = json.optJSONArray(key) ?: return emptyList()
                    return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                }
                val limited = linkedMapOf<String, String>()
                json.optJSONObject("limitedApis")?.let { obj ->
                    val it = obj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        limited[k] = obj.optString(k)
                    }
                }
                return InstalledExtension(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    version = json.getString("version"),
                    description = json.optString("description"),
                    manifestVersion = json.optInt("manifestVersion", 3),
                    sourceType = json.optString("sourceType", "ZIP"),
                    installedAt = json.optLong("installedAt", 0L),
                    iconPath = json.optString("iconPath").takeIf { it.isNotBlank() && it != "null" },
                    permissions = list("permissions"),
                    hostPermissions = list("hostPermissions"),
                    unsupportedApis = list("unsupportedApis"),
                    limitedApis = limited,
                    compatibilityLevel = json.optString("compatibilityLevel", ExtensionCompatibility.Level.PARTIAL.name)
                )
            }
        }
    }

    /** Inspecciona un paquete `.crx`/`.xpi`/`.zip` sin instalarlo (para pantalla de consentimiento). */
    fun inspect(packageFile: File, localeTag: String = Locale.getDefault().toLanguageTag()): Inspection {
        val (sourceType, crxId, entries) = readVerifiedZip(packageFile)
        val manifestBytes = entries["manifest.json"]
            ?: throw InstallException("El paquete no contiene manifest.json en la raíz.")
        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        val manifest = runCatching { ExtensionManifest.parse(manifestText) }
            .getOrElse { throw InstallException(it.message ?: "manifest.json no es válido.") }

        val messages = ExtensionMessages.load("", manifest.defaultLocale, localeTag) { folder ->
            entries["_locales/$folder/messages.json"]?.toString(Charsets.UTF_8)
        }
        val id = deriveExtensionId(crxId, manifest, manifestBytes)
        val capabilities = NxrPermissions.summarizeCapabilities(manifest)
        val requestedHosts = NxrPermissions.requestedHostPatterns(manifest)
        val requiredScriptFiles = manifest.contentScripts.flatMap { it.js + it.css }
            .mapNotNull { ExtensionManifest.normalizePath(it) }
            .distinct()
        val missingFiles = requiredScriptFiles.filterNot { it in entries }
        val totalBytes = entries.values.sumOf { it.size.toLong() }

        return Inspection(
            id = id,
            sourceType = sourceType,
            manifest = manifest,
            localizedName = messages.localize(manifest.name),
            localizedDescription = messages.localize(manifest.description),
            capabilities = capabilities,
            requestedHosts = requestedHosts,
            missingFiles = missingFiles,
            fileCount = entries.size,
            totalUncompressedBytes = totalBytes
        )
    }

    /**
     * Instala el paquete validado en `[rootDir]/<id>/` con los hosts explícitamente
     * autorizados en [grantedHosts] y limpieza segura si falla a mitad.
     */
    fun install(
        rootDir: File,
        packageFile: File,
        grantedHosts: List<String>? = null,
        localeTag: String = Locale.getDefault().toLanguageTag()
    ): InstalledExtension {
        val (sourceType, crxId, entries) = readVerifiedZip(packageFile)
        val manifestBytes = entries["manifest.json"]
            ?: throw InstallException("El paquete no contiene manifest.json.")
        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        val manifest = runCatching { ExtensionManifest.parse(manifestText) }
            .getOrElse { throw InstallException(it.message ?: "manifest.json no es válido.") }
        val messages = ExtensionMessages.load("", manifest.defaultLocale, localeTag) { folder ->
            entries["_locales/$folder/messages.json"]?.toString(Charsets.UTF_8)
        }
        val id = deriveExtensionId(crxId, manifest, manifestBytes)
        val capabilities = NxrPermissions.summarizeCapabilities(manifest)
        val requestedHosts = NxrPermissions.requestedHostPatterns(manifest)
        val effectiveHosts = (grantedHosts ?: requestedHosts).filter { it.isNotBlank() }.distinct()

        rootDir.mkdirs()
        val extDir = File(rootDir, id)
        val staging = File(rootDir, "$id.staging-${System.nanoTime()}")
        val filesDir = File(staging, FILES_DIR)
        filesDir.mkdirs()
        val canonicalRoot = filesDir.canonicalPath + File.separator

        try {
            for ((relPath, bytes) in entries) {
                val dest = File(filesDir, relPath)
                if (!dest.canonicalPath.startsWith(canonicalRoot)) {
                    throw InstallException("Ruta peligrosa detectada en el paquete: $relPath")
                }
                dest.parentFile?.mkdirs()
                dest.writeBytes(bytes)
            }

            val bestIcon = manifest.icons.entries.maxByOrNull { it.key }?.value
                ?.let { ExtensionManifest.normalizePath(it) }
                ?.takeIf { it in entries }

            val installed = InstalledExtension(
                id = id,
                name = messages.localize(manifest.name),
                version = manifest.version,
                description = messages.localize(manifest.description),
                manifestVersion = manifest.manifestVersion,
                sourceType = sourceType,
                installedAt = System.currentTimeMillis(),
                iconPath = bestIcon,
                permissions = manifest.permissions.sorted(),
                hostPermissions = requestedHosts,
                unsupportedApis = capabilities.unsupportedApis,
                limitedApis = capabilities.limitedApis,
                compatibilityLevel = capabilities.level.name
            )
            File(staging, META_FILE).writeText(installed.toJson().toString(), Charsets.UTF_8)

            // Conservar almacenamiento existente si es una actualización de la misma extensión.
            val existingPolicy = if (extDir.isDirectory) NxrPermissions.readPolicy(extDir) else null
            val policy = (existingPolicy ?: NxrPermissions.Policy(enabled = true, allowInPrivate = false))
                .copy(grantedHostPatterns = effectiveHosts)
            NxrPermissions.writePolicy(staging, policy)

            val existingStorage = File(extDir, "storage_local.json")
            if (existingStorage.isFile) {
                existingStorage.copyTo(File(staging, "storage_local.json"), overwrite = true)
            }

            if (extDir.exists()) extDir.deleteRecursively()
            if (!staging.renameTo(extDir)) {
                staging.copyRecursively(extDir, overwrite = true)
                staging.deleteRecursively()
            }
            return installed
        } catch (t: Throwable) {
            staging.deleteRecursively()
            if (t is InstallException) throw t
            throw InstallException(t.message ?: "Error al instalar la extensión.")
        }
    }

    /** Elimina por completo la extensión: archivos extraídos, almacenamiento local y permisos. */
    fun uninstall(rootDir: File, extId: String): Boolean {
        val safe = runCatching { NxrStorage.sanitizeId(extId) }.getOrNull() ?: return false
        val dir = File(rootDir, safe)
        val canonicalBase = rootDir.canonicalPath + File.separator
        if (!dir.canonicalPath.startsWith(canonicalBase)) return false
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    fun listInstalled(rootDir: File): List<InstalledExtension> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.contains(".staging-") }
            ?.mapNotNull { dir ->
                val meta = File(dir, META_FILE)
                if (!meta.isFile) return@mapNotNull null
                runCatching { InstalledExtension.fromJson(JSONObject(meta.readText(Charsets.UTF_8))) }.getOrNull()
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun filesDir(rootDir: File, extId: String): File =
        File(File(rootDir, NxrStorage.sanitizeId(extId)), FILES_DIR)

    fun extensionDir(rootDir: File, extId: String): File =
        File(rootDir, NxrStorage.sanitizeId(extId))

    fun readManifest(rootDir: File, extId: String): ExtensionManifest? {
        val file = File(filesDir(rootDir, extId), "manifest.json")
        if (!file.isFile) return null
        return runCatching { ExtensionManifest.parse(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun readPackagedText(rootDir: File, extId: String, relativePath: String): String? {
        val normalized = ExtensionManifest.normalizePath(relativePath) ?: return null
        val base = filesDir(rootDir, extId)
        val target = File(base, normalized)
        val canonicalBase = base.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(canonicalBase) || !target.isFile) return null
        if (target.length() > MAX_SINGLE_FILE_BYTES) return null
        return runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun deriveExtensionId(crxId: String?, manifest: ExtensionManifest, manifestBytes: ByteArray): String {
        if (!crxId.isNullOrBlank()) return NxrStorage.sanitizeId(crxId)
        if (!manifest.geckoId.isNullOrBlank()) return NxrStorage.sanitizeId(manifest.geckoId)
        val digest = MessageDigest.getInstance("SHA-256").digest(manifestBytes)
        val hex = digest.take(12).joinToString("") { "%02x".format(it) }
        return "nxr_$hex"
    }

    private fun readVerifiedZip(packageFile: File): Triple<String, String?, Map<String, ByteArray>> {
        if (!packageFile.isFile || packageFile.length() == 0L) {
            throw InstallException("Archivo de extensión vacío o inaccesible.")
        }
        val sourceType: String
        val crxId: String?
        val zipStream: InputStream
        if (CrxPackage.isCrx(packageFile)) {
            val verified = runCatching { CrxPackage.verify(packageFile) }
                .getOrElse { throw InstallException(it.message ?: "Firma CRX no válida.") }
            sourceType = "CRX${verified.formatVersion}"
            crxId = verified.extensionId
            zipStream = CrxPackage.openZip(packageFile, verified)
        } else {
            sourceType = if (packageFile.name.endsWith(".xpi", ignoreCase = true)) "XPI" else "ZIP"
            crxId = null
            zipStream = packageFile.inputStream()
        }

        val files = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        var fileCount = 0
        ZipInputStream(zipStream).use { zis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break
                val rawName = entry.name
                // Rechazar explícitamente cualquier intento de Zip Slip antes de escribir nada.
                if (rawName.contains("..") || rawName.startsWith("/") || rawName.startsWith("\\") || rawName.contains(":")) {
                    throw InstallException("El paquete contiene una ruta no permitida (Zip Slip): $rawName")
                }
                val normalized = ExtensionManifest.normalizePath(rawName)
                    ?: throw InstallException("Ruta de archivo no válida en el paquete: $rawName")
                if (entry.isDirectory) continue
                if (++fileCount > MAX_FILES) {
                    throw InstallException("El paquete supera el límite de $MAX_FILES archivos.")
                }
                val out = ByteArrayOutputStream()
                while (true) {
                    val read = zis.read(buf)
                    if (read < 0) break
                    totalBytes += read
                    if (out.size() + read > MAX_SINGLE_FILE_BYTES) {
                        throw InstallException("El archivo «$normalized» supera el límite de ${MAX_SINGLE_FILE_BYTES / (1024 * 1024)} MB.")
                    }
                    if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                        throw InstallException("El paquete descomprimido supera el límite de ${MAX_UNCOMPRESSED_BYTES / (1024 * 1024)} MB.")
                    }
                    out.write(buf, 0, read)
                }
                files[normalized] = out.toByteArray()
            }
        }
        return Triple(sourceType, crxId, files)
    }
}
