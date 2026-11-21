package com.nexo.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.nexo.browser.MediaNotificationLayout.Button

/**
 * Reproducción en segundo plano con controles del sistema.
 *
 * Publica una [MediaSession] con título, artista, carátula, duración y posición: así Android
 * muestra el reproductor moderno (barra de progreso arrastrable en el panel rápido y la pantalla
 * de bloqueo) y las superficies tipo «isla dinámica» que leen la sesión multimedia (Now Bar de
 * One UI, Live Alerts de ColorOS/OxygenOS, islas de HyperOS o apps de terceros).
 *
 * Al cerrar Nexo desde Recientes se pausa todo y se retira la notificación: antes quedaba
 * colgada porque el servicio sobrevivía a la tarea (`stopWithTask="false"`) sin atender
 * `onTaskRemoved`.
 */
class MediaPlaybackService : Service() {
    private var session: MediaSession? = null
    private var pausedSince = 0L
    private var artworkUrl = ""
    private var artwork: Bitmap? = null
    private var lastMetadataKey = ""
    private var foreground = false
    private var closed = false
    private val expirePaused = Runnable { refreshFromController() }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
        startRequested = false
        session = runCatching { createSession() }.getOrNull()

        /*
         * Android exige que cualquier Service iniciado mediante startForegroundService()
         * se promocione a foreground casi inmediatamente.
         */
        promoteToForeground(buildBootstrapNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH -> if (closed) {
                // Nuevo arranque antes de que terminara el cierre anterior: Android exige foreground ya.
                closed = false
                if (!foreground) promoteToForeground(buildBootstrapNotification())
            }
            ACTION_PLAY -> BrowserMediaController.playCurrent()
            ACTION_PAUSE -> BrowserMediaController.pauseCurrent()
            ACTION_STOP, ACTION_DISMISS -> {
                BrowserMediaController.stopCurrent()
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_REWIND -> BrowserMediaController.seekBy(-SEEK_STEP_SEC)
            ACTION_FORWARD -> BrowserMediaController.seekBy(SEEK_STEP_SEC)
            ACTION_NEXT -> BrowserMediaController.skipNext()
            ACTION_PREVIOUS -> BrowserMediaController.skipPrevious()
        }
        refreshFromController()
        return START_NOT_STICKY
    }

