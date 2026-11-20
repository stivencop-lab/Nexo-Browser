package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiContrastTest {
    @Test fun accentLabelsMeetNormalTextContrast() {
        listOf(0x2563EB, 0x3B82F6, 0xF97316, 0x16A34A, 0x111113, 0xF4F4F5).forEach { rgb ->
            val background = rgb or 0xFF000000.toInt()
            val a = UiContrast.luminance(background)
            val b = UiContrast.luminance(UiContrast.foreground(background))
            val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
            assertTrue("Insufficient contrast for ${rgb.toString(16)}: $ratio", ratio >= 4.5)
        }
    }

    @Test fun blackAndWhiteSurfacesUseOppositeForegrounds() {
        assertEquals(0xFFFFFFFF.toInt(), UiContrast.foreground(0xFF000000.toInt()))
        assertEquals(0xFF000000.toInt(), UiContrast.foreground(0xFFFFFFFF.toInt()))
    }

    @Test fun signalBlueButtonsUseWhiteLabels() {
        assertEquals(0xFFFFFFFF.toInt(), UiContrast.foreground(0xFF2563EB.toInt()))
    }
}
