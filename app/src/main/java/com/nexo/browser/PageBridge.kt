package com.nexo.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import org.json.JSONObject
import java.util.WeakHashMap

/**
 * Puente de Nexo con las páginas (`assets/nexo/bridge/page-bridge.js`),
 * sobre el canal de cada pestaña
 * (`PageChannel` + `window.cefriumQuery`). Mini video, subtítulos (pistas,
 * texto del reproductor y audio del video para Deepgram), estado de
 * reproducción en segundo plano y modo nocturno.
 *
 * Cada marco se
 * identifica con un token aleatorio (`bridge-shim.js`) y los mensajes de Nexo
 * van a todos los marcos, al principal (`top`) o a uno solo (su token). Las
 * reglas: los marcos con video se ofrecen para los
 * subtítulos, Nexo concede la captura al mejor en 200 ms y solo ese marco
 * puede enviar audio y estados; el estado del video para el mini reproductor
 * solo lo da el marco principal.
 *
 * Todo en el hilo principal.
 */
object PageBridge {
    private const val OFFER_WINDOW_MS = 200L
    private const val DARK_READER_ASSET = "nexo/bridge/darkreader.js"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private data class PipState(val resume: Boolean, val isolate: Boolean = true)

    private class State {
        /** Marcos con el puente conectado (token). */
        val frames = LinkedHashSet<String>()
        var topConnected = false
        var owner: String? = null
        var offers: MutableMap<String, Int>? = null
        var pip: PipState? = null
        var mediaFrame: String? = null
    }

    private val states = WeakHashMap<EngineSession, State>()

    @Volatile private var darkReader: String? = null
    /** Notificación ligera del marco principal al desplazarse (solo UI del dock adaptable). */
    @Volatile var onScrollDirection: ((EngineSession, Boolean, Int) -> Unit)? = null

    private fun state(session: EngineSession) = states.getOrPut(session) { State() }

    // --- Desde la página ---

    /**
     * Mensaje del puente de un marco. [frame]: token del marco (o un
     * identificador por origen si el script no lo da); [isMainFrame]: lo dice
     * Chromium, no la página.
     */
    fun onMessage(session: CefriumEngineSession, frame: String, isMainFrame: Boolean, json: JSONObject) {
        val accepted = BridgeLimits.accept(json)
        com.nexo.browser.engine.cefrium.EngineDiagnostics.recordBridgeMessage(accepted)
        if (!accepted) return
        val state = state(session)
        val type = json.optString("type")
        if (type == "content_script_ready") {
            onConnect(session, state, frame, isMainFrame)
            return
        }
        val fresh = state.frames.add(frame)
        if (isMainFrame) state.topConnected = true
        if (fresh && LiveCaptions.isActive(session) && !LiveCaptions.pageSuspended(session)) {
            LiveCaptions.onBridgeConnected(session)
            if (!type.startsWith("captions_")) send(session, captionsOn(session), frame)
        }
        when (type) {
            "video_state" -> if (isMainFrame) videoState(session, json)
            "media_state" -> mediaState(session, state, frame, json)
            "scroll_dir" -> if (isMainFrame) onScrollDirection?.invoke(session, json.optString("dir") == "down", json.optInt("y", 0))
            else -> captions(session, state, frame, json)
        }
    }

    /** Resultado de [onCall] cuando la respuesta llegará después por su `reply`. */
    val LATER = Any()

    /** Llamada con respuesta (`sendNativeMessage`/`sendMessage`): el resultado, o [LATER] si llegará por [reply]. */
    fun onCall(session: CefriumEngineSession, isMainFrame: Boolean, json: JSONObject, reply: (Any?) -> Unit): Any? =
        when (json.optString("type")) {
            // Incluye el modo oscuro: el primer mensaje del marco ya lo trae, sin esperar a la conexión.
            "get_state" -> browserState(AppContext.get(), session).put("ok", true)
            // Dark Reader solo en el marco principal: es el único donde Nexo puede ejecutar código
            // sin depender de la CSP del sitio.
            "nexo_dark_load" -> if (!isMainFrame) false else {
                loadDarkReader(session, reply)
                LATER
            }
            else -> null
        }

