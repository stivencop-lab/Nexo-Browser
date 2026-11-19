package com.nexo.browser.shield

import android.os.Handler
import android.os.Looper
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumNavigationClient
import com.cefrium.CefriumNavigationRequest
import com.cefrium.CefriumRendererTerminationStatus
import com.nexo.browser.PhantomShieldPanel
import com.nexo.browser.engine.cefrium.CefriumEngine
import com.nexo.browser.engine.cefrium.EngineDiagnostics
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Anfitrión oculto de la IA de PhantomShield: un navegador Chromium sin
 * vista, en el perfil del panel, con `host/ai-host.html` (el clasificador
 * original de la extensión). Se crea solo cuando hace falta y se cierra tras
 * un rato sin uso; su estado (memoria, dominios, estadísticas) vive en el
 * almacén de PhantomShield.
 *
 * Todo en el hilo principal, salvo las llamadas HTTP a la API de IA.
 */
internal object ShieldAiHost {
    private const val HOST_PAGE = "host/ai-host.html"
    private const val REQUEST_TIMEOUT_MS = 60_000L
    private const val IDLE_CLOSE_MS = 10 * 60_000L
    private const val MAX_RESPONSE_BYTES = 65_536L
    private const val MAX_QUEUE = 32
    private const val START_TIMEOUT_MS = 20_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var browser: CefriumBrowser? = null
    private var channel: PageChannel? = null
    @Volatile private var ready = false
    private var nextRid = 0
    private val pending = LinkedHashMap<Int, (Any?) -> Unit>()
    private val timeouts = HashMap<Int, Runnable>()
    private val activeCalls = java.util.concurrent.CopyOnWriteArrayList<Call>()
    private val queue = ArrayList<String>()
    private val closeIdle: Runnable = Runnable { close("sin uso") }
    private val startTimeout: Runnable = Runnable {
        if (browser != null && !ready) close("no cargó en ${START_TIMEOUT_MS / 1000} s", "No se pudo iniciar la IA de PhantomShield. Inténtalo de nuevo.")
    }

