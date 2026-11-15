package com.nexo.browser

import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.palettes.TonalPalette

/** Opaque, semantic HCT tokens. Kept independent of Views so contrast is testable. */
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
    val isAmoled: Boolean
) {
    // Compatibility names used by existing View components; every alias has one role.
    val surface get() = surfaceContainerLow
    val elevated get() = surfaceContainerHigh
    val accent get() = primary.color
    val onAccent get() = primary.onColor
    val accentSoft get() = primary.container
    val onAccentSoft get() = primary.onContainer
    val danger get() = error.color
}

object BrowserColors {
    const val COBALT = 0xFF3D6BF2.toInt()
    const val VIOLET = 0xFF7C3AED.toInt()
    const val CYAN = 0xFF06B6D4.toInt()
    const val LIME = 0xFF22C55E.toInt()
    const val ORANGE = 0xFFF97316.toInt()
    const val PINK = 0xFFEC4899.toInt()
    const val RED = 0xFFEF4444.toInt()

    fun create(seed: Int, dark: Boolean, amoled: Boolean = false): BrowserPalette {
        val oled = dark && amoled
        val scheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0, ColorSpec.SpecVersion.SPEC_2021)
        val c = MaterialDynamicColors()
        fun status(seedColor: Int): StatusColors {
            val tones = TonalPalette.fromInt(seedColor)
            return if (dark) StatusColors(tones.tone(80), tones.tone(20), tones.tone(30), tones.tone(90))
            else StatusColors(tones.tone(40), tones.tone(100), tones.tone(90), tones.tone(10))
        }
        return BrowserPalette(
            primary = StatusColors(c.primary().getArgb(scheme), c.onPrimary().getArgb(scheme), c.primaryContainer().getArgb(scheme), c.onPrimaryContainer().getArgb(scheme)),
            secondary = StatusColors(c.secondary().getArgb(scheme), c.onSecondary().getArgb(scheme), c.secondaryContainer().getArgb(scheme), c.onSecondaryContainer().getArgb(scheme)),
            tertiary = StatusColors(c.tertiary().getArgb(scheme), c.onTertiary().getArgb(scheme), c.tertiaryContainer().getArgb(scheme), c.onTertiaryContainer().getArgb(scheme)),
            error = StatusColors(c.error().getArgb(scheme), c.onError().getArgb(scheme), c.errorContainer().getArgb(scheme), c.onErrorContainer().getArgb(scheme)),
            success = status(0xFF146C2E.toInt()), warning = status(0xFF7A5900.toInt()), info = status(0xFF006493.toInt()),
            background = if (oled) 0xFF000000.toInt() else c.surface().getArgb(scheme),
            surfaceDim = if (oled) 0xFF000000.toInt() else c.surfaceDim().getArgb(scheme),
            surfaceBright = c.surfaceBright().getArgb(scheme),
            surfaceContainerLowest = if (oled) 0xFF000000.toInt() else c.surfaceContainerLowest().getArgb(scheme),
            surfaceContainerLow = c.surfaceContainerLow().getArgb(scheme),
            surfaceContainer = c.surfaceContainer().getArgb(scheme),
            surfaceContainerHigh = c.surfaceContainerHigh().getArgb(scheme),
            surfaceContainerHighest = c.surfaceContainerHighest().getArgb(scheme),
            text = c.onSurface().getArgb(scheme), muted = c.onSurfaceVariant().getArgb(scheme),
            outline = c.outline().getArgb(scheme), outlineVariant = c.outlineVariant().getArgb(scheme),
            inverseSurface = c.inverseSurface().getArgb(scheme), inverseOnSurface = c.inverseOnSurface().getArgb(scheme),
            inversePrimary = c.inversePrimary().getArgb(scheme), scrim = c.scrim().getArgb(scheme),
            isDark = dark, isAmoled = oled
        )
    }
}
