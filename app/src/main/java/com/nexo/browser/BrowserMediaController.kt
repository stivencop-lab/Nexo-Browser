package com.nexo.browser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nexo.browser.engine.EngineMedia

object BrowserMediaController {
    interface Listener {
        fun onBrowserMediaStateChanged()
    }

    data class Snapshot(
        val tabId: String,
        val title: String,
        val playing: Boolean,
        /** Metadatos, posición y acciones que informa la página (sin carátula ni artista en privado). */
        val details: MediaDetails? = null,
        /** Sitio que suena ("youtube.com"); vacío en privado. */
        val host: String = "",
        val isPrivate: Boolean = false
    )

    private class Entry(
        val mediaSession: EngineMedia,
        title: String,
        val isPrivate: Boolean,
        var playing: Boolean = false
    ) {
        /*
         * El título se muestra en la notificación (incluida la pantalla de
         * bloqueo). En pestañas privadas nunca se expone el título real.
         */
        var title: String = displayTitle(title, isPrivate)
            set(value) {
                field = displayTitle(value, isPrivate)
            }
    }

    private const val DEFAULT_TITLE = "Multimedia en Nexo"
    private const val PRIVATE_TITLE = "Reproducción en pestaña privada"

    private fun displayTitle(title: String, isPrivate: Boolean): String =
        when {
            isPrivate -> PRIVATE_TITLE
            title.isBlank() -> DEFAULT_TITLE
            else -> title
        }

    private data class VideoState(
        var present: Boolean = false,
        var playing: Boolean = false,
        var width: Int = 0,
        var height: Int = 0,
        /** Rectángulo del video dentro de la vista de página, en píxeles. */
        var rect: android.graphics.Rect? = null
    )

    private val entries = linkedMapOf<String, Entry>()
    private val details = mutableMapOf<String, MediaDetails>()
    private val videos = mutableMapOf<String, VideoState>()
    private val recentPlayback = mutableMapOf<String, Long>()

    /*
     * Ventana de protección para Home/PiP.
     * Durante esta ventana una pausa transitoria emitida por el sitio
     * no debe apagar la sesión del motor ni el foreground service.
     */
    private val playbackHoldUntil = mutableMapOf<String, Long>()

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private var listener: Listener? = null
    private var currentTabId: String? = null

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun markRecentlyPlaying(tabId: String) {
        recentPlayback[tabId] = now()
    }

    private fun hasActiveHold(tabId: String): Boolean {
        val until = playbackHoldUntil[tabId] ?: return false
        if (now() >= until) {
            playbackHoldUntil.remove(tabId)
            return false
        }
        return true
    }

    private fun cleanupExpiredHold(tabId: String) {
        if (hasActiveHold(tabId)) return

        playbackHoldUntil.remove(tabId)

        val entry = entries[tabId]
        if (
            entry != null &&
            !entry.playing &&
            videos[tabId]?.playing != true &&
            !entry.mediaSession.isActive
        ) {
            entries.remove(tabId)
            if (currentTabId == tabId) {
                currentTabId = null
            }
        }

        notifyChanged()
    }

    fun holdPlayback(
        tabId: String,
        durationMs: Long = 10_000L
    ) {
        val duration = durationMs.coerceAtLeast(1_000L)
        val until = now() + duration
        val previous = playbackHoldUntil[tabId] ?: 0L
        playbackHoldUntil[tabId] = maxOf(previous, until)

        main.postDelayed(
            { cleanupExpiredHold(tabId) },
            duration + 100L
        )

        notifyChanged()
    }

    fun clearPlaybackHold(tabId: String) {
        if (playbackHoldUntil.remove(tabId) != null) {
            notifyChanged()
        }
    }

    fun attach(value: Listener?) {
        listener = value
        listener?.onBrowserMediaStateChanged()
    }

    fun onActivated(
        tab: BrowserTab,
        media: EngineMedia
    ) {
        val previous = entries[tab.id]

        entries[tab.id] = Entry(
            mediaSession = media,
            title = tab.title.ifBlank { previous?.title ?: DEFAULT_TITLE },
            isPrivate = tab.isPrivate,
            playing = previous?.playing == true
        )

        currentTabId = tab.id
        notifyChanged()
    }