    private fun onConnect(session: CefriumEngineSession, state: State, frame: String, isMainFrame: Boolean) {
        state.frames += frame
        if (isMainFrame && !state.topConnected) {
            com.nexo.browser.engine.cefrium.EngineDiagnostics.log("puente de Nexo conectado (marco principal)")
        }
        if (isMainFrame) state.topConnected = true
        // Solo al marco nuevo: los demás ya tienen el estado (y quien tiene los subtítulos sigue igual).
        send(session, browserState(AppContext.get(), session), frame)
        if (LiveCaptions.isActive(session) && !LiveCaptions.pageSuspended(session)) {
            send(session, captionsOn(session), frame)
            LiveCaptions.onBridgeConnected(session)
        }
        if (isMainFrame) state.pip?.let { pip ->
            send(session, pipModeMessage(true, pip.resume, pip.isolate), frame)
            if (!pip.resume) send(session, JSONObject().put("type", "pip_command").put("command", "pause"), frame)
        }
    }

    private fun videoState(session: EngineSession, json: JSONObject) {
        val tab = TabManager.tabForSession(session) ?: return
        val rx = json.optInt("rx", 0)
        val ry = json.optInt("ry", 0)
        val present = json.optBoolean("present", false)
        val playing = json.optBoolean("playing", false)
        BrowserMediaController.onVideoState(
            tab.id,
            present,
            playing,
            json.optInt("width", 0),
            json.optInt("height", 0),
            android.graphics.Rect(rx, ry, rx + json.optInt("rw", 0), ry + json.optInt("rh", 0))
        )
        runCatching {
            com.nexo.browser.media.NexoMediaEngine.onVideoState(
                context = AppContext.get(),
                tabId = tab.id,
                session = session,
                host = tab.url,
                present = present,
                playing = playing,
                playbackRate = json.optDouble("rate", 1.0).toFloat(),
                currentTimeSec = json.optDouble("time", 0.0),
                durationSec = json.optDouble("duration", 0.0),
                drmProtected = json.optBoolean("drm", false),
                hasTextTracks = json.optBoolean("tracks", false),
                loop = json.optBoolean("loop", false),
                bufferedAheadSec = json.optDouble("buffered", 0.0)
            )
        }
    }

    /** Medios con sonido: un marco es dueño; otro sin medios no los borra. */
    private fun mediaState(session: CefriumEngineSession, state: State, frame: String, json: JSONObject) {
        val active = json.optBoolean("active")
        val owner = state.mediaFrame
        if (!active && owner != null && owner != frame) return
        state.mediaFrame = if (active) frame else null
        session.onMediaState(active, json.optBoolean("playing"), json.optString("title").take(200))
        TabManager.tabForSession(session)?.let { tab ->
            BrowserMediaController.updateDetails(
                tab.id,
                if (active) MediaDetails.fromJson(json, android.os.SystemClock.elapsedRealtime()) else null
            )
        }
    }

    /**
     * Controles del sistema (notificación, pantalla de bloqueo, isla dinámica): saltar, ±N s y
     * pista siguiente/anterior, solo al marco que tiene el audio.
     */
    fun sendMediaSessionCommand(session: EngineSession?, action: String, extra: JSONObject? = null): Boolean {
        val cefrium = cefrium(session) ?: return false
        val msg = JSONObject(extra?.toString() ?: "{}").put("type", "media_session_command").put("action", action)
        return send(cefrium, msg, states[cefrium]?.mediaFrame)
    }

    private fun captions(session: EngineSession, state: State, frame: String, json: JSONObject) {
        // Con la captura de Android la página no participa: un marco nuevo no la reinicia ni la corta.
        if (!LiveCaptions.isActive(session) || LiveCaptions.pageSuspended(session)) return
        val type = json.optString("type")
        if (type.startsWith("captions_")) LiveCaptions.onBridgeConnected(session)
        if (type == "captions_offer" && state.owner == null) {
            val score = json.optInt("score").coerceIn(0, 100_000_000)
            val pending = state.offers
            if (pending != null) {
                pending[frame] = maxOf(score, pending[frame] ?: 0)
            } else {
                val offers = mutableMapOf(frame to score)
                state.offers = offers
                main.postDelayed({ grant(session, state, offers) }, OFFER_WINDOW_MS)
            }
        }
        if (type == "captions_status" && json.optString("code") == "paused" && state.owner == null) {
            LiveCaptions.onStatus(session, "paused")
            return
        }
        if (state.owner != frame) return
        val context = AppContext.get()
        when (type) {
            "captions_release" -> {
                state.owner = null
                LiveCaptions.onRecordingStopped(session)
            }
            "captions_start" -> LiveCaptions.onRecordingStarted(context, session, json.optString("mime"))
            "captions_audio" -> LiveCaptions.onAudio(session, json.optString("data"))
            "captions_status" -> LiveCaptions.onStatus(session, json.optString("code"))
            "captions_stop" -> LiveCaptions.onRecordingStopped(session)
            "captions_error" -> json.optString("code").let { code ->
                LiveCaptions.onPageError(context, session, code, captionErrorText(code))
            }
            "captions_track" -> LiveCaptions.onTrack(session, json.optString("source", "track"))
            "captions_text" -> LiveCaptions.onDomText(context, session, json.optString("text").take(400))
            "captions_cues" -> json.optJSONArray("cues")?.let { LiveCaptions.onCues(context, session, it) }
            "captions_active" -> json.optJSONArray("ids")?.let { LiveCaptions.onActiveCues(context, session, it) }
        }
    }

