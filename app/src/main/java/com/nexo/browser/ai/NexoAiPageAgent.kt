package com.nexo.browser.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.TabManager
import com.nexo.browser.Toastish
import com.nexo.browser.UrlSafety
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Agente de página de Nexo AI: extracción segura de contexto de la pestaña activa
 * (excluyendo contraseñas, tarjetas, cookies y campos ocultos) y acciones asistidas
 * con confirmación explícita obligatoria antes de cualquier efecto secundario.
 */
object NexoAiPageAgent {
    private const val AGENT_ASSET = "nexo/ai-page-agent.js"

    data class PageSnapshot(
        val title: String,
        val url: String,
        val selection: String,
        val headings: List<String>,
        val links: List<Pair<String, String>>,
        val tables: List<List<List<String>>>,
        val forms: List<FormField>,
        val text: String
    ) {
        data class FormField(val name: String, val type: String, val placeholder: String)

        fun toContextText(scope: String): String = buildString {
            appendLine("Título: $title")
            if (url.isNotBlank()) appendLine("URL saneada: $url")
            if (scope == "selection") {
                appendLine("Texto seleccionado:")
                appendLine(selection.ifBlank { "(sin selección activa)" })
                return@buildString
            }
            if (selection.isNotBlank()) {
                appendLine("Selección activa: $selection")
            }
            if (headings.isNotEmpty()) {
                appendLine("Encabezados: ${headings.joinToString(" | ")}")
            }
            if (tables.isNotEmpty()) {
                appendLine("Tablas detectadas (${tables.size}):")
                tables.forEachIndexed { idx, rows ->
                    appendLine("Tabla ${idx + 1}:")
                    rows.forEach { row -> appendLine("  " + row.joinToString(" | ")) }
                }
            }
            if (forms.isNotEmpty()) {
                appendLine("Campos de formulario no sensibles (${forms.size}):")
                forms.forEach { f -> appendLine("  - ${f.name.ifBlank { "(sin nombre)" }} [${f.type}] ${f.placeholder}") }
            }
            if (links.isNotEmpty()) {
                appendLine("Enlaces destacados:")
                links.take(12).forEach { (label, href) -> appendLine("  - $label -> $href") }
            }
            if (text.isNotBlank()) {
                appendLine("Contenido visible:")
                append(text)
            }
        }
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pendingSnapshots = ConcurrentHashMap<String, Pair<CefriumEngineSession, (PageSnapshot?) -> Unit>>()
    @Volatile private var agentScript: String? = null

    private fun loadAgentScript(context: Context): String? {
        return agentScript ?: runCatching {
            context.applicationContext.assets.open(AGENT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.also { agentScript = it }
    }

    /** Captura una instantánea segura de la pestaña activa (o null si es inicio/privada bloqueada). */
    fun captureActiveSnapshot(
        context: Context,
        maxChars: Int,
        allowPrivate: Boolean,
        onDone: (PageSnapshot?, String?) -> Unit
    ) {
        val tab = TabManager.activeTab()
        val session = tab?.session as? CefriumEngineSession
        if (tab == null || session == null || BrowserPrefs.isNexoHome(tab.url)) {
            onDone(null, "Abre una página web para analizar su contenido.")
            return
        }
        if (tab.isPrivate && !allowPrivate) {
            onDone(null, "Nexo AI está bloqueado en pestañas privadas por seguridad (puedes cambiarlo en Configuración de Nexo AI).")
            return
        }
        val source = loadAgentScript(context)
        if (source == null) {
            onDone(null, "No se pudo cargar el extractor de contexto.")
            return
        }
        val reqId = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val timeout = Runnable {
            val removed = pendingSnapshots.remove(reqId)
            if (removed != null) {
                onDone(
                    PageSnapshot(tab.title, tab.url, "", emptyList(), emptyList(), emptyList(), emptyList(), ""),
                    null
                )
            }
        }
        pendingSnapshots[reqId] = session to { snap ->
            main.removeCallbacks(timeout)
            onDone(snap, null)
        }
        main.postDelayed(timeout, 2500L)

        val token = JSONObject.quote(session.nxrToken)
        val reqIdJson = JSONObject.quote(reqId)
        val bounded = NexoAiConfig.clampContextChars(maxChars)
        val script = """
            (function(){
              try {
                $source
                var snap = window.__nexoAiPageAgent
                  ? window.__nexoAiPageAgent.extractSafeSnapshot(document, window, $bounded)
                  : null;
                if (typeof window.cefriumQuery === 'function') {
                  window.cefriumQuery({
                    request: JSON.stringify({
                      m: JSON.stringify({
                        id: 1,
                        kind: 'nexo_ai_snapshot',
                        payload: { token: $token, reqId: $reqIdJson, snapshot: snap }
                      }),
                      s: -1
                    }),
                    onSuccess: function(){},
                    onFailure: function(){}
                  });
                }
              } catch (_) {}
            })();
        """.trimIndent()
        session.evaluateInMainFrame(script) {}
    }

    /** Recibe la instantánea enviada por `ai-page-agent.js` tras validar token y marco principal. */
    internal fun onPageSnapshot(session: CefriumEngineSession, isMainFrame: Boolean, payload: JSONObject?): Boolean {
        if (!isMainFrame || payload == null) return false
        if (payload.optString("token") != session.nxrToken) return false
        val reqId = payload.optString("reqId")
        val entry = pendingSnapshots.remove(reqId) ?: return false
        if (entry.first !== session) return false
        val snap = parseSnapshot(payload.optJSONObject("snapshot"))
        main.post { entry.second(snap) }
        return true
    }

    internal fun parseSnapshot(json: JSONObject?): PageSnapshot? {
        if (json == null) return null
        fun stringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }
        val links = mutableListOf<Pair<String, String>>()
        json.optJSONArray("links")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text")
                val href = o.optString("href")
                if (text.isNotBlank() && href.isNotBlank()) links += text to href
            }
        }
        val tables = mutableListOf<List<List<String>>>()
        json.optJSONArray("tables")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tArr = arr.optJSONArray(i) ?: continue
                val rows = mutableListOf<List<String>>()
                for (r in 0 until tArr.length()) {
                    val rArr = tArr.optJSONArray(r) ?: continue
                    rows += (0 until rArr.length()).map { c -> rArr.optString(c) }
                }
                if (rows.isNotEmpty()) tables += rows
            }
        }
        val forms = mutableListOf<PageSnapshot.FormField>()
        json.optJSONArray("forms")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                forms += PageSnapshot.FormField(
                    name = o.optString("name"),
                    type = o.optString("type", "text"),
                    placeholder = o.optString("placeholder")
                )
            }
        }
        return PageSnapshot(
            title = json.optString("title"),
            url = json.optString("url"),
            selection = json.optString("selection"),
            headings = stringList(json.optJSONArray("headings")),
            links = links,
            tables = tables,
            forms = forms,
            text = json.optString("text")
        )
    }

    /** Resume las pestañas abiertas no privadas para el modo «Comparar pestañas». */
    fun buildOpenTabsContext(allowPrivate: Boolean): String {
        val tabs = TabManager.allTabs()
            .filter { !BrowserPrefs.isNexoHome(it.url) && (allowPrivate || !it.isPrivate) }
            .take(12)
        if (tabs.isEmpty()) return "No hay pestañas web abiertas para comparar."
        return buildString {
            appendLine("Pestañas abiertas (${tabs.size}):")
            tabs.forEachIndexed { idx, tab ->
                val host = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull().orEmpty()
                appendLine("${idx + 1}. ${tab.title.ifBlank { host }} — $host")
            }
        }
    }

    /** Acción de solo lectura: resalta texto en la página activa sin modificar datos. */
    fun highlightInActivePage(context: Context, query: String) {
        val session = TabManager.activeTab()?.session as? CefriumEngineSession ?: return
        val source = loadAgentScript(context) ?: return
        val q = JSONObject.quote(query.trim().take(120))
        val script = "(function(){try{$source;if(window.__nexoAiPageAgent)window.__nexoAiPageAgent.highlightText(document,$q);}catch(_){}})();"
        session.evaluateInMainFrame(script) {}
    }

    /**
     * Ejecuta una acción con efecto secundario SOLO tras confirmación explícita del usuario.
     * Nunca envía formularios ni navega automáticamente sin confirmación.
     */
    fun confirmAndExecuteAction(activity: Activity, action: NexoAiClient.ProposedAction) {
        val tab = TabManager.activeTab()
        val host = runCatching { android.net.Uri.parse(tab?.url.orEmpty()).host }.getOrNull().orEmpty()
        if (!action.requiresConfirmation) {
            if (action.type == "highlight") {
                highlightInActivePage(activity, action.target)
                NexoAiConfig.appendLog(activity, "highlight", host, "ok")
                Toastish.show(activity, "Resaltando coincidencia en la página.")
            }
            return
        }

        val message = when (action.type) {
            "open_link" -> "Nexo AI propone abrir este enlace en una pestaña nueva:\n\n${action.target}"
            "fill_draft" -> "Nexo AI propone rellenar el campo «${action.target}» con este borrador (NO enviará el formulario):\n\n${action.value.take(300)}"
            else -> "Confirmar acción asistida: ${action.label}"
        }
        AlertDialog.Builder(activity)
            .setTitle("Confirmar acción de Nexo AI")
            .setMessage(message)
            .setNegativeButton("Cancelar") { _, _ ->
                NexoAiConfig.appendLog(activity, action.type, host, "cancelado")
            }
            .setPositiveButton("Confirmar y ejecutar") { _, _ ->
                when (action.type) {
                    "open_link" -> {
                        if (UrlSafety.isWebUrl(action.target)) {
                            TabManager.createTab(action.target, isPrivate = tab?.isPrivate == true, activate = true)
                            NexoAiConfig.appendLog(activity, "open_link", host, "ejecutado")
                        } else {
                            Toastish.show(activity, "Enlace bloqueado por seguridad.")
                        }
                    }
                    "fill_draft" -> {
                        val session = tab?.session as? CefriumEngineSession
                        val source = loadAgentScript(activity)
                        if (session != null && source != null) {
                            val f = JSONObject.quote(action.target)
                            val v = JSONObject.quote(action.value.take(2000))
                            val js = "(function(){try{$source;if(window.__nexoAiPageAgent)window.__nexoAiPageAgent.fillDraftField(document,$f,$v);}catch(_){}})();"
                            session.evaluateInMainFrame(js) {}
                            NexoAiConfig.appendLog(activity, "fill_draft:${action.target}", host, "ejecutado")
                            Toastish.show(activity, "Borrador insertado en «${action.target}».")
                        }
                    }
                }
            }
            .show()
    }
}