    fun onDeactivated(
        tabId: String,
        media: EngineMedia
    ) {
        val entry = entries[tabId]

        if (entry?.mediaSession === media) {
            if (hasActiveHold(tabId)) {
                entry.playing = false
            } else {
                entries.remove(tabId)
                if (currentTabId == tabId) {
                    currentTabId = null
                }
            }
        }

        notifyChanged()
    }

    fun onPlay(
        tab: BrowserTab,
        media: EngineMedia
    ) {
        val entry = entries[tab.id]

        if (entry == null || entry.mediaSession !== media) {
            entries[tab.id] = Entry(
                mediaSession = media,
                title = tab.title,
                isPrivate = tab.isPrivate,
                playing = true
            )
        } else {
            if (tab.title.isNotBlank()) {
                entry.title = tab.title
            }
            entry.playing = true
        }

        currentTabId = tab.id
        markRecentlyPlaying(tab.id)
        notifyChanged()
    }

    fun onPause(
        tabId: String,
        media: EngineMedia
    ) {
        entries[tabId]
            ?.takeIf { it.mediaSession === media }
            ?.playing = false

        notifyChanged()
    }

    fun onStop(
        tabId: String,
        media: EngineMedia
    ) {
        val entry = entries[tabId]

        if (entry?.mediaSession === media) {
            if (hasActiveHold(tabId)) {
                entry.playing = false
            } else {
                entries.remove(tabId)
                recentPlayback.remove(tabId)
                if (currentTabId == tabId) {
                    currentTabId = null
                }
            }
        }

        notifyChanged()
    }

    fun updateMetadata(
        tabId: String,
        media: EngineMedia,
        title: String?
    ) {
        val entry = entries[tabId] ?: return
        if (entry.mediaSession !== media) return

        if (!title.isNullOrBlank()) {
            entry.title = title
        }

        notifyChanged()
    }

    fun updateTabTitle(
        tabId: String,
        title: String
    ) {
        val entry = entries[tabId] ?: return

        if (title.isNotBlank()) {
            entry.title = title
        }

        if (entry.playing || hasActiveHold(tabId)) {
            notifyChanged()
        }
    }

    fun onVideoState(
        tabId: String,
        present: Boolean,
        playing: Boolean,
        width: Int,
        height: Int,
        rect: android.graphics.Rect? = null
    ) {
        val state = VideoState(
            present = present,
            playing = playing,
            width = width.coerceAtLeast(0),
            height = height.coerceAtLeast(0),
            rect = rect?.takeIf { it.width() > 0 && it.height() > 0 }
        )

        if (playing) {
            markRecentlyPlaying(tabId)
        }

        // El latido periódico de la página repite el mismo estado: no
        // reconstruir controles PiP ni la interfaz si nada cambió.
        if (videos.put(tabId, state) == state) return
        listener?.onBrowserMediaStateChanged()
    }

    /** Último rectángulo conocido del video (píxeles de la vista de página). */
    fun videoRect(tabId: String): android.graphics.Rect? =
        videos[tabId]?.rect?.let { android.graphics.Rect(it) }

    fun resetVideo(tabId: String) {
        videos.remove(tabId)
        com.nexo.browser.media.NexoMediaEngine.resetTab(tabId)
        listener?.onBrowserMediaStateChanged()
    }

    fun removeTab(tabId: String) {
        entries.remove(tabId)
        details.remove(tabId)
        videos.remove(tabId)
        recentPlayback.remove(tabId)
        playbackHoldUntil.remove(tabId)
        com.nexo.browser.media.NexoMediaEngine.resetTab(tabId)

        if (currentTabId == tabId) {
            currentTabId = null
        }

        notifyChanged()
    }

    fun isPlaying(tabId: String): Boolean =
        entries[tabId]?.playing == true

    fun isAnyPlaying(): Boolean =
        entries.values.any { it.playing }

