package com.nexo.browser

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Botón CC, capa de subtítulos sobre el video y hoja de configuración. */
object CaptionsUi {
    val LANGUAGES = listOf(
        "multi" to "Automático", "es" to "Español", "en" to "English", "pt" to "Português",
        "fr" to "Français", "de" to "Deutsch", "it" to "Italiano", "nl" to "Nederlands", "pl" to "Polski",
        "ru" to "Русский", "uk" to "Українська", "tr" to "Türkçe", "hi" to "हिन्दी", "ja" to "日本語",
        "ko" to "한국어", "zh" to "中文", "id" to "Bahasa Indonesia", "vi" to "Tiếng Việt", "sv" to "Svenska"
    )
    /** Destino: "No traducir" y los 37 idiomas del traductor. */
    val TARGETS: List<Pair<String, String>> =
        listOf("" to "No traducir") + CaptionProtocol.TARGET_LANGUAGES.map { it.code to it.label }
    /** Los primeros que se ven como píldoras; el resto en "Más idiomas". */
    private val QUICK_TARGETS = listOf("es", "en", "pt", "fr", "de", "it", "ja", "zh")
    private val SIZES = listOf(10 to "Mínimo", 12 to "Pequeño", 13 to "Normal", 16 to "Grande", 19 to "Muy grande")

    // Estilo ya aplicado al texto: solo se cambia lo que cambió (cada cambio de fondo,
    // tamaño o líneas obliga a recolocar la pantalla, y eso con video resta fluidez).
    private var appliedStyle: String? = null
    private val backgrounds = HashMap<String, GradientDrawable>()

    private fun dp(activity: Activity, value: Float) = (value * activity.resources.displayMetrics.density).toInt()

    fun summary(activity: Activity): String {
        val language = LANGUAGES.firstOrNull { it.first == BrowserPrefs.captionLanguage(activity) }?.second ?: "Automático"
        val target = TARGETS.firstOrNull { it.first == BrowserPrefs.captionTranslateTo(activity) }?.second ?: "No traducir"
        return if (target == "No traducir") "Idioma: $language" else "$language → $target"
    }

    /**
     * Píldora compacta Liquid Glass (32 dp visuales dentro de un área táctil de 48 dp),
     * coherente con la mini-píldora del cromo inferior; usa el acento cuando está activa.
     */
    internal fun styleChip(
        activity: Activity,
        button: Button,
        active: Boolean,
        backdrop: NexoGlassBackdrop? = null,
        backdropSource: View? = null,
        smoked: Boolean = false
    ) {
        val styleKey = "${ThemeManager.themeKey(activity)}|$active|$smoked|${GlassPreferences.intensity.name}|${backdrop != null}"
        if (button.getTag(R.id.nexo_chip_style_key) == styleKey && button.background != null) return
        button.setTag(R.id.nexo_chip_style_key, styleKey)
        val p = ThemeManager.palette(activity)
        val insetY = dp(activity, 8f)
        val visual: android.graphics.drawable.Drawable = if (active) {
            ThemeManager.rounded(activity, p.accent, NexoTokens.PILL_RADIUS)
        } else {
            NexoGlassDrawable(activity, p, NexoTokens.PILL_RADIUS, smoked = smoked).also {
                if (backdrop != null && backdropSource != null) {
                    it.setWebBackdrop(backdrop, backdropSource, button)
                }
            }
        }
        val inset = android.graphics.drawable.InsetDrawable(visual, 0, insetY, 0, insetY)
        button.background = ThemeManager.ripple(
            activity,
            inset,
            NexoTokens.PILL_RADIUS,
            androidx.core.graphics.ColorUtils.setAlphaComponent(if (active) p.onAccent else p.text, 26)
        )
        button.backgroundTintList = null
        button.setTextColor(if (active) p.onAccent else p.text)
        button.stateListAnimator = null
        NexoGlass.float(button, p, if (p.isDark) 3f else 6f)
        button.contentDescription = if (button.id == R.id.miniPlayerButton) "Abrir mini reproductor"
            else if (active) "Subtítulos activos. Abrir opciones" else "Activar subtítulos en vivo"
    }

