package com.nexo.browser.engine.cefrium

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import com.cefrium.CefriumProfile
import com.nexo.browser.TabManager
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Paso de Nexo con Gecko a Nexo con Chromium en el mismo paquete.
 *
 * Nexo con Chromium se instala como actualización de la de Gecko (mismo
 * paquete y misma firma), así que el historial, los accesos rápidos, las
 * pestañas normales, los ajustes y las claves de servicios siguen donde
 * estaban: son datos de Nexo, no del motor. Lo único que vive dentro del
 * perfil de Gecko y se puede trasladar son las cookies de las pestañas
 * normales, para no cerrar las sesiones abiertas; eso hace esta clase, una
 * sola vez, antes de abrir ninguna pestaña.
 *
 * No se trasladan las cookies de pestañas privadas, Burner ni particionadas,
 * ni el almacenamiento de los sitios (localStorage, IndexedDB) ni los datos
 * de extensiones: Chromium no permite escribirlos desde fuera.
 *
 * El perfil de Gecko se conserva 30 días por si se vuelve a instalar la
 * versión Gecko; después se borra para liberar espacio.
 */
internal object UpgradeImport {
    private const val PREFS = "nexo_engine_migration"
    private const val KEY_DONE_AT = "gecko_import_done_at"
    private const val KEY_IMPORTED = "gecko_cookies_imported"
    private const val KEY_CLEANED = "gecko_profile_removed"
    private const val KEEP_MILLIS = 30L * 24 * 3600 * 1000
    private const val MAX_COOKIES = 20_000

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { Thread(it, "NexoUpgradeImport").apply { isDaemon = true } }
    var pending = false
        private set

