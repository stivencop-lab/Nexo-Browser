package com.nexo.browser.engine.cefrium

import android.content.Context
import android.os.Build
import android.os.Debug
import android.util.Log
import com.cefrium.Cefrium
import com.cefrium.CefriumBrowser
import com.nexo.browser.CrashReportRedactor
import com.nexo.browser.LiveCaptions
import com.nexo.browser.PageBridge
import com.nexo.browser.TabManager
import com.nexo.browser.TranslatorManager
import com.nexo.browser.extensions.nxr.NxrRuntime
import com.nexo.browser.shield.PhantomShield
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnóstico del motor dentro de la app (Ajustes › toca la versión): los
 * últimos eventos de Chromium, contadores de rendimiento y el estado saneado
 * de los subsistemas para copiar o compartir sin `adb`.
 * Nunca expone claves API, cookies, rutas privadas ni hosts de pestañas privadas.
 */
object EngineDiagnostics {
    private const val MAX_EVENTS = 150
    private val events = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val inlineSecretRegex = Regex(
        """(\bsk-(?:proj-|ant-|live-|test-)?[A-Za-z0-9_-]{12,}\b|\bAIza[0-9A-Za-z_-]{20,}\b|\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b)"""
    )

    @Volatile internal var initialized: Boolean? = null
    @Volatile internal var initError: String? = null
    private val documentLoads = java.util.concurrent.atomic.AtomicLong(0)
    private val rendererCrashes = java.util.concurrent.atomic.AtomicLong(0)
    private val rendererKills = java.util.concurrent.atomic.AtomicLong(0)
    private val surfaceRebinds = java.util.concurrent.atomic.AtomicLong(0)
    private val surfaceRecoveries = java.util.concurrent.atomic.AtomicLong(0)
    private val watchdogRecreations = java.util.concurrent.atomic.AtomicLong(0)
    private val bridgeMessages = java.util.concurrent.atomic.AtomicLong(0)
    private val bridgeRejected = java.util.concurrent.atomic.AtomicLong(0)
    private val totalLoadMs = java.util.concurrent.atomic.AtomicLong(0)
    private val completedLoads = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile internal var lastLoadMs: Long = -1L
        private set

    internal fun recordDocumentLoad() { documentLoads.incrementAndGet() }
    internal fun recordLoadDuration(durationMs: Long) {
        if (durationMs < 0L || durationMs > 300_000L) return
        lastLoadMs = durationMs
        totalLoadMs.addAndGet(durationMs)
        completedLoads.incrementAndGet()
    }
    internal fun averageLoadMs(): Long {
        val count = completedLoads.get()
        return if (count > 0L) totalLoadMs.get() / count else -1L
    }
    internal fun recordRendererExit(isKill: Boolean) {
        if (isKill) rendererKills.incrementAndGet() else rendererCrashes.incrementAndGet()
    }
    internal fun recordSurfaceRebind() { surfaceRebinds.incrementAndGet() }
    internal fun recordSurfaceRecovery() { surfaceRecoveries.incrementAndGet() }
    internal fun recordWatchdogRecreation() { watchdogRecreations.incrementAndGet() }
    internal fun recordBridgeMessage(accepted: Boolean) {
        if (accepted) bridgeMessages.incrementAndGet() else bridgeRejected.incrementAndGet()
    }

    fun countersSummary(): String {
        val avg = averageLoadMs()
        val timing = if (lastLoadMs >= 0L) " · última=${lastLoadMs}ms (media=${avg}ms)" else ""
        return "cargas=${documentLoads.get()}$timing · rebinds=${surfaceRebinds.get()} · " +
            "recoveries=${surfaceRecoveries.get()} (watchdog=${watchdogRecreations.get()}) · " +
            "kills=${rendererKills.get()} · crashes=${rendererCrashes.get()} · " +
            "bridge=${bridgeMessages.get()} (rechazados=${bridgeRejected.get()})"
    }