    private fun grant(session: EngineSession, state: State, offers: Map<String, Int>) {
        if (state.offers !== offers) return
        state.offers = null
        if (!LiveCaptions.isActive(session) || state.owner != null) return
        val selected = offers.filterKeys { it in state.frames }.maxByOrNull { it.value }?.key ?: return
        state.owner = selected
        if (send(session as? CefriumEngineSession ?: return, JSONObject().put("type", "captions_grant"), selected)) {
            LiveCaptions.onVideoGranted(session)
        } else {
            state.owner = null
        }
    }

    private fun captionErrorText(code: String): String = when (code) {
        "no_video" -> "No hay un video en esta página."
        "suspended" -> "Toca el video una vez y vuelve a activar los subtítulos."
        "unsupported", "mime", "capture", "SecurityError", "NotSupportedError" -> "La página no deja grabar el audio de este reproductor."
        "no_audio" -> "El reproductor no entrega su audio a la página (otro origen, protección o reproductor especial)."
        "paused" -> "Esperando a que el video se reproduzca."
        "silent" -> "El video suena pero la página solo recibe silencio (otro origen o contenido protegido)."
        "need_key" -> "Este video no trae subtítulos de texto."
        else -> "No se pudo captar el audio del video."
    }

    // --- Hacia la página ---

    /** Envía [message] a los marcos de [session]: a todos (`null`), al principal (`top`) o a uno (su token). */
    private fun send(session: CefriumEngineSession, message: JSONObject, to: String? = null): Boolean {
        val state = states[session]
        val connected = when (to) {
            null -> state?.frames?.isNotEmpty() == true
            "top" -> state?.topConnected == true
            else -> state?.frames?.contains(to) == true
        }
        val event = JSONObject().put("event", "bridge").put("message", message)
        if (to != null) event.put("to", to)
        session.shield.channel.push(event.toString())
        return connected
    }

    private fun cefrium(session: EngineSession?) = session as? CefriumEngineSession

    fun setCaptions(session: EngineSession?, active: Boolean, live: Boolean, language: String, target: String): Boolean {
        val cefrium = cefrium(session) ?: return false
        if (!active) states[cefrium]?.let { it.owner = null; it.offers = null }
        return send(cefrium, JSONObject().apply {
            put("type", "captions")
            put("active", active)
            // Idioma de destino: si la página tiene un archivo de subtítulos en ese idioma, se prefiere.
            put("target", target.takeIf { it.matches(LANGUAGE) } ?: "")
            // live: hay clave de Deepgram; sin ella se usan la pista del video o el texto del reproductor.
            put("live", live)
            put("lang", language.takeIf { it.matches(LANGUAGE) || it == "multi" } ?: "multi")
        })
    }

    /** Tras un corte de red: grabación nueva (con cabecera) para la conexión nueva. */
    fun restartCaptions(session: EngineSession?): Boolean {
        val cefrium = cefrium(session) ?: return false
        val owner = states[cefrium]?.owner ?: return false
        return send(cefrium, JSONObject().put("type", "captions_restart"), owner)
    }

    fun restoreCaptions(session: EngineSession): Boolean {
        val cefrium = cefrium(session) ?: return false
        if (!LiveCaptions.isActive(session) || LiveCaptions.pageSuspended(session)) return false
        return send(cefrium, captionsOn(session))
    }

    private fun captionsOn(session: EngineSession) = JSONObject().apply {
        put("type", "captions")
        put("active", true)
        put("live", LiveCaptions.audioAllowed(session))
        put("target", BrowserPrefs.captionTranslateTo(AppContext.get()).takeIf { it.matches(LANGUAGE) } ?: "")
        put("lang", LiveCaptions.language(session).takeIf { it.matches(LANGUAGE) || it == "multi" } ?: "multi")
    }

