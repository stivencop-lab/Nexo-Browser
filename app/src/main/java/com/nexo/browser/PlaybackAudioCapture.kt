package com.nexo.browser

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.*
import com.nexo.browser.engine.EngineSession

/** Explicit audio-only MediaProjection consent. Never uses a microphone AudioSource. */
object PlaybackAudioCapture {
    const val REQUEST = 9844
    private data class Request(val session: EngineSession, val token: Any, val at: Long = SystemClock.uptimeMillis())
    private var pending: Request? = null
    internal data class Grant(val session: EngineSession, val token: Any, val data: Intent)
    private var grant: Grant? = null
    internal var service: PlaybackCaptureService? = null
    fun awaitingConsent(): Boolean = pending != null

    /** [reason]: la página no pudo dar su audio; se explica antes de proponer la captura de Android. */
    fun request(activity: MainActivity, reason: String? = null) {
        if (Build.VERSION.SDK_INT < 29) {
            ToastMessage.show(activity, "La captura de reproducción requiere Android 10 o posterior."); return
        }
        val session = TabManager.activeSession()
        if (session == null) {
            ToastMessage.show(activity, "Abre una pestaña con video o audio primero."); return
        }
        if (LiveCaptions.usingDeviceAudio(session)) {
            ToastMessage.show(activity, "La captura universal de Android ya está activa. Apaga CC para detenerla."); return
        }
        if (BrowserPrefs.deepgramKey(activity).isBlank()) {
            ToastMessage.show(activity, "Configura tu clave de Deepgram para transcribir cualquier audio.")
            CaptionsUi.showSheet(activity, askKey = true)
            return
        }
        // Si CC todavía no estaba activo en esta pestaña, activarlo automáticamente en un toque.
        if (!LiveCaptions.isActive(session)) {
            LiveCaptions.toggle(activity, session)
        }
        val token = LiveCaptions.captureToken(session)
        if (token == null) {
            ToastMessage.show(activity, "No se pudo preparar los subtítulos en esta pestaña."); return
        }
        // Una solicitud anterior sin respuesta (diálogo cerrado por Android) no bloquea para siempre.
        pending?.let { if (SystemClock.uptimeMillis() - it.at < 60_000L) return else pending = null }

        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        val pills = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, NexoUi.dp(activity, 8f))
        }
        pills.addView(NexoUi.statusPill(activity, "Todo el audio", NexoUi.Tone.ACCENT))
        pills.addView(
            NexoUi.statusPill(activity, "Sin micrófono", NexoUi.Tone.SUCCESS),
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = NexoUi.dp(activity, 6f) }
        )
        body.addView(pills)

        if (!reason.isNullOrBlank()) {
            body.addView(NexoUi.note(activity, "Motivo detectado: $reason"))
        }
        body.addView(NexoUi.note(
            activity,
            "Android capturará directamente el audio multimedia que suene en el teléfono (videos protegidos por CORS, " +
                "iframes, reproductores web, streams y podcasts) sin depender de la página ni usar el micrófono.\n\n" +
                "Android pedirá autorización de audio y proyección de pantalla (Nexo solo lee el audio). " +
                "Se detiene automáticamente al apagar CC o cambiar de pestaña."
        ))

        body.addView(NexoUi.button(activity, "Activar captura universal", primary = true) {
            sheet.dismiss()
            if (!LiveCaptions.matchesCapture(session, token) || TabManager.activeSession() !== session ||
                !LiveCaptions.beginDeviceRequest(session, token)) {
                ToastMessage.show(activity, "Los subtítulos de esta pestaña cambiaron. Vuelve a elegir la captura desde CC.")
                return@button
            }
            val request = Request(session, token)
            pending = request
            activity.requestAndroidPermissions(arrayOf(Manifest.permission.RECORD_AUDIO)) { allowed ->
                if (pending !== request) return@requestAndroidPermissions
                if (activity.isFinishing || activity.isDestroyed) { abort(activity, request, null); return@requestAndroidPermissions }
                if (!allowed) {
                    abort(activity, request, "Sin el permiso de audio Android no deja capturar la reproducción. Actívalo en Ajustes › Apps › Nexo › Permisos.")
                    return@requestAndroidPermissions
                }
                if (!LiveCaptions.matchesCapture(session, token) || TabManager.activeSession() !== session) {
                    abort(activity, request, "Los subtítulos de esta pestaña cambiaron. Vuelve a intentarlo desde CC.")
                    return@requestAndroidPermissions
                }
                try {
                    val manager = activity.getSystemService(MediaProjectionManager::class.java)
                    // Android 14+: pantalla completa directamente para capturar audio de cualquier proceso de Chromium.
                    val intent = if (Build.VERSION.SDK_INT >= 34)
                        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    else manager.createScreenCaptureIntent()
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, REQUEST)
                } catch (_: Exception) {
                    abort(activity, request, "Android no pudo abrir la autorización de captura.")
                }
            }
        }, NexoUi.buttonParams(activity, 10f))

        body.addView(NexoUi.button(activity, if (reason != null) "Ahora no" else "Cancelar", primary = false) {
            sheet.dismiss()
        }, NexoUi.buttonParams(activity, 6f))

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(
                    activity,
                    if (reason != null) "Capturar audio con Android" else "Captura universal de audio",
                    "Escucha cualquier video, stream o reproductor"
                ),
                body
            )
        )
    }

    /** La solicitud termina sin captura: se explica en pantalla y la página vuelve a buscar texto. */
    private fun abort(activity: Activity, request: Request, message: String?) {
        if (pending === request) pending = null
        val text = message ?: "Captura de Android cancelada."
        if (LiveCaptions.matchesCapture(request.session, request.token)) LiveCaptions.endDeviceRequest(activity, request.session, text)
        if (message != null) ToastMessage.show(activity, message)
    }

    fun result(activity: Activity, resultCode: Int, data: Intent?) {
        val request = pending ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            abort(activity, request, "Captura de Android cancelada: Android no dio el permiso de captura."); return
        }
        if (!LiveCaptions.matchesCapture(request.session, request.token) || TabManager.activeSession() !== request.session) {
            abort(activity, request, "Los subtítulos de esta pestaña cambiaron mientras Android pedía permiso. Vuelve a intentarlo desde CC."); return
        }
        pending = null
        grant = Grant(request.session, request.token, data)
        try { activity.startForegroundService(Intent(activity, PlaybackCaptureService::class.java)) }
        catch (_: Exception) {
            grant = null
            LiveCaptions.onPageError(activity, request.session, "android_capture", "Android no permitió iniciar la captura. Vuelve a intentarlo con Nexo visible.")
        }
    }
    internal fun takeGrant(): Grant? = grant.also { grant = null }
    fun cancel(session: EngineSession) {
        if (pending?.session === session) pending = null
        if (grant?.session === session) grant = null
        service?.takeIf { it.session === session }?.finishCapture()
    }
}

