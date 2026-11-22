package com.nexo.browser

import com.nexo.browser.extensions.MatchPattern
import com.nexo.browser.extensions.nxr.NxrPackageInstaller
import com.nexo.browser.extensions.nxr.NxrPermissions
import com.nexo.browser.extensions.nxr.NxrRuntime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pruebas de regresión para los hallazgos de seguridad y rendimiento remediados en la auditoría.
 */
class SecurityHardeningTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun matchPatternIsLinearTimeAndStopsBackslashAuthorityConfusion() {
        val pattern = MatchPattern.parse("https://bank.example/*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*a*b")!!
        val start = System.currentTimeMillis()
        assertFalse(pattern.matches("https://bank.example/" + "a".repeat(200) + "c"))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("RE2J en MatchPattern tardó demasiado: ${elapsed}ms", elapsed < 250L)

        // Backslash confusion: la autoridad real es evil.example, jamás bank.example.
        val bankOnly = MatchPattern.parse("https://bank.example/*")!!
        assertFalse(bankOnly.matches("https://evil.example\\@bank.example/login"))
        assertEquals("evil.example", NxrPermissions.extractHost("https://evil.example\\@bank.example/login"))
    }

    @Test
    fun nxrPermissionsExtractHostPreservesIpv6Literals() {
        assertEquals("2001:db8::1", NxrPermissions.extractHost("https://[2001:db8::1]:8443/app"))
        assertEquals("::1", NxrPermissions.extractHost("http://[::1]/local"))
        assertEquals("example.com", NxrPermissions.extractHost("https://www.Example.COM:443/path"))
    }

    @Test
    fun crashReportRedactorStripsExtendedHeadersWebSocketsAndUserDePaths() {
        val raw = """
            Authorization: DeepL-Auth-Key super-secret-deepl-key
            Authorization: Token ghp_secretToken123456
            x-api-key: sk-ant-secret-key-999
            x-goog-api-key: AIzaSySecretKey123456
            WebSocket failed on wss://api.deepgram.com/v1/listen?token=abc123
            File /data/user_de/0/com.nexo.browser/files/secret.bin not found
        """.trimIndent()
        val redacted = CrashReportRedactor.redact(raw)
        assertFalse(redacted.contains("super-secret-deepl-key"))
        assertFalse(redacted.contains("ghp_secretToken123456"))
        assertFalse(redacted.contains("sk-ant-secret-key-999"))
        assertFalse(redacted.contains("AIzaSySecretKey123456"))
        assertFalse(redacted.contains("wss://api.deepgram.com"))
        assertFalse(redacted.contains("/data/user_de/0/com.nexo.browser"))
    }

    @Test
    fun memoryPressureClearsOnAndroid14BackgroundLevel40() {
        assertEquals(MemoryPressure.CacheAction.CLEAR, MemoryPressure.cacheAction(40))
    }

    @Test
    fun nxrPanelRejectsMissingStoragePermissionOversizedPayloadsAndRevokesOnDisable() {
        val root = temp.newFolder("nxr_sec")
        NxrRuntime.initializeForTest(root, "/* shim */")
        val zipFile = temp.newFile("nostorage.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            val manifest = """
                {
                  "manifest_version": 3,
                  "name": "No Storage Ext",
                  "version": "1.0.0",
                  "permissions": [],
                  "host_permissions": ["https://example.com/*"],
                  "browser_specific_settings": { "gecko": { "id": "nostorage@ext.test" } }
                }
            """.trimIndent()
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()
        }
        val installed = NxrPackageInstaller.install(root, zipFile, listOf("https://example.com/*"), enableAfterInstall = true)
        val token = NxrRuntime.issuePanelToken(installed.id)

        // Sin permiso "storage" debe rechazarse aunque tenga token de panel válido.
        val noPerm = NxrRuntime.onPanelMessage(
            extId = installed.id,
            panelToken = token,
            kind = "nxr_storage",
            payload = JSONObject().put("token", token).put("op", "get")
        )
        assertNotNull(noPerm.error)

        // Payload > 32 KB debe rechazarse.
        val huge = NxrRuntime.onPanelMessage(
            extId = installed.id,
            panelToken = token,
            kind = "nxr_error",
            payload = JSONObject().put("token", token).put("message", "x".repeat(40_000))
        )
        assertNotNull(huge.error)

        // Revocar grants de la extensión también revoca panelTokens.
        NxrRuntime.revokeGrantsForExtension(installed.id)
        val afterRevoke = NxrRuntime.onPanelMessage(
            extId = installed.id,
            panelToken = token,
            kind = "nxr_error",
            payload = JSONObject().put("token", token).put("message", "after")
        )
        assertNotNull(afterRevoke.error)
    }
}
