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

    private fun policy(mode: String, excluded: Boolean = false, override: String? = null) = DarkEngine.resolvePolicy(
        globalMode = mode,
        strategy = DarkEngine.STRATEGY_HYBRID,
        appTheme = BrowserPrefs.THEME_SYSTEM,
        systemDark = false,
        siteExcluded = excluded,
        siteOverride = override
    )

    @Test
    fun `boot scripts give every site only its own policy and site rules run after the global one`() {
        val global = policy(DarkEngine.MODE_DARK)
        val scripts = DarkEngine.bootScripts(global, mapOf(
            "excluded.com" to policy(DarkEngine.MODE_DARK, excluded = true),
            "also-excluded.org" to policy(DarkEngine.MODE_DARK, excluded = true),
            "oled.net" to policy(DarkEngine.MODE_DARK, override = DarkEngine.MODE_OLED),
            "same.com" to policy(DarkEngine.MODE_DARK),
            "bad host!" to policy(DarkEngine.MODE_DARK, excluded = true)
        ))
        assertEquals(setOf("*"), scripts.first().origins)
        assertTrue(scripts.first().script.contains("\"enabled\":true"))
        // Excluidos juntos (misma política), OLED aparte; el sitio igual al global y el nombre inválido no generan reglas.
        assertEquals(3, scripts.size)
        val excluded = scripts.first { "https://excluded.com" in it.origins }
        assertTrue("https://www.excluded.com" in excluded.origins)
        assertTrue("http://also-excluded.org" in excluded.origins)
        assertTrue(excluded.script.contains("\"enabled\":false"))
        val oled = scripts.first { "https://oled.net" in it.origins }
        assertTrue(oled.script.contains("#000000"))
        assertFalse(scripts.any { origins -> origins.origins.any { "same.com" in it || "bad" in it } })
        // Ningún script menciona sitios: solo la política.
        assertFalse(scripts.any { "excluded.com" in it.script || "oled.net" in it.script })
    }

    @Test
    fun `boot script hands the policy to the engine hook or leaves it for it, top frame only`() {
        val script = DarkEngine.bootScript(DarkEngine.bootConfig(policy(DarkEngine.MODE_DARK)))
        assertTrue(script.contains("window!==window.top"))
        assertTrue(script.contains("__nexoDarkBootHook"))
        assertTrue(script.contains("__nexoDarkBoot"))
        assertTrue(script.contains("\"strategy\":\"hybrid\""))
        assertEquals(emptySet<String>(), DarkEngine.originsFor("localhost"))
        assertEquals(4, DarkEngine.originsFor("www.Example.com").size)
    }
}
