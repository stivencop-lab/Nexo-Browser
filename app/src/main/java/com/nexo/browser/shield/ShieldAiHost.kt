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
 * Anfitrión oculto de la IA de PhantomShield: un navegador Chromium fuera de
 * pantalla (OSR, sin Activity ni superficie) en un perfil propio
 * ([PhantomShieldPanel.aiProfile]), con `host/ai-host.html` (el clasificador
 * original de la extensión). Se crea solo cuando hace falta y se cierra tras
 * un rato sin uso; su estado (memoria, dominios, estadísticas) vive en el
 * almacén de PhantomShield.
 *
 * Antes era un navegador de superficie de la Activity, en el mismo perfil y
 * origen que el panel: nunca se adjuntaba a una ventana, dependía de que la
 * Activity existiera y, en móviles que comparten el proceso de renderizado,
 * su caída cerraba el panel con los ajustes de IA abiertos. Si el modo fuera
 * de pantalla no está disponible se vuelve a ese modo como respaldo.
 *
 * Todo en el hilo principal, salvo las llamadas HTTP a la API de IA.
 */
internal object ShieldAiHost {
    private const val HOST_PAGE = "host/ai-host.html"
    /** Por encima del presupuesto total del clasificador (de 45 s a 120 s con respaldos): su respuesta llega antes. */
    private const val REQUEST_TIMEOUT_MS = 150_000L
    /** Vista mínima: el anfitrión no dibuja nada, solo ejecuta el clasificador. */
    private const val OSR_SIZE_PX = 64
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
    /** El anfitrión actual es fuera de pantalla; si uno así no llegó a cargar, se usa la superficie. */
    private var hostOffscreen = false
    private var offscreenFailed = false
    private val startTimeout: Runnable = Runnable {
        if (browser != null && !ready && hostOffscreen) {
            offscreenFailed = true
            EngineDiagnostics.log("IA de PhantomShield: el modo fuera de pantalla no cargó; se usará la superficie")
        }
        if (browser != null && !ready) close("no cargó en ${START_TIMEOUT_MS / 1000} s", "No se pudo iniciar la IA de PhantomShield. Inténtalo de nuevo.")
    }