    /** El usuario cerró Nexo desde Recientes: nada debe seguir sonando ni quedar en la barra. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        runCatching { BrowserMediaController.stopAll() }
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacks(expirePaused)
        runCatching {
            session?.isActive = false
            session?.release()
        }
        session = null
        // Por si el sistema destruye el servicio sin pasar por stopForeground.
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        if (instance === this) instance = null
        startRequested = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        closed = true
        main.removeCallbacks(expirePaused)
        runCatching { session?.isActive = false }
        if (foreground) stopForeground(Service.STOP_FOREGROUND_REMOVE)
        foreground = false
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        stopSelf()
    }

    private fun createSession(): MediaSession = MediaSession(this, "Nexo").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = BrowserMediaController.playCurrent()
            override fun onPause() = BrowserMediaController.pauseCurrent()
            override fun onStop() {
                BrowserMediaController.stopCurrent()
                shutdown()
            }
            override fun onSeekTo(pos: Long) = BrowserMediaController.seekTo(pos)
            override fun onFastForward() = BrowserMediaController.seekBy(SEEK_STEP_SEC)
            override fun onRewind() = BrowserMediaController.seekBy(-SEEK_STEP_SEC)
            override fun onSkipToNext() = BrowserMediaController.skipNext()
            override fun onSkipToPrevious() = BrowserMediaController.skipPrevious()
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                when (action) {
                    CUSTOM_REWIND -> BrowserMediaController.seekBy(-SEEK_STEP_SEC)
                    CUSTOM_FORWARD -> BrowserMediaController.seekBy(SEEK_STEP_SEC)
                }
            }
        }, main)
        setSessionActivity(openAppIntent())
    }

    private fun refreshFromController() {
        main.removeCallbacks(expirePaused)
        if (closed) return
        val state = BrowserMediaController.snapshotForService()
        if (state == null || !BrowserPrefs.backgroundMedia(this)) {
            shutdown()
            return
        }
        if (state.playing) {
            pausedSince = 0L
        } else {
            val now = SystemClock.elapsedRealtime()
            if (pausedSince == 0L) pausedSince = now
            val left = PAUSED_LINGER_MS - (now - pausedSince)
            if (left <= 0L) {
                shutdown()
                return
            }
            main.postDelayed(expirePaused, left + 50L)
        }
        updateSession(state)
        promoteToForeground(buildNotification(state))
    }

    private fun updateSession(state: BrowserMediaController.Snapshot) {
        val media = session ?: return
        val details = state.details
        val now = SystemClock.elapsedRealtime()

        val wantedArt = details?.artworkUrl.orEmpty()
        if (wantedArt != artworkUrl) {
            artworkUrl = wantedArt
            artwork = null
            if (wantedArt.isNotEmpty()) {
                RemoteImageLoader.loadBitmap(wantedArt) { bitmap ->
                    if (artworkUrl == wantedArt && bitmap != null && instance === this) {
                        artwork = bitmap
                        lastMetadataKey = ""
                        refreshFromController()
                    }
                }
            }
        }
        val art = artwork ?: siteIcon(state)

        val subtitle = subtitleFor(state)
        val key = listOf(state.title, subtitle, details?.album, details?.durationSec, art?.generationId, art?.hashCode()).joinToString("|")
        if (key != lastMetadataKey) {
            lastMetadataKey = key
            media.setMetadata(MediaMetadata.Builder().apply {
                putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, state.title)
                putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                details?.album?.takeIf { it.isNotBlank() }?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
                // Sin duración (directos) Android oculta la barra de progreso.
                if (details != null && details.durationSec > 0) {
                    putLong(MediaMetadata.METADATA_KEY_DURATION, (details.durationSec * 1000).toLong())
                }
                art?.let {
                    putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                    putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
                }
            }.build())
        }

        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (details?.canSeek == true) {
            actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
        }
        if (details?.hasNext == true) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (details?.hasPrevious == true) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val position = details?.let { (it.positionAt(now, state.playing) * 1000).toLong() } ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
        media.setPlaybackState(PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                position,
                if (state.playing) (details?.rate ?: 1f) else 0f,
                now
            )
            .apply {
                // Android 13+ dibuja los botones desde la sesión: ±10 s donde no hay pista anterior/siguiente.
                if (details?.canSeek == true) {
                    if (!details.hasPrevious) addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_REWIND, "Retroceder 10 s", R.drawable.ic_media_replay_10).build())
                    if (!details.hasNext) addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_FORWARD, "Avanzar 10 s", R.drawable.ic_media_forward_10).build())
                }
            }
            .build())
        if (!media.isActive) media.isActive = true
    }

    private fun subtitleFor(state: BrowserMediaController.Snapshot): String = when {
        state.isPrivate -> "Nexo Browser"
        !state.details?.artist.isNullOrBlank() -> state.details!!.artist
        state.host.isNotBlank() -> state.host
        else -> "Nexo Browser"
    }

    private fun siteIcon(state: BrowserMediaController.Snapshot): Bitmap? =
        if (state.isPrivate || state.host.isBlank()) null
        else runCatching { SiteIconStore.cached("https://${state.host}/") }.getOrNull()

    private fun promoteToForeground(notification: Notification) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        foreground = true
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        200,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this,
        code,
        Intent(this, MediaPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildBootstrapNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nexo)
            .setContentTitle("Nexo Browser")
            .setContentText("Preparando reproducción…")
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun action(button: Button): Notification.Action {
        val (icon, label, intent) = when (button) {
            Button.PREVIOUS -> Triple(R.drawable.ic_media_previous, "Anterior", serviceIntent(ACTION_PREVIOUS, 205))
            Button.REWIND -> Triple(R.drawable.ic_media_replay_10, "Retroceder 10 s", serviceIntent(ACTION_REWIND, 203))
            Button.PLAY -> Triple(R.drawable.ic_pip_play, "Reproducir", serviceIntent(ACTION_PLAY, 207))
            Button.PAUSE -> Triple(R.drawable.ic_pip_pause, "Pausar", serviceIntent(ACTION_PAUSE, 201))
            Button.FORWARD -> Triple(R.drawable.ic_media_forward_10, "Avanzar 10 s", serviceIntent(ACTION_FORWARD, 204))
            Button.NEXT -> Triple(R.drawable.ic_media_next, "Siguiente", serviceIntent(ACTION_NEXT, 206))
            Button.CLOSE -> Triple(R.drawable.ic_menu_close, "Cerrar", serviceIntent(ACTION_STOP, 202))
        }
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, intent).build()
    }

    private fun buildNotification(state: BrowserMediaController.Snapshot): Notification {
        val layout = MediaNotificationLayout.layout(state.details, state.playing)
        val subtitle = subtitleFor(state)
        val art = artwork ?: siteIcon(state)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nexo)
            .setContentTitle(state.title)
            .setContentText(subtitle)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_DISMISS, 208))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setShowWhen(false)
            // El título solo en pantalla de bloqueo si el sistema lo pide por la sesión; la
            // notificación pública sigue siendo genérica.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_nexo)
                    .setContentTitle("Nexo Browser")
                    .setContentText(if (state.playing) "Reproduciendo multimedia" else "Reproducción en pausa")
                    .setCategory(Notification.CATEGORY_TRANSPORT)
                    .build()
            )
            .setOngoing(state.playing)
            .setOnlyAlertOnce(true)
        if (!state.isPrivate && !state.details?.artist.isNullOrBlank() && state.host.isNotBlank()) {
            builder.setSubText(state.host)
        }
        art?.let { builder.setLargeIcon(it) }
        layout.buttons.forEach { builder.addAction(action(it)) }
        val style = Notification.MediaStyle().setShowActionsInCompactView(*layout.compact)
        session?.sessionToken?.let { style.setMediaSession(it) }
        builder.setStyle(style)
        return builder.build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Reproducción multimedia", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Controles de audio y video de Nexo Browser"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
    }

    companion object {
        private const val CHANNEL_ID = "nexo_media_playback"
        const val NOTIFICATION_ID = 1907
        private const val ACTION_REFRESH = "nexo.media.REFRESH"
        private const val ACTION_PLAY = "nexo.media.PLAY"
        private const val ACTION_PAUSE = "nexo.media.PAUSE"
        private const val ACTION_STOP = "nexo.media.STOP"
        private const val ACTION_DISMISS = "nexo.media.DISMISS"
        private const val ACTION_REWIND = "nexo.media.REWIND"
        private const val ACTION_FORWARD = "nexo.media.FORWARD"
        private const val ACTION_NEXT = "nexo.media.NEXT"
        private const val ACTION_PREVIOUS = "nexo.media.PREVIOUS"
        private const val CUSTOM_REWIND = "nexo.rewind10"
        private const val CUSTOM_FORWARD = "nexo.forward10"
        private const val SEEK_STEP_SEC = 10

        /** En pausa los controles siguen disponibles este tiempo; luego se retiran solos. */
        private const val PAUSED_LINGER_MS = 10 * 60_000L