    /** Memoria aproximada del proceso de la app (NO incluye RSS aislado de renderers Chromium). */
    fun memorySummary(): String {
        val rt = Runtime.getRuntime()
        val jvmUsedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).coerceAtLeast(0L)
        val jvmMaxMb = (rt.maxMemory() / (1024L * 1024L)).coerceAtLeast(1L)
        val nativeMb = runCatching { Debug.getNativeHeapAllocatedSize() / (1024L * 1024L) }.getOrDefault(-1L)
        val nativePart = if (nativeMb >= 0L) " · nativo app=${nativeMb} MB" else ""
        return "JVM=${jvmUsedMb}/${jvmMaxMb} MB$nativePart (no incluye RSS del renderer Chromium)"
    }

    /** Sanea cualquier informe antes de mostrarlo, copiarlo o compartirlo. */
    fun sanitizeReport(raw: String): String {
        val step1 = CrashReportRedactor.redact(raw)
        return inlineSecretRegex.replace(step1, "<secret-redacted>")
    }

    /**
     * Consulta ligera bajo demanda sobre la pestaña activa (solo no privadas) usando
     * `evaluateJavaScript` directo para obtener conteo de nodos DOM y Navigation Timing.
     */
    fun probeActivePageMetrics(session: CefriumEngineSession?, onResult: (String?) -> Unit) {
        if (session == null || session.params.isPrivate) {
            onResult(null)
            return
        }
        val script = """
            (function(){
              try {
                var nav = performance && performance.getEntriesByType ? performance.getEntriesByType('navigation')[0] : null;
                var domNodes = document && document.getElementsByTagName ? document.getElementsByTagName('*').length : -1;
                return JSON.stringify({
                  domNodes: domNodes,
                  domInteractiveMs: nav && isFinite(nav.domInteractive) ? Math.round(nav.domInteractive) : -1,
                  loadEventMs: nav && isFinite(nav.loadEventEnd) ? Math.round(nav.loadEventEnd) : -1
                });
              } catch (_) { return null; }
            })();
        """.trimIndent()
        session.evaluateJsonInMainFrame(script) { raw ->
            val formatted = parsePageMetrics(raw)
            onResult(formatted)
        }
    }

    internal fun parsePageMetrics(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        val json = runCatching {
            when (val parsed = JSONTokener(trimmed).nextValue()) {
                is JSONObject -> parsed
                is String -> if (parsed.trim().startsWith("{")) JSONObject(parsed.trim()) else null
                else -> null
            }
        }.getOrNull() ?: return null
        val domNodes = json.optInt("domNodes", -1)
        val domInteractive = json.optInt("domInteractiveMs", -1)
        val loadEvent = json.optInt("loadEventMs", -1)
        if (domNodes < 0 && domInteractive < 0 && loadEvent < 0) return null
        return "DOM=${if (domNodes >= 0) "$domNodes nodos" else "?"} · " +
            "domInteractive=${if (domInteractive >= 0) "${domInteractive}ms" else "?"} · " +
            "loadEvent=${if (loadEvent >= 0) "${loadEvent}ms" else "?"}"
    }

    /** Evento del motor: al registro de Android (NexoEngine) y al diagnóstico de la app. */
    fun log(message: String, error: Throwable? = null) {
        if (error != null) Log.w(CefriumEngine.TAG, message, error) else Log.i(CefriumEngine.TAG, message)
        val line = time.format(Date()) + "  " + message + (error?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: "")
        synchronized(events) {
            events.addLast(line.take(400))
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
    }

    fun report(context: Context, activePagePerf: String? = null): String {
        val raw = buildString {
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            appendLine("Nexo $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("Cefrium ${runCatching { Cefrium.getVersion() }.getOrDefault("?")} · Chromium ${runCatching { Cefrium.getChromiumVersion() }.getOrDefault("?")}")
            appendLine("Motor iniciado: ${initialized ?: "aún no"}" + (initError?.let { " ($it)" } ?: ""))
            appendLine("Navegadores vivos: ${runCatching { CefriumBrowser.liveBrowserCount() }.getOrDefault(-1)} · " +
                "procesos de página: ${runCatching { CefriumBrowser.activeRenderProcessCount() }.getOrDefault(-1)}")
            appendLine("Contadores: ${countersSummary()}")
            appendLine("Memoria app: ${memorySummary()}")
            appendLine("Activity registrada: ${CefriumEngine.activity() != null}")

            val session = TabManager.activeSession() as? CefriumEngineSession
            if (session == null) {
                appendLine("Pestaña activa: ninguna con motor")
            } else {
                val host = if (session.params.isPrivate) {
                    "(pestaña privada omitida)"
                } else {
                    TabManager.activeTab()?.url?.let { com.nexo.browser.shield.UrlParts.host(it) } ?: "(sin host)"
                }
                appendLine("Pestaña activa: $host · docGen=${session.documentGeneration()} · navegador ${if (session.browser != null) "creado" else "SIN CREAR"} · " +
                    "privada=${session.params.isPrivate} · burner=${session.params.contextId != null}")
                if (!activePagePerf.isNullOrBlank()) {
                    appendLine("Métricas pestaña activa: $activePagePerf")
                }
                appendLine("Puente de páginas: canal ${if (session.shield.channel.connected) "conectado" else "sin conectar"} · " +
                    "puente de Nexo ${if (PageBridge.hasMediaBridge(session)) "conectado" else "sin conectar"}")
                appendLine("Subtítulos: ${LiveCaptions.state(session)}" + (LiveCaptions.message(session)?.let { " · $it" } ?: ""))
                LiveCaptions.stages(session).forEach { (stage, ok) ->
                    appendLine("  ${if (ok == true) "✓" else if (ok == false) "✗" else "·"} $stage")
                }
            }
            appendLine("Subtítulos (global): ${LiveCaptions.diagnosticsSummary()}")
            appendLine("Traductor: ${TranslatorManager.diagnosticsSummary()}")
            appendLine("NXR Extensiones: ${NxrRuntime.diagnosticsSummary()}")
            appendLine("PhantomShield: ${PhantomShield.diagnostics()}")
            val sdkDiag = runCatching { Cefrium.diagnostics(context) }.getOrNull()?.trim()
            if (!sdkDiag.isNullOrEmpty()) {
                appendLine()
                appendLine(sdkDiag)
            }
            appendLine()
            appendLine("Últimos eventos:")
            val lines = synchronized(events) { events.toList() }
            if (lines.isEmpty()) appendLine("(ninguno)") else lines.forEach { appendLine(it) }
        }
        return sanitizeReport(raw)
    }
}