    /** Peticiones a la API de IA: sin cookies, sin caché, sin redirecciones y con protección SSRF. */
    private val http by lazy {
        val publicDns = com.nexo.browser.NativeImagePolicy.publicDns(okhttp3.Dns.SYSTEM)
        val dns = object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val lower = hostname.lowercase().trimEnd('.')
                if (lower == "localhost" || lower == "127.0.0.1" || lower == "::1") {
                    return okhttp3.Dns.SYSTEM.lookup(hostname)
                }
                return publicDns.lookup(hostname)
            }
        }
        OkHttpClient.Builder()
            .dns(dns)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false)
            // Por encima del tiempo límite máximo del clasificador (90 s): quien corta es él, con su mensaje.
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(100, TimeUnit.SECONDS).callTimeout(100, TimeUnit.SECONDS)
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
            done(failure("No se pudo iniciar la IA de PhantomShield. Abre Nexo e inténtalo de nuevo."))
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
        channel?.dispose()
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

    /** Navegador del anfitrión: fuera de pantalla en su perfil; si no, el modo de superficie anterior. */
    private fun createHost(): CefriumBrowser? {
        val context = runCatching { com.nexo.browser.AppContext.get() }.getOrNull()
            ?: CefriumEngine.activity()?.applicationContext ?: return null
        val profile = PhantomShieldPanel.aiProfile(context) ?: PhantomShieldPanel.profile(context) ?: return null
        val offscreen = if (offscreenFailed) null else runCatching { CefriumBrowser(OSR_SIZE_PX, OSR_SIZE_PX, 1f, profile) }
            .onFailure { EngineDiagnostics.log("IA de PhantomShield: sin modo fuera de pantalla; se usa la superficie", it) }
            .getOrNull()
        hostOffscreen = offscreen != null
        if (offscreen != null) return offscreen
        val activity = CefriumEngine.activity() ?: return null
        return runCatching { CefriumBrowser.createWithSurface(activity, profile) }
            .onFailure { EngineDiagnostics.log("IA de PhantomShield: no se pudo crear el anfitrión", it) }
            .getOrNull()
    }

    private fun ensure(): Boolean {
        browser?.let { if (!it.isClosed) return true; close("cerrado") }
        val web = createHost() ?: return false
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
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                val deliver: (String) -> Unit = { response ->
                    if (answered.compareAndSet(false, true)) runCatching { callback.success(response) }
                }
                if (parsed.message == null) {
                    if (parsed.wait) pageChannel.awaitEvents(parsed.since, parsed.waiterKey, deliver)
                    else deliver(pageChannel.envelope(null, parsed.since))
                    return true
                }
                val before = pageChannel.currentSeq()
                val sync = java.util.concurrent.atomic.AtomicBoolean(true)
                onMessage(parsed.message, web, pageChannel) { reply ->
                    if (sync.get()) deliver(pageChannel.envelope(reply, parsed.since, before))
                    else deliver(pageChannel.replyEnvelope(reply))
                }
                sync.set(false)
                return true
            }
        }
        web.setQueryHandler(CefriumBrowser.QueryOriginPolicy.exactly(PhantomShield.PANEL_ORIGIN), handler)
        // Mantener el anfitrión activo aunque su vista no esté adjunta: evita que Chromium pause los temporizadores.
        runCatching { web.onResume(); web.setPageVisible(true) }
        EngineDiagnostics.log("IA de PhantomShield: anfitrión iniciado (${if (hostOffscreen) "fuera de pantalla" else "superficie"})")
        main.postDelayed(startTimeout, START_TIMEOUT_MS)
        web.loadUrl(PhantomShield.BASE_URL + HOST_PAGE)
        return true
    }

    private fun answer(id: Int, result: Any?, error: String? = null): String {
        val json = JSONObject().put("id", id)
        if (error != null) json.put("error", error) else json.put("result", result ?: JSONObject.NULL)
        return json.toString()
    }

    private fun onMessage(text: String, web: CefriumBrowser, pageChannel: PageChannel, done: (String?) -> Unit) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return done(null)
        val id = json.optInt("id")
        val payload = json.opt("payload")
        when (json.optString("kind")) {
            "ready" -> {
                ready = true
                main.removeCallbacks(startTimeout)
                EngineDiagnostics.log("IA de PhantomShield: anfitrión listo")
                queue.forEach { pageChannel.push(it) }
                queue.clear()
                done(answer(id, null))
            }
            "reply" -> {
                val reply = payload as? JSONObject
                val result = reply?.opt("result")
                val rid = reply?.optInt("rid")
                if (rid != null) {
                    timeouts.remove(rid)?.let { main.removeCallbacks(it) }
                    pending.remove(rid)?.invoke(if (result == JSONObject.NULL) null else result)
                }
                done(answer(id, null))
            }
            "get" -> done(answer(id, PhantomShield.hostGet(payload)))
            "set" -> {
                val values = payload as? JSONObject ?: return done(answer(id, null, "Valores no válidos"))
                val allowed = JSONObject()
                values.keys().forEach { key -> if (key in WRITABLE) allowed.put(key, values.get(key)) }
                PhantomShield.hostSet(allowed)
                done(answer(id, null))
            }
            "remove" -> {
                PhantomShield.hostRemove(PhantomShield.keysOf(payload).orEmpty().filter { it in WRITABLE })
                done(answer(id, null))
            }
            "fetch" -> {
                val request = payload as? JSONObject ?: return done(answer(id, null, "Petición no válida"))
                fetch(request) { result, error ->
                    if (web === browser) done(answer(id, result, error))
                }
            }
            else -> done(answer(id, null, "No disponible"))
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
                        if (source != null) {
                            while (buffer.size <= MAX_RESPONSE_BYTES && source.read(buffer, 8192) != -1L) {
                                // lectura acotada en buffer
                            }
                        }
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
