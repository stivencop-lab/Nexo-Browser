package com.nexo.browser.media

import android.app.Activity
import android.app.Dialog
import android.content.pm.ActivityInfo
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.ExtensionManager
import com.nexo.browser.LiveCaptions
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.PageBridge
import com.nexo.browser.TabManager
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import com.nexo.browser.engine.EngineSession
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Nexo Media Engine: capa unificada de control multimedia avanzado sobre
 * reproductores HTML5 compatibles y reproductores reconocidos, sin sustituir
 * el reproductor del sitio.
 *
 * Incluye:
 * - Velocidad de reproducción (0.5x–2.5x), salto configurable (±5/10/15/30 s), play/pause.
 * - Gestos en pantalla completa (brillo a la izquierda, volumen a la derecha) + bloqueo de controles.
 * - Subtítulos inteligentes con desplazamiento temporal (`offsetMs`) sin alterar `video.currentTime`.
 * - Detección honesta de DRM/EME (informa limitaciones sin intentar eludir DRM).
 * - Preferencias por sitio persistidas y restauradas automáticamente al cambiar de video.
 */
object NexoMediaEngine {
    data class MediaSnapshot(
        val tabId: String,
        val present: Boolean = false,
        val playing: Boolean = false,
        val playbackRate: Float = 1.0f,
        val currentTimeSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val drmProtected: Boolean = false,
        val hasTextTracks: Boolean = false,
        val captionOffsetMs: Int = 0,
        val loop: Boolean = false,
        val bufferedAheadSec: Double = 0.0,
        /** `SystemClock.elapsedRealtime()` del informe: la página solo informa cambios. */
        val reportedAtMs: Long = 0L
    ) {
        /** Posición estimada ahora (extrapolada si se reproduce). */
        fun positionNow(nowMs: Long): Double {
            if (!playing || reportedAtMs <= 0L) return currentTimeSec
            val est = currentTimeSec + (nowMs - reportedAtMs).coerceAtLeast(0L) / 1000.0 * playbackRate
            return if (durationSec > 0) est.coerceAtMost(durationSec) else est
        }
    }

    data class TimedCue(val id: Int, val startSec: Double, val endSec: Double, val text: String)

    private val snapshots = ConcurrentHashMap<String, MediaSnapshot>()
    private val lastAppliedSite = ConcurrentHashMap<String, String>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun snapshot(tabId: String?): MediaSnapshot? = tabId?.let { snapshots[it] }

    /**
     * Selecciona líneas activas aplicando un desplazamiento de subtítulos [offsetMs]
     * sin modificar jamás `video.currentTime`. Puro: probado en la JVM.
     */
    fun selectCuesAtTime(cues: List<TimedCue>, mediaTimeSec: Double, offsetMs: Int): List<TimedCue> {
        if (cues.isEmpty() || !mediaTimeSec.isFinite()) return emptyList()
        val shifted = (mediaTimeSec + MediaSitePrefs.clampOffsetMs(offsetMs) / 1000.0).coerceAtLeast(0.0)
        return cues.filter { shifted >= it.startSec && shifted < it.endSec }.take(4)
    }

    /** Formatea un desplazamiento temporal en segundos con signo (ej. "+0.50 s", "0.00 s"). */
    fun formatOffset(offsetMs: Int): String {
        val clamped = MediaSitePrefs.clampOffsetMs(offsetMs)
        val sec = clamped / 1000.0
        return when {
            clamped > 0 -> String.format(java.util.Locale.US, "+%.2f s", sec)
            clamped < 0 -> String.format(java.util.Locale.US, "%.2f s", sec)
            else -> "0.00 s"
        }
    }

