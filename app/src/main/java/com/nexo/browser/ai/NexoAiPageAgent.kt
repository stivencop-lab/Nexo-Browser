package com.nexo.browser.ai

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.TabManager
import com.nexo.browser.Toastish
import com.nexo.browser.UrlSafety
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agente de página de Nexo AI: extracción segura de contexto de la pestaña activa
 * (excluyendo contraseñas, tarjetas, cookies, elementos ocultos y campos sensibles)
 * mediante retorno directo de `evaluateJavaScript` (sin exponer tokens en `cefriumQuery`),
 * vinculación estricta a `(tabId, url, documentGeneration)` y acciones asistidas con
 * confirmación explícita obligatoria antes de cualquier efecto secundario.
 */
object NexoAiPageAgent {
    private const val AGENT_ASSET = "nexo/ai-page-agent.js"
    private const val SNAPSHOT_TIMEOUT_MS = 2500L
    private const val MAX_LIVE_COMPARE_TABS = 4

    data class PageSnapshot(
        val title: String,
        val url: String,
        val selection: String,
        val headings: List<String>,
        val links: List<Pair<String, String>>,
        val tables: List<List<List<String>>>,
        val forms: List<FormField>,
        val text: String,
        val binding: NexoAiModels.PageBinding? = null
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
    @Volatile private var agentScript: String? = null

    private fun loadAgentScript(context: Context): String? {
        return agentScript ?: runCatching {
            context.applicationContext.assets.open(AGENT_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.also { agentScript = it }
    }

    /** Captura el vínculo actual `(tabId, url, host, title, documentGeneration)` de la pestaña activa. */
    fun currentActiveBinding(): NexoAiModels.PageBinding? {
        val tab = TabManager.activeTab() ?: return null
        val session = tab.session as? CefriumEngineSession
        val host = runCatching { android.net.Uri.parse(tab.url).host }
            .getOrNull()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return NexoAiModels.PageBinding(
            tabId = tab.id,
            url = tab.url,
            host = host,
            title = tab.title,
            documentGeneration = session?.documentGeneration() ?: 0L
        )
    }

    /** Comprueba si [binding] sigue correspondiendo exactamente al documento activo actual (puro, probado en JVM). */
    fun isBindingMatchingCurrent(
        binding: NexoAiModels.PageBinding?,
        current: NexoAiModels.PageBinding?
    ): Boolean {
        if (binding == null) return true
        if (current == null) return false
        return binding.tabId == current.tabId &&
            binding.url == current.url &&
            binding.documentGeneration == current.documentGeneration
    }

    /**
     * Desenvuelve la respuesta JSON de `evaluateJavaScript`, soportando tanto un objeto
     * JSON directo como una cadena JSON escapada.
     */
    internal fun unwrapJsonObject(raw: String?): JSONObject? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        return runCatching {
            when (val parsed = JSONTokener(trimmed).nextValue()) {
                is JSONObject -> parsed
                is String -> {
                    val inner = parsed.trim()
                    if (inner.startsWith("{")) JSONObject(inner) else null
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun buildExtractScript(source: String, boundedChars: Int): String = """
        (function(){
          try {
            $source
            var snap = window.__nexoAiPageAgent
              ? window.__nexoAiPageAgent.extractSafeSnapshot(document, window, $boundedChars)
              : null;
            return snap ? JSON.stringify(snap) : null;
          } catch (_) {
            return null;
          }
        })();
    """.trimIndent()

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
        val activeHost = com.nexo.browser.SiteControlModel.hostOf(tab.url)
        if (activeHost.isNotEmpty() && !NexoAiConfig.isHostAllowed(context, activeHost)) {
            onDone(null, "Nexo AI está desactivado para $activeHost (puedes habilitarlo en Nexo Site Control).")
            return
        }
        val source = loadAgentScript(context)
        if (source == null) {
            onDone(null, "No se pudo cargar el extractor de contexto.")
            return
        }

        val startDocGen = session.documentGeneration()
        val binding = NexoAiModels.PageBinding(
            tabId = tab.id,
            url = tab.url,
            host = activeHost,
            title = tab.title,
            documentGeneration = startDocGen
        )
        val settled = AtomicBoolean(false)
        val timeout = Runnable {
            if (settled.compareAndSet(false, true)) {
                onDone(
                    PageSnapshot(tab.title, tab.url, "", emptyList(), emptyList(), emptyList(), emptyList(), "", binding),
                    null
                )
            }
        }
        main.postDelayed(timeout, SNAPSHOT_TIMEOUT_MS)

        val bounded = NexoAiConfig.clampContextChars(maxChars)
        val script = buildExtractScript(source, bounded)
        session.evaluateJsonInMainFrame(script) { raw ->
            main.post {
                if (!settled.compareAndSet(false, true)) return@post
                main.removeCallbacks(timeout)
                if (session.documentGeneration() != startDocGen) {
                    onDone(null, "La página cambió durante la extracción de contexto. Inténtalo de nuevo.")
                    return@post
                }
                val snap = (parseSnapshot(unwrapJsonObject(raw))
                    ?: PageSnapshot(tab.title, tab.url, "", emptyList(), emptyList(), emptyList(), emptyList(), ""))
                    .copy(binding = binding)
                onDone(snap, null)
            }
        }
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

    /** Resume títulos y hosts de pestañas abiertas no privadas (respaldo rápido síncrono). */
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

    /**
     * Captura contexto real saneado de hasta [MAX_LIVE_COMPARE_TABS] pestañas vivas dentro
     * del presupuesto total de caracteres, y añade título+host del resto de pestañas abiertas.
     */
    fun captureOpenTabsContext(
        context: Context,
        maxChars: Int,
        allowPrivate: Boolean,
        onDone: (String) -> Unit
    ) {
        val tabs = TabManager.allTabs()
            .filter { !BrowserPrefs.isNexoHome(it.url) && (allowPrivate || !it.isPrivate) }
            .take(12)
        if (tabs.isEmpty()) {
            onDone("No hay pestañas web abiertas para comparar.")
            return
        }
        val source = loadAgentScript(context)
        val activeId = TabManager.activeTab()?.id
        val ordered = tabs.sortedByDescending { it.id == activeId }
        val liveTargets = ordered
            .mapNotNull { t -> (t.session as? CefriumEngineSession)?.let { s -> t to s } }
            .take(MAX_LIVE_COMPARE_TABS)
        if (source == null || liveTargets.isEmpty()) {
            onDone(buildOpenTabsContext(allowPrivate))
            return
        }

        val totalBudget = NexoAiConfig.clampContextChars(maxChars)
        val perTabBudget = (totalBudget / liveTargets.size).coerceIn(1000, 2200)
        val script = buildExtractScript(source, perTabBudget)
        val snapshots = arrayOfNulls<PageSnapshot>(liveTargets.size)
        val remaining = AtomicInteger(liveTargets.size)
        val settled = AtomicBoolean(false)

        fun finish() {
            if (!settled.compareAndSet(false, true)) return
            val liveMap = HashMap<String, PageSnapshot>()
            liveTargets.forEachIndexed { idx, (tab, _) ->
                snapshots[idx]?.let { liveMap[tab.id] = it }
            }
            val result = buildString {
                appendLine("Comparación de pestañas abiertas (${ordered.size}; extracto saneado de ${liveMap.size}):")
                ordered.forEachIndexed { idx, tab ->
                    val host = runCatching { android.net.Uri.parse(tab.url).host }.getOrNull().orEmpty()
                    val title = tab.title.ifBlank { host }
                    appendLine("\n--- Pestaña ${idx + 1}: $title ($host) ---")
                    val snap = liveMap[tab.id]
                    if (snap != null) {
                        if (snap.headings.isNotEmpty()) {
                            appendLine("Encabezados: ${snap.headings.take(5).joinToString(" | ")}")
                        }
                        if (snap.text.isNotBlank()) {
                            appendLine(snap.text.take(perTabBudget - 300))
                        }
                    } else {
                        appendLine("(solo metadatos disponibles: pestaña inactiva o en espera)")
                    }
                }
            }.take(totalBudget)
            main.post { onDone(result) }
        }

        val timeout = Runnable { finish() }
        main.postDelayed(timeout, SNAPSHOT_TIMEOUT_MS)

        liveTargets.forEachIndexed { idx, (_, session) ->
            val startGen = session.documentGeneration()
            session.evaluateJsonInMainFrame(script) { raw ->
                if (session.documentGeneration() == startGen) {
                    snapshots[idx] = parseSnapshot(unwrapJsonObject(raw))
                }
                if (remaining.decrementAndGet() == 0) {
                    main.removeCallbacks(timeout)
                    finish()
                }
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
     * Si la acción depende del DOM (`highlight`, `fill_draft`) y el usuario ya cambió de pestaña,
     * URL o documento respecto a [expectedBinding], se bloquea por seguridad sin ejecutarse sobre otra página.
     */
    fun confirmAndExecuteAction(
        activity: Activity,
        action: NexoAiClient.ProposedAction,
        expectedBinding: NexoAiModels.PageBinding? = null
    ) {
        val currentBinding = currentActiveBinding()
        val tab = TabManager.activeTab()
        val host = currentBinding?.host.orEmpty()

        val requiresSamePage = action.type == "highlight" || action.type == "fill_draft"
        if (requiresSamePage && expectedBinding != null && !isBindingMatchingCurrent(expectedBinding, currentBinding)) {
            val orig = expectedBinding.host.ifBlank { expectedBinding.title.ifBlank { "la página original" } }
            Toastish.show(activity, "Acción bloqueada: pertenece a $orig y ya navegaste a otra página.")
            NexoAiConfig.appendLog(activity, action.type, host, "bloqueado_cambio_pagina")
            return
        }

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
        lateinit var confirmDialog: Dialog
        val body = NexoUi.column(activity)
        body.addView(NexoUi.note(activity, message))
        body.addView(NexoUi.button(activity, "Confirmar y ejecutar", primary = true) {
            confirmDialog.dismiss()
            if (requiresSamePage && expectedBinding != null && !isBindingMatchingCurrent(expectedBinding, currentActiveBinding())) {
                Toastish.show(activity, "La página cambió antes de ejecutar la acción; cancelada por seguridad.")
                NexoAiConfig.appendLog(activity, action.type, host, "bloqueado_cambio_pagina")
                return@button
            }
            val activeTab = TabManager.activeTab()
            when (action.type) {
                "open_link" -> {
                    if (UrlSafety.isWebUrl(action.target)) {
                        TabManager.createTab(action.target, isPrivate = activeTab?.isPrivate == true, activate = true)
                        NexoAiConfig.appendLog(activity, "open_link", host, "ejecutado")
                    } else {
                        Toastish.show(activity, "Enlace bloqueado por seguridad.")
                    }
                }
                "fill_draft" -> {
                    val session = activeTab?.session as? CefriumEngineSession
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
        }, NexoUi.buttonParams(activity, 12f))
        body.addView(NexoUi.button(activity, "Cancelar", primary = false) {
            confirmDialog.dismiss()
            NexoAiConfig.appendLog(activity, action.type, host, "cancelado")
        }, NexoUi.buttonParams(activity, 6f))
        confirmDialog = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(activity, "Confirmar acción de Nexo AI", expectedBinding?.host?.takeIf { it.isNotBlank() }),
                body
            )
        )
    }
}
