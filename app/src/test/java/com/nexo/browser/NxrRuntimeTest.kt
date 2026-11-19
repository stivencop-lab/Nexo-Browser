package com.nexo.browser

import com.nexo.browser.extensions.ExtensionCompatibility
import com.nexo.browser.extensions.nxr.NxrPackageInstaller
import com.nexo.browser.extensions.nxr.NxrPermissions
import com.nexo.browser.extensions.nxr.NxrRuntime
import com.nexo.browser.extensions.nxr.NxrStorage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NxrRuntimeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun buildZip(name: String, files: Map<String, ByteArray>): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zos ->
            for ((entryName, bytes) in files) {
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }

    private fun sampleManifest(
        name: String = "Nexo Sample Ext",
        geckoId: String = "sample@ext.test",
        extra: String = ""
    ): String = """
        {
          "manifest_version": 3,
          "name": "$name",
          "version": "1.0.0",
          "description": "Extensión de prueba NXR",
          "permissions": ["storage"],
          "host_permissions": ["https://example.com/*"],
          "content_scripts": [
            {
              "matches": ["https://example.com/*"],
              "js": ["content.js"],
              "css": ["style.css"],
              "run_at": "document_idle"
            }
          ],
          "browser_specific_settings": { "gecko": { "id": "$geckoId" } }
          $extra
        }
    """.trimIndent()

    @Test
    fun installInspectExecuteStorageAndUninstallCleanly() {
        val root = temp.newFolder("nxr_root")
        NxrRuntime.initializeForTest(root, "/* shim */")
        val pkg = buildZip(
            "sample.zip",
            mapOf(
                "manifest.json" to sampleManifest().toByteArray(),
                "content.js" to "console.log('ok');".toByteArray(),
                "style.css" to "body { color: inherit; }".toByteArray()
            )
        )

        val inspection = NxrPackageInstaller.inspect(pkg)
        assertEquals("sample@ext.test", inspection.id)
        assertEquals(1, inspection.capabilities.runnableContentScripts)
        assertTrue(inspection.missingFiles.isEmpty())

        val installed = NxrPackageInstaller.install(root, pkg, inspection.requestedHosts)
        assertEquals("sample@ext.test", installed.id)
        assertEquals(1, NxrPackageInstaller.listInstalled(root).size)

        // Storage autorizado en origen permitido con token válido
        val token = "session-token-123456"
        val setReply = NxrRuntime.onPageMessage(
            sessionToken = token,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject()
                .put("extId", installed.id)
                .put("token", token)
                .put("op", "set")
                .put("values", JSONObject().put("theme", "slate"))
        )
        assertNull(setReply.error)

        val getReply = NxrRuntime.onPageMessage(
            sessionToken = token,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject()
                .put("extId", installed.id)
                .put("token", token)
                .put("op", "get")
                .put("keys", "theme")
        )
        assertNull(getReply.error)
        assertEquals("slate", (getReply.result as JSONObject).getString("theme"))

        // Token falsificado -> rechazado
        val spoofed = NxrRuntime.onPageMessage(
            sessionToken = token,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject().put("extId", installed.id).put("token", "wrong-token").put("op", "get")
        )
        assertNotNull(spoofed.error)

        // Origen no autorizado -> rechazado
        val wrongOrigin = NxrRuntime.onPageMessage(
            sessionToken = token,
            isPrivate = false,
            origin = "https://bank.evil.test",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject().put("extId", installed.id).put("token", token).put("op", "get")
        )
        assertNotNull(wrongOrigin.error)

        // Pestaña privada denegada por defecto
        val privateBlocked = NxrRuntime.onPageMessage(
            sessionToken = token,
            isPrivate = true,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject().put("extId", installed.id).put("token", token).put("op", "get")
        )
        assertNotNull(privateBlocked.error)

        // Desinstalación limpia borra directorio, permisos y storage
        assertTrue(NxrPackageInstaller.uninstall(root, installed.id))
        assertTrue(NxrPackageInstaller.listInstalled(root).isEmpty())
        assertFalse(NxrPackageInstaller.extensionDir(root, installed.id).exists())
    }

    @Test
    fun storageIsStrictlyIsolatedBetweenExtensions() {
        val root = temp.newFolder("nxr_iso")
        val storage = NxrStorage(root)
        storage.set("ext_a", JSONObject().put("secret", "alpha"))
        storage.set("ext_b", JSONObject().put("secret", "beta"))

        assertEquals("alpha", storage.get("ext_a", "secret").getString("secret"))
        assertEquals("beta", storage.get("ext_b", "secret").getString("secret"))
        storage.clear("ext_a")
        assertFalse(storage.get("ext_a", "secret").has("secret"))
        assertEquals("beta", storage.get("ext_b", "secret").getString("secret"))
    }

    @Test
    fun rejectsZipSlipPathTraversalPackages() {
        val root = temp.newFolder("nxr_slip")
        val evilZip = buildZip(
            "evil.zip",
            mapOf(
                "manifest.json" to sampleManifest().toByteArray(),
                "../../escape.txt" to "owned".toByteArray()
            )
        )
        val thrown = runCatching { NxrPackageInstaller.install(root, evilZip) }.exceptionOrNull()
        assertNotNull("Zip Slip must be rejected", thrown)
        assertTrue(NxrPackageInstaller.listInstalled(root).isEmpty())
    }

    @Test
    fun flagsUnsupportedMv3ServiceWorkerAndPrivilegedApisHonestly() {
        val pkg = buildZip(
            "mv3_sw.zip",
            mapOf(
                "manifest.json" to """
                    {
                      "manifest_version": 3,
                      "name": "SW Ext",
                      "version": "1.0",
                      "permissions": ["storage", "debugger", "proxy"],
                      "background": { "service_worker": "sw.js" },
                      "content_scripts": [{ "matches": ["https://example.com/*"], "js": ["c.js"] }]
                    }
                """.trimIndent().toByteArray(),
                "sw.js" to "self.addEventListener('install', () => {});".toByteArray(),
                "c.js" to "console.log(1);".toByteArray()
            )
        )
        val inspection = NxrPackageInstaller.inspect(pkg)
        assertEquals(ExtensionCompatibility.Level.PARTIAL, inspection.capabilities.level)
        assertTrue(inspection.capabilities.hasServiceWorkerBackground)
        assertTrue("debugger" in inspection.capabilities.unsupportedApis)
        assertTrue("proxy" in inspection.capabilities.unsupportedApis)
        assertTrue(inspection.capabilities.unsupportedApis.any { it.contains("service_worker") })
    }

    @Test
    fun permissionPolicyBlocksInternalSchemesAndDeniedHosts() {
        val policy = NxrPermissions.Policy(
            enabled = true,
            allowInPrivate = false,
            grantedHostPatterns = listOf("<all_urls>"),
            deniedHosts = setOf("blocked.example")
        )
        assertTrue(NxrPermissions.isAuthorizedForUrl(policy, "https://allowed.example/page", isPrivate = false))
        assertFalse(NxrPermissions.isAuthorizedForUrl(policy, "https://blocked.example/page", isPrivate = false))
        assertFalse(NxrPermissions.isAuthorizedForUrl(policy, "about:blank", isPrivate = false))
        assertFalse(NxrPermissions.isAuthorizedForUrl(policy, "file:///etc/passwd", isPrivate = false))
        assertFalse(NxrPermissions.isAuthorizedForUrl(policy, "https://allowed.example/page", isPrivate = true))
    }
}
