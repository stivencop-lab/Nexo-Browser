package com.nexo.browser

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Fila de lista con insignia, título, subtítulo, dato secundario y píldora de estado opcional. */
data class ListRow(
    val title: String,
    val subtitle: String = "",
    val meta: String = "",
    val badge: String = "",
    val status: String = "",
    val statusTone: NexoUi.Tone? = null
)

/**
 * Sistema visual de Nexo: paleta, tema de Android (para que diálogos, menús,
 * cursor e interruptores usen el acento elegido) y estilos de componentes
 * (botones planos con ripple, campos tipo píldora, tarjetas redondeadas).
 */
object ThemeManager {
    private fun resolvedTheme(activity: Activity): String {
        val selected = BrowserPrefs.theme(activity)
        if (selected != BrowserPrefs.THEME_SYSTEM) return selected
        val night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) {
            BrowserPrefs.THEME_MIDNIGHT
        } else BrowserPrefs.THEME_LIGHT
    }

    fun isDark(activity: Activity): Boolean =
        resolvedTheme(activity) != BrowserPrefs.THEME_LIGHT

    /** Identifica tema + acento: si cambia, hay que recrear la Activity. */
    fun themeKey(activity: Activity): String =
        resolvedTheme(activity) + "|" + accentColor(activity)

    private fun accentColor(activity: Activity): Int = accentSeed(BrowserPrefs.accent(activity))

    /** Semilla de color de cada acento de UI 7.0 (Monocromo = tinta del tema). */
    fun accentSeed(accent: String): Int = when (accent) {
        BrowserPrefs.ACCENT_SIGNAL -> BrowserColors.SIGNAL
        BrowserPrefs.ACCENT_COBALT -> BrowserColors.COBALT
        BrowserPrefs.ACCENT_ORANGE -> BrowserColors.ORANGE
        BrowserPrefs.ACCENT_SAGE -> BrowserColors.SAGE
        else -> BrowserColors.MONOCHROME
    }

    /** Nombre visible de cada acento. */
    fun accentLabel(accent: String): String = when (accent) {
        BrowserPrefs.ACCENT_SIGNAL -> "Azul Señal"
        BrowserPrefs.ACCENT_COBALT -> "Cobalto Editorial"
        BrowserPrefs.ACCENT_ORANGE -> "Bermellón"
        BrowserPrefs.ACCENT_SAGE -> "Salvia"
        else -> "Monocromo"
    }

    // Palette generation is pure but nontrivial. Reuse tokens without retaining Activities.
    private data class PaletteKey(val mode: String, val seed: Int)
    private val palettes = object : LinkedHashMap<PaletteKey, BrowserPalette>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PaletteKey, BrowserPalette>?): Boolean = size > 16
    }

    fun palette(activity: Activity): BrowserPalette {
        val key = PaletteKey(resolvedTheme(activity), accentColor(activity))
        return synchronized(palettes) {
            palettes.getOrPut(key) {
                BrowserColors.create(key.seed, key.mode != BrowserPrefs.THEME_LIGHT, key.mode == BrowserPrefs.THEME_OLED)
            }
        }
    }

    fun themeRes(activity: Activity): Int {
        val dark = isDark(activity)
        return when (BrowserPrefs.accent(activity)) {
            BrowserPrefs.ACCENT_SIGNAL -> if (dark) R.style.Theme_Nexo_Dark_Signal else R.style.Theme_Nexo_Light_Signal
            BrowserPrefs.ACCENT_COBALT -> if (dark) R.style.Theme_Nexo_Dark_Cobalt else R.style.Theme_Nexo_Light_Cobalt
            BrowserPrefs.ACCENT_ORANGE -> if (dark) R.style.Theme_Nexo_Dark_Orange else R.style.Theme_Nexo_Light_Orange
            BrowserPrefs.ACCENT_SAGE -> if (dark) R.style.Theme_Nexo_Dark_Sage else R.style.Theme_Nexo_Light_Sage
            else -> if (dark) R.style.Theme_Nexo_Dark_Mono else R.style.Theme_Nexo_Light_Mono
        }
    }

    /**
     * Llamar ANTES de super.onCreate(): fija el tema de Android (claro/oscuro
     * + acento) y los colores de las barras del sistema.
     */
    fun applyWindow(activity: Activity) {
        activity.setTheme(themeRes(activity))

        val p = palette(activity)
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        installInsets(activity)
        window.setBackgroundDrawable(ColorDrawable(p.background))
        window.decorView.setBackgroundColor(p.background)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.isDark
            isAppearanceLightNavigationBars = !p.isDark
        }
    }

    /** Marca de Activity que reparte ella misma los insets (lienzo edge-to-edge). */
    interface InsetsOwner

    /** One owner per window: combine IME and safe areas with max(), never add twice. */
    private fun installInsets(activity: Activity) {
        if (activity is InsetsOwner) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            content.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard))
            // Root handles these once; embedded views must not pad them a second time.
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.NONE)
                .build()
        }
        ViewCompat.requestApplyInsets(content)
    }

    // ---------------------------------------------------------------
    // Primitivas y tokens UI 7.0 «Liquid Craft»
    // ---------------------------------------------------------------

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /** Divisor translúcido (6 % de tinta): separa sin dibujar cajas dentro de cajas. */
    fun hairline(p: BrowserPalette): Int = p.divider

    private var sansCache: Typeface? = null
    private val weighted = HashMap<Int, Typeface>(4)

    /** Plus Jakarta Sans variable; se resuelve una sola vez por proceso. */
    private fun sans(activity: Activity): Typeface =
        sansCache ?: runCatching { activity.resources.getFont(R.font.plus_jakarta_sans) }
            .getOrDefault(Typeface.SANS_SERIF).also { sansCache = it }

    private fun weight(activity: Activity, w: Int): Typeface = synchronized(weighted) {
        weighted.getOrPut(w) { Typeface.create(sans(activity), w, false) }
    }

    /** 400 · cuerpo. */
    fun fontRegular(activity: Activity): Typeface = weight(activity, 400)

    /** 500 · controles. */
    fun fontMedium(activity: Activity): Typeface = weight(activity, 500)

    /** 600 · encabezados (con letterSpacing -0.025em). */
    fun fontSemiBold(activity: Activity): Typeface = weight(activity, 600)

    /** UI 7.0 no grita: el antiguo «bold» se limita a SemiBold 600. */
    fun fontBold(activity: Activity): Typeface = weight(activity, 700)

    /** JetBrains Mono: solo métricas y latencias, siempre con cifras tabulares. */
    fun fontMono(activity: Activity, weight: Int = 400): Typeface {
        val base = runCatching { activity.resources.getFont(R.font.jetbrains_mono) }.getOrDefault(Typeface.MONOSPACE)
        return if (weight == 400) base else Typeface.create(base, weight, false)
    }

    /** Encabezado editorial: SemiBold con tracking -0.025em. */
    fun styleHeading(activity: Activity, text: TextView, sizeSp: Float) {
        text.typeface = fontSemiBold(activity)
        text.textSize = sizeSp
        text.letterSpacing = -0.025f
        text.setTextColor(palette(activity).text)
    }

    /** Cifras de métricas: JetBrains Mono con tnum. */
    fun styleMetric(activity: Activity, text: TextView, muted: Boolean = true) {
        text.typeface = fontMono(activity)
        text.fontFeatureSettings = "tnum"
        text.setTextColor(if (muted) palette(activity).muted else palette(activity).text)
    }

    fun rounded(
        activity: Activity,
        color: Int,
        radiusDp: Float = 16f,
        strokeColor: Int? = null,
        strokeWidthDp: Float = 1f
    ): GradientDrawable {
        val density = activity.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
            if (strokeColor != null) {
                setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
    }

    /** Fondo con efecto de pulsación (ripple) recortado a esquinas redondeadas. */
    fun ripple(
        activity: Activity,
        content: Drawable?,
        radiusDp: Float,
        rippleColor: Int = ColorUtils.setAlphaComponent(palette(activity).text, 28)
    ): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            content,
            rounded(activity, Color.WHITE, radiusDp)
        )

    private fun tint(color: Int): ColorStateList =
        ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(color, 95), color)
        )

    // ---------------------------------------------------------------
    // Componentes
    // ---------------------------------------------------------------

    /**
     * Botón de texto. [primary] = relleno con el acento; [danger] = tono suave de peligro;
     * en otro caso, botón tonal limpio sin bordes pesados.
     */
    fun styleButton(
        activity: Activity,
        button: Button,
        primary: Boolean = false,
        danger: Boolean = false
    ) {
        val p = palette(activity)
        val foreground = when {
            primary -> p.onAccent
            danger -> p.error.onContainer
            else -> p.text
        }
        val bgColor = when {
            primary -> p.accent
            danger -> p.error.container
            else -> p.surfaceContainerHigh
        }
        val rippleColor = ColorUtils.setAlphaComponent(foreground, 36)

        button.background = ripple(activity, rounded(activity, bgColor, NexoUi.Tokens.RADIUS_PILL), NexoUi.Tokens.RADIUS_PILL, rippleColor)
        button.setTextColor(tint(foreground))
        button.compoundDrawableTintList = tint(foreground)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
        button.minHeight = dp(activity, 48f)
        button.isAllCaps = false
        button.letterSpacing = 0f
        button.typeface = fontMedium(activity)
    }

    /** Icono plano con ripple circular; [active] lo muestra en cápsula tonal suave. */
    fun styleIconButton(activity: Activity, button: ImageButton, active: Boolean = false) {
        val p = palette(activity)
        val inset = dp(activity, 4f)
        val content = if (active) InsetDrawable(rounded(activity, p.surfaceContainerHighest, NexoUi.Tokens.RADIUS_PILL), inset) else null
        button.background = ripple(activity, content, NexoUi.Tokens.RADIUS_PILL)
        button.imageTintList = tint(p.text)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.alpha = if (button.isEnabled) 1f else 0.42f
        button.elevation = 0f
    }

    /** Botón de texto que actúa como icono (sin caja), p. ej. el menú ⋮. */
    fun styleFlatButton(activity: Activity, button: Button) {
        val p = palette(activity)
        button.background = ripple(activity, null, 24f)
        button.setTextColor(tint(p.text))
        button.compoundDrawableTintList = tint(p.text)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
        button.minHeight = dp(activity, 48f)
        button.isAllCaps = false
        button.typeface = fontMedium(activity)
    }

    fun styleText(activity: Activity, text: TextView, muted: Boolean = false) {
        val p = palette(activity)
        text.setTextColor(if (muted) p.muted else p.text)
    }

    /** Etiqueta de grupo: frase en caja normal, 13sp Medium y tinta atenuada (nunca ALL-CAPS). */
    fun styleSectionLabel(activity: Activity, text: TextView) {
        val p = palette(activity)
        text.setTextColor(p.muted)
        text.textSize = 13f
        text.isAllCaps = false
        text.letterSpacing = 0f
        text.typeface = fontMedium(activity)
    }

    /** Tarjeta agrupada r:22 sin borde; en Paper flota con sombra difusa (0 2dp 14dp · 4.5 %). */
    fun styleCard(activity: Activity, view: View, radiusDp: Float = NexoUi.Tokens.RADIUS_CARD) {
        val p = palette(activity)
        view.background = rounded(activity, p.surface, radiusDp)
        view.clipToOutline = true
        NexoGlass.paperShadow(view, p)
    }

    /** Fondo de barra: cristal translúcido con borde especular (sin líneas de 1dp). */
    fun barBackground(activity: Activity, lineOnTop: Boolean): Drawable {
        val p = palette(activity)
        return NexoGlassDrawable(activity, p, radiusDp = 0f, specularTop = lineOnTop)
    }

    /** Contador de pestañas: cuadro de 20dp con trazo 1.6dp y cifras tabulares (48dp táctiles). */
    fun styleTabCounter(activity: Activity, button: Button) {
        val p = palette(activity)
        val inset = dp(activity, 14f)
        val box = InsetDrawable(
            rounded(activity, Color.TRANSPARENT, 6f, p.text, 1.6f),
            inset
        )
        button.fontFeatureSettings = "tnum"
        button.typeface = fontMedium(activity)
        button.textSize = 11f
        button.background = ripple(activity, box, NexoUi.Tokens.RADIUS_PILL)
        button.setTextColor(p.text)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
    }

    fun styleDivider(activity: Activity, view: View) {
        view.setBackgroundColor(hairline(palette(activity)))
    }

    /** Barra superior de pantallas secundarias: botón volver + título. */
    fun styleBackButton(activity: Activity, button: ImageButton) {
        styleIconButton(activity, button)
        button.setOnClickListener { activity.finish() }
    }

    /**
     * Evita que el primer campo de texto reciba el foco al abrir la pantalla
     * (cursor parpadeando y, en algunos equipos, el teclado abierto).
     */
    fun takeInitialFocus(root: View) {
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    fun spinnerAdapter(activity: Activity, items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, items) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                paint(super.getView(position, convertView, parent), false)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                paint(super.getDropDownView(position, convertView, parent), true)

            private fun paint(view: View, dropdown: Boolean): View {
                val p = palette(activity)
                (view as? TextView)?.apply {
                    setTextColor(p.text)
                    textSize = 14.5f
                    typeface = if (dropdown) fontRegular(activity) else fontMedium(activity)
                    maxLines = if (dropdown) 3 else 2
                    minHeight = dp(activity, 48f)
                    ellipsize = TextUtils.TruncateAt.END
                    val d = resources.displayMetrics.density
                    if (dropdown) {
                        setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                        background = ripple(activity, null, 12f)
                    } else {
                        setPadding((14 * d).toInt(), (6 * d).toInt(), (36 * d).toInt(), (6 * d).toInt())
                        background = null
                        gravity = Gravity.CENTER_VERTICAL
                    }
                }
                return view
            }
        }

    /** Lista simple: la primera línea es el título; el resto, texto secundario. */
    fun listAdapter(activity: Activity, items: List<String>): BaseAdapter =
        rowsAdapter(
            activity,
            items.map { item ->
                val lines = item.split('\n')
                ListRow(
                    title = lines.firstOrNull().orEmpty(),
                    subtitle = lines.getOrNull(1).orEmpty(),
                    meta = lines.drop(2).joinToString(" · ")
                )
            }
        )

    /** Lista en tarjetas tonales con insignia redondeada, título, subtítulo y píldora de estado. */
    fun rowsAdapter(activity: Activity, rows: List<ListRow>): BaseAdapter =
        object : BaseAdapter() {
            override fun getCount(): Int = rows.size
            override fun getItem(position: Int): Any = rows[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val holder = (convertView?.tag as? RowHolder) ?: createRow(activity)
                holder.bind(activity, rows[position])
                return holder.root
            }
        }

    private class RowHolder(
        val root: LinearLayout,
        val badge: TextView,
        val title: TextView,
        val subtitle: TextView,
        val meta: TextView,
        val statusPill: TextView
    ) {
        fun bind(activity: Activity, row: ListRow) {
            val p = palette(activity)
            title.text = row.title
            subtitle.text = row.subtitle
            subtitle.visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
            meta.text = row.meta
            meta.visibility = if (row.meta.isBlank()) View.GONE else View.VISIBLE

            val label = row.badge.ifBlank { row.title.trim().take(1).uppercase() }.take(4)
            badge.text = label
            badge.textSize = if (label.length > 2) 10.5f else 14f
            // Monograma neutro y circular: sin cuadrados de color detrás de cada fila.
            badge.setTextColor(p.text)
            badge.background = rounded(activity, p.surfaceContainerHighest, NexoUi.Tokens.RADIUS_PILL)

            if (row.status.isBlank()) {
                statusPill.visibility = View.GONE
            } else {
                val (bg, fg) = NexoUi.toneColors(p, row.statusTone ?: NexoUi.Tone.NEUTRAL)
                statusPill.text = row.status
                statusPill.setTextColor(fg)
                statusPill.background = rounded(activity, bg, NexoUi.Tokens.RADIUS_PILL)
                statusPill.visibility = View.VISIBLE
            }
        }
    }

    private fun createRow(activity: Activity): RowHolder {
        val p = palette(activity)
        val d = activity.resources.displayMetrics.density
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            minimumHeight = (64 * d).toInt()
            background = ripple(
                activity,
                rounded(activity, p.surface, NexoUi.Tokens.RADIUS_CARD),
                NexoUi.Tokens.RADIUS_CARD
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val badge = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(p.text)
            typeface = fontMedium(activity)
            background = rounded(activity, p.surfaceContainerHighest, NexoUi.Tokens.RADIUS_PILL)
            layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply {
                marginEnd = (14 * d).toInt()
            }
        }

        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val title = TextView(activity).apply {
            textSize = 15f
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = fontMedium(activity)
        }
        val subtitle = TextView(activity).apply {
            textSize = 13f
            setTextColor(p.muted)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.15f)
            setPadding(0, (2 * d).toInt(), 0, 0)
        }
        val meta = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(p.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (3 * d).toInt(), 0, 0)
            typeface = fontMono(activity)
            fontFeatureSettings = "tnum"
        }

        val statusPill = TextView(activity).apply {
            textSize = 11.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = fontMedium(activity)
            gravity = Gravity.CENTER
            setPadding((9 * d).toInt(), (4 * d).toInt(), (9 * d).toInt(), (4 * d).toInt())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (10 * d).toInt() }
        }

        texts.addView(title)
        texts.addView(subtitle)
        texts.addView(meta)
        root.addView(badge)
        root.addView(texts)
        root.addView(statusPill)

        return RowHolder(root, badge, title, subtitle, meta, statusPill).also { root.tag = it }
    }

    /** Selector tonal limpio con flecha discreta a la derecha (sin doble borde pesado). */
    fun styleSpinner(activity: Activity, spinner: Spinner) {
        val p = palette(activity)
        val fill = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainer
        val field = rounded(activity, fill, NexoUi.Tokens.RADIUS_CONTROL)
        val chevron = ChevronDrawable(p.muted, dp(activity, 1.6f).toFloat())

        spinner.background = LayerDrawable(arrayOf(field, chevron)).apply {
            val size = dp(activity, 11f)
            setLayerSize(1, size, size)
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInsetEnd(1, dp(activity, 14f))
        }
        spinner.backgroundTintList = null
        spinner.setPopupBackgroundDrawable(
            LayerDrawable(arrayOf(rounded(activity, p.surfaceContainerHigh, NexoUi.Tokens.RADIUS_CARD))).apply {
                setPadding(dp(activity, 6f), dp(activity, 8f), dp(activity, 6f), dp(activity, 8f))
            }
        )
    }

    fun styleSwitch(activity: Activity, toggle: Switch) {
        val p = palette(activity)
        toggle.setTextColor(p.text)
        toggle.typeface = fontMedium(activity)
        // Interruptor Liquid: pista sólida del acento (Monocromo = tinta) y pulgar del lienzo.
        val track = if (p.isMonochrome) p.text else p.accentSwatch
        toggle.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(if (p.isMonochrome) p.background else Color.WHITE, p.muted)
        )
        toggle.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(track, p.surfaceContainerHighest)
        )
        toggle.setOnCheckedChangeListenerSpring()
    }

    /** Rebote físico del pulgar al cambiar (SpringAnimation 380 / 0.82). */
    private fun Switch.setOnCheckedChangeListenerSpring() {
        if (getTag(R.id.nexo_spring_switch) != null) return
        setTag(R.id.nexo_spring_switch, true)
        setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP) NexoMotion.pulse(v, 0.94f)
            false
        }
    }

    /**
     * Campo de texto tipo píldora con contraste garantizado tanto sobre el lienzo
     * principal como dentro de hojas inferiores elevadas.
     */
    fun styleEdit(activity: Activity, edit: EditText) {
        val p = palette(activity)
        val normalBg = if (p.isDark) p.surfaceContainerLow else p.surfaceContainer
        val focusedBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
        val normal = rounded(activity, normalBg, NexoUi.Tokens.RADIUS_CONTROL)
        val focused = rounded(activity, focusedBg, NexoUi.Tokens.RADIUS_CONTROL, ColorUtils.setAlphaComponent(p.text, 70), 1.2f)

        edit.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.muted)
        edit.typeface = fontRegular(activity)
        edit.highlightColor = ColorUtils.setAlphaComponent(p.accent, 75)
        edit.backgroundTintList = null
        edit.gravity = Gravity.CENTER_VERTICAL
        edit.compoundDrawableTintList = ColorStateList.valueOf(p.muted)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            edit.textCursorDrawable = GradientDrawable().apply {
                setColor(p.accent)
                setSize(dp(activity, 2f), dp(activity, 20f))
            }
        }
    }

    /** Píldora de dirección de cristal líquido (Liquid Split Chrome). */
    fun styleOmniboxCapsule(activity: Activity, capsule: View, focused: Boolean = false) {
        val p = palette(activity)
        if (focused) {
            // Al escribir, el cristal se solidifica para máxima legibilidad.
            capsule.background = rounded(activity, p.surfaceContainerHigh, NexoUi.Tokens.RADIUS_PILL)
        } else {
            capsule.background = NexoGlassDrawable(activity, p, NexoUi.Tokens.RADIUS_PILL)
        }
        NexoGlass.float(capsule, p)
    }

    /** Estiliza el campo de texto dentro de la cápsula unificada (sin fondo propio). */
    fun styleOmniboxInput(activity: Activity, edit: EditText) {
        val p = palette(activity)
        edit.background = null
        edit.backgroundTintList = null
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.muted)
        edit.typeface = fontMedium(activity)
        edit.highlightColor = ColorUtils.setAlphaComponent(p.accentSwatch, 75)
        edit.gravity = Gravity.CENTER_VERTICAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            edit.textCursorDrawable = GradientDrawable().apply {
                setColor(if (p.isMonochrome) p.text else p.accent)
                setSize(dp(activity, 2f), dp(activity, 20f))
            }
        }
    }

    /**
     * Emblema Nexo Aperture (dos arcos concéntricos y punto central). Sin bordes de color:
     * el estado se comunica con un punto discreto y con la respiración del núcleo.
     */
    fun styleAiSparkButton(
        activity: Activity,
        button: ImageButton,
        state: com.nexo.browser.ai.NexoAiModels.IndicatorState = com.nexo.browser.ai.NexoAiModels.IndicatorState.IDLE
    ) {
        val p = palette(activity)
        val aperture = (button.drawable as? NexoApertureDrawable)
            ?: NexoApertureDrawable(activity).also { button.setImageDrawable(it) }
        aperture.setColor(p.text)
        val dot = when (state) {
            com.nexo.browser.ai.NexoAiModels.IndicatorState.GENERATING -> if (p.isMonochrome) p.success.color else p.accent
            com.nexo.browser.ai.NexoAiModels.IndicatorState.READY -> p.success.color
            com.nexo.browser.ai.NexoAiModels.IndicatorState.ERROR -> p.danger
            com.nexo.browser.ai.NexoAiModels.IndicatorState.IDLE -> null
        }
        aperture.setStatusDot(dot)
        aperture.setBreathing(state == com.nexo.browser.ai.NexoAiModels.IndicatorState.GENERATING && !NexoUi.reducedMotion(activity))
        button.imageTintList = null
        button.background = ripple(activity, null, NexoUi.Tokens.RADIUS_PILL)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
        button.contentDescription = when (state) {
            com.nexo.browser.ai.NexoAiModels.IndicatorState.GENERATING -> "Nexo AI generando respuesta"
            com.nexo.browser.ai.NexoAiModels.IndicatorState.READY -> "Nexo AI tiene una respuesta disponible"
            com.nexo.browser.ai.NexoAiModels.IndicatorState.ERROR -> "Nexo AI encontró un error"
            com.nexo.browser.ai.NexoAiModels.IndicatorState.IDLE -> activity.getString(R.string.nexo_ai_action)
        }
    }

    /** Respuesta física breve al pulsar el emblema (respeta reducción de movimiento). */
    fun animateAiSparkTap(button: View) = NexoMotion.pulse(button, 0.9f)

    /** Botón «•••» horizontal centrado ópticamente junto al contador de pestañas. */
    fun styleMenuIconButton(activity: Activity, button: Button) {
        val p = palette(activity)
        val dots = MenuDotsDrawable(p.text, dp(activity, 1.7f).toFloat())
        button.backgroundTintList = null
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        button.background = ripple(activity, dots, NexoUi.Tokens.RADIUS_PILL)
        button.setTextColor(tint(p.text))
        button.stateListAnimator = null
        button.elevation = 0f
        button.minHeight = dp(activity, 48f)
        button.isAllCaps = false
    }

    /** Dock clásico refinado: lámina de cristal con radio concéntrico (sin hairline). */
    fun styleFloatingDock(activity: Activity, dock: View, gesturePill: View? = null) {
        val p = palette(activity)
        dock.background = NexoGlassDrawable(activity, p, 28f)
        NexoGlass.float(dock, p)
        gesturePill?.background = rounded(activity, ColorUtils.setAlphaComponent(p.muted, 90), 2f)
    }

    /** Acción principal (+) del dock: círculo tonal sin color de marca. */
    fun styleDockPrimaryIcon(activity: Activity, button: ImageButton) {
        val p = palette(activity)
        val inset = dp(activity, 6f)
        val well = InsetDrawable(rounded(activity, p.surfaceContainerHighest, NexoUi.Tokens.RADIUS_PILL), inset)
        button.background = ripple(activity, well, NexoUi.Tokens.RADIUS_PILL)
        button.imageTintList = tint(p.text)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.alpha = if (button.isEnabled) 1f else 0.42f
        button.elevation = 0f
    }
}

/** Flecha "⌄" dibujada con el color exacto de la paleta (sin depender de tints). */
private class ChevronDrawable(color: Int, strokeWidth: Float) : Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        path.reset()
        path.moveTo(b.left + w * 0.1f, b.top + h * 0.32f)
        path.lineTo(b.left + w * 0.5f, b.top + h * 0.72f)
        path.lineTo(b.left + w * 0.9f, b.top + h * 0.32f)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Icono "⋮" dibujado directamente con el color exacto de la paleta (siempre visible en claro, oscuro y OLED). */
private class MenuDotsDrawable(color: Int, private val radius: Float) : Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.FILL
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val step = radius * 3.6f
        canvas.drawCircle(cx - step, cy, radius, paint)
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawCircle(cx + step, cy, radius, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