    /** Peticiones a la API de IA: sin cookies, sin caché y sin redirecciones (como el `fetch` original). */
    private val http by lazy {
        OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** Mensajes que el anfitrión puede escribir en el almacén. */
    val WRITABLE = setOf("aiConfig", "aiStats", "aiStatus", "aiProviders", "aiMemory", "aiAdDomains", "aiMemoryRevision")

    /** Estado breve para el diagnóstico del motor. */
    fun statusSummary(): String = when {
        browser == null -> "en espera"
        ready -> "listo"
        else -> "iniciando"
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Envía [message] (ya autorizado por Nexo) al anfitrión, con [sender]
     * como remitente. [done] recibe la respuesta, o un error con `ok: false`.
     */
    fun request(message: JSONObject, sender: JSONObject, done: (Any?) -> Unit): Unit = onMain {
        main.removeCallbacks(closeIdle)
        main.postDelayed(closeIdle, IDLE_CLOSE_MS)
        if (!ensure()) {
            done(failure("La IA necesita Nexo abierto en primer plano. Inténtalo de nuevo."))
            return@onMain
        }
        val rid = ++nextRid
        pending[rid] = done
        val text = JSONObject().put("event", "request").put("rid", rid).put("message", message).put("sender", sender).toString()
        if (ready) channel?.push(text) else {
            if (queue.size >= MAX_QUEUE) queue.removeAt(0)
            queue += text
        }
        val timeout = Runnable {
            timeouts.remove(rid)
            pending.remove(rid)?.invoke(failure("La IA tardó demasiado en responder. El bloqueo local sigue activo."))
        }
        timeouts[rid] = timeout
        main.postDelayed(timeout, REQUEST_TIMEOUT_MS)
    }

    /** Cambios del almacén para `storage.onChanged` del anfitrión. */
    fun storageChanged(text: String): Unit = onMain { if (ready) channel?.push(text) }

    fun close(reason: String, message: String = "La IA se reinició. Inténtalo de nuevo."): Unit = onMain {
        main.removeCallbacks(closeIdle)
        main.removeCallbacks(startTimeout)
        val current = browser ?: return@onMain
        EngineDiagnostics.log("IA de PhantomShield: anfitrión cerrado ($reason)")
        browser = null
        channel = null
        ready = false
        queue.clear()
        activeCalls.forEach { runCatching { it.cancel() } }
        activeCalls.clear()
        timeouts.values.forEach { main.removeCallbacks(it) }
        timeouts.clear()
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it(failure(message)) }
        runCatching { current.stopLoading(); current.close() }
    }

    private fun failure(text: String) = JSONObject().put("ok", false).put("code", "unavailable").put("error", text)

    private fun ensure(): Boolean {
        browser?.let { if (!it.isClosed) return true; close("cerrado") }
        val activity = CefriumEngine.activity() ?: return false
        val profile = PhantomShieldPanel.profile(activity) ?: return false
        val web = runCatching { CefriumBrowser.createWithSurface(activity, profile) }
            .onFailure { EngineDiagnostics.log("IA de PhantomShield: no se pudo crear el anfitrión", it) }
            .getOrNull() ?: return false
        val pageChannel = PageChannel()
        browser = web
        channel = pageChannel
        ready = false
        web.setNavigationClient(object : CefriumNavigationClient {
            override fun onBeforeNavigate(browser: CefriumBrowser, request: CefriumNavigationRequest): Boolean =
                !request.isForMainFrame || request.url.orEmpty() != PhantomShield.BASE_URL + HOST_PAGE

            override fun onRenderProcessTerminated(browser: CefriumBrowser, status: CefriumRendererTerminationStatus, errorCode: Int) {
                if (browser === this@ShieldAiHost.browser) close("proceso terminado: $status")
            }

            override fun onLoadError(browser: CefriumBrowser, error: com.cefrium.CefriumLoadError) {
                EngineDiagnostics.log("IA de PhantomShield: error de carga ${error.errorCode} (${error.description})")
            }
        })
        runCatching { web.addDocumentStartJavaScript(PhantomShield.aiHostScript(), setOf(PhantomShield.PANEL_ORIGIN)) }
        val handler = object : CefriumBrowser.QueryHandler {
            override fun onQuery(queryId: Long, request: String, origin: String, callback: CefriumBrowser.QueryCallback): Boolean =
                onQuery(queryId, request, origin, true, callback)

            override fun onQuery(queryId: Long, request: String, origin: String, isMainFrame: Boolean, callback: CefriumBrowser.QueryCallback): Boolean {
                if (web !== browser || !isMainFrame) return false
                val parsed = pageChannel.parse(request) ?: return false
                val before = pageChannel.currentSeq()
                val reply = parsed.message?.let { onMessage(it, web, pageChannel) }
                callback.success(pageChannel.envelope(reply, parsed.since, before))
                return true
            }
        }
        web.setQueryHandler(CefriumBrowser.QueryOriginPolicy.exactly(PhantomShield.PANEL_ORIGIN), handler)
        pageChannel.wake = { main.post { if (!web.isClosed) runCatching { web.evaluateJavaScript("window.__nexoWake&&window.__nexoWake()") } } }
        // Mantener el anfitrión activo aunque su vista no esté adjunta: evita que Chromium pause los temporizadores.
        runCatching { web.onResume(); web.setPageVisible(true) }
        EngineDiagnostics.log("IA de PhantomShield: anfitrión iniciado")
        main.postDelayed(startTimeout, START_TIMEOUT_MS)
        web.loadUrl(PhantomShield.BASE_URL + HOST_PAGE)
        return true
    }

    private fun answer(id: Int, result: Any?, error: String? = null): String {
        val json = JSONObject().put("id", id)
        if (error != null) json.put("error", error) else json.put("result", result ?: JSONObject.NULL)
        return json.toString()
    }

    private fun onMessage(text: String, web: CefriumBrowser, pageChannel: PageChannel): String? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = json.optInt("id")
        val payload = json.opt("payload")
        return when (json.optString("kind")) {
            "ready" -> {
                ready = true
                main.removeCallbacks(startTimeout)
                EngineDiagnostics.log("IA de PhantomShield: anfitrión listo")
                queue.forEach { pageChannel.push(it) }
                queue.clear()
                answer(id, null)
            }
            "reply" -> {
                val reply = payload as? JSONObject
                val result = reply?.opt("result")
                val rid = reply?.optInt("rid")
                if (rid != null) {
                    timeouts.remove(rid)?.let { main.removeCallbacks(it) }
                    pending.remove(rid)?.invoke(if (result == JSONObject.NULL) null else result)
                }
                answer(id, null)
            }
            "get" -> answer(id, PhantomShield.hostGet(payload))
            "set" -> {
                val values = payload as? JSONObject ?: return answer(id, null, "Valores no válidos")
                val allowed = JSONObject()
                values.keys().forEach { key -> if (key in WRITABLE) allowed.put(key, values.get(key)) }
                PhantomShield.hostSet(allowed)
                answer(id, null)
            }
            "remove" -> {
                PhantomShield.hostRemove(PhantomShield.keysOf(payload).orEmpty().filter { it in WRITABLE })
                answer(id, null)
            }
            "fetch" -> {
                val request = payload as? JSONObject ?: return answer(id, null, "Petición no válida")
                fetch(request) { result, error ->
                    if (web === browser) pageChannel.push(answer(id, result, error))
                }
                null
            }
            else -> answer(id, null, "No disponible")
        }
    }