    /** Las pestañas normales esperan el lote sin bloquear la interfaz. */
    fun runOnce(context: Context, onReady: () -> Unit) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val doneAt = prefs.getLong(KEY_DONE_AT, 0L)
        if (doneAt == 0L) {
            pending = true
            io.execute {
                runCatching {
                    val legacy = File(app.filesDir, "mozilla").isDirectory
                    val imported = if (legacy) importCookies(app) else 0
                    check(prefs.edit().putLong(KEY_DONE_AT, System.currentTimeMillis())
                        .putInt(KEY_IMPORTED, imported).commit()) { "No se pudo confirmar la migración" }
                    if (legacy) notice(imported)
                }.onFailure { error ->
                    EngineDiagnostics.log("No se pudo completar la importación de cookies de Gecko", error)
                    main.post { TabManager.notifyMessage("No se pudo completar la importación de sesiones. Se volverá a intentar al reiniciar.") }
                }
                main.post {
                    pending = false
                    onReady()
                }
            }
        } else if (!prefs.getBoolean(KEY_CLEANED, false) && System.currentTimeMillis() - doneAt > KEEP_MILLIS) {
            io.execute {
                val ok = runCatching { removeLegacyProfile(app) }.getOrDefault(false)
                if (ok) prefs.edit().putBoolean(KEY_CLEANED, true).apply()
            }
        }
    }

    private fun notice(imported: Int) {
        val cookies = when {
            imported > 0 -> " y las sesiones iniciadas en los sitios"
            else -> ""
        }
        val message = "Nexo ahora usa Chromium. Se conservaron tu historial, accesos, pestañas, ajustes$cookies. " +
            "Las extensiones y los datos guardados por los sitios no se pueden trasladar."
        main.postDelayed({ TabManager.notifyMessage(message) }, 1500)
    }

    /** Copia cookies.sqlite (con su WAL) y pasa al perfil normal de Chromium las cookies vigentes. */
    private fun importCookies(context: Context): Int {
        val profile = legacyProfile(context.filesDir)
        val source = File(profile, "cookies.sqlite")
        if (!source.isFile) return 0
        val work = File(context.noBackupFilesDir, "gecko-cookies-import").apply { deleteRecursively(); mkdirs() }
        try {
            val copy = File(work, "cookies.sqlite")
            source.copyTo(copy)
            File(profile, "cookies.sqlite-wal").takeIf { it.isFile }?.copyTo(File(work, "cookies.sqlite-wal"))
            val now = System.currentTimeMillis()
            val cookies = readCookies(copy).mapNotNull { it.toSetCookie(now) }
            if (cookies.isEmpty()) return 0
            val manager = CefriumProfile.getDefault().cookieManager
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            var imported = 0
            for (batch in cookies.chunked(128)) {
                val results = batch.map { (url, header) ->
                    CompletableFuture<Boolean>().also { result ->
                        manager.setCookie(url, header) { result.complete(it == true) }
                    }
                }
                CompletableFuture.allOf(*results.toTypedArray())
                    .get((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                imported += results.count { it.join() }
            }
            val flushed = CompletableFuture<Unit>()
            manager.flush { flushed.complete(Unit) }
            flushed.get(5, TimeUnit.SECONDS)
            check(imported == cookies.size) { "Chromium rechazó ${cookies.size - imported} cookies" }
            return imported
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readCookies(file: File): List<ImportedCookie> {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        return db.use {
            it.rawQuery("SELECT * FROM moz_cookies LIMIT $MAX_COOKIES", null).use { cursor ->
                fun index(name: String) = cursor.getColumnIndex(name)
                val name = index("name"); val value = index("value"); val host = index("host")
                val path = index("path"); val expiry = index("expiry"); val secure = index("isSecure")
                val httpOnly = index("isHttpOnly"); val sameSite = index("sameSite")
                val scheme = index("schemeMap"); val origin = index("originAttributes")
                if (listOf(name, value, host, path, expiry).any { c -> c < 0 }) return emptyList()
                buildList {
                    while (cursor.moveToNext()) {
                        add(ImportedCookie(
                            name = cursor.getString(name).orEmpty(),
                            value = cursor.getString(value).orEmpty(),
                            host = cursor.getString(host).orEmpty(),
                            path = cursor.getString(path).orEmpty(),
                            expiry = cursor.getLong(expiry),
                            isSecure = secure >= 0 && cursor.getInt(secure) != 0,
                            isHttpOnly = httpOnly >= 0 && cursor.getInt(httpOnly) != 0,
                            sameSite = if (sameSite >= 0) cursor.getInt(sameSite) else -1,
                            schemeMap = if (scheme >= 0) cursor.getInt(scheme) else 0,
                            originAttributes = if (origin >= 0) cursor.getString(origin).orEmpty() else ""
                        ))
                    }
                }
            }
        }
    }

    /**
     * Perfil por defecto que dejó la versión anterior (`files/mozilla/profiles.ini`),
     * sin seguir enlaces ni salir de su carpeta. Solo se lee; nunca se ejecuta nada de él.
     */
    internal fun legacyProfile(filesDir: File): File {
        val home = File(filesDir, "mozilla")
        require(!java.nio.file.Files.isSymbolicLink(home.toPath()))
        val ini = File(home, "profiles.ini")
        require(!java.nio.file.Files.isSymbolicLink(ini.toPath()) && ini.isFile && ini.length() < 65536) { "Sin perfil de la versión anterior" }
        val sections = ini.readText().split(Regex("(?m)^\\["))
            .filter { it.startsWith("Profile") }.map { section ->
                section.lineSequence().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
            }
        val item = sections.singleOrNull { it["Default"] == "1" } ?: sections.singleOrNull()
        require(item != null && item["IsRelative"] == "1") { "Perfil anterior ambiguo o no compatible" }
        val name = requireNotNull(item["Path"])
        require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..")
        val profile = File(home, name)
        require(profile.isDirectory && !java.nio.file.Files.isSymbolicLink(profile.toPath()))
        require(profile.canonicalFile.parentFile == home.canonicalFile)
        return profile
    }

    /** Perfil de Gecko (y lo que dejó en caché) tras el periodo de gracia. */
    private fun removeLegacyProfile(context: Context): Boolean {
        val home = File(context.filesDir, "mozilla")
        if (java.nio.file.Files.isSymbolicLink(home.toPath())) return false
        val ok = !home.exists() || home.deleteRecursively()
        File(context.filesDir, "transferred-addons").takeIf { it.isDirectory }?.deleteRecursively()
        return ok
    }
}