    /**
     * Coloca el texto sobre la parte baja del video con tamaño proporcionado al contenedor
     * (evitando subtítulos desproporcionados en videos pequeños o PiP) y siempre respetando
     * la barra de estado y el cromo inferior.
     */
    fun render(
        activity: Activity,
        view: TextView,
        frameWidth: Int,
        frameHeight: Int,
        videoRect: Rect?,
        display: CaptionDisplay?,
        pip: Boolean,
        hint: Boolean = false,
        /** Alto del cromo flotante que tapa el pie de la página (Liquid Split Chrome). */
        bottomObstruction: Int = 0,
        topObstruction: Int = 0
    ) {
        if (display == null || display.isEmpty || frameWidth <= 0 || frameHeight <= 0) {
            view.visibility = View.GONE
            return
        }
        val margin = dp(activity, if (pip) 6f else 8f)
        val position = if (pip || hint) BrowserPrefs.CAPTION_POS_VIDEO else BrowserPrefs.captionPosition(activity)
        // Área visible real: nunca detrás del cromo inferior ni de la barra de estado.
        val visibleTop = if (pip) 0 else topObstruction
        val visibleBottom = if (pip) frameHeight else (frameHeight - maxOf(bottomObstruction, dp(activity, 10f)))
        val safeBottom = visibleBottom.coerceAtLeast(visibleTop + dp(activity, 48f))
        val videoArea = if (!pip && videoRect != null && position == BrowserPrefs.CAPTION_POS_VIDEO) {
            Rect(videoRect).takeIf {
                it.intersect(0, visibleTop, frameWidth, safeBottom) &&
                    it.width() > dp(activity, 150f) && it.height() > dp(activity, 84f)
            }
        } else null
        val area = videoArea ?: Rect(0, visibleTop, frameWidth, safeBottom)

        val baseSize = BrowserPrefs.captionSize(activity).toFloat()
        // Limita la escala del sistema para que fuentes accesibles gigantes no tapen el 50 % del video.
        val fontScale = activity.resources.configuration.fontScale.coerceAtLeast(1f)
        val scaleCompensation = if (fontScale > 1.15f) 1.15f / fontScale else 1f
        val compactInline = !pip && videoArea != null && videoArea.height() < dp(activity, 250f)
        val textSize = when {
            hint -> (baseSize * 0.82f).coerceIn(10.5f, 13f)
            pip -> (baseSize * 0.66f).coerceIn(9.5f, 12f)
            compactInline -> (baseSize * 0.86f).coerceIn(10.5f, 15f)
            else -> (baseSize * scaleCompensation).coerceIn(10f, 22f)
        }
        val background = if (hint) "hint" else BrowserPrefs.captionBackground(activity)
        val color = if (BrowserPrefs.captionColor(activity) == "yellow" && !hint) 0xFFFFE45C.toInt() else Color.WHITE
        val lines = BrowserPrefs.captionLines(activity)
        val maxLines = when {
            pip -> minOf(lines, 2)
            display.secondary.isNotBlank() -> lines + 1
            else -> lines
        }
        val padH = dp(activity, when { pip -> 7f; compactInline -> 9f; else -> 10f })
        val padV = dp(activity, when { pip -> 3f; compactInline -> 4f; else -> 5f })
        val radiusDp = when { pip -> 6f; compactInline -> 7.5f; else -> 8.5f }
        val font = com.nexo.browser.media.MediaSitePrefs.captionFont(activity)
        val style = "$textSize|$background|$color|$maxLines|$hint|$font|$padH|$padV"
        if (style != appliedStyle || view.tag !== CaptionsUi) {
            appliedStyle = style
            view.tag = CaptionsUi
            view.textSize = textSize
            view.setPadding(padH, padV, padH, padV)
            view.setLineSpacing(0f, 1.12f)
            // El aviso de estado se distingue del subtítulo: cursiva y fondo más ligero.
            val fontStyle = if (hint) Typeface.ITALIC else Typeface.NORMAL
            view.typeface = when (font) {
                com.nexo.browser.media.MediaSitePrefs.FONT_SERIF -> Typeface.create(Typeface.SERIF, fontStyle)
                com.nexo.browser.media.MediaSitePrefs.FONT_MONO -> Typeface.create(Typeface.MONOSPACE, fontStyle)
                else -> Typeface.create("sans-serif-medium", fontStyle)
            }
            view.setTextColor(color)
            // Sin fondo, un contorno oscuro marcado mantiene el texto legible sobre cualquier imagen.
            if (background == "none") view.setShadowLayer(dp(activity, 2.2f).toFloat(), 0f, 0f, 0xF0000000.toInt())
            else view.setShadowLayer(2.5f, 0f, 1f, 0x99000000.toInt())
            view.background = if (background == "none") null else backgrounds.getOrPut("$background|$radiusDp") {
                GradientDrawable().apply {
                    setColor(when (background) { "hint" -> 0x94000000.toInt(); "soft" -> 0x62000000; else -> 0xB2000000.toInt() })
                    cornerRadius = dp(activity, radiusDp).toFloat()
                }
            }
            view.maxLines = maxLines
        }
        val text = SpannableStringBuilder()
        if (display.primary.isNotBlank()) text.append(display.primary)
        if (display.pending.isNotBlank()) {
            if (text.isNotEmpty()) text.append(' ')
            val start = text.length
            text.append(display.pending)
            // Lo que aún se está transcribiendo o traduciendo, algo más tenue.
            text.setSpan(ForegroundColorSpan(0xC8E5E7EB.toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (display.secondary.isNotBlank() && !pip) {
            // Modo "ambos": el original debajo, más pequeño.
            text.append('\n')
            val start = text.length
            text.append(display.secondary)
            text.setSpan(ForegroundColorSpan(0xB3CBD5E1.toInt()), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text

        val maxWidth = minOf(area.width() - margin * 2, dp(activity, if (pip) 240f else 336f)).coerceAtLeast(dp(activity, 112f))
        view.maxWidth = maxWidth
        view.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val width = view.measuredWidth
        val height = view.measuredHeight
        val maxX = (frameWidth - width - margin).coerceAtLeast(margin)
        val maxY = (safeBottom - height - margin).coerceAtLeast(visibleTop + margin)
        val minY = (area.top + margin).coerceAtMost(maxY)
        view.translationX = (area.centerX() - width / 2f).coerceIn(margin.toFloat(), maxX.toFloat())
        val maxVisibleY = (area.bottom - height - margin).coerceAtLeast(minY).coerceAtMost(maxY)
        val y = when (position) {
            BrowserPrefs.CAPTION_POS_TOP -> (area.top + dp(activity, 10f)).toFloat()
            BrowserPrefs.CAPTION_POS_CENTER -> area.centerY() - height / 2f
            BrowserPrefs.CAPTION_POS_CUSTOM -> area.top + (area.height() - height) * BrowserPrefs.captionCustomY(activity)
            else -> (area.bottom - height - dp(activity, if (pip) 4f else 10f)).toFloat()
        }
        // Mientras se arrastra, la posición la marca el dedo.
        if (view.getTag(R.id.nexo_caption_dragging) != true) {
            view.translationY = y.coerceIn(minY.toFloat(), maxVisibleY.toFloat())
        }
        view.setTag(R.id.nexo_caption_area, intArrayOf(area.top, area.bottom))
        view.visibility = View.VISIBLE
    }

    /**
     * Arrastrar el subtítulo con el dedo: al soltarlo queda en modo «Donde lo arrastre» con su
     * altura relativa guardada. Un toque sin movimiento no hace nada (no tapa el video).
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun enableDrag(activity: Activity, view: TextView, onMoved: () -> Unit) {
        val slop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
        var startY = 0f
        var startTranslation = 0f
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY; startTranslation = v.translationY; dragging = false; true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (!dragging && kotlin.math.abs(dy) > slop) {
                        dragging = true
                        v.setTag(R.id.nexo_caption_dragging, true)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) v.translationY = startTranslation + dy
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.setTag(R.id.nexo_caption_dragging, false)
                    if (dragging) {
                        val area = v.getTag(R.id.nexo_caption_area) as? IntArray
                        if (area != null) {
                            val span = (area[1] - area[0] - v.height).coerceAtLeast(1)
                            BrowserPrefs.setCaptionCustomY(activity, (v.translationY - area[0]) / span)
                            BrowserPrefs.setCaptionPosition(activity, BrowserPrefs.CAPTION_POS_CUSTOM)
                        }
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        onMoved()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** Píldoras de idioma de destino: los habituales, el elegido y "Más idiomas…". */
    private fun targetChips(activity: Activity, selected: String, onPick: (String) -> Unit): View {
        // El idioma elegido siempre a la vista, al principio de la fila.
        val codes = (listOf(selected) + listOf("") + QUICK_TARGETS).distinct()
        val chips = codes.map { code ->
            NexoUi.chip(activity, if (code.isBlank()) "No traducir" else CaptionProtocol.languageLabel(code), code == selected) { onPick(code) }
        } + NexoUi.chip(activity, "Más idiomas…", false) { pickLanguage(activity, selected, onPick) }
        return NexoUi.chipRow(activity, chips)
    }

    /** Lista completa de idiomas de destino, con el elegido marcado. */
    fun pickLanguage(activity: Activity, selected: String, onPick: (String) -> Unit) {
        val p = ThemeManager.palette(activity)
        lateinit var sheet: Dialog
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12f), 0, dp(activity, 12f), dp(activity, 8f))
        }
        for ((code, label) in TARGETS) {
            val chosen = code == selected
            list.addView(TextView(activity).apply {
                text = (if (chosen) "✓  " else "") + label
                textSize = 15.5f
                setTextColor(if (chosen) (if (p.isDark) p.text else p.accent) else p.text)
                typeface = if (chosen) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
                minHeight = dp(activity, 48f)
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                // Árabe o hebreo también alineados con el resto de la lista.
                textDirection = View.TEXT_DIRECTION_LOCALE
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(dp(activity, 14f), 0, dp(activity, 14f), 0)
                background = ThemeManager.ripple(activity, if (chosen) ThemeManager.rounded(activity, p.accentSoft, 14f) else null, 14f)
                isClickable = true
                setOnClickListener { sheet.dismiss(); onPick(code) }
            })
        }
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Traducir a", "${TARGETS.size - 1} idiomas"), list))
    }

    private fun stateText(activity: Activity, session: com.nexo.browser.engine.EngineSession): String = when {
        LiveCaptions.isTrackMode(session) -> "Subtítulos del propio video · sin consumo de Deepgram"
        LiveCaptions.isDomMode(session) -> "Subtítulos del reproductor · sin consumo de Deepgram"
        LiveCaptions.state(session) == LiveCaptions.State.LISTENING -> "Escuchando · " + LiveCaptions.usageText(activity)
        LiveCaptions.state(session) == LiveCaptions.State.CONNECTING -> "Conectando con Deepgram…"
        LiveCaptions.state(session) == LiveCaptions.State.ERROR -> LiveCaptions.message(session) ?: "Error"
        else -> "En espera: empieza al reproducir el video"
    }

    /**
     * Hoja rápida con los subtítulos activos: idioma de destino, qué mostrar
     * e idioma del video. Los cambios se aplican al momento, sin cortar el audio.
     */
    fun showQuick(activity: Activity, session: com.nexo.browser.engine.EngineSession, onChange: () -> Unit, onOff: () -> Unit) {
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        val targetRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val sourceRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        fun renderTarget() {
            targetRow.removeAllViews()
            targetRow.addView(targetChips(activity, BrowserPrefs.captionTranslateTo(activity)) { code ->
                BrowserPrefs.setCaptionTranslateTo(activity, code)
                if (code.isNotBlank() && BrowserPrefs.captionMode(activity) == "original") BrowserPrefs.setCaptionMode(activity, "translated")
                LiveCaptions.retarget(activity, session)
                renderTarget(); onChange()
            })
            modeRow.visibility = if (BrowserPrefs.captionTranslateTo(activity).isBlank()) View.GONE else View.VISIBLE
        }
        fun renderMode() {
            modeRow.removeAllViews()
            modeRow.addView(NexoUi.section(activity, "Mostrar"))
            modeRow.addView(NexoUi.chipRow(activity, listOf("translated" to "Traducido", "both" to "Ambos", "original" to "Original").map { (value, label) ->
                NexoUi.chip(activity, label, BrowserPrefs.captionMode(activity) == value) {
                    BrowserPrefs.setCaptionMode(activity, value)
                    LiveCaptions.retarget(activity, session)
                    renderMode(); onChange()
                }
            }))
        }
        fun renderSource() {
            sourceRow.removeAllViews()
            sourceRow.addView(NexoUi.chipRow(activity, LANGUAGES.map { (value, label) ->
                NexoUi.chip(activity, label, BrowserPrefs.captionLanguage(activity) == value) {
                    BrowserPrefs.setCaptionLanguage(activity, value)
                    LiveCaptions.relisten(activity, session)
                    renderSource(); onChange()
                }
            }))
        }
        // Tarjeta de estado en vivo + medidor de señal de audio (Nexo UI 5.0)
        val p = ThemeManager.palette(activity)
        val statusCard = NexoUi.card(activity).apply {
            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tone = when {
                LiveCaptions.state(session) == LiveCaptions.State.ERROR -> NexoUi.Tone.DANGER
                LiveCaptions.usingDeviceAudio(session) || LiveCaptions.state(session) == LiveCaptions.State.LISTENING -> NexoUi.Tone.SUCCESS
                LiveCaptions.state(session) == LiveCaptions.State.CONNECTING -> NexoUi.Tone.INFO
                else -> NexoUi.Tone.NEUTRAL
            }
            topRow.addView(NexoUi.statusPill(activity, LiveCaptions.sourceLabel(session), tone))
            if (LiveCaptions.usingDeviceAudio(session)) {
                val lvl = LiveCaptions.deviceLevel(session)
                topRow.addView(
                    NexoUi.statusPill(activity, "Nivel $lvl %", if (lvl > 0) NexoUi.Tone.ACCENT else NexoUi.Tone.WARNING),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(activity, 6f) }
                )
            }
            addView(topRow)

            if (LiveCaptions.usingDeviceAudio(session)) {
                val meterBg = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = LiveCaptions.deviceLevel(session).coerceIn(0, 100)
                    progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(p.surfaceContainerHighest)
                }
                addView(meterBg, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 6f)).apply {
                    topMargin = dp(activity, 10f)
                })
            }

