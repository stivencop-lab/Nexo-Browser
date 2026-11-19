package com.nexo.browser

import org.junit.Assert.*
import org.junit.Test

class AppUpdaterTest {
    @Test fun versionNormalizationAndComparisonHandlePrefixesAndSegments() {
        assertEquals("0.32.0", AppUpdater.normalizeVersion("v0.32.0"))
        assertEquals("0.32.1", AppUpdater.normalizeVersion("V0.32.1-rc1+build4"))
        assertEquals("", AppUpdater.normalizeVersion("invalid"))

        assertTrue(AppUpdater.isNewer("0.32.1", "0.32.0"))
        assertTrue(AppUpdater.isNewer("v0.33.0", "0.32.9"))
        assertTrue(AppUpdater.isNewer("1.0.0", "0.99.9"))
        assertTrue(AppUpdater.isNewer("0.32.0.1", "0.32.0"))

        assertFalse(AppUpdater.isNewer("0.32.0", "0.32.0"))
        assertFalse(AppUpdater.isNewer("v0.30.2", "0.32.0"))
        assertFalse(AppUpdater.isNewer("", "0.32.0"))
    }

    @Test fun releaseUrlAllowlistRejectsNonHttpsCredentialsAndForeignHosts() {
        assertTrue(AppUpdater.isAllowedReleaseUrl("https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.32.0/Nexo-v0.32.0-arm64-v8a.apk"))
        assertTrue(AppUpdater.isAllowedReleaseUrl("https://objects.githubusercontent.com/github-production-release-asset/123"))
        assertTrue(AppUpdater.isAllowedReleaseUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123"))
        assertTrue(AppUpdater.isAllowedReleaseUrl("https://api.github.com/repos/stivencop414-bot/NavegadorGecko/releases/latest"))

        assertFalse(AppUpdater.isAllowedReleaseUrl("http://github.com/stivencop414-bot/NavegadorGecko/releases"))
        assertFalse(AppUpdater.isAllowedReleaseUrl("https://user@github.com/stivencop414-bot/NavegadorGecko"))
        assertFalse(AppUpdater.isAllowedReleaseUrl("https://evil-github.com/Nexo.apk"))
        assertFalse(AppUpdater.isAllowedReleaseUrl("javascript:alert(1)"))
    }

    @Test fun sha256SumsParserExtractsHashesByFileName() {
        val hash64 = "a".repeat(64)
        val hash32 = "b".repeat(64)
        val raw = """
            $hash64  Nexo-v0.33.0-arm64-v8a.apk
            $hash32 *Nexo-v0.33.0-armeabi-v7a.apk
            invalid line
            $hash64  ../escape.apk
        """.trimIndent()
        val parsed = AppUpdater.parseSha256Sums(raw)
        assertEquals(2, parsed.size)
        assertEquals(hash64, parsed["Nexo-v0.33.0-arm64-v8a.apk"])
        assertEquals(hash32, parsed["Nexo-v0.33.0-armeabi-v7a.apk"])
    }

    @Test fun parseReleaseSelectsMatchingAbiAndPackageVariant() {
        val sampleJson = """
            {
              "tag_name": "v0.33.0",
              "name": "Nexo v0.33.0",
              "body": "### Novedades\n- Ajustes compactos\n- Actualizador integrado",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/stivencop414-bot/NavegadorGecko/releases/tag/v0.33.0",
              "assets": [
                {
                  "name": "Nexo-anterior-v0.33.0-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.33.0/Nexo-anterior-v0.33.0-arm64-v8a.apk",
                  "size": 84000000
                },
                {
                  "name": "Nexo-v0.33.0-armeabi-v7a.apk",
                  "browser_download_url": "https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.33.0/Nexo-v0.33.0-armeabi-v7a.apk",
                  "size": 78000000
                },
                {
                  "name": "Nexo-v0.33.0-arm64-v8a.apk",
                  "browser_download_url": "https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.33.0/Nexo-v0.33.0-arm64-v8a.apk",
                  "size": 85000000
                },
                {
                  "name": "SHA256SUMS.txt",
                  "browser_download_url": "https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.33.0/SHA256SUMS.txt",
                  "size": 512
                }
              ]
            }
        """.trimIndent()

        val release = AppUpdater.parseRelease(
            sampleJson,
            currentVersion = "0.32.0",
            abis = listOf("arm64-v8a", "armeabi-v7a"),
            packageName = "com.nexo.browser"
        )
        assertNotNull(release)
        assertEquals("0.33.0", release!!.version)
        assertEquals("Nexo-v0.33.0-arm64-v8a.apk", release.apkAsset?.name)
        assertEquals("arm64-v8a", release.apkAsset?.abi)
        assertNotNull(release.sha256SumsUrl)

        val transitionRelease = AppUpdater.parseRelease(
            sampleJson,
            currentVersion = "0.32.0",
            abis = listOf("arm64-v8a"),
            packageName = "com.ejemplo.navegador"
        )
        assertEquals("Nexo-anterior-v0.33.0-arm64-v8a.apk", transitionRelease?.apkAsset?.name)

        // Same or newer installed version returns null
        assertNull(AppUpdater.parseRelease(sampleJson, "0.33.0", listOf("arm64-v8a")))
        assertNull(AppUpdater.parseRelease(sampleJson, "0.34.0", listOf("arm64-v8a")))
    }
}