private object ToastMessage {
    fun show(c: Context, text: String) = android.widget.Toast.makeText(c,text,android.widget.Toast.LENGTH_LONG).show()
}

/** Foreground lifetime is bound to one caption stream, not to a saved permission token. */
class PlaybackCaptureService : Service() {
    private data class CaptureSpec(val sampleRate: Int, val channelMask: Int, val channels: Int)

    internal var session: EngineSession? = null
        private set
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private var closing = false
    private val main = Handler(Looper.getMainLooper())
    // Si el video está sonando (lo actualiza el hilo principal cada segundo).
    @Volatile private var mediaPlaying = true
    private val playbackWatch = object : Runnable {
        override fun run() {
            val current = session ?: return
            mediaPlaying = TabManager.tabForSession(current)?.let { BrowserMediaController.isPlaying(it.id) } ?: true
            if (running) main.postDelayed(this, 1000L)
        }
    }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { fail("Android detuvo la autorización de captura. Puedes volver a activarla desde CC.") }
    }
    override fun onBind(intent: Intent?) = null

    /**
     * Abre [AudioRecord] probando primero el formato nativo del mezclador de Android
     * (48 kHz estéreo → 44.1 kHz estéreo → 48 kHz mono → 16 kHz mono).
     * En muchos teléfonos Android 11–16 solicitar 16 kHz mono directo falla o entrega ceros.
     */
    private fun openBestRecorder(config: AudioPlaybackCaptureConfiguration): Pair<AudioRecord, CaptureSpec> {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Permiso RECORD_AUDIO no concedido para la captura de reproducción")
        }
        val candidates = listOf(
            CaptureSpec(48000, AudioFormat.CHANNEL_IN_STEREO, 2),
            CaptureSpec(44100, AudioFormat.CHANNEL_IN_STEREO, 2),
            CaptureSpec(48000, AudioFormat.CHANNEL_IN_MONO, 1),
            CaptureSpec(PlaybackPcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, 1)
        )
        var lastErr: Throwable? = null
        for (spec in candidates) {
            val min = AudioRecord.getMinBufferSize(spec.sampleRate, spec.channelMask, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val chunkBytes = (spec.sampleRate / 10) * spec.channels * 2 // 100 ms
            val bufBytes = maxOf(min * 2, chunkBytes * 4)
            var audio: AudioRecord? = null
            try {
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(spec.sampleRate)
                    .setChannelMask(spec.channelMask)
                    .build()
                audio = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufBytes)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { audio.release() }
                    continue
                }
                audio.startRecording()
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    runCatching { audio.stop() }
                    runCatching { audio.release() }
                    continue
                }
                com.nexo.browser.engine.cefrium.EngineDiagnostics.log(
                    "captura Android iniciada: ${spec.sampleRate} Hz · ${if (spec.channels == 2) "estéreo" else "mono"}"
                )
                return audio to spec
            } catch (se: SecurityException) {
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
                throw se
            } catch (t: Throwable) {
                lastErr = t
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
            }
        }
        throw IllegalStateException("Ningún formato de AudioRecord fue aceptado", lastErr)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            session?.let { LiveCaptions.stop(it) }
            finishCapture()
            return START_NOT_STICKY
        }
        val grant = PlaybackAudioCapture.takeGrant()
        // Arrancado con startForegroundService: Android cierra la app si el servicio se detiene
        // sin haber pasado a primer plano. Se promueve antes de cualquier comprobación.
        if (!running && !promote()) {
            grant?.let { LiveCaptions.onPageError(this, it.session, "android_capture", "Android no permitió iniciar la captura. Vuelve a intentarlo con Nexo visible.") }
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < 29 || grant == null || running || !LiveCaptions.matchesCapture(grant.session,grant.token) || TabManager.activeSession() !== grant.session) {
            if (!running) {
                if (grant != null && LiveCaptions.isActive(grant.session))
                    LiveCaptions.onPageError(this, grant.session, "android_capture", "La pestaña cambió mientras Android iniciaba la captura. Vuelve a activarla desde CC.")
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            return START_NOT_STICKY
        }
        if (closing) { LiveCaptions.onPageError(this,grant.session,"android_capture","La captura anterior se está cerrando. Vuelve a autorizar desde CC."); stopSelf(); return START_NOT_STICKY }
        PlaybackAudioCapture.service = this
        session = grant.session
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            fail("Android no permitió capturar audio. Autoriza el permiso desde CC.")
            return START_NOT_STICKY
        }
        try {
            // Reafirmar política de captura permitida antes de abrir la pista.
            runCatching {
                getSystemService(AudioManager::class.java)
                    ?.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
            }
            val consent = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, grant.data)
                ?: error("Projection denied")
            projection = consent
            consent.registerCallback(callback, main)
            // Sin filtro por UID: el audio de Chromium puede salir de un proceso hijo con otro
            // UID y Android lo descartaba entero. Se capta todo el audio multimedia permitido.
            val config = AudioPlaybackCaptureConfiguration.Builder(consent)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val (audio, spec) = openBestRecorder(config)
            recorder = audio
            check(LiveCaptions.startDeviceAudio(grant.session, grant.token))
            running = true
            main.post(playbackWatch)
            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                // Bloques de 100 ms en el formato nativo del mezclador, remuestreados a 16 kHz mono PCM16.
                val rawChunkBytes = (spec.sampleRate / 10) * spec.channels * 2
                val buffer = ByteArray(rawChunkBytes)
                val gain = PlaybackPcm.Gain(target = 16000, maxGain = 12f, floor = 48)
                val queued = java.util.concurrent.atomic.AtomicInteger()
                var signal = false
                var playingSilenceMs = 0L
                var quietReported = false
                // Diagnóstico en pantalla: pico del último segundo (antes de la ganancia).
                var levelPeak = 0
                var levelBytes = 0
                try {
                    while (running) {
                        val size = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (size < 0) error("AudioRecord read failed ($size)")
                        if (size == 0) continue
                        val pcm16k = PlaybackPcm.to16kMono(buffer, size, spec.sampleRate, spec.channels)
                        if (pcm16k.isEmpty()) continue
                        val chunkPeak = PlaybackPcm.peak(pcm16k)
                        levelPeak = maxOf(levelPeak, chunkPeak)
                        levelBytes += pcm16k.size
                        if (levelBytes >= PlaybackPcm.SAMPLE_RATE * 2) {
                            val peak = levelPeak
                            val playing = mediaPlaying
                            levelPeak = 0
                            levelBytes = 0
                            main.post {
                                if (running && LiveCaptions.matchesCapture(grant.session, grant.token)) {
                                    LiveCaptions.onDeviceLevel(grant.session, peak, playing)
                                }
                            }
                        }
                        val heard = PlaybackPcm.hasWeakSignal(pcm16k, floor = 24)
                        signal = signal || heard
                        if (chunkPeak > 16) gain.apply(pcm16k)
                        // Cola acotada: con el hilo principal ocupado se descarta audio en vez de acumularlo.
                        if (queued.incrementAndGet() > 80) queued.decrementAndGet()
                        else main.post {
                            queued.decrementAndGet()
                            if (running && LiveCaptions.matchesCapture(grant.session, grant.token)) {
                                LiveCaptions.onDeviceAudio(grant.session, pcm16k)
                            }
                        }
                        if (heard) {
                            playingSilenceMs = 0L
                            quietReported = false
                            continue
                        }
                        if (mediaPlaying) playingSilenceMs += pcm16k.size * 1000L / (PlaybackPcm.SAMPLE_RATE * 2)
                        if (!quietReported && playingSilenceMs > (if (signal) 30_000L else 8_000L)) {
                            quietReported = true
                            val code = if (signal) "quiet" else "device_quiet"
                            main.post { if (running) LiveCaptions.onStatus(grant.session, code) }
                        }
                        if (!signal && playingSilenceMs > 45_000L) {
                            main.post {
                                if (running) fail(
                                    "Android solo recibió silencio (nivel 0 %). Sube el volumen multimedia, " +
                                        "comprueba que el video no esté silenciado ni protegido con DRM, " +
                                        "o activa Subtítulos instantáneos de Android."
                                )
                            }
                            break
                        }
                    }
                } catch (err: Exception) {
                    com.nexo.browser.engine.cefrium.EngineDiagnostics.log("captura Android interrumpida", err)
                    main.post { if (running) fail("Android no pudo seguir capturando el audio del reproductor.") }
                }
            }, "NexoPlaybackCapture").start()
        } catch (err: Exception) {
            com.nexo.browser.engine.cefrium.EngineDiagnostics.log("captura Android falló al iniciar", err)
            fail("No se pudo iniciar la captura de reproducción. Comprueba permisos y vuelve a autorizar con Nexo visible.")
        }
        return START_NOT_STICKY
    }

    /** Notificación de captura con "Detener"; false si Android no deja pasar a primer plano. */
    private fun promote(): Boolean = runCatching {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("caption-capture", "Captura para subtítulos", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, PlaybackCaptureService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "caption-capture")
            .setSmallIcon(R.drawable.ic_stat_nexo).setContentTitle("Nexo · captura universal de audio")
            .setContentText("Audio multimedia del dispositivo · transcripción en vivo")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Detener", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(947, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(947, notification)
    }.isSuccess

    private fun fail(message: String) {
        if (closing) return // ignore a revocation already queued before explicit stop
        val current = session
        finishCapture()
        if (current != null) LiveCaptions.onPageError(this, current, "android_capture", message)
    }

    internal fun finishCapture() {
        if (closing) return
        closing = true; running = false
        main.removeCallbacks(playbackWatch)
        val audio = recorder; recorder = null
        runCatching { audio?.stop() }; runCatching { audio?.release() }
        val consent = projection; projection = null
        runCatching { consent?.unregisterCallback(callback) }; runCatching { consent?.stop() }
        if (PlaybackAudioCapture.service === this) PlaybackAudioCapture.service = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() { finishCapture(); super.onDestroy() }
}