    fun isVideoPresent(tabId: String): Boolean =
        videos[tabId]?.present == true

    fun isVideoPlaying(tabId: String): Boolean =
        videos[tabId]?.playing == true

    fun isPlaybackActive(tabId: String): Boolean =
        isPlaying(tabId) || isVideoPlaying(tabId)

    fun wasRecentlyPlaying(
        tabId: String,
        windowMs: Long = 6_000L
    ): Boolean {
        val last = recentPlayback[tabId] ?: return false
        return now() - last <= windowMs.coerceAtLeast(0L)
    }

    fun shouldKeepAlive(
        tabId: String,
        windowMs: Long = 6_000L
    ): Boolean =
        isPlaybackActive(tabId) ||
            hasActiveHold(tabId) ||
            wasRecentlyPlaying(tabId, windowMs)

    fun videoAspect(tabId: String): Pair<Int, Int> {
        val state = videos[tabId]
        return if (
            (state?.width ?: 0) > 0 &&
            (state?.height ?: 0) > 0
        ) {
            state!!.width to state.height
        } else {
            16 to 9
        }
    }

    /*
     * Solo reanuda automáticamente cuando existe una transición
     * explícitamente protegida por MainActivity.
     * Esto evita reactivar un contenido que el usuario pausó a mano.
     */
    fun resumeIfRecent(
        tabId: String,
        @Suppress("UNUSED_PARAMETER") windowMs: Long = 6_000L
    ): Boolean {
        if (!hasActiveHold(tabId)) {
            return false
        }

        val media = entries[tabId]?.mediaSession ?: return false

        main.post {
            runCatching {
                if (media.isActive) {
                    media.play()
                }
            }
        }

        return true
    }

    private fun snapshotFor(
        allowHold: Boolean
    ): Snapshot? {
        fun usable(id: String): Boolean =
            isPlaybackActive(id) ||
                (allowHold && hasActiveHold(id))

        val preferredId =
            currentTabId?.takeIf { id ->
                entries[id] != null && usable(id)
            }

        val selectedId =
            preferredId
                ?: entries.keys.lastOrNull { usable(it) }
                ?: return null

        val entry = entries[selectedId] ?: return null
        currentTabId = selectedId

        val tab = TabManager.allTabs().firstOrNull { it.id == selectedId }
        val host = if (entry.isPrivate) "" else com.nexo.browser.media.MediaSitePrefs.normalizeHost(tab?.url)
        return Snapshot(
            tabId = selectedId,
            title = if (entry.isPrivate) PRIVATE_TITLE else entry.title,
            playing = isPlaybackActive(selectedId),
            details = details[selectedId]?.let { if (entry.isPrivate) it.forPrivate() else it },
            host = host,
            isPrivate = entry.isPrivate
        )
    }

    fun snapshotForService(): Snapshot? =
        snapshotFor(allowHold = true) ?: pausedSnapshot()

    /**
     * En pausa la notificación sigue (como en cualquier reproductor) para poder reanudar desde
     * la pantalla de bloqueo o la isla dinámica; el servicio la retira pasado un tiempo.
     */
    private fun pausedSnapshot(): Snapshot? {
        val id = currentTabId?.takeIf { entries[it]?.mediaSession?.isActive == true }
            ?: entries.entries.lastOrNull { it.value.mediaSession.isActive }?.key
            ?: return null
        val entry = entries[id] ?: return null
        val tab = TabManager.allTabs().firstOrNull { it.id == id }
        return Snapshot(
            tabId = id,
            title = if (entry.isPrivate) PRIVATE_TITLE else entry.title,
            playing = false,
            details = details[id]?.let { if (entry.isPrivate) it.forPrivate() else it },
            host = if (entry.isPrivate) "" else com.nexo.browser.media.MediaSitePrefs.normalizeHost(tab?.url),
            isPrivate = entry.isPrivate
        )
    }