    /** `fetch` del clasificador: solo POST HTTPS a la API configurada; respuesta acotada. */
    private fun fetch(request: JSONObject, done: (JSONObject?, String?) -> Unit) {
        val url = request.optString("url")
        val method = request.optString("method", "POST")
        if (!url.startsWith("https://") || method != "POST") { done(null, "Solo HTTPS"); return }
        val headers = HashMap<String, String>()
        var contentType = "application/json"
        request.optJSONObject("headers")?.let { source ->
            source.keys().forEach { key ->
                val value = source.optString(key)
                val name = key.lowercase()
                if ('\n' in value || '\r' in value || value.length > 8192) return@forEach
                if (name == "content-type") contentType = value.ifBlank { "application/json" }
                else if (name !in FORBIDDEN_HEADERS) headers[key] = value
            }
        }
        val body = request.optString("body").toRequestBody(contentType.toMediaTypeOrNull())
        val call = runCatching {
            http.newCall(Request.Builder().url(url).headers(headers.toHeaders()).post(body).build())
        }.getOrElse { done(null, "Dirección no válida"); return }
        activeCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCalls.remove(call)
                EngineDiagnostics.log("IA de PhantomShield: error de red (${e.javaClass.simpleName})")
                val error = if (e is InterruptedIOException) "timeout" else "Sin conexión"
                main.post { done(null, error) }
            }

            override fun onResponse(call: Call, response: Response) {
                activeCalls.remove(call)
                val result = runCatching {
                    response.use {
                        val source = it.body?.source()
                        val buffer = okio.Buffer()
                        // Un byte de más basta para que el clasificador detecte el exceso.
                        if (source != null) while (buffer.size <= MAX_RESPONSE_BYTES && source.read(buffer, 8192) != -1L) Unit
                        val bytes = buffer.readByteArray(minOf(buffer.size, MAX_RESPONSE_BYTES + 1))
                        val out = JSONObject()
                        it.header("Retry-After")?.let { value -> out.put("retry-after", value.take(64)) }
                        it.header("Content-Type")?.let { value -> out.put("content-type", value.take(128)) }
                        JSONObject().put("status", it.code).put("headers", out)
                            .put("body", String(bytes, Charsets.UTF_8))
                    }
                }
                main.post { result.fold({ done(it, null) }, { done(null, "Sin conexión") }) }
            }
        })
    }

    private val FORBIDDEN_HEADERS = setOf("host", "cookie", "content-length", "connection", "transfer-encoding", "origin", "referer")
}