        private val main = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: MediaPlaybackService? = null

        @Volatile
        private var startRequested = false

        fun sync(context: Context) {
            val app = context.applicationContext
            val state = BrowserMediaController.snapshotForService()
            val enabled = BrowserPrefs.backgroundMedia(app)
            val current = instance

            if (current != null) {
                main.post { if (instance === current) current.refreshFromController() }
                return
            }
            // Una pausa sin servicio en marcha no lo arranca: solo la reproducción.
            if (state == null || !enabled || !state.playing) {
                if (!startRequested) runCatching { app.stopService(Intent(app, MediaPlaybackService::class.java)) }
                return
            }
            if (startRequested) return

            startRequested = true
            runCatching {
                app.startForegroundService(Intent(app, MediaPlaybackService::class.java).setAction(ACTION_REFRESH))
            }.onFailure { error ->
                startRequested = false
                android.util.Log.w("NexoMedia", "No se pudo iniciar servicio multimedia", error)
            }
            // Si el sistema rechazara silenciosamente la creación, permitir otro intento.
            main.postDelayed({ if (instance == null) startRequested = false }, 6_000L)
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val current = instance
            if (current != null) {
                main.post { if (instance === current) current.shutdown() }
            } else if (!startRequested) {
                runCatching { app.stopService(Intent(app, MediaPlaybackService::class.java)) }
            }
        }

        /** Al arrancar la app: una notificación que sobrevivió a un proceso muerto no debe quedarse. */
        fun clearStale(context: Context) {
            if (instance != null) return
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        }
    }
}