    fun setPipMode(session: EngineSession?, active: Boolean, resumePlayback: Boolean, isolateCss: Boolean): Boolean {
        val cefrium = cefrium(session) ?: return false
        val state = state(cefrium)
        if (active) { if (state.pip == null) state.pip = PipState(resumePlayback, isolateCss) } else state.pip = null
        val pip = state.pip
        return send(cefrium, pipModeMessage(active, pip?.resume == true, pip?.isolate ?: isolateCss), "top")
    }

    private fun pipModeMessage(active: Boolean, resume: Boolean, isolate: Boolean) = JSONObject()
        .put("type", "pip_mode").put("active", active).put("resume", active && resume).put("isolate", isolate)

    fun sendPipCommand(session: EngineSession?, command: String): Boolean {
        val cefrium = cefrium(session) ?: return false
        val state = state(cefrium)
        state.pip?.let { if (command == "play" || command == "pause") state.pip = it.copy(resume = command == "play") }
        return send(cefrium, JSONObject().put("type", "pip_command").put("command", command), "top")
    }

    /** Envía una orden de Nexo Media Engine (velocidad, salto, play/pause o desplazamiento CC). */
    fun sendMediaCommand(session: EngineSession?, payload: JSONObject): Boolean {
        val cefrium = cefrium(session) ?: return false
        val msg = JSONObject(payload.toString()).put("type", "media_command")
        return send(cefrium, msg)
    }

    fun sendLifecycle(session: EngineSession?, foreground: Boolean): Boolean {
        val cefrium = cefrium(session) ?: return false
        return send(cefrium, JSONObject().put("type", "lifecycle").put("state", if (foreground) "foreground" else "background"))
    }

    /** El marco principal tiene el puente conectado (mini video, controles). */
    fun hasMediaBridge(session: EngineSession?): Boolean = cefrium(session)?.let { states[it]?.topConnected } == true

    /** Ajustes que dependen del navegador (reproducción en segundo plano, modo nocturno) a todas las pestañas. */
    fun sendBrowserState(context: Context) {
        TabManager.liveSessions().forEach { session ->
            cefrium(session)?.let { send(it, browserState(context, it)) }
        }
    }

    private fun browserState(context: Context, session: EngineSession): JSONObject {
        // La dirección ya confirmada del documento (la de la pestaña puede ir un paso por detrás al navegar).
        val url = (session as? CefriumEngineSession)?.currentLocationUrl() ?: TabManager.tabForSession(session)?.url
        val host = url?.let { SuggestionIndex.hostOf(it) }
        val policy = com.nexo.browser.dark.DarkEngine.policyFor(context, host)
        return JSONObject()
            .put("type", "browser_state")
            .put("accent", BrowserPrefs.accent(context))
            .put("theme", BrowserPrefs.theme(context))
            .put("showBadge", false)
            .put("backgroundMedia", BrowserPrefs.backgroundMedia(context))
            .put("smartPip", BrowserPrefs.smartPip(context))
            .put("night", policy.enabled)
            .put("nightException", policy.excluded)
            .put("nightBackground", if (policy.enabled) policy.backgroundHex else "")
            .put("darkStrategy", policy.strategy)
            .put("darkOled", policy.oled)
    }

    /**
     * Dark Reader en el marco principal: Nexo lo ejecuta desde fuera de la
     * página (sin depender de su CSP) y responde cuando ya está cargado.
     */
    private fun loadDarkReader(session: CefriumEngineSession, reply: (Any?) -> Unit) {
        val source = darkReader ?: runCatching {
            AppContext.get().assets.open(DARK_READER_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()?.also { darkReader = it }
        if (source == null) { reply(false); return }
        session.evaluateInMainFrame(source) { reply(true) }
    }

    /** Documento nuevo en el marco principal: sus marcos se vuelven a conectar. */
    fun onNewDocument(session: EngineSession) {
        TabManager.tabForSession(session)?.id?.let { com.nexo.browser.media.NexoMediaEngine.resetTab(it) }
        val state = states[session] ?: return
        val hadOwner = state.owner != null
        state.frames.clear()
        state.topConnected = false
        state.owner = null
        state.offers = null
        state.mediaFrame = null
        if (hadOwner) LiveCaptions.onRecordingStopped(session)
    }

    fun forget(session: EngineSession) {
        states.remove(session)
    }

    private val LANGUAGE = Regex("[a-zA-Z]{2,3}(-[A-Za-z]{2,4})?")
}
