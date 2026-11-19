package com.nexo.browser

import com.nexo.browser.dark.DarkEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkEngineTest {
    @Test
    fun `auto mode follows app theme and system dark state`() {
        val systemDark = DarkEngine.resolvePolicy(
            globalMode = DarkEngine.MODE_AUTO,
            strategy = DarkEngine.STRATEGY_HYBRID,
            appTheme = BrowserPrefs.THEME_SYSTEM,
            systemDark = true,
            siteExcluded = false
        )
        assertTrue(systemDark.enabled)
        assertFalse(systemDark.oled)
        assertEquals(DarkEngine.COLOR_DARK_BG, systemDark.backgroundHex)

        val systemLight = DarkEngine.resolvePolicy(
            globalMode = DarkEngine.MODE_AUTO,
            strategy = DarkEngine.STRATEGY_HYBRID,
            appTheme = BrowserPrefs.THEME_SYSTEM,
            systemDark = false,
            siteExcluded = false
        )
        assertFalse(systemLight.enabled)

        val oledTheme = DarkEngine.resolvePolicy(
            globalMode = DarkEngine.MODE_AUTO,
            strategy = DarkEngine.STRATEGY_HYBRID,
            appTheme = BrowserPrefs.THEME_OLED,
            systemDark = false,
            siteExcluded = false
        )
        assertTrue(oledTheme.enabled)
        assertTrue(oledTheme.oled)
        assertEquals(DarkEngine.COLOR_OLED_BG, oledTheme.backgroundHex)
    }

    @Test
    fun `explicit modes override app theme`() {
        val forcedLight = DarkEngine.resolvePolicy(
            globalMode = DarkEngine.MODE_LIGHT,
            strategy = DarkEngine.STRATEGY_CSS_FAST,
            appTheme = BrowserPrefs.THEME_OLED,
            systemDark = true,
            siteExcluded = false
        )
        assertFalse(forcedLight.enabled)
        assertEquals(DarkEngine.STRATEGY_CSS_FAST, forcedLight.strategy)

        val forcedOled = DarkEngine.resolvePolicy(
            globalMode = DarkEngine.MODE_OLED,
            strategy = DarkEngine.STRATEGY_HYBRID,
            appTheme = BrowserPrefs.THEME_LIGHT,
            systemDark = false,
            siteExcluded = false
        )
        assertTrue(forcedOled.enabled)
        assertTrue(forcedOled.oled)
        assertEquals(DarkEngine.COLOR_OLED_BG, forcedOled.backgroundHex)
    }

    @Test
    fun `site exclusions disable dark engine unless site explicitly forces dark`() {
        val excluded = DarkEngine.resolvePolicy(
            globalMode = DarkEngine.MODE_DARK,
            strategy = DarkEngine.STRATEGY_HYBRID,
            appTheme = BrowserPrefs.THEME_MIDNIGHT,
            systemDark = true,
            siteExcluded = true
        )
        assertFalse(excluded.enabled)
        assertTrue(excluded.excluded)

        val siteForcedOled = DarkEngine.resolvePolicy(
            globalMode = DarkEngine.MODE_LIGHT,
            strategy = DarkEngine.STRATEGY_HYBRID,
            appTheme = BrowserPrefs.THEME_LIGHT,
            systemDark = false,
            siteExcluded = false,
            siteOverride = DarkEngine.MODE_OLED
        )
        assertTrue(siteForcedOled.enabled)
        assertTrue(siteForcedOled.oled)
    }

    @Test
    fun `normalizeHost extracts clean domain without www`() {
        assertEquals("example.com", DarkEngine.normalizeHost("https://www.Example.COM/path?q=1"))
        assertEquals("docs.github.com", DarkEngine.normalizeHost("docs.github.com:443/en"))
        assertEquals("", DarkEngine.normalizeHost("   "))
    }
}
