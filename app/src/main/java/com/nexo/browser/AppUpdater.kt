package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actualizador integrado de Nexo desde GitHub Releases:
 * - Comprueba la última versión publicada en GitHub (HTTPS estricto).
 * - Elige el APK que corresponde a la arquitectura (ABI) y paquete instalados.
 * - Verifica el hash SHA-256 contra `SHA256SUMS.txt` y el certificado de firma
 *   del APK antes de abrir el instalador del sistema.
 */
object AppUpdater {
    const val API_LATEST_URL = "https://api.github.com/repos/stivencop414-bot/NavegadorGecko/releases/latest"
    const val RELEASES_PAGE_URL = LegacyImport.RELEASES_URL
    /** Huella SHA-256 oficial del certificado de firma de Nexo (config/android-signing-certificate.sha256). */
    const val EXPECTED_SIGNING_SHA256 = "a5b4232cb08151a114269947d487bf8e15b4b7a62eb76eb8fb0795752ff1694f"

    private const val PREFS = "nexo_updates"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED_VERSION = "skipped_version"
    private const val KEY_KNOWN_LATEST = "known_latest_version"
    private const val AUTO_CHECK_INTERVAL_MS = 12L * 3600L * 1000L
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    private const val MAX_SUMS_BYTES = 64 * 1024

