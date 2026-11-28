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
 * - Protección estricta contra Zip Slip (normalización + validación de ruta canónica) y entradas duplicadas.
 * - Extracción e inspección en flujo directo a disco (sin cargar el paquete completo en RAM) con protección
 *   contra zip bombs (relación de compresión anómala) y cotas amplias compatibles con extensiones de escritorio.
 * - Verificación criptográfica CRX2/CRX3 mediante [CrxPackage] (anti-políglota EOCD).
 * - Continuidad de clave pública ([publicKeySha256]): bloquea degradaciones a paquetes sin firma o con clave distinta.
 * - Activación segura por defecto (`enabled = false`) en paquetes no firmados, incompatibles, parciales con APIs
 *   sin soporte o con acceso amplio a todos los sitios, salvo confirmación explícita del usuario.
 * - Desinstalación limpia que borra archivos, almacenamiento aislado y permisos.
 * Puro sobre [File]: probado en la JVM.
 */
object NxrPackageInstaller {
    internal val mutationLock = Any()
    private val activeStages = HashSet<File>()
    internal val catalogRevision = java.util.concurrent.atomic.AtomicLong()
    const val MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L
    const val MAX_SINGLE_FILE_BYTES = 100 * 1024 * 1024
    const val MAX_FILES = 20_000
    private const val MAX_TEXT_SCRIPT_BYTES = 16 * 1024 * 1024
    private const val MAX_METADATA_JSON_BYTES = 2 * 1024 * 1024
    const val ORIGIN_CHROME_WEB_STORE = "chrome_web_store"
    const val ORIGIN_EDGE_ADDONS = "edge_addons"
    const val ORIGIN_LOCAL_FILE = "local_file"
    const val ORIGIN_DIRECT_URL = "direct_url"
    private const val META_FILE = "meta.json"
    private const val FILES_DIR = "files"

    class InstallException(message: String) : Exception(message)