    fun onVideoState(
        context: Context,
        tabId: String,
        session: EngineSession,
        host: String?,
        present: Boolean,
        playing: Boolean,
        playbackRate: Float,
        currentTimeSec: Double,
        durationSec: Double,
        drmProtected: Boolean,
        hasTextTracks: Boolean,
        loop: Boolean = false,
        bufferedAheadSec: Double = 0.0
    ) {
        val prev = snapshots[tabId]
        val cleanRate = MediaSitePrefs.clampSpeed(playbackRate)
        val siteCfg = MediaSitePrefs.forSite(context, host)
        val next = MediaSnapshot(
            tabId = tabId,
            present = present,
            playing = playing,
            playbackRate = cleanRate,
            currentTimeSec = if (currentTimeSec.isFinite() && currentTimeSec >= 0.0) currentTimeSec else 0.0,
            durationSec = if (durationSec.isFinite() && durationSec >= 0.0) durationSec else 0.0,
            drmProtected = drmProtected,
            hasTextTracks = hasTextTracks,
            captionOffsetMs = siteCfg.captionOffsetMs,
            loop = loop,
            bufferedAheadSec = if (bufferedAheadSec.isFinite() && bufferedAheadSec > 0) bufferedAheadSec else 0.0,
            reportedAtMs = android.os.SystemClock.elapsedRealtime()
        )
        snapshots[tabId] = next

        // Aplicar preferencias guardadas del sitio una vez cuando aparece un video nuevo.
        val siteKey = MediaSitePrefs.normalizeHost(host)
        if (present && siteKey.isNotEmpty() && (prev == null || !prev.present || lastAppliedSite[tabId] != siteKey)) {
            lastAppliedSite[tabId] = siteKey
            if (siteCfg.speed != 1.0f && abs(cleanRate - siteCfg.speed) > 0.04f) {
                sendSpeed(session, siteCfg.speed)
            }
            if (siteCfg.captionOffsetMs != 0) {
                sendCaptionOffset(session, siteCfg.captionOffsetMs)
            }
        }
    }

    fun resetTab(tabId: String) {
        snapshots.remove(tabId)
        lastAppliedSite.remove(tabId)
    }

    fun sendPlayPause(session: EngineSession?, command: String = "toggle"): Boolean =
        PageBridge.sendMediaCommand(session, JSONObject().put("action", command))

    fun sendSeekRelative(session: EngineSession?, deltaSeconds: Int): Boolean =
        PageBridge.sendMediaCommand(session, JSONObject().put("action", "seek_relative").put("delta", deltaSeconds))

    fun sendSpeed(session: EngineSession?, speed: Float): Boolean {
        val clamped = MediaSitePrefs.clampSpeed(speed)
        return PageBridge.sendMediaCommand(session, JSONObject().put("action", "set_speed").put("speed", clamped.toDouble()))
    }

    fun sendSeekTo(session: EngineSession?, seconds: Double): Boolean =
        PageBridge.sendMediaCommand(session, JSONObject().put("action", "seek_to").put("time", seconds.coerceAtLeast(0.0)))

    /** Velocidad temporal mientras se mantiene pulsado (se deshace al soltar). */
    fun sendHoldSpeed(session: EngineSession?, active: Boolean, speed: Float = MediaGestures.HOLD_SPEED): Boolean =
        PageBridge.sendMediaCommand(session, JSONObject().put("action", "hold_speed").put("active", active).put("speed", speed.toDouble()))

    fun sendFit(session: EngineSession?, fit: MediaGestures.Fit): Boolean =
        PageBridge.sendMediaCommand(session, JSONObject().put("action", "set_fit").put("fit", fit.css))

    fun sendLoop(session: EngineSession?, loop: Boolean): Boolean =
        PageBridge.sendMediaCommand(session, JSONObject().put("action", "set_loop").put("loop", loop))

    fun sendCaptionOffset(session: EngineSession?, offsetMs: Int): Boolean {
        val clamped = MediaSitePrefs.clampOffsetMs(offsetMs)
        return PageBridge.sendMediaCommand(session, JSONObject().put("action", "set_caption_offset").put("offsetMs", clamped))
    }

    /** Hoja nativa de Nexo Media Engine: velocidad, salto, subtítulos y preferencias por sitio. */
    fun showSheet(activity: Activity, onCaptionsChanged: () -> Unit = {}) {
        val tab = TabManager.activeTab() ?: return
        val session = tab.session
        val host = MediaSitePrefs.normalizeHost(tab.url)
        val snap = snapshot(tab.id)
        var siteCfg = MediaSitePrefs.forSite(activity, host)
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)

        if (snap?.drmProtected == true) {
            body.addView(NexoUi.note(activity,
                "Contenido protegido por DRM/EME detectado: los controles básicos y PiP dependen de las políticas del sitio; " +
                    "la captura de audio por página no está disponible por diseño."))
        }

