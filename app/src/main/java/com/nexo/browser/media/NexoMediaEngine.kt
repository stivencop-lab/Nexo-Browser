package com.nexo.browser.media

import android.app.Activity
import android.app.Dialog
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
import android.widget.Button
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
        val captionOffsetMs: Int = 0
    )

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
        hasTextTracks: Boolean
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
            captionOffsetMs = siteCfg.captionOffsetMs
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
     * Capa ligera de gestos (brillo/volumen) y bloqueo táctil en pantalla completa.
     * Solo se monta cuando la página entra en pantalla completa y se retira al salir.
     */
    class FullscreenOverlay(
        private val activity: Activity,
        private val hostContainer: ViewGroup,
        private val onOpenPanel: () -> Unit
    ) {
        private var overlayRoot: FrameLayout? = null
        private var hudText: TextView? = null
        private var controlsBar: LinearLayout? = null
        private var lockButton: Button? = null
        private var locked = false
        private val hideHud = Runnable { hudText?.visibility = View.GONE }
        private val hideBar = Runnable { controlsBar?.visibility = View.GONE }

        fun setActive(fullscreen: Boolean) {
            if (!fullscreen) {
                detach()
                return
            }
            if (!MediaSitePrefs.fullscreenGestures(activity)) {
                detach()
                return
            }
            if (overlayRoot != null) return
            attach()
        }

        fun detach() {
            main.removeCallbacks(hideHud)
            main.removeCallbacks(hideBar)
            locked = false
            overlayRoot?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            overlayRoot = null
            hudText = null
            controlsBar = null
            lockButton = null
        }

        private fun dp(v: Float): Int = (v * activity.resources.displayMetrics.density).toInt()

        private fun attach() {
            val slop = ViewConfiguration.get(activity).scaledTouchSlop
            val audio = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            var startX = 0f
            var startY = 0f
            var dragging: String? = null // "brightness" | "volume"
            var startBrightness = 0.5f
            var startVolume = 0
            var maxVolume = 15

            val root = object : FrameLayout(activity) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (locked) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) showControlsTemporarily()
                        return true
                    }
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = event.x
                            startY = event.y
                            dragging = null
                            val lp = activity.window.attributes
                            startBrightness = if (lp.screenBrightness in 0.05f..1.0f) lp.screenBrightness else 0.5f
                            maxVolume = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
                            startVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                            // Dejar pasar toques simples al reproductor web salvo que sea arrastre vertical en borde.
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - startX
                            val dy = startY - event.y
                            if (dragging == null && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.6f) {
                                dragging = if (startX < width / 2f) "brightness" else "volume"
                            }
                            val mode = dragging ?: return false
                            val range = (height * 0.55f).coerceAtLeast(180f)
                            val fraction = dy / range
                            if (mode == "brightness") {
                                val next = (startBrightness + fraction).coerceIn(0.05f, 1.0f)
                                val attrs = activity.window.attributes
                                attrs.screenBrightness = next
                                activity.window.attributes = attrs
                                showHud("Brillo ${(next * 100).roundToInt()}%")
                            } else if (audio != null) {
                                val nextVol = (startVolume + fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                                showHud("Volumen ${(nextVol * 100 / maxVolume)}%")
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val wasDrag = dragging != null
                            dragging = null
                            return wasDrag
                        }
                    }
                    return false
                }

                override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                    if (locked) {
                        val bar = controlsBar
                        if (bar != null && bar.visibility == View.VISIBLE &&
                            event.y >= bar.top && event.y <= bar.bottom &&
                            event.x >= bar.left && event.x <= bar.right
                        ) return false
                        return true
                    }
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        startX = event.x
                        startY = event.y
                        dragging = null
                        val lp = activity.window.attributes
                        startBrightness = if (lp.screenBrightness in 0.05f..1.0f) lp.screenBrightness else 0.5f
                        maxVolume = (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)
                        startVolume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                        return false
                    }
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                        val dx = event.x - startX
                        val dy = startY - event.y
                        // Activar gesto vertical solo en los laterales (30% izquierdo / derecho) para no interferir con el centro.
                        val onEdge = startX < width * 0.30f || startX > width * 0.70f
                        if (onEdge && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.6f) {
                            dragging = if (startX < width / 2f) "brightness" else "volume"
                            return true
                        }
                    }
                    return false
                }
            }

            val hud = TextView(activity).apply {
                visibility = View.GONE
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                background = GradientDrawable().apply {
                    setColor(0xC0000000.toInt())
                    cornerRadius = dp(18f).toFloat()
                }
            }
            root.addView(hud, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))

            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                background = GradientDrawable().apply {
                    setColor(0xB8000000.toInt())
                    cornerRadius = dp(22f).toFloat()
                }
            }

            fun miniBtn(label: String, onClick: () -> Unit): Button = Button(activity).apply {
                text = label
                isAllCaps = false
                textSize = 12.5f
                setTextColor(Color.WHITE)
                minWidth = 0
                minimumWidth = 0
                minHeight = dp(36f)
                minimumHeight = dp(36f)
                setPadding(dp(12f), 0, dp(12f), 0)
                background = null
                setOnClickListener { onClick() }
            }

            val lockBtn = miniBtn("Bloquear") {
                locked = !locked
                lockButton?.text = if (locked) "Desbloquear" else "Bloquear"
                showHud(if (locked) "Controles bloqueados" else "Controles desbloqueados")
                showControlsTemporarily()
            }
            val panelBtn = miniBtn("Media") {
                if (!locked) onOpenPanel()
            }
            bar.addView(lockBtn)
            bar.addView(panelBtn)

            root.addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(14f)
                marginEnd = dp(14f)
            })

            overlayRoot = root
            hudText = hud
            controlsBar = bar
            lockButton = lockBtn
            hostContainer.addView(
                root,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            showControlsTemporarily()
        }

        private fun showHud(text: String) {
            val hud = hudText ?: return
            hud.text = text
            hud.visibility = View.VISIBLE
            main.removeCallbacks(hideHud)
            main.postDelayed(hideHud, 1200L)
        }

        private fun showControlsTemporarily() {
            val bar = controlsBar ?: return
            bar.visibility = View.VISIBLE
            main.removeCallbacks(hideBar)
            main.postDelayed(hideBar, 3500L)
        }
    }
}