    /** Detalles de la página (null al dejar de sonar). Solo avisa si cambia algo visible. */
    fun updateDetails(tabId: String, value: MediaDetails?) {
        val previous = details[tabId]
        if (value == null) {
            if (details.remove(tabId) != null) notifyChanged()
            return
        }
        details[tabId] = value
        val visibleChange = previous == null ||
            previous.copy(positionSec = 0.0, reportedAtMs = 0L) != value.copy(positionSec = 0.0, reportedAtMs = 0L) ||
            kotlin.math.abs(previous.positionAt(value.reportedAtMs, isPlaybackActive(tabId)) - value.positionSec) > 1.5
        if (visibleChange && entries.containsKey(tabId)) notifyChanged()
    }

    private fun sessionFor(tabId: String) = TabManager.allTabs().firstOrNull { it.id == tabId }?.session

    private fun targetTab(): String? = currentTabId ?: entries.keys.lastOrNull()

    fun playCurrent() {
        val id = targetTab() ?: return
        playbackHoldUntil.remove(id)
        play(id)
    }

    fun seekTo(positionMs: Long) {
        val id = targetTab() ?: return
        val time = (positionMs.coerceAtLeast(0L) / 1000.0)
        PageBridge.sendMediaSessionCommand(sessionFor(id), "seekto", org.json.JSONObject().put("time", time))
        details[id]?.let { details[id] = it.copy(positionSec = time, reportedAtMs = now()) }
        notifyChanged()
    }

    fun seekBy(deltaSec: Int) {
        val id = targetTab() ?: return
        PageBridge.sendMediaSessionCommand(sessionFor(id), "seekby", org.json.JSONObject().put("delta", deltaSec))
    }

    fun skipNext() {
        val id = targetTab() ?: return
        PageBridge.sendMediaSessionCommand(sessionFor(id), "nexttrack")
    }

    fun skipPrevious() {
        val id = targetTab() ?: return
        PageBridge.sendMediaSessionCommand(sessionFor(id), "previoustrack")
    }

    /** Al cerrar la app desde Recientes: nada debe seguir sonando sin interfaz. */
    fun stopAll() {
        val all = entries.toMap()
        playbackHoldUntil.clear()
        recentPlayback.clear()
        all.forEach { (id, entry) ->
            entry.playing = false
            notifyUserPaused(id)
            main.post { runCatching { entry.mediaSession.pause() } }
        }
        notifyChanged()
    }

    fun play(tabId: String) {
        val entry = entries[tabId] ?: return
        main.post { runCatching { entry.mediaSession.play() } }
    }

    // La recuperación de PiP también debe respetar los botones de la notificación.
    private fun notifyUserPaused(tabId: String) {
        val session = TabManager.allTabs().firstOrNull { it.id == tabId }?.session
        ExtensionManager.sendPipCommand(session, "pause")
    }

    /** Pausa la reproducción de una pestaña concreta. */
    fun pause(tabId: String) {
        notifyUserPaused(tabId)
        val entry = entries[tabId] ?: return
        playbackHoldUntil.remove(tabId)
        recentPlayback.remove(tabId)
        main.post { runCatching { entry.mediaSession.pause() } }
    }

    fun pauseCurrent() {
        val id = currentTabId ?: entries.keys.lastOrNull() ?: return
        val entry = entries[id] ?: return
        notifyUserPaused(id)

        playbackHoldUntil.remove(id)
        recentPlayback.remove(id)
        entry.playing = false
        notifyChanged()

        main.post {
            runCatching {
                entry.mediaSession.pause()
            }
        }
    }

    fun stopCurrent() {
        val id = currentTabId ?: entries.keys.lastOrNull() ?: return
        val entry = entries[id] ?: return
        notifyUserPaused(id)

        playbackHoldUntil.remove(id)
        recentPlayback.remove(id)
        entry.playing = false
        notifyChanged()

        main.post {
            runCatching {
                entry.mediaSession.stop()
            }
        }
    }

    private fun notifyChanged() {
        listener?.onBrowserMediaStateChanged()

        val context = runCatching {
            AppContext.get()
        }.getOrNull() ?: return

        MediaPlaybackService.sync(context)
    }
}
