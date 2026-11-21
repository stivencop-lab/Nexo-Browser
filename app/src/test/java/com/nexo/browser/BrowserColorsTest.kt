package com.nexo.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserColorsTest {
    private val seeds = BrowserColors.ACCENTS +
        listOf(0xFF777777.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFEC4899.toInt())

    @Test fun textAndStatusColorsRemainReadableForEveryAccentAndMode() {
        for (seed in seeds) for ((dark, oled) in listOf(false to false, true to false, true to true)) {
            val p = BrowserColors.create(seed, dark, oled)
            val surfaces = listOf(p.background, p.surface, p.elevated, p.surfaceContainerHighest)
            for (surface in surfaces) {
                assertTrue("Body text must meet AAA", UiContrast.ratio(p.text, surface) >= 7.0)
                assertTrue("Supporting text must meet AA", UiContrast.ratio(p.muted, surface) >= 4.5)
                for (color in listOf(p.accent, p.danger, p.success.color, p.warning.color, p.info.color)) {
                    assertTrue("Action/status must meet AA", UiContrast.ratio(color, surface) >= 4.5)
                }
            }
            for (role in listOf(p.primary, p.secondary, p.tertiary, p.error, p.success, p.warning, p.info)) {
                assertTrue(UiContrast.ratio(role.color, role.onColor) >= 4.5)
                assertTrue(UiContrast.ratio(role.container, role.onContainer) >= 4.5)
                assertEquals(255, role.container ushr 24)
            }
        }
    }

    @Test fun oledKeepsBlackCanvasAndVisibleTonalDepth() {
        val p = BrowserColors.create(BrowserColors.SIGNAL, dark = true, amoled = true)
        assertEquals(0xFF000000.toInt(), p.background)
        assertEquals(p.background, p.surfaceContainerLowest)
        val layers = listOf(p.background, p.surfaceContainerLow, p.surfaceContainer,
            p.surfaceContainerHigh, p.surfaceContainerHighest).map(UiContrast::luminance)
        assertTrue(layers.zipWithNext().all { (lower, upper) -> upper > lower })
        assertTrue(UiContrast.ratio(p.outlineVariant, p.background) > 1.5)
    }

    @Test fun liquidCraftNeutralsMatchTheBrief() {
        val obsidian = BrowserColors.create(BrowserColors.MONOCHROME, dark = true)
        assertEquals(0xFF09090B.toInt(), obsidian.canvas)
        assertEquals(0xFF131316.toInt(), obsidian.surface)
        assertEquals(0xFF1B1B20.toInt(), obsidian.elevated)
        assertEquals(0xFFF4F4F5.toInt(), obsidian.ink)
        assertEquals(0xFF8E8E98.toInt(), obsidian.muted)
        val oled = BrowserColors.create(BrowserColors.MONOCHROME, dark = true, amoled = true)
        assertEquals(0xFF0E0E11.toInt(), oled.surface)
        assertEquals(0xFF17171C.toInt(), oled.elevated)
        val paper = BrowserColors.create(BrowserColors.MONOCHROME, dark = false)
        assertEquals(0xFFF5F4F0.toInt(), paper.canvas)
        assertEquals(0xFFFFFFFF.toInt(), paper.surface)
        assertEquals(0xFFECEAE4.toInt(), paper.elevated)
        assertEquals(0xFF111113.toInt(), paper.ink)
        // Ningún neutro se tiñe con el acento.
        for (seed in BrowserColors.ACCENTS) {
            val p = BrowserColors.create(seed, dark = true)
            assertEquals(obsidian.background, p.background)
            assertEquals(obsidian.surfaceContainerHighest, p.surfaceContainerHighest)
        }
    }

    @Test fun monochromeAccentIsTheInkItself() {
        assertEquals(0xFFF4F4F5.toInt(), BrowserColors.create(BrowserColors.MONOCHROME, true).accent)
        assertEquals(0xFF111113.toInt(), BrowserColors.create(BrowserColors.MONOCHROME, false).accent)
        assertTrue(BrowserColors.create(BrowserColors.MONOCHROME, false).isMonochrome)
    }

    @Test fun lightModeNeverTurnsBlackWhenOledFlagIsStale() {
        val p = BrowserColors.create(BrowserColors.COBALT, dark = false, amoled = true)
        assertFalse(p.isAmoled)
        assertTrue(UiContrast.luminance(p.background) > 0.9)
    }

    @Test fun actionGridPreservesSpaceAtLargeFontsAndNarrowWidths() {
        assertEquals(3, UiLayout.actionColumns(328f, 1f))
        assertEquals(1, UiLayout.actionColumns(288f, 2f))
        assertEquals(2, UiLayout.actionColumns(568f, 2f))
        assertEquals(4, UiLayout.actionColumns(1080f, 1f))
        assertEquals(1, UiLayout.actionColumns(0f, 1f))
    }
}
