package com.nexo.browser.extensions.nxr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DnrBridgeTest {
    @get:Rule val folder = TemporaryFolder()

    private fun compile(rules: String, policy: NxrPermissions.Policy = NxrPermissions.Policy()): DnrBridge.Rules? {
        val root = folder.newFolder()
        val ext = NxrPackageInstaller.extensionDir(root, "test-extension")
        val files = NxrPackageInstaller.filesDir(root, "test-extension").apply { mkdirs() }
        files.resolve("manifest.json").writeText(
            """
            {"manifest_version":3,"name":"Test","version":"1","permissions":["declarativeNetRequest"],
             "declarative_net_request":{"rule_resources":[{"id":"default","enabled":true,"path":"rules.json"}]}}
            """.trimIndent()
        )
        files.resolve("rules.json").writeText(rules)
        NxrPermissions.writePolicy(ext, policy)
        return DnrBridge.compileExtension("test-extension", root)
    }

    private fun blocks(rules: DnrBridge.Rules, host: String, origin: String, site: String, kind: String, isPrivate: Boolean): Boolean {
        val request = requireNotNull(DnrBridge.Request.from("https://$host/", "https://$origin/", "https://$site/", kind, "GET"))
        return rules.blocks(request, isPrivate)
    }

    @Test fun blocksSubdomainsAndHonorsScopeAndAllowPriority() {
        val rules = requireNotNull(compile(
            """
            [{"id":1,"action":{"type":"block"},"condition":{"urlFilter":"||ads.example^","resourceTypes":["script"]}},
             {"id":2,"action":{"type":"allow"},"condition":{"requestDomains":["cdn.ads.example"],"initiatorDomains":["site.example"]}}]
            """.trimIndent(),
            NxrPermissions.Policy(deniedHosts = setOf("excluded.example"))
        ))
        assertTrue(blocks(rules, "video.ads.example", "site.example", "site.example", "script", false))
        assertTrue(blocks(rules, "cdn.ads.example", "other.example", "site.example", "script", false))
        assertFalse(blocks(rules, "cdn.ads.example", "site.example", "site.example", "script", false))
        assertFalse(blocks(rules, "ads.example.net", "site.example", "site.example", "script", false))
        assertFalse(blocks(rules, "ads.example", "site.example", "site.example", "image", false))
        assertFalse(blocks(rules, "ads.example", "site.example", "excluded.example", "script", false))
        assertFalse(blocks(rules, "ads.example", "site.example", "site.example", "script", true))
    }

    @Test fun skipsASetWhoseExceptionsCannotBeEnforced() {
        assertNull(compile(
            """
            [{"id":1,"action":{"type":"block"},"condition":{"urlFilter":"||ads.example^"}},
             {"id":2,"action":{"type":"allow"},"condition":{"regexFilter":"^https://ads[.]example/allowed"}}]
            """.trimIndent()
        ))
    }
}