    data class SourceInfo(
        val sourceOrigin: String = ORIGIN_LOCAL_FILE,
        val storeId: String? = null,
        val sourceUrl: String? = null,
        val updateUrl: String? = null,
        val publicKeySha256: String? = null,
        val developer: String? = null,
        val signatureVerified: Boolean = false
    ) {
        fun originLabel(): String = when (sourceOrigin) {
            ORIGIN_CHROME_WEB_STORE -> "Chrome Web Store"
            ORIGIN_EDGE_ADDONS -> "Microsoft Edge Add-ons"
            ORIGIN_DIRECT_URL -> "URL HTTPS directa"
            else -> "Archivo local"
        }
    }

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
        val totalUncompressedBytes: Long,
        val publicKeySha256: String? = null,
        val signatureVerified: Boolean = false,
        val signerCount: Int = 0,
        val installVerdict: ExtensionCompatibility.InstallVerdict = capabilities.verdict,
        val recommendedEnabled: Boolean = false,
        val riskReasons: List<String> = emptyList()
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
        val compatibilityLevel: String,
        val sourceOrigin: String = ORIGIN_LOCAL_FILE,
        val storeId: String? = null,
        val sourceUrl: String? = null,
        val updateUrl: String? = null,
        val publicKeySha256: String? = null,
        val developer: String? = null,
        val signatureVerified: Boolean = false
    ) {
        fun sourceInfo(): SourceInfo = SourceInfo(
            sourceOrigin = sourceOrigin,
            storeId = storeId,
            sourceUrl = sourceUrl,
            updateUrl = updateUrl,
            publicKeySha256 = publicKeySha256,
            developer = developer,
            signatureVerified = signatureVerified
        )

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
            .put("sourceOrigin", sourceOrigin)
            .put("storeId", storeId ?: JSONObject.NULL)
            .put("sourceUrl", sourceUrl ?: JSONObject.NULL)
            .put("updateUrl", updateUrl ?: JSONObject.NULL)
            .put("publicKeySha256", publicKeySha256 ?: JSONObject.NULL)
            .put("developer", developer ?: JSONObject.NULL)
            .put("signatureVerified", signatureVerified)

        companion object {
            fun fromJson(json: JSONObject): InstalledExtension {
                fun list(key: String): List<String> {
                    val arr = json.optJSONArray(key) ?: return emptyList()
                    return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
                }
                fun optClean(key: String): String? =
                    json.optString(key).takeIf { it.isNotBlank() && it != "null" }
                val limited = linkedMapOf<String, String>()
                json.optJSONObject("limitedApis")?.let { obj ->
                    val it = obj.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        limited[k] = obj.optString(k)
                    }
                }
                val sourceType = json.optString("sourceType", "ZIP")
                return InstalledExtension(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    version = json.getString("version"),
                    description = json.optString("description"),
                    manifestVersion = json.optInt("manifestVersion", 3),
                    sourceType = sourceType,
                    installedAt = json.optLong("installedAt", 0L),
                    iconPath = optClean("iconPath"),
                    permissions = list("permissions"),
                    hostPermissions = list("hostPermissions"),
                    unsupportedApis = list("unsupportedApis"),
                    limitedApis = limited,
                    compatibilityLevel = json.optString("compatibilityLevel", ExtensionCompatibility.Level.PARTIAL.name),
                    sourceOrigin = optClean("sourceOrigin") ?: ORIGIN_LOCAL_FILE,
                    storeId = optClean("storeId"),
                    sourceUrl = optClean("sourceUrl"),
                    updateUrl = optClean("updateUrl"),
                    publicKeySha256 = optClean("publicKeySha256"),
                    developer = optClean("developer"),
                    signatureVerified = json.optBoolean("signatureVerified", sourceType.startsWith("CRX"))
                )
            }
        }
    }

    private data class VerifiedArchiveSummary(
        val sourceType: String,
        val crxId: String?,
        val publicKeySha256: String?,
        val signatureVerified: Boolean,
        val signerCount: Int,
        val manifestBytes: ByteArray?,
        val localeMessages: Map<String, ByteArray>,
        val entryPaths: Set<String>,
        val totalUncompressedBytes: Long
    )

    /** Evalúa si es seguro activar automáticamente una nueva instalación sin confirmación adicional. */
    fun evaluateRisk(
        capabilities: NxrPermissions.CapabilitySummary,
        signatureVerified: Boolean,
        missingFiles: List<String>
    ): Pair<Boolean, List<String>> {
        val reasons = mutableListOf<String>()
        if (!signatureVerified) {
            reasons += "Paquete sin firma criptográfica CRX verificable."
        }
        if (capabilities.level == ExtensionCompatibility.Level.UNSUPPORTED) {
            reasons += "No contiene content scripts ejecutables compatibles con Cefrium 0.22.0."
        }
        if (capabilities.unsupportedApis.isNotEmpty()) {
            reasons += "Solicita APIs sin soporte en Cefrium (${capabilities.unsupportedApis.take(4).joinToString(", ")})."
        }
        if (capabilities.hasBroadHostAccess) {
            reasons += "Solicita acceso amplio a todos los sitios web (<all_urls> / *://*/*)."
        }
        if (missingFiles.isNotEmpty()) {
            reasons += "Faltan archivos referenciados por el manifiesto (${missingFiles.size})."
        }
        val safeByDefault = reasons.isEmpty()
        return safeByDefault to reasons
    }

    /** Inspecciona un paquete `.crx`/`.xpi`/`.zip` sin instalarlo (para pantalla de consentimiento). */
    fun inspect(
        packageFile: File,
        localeTag: String = Locale.getDefault().toLanguageTag(),
        expectedId: String? = null
    ): Inspection {
        val archive = streamVerifiedZip(packageFile, expectedId, extractDir = null)
        val manifestBytes = archive.manifestBytes
            ?: throw InstallException("El paquete no contiene manifest.json en la raíz.")
        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        val manifest = runCatching { ExtensionManifest.parse(manifestText) }
            .getOrElse { throw InstallException(it.message ?: "manifest.json no es válido.") }

        val messages = ExtensionMessages.load("", manifest.defaultLocale, localeTag) { folder ->
            archive.localeMessages["_locales/$folder/messages.json"]?.toString(Charsets.UTF_8)
        }
        val id = deriveExtensionId(archive.crxId, manifest, manifestBytes)
        if (expectedId != null && id != expectedId) {
            throw InstallException("El identificador del paquete ($id) no coincide con el esperado ($expectedId).")
        }
        val capabilities = NxrPermissions.summarizeCapabilities(manifest)
        val requestedHosts = NxrPermissions.requestedHostPatterns(manifest)
        val requiredScriptFiles = manifest.contentScripts.flatMap { it.js + it.css }
            .mapNotNull { ExtensionManifest.normalizePath(it) }
            .distinct()
        val missingFiles = requiredScriptFiles.filterNot { it in archive.entryPaths }
        val (recommendedEnabled, riskReasons) = evaluateRisk(capabilities, archive.signatureVerified, missingFiles)
        val verdict = when {
            capabilities.level == ExtensionCompatibility.Level.UNSUPPORTED -> ExtensionCompatibility.InstallVerdict.UNSUPPORTED
            !archive.signatureVerified -> ExtensionCompatibility.InstallVerdict.UNVERIFIABLE
            else -> capabilities.verdict
        }

        return Inspection(
            id = id,
            sourceType = archive.sourceType,
            manifest = manifest,
            localizedName = messages.localize(manifest.name),
            localizedDescription = messages.localize(manifest.description),
            capabilities = capabilities,
            requestedHosts = requestedHosts,
            missingFiles = missingFiles,
            fileCount = archive.entryPaths.size,
            totalUncompressedBytes = archive.totalUncompressedBytes,
            publicKeySha256 = archive.publicKeySha256,
            signatureVerified = archive.signatureVerified,
            signerCount = archive.signerCount,
            installVerdict = verdict,
            recommendedEnabled = recommendedEnabled,
            riskReasons = riskReasons
        )
    }

    /**
     * Instala el paquete validado en `[rootDir]/<id>/` con los hosts explícitamente
     * autorizados en [grantedHosts], comprobando continuidad de clave criptográfica
     * frente a instalaciones previas y aplicando activación segura por defecto.
     */
    fun install(
        rootDir: File,
        packageFile: File,
        grantedHosts: List<String>? = null,
        localeTag: String = Locale.getDefault().toLanguageTag(),
        sourceInfo: SourceInfo? = null,
        enableAfterInstall: Boolean? = null,
        expectedId: String? = null
    ): InstalledExtension {
        val staging = synchronized(mutationLock) {
            rootDir.mkdirs()
            File(rootDir, "nxr.staging-${System.nanoTime()}").also { activeStages.add(it.absoluteFile) }
        }
        val filesDir = File(staging, FILES_DIR)
        filesDir.mkdirs()

        try {
            val archive = streamVerifiedZip(packageFile, expectedId, extractDir = filesDir)
            val manifestBytes = archive.manifestBytes
                ?: throw InstallException("El paquete no contiene manifest.json.")
            val manifestText = manifestBytes.toString(Charsets.UTF_8)
            val manifest = runCatching { ExtensionManifest.parse(manifestText) }
                .getOrElse { throw InstallException(it.message ?: "manifest.json no es válido.") }
            val messages = ExtensionMessages.load("", manifest.defaultLocale, localeTag) { folder ->
                archive.localeMessages["_locales/$folder/messages.json"]?.toString(Charsets.UTF_8)
            }
            val id = deriveExtensionId(archive.crxId, manifest, manifestBytes)
            if (expectedId != null && id != expectedId) {
                throw InstallException("El identificador del paquete ($id) no coincide con el esperado ($expectedId).")
            }
            val capabilities = NxrPermissions.summarizeCapabilities(manifest)
            val requestedHosts = NxrPermissions.requestedHostPatterns(manifest)
            val effectiveHosts = (grantedHosts ?: requestedHosts).filter { it.isNotBlank() }.distinct()
            val requiredScriptFiles = manifest.contentScripts.flatMap { it.js + it.css }
                .mapNotNull { ExtensionManifest.normalizePath(it) }
                .distinct()
            val missingFiles = requiredScriptFiles.filterNot { it in archive.entryPaths }
            val (recommendedEnabled, _) = evaluateRisk(capabilities, archive.signatureVerified, missingFiles)

            return synchronized(mutationLock) {
                val extDir = File(rootDir, id)
                if (!recoverBackup(rootDir, id)) throw InstallException("No se pudo recuperar la extensión existente.")
                val existingMeta = readInstalledMeta(extDir)

                // Continuidad de clave criptográfica: nunca permitir degradación sin firma o cambio de clave.
                if (existingMeta != null && !existingMeta.publicKeySha256.isNullOrBlank()) {
                    if (!archive.signatureVerified || archive.publicKeySha256.isNullOrBlank()) {
                        throw InstallException(
                            "Actualización bloqueada por seguridad: un paquete sin firma no puede sobrescribir la extensión firmada «${existingMeta.name}»."
                        )
                    }
                    if (archive.publicKeySha256 != existingMeta.publicKeySha256) {
                        throw InstallException(
                            "Actualización bloqueada por seguridad: la huella de la clave pública no coincide con la extensión instalada «${existingMeta.name}»."
                        )
                    }
                }

                val bestIcon = manifest.icons.entries.maxByOrNull { it.key }?.value
                    ?.let { ExtensionManifest.normalizePath(it) }
                    ?.takeIf { it in archive.entryPaths }

                val mergedSource = sourceInfo ?: existingMeta?.sourceInfo() ?: SourceInfo()
                val installed = InstalledExtension(
                    id = id,
                    name = messages.localize(manifest.name),
                    version = manifest.version,
                    description = messages.localize(manifest.description),
                    manifestVersion = manifest.manifestVersion,
                    sourceType = archive.sourceType,
                    installedAt = System.currentTimeMillis(),
                    iconPath = bestIcon,
                    permissions = manifest.permissions.sorted(),
                    hostPermissions = requestedHosts,
                    unsupportedApis = capabilities.unsupportedApis,
                    limitedApis = capabilities.limitedApis,
                    compatibilityLevel = capabilities.level.name,
                    sourceOrigin = mergedSource.sourceOrigin,
                    storeId = mergedSource.storeId ?: archive.crxId,
                    sourceUrl = mergedSource.sourceUrl,
                    updateUrl = mergedSource.updateUrl,
                    publicKeySha256 = archive.publicKeySha256 ?: mergedSource.publicKeySha256,
                    developer = mergedSource.developer,
                    signatureVerified = archive.signatureVerified
                )
                File(staging, META_FILE).writeText(installed.toJson().toString(), Charsets.UTF_8)

                // Conservar almacenamiento existente (global y particionado por origen) si es una actualización.
                val existingPolicy = if (extDir.isDirectory) NxrPermissions.readPolicy(extDir) else null
                val initialEnabled = enableAfterInstall ?: existingPolicy?.enabled ?: recommendedEnabled
                val policy = (existingPolicy ?: NxrPermissions.Policy(enabled = initialEnabled, allowInPrivate = false))
                    .copy(
                        enabled = initialEnabled,
                        grantedHostPatterns = effectiveHosts
                    )
                NxrPermissions.writePolicy(staging, policy)

                val existingStorage = File(extDir, NxrStorage.STORAGE_FILE)
                if (existingStorage.isFile) {
                    existingStorage.copyTo(File(staging, NxrStorage.STORAGE_FILE), overwrite = true)
                }
                val existingOrigins = File(extDir, NxrStorage.ORIGINS_DIR)
                if (existingOrigins.isDirectory) {
                    existingOrigins.copyRecursively(File(staging, NxrStorage.ORIGINS_DIR), overwrite = true)
                }

                // Intercambio transaccional con respaldo y reversión automática si falla el cambio.
                val backup = if (extDir.exists()) File(rootDir, "$id.backup-${System.nanoTime()}") else null
                if (backup != null) {
                    if (!extDir.renameTo(backup)) {
                        throw InstallException("No se pudo preparar la actualización atómica de la extensión.")
                    }
                }
                if (!staging.renameTo(extDir)) {
                    if (backup != null && !backup.renameTo(extDir)) {
                        throw InstallException("La copia válida quedó respaldada para su recuperación.")
                    }
                    throw InstallException("No se pudo publicar la extensión.")
                }
                backup?.deleteRecursively()
                catalogRevision.incrementAndGet()
                DnrBridge.refresh(rootDir, id)
                installed
            }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            if (t is InstallException) throw t
            throw InstallException(t.message ?: "Error al instalar la extensión.")
        } finally {
            synchronized(mutationLock) { activeStages.remove(staging.absoluteFile) }
        }
    }

    fun readInstalledMeta(extDir: File): InstalledExtension? {
        val meta = File(extDir, META_FILE)
        if (!meta.isFile) return null
        return runCatching { InstalledExtension.fromJson(JSONObject(meta.readText(Charsets.UTF_8))) }.getOrNull()
    }

    private val backupName = Regex("(.+)\\.backup--?\\d+")

    private fun validVersion(file: File, id: String): Boolean =
        readInstalledMeta(file)?.id == id && File(File(file, FILES_DIR), "manifest.json").isFile

    private fun recoverBackup(rootDir: File, id: String): Boolean {
        val active = File(rootDir, id)
        val backups = rootDir.listFiles()?.filter { dir ->
            dir.isDirectory && backupName.matchEntire(dir.name)?.groupValues?.get(1) == id
        }.orEmpty()
        if (backups.isEmpty()) return true
        if (!validVersion(active, id)) {
            val previous = backups.filter { validVersion(it, id) }.maxByOrNull(File::lastModified)
                ?: return false
            val displaced = File(rootDir, "nxr.staging-${System.nanoTime()}")
            if (active.exists() && !active.renameTo(displaced)) return false
            if (!previous.renameTo(active)) {
                if (displaced.exists()) displaced.renameTo(active)
                return false
            }
            displaced.deleteRecursively()
            catalogRevision.incrementAndGet()
        }
        backups.forEach { if (it.exists()) it.deleteRecursively() }
        return true
    }

    fun cleanupOrphanDirs(rootDir: File) = synchronized(mutationLock) {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory }.orEmpty()
        dirs.mapNotNull { backupName.matchEntire(it.name)?.groupValues?.get(1) }
            .distinct().forEach { recoverBackup(rootDir, it) }
        dirs.filter { it.name.startsWith("nxr.staging-") && it.absoluteFile !in activeStages }
            .forEach { it.deleteRecursively() }
    }

    /** Elimina los archivos y el almacenamiento de la extensión seleccionada por el usuario. */
    fun uninstall(rootDir: File, extId: String): Boolean = synchronized(mutationLock) {
        val safe = runCatching { NxrStorage.sanitizeId(extId) }.getOrNull() ?: return false
        val dir = File(rootDir, safe)
        val canonicalBase = rootDir.canonicalPath + File.separator
        if (!dir.canonicalPath.startsWith(canonicalBase)) return false
        if (!recoverBackup(rootDir, safe)) return false
        val removed = dir.exists() && dir.deleteRecursively()
        if (removed) catalogRevision.incrementAndGet()
        removed
    }

    fun listInstalled(rootDir: File): List<InstalledExtension> {
        if (!rootDir.isDirectory) return emptyList()
        return rootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.contains(".staging-") && !it.name.contains(".backup-") }
            ?.mapNotNull { dir -> readInstalledMeta(dir) }
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
        if (target.length() > MAX_TEXT_SCRIPT_BYTES) return null
        return runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun deriveExtensionId(crxId: String?, manifest: ExtensionManifest, manifestBytes: ByteArray): String {
        if (!crxId.isNullOrBlank()) return NxrStorage.sanitizeId(crxId)
        if (!manifest.geckoId.isNullOrBlank()) return NxrStorage.sanitizeId(manifest.geckoId)
        val digest = MessageDigest.getInstance("SHA-256").digest(manifestBytes)
        val hex = digest.take(12).joinToString("") { "%02x".format(it) }
        return "nxr_$hex"
    }

    private const val MAX_COMPRESSION_RATIO = 100L
    private const val RATIO_CHECK_MIN_BYTES = 25L * 1024L * 1024L

    /**
     * Verifica firma CRX (si aplica) y recorre el ZIP en streaming sin cargar binarios en memoria.
     * Si [extractDir] no es nulo, escribe cada archivo validado directamente en disco.
     */
    private fun streamVerifiedZip(
        packageFile: File,
        expectedId: String? = null,
        extractDir: File? = null
    ): VerifiedArchiveSummary {
        if (!packageFile.isFile || packageFile.length() == 0L) {
            throw InstallException("Archivo de extensión vacío o inaccesible.")
        }
        val compressedSize = packageFile.length().coerceAtLeast(1L)
        val sourceType: String
        val crxId: String?
        val publicKeySha256: String?
        val signatureVerified: Boolean
        val signerCount: Int
        val zipStream: InputStream
        if (CrxPackage.isCrx(packageFile)) {
            val verified = runCatching { CrxPackage.verify(packageFile, expectedId = expectedId) }
                .getOrElse { throw InstallException(it.message ?: "Firma CRX no válida.") }
            sourceType = "CRX${verified.formatVersion}"
            crxId = verified.extensionId
            publicKeySha256 = verified.publicKeySha256
            signatureVerified = true
            signerCount = verified.signerCount
            zipStream = CrxPackage.openZip(packageFile, verified)
        } else {
            sourceType = if (packageFile.name.endsWith(".xpi", ignoreCase = true)) "XPI" else "ZIP"
            crxId = null
            publicKeySha256 = null
            signatureVerified = false
            signerCount = 0
            zipStream = packageFile.inputStream()
        }

        val canonicalRoot = extractDir?.let { it.canonicalPath + File.separator }
        val seenPaths = HashSet<String>()
        var manifestBytes: ByteArray? = null
        val localeMessages = HashMap<String, ByteArray>()
        var totalBytes = 0L
        var fileCount = 0

        ZipInputStream(zipStream).use { zis ->
            val buf = ByteArray(64 * 1024)
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
                if (!seenPaths.add(normalized)) {
                    throw InstallException("El paquete contiene entradas duplicadas: $normalized")
                }
                if (++fileCount > MAX_FILES) {
                    throw InstallException("El paquete supera el límite de seguridad de $MAX_FILES archivos.")
                }

                val isManifest = normalized == "manifest.json"
                val isLocale = normalized.startsWith("_locales/") && normalized.endsWith("/messages.json")
                val captureBuf = if (isManifest || isLocale) ByteArrayOutputStream() else null

                val diskOut = if (extractDir != null && canonicalRoot != null) {
                    val dest = File(extractDir, normalized)
                    if (!dest.canonicalPath.startsWith(canonicalRoot)) {
                        throw InstallException("Ruta peligrosa detectada en el paquete: $normalized")
                    }
                    dest.parentFile?.mkdirs()
                    dest.outputStream().buffered(64 * 1024)
                } else null

                var entryBytes = 0L
                try {
                    while (true) {
                        val read = zis.read(buf)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_SINGLE_FILE_BYTES) {
                            throw InstallException("El archivo «$normalized» supera el límite de seguridad de ${MAX_SINGLE_FILE_BYTES / (1024 * 1024)} MB.")
                        }
                        if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw InstallException("El paquete descomprimido supera el límite de seguridad de ${MAX_UNCOMPRESSED_BYTES / (1024 * 1024)} MB.")
                        }
                        if (totalBytes > RATIO_CHECK_MIN_BYTES && totalBytes > compressedSize * MAX_COMPRESSION_RATIO) {
                            throw InstallException("Relación de compresión anómala detectada (posible zip bomb).")
                        }
                        diskOut?.write(buf, 0, read)
                        if (captureBuf != null && captureBuf.size() + read <= MAX_METADATA_JSON_BYTES) {
                            captureBuf.write(buf, 0, read)
                        }
                    }
                } finally {
                    diskOut?.close()
                }

                if (isManifest && captureBuf != null) {
                    manifestBytes = captureBuf.toByteArray()
                } else if (isLocale && captureBuf != null) {
                    localeMessages[normalized] = captureBuf.toByteArray()
                }
            }
        }
        return VerifiedArchiveSummary(
            sourceType = sourceType,
            crxId = crxId,
            publicKeySha256 = publicKeySha256,
            signatureVerified = signatureVerified,
            signerCount = signerCount,
            manifestBytes = manifestBytes,
            localeMessages = localeMessages,
            entryPaths = seenPaths,
            totalUncompressedBytes = totalBytes
        )
    }
}
