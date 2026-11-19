package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavConfigTest {
    @Test fun modeParsingFallsBackToClassic() {
        assertEquals(NavConfig.Mode.CLASSIC, NavConfig.Mode.fromId(null))
        assertEquals(NavConfig.Mode.CLASSIC, NavConfig.Mode.fromId("unknown"))
        assertEquals(NavConfig.Mode.COMPACT, NavConfig.Mode.fromId("compact"))
        assertEquals(NavConfig.Mode.CUSTOM, NavConfig.Mode.fromId("custom"))
    }

    @Test fun actionParsingDeduplicatesAndEnforcesBounds() {
        assertEquals(NavConfig.DEFAULT_ACTIONS, NavConfig.parseActions(null))
        assertEquals(NavConfig.DEFAULT_ACTIONS, NavConfig.parseActions("back,forward")) // < MIN_ACTIONS
        assertEquals(
            listOf(NavConfig.Action.BACK, NavConfig.Action.HISTORY, NavConfig.Action.AI),
            NavConfig.parseActions("back, history, back, bogus, ai")
        )
        val tooMany = NavConfig.Action.entries.joinToString(",") { it.id }
        assertEquals(NavConfig.MAX_ACTIONS, NavConfig.parseActions(tooMany).size)
    }

    @Test fun toggleAndMovePreserveInvariants() {
        var list = listOf(NavConfig.Action.BACK, NavConfig.Action.HOME, NavConfig.Action.NEW_TAB)
        // Cannot drop below MIN_ACTIONS (3)
        assertEquals(list, NavConfig.toggle(list, NavConfig.Action.HOME))

        // Add up to MAX_ACTIONS (6)
        list = NavConfig.toggle(list, NavConfig.Action.HISTORY)
        list = NavConfig.toggle(list, NavConfig.Action.AI)
        list = NavConfig.toggle(list, NavConfig.Action.SHIELD)
        assertEquals(6, list.size)
        // Cannot exceed MAX_ACTIONS
        assertEquals(list, NavConfig.toggle(list, NavConfig.Action.EXTENSIONS))

        // Move action up & down
        val moved = NavConfig.move(list, NavConfig.Action.SHIELD, -2)
        assertEquals(NavConfig.Action.SHIELD, moved[3])
        // Round-trip serialization
        assertEquals(moved, NavConfig.parseActions(NavConfig.serializeActions(moved)))
    }

    @Test fun smartAutoHideNeverHidesDuringEssentialInteractions() {
        assertTrue(
            NavConfig.shouldAutoHideDock(
                mode = NavConfig.Mode.CLASSIC,
                autoHideEnabled = true,
                scrollingDown = true,
                scrollY = 240,
                isStartPage = false,
                omniboxFocused = false,
                findBarVisible = false,
                imeVisible = false,
                fullScreen = false,
                isPip = false
            )
        )
        // Scrolling up immediately restores dock
        assertFalse(
            NavConfig.shouldAutoHideDock(
                NavConfig.Mode.CLASSIC, true, scrollingDown = false, scrollY = 240,
                isStartPage = false, omniboxFocused = false, findBarVisible = false,
                imeVisible = false, fullScreen = false, isPip = false
            )
        )
        // Never hide when editing URL, on StartPage, findBar open, IME open, or near top
        assertFalse(NavConfig.shouldAutoHideDock(NavConfig.Mode.CLASSIC, true, true, 300, false, omniboxFocused = true, false, false, false, false))
        assertFalse(NavConfig.shouldAutoHideDock(NavConfig.Mode.CLASSIC, true, true, 300, isStartPage = true, false, false, false, false, false))
        assertFalse(NavConfig.shouldAutoHideDock(NavConfig.Mode.CLASSIC, true, true, 300, false, false, findBarVisible = true, false, false, false))
        assertFalse(NavConfig.shouldAutoHideDock(NavConfig.Mode.CLASSIC, true, true, 300, false, false, false, imeVisible = true, false, false))
        assertFalse(NavConfig.shouldAutoHideDock(NavConfig.Mode.CLASSIC, true, true, scrollY = 40, false, false, false, false, false, false))
        assertFalse(NavConfig.shouldAutoHideDock(NavConfig.Mode.COMPACT, true, true, 300, false, false, false, false, false, false))
    }
}
