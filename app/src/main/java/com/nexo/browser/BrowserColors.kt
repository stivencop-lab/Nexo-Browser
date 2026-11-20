package com.nexo.browser

import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette

/** Opaque, semantic tokens. Kept independent of Views so contrast is testable. */
data class StatusColors(val color: Int, val onColor: Int, val container: Int, val onContainer: Int)

data class BrowserPalette(
    val primary: StatusColors,
    val secondary: StatusColors,
    val tertiary: StatusColors,
    val error: StatusColors,
    val success: StatusColors,
    val warning: StatusColors,
    val info: StatusColors,
    val background: Int,
    val surfaceDim: Int,
    val surfaceBright: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val text: Int,
    val muted: Int,
    val outline: Int,
    val outlineVariant: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val inversePrimary: Int,
    val scrim: Int,
    val isDark: Boolean,
    val isAmoled: Boolean,
    /** Color de marca del acento tal cual (anillos, puntos, pistas de interruptor). */
    val accentSwatch: Int = primary.color,
    /** Divisor translúcido: blanco 6 % en oscuro, tinta 7 % en Paper. */
    val divider: Int = 0x0FFFFFFF,
    /** Cristal líquido: relleno translúcido de las píldoras flotantes. */
    val glass: Int = 0xB8141418.toInt(),
    /** Borde especular superior del cristal. */
    val glassEdge: Int = 0x38FFFFFF,
    val isMonochrome: Boolean = false
) {
    // Compatibility names used by existing View components; every alias has one role.
    val surface get() = surfaceContainerLow
    val elevated get() = surfaceContainerHigh
    val accent get() = primary.color
    val onAccent get() = primary.onColor
    val accentSoft get() = primary.container
    val onAccentSoft get() = primary.onContainer
    val danger get() = error.color
    /** Lienzo: alias editorial del fondo. */
    val canvas get() = background
    /** Tinta: texto principal de alto contraste. */
    val ink get() = text
}

/**
 * Nexo UI 7.0 «Liquid Craft»: tres materiales neutros puros (Obsidian, OLED y Paper)
 * y cinco acentos adultos. Ningún neutro se tiñe con el acento: la marca vive solo
 * en controles activos. Todos los roles de texto se corrigen en tono HCT hasta
 * cumplir WCAG AA sobre cada superficie, y la tinta principal cumple AAA.
 */
object BrowserColors {
    /** Valor centinela: el acento Monocromo usa la propia tinta del tema. */
    const val MONOCHROME = 0x00000000
    const val SIGNAL = 0xFF2563EB.toInt()
    const val COBALT = 0xFF3B82F6.toInt()
    const val ORANGE = 0xFFF97316.toInt()
    const val SAGE = 0xFF16A34A.toInt()

    /** Acentos ofrecidos por Configuración, en orden de presentación. */
    val ACCENTS = listOf(MONOCHROME, SIGNAL, COBALT, ORANGE, SAGE)

    const val INK_DARK = 0xFFF4F4F5.toInt()
    const val INK_PAPER = 0xFF111113.toInt()

    private data class Neutrals(
        val canvas: Int, val surface: Int, val container: Int, val elevated: Int, val highest: Int,
        val ink: Int, val muted: Int, val outline: Int, val outlineVariant: Int,
        val divider: Int, val glass: Int, val glassEdge: Int
    )

    private val OBSIDIAN = Neutrals(
        canvas = 0xFF09090B.toInt(), surface = 0xFF131316.toInt(), container = 0xFF17171A.toInt(),
        elevated = 0xFF1B1B20.toInt(), highest = 0xFF232329.toInt(),
        ink = INK_DARK, muted = 0xFF8E8E98.toInt(), outline = 0xFF3A3A42.toInt(), outlineVariant = 0xFF2E2E35.toInt(),
        divider = 0x0FFFFFFF, glass = 0xB8141418.toInt(), glassEdge = 0x38FFFFFF
    )
    private val OLED = Neutrals(
        canvas = 0xFF000000.toInt(), surface = 0xFF0E0E11.toInt(), container = 0xFF121215.toInt(),
        elevated = 0xFF17171C.toInt(), highest = 0xFF1F1F25.toInt(),
        ink = INK_DARK, muted = 0xFF8E8E98.toInt(), outline = 0xFF3A3A42.toInt(), outlineVariant = 0xFF2E2E35.toInt(),
        divider = 0x12FFFFFF, glass = 0xC70E0E11.toInt(), glassEdge = 0x38FFFFFF
    )
    // Paper: muted #6E6E77 del brief baja a 4.1:1 sobre #ECEAE4; #64646D conserva el gris cálido y cumple AA.
    private val PAPER = Neutrals(
        canvas = 0xFFF6F5F1.toInt(), surface = 0xFFFFFFFF.toInt(), container = 0xFFF1F0EB.toInt(),
        elevated = 0xFFECEAE4.toInt(), highest = 0xFFE4E2DB.toInt(),
        ink = INK_PAPER, muted = 0xFF64646D.toInt(), outline = 0xFFC9C7C0.toInt(), outlineVariant = 0xFFDEDCD5.toInt(),
        divider = 0x12111113, glass = 0xC7FFFFFF.toInt(), glassEdge = 0xF2FFFFFF.toInt()
    )

