package com.nexo.browser

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.StrictMode
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class NexoApplication : Application() {
    /*
     * Los ContentProvider de las librerías (ML Kit, AndroidX Startup, Cefrium)
     * se crean ANTES de onCreate. Instalar aquí el registro de cierres hace
     * que un fallo en ese arranque también quede guardado y se muestre.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        if (isMainProcess()) CrashLog.install(this)
    }

    /**
     * Chromium dibuja las páginas en procesos aislados (renderizadores) y usa
     * otros para GPU y red; Android crea esta Application también en ellos.
     * Un proceso aislado no puede leer los archivos de la app: Nexo solo se
     * inicia en su proceso principal y deja los demás a Chromium.
     */
    private fun isMainProcess(): Boolean = Application.getProcessName() == packageName

    override fun onCreate() {
        super.onCreate()
        // Permitir AudioPlaybackCapture también desde procesos hijos de audio de Chromium (Android 10+).
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                getSystemService(AudioManager::class.java)
                    ?.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            }
        }
        if (!isMainProcess()) return
        AppContext.initialize(this)
        GlassPreferences.initialize(this)
        CrashLog.install(this)
        MigrationMode.initialize(this)
        if (!MigrationMode.cold) BrowserPrefs.purgeLegacyKeys(this)
        // Notificaciones que sobrevivieron a un proceso anterior (cierre forzado, fallo).
        MediaPlaybackService.clearStale(this)
        DownloadStore.clearStaleProgress(this)

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyDeathOnNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isMainProcess()) return
        if (level >= 10) com.nexo.browser.engine.cefrium.SpareRendererPool.clear()
        TabManager.onTrimMemory(level)
        SiteIconStore.trimMemory(level)
        RemoteImageLoader.trimMemory(level)
    }
}

object CrashLog {
    private const val PREFS = "nexo_crash_log"
    private const val KEY_LAST = "last_crash"
    private const val MAX_REPORT_CHARS = 12000
    private const val MAX_STACK_LINES = 90
    private var installed = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true

        // En attachBaseContext applicationContext todavía es null.
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))

                val raw = buildString {
                    appendLine("Versión: ${appVersion(app)}")
                    appendLine("Hilo: ${thread.name.take(80)}")
                    appendLine("Tipo: ${error.javaClass.name}")
                    appendLine("Mensaje: ${error.message ?: "(sin mensaje)"}")
                    appendLine()
                    append(
                        writer.toString()
                            .lineSequence()
                            .take(MAX_STACK_LINES)
                            .joinToString("\n")
                    )
                }

                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(
                        KEY_LAST,
                        redactCrashReport(raw).take(MAX_REPORT_CHARS)
                    )
                    .commit()
            }

            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                exitProcess(10)
            }
        }
    }

    private fun redactCrashReport(raw: String): String = CrashReportRedactor.redact(raw)

    fun appVersion(context: Context): String =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
        }.getOrDefault("?")

    fun consume(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_LAST, null)
        if (value != null) prefs.edit().remove(KEY_LAST).apply()
        return value
    }

    /** true si el informe es de la versión instalada ahora (y no de una anterior). */
    fun isFromCurrentVersion(context: Context, report: String): Boolean =
        report.lineSequence().firstOrNull() == "Versión: ${appVersion(context)}"
}