            LiveCaptions.stages(session).takeIf { it.isNotEmpty() }?.let { stages ->
                addView(TextView(activity).apply {
                    text = stages.joinToString("\n") { (label, ok) ->
                        (when (ok) { true -> "✓ "; false -> "✗ "; null -> "… " }) + label
                    } + (LiveCaptions.aiTranslationWinner(activity)?.let { "\n✓ Traducción IA: $it" } ?: "")
                    textSize = 12.5f
                    typeface = NexoUi.regular(activity)
                    setTextColor(p.muted)
                    setLineSpacing(dp(activity, 2f).toFloat(), 1f)
                    setPadding(0, dp(activity, 8f), 0, 0)
                })
            }
        }
        body.addView(statusCard)

        // Acción destacada en un toque para capturar TODO el audio con Android (videos, iframes, streams, podcasts).
        if (activity is MainActivity && android.os.Build.VERSION.SDK_INT >= 29 && !LiveCaptions.usingDeviceAudio(session)) {
            body.addView(NexoUi.button(activity, "Capturar todo el audio con Android", primary = true) {
                sheet.dismiss()
                PlaybackAudioCapture.request(activity)
            }, NexoUi.buttonParams(activity, 10f))
        }

        body.addView(NexoUi.section(activity, "Traducir a"))
        body.addView(targetRow)
        body.addView(modeRow)
        renderMode(); renderTarget()
        if (!LiveCaptions.isTrackMode(session) && !LiveCaptions.isDomMode(session)) {
            body.addView(NexoUi.section(activity, "Idioma del video"))
            body.addView(sourceRow)
            renderSource()
        }
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_play, "Nexo Media Engine", "Velocidad, salto rápido, tipografía y desplazamiento temporal") {
            sheet.dismiss(); com.nexo.browser.media.NexoMediaEngine.showSheet(activity) { onChange() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 10f)
        })
        if (activity is MainActivity && !LiveCaptions.isTrackMode(session) && !LiveCaptions.isDomMode(session) &&
            !LiveCaptions.usingDeviceAudio(session) && !LiveCaptions.audioAllowed(session) && BrowserPrefs.deepgramKey(activity).isNotBlank()
        ) {
            body.addView(NexoUi.actionRow(activity, R.drawable.ic_nav_reload, "Transcribir audio desde la página",
                "Intenta leer el audio del reproductor web y enviarlo a Deepgram") {
                sheet.dismiss(); LiveCaptions.allowAudio(activity, session); onChange()
            })
        }
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Subtítulos instantáneos de Android (Offline)",
            "Generados localmente en el teléfono para cualquier sonido, sin enviar nada") {
            sheet.dismiss(); SystemCaptions.openSystemSettings(activity)
        })
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Más ajustes de subtítulos", "Claves API, captura universal, servicio de traducción y estilo") {
            sheet.dismiss(); showSheet(activity) { onChange() }
        })
        body.addView(NexoUi.button(activity, "Apagar subtítulos", danger = true) { sheet.dismiss(); onOff() }, NexoUi.buttonParams(activity, 10f))
        sheet = NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Subtítulos en vivo", stateText(activity, session)), body))
    }

    /** Hoja de configuración: clave, idioma, traducción y tamaño. */
    fun showSheet(activity: Activity, askKey: Boolean = false, onSaved: () -> Unit = {}) {
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)
        var language = BrowserPrefs.captionLanguage(activity)
        var target = BrowserPrefs.captionTranslateTo(activity)
        var provider = BrowserPrefs.translationProvider(activity)
        var size = BrowserPrefs.captionSize(activity)
        var mode = BrowserPrefs.captionMode(activity)
        var limit = BrowserPrefs.captionDailyLimit(activity)

        body.addView(NexoUi.section(activity, "Deepgram"))
        val (keyView, keyField) = NexoUi.field(activity, "Clave API de Deepgram", "Pega tu clave", BrowserPrefs.deepgramKey(activity))
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        body.addView(keyView)
        body.addView(NexoUi.note(activity, "Nexo usa primero texto: la pista del video, los subtítulos del reproductor (YouTube, Netflix…) o el archivo .vtt/.srt que descarga la página. Nunca usa el micrófono. Deepgram (en la nube, con tu cuenta) solo transcribe el audio del video si tú lo eliges."))
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_link, "Crear una clave en deepgram.com", "Se abre en una pestaña nueva") {
            sheet.dismiss()
            TabManager.createTab("https://console.deepgram.com/signup", false, true)
            if (activity !is MainActivity) activity.finish()
        })

        fun chips(holder: LinearLayout, options: List<Pair<String, String>>, selected: () -> String, onPick: (String) -> Unit) {
            holder.removeAllViews()
            holder.addView(NexoUi.chipRow(activity, options.map { (value, label) ->
                NexoUi.chip(activity, label, selected() == value) { onPick(value); chips(holder, options, selected, onPick) }
            }))
        }
        body.addView(NexoUi.section(activity, "Idioma del video"))
        val languageRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(languageRow, LANGUAGES, { language }) { language = it }
        body.addView(languageRow)

        body.addView(NexoUi.section(activity, "Traducir a"))
        val targetRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val translateBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val providerRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (deeplView, deeplField) = NexoUi.field(activity, "Clave API de DeepL", "Termina en :fx si es gratuita", BrowserPrefs.deeplKey(activity))
        deeplField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        // Traducción con un modelo de IA: lotes grandes y pocas peticiones.
        val aiBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val aiProviderRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var aiProvider = BrowserPrefs.aiTranslateProvider(activity)
        val (aiModelView, aiModelField) = NexoUi.field(activity, "Modelo", CaptionProtocol.AI_MODELS[aiProvider] ?: "Nombre del modelo", BrowserPrefs.aiTranslateModel(activity))
        val (aiEndpointView, aiEndpointField) = NexoUi.field(activity, "Dirección de la API (compatible con OpenAI)", "https://servidor/v1", BrowserPrefs.aiTranslateEndpoint(activity))
        val (aiKeyView, aiKeyField) = NexoUi.field(activity, "Clave API del modelo", "Se guarda cifrada en el teléfono", BrowserPrefs.aiTranslateKey(activity))
        aiKeyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        // «Una API para todo»: si Nexo AI está configurado, el modelo 1 es su conexión y no se repite aquí.
        val usesMaster = com.nexo.browser.ai.AiHub.translationUsesMaster(activity)
        aiBox.addView(NexoUi.section(activity, "Modelo de IA"))
        if (usesMaster) {
            val m = com.nexo.browser.ai.AiHub.master(activity)
            aiBox.addView(NexoUi.note(activity, "Usa la API de Nexo AI (${m?.model?.substringAfterLast('/') ?: "configurada"}). Cámbiala en Nexo AI › Ajustes."))
        } else {
            aiBox.addView(aiProviderRow)
            aiBox.addView(aiModelView); aiBox.addView(aiEndpointView); aiBox.addView(aiKeyView)
        }
        aiBox.addView(NexoUi.note(activity, "Traduce varias frases en una sola petición, con el contexto anterior. Con subtítulos propios del video, traduce todo por adelantado."))
        fun refreshProvider() {
            translateBox.visibility = if (target.isBlank()) View.GONE else View.VISIBLE
            deeplView.visibility = if (provider == BrowserPrefs.TRANSLATE_DEEPL) View.VISIBLE else View.GONE
            aiBox.visibility = if (provider == BrowserPrefs.TRANSLATE_AI) View.VISIBLE else View.GONE
            aiEndpointView.visibility = if (aiProvider == "custom") View.VISIBLE else View.GONE
            aiModelField.hint = CaptionProtocol.AI_MODELS[aiProvider] ?: "Nombre del modelo"
        }
        fun renderTargets() {
            targetRow.removeAllViews()
            targetRow.addView(targetChips(activity, target, { target = it; renderTargets(); refreshProvider() }))
        }
        renderTargets()
        body.addView(targetRow)
        translateBox.addView(NexoUi.section(activity, "Mostrar"))
        chips(modeRow, listOf("translated" to "Traducido", "both" to "Ambos", "original" to "Original"), { mode }) { mode = it }
        translateBox.addView(modeRow)
        var hideOriginal = BrowserPrefs.captionHideOriginal(activity)
        translateBox.addView(NexoUi.section(activity, "Mientras llega la traducción"))
        val hideRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(hideRow, listOf("hide" to "Solo traducido", "show" to "Mostrar el original"), { if (hideOriginal) "hide" else "show" }) {
            hideOriginal = it == "hide"
        }
        translateBox.addView(hideRow)
        translateBox.addView(NexoUi.note(activity, "«Solo traducido» nunca enseña el idioma original antes que la traducción. Google adelanta la frase a medias y el servicio elegido la corrige al terminar."))
        translateBox.addView(NexoUi.section(activity, "Servicio de traducción"))
        chips(providerRow, listOf(
            BrowserPrefs.TRANSLATE_GOOGLE to "Google (sin clave)",
            BrowserPrefs.TRANSLATE_DEEPL to "DeepL",
            BrowserPrefs.TRANSLATE_AI to "Modelo de IA"
        ), { provider }) { provider = it; refreshProvider() }
        translateBox.addView(providerRow)
        translateBox.addView(deeplView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(activity, 8f)
        })
        chips(aiProviderRow, CaptionProtocol.AI_PROVIDERS, { aiProvider }) {
            aiProvider = it; refreshProvider()
        }
        translateBox.addView(aiBox)
        // Segundo modelo (otra API o proveedor): se piden los dos a la vez y gana el primero que responde.
        val ai2Box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val ai2Fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var ai2Enabled = BrowserPrefs.aiTranslate2Enabled(activity)
        var ai2Provider = BrowserPrefs.aiTranslate2Provider(activity)
        val ai2ProviderRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val (ai2ModelView, ai2ModelField) = NexoUi.field(activity, "Modelo 2", CaptionProtocol.AI_MODELS[ai2Provider] ?: "Nombre del modelo", BrowserPrefs.aiTranslate2Model(activity))
        val (ai2EndpointView, ai2EndpointField) = NexoUi.field(activity, "Dirección de la API 2 (compatible con OpenAI)", "https://servidor/v1", BrowserPrefs.aiTranslate2Endpoint(activity))
        val (ai2KeyView, ai2KeyField) = NexoUi.field(activity, "Clave API del modelo 2", "Se guarda cifrada en el teléfono", BrowserPrefs.aiTranslate2Key(activity))
        ai2KeyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        fun refreshAi2() {
            ai2Fields.visibility = if (ai2Enabled) View.VISIBLE else View.GONE
            ai2EndpointView.visibility = if (ai2Provider == "custom") View.VISIBLE else View.GONE
            ai2ModelField.hint = CaptionProtocol.AI_MODELS[ai2Provider] ?: "Nombre del modelo"
        }
        ai2Box.addView(NexoUi.switchRow(activity, "Segundo modelo en paralelo",
            "Se pregunta a los dos modelos a la vez y se usa el que responda primero. Si uno falla o se queda sin saldo, el otro sigue traduciendo. Gasta en ambos.", ai2Enabled) {
            ai2Enabled = it; refreshAi2()
        })
        chips(ai2ProviderRow, CaptionProtocol.AI_PROVIDERS, { ai2Provider }) { ai2Provider = it; refreshAi2() }
        ai2Fields.addView(ai2ProviderRow)
        ai2Fields.addView(ai2ModelView); ai2Fields.addView(ai2EndpointView); ai2Fields.addView(ai2KeyView)
        ai2Box.addView(ai2Fields)
        aiBox.addView(ai2Box)
        refreshAi2()
        translateBox.addView(NexoUi.note(activity, "Se traducen frases completas, agrupadas, al momento y sin descargar idiomas."))
        body.addView(translateBox)
        refreshProvider()

        var audioAuto = BrowserPrefs.captionAudioAuto(activity)
        body.addView(NexoUi.switchRow(activity, "Transcribir el audio sin preguntar",
            "Si el video no trae subtítulos de texto, enviar su audio a Deepgram directamente. Apagado: Nexo pregunta antes.", audioAuto) {
            audioAuto = it
        })
        var preferDeviceAudio = BrowserPrefs.captionPreferDeviceAudio(activity)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            body.addView(NexoUi.switchRow(activity, "Priorizar captura universal de Android",
                "Al tocar CC con Deepgram configurado, abrir directamente la captura de audio del sistema para captar cualquier video, stream o podcast.", preferDeviceAudio) {
                preferDeviceAudio = it
            })
        }
        var friendlyAudio = BrowserPrefs.captureFriendlyAudio(activity)
        body.addView(NexoUi.switchRow(activity, "Audio compatible con la captura de Android",
            "Los reproductores con Web Audio suenan por la vía que Android puede capturar (~0,1 s más de retardo en juegos web). Apágalo para comparar si la captura no lo necesita.", friendlyAudio) {
            friendlyAudio = it
        })
        body.addView(NexoUi.section(activity, "Límite diario de Deepgram"))
        val limitRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(limitRow, listOf("30" to "30 min", "60" to "1 h", "120" to "2 h", "240" to "4 h", "0" to "Sin límite"), { limit.toString() }) { limit = it.toInt() }
        body.addView(limitRow)
        body.addView(NexoUi.note(activity, LiveCaptions.usageText(activity) + ". Los videos con subtítulos propios no lo consumen."))

        body.addView(NexoUi.section(activity, "Diseño · tamaño del texto"))
        val sizeRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(sizeRow, SIZES.map { it.first.toString() to it.second }, { size.toString() }) { size = it.toInt() }
        body.addView(sizeRow)
        var position = BrowserPrefs.captionPosition(activity)
        body.addView(NexoUi.section(activity, "Posición"))
        val positionRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(positionRow, listOf(
            BrowserPrefs.CAPTION_POS_VIDEO to "Sobre el video",
            BrowserPrefs.CAPTION_POS_TOP to "Arriba",
            BrowserPrefs.CAPTION_POS_CENTER to "Centro",
            BrowserPrefs.CAPTION_POS_BOTTOM to "Abajo",
            BrowserPrefs.CAPTION_POS_CUSTOM to "Donde lo arrastre"
        ), { position }) { position = it }
        body.addView(positionRow)
        body.addView(NexoUi.note(activity, "También puedes arrastrar el subtítulo con el dedo: se queda donde lo sueltes."))
        var background = BrowserPrefs.captionBackground(activity)
        body.addView(NexoUi.section(activity, "Fondo"))
        val backgroundRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(backgroundRow, listOf("solid" to "Oscuro", "soft" to "Suave", "none" to "Sin fondo"), { background }) { background = it }
        body.addView(backgroundRow)
        var color = BrowserPrefs.captionColor(activity)
        body.addView(NexoUi.section(activity, "Color del texto"))
        val colorRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(colorRow, listOf("white" to "Blanco", "yellow" to "Amarillo"), { color }) { color = it }
        body.addView(colorRow)
        var lines = BrowserPrefs.captionLines(activity)
        body.addView(NexoUi.section(activity, "Longitud"))
        val linesRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(linesRow, listOf("1" to "Corta · 1 línea", "2" to "Media · 2 líneas", "3" to "Larga · 3 líneas"), { lines.toString() }) { lines = it.toInt() }
        body.addView(linesRow)
        var clearSeconds = BrowserPrefs.captionClearSeconds(activity)
        body.addView(NexoUi.section(activity, "Borrar al dejar de hablar"))
        val clearRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chips(clearRow, listOf("2" to "2 s", "3" to "3 s", "5" to "5 s", "8" to "8 s"), { clearSeconds.toString() }) { clearSeconds = it.toInt() }
        body.addView(clearRow)
        body.addView(NexoUi.note(activity, "Tras ese silencio el último subtítulo desaparece, para no tapar el video."))

        val error = TextView(activity).apply {
            textSize = 12.5f; setTextColor(ThemeManager.palette(activity).danger); visibility = View.GONE
        }
        body.addView(error)
        body.addView(NexoUi.button(activity, "Guardar", primary = true) {
            val key = keyField.text.toString().trim()
            if (key.isNotEmpty() && !key.matches(Regex("[A-Za-z0-9_\\-.]{20,200}"))) {
                error.text = "La clave de Deepgram no parece válida."; error.visibility = View.VISIBLE; return@button
            }
            if (target.isNotBlank() && provider == BrowserPrefs.TRANSLATE_DEEPL && deeplField.text.isBlank()) {
                error.text = "Escribe la clave de DeepL o elige Google."; error.visibility = View.VISIBLE; return@button
            }
            if (target.isNotBlank() && provider == BrowserPrefs.TRANSLATE_AI) {
                if (!usesMaster) {
                    val endpoint = aiEndpointField.text.toString().trim()
                    if (aiProvider == "custom" && !CaptionProtocol.isSafeKeyedEndpoint(endpoint)) {
                        error.text = "La dirección de la API debe ser https:// (o http:// solo hacia este teléfono), sin usuario ni contraseña."
                        error.visibility = View.VISIBLE; return@button
                    }
                    if (aiProvider != "custom" && aiKeyField.text.isBlank()) {
                        error.text = "Escribe la clave del modelo de IA."; error.visibility = View.VISIBLE; return@button
                    }
                }
                if (ai2Enabled) {
                    if (ai2Provider == "custom" && !CaptionProtocol.isSafeKeyedEndpoint(ai2EndpointField.text.toString().trim())) {
                        error.text = "Modelo 2: la dirección de la API debe ser https:// (o http:// solo hacia este teléfono)."
                        error.visibility = View.VISIBLE; return@button
                    }
                    if (ai2Provider != "custom" && ai2KeyField.text.isBlank()) {
                        error.text = "Escribe la clave del modelo 2 o apaga el segundo modelo."; error.visibility = View.VISIBLE; return@button
                    }
                }
            }
            BrowserPrefs.setDeepgramKey(activity, key)
            BrowserPrefs.setCaptionLanguage(activity, language)
            BrowserPrefs.setCaptionTranslateTo(activity, target)
            BrowserPrefs.setTranslationProvider(activity, provider)
            BrowserPrefs.setDeeplKey(activity, deeplField.text.toString())
            BrowserPrefs.setCaptionSize(activity, size)
            BrowserPrefs.setCaptionBackground(activity, background)
            BrowserPrefs.setCaptionPosition(activity, position)
            BrowserPrefs.setCaptionColor(activity, color)
            BrowserPrefs.setCaptionLines(activity, lines)
            BrowserPrefs.setCaptionClearSeconds(activity, clearSeconds)
            BrowserPrefs.setCaptionMode(activity, mode)
            BrowserPrefs.setCaptionHideOriginal(activity, hideOriginal)
            BrowserPrefs.setCaptionDailyLimit(activity, limit)
            BrowserPrefs.setCaptionAudioAuto(activity, audioAuto)
            BrowserPrefs.setCaptionPreferDeviceAudio(activity, preferDeviceAudio)
            if (friendlyAudio != BrowserPrefs.captureFriendlyAudio(activity)) {
                BrowserPrefs.setCaptureFriendlyAudio(activity, friendlyAudio)
                com.nexo.browser.engine.Engines.current.applyAudioSettings(activity)
            }
            BrowserPrefs.setAiTranslateProvider(activity, aiProvider)
            BrowserPrefs.setAiTranslateModel(activity, aiModelField.text.toString())
            BrowserPrefs.setAiTranslateEndpoint(activity, if (aiProvider == "custom") aiEndpointField.text.toString() else "")
            BrowserPrefs.setAiTranslateKey(activity, aiKeyField.text.toString())
            BrowserPrefs.setAiTranslate2Enabled(activity, ai2Enabled)
            BrowserPrefs.setAiTranslate2Provider(activity, ai2Provider)
            BrowserPrefs.setAiTranslate2Model(activity, ai2ModelField.text.toString())
            BrowserPrefs.setAiTranslate2Endpoint(activity, if (ai2Provider == "custom") ai2EndpointField.text.toString() else "")
            BrowserPrefs.setAiTranslate2Key(activity, ai2KeyField.text.toString())
            sheet.dismiss()
            onSaved()
        }, NexoUi.buttonParams(activity, 14f))

        sheet = NexoSheets.show(activity, NexoSheets.column(activity,
            NexoSheets.header(activity, "Subtítulos en vivo", "Transcripción y traducción del video"), body))
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (askKey) keyField.requestFocus()
    }
}
