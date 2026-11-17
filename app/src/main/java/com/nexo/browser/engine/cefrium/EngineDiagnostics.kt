package com.nexo.browser.engine.cefrium

import android.content.Context
import android.os.Build
import android.util.Log
import com.cefrium.Cefrium
import com.cefrium.CefriumBrowser
import com.nexo.browser.LiveCaptions
import com.nexo.browser.PageBridge
import com.nexo.browser.TabManager
import com.nexo.browser.shield.PhantomShield
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico del motor dentro de la app (Ajustes › toca la versión): los
 * últimos eventos de Chromium y el estado de la pestaña activa, para copiar y
 * enviar sin `adb`. Nunca guarda URLs completas: solo hosts.
 */
object EngineDiagnostics {
    private const val MAX_EVENTS = 150
    private val events = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile internal var initialized: Boolean? = null
    @Volatile internal var initError: String? = null

    /** Evento del motor: al registro de Android (NexoEngine) y al diagnóstico de la app. */
    fun log(message: String, error: Throwable? = null) {
        if (error != null) Log.w(CefriumEngine.TAG, message, error) else Log.i(CefriumEngine.TAG, message)
        val line = time.format(Date()) + "  " + message + (error?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        synchronized(events) {
            events.addLast(line.take(400))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun report(context: Context): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("Nexo $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}")
        appendLine("Cefrium ${runCatching { Cefrium.getVersion() }.getOrDefault("?")} · Chromium ${runCatching { Cefrium.getChromiumVersion() }.getOrDefault("?")}")
        appendLine("Motor iniciado: ${initialized ?: "aún no"}" + (initError?.let { " ($it)" } ?: ""))
        appendLine("Navegadores vivos: ${runCatching { CefriumBrowser.liveBrowserCount() }.getOrDefault(-1)} · " +
            "procesos de página: ${runCatching { CefriumBrowser.activeRenderProcessCount() }.getOrDefault(-1)}")
        appendLine("Activity registrada: ${CefriumEngine.activity() != null}")

        val session = TabManager.activeSession() as? CefriumEngineSession
        if (session == null) {
            appendLine("Pestaña activa: ninguna con motor")
        } else {
            val host = TabManager.activeTab()?.url?.let { com.nexo.browser.shield.UrlParts.host(it) } ?: "(sin host)"
            appendLine("Pestaña activa: $host · navegador ${if (session.browser != null) "creado" else "SIN CREAR"} · " +
                "privada=${session.params.isPrivate} · burner=${session.params.contextId != null}")
            appendLine("Puente de páginas: canal ${if (session.shield.channel.connected) "conectado" else "sin conectar"} · " +
                "puente de Nexo ${if (PageBridge.hasMediaBridge(session)) "conectado" else "sin conectar"}")
            appendLine("Subtítulos: ${LiveCaptions.state(session)}" + (LiveCaptions.message(session)?.let { " · $it" } ?: ""))
            LiveCaptions.stages(session).forEach { (stage, ok) ->
                appendLine("  ${if (ok == true) "✓" else if (ok == false) "✗" else "·"} $stage")
            }
        }
        appendLine("PhantomShield: ${PhantomShield.diagnostics()}")
        appendLine()
        appendLine("Últimos eventos:")
        val lines = synchronized(events) { events.toList() }
        if (lines.isEmpty()) appendLine("(ninguno)") else lines.forEach { appendLine(it) }
    }
}
