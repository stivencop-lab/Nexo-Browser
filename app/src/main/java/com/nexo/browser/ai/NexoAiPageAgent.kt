package com.nexo.browser.ai

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nexo.browser.BrowserHosts
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.HistoryStore
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.SearchResolver
import com.nexo.browser.SiteControlModel
import com.nexo.browser.TabManager
import com.nexo.browser.Toastish
import com.nexo.browser.TranslatorManager
import com.nexo.browser.UrlSafety
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import com.nexo.browser.reader.NexoReader
import com.nexo.browser.workspace.BookmarkStore
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Agente de página y navegador de Nexo AI: extracción segura de contexto de la pestaña activa
 * (excluyendo contraseñas, tarjetas, cookies, elementos ocultos y campos sensibles),
 * indexación de elementos interactivos `[e1..e35]`, ejecución de herramientas del navegador
 * para el Modo Agente y acciones con efectos secundarios siempre protegidas con confirmación.
 */
object NexoAiPageAgent {
    private const val AGENT_ASSET = "nexo/ai-page-agent.js"
    private const val SNAPSHOT_TIMEOUT_MS = 2500L
    private const val TOOL_EVAL_TIMEOUT_MS = 3200L
    private const val NAV_SETTLE_MAX_MS = 3800L
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
        val binding: NexoAiModels.PageBinding? = null,
        val interactives: List<InteractiveElement> = emptyList(),
        val scrollPercent: Int = 0
    ) {
        data class FormField(val name: String, val type: String, val placeholder: String)
        data class InteractiveElement(
            val ref: String,
            val tag: String,
            val text: String,
            val href: String = "",
            val fieldName: String = ""
        )

        fun toContextText(scope: String): String = buildString {
            appendLine("Título: $title")
            if (url.isNotBlank()) appendLine("URL saneada: $url")
            if (scope == "agent") {
                appendLine("Posición de scroll: $scrollPercent%")
            }
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
            if (scope == "agent" && interactives.isNotEmpty()) {
                appendLine("Elementos interactivos visibles (${interactives.size}):")
                interactives.take(30).forEach { el ->
                    val extra = when {
                        el.href.isNotBlank() -> " -> ${el.href.take(90)}"
                        el.fieldName.isNotBlank() -> " [campo: ${el.fieldName}]"
                        else -> ""
                    }
                    appendLine("  [${el.ref}] <${el.tag}> ${el.text}$extra")
                }
            }
            if (forms.isNotEmpty()) {
                appendLine("Campos de formulario no sensibles (${forms.size}):")
                forms.forEach { f -> appendLine("  - ${f.name.ifBlank { "(sin nombre)" }} [${f.type}] ${f.placeholder}") }
            }
            if (links.isNotEmpty() && scope != "agent") {
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
        val activeHost = SiteControlModel.hostOf(tab.url)
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
        val interactives = mutableListOf<PageSnapshot.InteractiveElement>()
        json.optJSONArray("interactives")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ref = o.optString("ref")
                val tag = o.optString("tag")
                val text = o.optString("text")
                if (ref.isNotBlank() && tag.isNotBlank()) {
                    interactives += PageSnapshot.InteractiveElement(
                        ref = ref,
                        tag = tag,
                        text = text,
                        href = o.optString("href"),
                        fieldName = o.optString("fieldName")
                    )
                }
            }
        }
        val scrollPercent = json.optJSONObject("scroll")?.optInt("percent", 0)?.coerceIn(0, 100) ?: 0
        return PageSnapshot(
            title = json.optString("title"),
            url = json.optString("url"),
            selection = json.optString("selection"),
            headings = stringList(json.optJSONArray("headings")),
            links = links,
            tables = tables,
            forms = forms,
            text = json.optString("text"),
            interactives = interactives,
            scrollPercent = scrollPercent
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
     * del presupuesto total de caracteres, respetando las exclusiones por sitio de Nexo Site Control.
     */
    fun captureOpenTabsContext(
        context: Context,
        maxChars: Int,
        allowPrivate: Boolean,
        onDone: (String) -> Unit
    ) {
        val tabs = TabManager.allTabs()
            .filter { tab ->
                if (BrowserPrefs.isNexoHome(tab.url)) return@filter false
                if (!allowPrivate && tab.isPrivate) return@filter false
                val host = SiteControlModel.hostOf(tab.url)
                host.isEmpty() || NexoAiConfig.isHostAllowed(context, host)
            }
            .take(12)
        if (tabs.isEmpty()) {
            onDone("No hay pestañas web permitidas abiertas para comparar.")
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

    /**
     * Crea un ejecutor real de herramientas del navegador para el Modo Agente.
     * Solo retiene `applicationContext` (jamás `Activity` ni `Dialog`).
     */
    fun createBrowserToolExecutor(
        context: Context,
        allowPrivate: Boolean,
        maxChars: Int
    ): NexoAiAgent.ToolExecutor {
        val app = context.applicationContext
        val boundedChars = NexoAiConfig.clampContextChars(maxChars).coerceAtMost(7000)

        fun captureSync(): Pair<PageSnapshot?, String?> {
            val latch = CountDownLatch(1)
            val result = AtomicReference<Pair<PageSnapshot?, String?>>(null to "Tiempo de espera agotado.")
            main.post {
                captureActiveSnapshot(app, boundedChars, allowPrivate) { snap, err ->
                    result.set(snap to err)
                    latch.countDown()
                }
            }
            latch.await(SNAPSHOT_TIMEOUT_MS + 600L, TimeUnit.MILLISECONDS)
            return result.get()
        }

        fun waitForPageReady(prevUrl: String?) {
            val deadline = System.currentTimeMillis() + NAV_SETTLE_MAX_MS
            Thread.sleep(350L)
            while (System.currentTimeMillis() < deadline) {
                val tab = TabManager.activeTab()
                if (tab != null && !tab.isLoading && (prevUrl == null || tab.url != prevUrl || System.currentTimeMillis() > deadline - 1600L)) {
                    Thread.sleep(320L)
                    break
                }
                Thread.sleep(160L)
            }
        }

        fun evalOnActivePage(jsBuilder: (String) -> String): JSONObject? {
            val latch = CountDownLatch(1)
            val out = AtomicReference<JSONObject?>(null)
            main.post {
                val tab = TabManager.activeTab()
                val session = tab?.session as? CefriumEngineSession
                val source = loadAgentScript(app)
                val host = SiteControlModel.hostOf(tab?.url.orEmpty())
                if (tab == null || session == null || source == null ||
                    (tab.isPrivate && !allowPrivate) ||
                    (host.isNotEmpty() && !NexoAiConfig.isHostAllowed(app, host))
                ) {
                    latch.countDown()
                    return@post
                }
                session.evaluateJsonInMainFrame(jsBuilder(source)) { raw ->
                    out.set(unwrapJsonObject(raw))
                    latch.countDown()
                }
            }
            latch.await(TOOL_EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return out.get()
        }

        return NexoAiAgent.ToolExecutor { call ->
            if (call.risk == NexoAiAgent.Risk.CONFIRM) {
                val action = NexoAiClient.ProposedAction(
                    type = call.tool,
                    label = call.reason.take(54),
                    target = call.args["target"].orEmpty(),
                    value = call.args["value"].orEmpty()
                )
                return@ToolExecutor NexoAiAgent.ToolOutcome(
                    ok = true,
                    summary = "Requiere confirmación: ${call.reason}",
                    observation = "Esta acción modifica un campo del formulario (${action.target}). " +
                        "Se ha preparado un botón de confirmación para que el usuario la apruebe explícitamente.",
                    pendingAction = action
                )
            }

            when (call.tool) {
                "observe_page" -> {
                    val (snap, err) = captureSync()
                    if (snap != null) {
                        NexoAiAgent.ToolOutcome(true, "Inspeccionado: ${snap.title.ifBlank { snap.url }.take(48)}", snap.toContextText("agent"))
                    } else {
                        NexoAiAgent.ToolOutcome(false, err ?: "No se pudo leer la página", err ?: "Sin acceso a la página.")
                    }
                }

                "scroll_page" -> {
                    val dir = call.args["direction"] ?: "down"
                    val res = evalOnActivePage { src ->
                        "(function(){try{$src;var r=window.__nexoAiPageAgent.scrollPage(document,window,${JSONObject.quote(dir)});return JSON.stringify(r);}catch(_){return null;}})();"
                    }
                    Thread.sleep(280L)
                    val (snap, _) = captureSync()
                    val pct = res?.optJSONObject("scroll")?.optInt("percent") ?: snap?.scrollPercent ?: 0
                    val obs = snap?.toContextText("agent") ?: "Scroll completado ($pct%)."
                    NexoAiAgent.ToolOutcome(res?.optBoolean("ok") == true || snap != null, "Desplazado ($dir · $pct%)", obs)
                }

                "navigate" -> {
                    val url = call.args["url"].orEmpty()
                    val host = SiteControlModel.hostOf(url)
                    if (host.isNotEmpty() && !NexoAiConfig.isHostAllowed(app, host)) {
                        NexoAiAgent.ToolOutcome(false, "Bloqueado por Site Control ($host)", "Nexo AI está desactivado para $host.")
                    } else {
                        val prevUrl = TabManager.activeTab()?.url
                        val latch = CountDownLatch(1)
                        main.post {
                            TabManager.navigate(url)
                            latch.countDown()
                        }
                        latch.await(1000L, TimeUnit.MILLISECONDS)
                        waitForPageReady(prevUrl)
                        val (snap, err) = captureSync()
                        NexoAiAgent.ToolOutcome(
                            snap != null,
                            "Abierto: ${host.ifBlank { url.take(40) }}",
                            snap?.toContextText("agent") ?: (err ?: "Navegación iniciada a $url.")
                        )
                    }
                }

                "web_search" -> {
                    val query = call.args["query"].orEmpty()
                    val searchUrl = SearchResolver.searchUrl(app, query)
                    val prevUrl = TabManager.activeTab()?.url
                    val latch = CountDownLatch(1)
                    main.post {
                        TabManager.navigate(searchUrl)
                        latch.countDown()
                    }
                    latch.await(1000L, TimeUnit.MILLISECONDS)
                    waitForPageReady(prevUrl)
                    val (snap, err) = captureSync()
                    NexoAiAgent.ToolOutcome(
                        snap != null,
                        "Buscado: «${query.take(36)}»",
                        snap?.toContextText("agent") ?: (err ?: "Búsqueda iniciada para $query.")
                    )
                }

                "open_tab" -> {
                    val url = call.args["url"].orEmpty()
                    val latch = CountDownLatch(1)
                    main.post {
                        val isPriv = TabManager.activeTab()?.isPrivate == true
                        TabManager.createTab(url, isPrivate = isPriv, activate = true)
                        latch.countDown()
                    }
                    latch.await(1000L, TimeUnit.MILLISECONDS)
                    waitForPageReady(null)
                    val (snap, err) = captureSync()
                    val host = SiteControlModel.hostOf(url)
                    NexoAiAgent.ToolOutcome(
                        true,
                        "Nueva pestaña: ${host.ifBlank { url.take(36) }}",
                        snap?.toContextText("agent") ?: (err ?: "Pestaña abierta en $url.")
                    )
                }

                "list_tabs" -> {
                    val tabs = TabManager.allTabs()
                        .filter { !BrowserPrefs.isNexoHome(it.url) && (allowPrivate || !it.isPrivate) }
                        .take(15)
                    val activeId = TabManager.activeTab()?.id
                    val text = if (tabs.isEmpty()) "No hay pestañas web abiertas." else buildString {
                        appendLine("Pestañas abiertas (${tabs.size}):")
                        tabs.forEachIndexed { i, t ->
                            val mark = if (t.id == activeId) " [ACTIVA]" else ""
                            appendLine("${i + 1}. ${t.title.ifBlank { t.url }} — ${SiteControlModel.hostOf(t.url)}$mark")
                        }
                    }
                    NexoAiAgent.ToolOutcome(true, "${tabs.size} pestañas abiertas", text)
                }

                "switch_tab" -> {
                    val rawIndex = call.args["index"].orEmpty()
                    val tabs = TabManager.allTabs()
                        .filter { !BrowserPrefs.isNexoHome(it.url) && (allowPrivate || !it.isPrivate) }
                    val target = rawIndex.toIntOrNull()?.let { idx -> tabs.getOrNull(idx - 1) }
                        ?: tabs.firstOrNull { it.id == rawIndex }
                    if (target == null) {
                        NexoAiAgent.ToolOutcome(false, "Pestaña no encontrada ($rawIndex)", "No existe una pestaña con índice $rawIndex.")
                    } else {
                        val latch = CountDownLatch(1)
                        main.post {
                            TabManager.switchTo(target.id)
                            latch.countDown()
                        }
                        latch.await(1000L, TimeUnit.MILLISECONDS)
                        Thread.sleep(350L)
                        val (snap, err) = captureSync()
                        NexoAiAgent.ToolOutcome(
                            true,
                            "Cambiado a: ${target.title.take(38)}",
                            snap?.toContextText("agent") ?: (err ?: "Pestaña activa: ${target.title} (${target.url})")
                        )
                    }
                }

                "close_tab" -> {
                    val rawIndex = call.args["index"].orEmpty()
                    val allOpen = TabManager.allTabs()
                    val visible = allOpen.filter { !BrowserPrefs.isNexoHome(it.url) && (allowPrivate || !it.isPrivate) }
                    val target = rawIndex.toIntOrNull()?.let { idx -> visible.getOrNull(idx - 1) }
                        ?: visible.firstOrNull { it.id == rawIndex }
                    if (target == null) {
                        NexoAiAgent.ToolOutcome(false, "Pestaña no encontrada ($rawIndex)", "No existe una pestaña con índice $rawIndex.")
                    } else if (allOpen.size <= 1) {
                        NexoAiAgent.ToolOutcome(false, "Última pestaña protegida", "No se cierra la única pestaña abierta.")
                    } else {
                        val latch = CountDownLatch(1)
                        main.post {
                            TabManager.closeTab(target.id)
                            latch.countDown()
                        }
                        latch.await(1000L, TimeUnit.MILLISECONDS)
                        Thread.sleep(250L)
                        val (snap, _) = captureSync()
                        NexoAiAgent.ToolOutcome(
                            true,
                            "Cerrada: ${target.title.ifBlank { target.url }.take(34)}",
                            snap?.toContextText("agent") ?: "Pestaña cerrada correctamente."
                        )
                    }
                }

                "go_back" -> {
                    val prevUrl = TabManager.activeTab()?.url
                    val latch = CountDownLatch(1)
                    main.post {
                        TabManager.goBack()
                        latch.countDown()
                    }
                    latch.await(1000L, TimeUnit.MILLISECONDS)
                    waitForPageReady(prevUrl)
                    val (snap, _) = captureSync()
                    NexoAiAgent.ToolOutcome(true, "Vuelto a la página anterior", snap?.toContextText("agent") ?: "Historial retrocedido.")
                }

                "go_forward" -> {
                    val prevUrl = TabManager.activeTab()?.url
                    val latch = CountDownLatch(1)
                    main.post {
                        TabManager.goForward()
                        latch.countDown()
                    }
                    latch.await(1000L, TimeUnit.MILLISECONDS)
                    waitForPageReady(prevUrl)
                    val (snap, _) = captureSync()
                    NexoAiAgent.ToolOutcome(true, "Avanzado a la siguiente página", snap?.toContextText("agent") ?: "Historial avanzado.")
                }

                "reload_page" -> {
                    val prevUrl = TabManager.activeTab()?.url
                    val latch = CountDownLatch(1)
                    main.post {
                        TabManager.reload()
                        latch.countDown()
                    }
                    latch.await(1000L, TimeUnit.MILLISECONDS)
                    waitForPageReady(prevUrl)
                    val (snap, _) = captureSync()
                    NexoAiAgent.ToolOutcome(true, "Página recargada", snap?.toContextText("agent") ?: "Recarga completada.")
                }

                "find_in_page" -> {
                    val query = call.args["query"].orEmpty()
                    val res = evalOnActivePage { src ->
                        "(function(){try{$src;var r=window.__nexoAiPageAgent.highlightText(document,${JSONObject.quote(query)});return JSON.stringify(r);}catch(_){return null;}})();"
                    }
                    val found = res?.optBoolean("found") == true
                    NexoAiAgent.ToolOutcome(
                        found,
                        if (found) "Resaltado: «${query.take(32)}»" else "Sin coincidencia para «${query.take(32)}»",
                        if (found) "Se encontró y resaltó «$query» en la página actual." else "No se encontró «$query» en el contenido visible."
                    )
                }

                "click_element" -> {
                    val target = call.args["target"].orEmpty()
                    val prevUrl = TabManager.activeTab()?.url
                    val res = evalOnActivePage { src ->
                        "(function(){try{$src;var r=window.__nexoAiPageAgent.clickElement(document,window,${JSONObject.quote(target)});return JSON.stringify(r);}catch(_){return null;}})();"
                    }
                    val ok = res?.optBoolean("ok") == true
                    if (!ok) {
                        val err = res?.optString("error")?.ifBlank { "No se pudo pulsar $target." } ?: "No se pudo pulsar $target."
                        NexoAiAgent.ToolOutcome(false, err, err)
                    } else {
                        Thread.sleep(650L)
                        if (TabManager.activeTab()?.isLoading == true) waitForPageReady(prevUrl)
                        val (snap, _) = captureSync()
                        val clicked = res.optString("clicked").ifBlank { target }
                        NexoAiAgent.ToolOutcome(true, "Pulsado: ${clicked.take(36)}", snap?.toContextText("agent") ?: "Elemento activado.")
                    }
                }

                "extract_section" -> {
                    val query = call.args["query"].orEmpty()
                    val res = evalOnActivePage { src ->
                        "(function(){try{$src;var r=window.__nexoAiPageAgent.extractSection(document,window,${JSONObject.quote(query)},3000);return JSON.stringify(r);}catch(_){return null;}})();"
                    }
                    val txt = res?.optString("text").orEmpty()
                    NexoAiAgent.ToolOutcome(
                        txt.isNotBlank(),
                        if (txt.isNotBlank()) "Sección extraída: ${query.take(30)}" else "Sección no encontrada",
                        txt.ifBlank { "No se encontró una sección que coincida con «$query»." }
                    )
                }

                "bookmark_page" -> {
                    val tab = TabManager.activeTab()
                    if (tab == null || BrowserPrefs.isNexoHome(tab.url) || !UrlSafety.isWebUrl(tab.url)) {
                        NexoAiAgent.ToolOutcome(false, "Sin página web para guardar", "Abre una página web antes de guardarla en marcadores.")
                    } else {
                        val saved = runCatching {
                            BookmarkStore.add(app, tab.title.ifBlank { tab.url }, tab.url)
                        }.getOrNull()
                        val title = tab.title.ifBlank { SiteControlModel.hostOf(tab.url) }.take(38)
                        NexoAiAgent.ToolOutcome(
                            saved != null,
                            if (saved != null) "Marcador guardado: $title" else "Ya estaba en marcadores",
                            "Página ${tab.url} registrada en marcadores de Nexo."
                        )
                    }
                }

                "search_bookmarks" -> {
                    val query = call.args["query"].orEmpty()
                    val matches = BookmarkStore.search(BookmarkStore.file(app), query).take(10)
                    val obs = if (matches.isEmpty()) "No hay marcadores que coincidan con «$query»." else buildString {
                        appendLine("Marcadores encontrados (${matches.size}):")
                        matches.forEachIndexed { i, bm -> appendLine("${i + 1}. ${bm.title} (${bm.folder}) -> ${bm.url}") }
                    }
                    NexoAiAgent.ToolOutcome(true, "${matches.size} marcadores para «${query.take(24)}»", obs)
                }

                "search_history" -> {
                    val query = call.args["query"].orEmpty().lowercase(Locale.ROOT)
                    val entries = HistoryStore.list(app)
                        .filter { query.isBlank() || it.title.lowercase(Locale.ROOT).contains(query) || it.url.lowercase(Locale.ROOT).contains(query) }
                        .take(10)
                    val obs = if (entries.isEmpty()) "No hay entradas del historial que coincidan con «$query»." else buildString {
                        appendLine("Historial encontrado (${entries.size}):")
                        entries.forEachIndexed { i, h -> appendLine("${i + 1}. ${h.title} -> ${h.url}") }
                    }
                    NexoAiAgent.ToolOutcome(true, "${entries.size} visitas en historial", obs)
                }

                "open_reader" -> {
                    val activity = BrowserHosts.current() as? android.app.Activity
                    if (activity == null) {
                        NexoAiAgent.ToolOutcome(false, "Ventana no disponible", "No hay una actividad activa para abrir Modo Lectura.")
                    } else {
                        main.post { runCatching { NexoReader.openActiveTab(activity) } }
                        NexoAiAgent.ToolOutcome(true, "Modo Lectura abierto", "Se activó el Modo Lectura de Nexo en la pestaña actual.")
                    }
                }

                "translate_page" -> {
                    main.post {
                        runCatching {
                            TranslatorManager.translateActive(app) { _, _ -> }
                        }
                    }
                    NexoAiAgent.ToolOutcome(true, "Traducción activada", "Se inició la traducción de la página con Nexo Translator.")
                }

                else -> NexoAiAgent.ToolOutcome(false, "Herramienta no soportada", "Herramienta desconocida: ${call.tool}")
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
     * Si la acción depende del DOM (`highlight`, `fill_draft`, `select_option`) y el usuario ya cambió de pestaña,
     * URL o documento respecto a [expectedBinding], se bloquea por seguridad sin ejecutarse sobre otra página.
     */
    fun confirmAndExecuteAction(
        activity: Activity,
        action: NexoAiClient.ProposedAction,
        expectedBinding: NexoAiModels.PageBinding? = null
    ) {
        val currentBinding = currentActiveBinding()
        val host = currentBinding?.host.orEmpty()

        val requiresSamePage = action.type == "highlight" || action.type == "fill_draft" || action.type == "select_option"
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
            "select_option" -> "Nexo AI propone seleccionar «${action.value}» en el desplegable «${action.target}»."
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
                "select_option" -> {
                    val session = activeTab?.session as? CefriumEngineSession
                    val source = loadAgentScript(activity)
                    if (session != null && source != null) {
                        val f = JSONObject.quote(action.target)
                        val v = JSONObject.quote(action.value.take(200))
                        val js = "(function(){try{$source;if(window.__nexoAiPageAgent)window.__nexoAiPageAgent.selectOption(document,$f,$v);}catch(_){}})();"
                        session.evaluateInMainFrame(js) {}
                        NexoAiConfig.appendLog(activity, "select_option:${action.target}", host, "ejecutado")
                        Toastish.show(activity, "Opción seleccionada en «${action.target}».")
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
