package com.nexo.browser.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionManifestTest {
    private val mv3 = """
        {
          "manifest_version": 3,
          "name": "Lector",
          "version": "1.4.2",
          "default_locale": "es",
          "background": { "service_worker": "sw.js", "type": "module" },
          "permissions": ["storage", "declarativeNetRequest", "debugger"],
          "host_permissions": ["*://*.example.com/*"],
          "optional_host_permissions": ["<all_urls>"],
          "action": { "default_popup": "popup.html", "default_icon": { "16": "i16.png", "48": "i48.png" } },
          "options_ui": { "page": "opts.html", "open_in_tab": true },
          "content_scripts": [
            { "matches": ["https://*/*"], "js": ["./cs/main.js"], "run_at": "document_start", "all_frames": true, "world": "MAIN" },
            { "matches": [], "js": ["ignored.js"] },
            { "matches": ["*://news.example.com/*"], "css": ["a.css"], "exclude_matches": ["*://news.example.com/admin/*"] }
          ],
          "web_accessible_resources": [ { "resources": ["img/*.png"], "matches": ["*://*.example.com/*"] } ],
          "declarative_net_request": { "rule_resources": [ { "id": "base", "enabled": true, "path": "rules/base.json" }, { "id": "x" } ] },
          "content_security_policy": { "extension_pages": "script-src 'self'" }
        }
    """.trimIndent()

    private val mv2 = """
        {
          "manifest_version": 2,
          "name": "Clásica",
          "version": "2.0",
          "browser_specific_settings": { "gecko": { "id": "clasica@ejemplo.org" } },
          "permissions": ["webRequest", "webRequestBlocking", "<all_urls>", "https://api.ejemplo.org/*", "tabs"],
          "optional_permissions": ["cookies", "*://*.optional.net/*"],
          "background": { "scripts": ["a.js", "b.js"] },
          "browser_action": { "default_popup": "panel.html", "default_icon": "icon.png", "default_title": "Abrir" },
          "options_page": "options.html",
          "web_accessible_resources": ["surrogate.js"],
          "content_security_policy": "script-src 'self'; object-src 'self'"
        }
    """.trimIndent()

    @Test fun parsesManifestV3() {
        val m = ExtensionManifest.parse(mv3)
        assertEquals(3, m.manifestVersion)
        assertEquals("es", m.defaultLocale)
        assertEquals("sw.js", m.background?.serviceWorker)
        assertTrue(m.background!!.isModule)
        assertFalse(m.background!!.persistent)
        assertEquals(setOf("storage", "declarativeNetRequest", "debugger"), m.permissions)
        assertEquals(listOf("*://*.example.com/*"), m.hostPermissions)
        assertEquals(listOf("<all_urls>"), m.optionalHostPermissions)
        assertEquals("popup.html", m.action?.popup)
        assertEquals(mapOf(16 to "i16.png", 48 to "i48.png"), m.action?.icons)
        assertEquals("opts.html", m.optionsPage)
        assertTrue(m.optionsOpenInTab)
        assertEquals(2, m.contentScripts.size)
        val first = m.contentScripts[0]
        assertEquals(ExtensionManifest.RunAt.DOCUMENT_START, first.runAt)
        assertEquals(ExtensionManifest.World.MAIN, first.world)
        assertTrue(first.allFrames)
        assertEquals(ExtensionManifest.RunAt.DOCUMENT_IDLE, m.contentScripts[1].runAt)
        assertTrue(m.contentScripts[1].matcher.matches("https://news.example.com/today"))
        assertFalse(m.contentScripts[1].matcher.matches("https://news.example.com/admin/x"))
        assertEquals(listOf(ExtensionManifest.RulesetRef("base", true, "rules/base.json")), m.dnrRulesets)
        assertEquals("script-src 'self'", m.contentSecurityPolicy)
        assertEquals(listOf("img/*.png"), m.webAccessibleResources.single().resources)
        assertEquals(
            setOf("sw.js", "cs/main.js", "a.css", "popup.html", "opts.html", "rules/base.json"),
            m.referencedFiles()
        )
    }

    @Test fun parsesManifestV2AndSplitsHostPermissions() {
        val m = ExtensionManifest.parse(mv2)
        assertEquals("clasica@ejemplo.org", m.geckoId)
        assertEquals(setOf("webRequest", "webRequestBlocking", "tabs"), m.permissions)
        assertEquals(listOf("<all_urls>", "https://api.ejemplo.org/*"), m.hostPermissions)
        assertEquals(setOf("cookies"), m.optionalPermissions)
        assertEquals(listOf("*://*.optional.net/*"), m.optionalHostPermissions)
        assertEquals(listOf("a.js", "b.js"), m.background?.scripts)
        assertTrue(m.background!!.persistent)
        assertEquals("panel.html", m.action?.popup)
        assertEquals("Abrir", m.action?.title)
        assertEquals(mapOf(0 to "icon.png"), m.action?.icons)
        assertEquals("options.html", m.optionsPage)
        assertTrue(m.optionsOpenInTab)
        assertEquals(listOf("<all_urls>"), m.webAccessibleResources.single().matches)
        assertEquals("script-src 'self'; object-src 'self'", m.contentSecurityPolicy)
    }

    @Test fun rejectsInvalidManifests() {
        listOf(
            """{"manifest_version":1,"name":"a","version":"1"}""",
            """{"manifest_version":3,"name":"","version":"1"}""",
            """{"manifest_version":3,"name":"a","version":"1.x"}""",
            """{"manifest_version":3,"name":"a","version":"1.2.3.4.5"}""",
            "no es json"
        ).forEach { raw ->
            val failed = runCatching { ExtensionManifest.parse(raw) }.isFailure
            assertTrue(raw, failed)
        }
        // BOM inicial habitual en paquetes de Windows.
        assertEquals("a", ExtensionManifest.parse("\uFEFF{\"manifest_version\":2,\"name\":\"a\",\"version\":\"1\"}").name)
    }

    @Test fun pathsCannotLeaveThePackage() {
        assertEquals("js/a.js", ExtensionManifest.normalizePath("./js/a.js"))
        assertEquals("js/a.js", ExtensionManifest.normalizePath("/js//a.js?v=2#x"))
        assertEquals("js/a.js", ExtensionManifest.normalizePath("js\\a.js"))
        assertNull(ExtensionManifest.normalizePath("../secret"))
        assertNull(ExtensionManifest.normalizePath("js/../../x"))
        assertNull(ExtensionManifest.normalizePath("https://evil.example/x.js"))
        assertNull(ExtensionManifest.normalizePath("  "))
    }

    @Test fun bundledPhantomShieldManifestParses() {
        val file = listOf(
            File("src/main/assets/extensions/phantomshield/manifest.json"),
            File("app/src/main/assets/extensions/phantomshield/manifest.json")
        ).first { it.exists() }
        val m = ExtensionManifest.parse(file.readText())
        // Integrado en Nexo: sin identidad de Firefox.
        assertEquals(null, m.geckoId)
        assertEquals(2, m.manifestVersion)
        assertTrue("webRequestBlocking" in m.permissions)
        assertEquals(listOf("<all_urls>"), m.hostPermissions)
        assertTrue(m.contentScripts.any { it.world == ExtensionManifest.World.MAIN && it.js == listOf("content/page-hooks.js") })
        assertEquals("popup/popup.html", m.action?.popup)
        assertEquals(ExtensionCompatibility.Level.PARTIAL, ExtensionCompatibility.evaluate(m).level)
    }

    @Test fun compatibilityReflectsWebViewLimits() {
        val full = ExtensionManifest.parse(
            """{"manifest_version":3,"name":"a","version":"1","permissions":["storage","alarms"],
               "content_scripts":[{"matches":["<all_urls>"],"js":["a.js"],"world":"MAIN"}]}"""
        )
        assertEquals(ExtensionCompatibility.Level.FULL, ExtensionCompatibility.evaluate(full).level)

        val partial = ExtensionCompatibility.evaluate(ExtensionManifest.parse(mv3))
        assertEquals(ExtensionCompatibility.Level.PARTIAL, partial.level)
        assertEquals(listOf("debugger"), partial.unsupported)
        assertTrue("content_scripts" in partial.limited)

        val none = ExtensionManifest.parse(
            """{"manifest_version":3,"name":"a","version":"1","permissions":["debugger","nativeMessaging"],
               "background":{"service_worker":"sw.js"}}"""
        )
        assertEquals(ExtensionCompatibility.Level.UNSUPPORTED, ExtensionCompatibility.evaluate(none).level)
    }
}
