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

    private fun accentColor(activity: Activity): Int =
        when (BrowserPrefs.accent(activity)) {
            BrowserPrefs.ACCENT_DYNAMIC -> if (Build.VERSION.SDK_INT >= 31) {
                activity.getColor(android.R.color.system_accent1_500)
            } else BrowserColors.COBALT
            BrowserPrefs.ACCENT_COBALT -> BrowserColors.COBALT
            BrowserPrefs.ACCENT_CYAN -> BrowserColors.CYAN
            BrowserPrefs.ACCENT_LIME -> BrowserColors.LIME
            BrowserPrefs.ACCENT_ORANGE -> BrowserColors.ORANGE
            BrowserPrefs.ACCENT_PINK -> BrowserColors.PINK
            BrowserPrefs.ACCENT_RED -> BrowserColors.RED
            else -> BrowserColors.VIOLET
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
            BrowserPrefs.ACCENT_DYNAMIC -> if (dark) R.style.Theme_Nexo_Dark_Dynamic else R.style.Theme_Nexo_Light_Dynamic
            BrowserPrefs.ACCENT_COBALT -> if (dark) R.style.Theme_Nexo_Dark_Cobalt else R.style.Theme_Nexo_Light_Cobalt
            BrowserPrefs.ACCENT_CYAN -> if (dark) R.style.Theme_Nexo_Dark_Cyan else R.style.Theme_Nexo_Light_Cyan
            BrowserPrefs.ACCENT_LIME -> if (dark) R.style.Theme_Nexo_Dark_Lime else R.style.Theme_Nexo_Light_Lime
            BrowserPrefs.ACCENT_ORANGE -> if (dark) R.style.Theme_Nexo_Dark_Orange else R.style.Theme_Nexo_Light_Orange
            BrowserPrefs.ACCENT_PINK -> if (dark) R.style.Theme_Nexo_Dark_Pink else R.style.Theme_Nexo_Light_Pink
            BrowserPrefs.ACCENT_RED -> if (dark) R.style.Theme_Nexo_Dark_Red else R.style.Theme_Nexo_Light_Red
            else -> if (dark) R.style.Theme_Nexo_Dark_Violet else R.style.Theme_Nexo_Light_Violet
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

    /** One owner per window: combine IME and safe areas with max(), never add twice. */
    private fun installInsets(activity: Activity) {
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
    // Primitivas y tokens UI 5.0
    // ---------------------------------------------------------------

    private fun dp(activity: Activity, value: Float): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    /** Contorno hairline sutil: evita bordes pesados y mantiene definición limpia en OLED. */
    fun hairline(p: BrowserPalette): Int = when {
        p.isAmoled -> ColorUtils.setAlphaComponent(p.outlineVariant, 165)
        p.isDark -> ColorUtils.setAlphaComponent(p.outlineVariant, 110)
        else -> ColorUtils.setAlphaComponent(p.outlineVariant, 115)
    }

    fun fontRegular(activity: Activity): Typeface =
        runCatching { activity.resources.getFont(R.font.inter_variable) }.getOrDefault(Typeface.SANS_SERIF)

    fun fontMedium(activity: Activity): Typeface =
        Typeface.create(fontRegular(activity), 500, false)

    fun fontSemiBold(activity: Activity): Typeface =
        Typeface.create(fontRegular(activity), 600, false)

    fun fontBold(activity: Activity): Typeface =
        Typeface.create(fontRegular(activity), Typeface.BOLD)

    fun fontMono(activity: Activity): Typeface =
        runCatching { activity.resources.getFont(R.font.jetbrains_mono) }.getOrDefault(Typeface.MONOSPACE)

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
        val stroke = if (!primary && p.isAmoled) hairline(p) else null
        val rippleColor = ColorUtils.setAlphaComponent(foreground, 36)

        button.background = ripple(activity, rounded(activity, bgColor, 24f, stroke), 24f, rippleColor)
        button.setTextColor(tint(foreground))
        button.compoundDrawableTintList = tint(foreground)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
        button.minHeight = dp(activity, 48f)
        button.isAllCaps = false
        button.letterSpacing = 0.005f
        button.typeface = fontSemiBold(activity)
    }

    /** Icono plano con ripple circular; [active] lo muestra en cápsula tonal suave. */
    fun styleIconButton(activity: Activity, button: ImageButton, active: Boolean = false) {
        val p = palette(activity)
        val inset = dp(activity, 4f)
        val content = if (active) InsetDrawable(rounded(activity, p.accentSoft, 20f), inset) else null
        button.background = ripple(activity, content, 24f)
        button.imageTintList = tint(if (active) p.onAccentSoft else p.text)
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

    /** Título de sección (pequeño, en el color de acento con tracking editorial). */
    fun styleSectionLabel(activity: Activity, text: TextView) {
        val p = palette(activity)
        text.setTextColor(p.accent)
        text.textSize = 11.5f
        text.isAllCaps = true
        text.letterSpacing = 0.08f
        text.typeface = fontBold(activity)
    }

    /** Contenedor tipo tarjeta: superficie tonal redondeada con hairline discreto. */
    fun styleCard(activity: Activity, view: View, radiusDp: Float = 20f) {
        val p = palette(activity)
        val bg = if (p.isDark) p.surfaceContainerLow else p.surfaceContainerLowest
        view.background = rounded(activity, bg, radiusDp, hairline(p))
        view.clipToOutline = true
    }

    /** Fondo de barra (superficie) con una línea hairline en el borde indicado. */
    fun barBackground(activity: Activity, lineOnTop: Boolean): Drawable {
        val p = palette(activity)
        return LayerDrawable(
            arrayOf(ColorDrawable(p.surfaceContainerLow), ColorDrawable(hairline(p)))
        ).apply {
            setLayerHeight(1, dp(activity, 1f).coerceAtLeast(1))
            setLayerGravity(1, if (lineOnTop) Gravity.TOP else Gravity.BOTTOM)
        }
    }

    /** Contador de pestañas: cuadro redondeado limpio y proporcionado (24dp ópticos en 48dp). */
    fun styleTabCounter(activity: Activity, button: Button) {
        val p = palette(activity)
        val inset = dp(activity, 12f)
        val box = InsetDrawable(
            rounded(activity, Color.TRANSPARENT, 7f, p.text, 1.75f),
            inset
        )
        button.fontFeatureSettings = "tnum"
        button.typeface = fontBold(activity)
        button.textSize = 11.5f
        button.background = ripple(activity, box, 24f)
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
            badge.textSize = if (label.length > 2) 10.5f else 15f
            val (badgeBg, badgeFg) = if (row.statusTone != null) {
                NexoUi.toneColors(p, row.statusTone)
            } else {
                p.accentSoft to p.onAccentSoft
            }
            badge.setTextColor(badgeFg)
            badge.background = rounded(activity, badgeBg, 14f)

            if (row.status.isBlank()) {
                statusPill.visibility = View.GONE
            } else {
                val (bg, fg) = NexoUi.toneColors(p, row.statusTone ?: NexoUi.Tone.NEUTRAL)
                statusPill.text = row.status
                statusPill.setTextColor(fg)
                statusPill.background = rounded(activity, bg, 12f)
                statusPill.visibility = View.VISIBLE
            }
        }
    }

    private fun createRow(activity: Activity): RowHolder {
        val p = palette(activity)
        val d = activity.resources.displayMetrics.density
        val cardSurface = if (p.isDark) p.surfaceContainerLow else p.surfaceContainerLowest

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (13 * d).toInt(), (16 * d).toInt(), (13 * d).toInt())
            minimumHeight = (68 * d).toInt()
            background = ripple(
                activity,
                rounded(activity, cardSurface, 20f, hairline(p)),
                20f
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val badge = TextView(activity).apply {
            gravity = Gravity.CENTER
            setTextColor(p.onAccentSoft)
            typeface = fontBold(activity)
            background = rounded(activity, p.accentSoft, 14f)
            layoutParams = LinearLayout.LayoutParams((42 * d).toInt(), (42 * d).toInt()).apply {
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
            typeface = fontSemiBold(activity)
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
            alpha = 0.85f
        }

        val statusPill = TextView(activity).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = fontBold(activity)
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
        val field = rounded(activity, fill, 14f, if (p.isAmoled) hairline(p) else null)
        val chevron = ChevronDrawable(p.muted, dp(activity, 1.8f).toFloat())

        spinner.background = LayerDrawable(arrayOf(field, chevron)).apply {
            val size = dp(activity, 11f)
            setLayerSize(1, size, size)
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInsetEnd(1, dp(activity, 14f))
        }
        spinner.backgroundTintList = null
        spinner.setPopupBackgroundDrawable(
            LayerDrawable(arrayOf(rounded(activity, p.surfaceContainerHigh, 18f, hairline(p)))).apply {
                setPadding(dp(activity, 6f), dp(activity, 8f), dp(activity, 6f), dp(activity, 8f))
            }
        )
    }

    fun styleSwitch(activity: Activity, toggle: Switch) {
        val p = palette(activity)
        toggle.setTextColor(p.text)
        toggle.typeface = fontMedium(activity)
        toggle.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(p.accent, p.muted)
        )
        toggle.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                ColorUtils.setAlphaComponent(p.accent, 115),
                ColorUtils.setAlphaComponent(p.outline, if (p.isDark) 115 else 95)
            )
        )
    }

    /**
     * Campo de texto tipo píldora con contraste garantizado tanto sobre el lienzo
     * principal como dentro de hojas inferiores elevadas.
     */
    fun styleEdit(activity: Activity, edit: EditText) {
        val p = palette(activity)
        val normalBg = if (p.isDark) p.surfaceContainerLow else p.surfaceContainer
        val focusedBg = if (p.isDark) p.surfaceContainerHigh else p.surfaceContainerLowest
        val normal = rounded(activity, normalBg, 24f, hairline(p))
        val focused = rounded(activity, focusedBg, 24f, p.accent, 1.6f)

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

    /** Cápsula unificada de dirección + estado del sitio + Nexo AI (UI 5.0). */
    fun styleOmniboxCapsule(activity: Activity, capsule: View, focused: Boolean = false) {
        val p = palette(activity)
        val fill = when {
            focused && p.isDark -> p.surfaceContainerHighest
            p.isAmoled -> 0xFF13161E.toInt()
            p.isDark -> p.surfaceContainerHigh
            else -> p.surfaceContainerLowest
        }
        val stroke = if (focused) p.accent else hairline(p)
        val strokeWidth = if (focused) 1.75f else 1f
        capsule.background = rounded(activity, fill, 24f, stroke, strokeWidth)
        capsule.elevation = if (p.isDark) 0f else dp(activity, 2f).toFloat()
    }

    /** Estiliza el campo de texto dentro de la cápsula unificada (sin fondo propio). */
    fun styleOmniboxInput(activity: Activity, edit: EditText) {
        val p = palette(activity)
        edit.background = null
        edit.backgroundTintList = null
        edit.setTextColor(p.text)
        edit.setHintTextColor(p.muted)
        edit.typeface = fontMedium(activity)
        edit.highlightColor = ColorUtils.setAlphaComponent(p.accent, 75)
        edit.gravity = Gravity.CENTER_VERTICAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            edit.textCursorDrawable = GradientDrawable().apply {
                setColor(p.accent)
                setSize(dp(activity, 2f), dp(activity, 20f))
            }
        }
    }

    /** Botón permanente ✨ de Nexo AI integrado en el borde derecho de la cápsula de direcciones. */
    fun styleAiSparkButton(activity: Activity, button: ImageButton) {
        val p = palette(activity)
        val hInset = dp(activity, 3f)
        val vInset = dp(activity, 6f)
        val pill = InsetDrawable(rounded(activity, p.accentSoft, 18f), hInset, vInset, hInset, vInset)
        val rippleColor = ColorUtils.setAlphaComponent(p.onAccentSoft, 40)
        button.background = ripple(activity, pill, 20f, rippleColor)
        button.imageTintList = ColorStateList.valueOf(if (p.isDark) p.onAccentSoft else p.accent)
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.elevation = 0f
    }

    /** Botón menú ⋮ centrado con precisión óptica junto al contador de pestañas. */
    fun styleMenuIconButton(activity: Activity, button: Button) {
        val p = palette(activity)
        val dots = MenuDotsDrawable(p.text, dp(activity, 2f).toFloat())
        button.backgroundTintList = null
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        button.background = ripple(activity, dots, 24f)
        button.setTextColor(tint(p.text))
        button.stateListAnimator = null
        button.elevation = 0f
        button.minHeight = dp(activity, 48f)
        button.isAllCaps = false
    }

    /** Dock inferior flotante con pista discreta de gestos (UI 5.0). */
    fun styleFloatingDock(activity: Activity, dock: View, gesturePill: View? = null) {
        val p = palette(activity)
        val fill = when {
            p.isAmoled -> 0xFF0E1118.toInt()
            p.isDark -> p.surfaceContainer
            else -> p.surfaceContainerLowest
        }
        dock.background = rounded(activity, fill, 26f, hairline(p))
        dock.elevation = if (p.isDark) 0f else dp(activity, 4f).toFloat()
        gesturePill?.background = rounded(activity, ColorUtils.setAlphaComponent(p.muted, 90), 2f)
    }

    /** Acento tonal suave para la acción principal (+) del dock inferior. */
    fun styleDockPrimaryIcon(activity: Activity, button: ImageButton) {
        val p = palette(activity)
        val hInset = dp(activity, 10f)
        val vInset = dp(activity, 6f)
        val well = InsetDrawable(rounded(activity, p.surfaceContainerHighest, 18f), hInset, vInset, hInset, vInset)
        button.background = ripple(activity, well, 22f)
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
        val step = radius * 3.1f
        canvas.drawCircle(cx, cy - step, radius, paint)
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawCircle(cx, cy + step, radius, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