        // --- Controles rápidos de reproducción ---
        body.addView(NexoUi.section(activity, "Control de reproducción"))
        val quickRow = NexoUi.chipRow(activity, listOf(
            NexoUi.chip(activity, "-${siteCfg.seekStepSec} s", false) {
                sendSeekRelative(session, -siteCfg.seekStepSec)
            },
            NexoUi.chip(activity, if (snap?.playing == true) "Pausar" else "Reproducir", true) {
                sendPlayPause(session, "toggle")
                sheet.dismiss()
            },
            NexoUi.chip(activity, "+${siteCfg.seekStepSec} s", false) {
                sendSeekRelative(session, siteCfg.seekStepSec)
            }
        ))
        body.addView(quickRow)
        if (snap?.present == true && snap.durationSec > 0) {
            val now = android.os.SystemClock.elapsedRealtime()
            val pos = snap.positionNow(now)
            body.addView(TextView(activity).apply {
                text = buildString {
                    append(com.nexo.browser.MediaNotificationLayout.formatTime(pos))
                    append(" / ")
                    append(com.nexo.browser.MediaNotificationLayout.formatTime(snap.durationSec))
                    if (snap.bufferedAheadSec >= 1) append(" · ${snap.bufferedAheadSec.toInt()} s en búfer")
                }
                textSize = 13f
                typeface = NexoUi.regular(activity)
                setTextColor(ThemeManager.palette(activity).muted)
                setPadding(0, NexoUi.dp(activity, 8f), 0, 0)
            })
        }
        body.addView(NexoUi.chipRow(activity, listOf(
            NexoUi.chip(activity, "Desde el inicio", false) { sendSeekTo(session, 0.0) },
            NexoUi.chip(activity, if (snap?.loop == true) "Repetir: sí" else "Repetir: no", snap?.loop == true) {
                sendLoop(session, snap?.loop != true)
                sheet.dismiss()
            },
            NexoUi.chip(activity, "Mini video", false) {
                sheet.dismiss()
                (activity as? com.nexo.browser.MainActivity)?.startMiniPlayer()
            }
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = NexoUi.dp(activity, 8f) })

        // --- Velocidad ---
        body.addView(NexoUi.section(activity, "Velocidad de reproducción"))
        val speedHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var currentSpeed = snap?.playbackRate ?: siteCfg.speed
        fun renderSpeeds() {
            speedHolder.removeAllViews()
            speedHolder.addView(NexoUi.chipRow(activity, MediaSitePrefs.SPEEDS.map { spd ->
                val label = if (spd == 1.0f) "1x" else "${spd}x"
                NexoUi.chip(activity, label, abs(currentSpeed - spd) < 0.04f) {
                    currentSpeed = spd
                    sendSpeed(session, spd)
                    if (host.isNotEmpty()) {
                        siteCfg = siteCfg.copy(speed = spd)
                        MediaSitePrefs.saveSite(activity, host, siteCfg)
                    }
                    renderSpeeds()
                }
            }))
        }
        renderSpeeds()
        body.addView(speedHolder)

        // --- Salto personalizable ---
        body.addView(NexoUi.section(activity, "Salto rápido (adelantar / retroceder)"))
        val seekHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderSeekSteps() {
            seekHolder.removeAllViews()
            seekHolder.addView(NexoUi.chipRow(activity, MediaSitePrefs.SEEK_STEPS.map { sec ->
                NexoUi.chip(activity, "${sec} s", siteCfg.seekStepSec == sec) {
                    siteCfg = siteCfg.copy(seekStepSec = sec)
                    MediaSitePrefs.setDefaultSeekStep(activity, sec)
                    if (host.isNotEmpty()) MediaSitePrefs.saveSite(activity, host, siteCfg)
                    renderSeekSteps()
                }
            }))
        }
        renderSeekSteps()
        body.addView(seekHolder)

        // --- Desplazamiento y tipografía de subtítulos ---
        body.addView(NexoUi.section(activity, "Subtítulos inteligentes"))
        val captionsActive = LiveCaptions.isActive(session)
        if (activity is com.nexo.browser.MainActivity) {
            body.addView(NexoUi.actionRow(
                activity,
                com.nexo.browser.R.drawable.ic_menu_translate,
                if (captionsActive) "Subtítulos en vivo · activos" else "Activar subtítulos en vivo (CC)",
                if (captionsActive) LiveCaptions.sourceLabel(session) else "Pistas, reproductor, Deepgram o captura universal de Android"
            ) {
                sheet.dismiss()
                activity.openCaptionsFromMediaSheet()
            })
        }
        val offsetLabel = TextView(activity).apply {
            textSize = 13.5f
            setTextColor(ThemeManager.palette(activity).text)
        }
        fun renderOffset() {
            offsetLabel.text = "Desplazamiento temporal: ${formatOffset(siteCfg.captionOffsetMs)} (no altera la posición del video)"
        }
        renderOffset()
        body.addView(offsetLabel)
        body.addView(NexoUi.chipRow(activity, listOf(
            NexoUi.chip(activity, "-0.50 s", false) {
                siteCfg = siteCfg.copy(captionOffsetMs = MediaSitePrefs.clampOffsetMs(siteCfg.captionOffsetMs - 500))
                applyOffset(activity, host, session, siteCfg)
                renderOffset()
            },
            NexoUi.chip(activity, "-0.25 s", false) {
                siteCfg = siteCfg.copy(captionOffsetMs = MediaSitePrefs.clampOffsetMs(siteCfg.captionOffsetMs - 250))
                applyOffset(activity, host, session, siteCfg)
                renderOffset()
            },
            NexoUi.chip(activity, "0 s", siteCfg.captionOffsetMs == 0) {
                siteCfg = siteCfg.copy(captionOffsetMs = 0)
                applyOffset(activity, host, session, siteCfg)
                renderOffset()
            },
            NexoUi.chip(activity, "+0.25 s", false) {
                siteCfg = siteCfg.copy(captionOffsetMs = MediaSitePrefs.clampOffsetMs(siteCfg.captionOffsetMs + 250))
                applyOffset(activity, host, session, siteCfg)
                renderOffset()
            },
            NexoUi.chip(activity, "+0.50 s", false) {
                siteCfg = siteCfg.copy(captionOffsetMs = MediaSitePrefs.clampOffsetMs(siteCfg.captionOffsetMs + 500))
                applyOffset(activity, host, session, siteCfg)
                renderOffset()
            }
        )))

        val fontHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var currentFont = MediaSitePrefs.captionFont(activity)
        val fonts = listOf(MediaSitePrefs.FONT_SANS to "Sans", MediaSitePrefs.FONT_SERIF to "Serif", MediaSitePrefs.FONT_MONO to "Monospace")
        fun renderFonts() {
            fontHolder.removeAllViews()
            fontHolder.addView(NexoUi.chipRow(activity, fonts.map { (id, label) ->
                NexoUi.chip(activity, label, currentFont == id) {
                    currentFont = id
                    MediaSitePrefs.setCaptionFont(activity, id)
                    onCaptionsChanged()
                    renderFonts()
                }
            }))
        }
        renderFonts()
        body.addView(fontHolder)

        // --- Gestos en pantalla completa ---
        body.addView(NexoUi.section(activity, "Pantalla completa y gestos"))
        body.addView(NexoUi.switchRow(
            activity,
            "Gestos de brillo y volumen",
            "Desliza verticalmente a la izquierda (brillo) o derecha (volumen) durante pantalla completa.",
            MediaSitePrefs.fullscreenGestures(activity)
        ) { enabled ->
            MediaSitePrefs.setFullscreenGestures(activity, enabled)
        })
        body.addView(NexoUi.note(activity,
            "Doble toque a los lados: ∓${siteCfg.seekStepSec} s · Arrastra en horizontal para buscar · Mantén pulsado para 2×."))
        body.addView(NexoUi.switchRow(
            activity,
            "Girar automáticamente",
            "Al entrar en pantalla completa, orienta la pantalla según el video (horizontal o vertical).",
            MediaSitePrefs.autoRotate(activity)
        ) { enabled ->
            MediaSitePrefs.setAutoRotate(activity, enabled)
        })

        if (host.isNotEmpty() && !siteCfg.isDefault()) {
            body.addView(NexoUi.button(activity, "Restablecer preferencias de $host") {
                MediaSitePrefs.clearSite(activity, host)
                sendSpeed(session, 1.0f)
                sendCaptionOffset(session, 0)
                sheet.dismiss()
                Toastish.show(activity, "Preferencias multimedia restablecidas para $host.")
            }, NexoUi.buttonParams(activity, 10f))
        }

        val subtitle = buildString {
            if (host.isNotEmpty()) append(host).append(" · ")
            append(if (snap?.present == true) "Video detectado (${currentSpeed}x)" else "Sin video activo en esta pestaña")
        }
        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, "Nexo Media Engine", subtitle), body)
        )
    }

    private fun applyOffset(activity: Activity, host: String, session: EngineSession?, siteCfg: MediaSitePrefs.SiteConfig) {
        MediaSitePrefs.setDefaultCaptionOffsetMs(activity, siteCfg.captionOffsetMs)
        if (host.isNotEmpty()) MediaSitePrefs.saveSite(activity, host, siteCfg)
        sendCaptionOffset(session, siteCfg.captionOffsetMs)
    }

    /**
     * Capa del reproductor en pantalla completa: gestos (doble toque para saltar, arrastre
     * horizontal para buscar, brillo/volumen en los laterales, mantener pulsado para 2×),
     * HUD con nivel, barra de controles (bloquear, girar, ajuste de imagen, velocidad, panel)
     * y giro automático según la forma del video.
     *
     * Los toques llegan por [PullRefreshLayout.touchFilter] antes que la página: los que no
     * son un gesto pasan intactos al reproductor web; al empezar un gesto la página recibe
     * `ACTION_CANCEL` y el resto del gesto es de Nexo.
     */
    class FullscreenOverlay(
        private val activity: Activity,
        private val hostContainer: com.nexo.browser.PullRefreshLayout,
        private val onOpenPanel: () -> Unit
    ) {
        private var overlayRoot: FrameLayout? = null
        private var hud: LinearLayout? = null
        private var hudText: TextView? = null
        private var hudLevel: LevelBar? = null
        private var sideHud: TextView? = null
        private var controlsBar: LinearLayout? = null
        private var lockButton: TextView? = null
        private var fitButton: TextView? = null
        private var speedButton: TextView? = null
        private var locked = false
        private var fit = MediaGestures.Fit.CONTAIN
        private var savedOrientation: Int? = null
        private val hideHud = Runnable { hud?.visibility = View.GONE }
        private val hideSide = Runnable { sideHud?.visibility = View.GONE }
        private val hideBar = Runnable { if (!locked) controlsBar?.visibility = View.GONE else lockOnly() }

        // Estado del gesto en curso.
        private enum class Mode { NONE, CONTROLS, LOCKED, SEEK_TAP, HOLD, BRIGHTNESS, VOLUME, SCRUB }
        private var mode = Mode.NONE
        private var startX = 0f
        private var startY = 0f
        private var startZone = MediaGestures.Zone.PAGE
        private var startBrightness = 0.5f
        private var startVolume = 0
        private var maxVolume = 15
        private var scrubDelta = 0
        private var lastTapUpMs = 0L
        private var lastTapZone: MediaGestures.Zone? = null
        private var chainUntilMs = 0L
        private var chainZone: MediaGestures.Zone? = null
        private var chainSeconds = 0
        private var pass: ((MotionEvent) -> Boolean)? = null
        private var downEvent: MotionEvent? = null
        private val longPress = Runnable { startHold() }
        private val audio by lazy { activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
        private val slop by lazy { ViewConfiguration.get(activity).scaledTouchSlop.toFloat() }

        fun setActive(fullscreen: Boolean) {
            if (!fullscreen) {
                detach()
                return
            }
            if (overlayRoot != null) return
            attach()
        }

        fun detach() {
            main.removeCallbacks(hideHud)
            main.removeCallbacks(hideSide)
            main.removeCallbacks(hideBar)
            main.removeCallbacks(longPress)
            if (mode == Mode.HOLD) sendHoldSpeed(session(), false)
            if (fit != MediaGestures.Fit.CONTAIN) sendFit(session(), MediaGestures.Fit.CONTAIN)
            fit = MediaGestures.Fit.CONTAIN
            mode = Mode.NONE
            locked = false
            downEvent?.recycle()
            downEvent = null
            if (hostContainer.touchFilter != null) hostContainer.touchFilter = null
            savedOrientation?.let { runCatching { activity.requestedOrientation = it } }
            savedOrientation = null
            overlayRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }
            overlayRoot = null
            hud = null
            hudText = null
            hudLevel = null
            sideHud = null
            controlsBar = null
            lockButton = null
            fitButton = null
            speedButton = null
        }

        private fun dp(v: Float): Int = (v * activity.resources.displayMetrics.density).toInt()

        private fun session(): EngineSession? = TabManager.activeTab()?.session

        private fun gesturesOn() = MediaSitePrefs.fullscreenGestures(activity)

        private fun attach() {
            val root = FrameLayout(activity).apply {
                // No acepta toques: solo dibuja. Los toques los filtra el contenedor.
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val pill = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                visibility = View.GONE
                setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
                background = GradientDrawable().apply {
                    setColor(0xD9101114.toInt())
                    cornerRadius = dp(22f).toFloat()
                }
            }
            val hudLabel = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = NexoUi.medium(activity)
            }
            val level = LevelBar(activity).apply { visibility = View.GONE }
            pill.addView(hudLabel)
            pill.addView(level, LinearLayout.LayoutParams(dp(140f), dp(4f)).apply { topMargin = dp(10f) })
            root.addView(pill, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

            val side = TextView(activity).apply {
                visibility = View.GONE
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                typeface = NexoUi.semiBold(activity)
                setPadding(dp(22f), dp(16f), dp(22f), dp(16f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x66000000)
                }
            }
            root.addView(side, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))

            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
                background = GradientDrawable().apply {
                    setColor(0xC7101114.toInt())
                    cornerRadius = dp(24f).toFloat()
                }
            }
            fun barButton(label: String, description: String, onClick: () -> Unit): TextView = TextView(activity).apply {
                text = label
                contentDescription = description
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = NexoUi.medium(activity)
                gravity = Gravity.CENTER
                minWidth = dp(48f)
                minHeight = dp(44f)
                setPadding(dp(12f), 0, dp(12f), 0)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                    GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20f).toFloat() })
                setOnClickListener { onClick(); showControlsTemporarily() }
            }
            val lockBtn = barButton("Bloquear", "Bloquear los toques") { toggleLock() }
            val rotateBtn = barButton("Girar", "Girar la pantalla") { rotate() }
            val fitBtn = barButton(fit.label, "Ajuste de imagen") { cycleFit() }
            val speedBtn = barButton(speedLabel(), "Velocidad de reproducción") { cycleSpeed() }
            val panelBtn = barButton("Más", "Abrir el panel multimedia") { if (!locked) onOpenPanel() }
            listOf(lockBtn, rotateBtn, fitBtn, speedBtn, panelBtn).forEach { bar.addView(it) }
            root.addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(14f)
                marginEnd = dp(14f)
            })

            overlayRoot = root
            hud = pill
            hudText = hudLabel
            hudLevel = level
            sideHud = side
            controlsBar = bar
            lockButton = lockBtn
            fitButton = fitBtn
            speedButton = speedBtn
            hostContainer.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            hostContainer.touchFilter = { event, passThrough -> onTouch(event, passThrough) }
            autoRotate()
            showControlsTemporarily()
        }

        // ---------------------------------------------------------------- gestos

        private fun onTouch(event: MotionEvent, passThrough: (MotionEvent) -> Boolean): Boolean {
            val root = overlayRoot ?: return passThrough(event)
            pass = passThrough
            val now = android.os.SystemClock.uptimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(longPress)
                    downEvent?.recycle()
                    downEvent = MotionEvent.obtain(event)
                    startX = event.x
                    startY = event.y
                    scrubDelta = 0
                    if (onControls(event)) {
                        mode = Mode.CONTROLS
                        return passThrough(event)
                    }
                    if (locked) {
                        mode = Mode.LOCKED
                        showControlsTemporarily()
                        return true
                    }
                    mode = Mode.NONE
                    startZone = MediaGestures.zone(event.x, event.y, root.width, root.height)
                    if (!gesturesOn()) return passThrough(event)
                    if (MediaGestures.continuesSeek(startZone, now, lastTapUpMs, lastTapZone, chainUntilMs, chainZone)) {
                        mode = Mode.SEEK_TAP
                        seekTap(startZone, now)
                        return true
                    }
                    val lp = activity.window.attributes
                    startBrightness = if (lp.screenBrightness in 0.02f..1.0f) lp.screenBrightness else systemBrightness()
                    maxVolume = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
                    startVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    if (startZone != MediaGestures.Zone.PAGE) main.postDelayed(longPress, MediaGestures.LONG_PRESS_MS)
                    return passThrough(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    when (mode) {
                        Mode.CONTROLS -> return passThrough(event)
                        Mode.LOCKED, Mode.SEEK_TAP, Mode.HOLD -> return true
                        Mode.BRIGHTNESS, Mode.VOLUME, Mode.SCRUB -> {
                            updateDrag(event, root)
                            return true
                        }
                        Mode.NONE -> {
                            val dx = event.x - startX
                            val dy = startY - event.y
                            if (abs(dx) > slop || abs(dy) > slop) main.removeCallbacks(longPress)
                            if (!gesturesOn()) return passThrough(event)
                            val drag = MediaGestures.drag(startZone, dx, dy, slop)
                            if (drag == MediaGestures.Drag.NONE) return passThrough(event)
                            mode = when (drag) {
                                MediaGestures.Drag.BRIGHTNESS -> Mode.BRIGHTNESS
                                MediaGestures.Drag.VOLUME -> Mode.VOLUME
                                else -> Mode.SCRUB
                            }
                            cancelPage(event)
                            updateDrag(event, root)
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    val ended = mode
                    mode = Mode.NONE
                    when (ended) {
                        Mode.CONTROLS -> return passThrough(event)
                        Mode.NONE -> {
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                lastTapUpMs = now
                                lastTapZone = startZone
                                showControlsTemporarily()
                            }
                            return passThrough(event)
                        }
                        Mode.SCRUB -> if (event.actionMasked == MotionEvent.ACTION_UP && scrubDelta != 0) {
                            sendSeekRelative(session(), scrubDelta)
                        }
                        Mode.HOLD -> {
                            sendHoldSpeed(session(), false)
                            showHud("Velocidad normal")
                        }
                        Mode.SEEK_TAP -> {
                            lastTapUpMs = 0L
                            chainUntilMs = now + MediaGestures.SEEK_CHAIN_MS
                        }
                        else -> Unit
                    }
                    return true
                }
                else -> return if (mode == Mode.NONE || mode == Mode.CONTROLS) passThrough(event) else true
            }
        }

        /** El reproductor web pierde el gesto en curso (deja de arrastrar su barra, etc.). */
        private fun cancelPage(event: MotionEvent) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            runCatching { pass?.invoke(cancel) }
            cancel.recycle()
        }

        private fun onControls(event: MotionEvent): Boolean {
            val bar = controlsBar ?: return false
            if (bar.visibility != View.VISIBLE) return false
            return event.x >= bar.left && event.x <= bar.right && event.y >= bar.top && event.y <= bar.bottom
        }

        private fun systemBrightness(): Float =
            runCatching {
                android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f).coerceIn(0.02f, 1f)

        private fun updateDrag(event: MotionEvent, root: View) {
            val dx = event.x - startX
            val dy = startY - event.y
            when (mode) {
                Mode.BRIGHTNESS -> {
                    val fraction = dy / (root.height * 0.6f).coerceAtLeast(180f)
                    val next = MediaGestures.brightness(startBrightness, fraction)
                    val attrs = activity.window.attributes
                    attrs.screenBrightness = next
                    activity.window.attributes = attrs
                    showHud("Brillo ${(next * 100).roundToInt()} %", next)
                }
                Mode.VOLUME -> {
                    val am = audio ?: return
                    val fraction = dy / (root.height * 0.6f).coerceAtLeast(180f)
                    val next = MediaGestures.volume(startVolume, fraction, maxVolume)
                    if (next != am.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0) }
                    }
                    showHud(if (next == 0) "Silencio" else "Volumen ${next * 100 / maxVolume} %", next / maxVolume.toFloat())
                }
                Mode.SCRUB -> {
                    val snap = snapshot(TabManager.activeTab()?.id)
                    val duration = snap?.durationSec ?: 0.0
                    scrubDelta = MediaGestures.scrubSeconds(dx, root.width, duration)
                    val current = snap?.positionNow(android.os.SystemClock.elapsedRealtime()) ?: 0.0
                    val target = MediaGestures.scrubTarget(current, scrubDelta, duration)
                    val sign = if (scrubDelta >= 0) "+" else "−"
                    val label = buildString {
                        append(com.nexo.browser.MediaNotificationLayout.formatTime(target))
                        if (duration > 0) append(" / ").append(com.nexo.browser.MediaNotificationLayout.formatTime(duration))
                        append("\n").append(sign).append(abs(scrubDelta)).append(" s")
                    }
                    showHud(label, if (duration > 0) (target / duration).toFloat() else null)
                }
                else -> Unit
            }
        }

        private fun seekTap(zone: MediaGestures.Zone, now: Long) {
            val step = MediaSitePrefs.forSite(activity, TabManager.activeTab()?.url).seekStepSec
            val forward = zone == MediaGestures.Zone.RIGHT
            chainSeconds = if (now < chainUntilMs && chainZone == zone) chainSeconds + step else step
            chainZone = zone
            chainUntilMs = now + MediaGestures.SEEK_CHAIN_MS
            sendSeekRelative(session(), if (forward) step else -step)
            val side = sideHud ?: return
            side.text = if (forward) "$chainSeconds s  ›› " else " ‹‹  $chainSeconds s"
            (side.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.gravity = Gravity.CENTER_VERTICAL or if (forward) Gravity.END else Gravity.START
                lp.marginStart = dp(36f)
                lp.marginEnd = dp(36f)
                side.layoutParams = lp
            }
            side.visibility = View.VISIBLE
            side.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            main.removeCallbacks(hideSide)
            main.postDelayed(hideSide, MediaGestures.SEEK_CHAIN_MS + 150L)
        }

        private fun startHold() {
            if (mode != Mode.NONE || locked) return
            mode = Mode.HOLD
            downEvent?.let { cancelPage(it) }
            sendHoldSpeed(session(), true)
            overlayRoot?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showHud("2×  ››", null, sticky = true)
        }

        // ---------------------------------------------------------------- controles

        private fun toggleLock() {
            locked = !locked
            lockButton?.text = if (locked) "Desbloquear" else "Bloquear"
            lockButton?.contentDescription = if (locked) "Desbloquear los toques" else "Bloquear los toques"
            controlsBar?.let { bar ->
                for (i in 0 until bar.childCount) {
                    val child = bar.getChildAt(i)
                    child.visibility = if (!locked || child === lockButton) View.VISIBLE else View.GONE
                }
            }
            showHud(if (locked) "Pantalla bloqueada" else "Pantalla desbloqueada")
        }

        private fun lockOnly() {
            // Bloqueado: la barra se esconde igual; un toque la vuelve a mostrar.
            controlsBar?.visibility = View.GONE
        }

        private fun rotate() {
            if (savedOrientation == null) savedOrientation = activity.requestedOrientation
            val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        private fun autoRotate() {
            if (!MediaSitePrefs.autoRotate(activity)) return
            val tab = TabManager.activeTab() ?: return
            val (w, h) = com.nexo.browser.BrowserMediaController.videoAspect(tab.id)
            if (w <= 0 || h <= 0) return
            val wanted = when {
                w > h * 1.05f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                h > w * 1.05f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> return
            }
            savedOrientation = activity.requestedOrientation
            activity.requestedOrientation = wanted
        }

        private fun cycleFit() {
            fit = fit.next()
            fitButton?.text = fit.label
            sendFit(session(), fit)
            showHud(when (fit) {
                MediaGestures.Fit.CONTAIN -> "Imagen completa"
                MediaGestures.Fit.COVER -> "Rellenar pantalla (recorta)"
                MediaGestures.Fit.FILL -> "Estirar a la pantalla"
            })
        }

        private fun speedLabel(): String {
            val rate = snapshot(TabManager.activeTab()?.id)?.playbackRate ?: 1f
            return if (abs(rate - rate.roundToInt()) < 0.01f) "${rate.roundToInt()}×" else "${rate}×"
        }

        private fun cycleSpeed() {
            val current = snapshot(TabManager.activeTab()?.id)?.playbackRate ?: 1f
            val steps = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
            val idx = steps.indexOfFirst { abs(it - current) < 0.04f }
            val next = steps[(idx + 1).mod(steps.size)]
            sendSpeed(session(), next)
            speedButton?.text = if (abs(next - next.roundToInt()) < 0.01f) "${next.roundToInt()}×" else "${next}×"
            showHud("Velocidad ${speedButton?.text}")
        }

        private fun showHud(text: String, level: Float? = null, sticky: Boolean = false) {
            val pill = hud ?: return
            hudText?.text = text
            hudLevel?.let { bar ->
                bar.visibility = if (level != null) View.VISIBLE else View.GONE
                if (level != null) bar.setLevel(level)
            }
            pill.visibility = View.VISIBLE
            main.removeCallbacks(hideHud)
            if (!sticky) main.postDelayed(hideHud, 1100L)
        }

        private fun showControlsTemporarily() {
            val bar = controlsBar ?: return
            bar.visibility = View.VISIBLE
            main.removeCallbacks(hideBar)
            main.postDelayed(hideBar, 3500L)
        }
    }

    /** Barra de nivel del HUD (brillo, volumen, posición). */
    private class LevelBar(context: Context) : View(context) {
        private var level = 0f
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        fun setLevel(value: Float) {
            level = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            if (level > 0f) canvas.drawRoundRect(0f, 0f, width * level, height.toFloat(), r, r, fill)
        }
    }
}
