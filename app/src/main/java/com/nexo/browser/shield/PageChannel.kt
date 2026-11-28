package com.nexo.browser.shield

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canal bidireccional entre Nexo y los marcos de una pestaña (o del panel /
 * anfitrión de IA) sobre `window.cefriumQuery`, aprovechando respuestas
 * asíncronas de `QueryCallback` sin sondeos periódicos (`setInterval`).
 *
 * Protocolo por consulta:
 * - Llamada RPC (`{"m": mensaje, "s": n}`): Nexo responde inmediatamente si el
 *   resultado es síncrono, o conserva el `QueryCallback` y responde después
 *   cuando termina el trabajo asíncrono (Dark Reader, IA, actualización de
 *   listas, `fetch` del anfitrión).
 * - Espera de eventos (`{"m": null, "s": n, "w": true, "k": clave}`): si ya
 *   hay eventos posteriores a `n` (o `n < 0` en el saludo inicial), responde al
 *   instante; si no, conserva el `QueryCallback` y lo resuelve inmediatamente
 *   en cuanto [push] publica un evento nuevo (alcanza también a iframes y al
 *   anfitrión oculto sin `evaluateJavaScript` ni temporizadores).
 *
 * Como Cefrium 0.22.0 no expone `onQueryCanceled` en `QueryHandler`, el mapa
 * de esperas se acota a [MAX_WAITERS] por clave de marco y se limpia en
 * [reset] y [dispose].
 *
 * Sin Android: se prueba en la JVM.
 */
class PageChannel {
    private class Waiter(val since: Long, val mainFrame: Boolean, val parkedAt: Long, val deliver: (String) -> Unit)

    private val lock = Any()
    private val events = ArrayDeque<Pair<Long, String>>()
    private val waiters = LinkedHashMap<String, Waiter>()
    private var seq = 0L
    private var nextAnonWaiter = 0L

    /** Gancho opcional al publicar un evento (p. ej. pruebas). */
    @Volatile var wake: () -> Unit = {}

    /** Algún marco preguntó desde el último [reset]: hay scripts de Nexo en la página. */
    @Volatile var connected = false
        private set

    /** Número de esperas `cefriumQuery` aparcadas ahora mismo (diagnóstico y pruebas). */
    fun pendingWaiterCount(): Int = synchronized(lock) { waiters.size }

    /** Mensaje a todos los marcos (en orden, una sola vez cada uno). */
    fun push(text: String) {
        val ready: List<Pair<Waiter, String>>
        synchronized(lock) {
            seq++
            events.addLast(seq to text)
            while (events.size > MAX_EVENTS) events.removeFirst()
            ready = if (waiters.isEmpty()) {
                emptyList()
            } else {
                val list = waiters.values.map { waiter -> waiter to buildEnvelopeLocked(null, waiter.since, Long.MAX_VALUE) }
                waiters.clear()
                list
            }
        }
        for ((waiter, payload) in ready) {
            runCatching { waiter.deliver(payload) }
        }
        runCatching { wake() }
    }

    /**
     * Petición de un marco: [message] (o `null` si espera eventos), [since] último
     * evento recibido, [wait] si puede quedarse aparcada hasta el próximo [push] y
     * [waiterKey] identificador por marco para reemplazar su propia espera.
     */
    class Request(
        val message: String?,
        val since: Long,
        val wait: Boolean = false,
        val waiterKey: String? = null
    )

    fun parse(raw: String): Request? = runCatching {
        if (raw.length > MAX_REQUEST) return null
        val json = JSONObject(raw)
        val message = if (json.has("m") && !json.isNull("m")) json.getString("m") else null
        val wait = json.optBoolean("w", false)
        val key = json.optString("k").takeIf { it.length in 1..64 && it.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' } }
        Request(message, json.optLong("s", -1L), wait, key)
    }.getOrNull()

    /** Secuencia actual del canal (para no perder eventos publicados durante la misma consulta). */
    fun currentSeq(): Long = synchronized(lock) { seq }

    /**
     * Atiende una espera larga de eventos (`w = true`):
     * - Si `since < 0` (saludo inicial), responde al instante con `{seq}` sin repetir eventos antiguos.
     * - Si ya existen eventos con `n > since`, los entrega de inmediato.
     * - En caso contrario, aparca [deliver] hasta el siguiente [push].
     */
    fun awaitEvents(since: Long, waiterKey: String?, mainFrame: Boolean = false, deliver: (String) -> Unit) {
        connected = true
        var displaced: Waiter? = null
        val immediate = synchronized(lock) {
            when {
                since < 0L -> JSONObject().put("seq", seq).toString()
                since < seq -> buildEnvelopeLocked(null, since, Long.MAX_VALUE)
                else -> {
                    val rawKey = waiterKey ?: "anon:${++nextAnonWaiter}"
                    val key = if (mainFrame) "main:$rawKey" else "sub:$rawKey"
                    waiters.remove(key)
                    val now = System.nanoTime()
                    if (waiters.size >= MAX_WAITERS) {
                        val candidate = waiters.entries.firstOrNull {
                            !it.value.mainFrame && (mainFrame || now - it.value.parkedAt >= WAITER_LEASE_NS)
                        }
                        if (candidate == null) return@synchronized RETRY
                        displaced = waiters.remove(candidate.key)
                    }
                    waiters[key] = Waiter(since, mainFrame, now, deliver)
                    null
                }
            }
        }
        displaced?.let { runCatching { it.deliver(RETRY) } }
        if (immediate != null) runCatching { deliver(immediate) }
    }

    /**
     * Respuesta a un marco: [reply] para su mensaje y los eventos nuevos desde [since].
     * Si el marco acaba de arrancar (`since < 0`), no repite eventos anteriores, pero sí
     * entrega los que su propio mensaje provocó (`n > generatedAfter`).
     */
    fun envelope(reply: String?, since: Long, generatedAfter: Long = Long.MAX_VALUE): String {
        connected = true
        return synchronized(lock) { buildEnvelopeLocked(reply, since, generatedAfter) }
    }

    /**
     * Respuesta directa para una llamada RPC asíncrona resuelta después: no
     * modifica `seq` ni duplica eventos, que viajan por la espera dedicada.
     */
    fun replyEnvelope(reply: String?): String {
        connected = true
        val json = JSONObject()
        if (reply != null) json.put("reply", reply)
        return json.toString()
    }

    private fun buildEnvelopeLocked(reply: String?, since: Long, generatedAfter: Long): String {
        val json = JSONObject()
        if (reply != null) json.put("reply", reply)
        json.put("seq", seq)
        val cutoff = if (since >= 0) since else generatedAfter
        if (cutoff < seq) {
            val list = JSONArray()
            events.forEach { (n, text) -> if (n > cutoff) list.put(text) }
            if (list.length() > 0) json.put("events", list)
        }
        return json.toString()
    }

    /** Documento nuevo en la página principal: libera esperas obsoletas hasta que el nuevo marco pregunte. */
    fun reset() {
        connected = false
        synchronized(lock) { waiters.clear() }
    }

    /** Cierre definitivo de la pestaña, panel o anfitrión: libera todas las referencias de callbacks. */
    fun dispose() {
        connected = false
        synchronized(lock) {
            waiters.clear()
            events.clear()
        }
    }

    companion object {
        const val MAX_EVENTS = 64
        const val MAX_WAITERS = 32
        private const val WAITER_LEASE_NS = 45_000_000_000L
        private const val RETRY = "{\"retryAfter\":1500}"
        const val MAX_REQUEST = 512 * 1024
    }
}
