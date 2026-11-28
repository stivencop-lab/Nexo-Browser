package com.nexo.browser

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.Executors

/**
 * Pestañas guardadas en su propio archivo (escritura atómica en segundo plano).
 *
 * Antes iban dentro de las preferencias de ajustes: cada guardado reescribía
 * el XML completo con el historial de todas las pestañas y Android bloquea
 * el hilo principal esperando esas escrituras al pausar la app.
 */
object TabStore {
    private const val FILE = "tabs.json"

    private val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "NexoTabs").apply { isDaemon = true }
    }

    @Volatile private var lastWritten: String? = null

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

    /** Lectura síncrona al iniciar; migra el formato antiguo si existe. */
    fun read(context: Context): String? {
        val stored = runCatching {
            String(file(context).readFully(), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (stored != null) {
            lastWritten = stored
            return stored
        }

        val legacy = BrowserPrefs.tabsJson(context) ?: return null
        writeNow(context.applicationContext, legacy)
        val migrated = runCatching { String(file(context).readFully(), Charsets.UTF_8) == legacy }.getOrDefault(false)
        if (migrated) BrowserPrefs.clearTabsJson(context) else lastWritten = null
        return legacy
    }

    fun flush() { io.submit {}.get() }

    /** [serialize] se ejecuta fuera del hilo principal. */
    fun write(context: Context, serialize: () -> String): java.util.concurrent.Future<Boolean> {
        val app = context.applicationContext
        return io.submit<Boolean> {
            val json = runCatching(serialize).getOrNull() ?: return@submit false
            json == lastWritten || writeNow(app, json)
        }
    }

    @Synchronized
    private fun writeNow(context: Context, json: String): Boolean {
        val target = file(context)
        val stream = runCatching { target.startWrite() }.getOrNull() ?: return false
        return runCatching {
            stream.write(json.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
            lastWritten = json
        }.onFailure { target.failWrite(stream) }.isSuccess
    }
}