    private val ALLOWED_HOSTS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val abi: String
    )

    data class ReleaseInfo(
        val version: String,
        val title: String,
        val notes: String,
        val htmlUrl: String,
        val apkAsset: ReleaseAsset?,
        val sha256SumsUrl: String?
    )

    private val io by lazy {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "NexoAppUpdater").apply { isDaemon = true }
        }
    }
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val checking = AtomicBoolean(false)
    @Volatile private var autoCheckedThisProcess = false
    @Volatile private var pendingInstallFile: File? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(NativeImagePolicy.publicDns(okhttp3.Dns.SYSTEM))
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps || url.host.lowercase(Locale.US) !in ALLOWED_HOSTS) {
                    throw SecurityException("Host de actualización no permitido: ${url.host}")
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    private fun readBoundedBytes(body: okhttp3.ResponseBody?, maxBytes: Int): ByteArray {
        val stream = body?.byteStream() ?: return ByteArray(0)
        return stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) throw IllegalArgumentException("La respuesta excede el tamaño máximo ($maxBytes B)")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autoCheck(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheck(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    fun skippedVersion(context: Context): String =
        prefs(context).getString(KEY_SKIPPED_VERSION, "").orEmpty()

    fun setSkippedVersion(context: Context, version: String) {
        prefs(context).edit().putString(KEY_SKIPPED_VERSION, normalizeVersion(version)).apply()
    }

    fun currentVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("").ifBlank { "0.0.0" }

    fun knownLatestVersion(context: Context): String {
        val known = prefs(context).getString(KEY_KNOWN_LATEST, "").orEmpty()
        val current = currentVersion(context)
        return if (known.isNotBlank() && isNewer(known, current)) known else ""
    }

    fun hasAvailableUpdate(context: Context): Boolean =
        knownLatestVersion(context).isNotBlank()

    fun statusSummary(context: Context): String {
        val current = currentVersion(context)
        val available = knownLatestVersion(context)
        val lastCheck = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        val state = when {
            available.isNotBlank() -> "Nueva versión disponible: v$available"
            lastCheck > 0L -> "Última comprobación: " + DateFormat.format("d MMM · HH:mm", lastCheck)
            autoCheck(context) -> "Comprobación automática activada"
            else -> "Comprobación manual"
        }
        return "Versión instalada: v$current · $state"
    }

    // ---------------------------------------------------------------
    // Funciones puras (probadas en JVM sin red ni Android)
    // ---------------------------------------------------------------

    fun normalizeVersion(raw: String): String {
        val clean = raw.trim().removePrefix("v").removePrefix("V").trim()
        val core = clean.substringBefore('-').substringBefore('+').trim()
        val parts = core.split('.').mapNotNull { it.toIntOrNull() }
        return if (parts.isEmpty()) "" else parts.joinToString(".")
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val candParts = normalizeVersion(candidate).split('.').mapNotNull { it.toIntOrNull() }
        val currParts = normalizeVersion(current).split('.').mapNotNull { it.toIntOrNull() }
        if (candParts.isEmpty() || currParts.isEmpty()) return false
        val maxLen = maxOf(candParts.size, currParts.size)
        for (i in 0 until maxLen) {
            val a = candParts.getOrElse(i) { 0 }
            val b = currParts.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun isAllowedReleaseUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return false
        val withoutScheme = trimmed.substring(8)
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank() || '@' in authority) return false
        val host = authority.substringBefore(':').lowercase(Locale.US)
        return host in ALLOWED_HOSTS
    }

    fun parseSha256Sums(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val lineRegex = Regex("""^([a-fA-F0-9]{64})\s+\*?([^\s/\\]+)$""")
        text.lineSequence().forEach { rawLine ->
            val match = lineRegex.matchEntire(rawLine.trim()) ?: return@forEach
            val hash = match.groupValues[1].lowercase(Locale.US)
            val file = match.groupValues[2].trim()
            if (file.isNotEmpty()) result[file] = hash
        }
        return result
    }

    fun selectAsset(
        assets: JSONArray,
        abis: List<String>,
        packageName: String = LegacyImport.TARGET_PACKAGE
    ): ReleaseAsset? {
        val wantTransition = packageName == LegacyImport.SOURCE_PACKAGE
        val candidates = mutableListOf<ReleaseAsset>()
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            val url = obj.optString("browser_download_url").trim()
            val size = obj.optLong("size", 0L)
            if (!name.endsWith(".apk", ignoreCase = true) || !isAllowedReleaseUrl(url)) continue
            val isTransitionApk = name.startsWith("Nexo-anterior-", ignoreCase = true)
            if (wantTransition != isTransitionApk) continue
            val matchedAbi = abis.firstOrNull { abi -> name.contains(abi, ignoreCase = true) } ?: continue
            candidates += ReleaseAsset(name, url, size, matchedAbi)
        }
        for (abi in abis) {
            candidates.firstOrNull { it.abi.equals(abi, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    fun parseRelease(
        jsonText: String,
        currentVersion: String,
        abis: List<String>,
        packageName: String = LegacyImport.TARGET_PACKAGE
    ): ReleaseInfo? {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return null
        val tagVersion = normalizeVersion(root.optString("tag_name"))
        val nameVersion = normalizeVersion(root.optString("name"))
        val version = tagVersion.ifBlank { nameVersion }
        if (version.isBlank() || !isNewer(version, currentVersion)) return null

        val htmlUrl = root.optString("html_url").trim().takeIf { isAllowedReleaseUrl(it) } ?: RELEASES_PAGE_URL
        val title = root.optString("name").trim().ifBlank { "Nexo v$version" }
        val notes = root.optString("body").trim().take(2000)
        val assets = root.optJSONArray("assets") ?: JSONArray()
        val apkAsset = selectAsset(assets, abis, packageName)
        var sumsUrl: String? = null
        for (i in 0 until assets.length()) {
            val obj = assets.optJSONObject(i) ?: continue
            if (obj.optString("name").trim() == "SHA256SUMS.txt") {
                val url = obj.optString("browser_download_url").trim()
                if (isAllowedReleaseUrl(url)) sumsUrl = url
                break
            }
        }
        return ReleaseInfo(version, title, notes, htmlUrl, apkAsset, sumsUrl)
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------
    // Comprobación e instalación en Android
    // ---------------------------------------------------------------

    /** Si quedó un APK verificado esperando permiso de instalación, retomarlo al volver. */
    fun resumePendingInstallIfAllowed(activity: Activity) {
        val file = pendingInstallFile ?: return
        if (!file.isFile) {
            pendingInstallFile = null
            return
        }
        if (activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = null
            launchPackageInstaller(activity, file)
        }
    }

    /** Comprobación automática silenciosa al abrir Nexo (máximo una vez cada 12 h). */
    fun checkIfDue(activity: Activity, onUpdateFound: (() -> Unit)? = null) {
        if (autoCheckedThisProcess || !autoCheck(activity)) return
        val now = System.currentTimeMillis()
        if (now - prefs(activity).getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return
        autoCheckedThisProcess = true
        checkInternal(activity, manual = false, onStatusChanged = onUpdateFound)
    }

    /** Comprobación iniciada por el usuario desde Ajustes o el menú principal. */
    fun checkManual(activity: Activity, onStatusChanged: (() -> Unit)? = null) {
        Toast.makeText(activity, "Buscando actualizaciones…", Toast.LENGTH_SHORT).show()
        checkInternal(activity, manual = true, onStatusChanged = onStatusChanged)
    }

    private fun checkInternal(
        activity: Activity,
        manual: Boolean,
        onStatusChanged: (() -> Unit)?
    ) {
        if (!checking.compareAndSet(false, true)) return
        val app = activity.applicationContext
        val current = currentVersion(app)
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty().ifEmpty { listOf("arm64-v8a", "armeabi-v7a") }

        io.execute {
            val outcome = runCatching {
                val request = Request.Builder()
                    .url(API_LATEST_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "NexoBrowser/$current")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = String(readBoundedBytes(response.body, 512 * 1024), Charsets.UTF_8)
                    val release = JSONObject(body)
                    require(listOf(release.optString("tag_name"), release.optString("name")).any {
                        Regex("""(?i)^v?\d+(?:\.\d+)*(?:[-+][\w.+-]+)?$""").matches(it.trim())
                    } && release.optJSONArray("assets") != null) { "GitHub devolvió una versión con formato inválido." }
                    parseRelease(body, current, abis, app.packageName)
                }
            }
            main.post {
                checking.set(false)
                outcome.fold(
                    onSuccess = { info ->
                        prefs(app).edit()
                            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
                            .putString(KEY_KNOWN_LATEST, info?.version.orEmpty())
                            .apply()
                        onStatusChanged?.invoke()
                        if (activity.isFinishing || activity.isDestroyed) return@fold
                        if (info != null) {
                            if (!manual && info.version == skippedVersion(app)) return@fold
                            showUpdateSheet(activity, info, manual, onStatusChanged)
                        } else if (manual) {
                            showUpToDateDialog(activity, current)
                        }
                    },
                    onFailure = { err ->
                        onStatusChanged?.invoke()
                        if (manual && !activity.isFinishing && !activity.isDestroyed) {
                            AlertDialog.Builder(activity)
                                .setTitle("No se pudo comprobar")
                                .setMessage("No fue posible consultar GitHub Releases (${err.message ?: "error de red"}).\n\nPuedes abrir la página oficial de versiones para comprobarlo manualmente.")
                                .setPositiveButton("Abrir GitHub") { _, _ ->
                                    openReleasesPage(activity, RELEASES_PAGE_URL)
                                }
                                .setNegativeButton("Cerrar", null)
                                .show()
                        }
                    }
                )
            }
        }
    }

    private fun showUpToDateDialog(activity: Activity, current: String) {
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        val card = NexoUi.card(activity).apply {
            addView(TextView(activity).apply {
                text = "Tienes instalada la versión más reciente de Nexo (v$current)."
                textSize = 14f
                setTextColor(ThemeManager.palette(activity).text)
            })
            addView(NexoUi.note(activity, "Las actualizaciones verifican la firma oficial y el hash SHA-256 antes de instalarse."))
        }
        body.addView(card)
        body.addView(NexoUi.button(activity, "Ver versiones en GitHub") {
            sheet.dismiss()
            openReleasesPage(activity, RELEASES_PAGE_URL)
        }, NexoUi.buttonParams(activity, 12f))
        body.addView(NexoUi.button(activity, "Listo", primary = true) {
            sheet.dismiss()
        }, NexoUi.buttonParams(activity, 8f))
        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Nexo está actualizado", "Versión instalada: v$current"),
                body
            )
        )
    }

    private fun showUpdateSheet(
        activity: Activity,
        info: ReleaseInfo,
        manual: Boolean,
        onStatusChanged: (() -> Unit)?
    ) {
        val p = ThemeManager.palette(activity)
        val current = currentVersion(activity)
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)

        val notesCard = NexoUi.card(activity).apply {
            val cleanNotes = info.notes
                .replace("\r\n", "\n")
                .lineSequence()
                .map { it.trim().removePrefix("### ").removePrefix("## ").removePrefix("# ") }
                .filter { it.isNotBlank() }
                .take(10)
                .joinToString("\n")
                .ifBlank { "Incluye mejoras de interfaz, rendimiento, privacidad y estabilidad." }
            addView(TextView(activity).apply {
                text = cleanNotes
                textSize = 13.5f
                setLineSpacing(0f, 1.18f)
                setTextColor(p.text)
            })
            info.apkAsset?.let { asset ->
                val sizeLabel = if (asset.sizeBytes > 0) " · " + Formatter.formatShortFileSize(activity, asset.sizeBytes) else ""
                addView(NexoUi.note(activity, "Paquete: ${asset.name} (${asset.abi}$sizeLabel)"))
            }
        }
        body.addView(notesCard)

        val asset = info.apkAsset
        if (asset != null) {
            body.addView(NexoUi.button(activity, "Descargar e instalar v${info.version}", primary = true) {
                sheet.dismiss()
                downloadAndInstall(activity, info, asset)
            }, NexoUi.buttonParams(activity, 14f))
        } else {
            body.addView(NexoUi.button(activity, "Descargar desde GitHub", primary = true) {
                sheet.dismiss()
                openReleasesPage(activity, info.htmlUrl)
            }, NexoUi.buttonParams(activity, 14f))
        }

        body.addView(NexoUi.button(activity, "Ver notas en GitHub") {
            sheet.dismiss()
            openReleasesPage(activity, info.htmlUrl)
        }, NexoUi.buttonParams(activity, 8f))

        if (!manual) {
            body.addView(NexoUi.button(activity, "Omitir esta versión") {
                setSkippedVersion(activity, info.version)
                onStatusChanged?.invoke()
                sheet.dismiss()
            }, NexoUi.buttonParams(activity, 8f))
        }

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(
                    activity,
                    "Actualización disponible · v${info.version}",
                    "Instalada: v$current"
                ),
                body
            )
        )
    }

    private fun downloadAndInstall(activity: Activity, info: ReleaseInfo, asset: ReleaseAsset) {
        val p = ThemeManager.palette(activity)
        val app = activity.applicationContext
        val cancelled = AtomicBoolean(false)
        val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>()
        fun cancelTransfer() {
            cancelled.set(true)
            activeCall.get()?.cancel()
        }
        fun <T> get(request: Request, read: (okhttp3.Response) -> T): T {
            val call = http.newCall(request)
            activeCall.set(call)
            if (cancelled.get()) call.cancel()
            return try {
                call.execute().use(read)
            } finally {
                activeCall.compareAndSet(call, null)
            }
        }

        lateinit var progressSheet: Dialog
        val statusText = TextView(activity).apply {
            text = "Preparando descarga segura…"
            textSize = 14f
            setTextColor(p.text)
        }
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
        }
        val detailText = NexoUi.note(activity, asset.name)
        val box = NexoUi.column(activity).apply {
            addView(NexoUi.card(activity).apply {
                addView(statusText)
                addView(progressBar, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    NexoUi.dp(activity, 24f)
                ).apply { topMargin = NexoUi.dp(activity, 8f) })
                addView(detailText)
            })
            addView(NexoUi.button(activity, "Cancelar") {
                cancelTransfer()
                progressSheet.dismiss()
            }, NexoUi.buttonParams(activity, 12f))
        }
        progressSheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Actualizando a Nexo v${info.version}", "Descarga verificada con SHA-256 y firma"),
                box
            )
        ).apply {
            setCanceledOnTouchOutside(false)
            setOnCancelListener { cancelTransfer() }
        }

        io.execute {
            var staged: File? = null
            val result = runCatching {
                val updatesDir = File(app.cacheDir, "updates").apply {
                    mkdirs()
                    val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                    listFiles()?.filter { it.name.startsWith("nexo-update-") && it.lastModified() < cutoff }
                        ?.forEach { it.delete() }
                }
                val expectedSha256 = info.sha256SumsUrl?.let { sumsUrl ->
                    val req = Request.Builder().url(sumsUrl).header("User-Agent", "NexoBrowser").build()
                    get(req) { resp ->
                        if (!resp.isSuccessful) error("No se pudo descargar SHA256SUMS.txt (HTTP ${resp.code})")
                        val bytes = readBoundedBytes(resp.body, MAX_SUMS_BYTES)
                        parseSha256Sums(String(bytes, Charsets.UTF_8))[asset.name]
                            ?: error("SHA256SUMS.txt no contiene el hash de ${asset.name}")
                    }
                }

                val targetFile = File.createTempFile("nexo-update-${info.version}-", ".apk", updatesDir).also { staged = it }
                val digest = MessageDigest.getInstance("SHA-256")
                val req = Request.Builder().url(asset.downloadUrl).header("User-Agent", "NexoBrowser").build()
                get(req) { resp ->
                    if (!resp.isSuccessful) error("Error al descargar APK (HTTP ${resp.code})")
                    val body = resp.body ?: error("Respuesta vacía")
                    val total = body.contentLength().takeIf { it > 0 } ?: asset.sizeBytes
                    require(total <= MAX_APK_BYTES) { "El APK supera el límite de seguridad" }

                    FileOutputStream(targetFile).use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastUiUpdate = 0L
                        while (true) {
                            if (cancelled.get()) error("Descarga cancelada")
                            val read = input.read(buffer)
                            if (read < 0) break
                            downloaded += read
                            if (downloaded > MAX_APK_BYTES) error("El archivo descargado supera el tamaño permitido")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 120L) {
                                lastUiUpdate = now
                                val pct = if (total > 0) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else -1
                                val progressLabel = if (total > 0) {
                                    "${Formatter.formatShortFileSize(app, downloaded)} de ${Formatter.formatShortFileSize(app, total)}"
                                } else Formatter.formatShortFileSize(app, downloaded)
                                main.post {
                                    if (progressSheet.isShowing) {
                                        statusText.text = if (pct >= 0) "Descargando actualización ($pct %)" else "Descargando actualización…"
                                        progressBar.isIndeterminate = pct < 0
                                        if (pct >= 0) progressBar.progress = pct
                                        detailText.text = progressLabel
                                    }
                                }
                            }
                        }
                        out.fd.sync()
                    }
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha256 != null && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    targetFile.delete()
                    throw SecurityException("El hash SHA-256 del APK descargado no coincide con SHA256SUMS.txt.")
                }

                main.post {
                    if (progressSheet.isShowing) {
                        statusText.text = "Verificando firma criptográfica del paquete…"
                        progressBar.isIndeterminate = true
                    }
                }
                if (cancelled.get()) throw java.io.InterruptedIOException("Descarga cancelada")
                verifyApkSignature(app, targetFile)
                targetFile
            }.onFailure { staged?.delete() }

            main.post {
                if (progressSheet.isShowing) runCatching { progressSheet.dismiss() }
                if (cancelled.get() || activity.isFinishing || activity.isDestroyed) {
                    staged?.let { file -> io.execute { file.delete() } }
                    return@post
                }
                result.fold(
                    onSuccess = { apkFile ->
                        promptInstall(activity, apkFile)
                    },
                    onFailure = { err ->
                        AlertDialog.Builder(activity)
                            .setTitle("No se pudo actualizar")
                            .setMessage("${err.message ?: "Error durante la descarga."}\n\nTambién puedes descargar el APK desde la página oficial de GitHub Releases.")
                            .setPositiveButton("Abrir GitHub") { _, _ ->
                                openReleasesPage(activity, info.htmlUrl)
                            }
                            .setNegativeButton("Cerrar", null)
                            .show()
                    }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApkSignature(context: Context, apkFile: File) {
        val pm = context.packageManager
        val archiveInfo = pm.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: run {
            apkFile.delete()
            throw SecurityException("El archivo descargado no es un paquete APK válido.")
        }
        if (archiveInfo.packageName != context.packageName) {
            apkFile.delete()
            throw SecurityException("El paquete del APK (${archiveInfo.packageName}) no coincide con ${context.packageName}.")
        }
        val signers = archiveInfo.signingInfo?.apkContentsSigners
            ?: archiveInfo.signingInfo?.signingCertificateHistory
            ?: emptyArray()
        if (signers.isEmpty()) {
            apkFile.delete()
            throw SecurityException("El APK descargado no contiene certificados de firma.")
        }
        val archiveCertHashes = signers.map { sha256Hex(it.toByteArray()).lowercase(Locale.US) }.toSet()
        val installedHashes = runCatching {
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val instSigners = installed.signingInfo?.apkContentsSigners
                ?: installed.signingInfo?.signingCertificateHistory
                ?: emptyArray()
            instSigners.map { sha256Hex(it.toByteArray()).lowercase(Locale.US) }.toSet()
        }.getOrDefault(emptySet())

        val matchesOfficial = EXPECTED_SIGNING_SHA256 in archiveCertHashes
        val matchesInstalled = installedHashes.isNotEmpty() && archiveCertHashes.intersect(installedHashes).isNotEmpty()
        if (!matchesOfficial && !matchesInstalled) {
            apkFile.delete()
            throw SecurityException("La firma del APK no coincide con el certificado oficial de Nexo.")
        }
    }

    private fun promptInstall(activity: Activity, apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = apkFile
            AlertDialog.Builder(activity)
                .setTitle("Permitir actualización desde Nexo")
                .setMessage("La actualización ya se descargó y su firma oficial fue verificada.\n\nActiva «Confiar en esta fuente» en la siguiente pantalla de Android; al volver a Nexo se abrirá el instalador automáticamente.")
                .setPositiveButton("Abrir permiso") { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    }.onFailure {
                        launchPackageInstaller(activity, apkFile)
                    }
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    pendingInstallFile = null
                }
                .show()
            return
        }
        launchPackageInstaller(activity, apkFile)
    }

    private fun launchPackageInstaller(activity: Activity, apkFile: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.files",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }.onFailure {
            Toast.makeText(
                activity,
                "No se pudo abrir el instalador del sistema.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openReleasesPage(activity: Activity, url: String) {
        val target = if (isAllowedReleaseUrl(url)) url else RELEASES_PAGE_URL
        runCatching {
            activity.startActivity(MainActivity.openUrlIntent(activity, target))
        }
    }
}
