package com.nexo.browser.engine.chromium

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import com.nexo.browser.TabManager
import com.nexo.browser.TransferSchema
import java.io.File
import java.util.concurrent.Executors

/**
 * Paso de Nexo con Gecko a Nexo con Chromium en el mismo paquete.
 *
 * La versión Chromium se instala como actualización de la de Gecko (mismo
 * paquete y misma firma), así que el historial, los accesos rápidos, las
 * pestañas normales, los ajustes y las claves de servicios siguen donde
 * estaban: son datos de Nexo, no del motor. Lo único que vive dentro del
 * perfil de Gecko y se puede trasladar son las cookies de las pestañas
 * normales, para no cerrar las sesiones abiertas; eso hace esta clase, una
 * sola vez, antes de abrir ninguna pestaña.
 *
 * No se trasladan las cookies de pestañas privadas, Burner ni particionadas,
 * ni el almacenamiento de los sitios (localStorage, IndexedDB) ni los datos
 * de extensiones: WebView no permite escribirlos desde fuera.
 *
 * El perfil de Gecko se conserva 30 días por si se vuelve a instalar la
 * versión Gecko; después se borra para liberar espacio.
 */
internal object GeckoProfileImport {
    private const val PREFS = "nexo_engine_migration"
    private const val KEY_DONE_AT = "gecko_import_done_at"
    private const val KEY_IMPORTED = "gecko_cookies_imported"
    private const val KEY_CLEANED = "gecko_profile_removed"
    private const val KEEP_MILLIS = 30L * 24 * 3600 * 1000
    private const val MAX_COOKIES = 20_000

    /** Llamar en el hilo principal al iniciar el motor, antes de restaurar pestañas. */
    fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val doneAt = prefs.getLong(KEY_DONE_AT, 0L)
        if (doneAt == 0L) {
            val home = File(context.filesDir, "mozilla")
            if (!home.isDirectory) {
                markDone(prefs, 0)
                notice(0)
                return
            }
            val imported = try {
                importCookies(context)
            } catch (_: Exception) {
                return
            }
            markDone(prefs, imported)
            notice(imported)
            return
        }
        if (!prefs.getBoolean(KEY_CLEANED, false) && System.currentTimeMillis() - doneAt > KEEP_MILLIS) {
            val app = context.applicationContext
            Executors.newSingleThreadExecutor().execute {
                val ok = runCatching { removeGeckoFiles(app) }.getOrDefault(false)
                if (ok) prefs.edit().putBoolean(KEY_CLEANED, true).apply()
            }
        }
    }

    private fun markDone(prefs: android.content.SharedPreferences, imported: Int) {
        prefs.edit().putLong(KEY_DONE_AT, System.currentTimeMillis()).putInt(KEY_IMPORTED, imported).apply()
    }

    private fun notice(imported: Int) {
        val cookies = when {
            imported > 0 -> " y las sesiones iniciadas en los sitios"
            else -> ""
        }
        val message = "Nexo ahora usa Chromium. Se conservaron tu historial, accesos, pestañas, ajustes$cookies. " +
            "Las extensiones y los datos guardados por los sitios no se pueden trasladar."
        Handler(Looper.getMainLooper()).postDelayed({ TabManager.notifyMessage(message) }, 1500)
    }

    /** Copia cookies.sqlite (con su WAL) y pasa al perfil normal de WebView las cookies vigentes. */
    private fun importCookies(context: Context): Int {
        val profile = TransferSchema.profile(context.filesDir)
        val source = File(profile, "cookies.sqlite")
        if (!source.isFile) return 0
        val work = File(context.noBackupFilesDir, "gecko-cookies-import").apply { deleteRecursively(); mkdirs() }
        try {
            val copy = File(work, "cookies.sqlite")
            source.copyTo(copy)
            File(profile, "cookies.sqlite-wal").takeIf { it.isFile }?.copyTo(File(work, "cookies.sqlite-wal"))
            val rows = readCookies(copy)
            val manager = CookieManager.getInstance()
            val now = System.currentTimeMillis()
            var count = 0
            for (row in rows) {
                val (url, header) = row.toSetCookie(now) ?: continue
                manager.setCookie(url, header)
                count++
            }
            manager.flush()
            return count
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readCookies(file: File): List<GeckoCookie> {
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
                        add(GeckoCookie(
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

    /** Perfil de Gecko (y lo que dejó en caché) tras el periodo de gracia. */
    private fun removeGeckoFiles(context: Context): Boolean {
        val home = File(context.filesDir, "mozilla")
        if (java.nio.file.Files.isSymbolicLink(home.toPath())) return false
        val ok = !home.exists() || home.deleteRecursively()
        File(context.filesDir, "transferred-addons").takeIf { it.isDirectory }?.deleteRecursively()
        return ok
    }
}