    private val DARK_TEXT_SURFACES = listOf(OBSIDIAN, OLED).flatMap {
        listOf(it.canvas, it.surface, it.container, it.elevated, it.highest)
    }
    private val PAPER_TEXT_SURFACES = with(PAPER) { listOf(canvas, surface, container, elevated, highest) }

    fun create(seed: Int, dark: Boolean, amoled: Boolean = false): BrowserPalette {
        val oled = dark && amoled
        val n = when {
            oled -> OLED
            dark -> OBSIDIAN
            else -> PAPER
        }
        val textSurfaces = if (dark) DARK_TEXT_SURFACES else PAPER_TEXT_SURFACES
        val mono = seed == MONOCHROME
        val swatch = if (mono) n.ink else seed or 0xFF000000.toInt()

        // El acento como texto/acción: mismo matiz, tono corregido hasta AA en todas las superficies.
        val accent = if (mono) n.ink else readable(swatch, textSurfaces, 4.5, lighten = dark)
        val onAccent = bestForeground(accent, n.canvas, n.ink)
        val accentContainer = if (mono) n.highest else blend(swatch, n.elevated, if (dark) 0.20f else 0.13f)
        val onAccentContainer = if (mono) n.ink else readable(accent, listOf(accentContainer), 4.5, lighten = dark)

        fun status(seedColor: Int): StatusColors {
            val tones = TonalPalette.fromInt(seedColor)
            val color = readable(if (dark) tones.tone(80) else tones.tone(40), textSurfaces, 4.5, lighten = dark)
            val container = if (dark) blend(tones.tone(60), n.elevated, 0.22f) else blend(tones.tone(50), n.surface, 0.14f)
            val onContainer = readable(if (dark) tones.tone(90) else tones.tone(30), listOf(container), 4.5, lighten = dark)
            return StatusColors(color, bestForeground(color, n.canvas, n.ink), container, onContainer)
        }

        val inkRole = StatusColors(n.ink, n.canvas, n.highest, n.ink)
        return BrowserPalette(
            primary = StatusColors(accent, onAccent, accentContainer, onAccentContainer),
            secondary = inkRole,
            tertiary = StatusColors(n.muted, n.canvas, n.elevated, n.ink),
            error = status(0xFFDC2626.toInt()),
            success = status(0xFF16A34A.toInt()),
            warning = status(0xFFB45309.toInt()),
            info = status(0xFF2563EB.toInt()),
            background = n.canvas,
            surfaceDim = n.canvas,
            surfaceBright = n.highest,
            surfaceContainerLowest = if (dark) n.canvas else n.surface,
            surfaceContainerLow = n.surface,
            surfaceContainer = n.container,
            surfaceContainerHigh = n.elevated,
            surfaceContainerHighest = n.highest,
            text = n.ink,
            muted = n.muted,
            outline = n.outline,
            outlineVariant = n.outlineVariant,
            inverseSurface = n.ink,
            inverseOnSurface = n.canvas,
            inversePrimary = if (mono) n.canvas else readable(swatch, listOf(n.ink), 4.5, lighten = !dark),
            scrim = 0xFF000000.toInt(),
            isDark = dark,
            isAmoled = oled,
            accentSwatch = swatch,
            divider = n.divider,
            glass = n.glass,
            glassEdge = n.glassEdge,
            isMonochrome = mono
        )
    }

    /** Desplaza el tono HCT de [color] hasta alcanzar [min]:1 frente a todas las [surfaces]. */
    fun readable(color: Int, surfaces: List<Int>, min: Double, lighten: Boolean): Int {
        if (surfaces.all { UiContrast.ratio(color, it) >= min }) return color
        val hct = Hct.fromInt(color)
        var tone = hct.tone
        var candidate = color
        while (tone in 0.0..100.0) {
            tone += if (lighten) 1.0 else -1.0
            candidate = Hct.from(hct.hue, hct.chroma, tone.coerceIn(0.0, 100.0)).toInt()
            if (surfaces.all { UiContrast.ratio(candidate, it) >= min }) return candidate
        }
        return candidate
    }

    /** Entre la tinta clara y la oscura del sistema, la que más contraste da sobre [bg]. */
    private fun bestForeground(bg: Int, canvas: Int, ink: Int): Int {
        val themed = listOf(canvas, ink).maxBy { UiContrast.ratio(bg, it) }
        if (UiContrast.ratio(bg, themed) >= 4.5) return themed
        return listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()).maxBy { UiContrast.ratio(bg, it) }
    }

    /** Mezcla opaca: [amount] de [top] sobre [bottom]. */
    fun blend(top: Int, bottom: Int, amount: Float): Int {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(top, s) * amount + ch(bottom, s) * (1f - amount)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
