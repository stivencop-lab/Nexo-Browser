package com.nexo.browser.shield

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canal entre Nexo y los marcos de una pestaña (o del panel) sobre
 * `window.cefriumQuery`, que solo admite respuestas inmediatas.
 *
 * Cada consulta de una página llega envuelta como `{"m": mensaje, "s": n}`:
 * `m` es el mensaje del puerto (`{id, kind, payload}`, el formato de
 * `phantom-shim.js`), `s` el último evento que ese marco ya recibió (-1 si
 * acaba de empezar). La respuesta lleva la contestación a `m` y los eventos
 * que Nexo publicó después de `s` (cambios de ajustes, órdenes de video,
 * respuestas tardías). Los marcos sin nada que preguntar consultan de vez en
 * cuando con solo `s`; [wake] les avisa de que hay eventos nuevos.
 *
 * Sin Android: se prueba en la JVM.
 */
class PageChannel {
    private val lock = Any()
    private val events = ArrayDeque<Pair<Long, String>>()
    private var seq = 0L

    /** Avisa a la página de que hay eventos (hilo principal); lo fija el motor. */
    @Volatile var wake: () -> Unit = {}

    /** Algún marco preguntó desde el último [reset]: hay scripts de Nexo en la página. */
    @Volatile var connected = false
        private set

    /** Mensaje a todos los marcos (en orden, una sola vez cada uno). */
    fun push(text: String) {
        synchronized(lock) {
            seq++
            events.addLast(seq to text)
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        runCatching { wake() }
    }

    /** Petición de un marco: el mensaje que trae (o `null` si solo consulta) y desde qué evento. */
    class Request(val message: String?, val since: Long)

    fun parse(raw: String): Request? = runCatching {
        if (raw.length > MAX_REQUEST) return null
        val json = JSONObject(raw)
        val message = if (json.has("m") && !json.isNull("m")) json.getString("m") else null
        Request(message, json.optLong("s", -1L))
    }.getOrNull()

    /** Respuesta a un marco: [reply] para su mensaje y los eventos nuevos desde [since]. */
    fun envelope(reply: String?, since: Long): String {
        connected = true
        val json = JSONObject()
        if (reply != null) json.put("reply", reply)
        synchronized(lock) {
            json.put("seq", seq)
            if (since >= 0) {
                val list = JSONArray()
                events.forEach { (n, text) -> if (n > since) list.put(text) }
                if (list.length() > 0) json.put("events", list)
            }
        }
        return json.toString()
    }

    /** Documento nuevo en la página principal: hasta que pregunte, no hay scripts de Nexo. */
    fun reset() {
        connected = false
    }

    companion object {
        const val MAX_EVENTS = 64
        const val MAX_REQUEST = 256 * 1024
    }
}
