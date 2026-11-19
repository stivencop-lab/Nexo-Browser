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

        // Storage autorizado en origen permitido con token de capacidad efímero válido
        val sessionKey = 1001
        val docGen = 1L
        val grant = NxrRuntime.issueGrant(
            sessionKey = sessionKey,
            extId = installed.id,
            url = "https://example.com/page",
            docGen = docGen,
            isPrivate = false,
            hasStoragePermission = true
        )
        val setReply = NxrRuntime.onPageMessage(
            sessionKey = sessionKey,
            docGen = docGen,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject()
                .put("extId", installed.id)
                .put("token", grant.token)
                .put("op", "set")
                .put("values", JSONObject().put("theme", "slate"))
        )
        assertNull(setReply.error)

        val getReply = NxrRuntime.onPageMessage(
            sessionKey = sessionKey,
            docGen = docGen,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject()
                .put("extId", installed.id)
                .put("token", grant.token)
                .put("op", "get")
                .put("keys", "theme")
        )
        assertNull(getReply.error)
        assertEquals("slate", (getReply.result as JSONObject).getString("theme"))

        // Token falsificado -> rechazado
        val spoofed = NxrRuntime.onPageMessage(
            sessionKey = sessionKey,
            docGen = docGen,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject().put("extId", installed.id).put("token", "wrong-token").put("op", "get")
        )
        assertNotNull(spoofed.error)

        // Generación de documento obsoleta (navegación posterior) -> rechazado
        val staleGen = NxrRuntime.onPageMessage(
            sessionKey = sessionKey,
            docGen = docGen + 1L,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject().put("extId", installed.id).put("token", grant.token).put("op", "get")
        )
        assertNotNull(staleGen.error)

        // Origen no autorizado -> rechazado
        val wrongOrigin = NxrRuntime.onPageMessage(
            sessionKey = sessionKey,
            docGen = docGen,
            isPrivate = false,
            origin = "https://bank.evil.test",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject().put("extId", installed.id).put("token", grant.token).put("op", "get")
        )
        assertNotNull(wrongOrigin.error)

        // Pestaña privada denegada por defecto
        val privateBlocked = NxrRuntime.onPageMessage(
            sessionKey = sessionKey,
            docGen = docGen,
            isPrivate = true,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject().put("extId", installed.id).put("token", grant.token).put("op", "get")
        )
        assertNotNull(privateBlocked.error)

        // Desinstalación limpia borra directorio, permisos y storage
        assertTrue(NxrPackageInstaller.uninstall(root, installed.id))
        assertTrue(NxrPackageInstaller.listInstalled(root).isEmpty())
        assertFalse(NxrPackageInstaller.extensionDir(root, installed.id).exists())
    }

    @Test
    fun storageIsStrictlyIsolatedBetweenExtensionsAndBetweenOrigins() {
        val root = temp.newFolder("nxr_iso")
        val storage = NxrStorage(root)
        storage.set("ext_a", JSONObject().put("secret", "alpha"), originHost = "bank.example")
        storage.set("ext_a", JSONObject().put("secret", "evil_val"), originHost = "evil.example")
        storage.set("ext_b", JSONObject().put("secret", "beta"), originHost = "bank.example")

        // Misma extensión en orígenes distintos no comparte storage desde content scripts.
        assertEquals("alpha", storage.get("ext_a", "secret", originHost = "bank.example").getString("secret"))
        assertEquals("evil_val", storage.get("ext_a", "secret", originHost = "evil.example").getString("secret"))
        assertFalse(storage.get("ext_a", "secret", originHost = "other.example").has("secret"))
        assertEquals("beta", storage.get("ext_b", "secret", originHost = "bank.example").getString("secret"))

        storage.clear("ext_a", originHost = "evil.example")
        assertEquals("alpha", storage.get("ext_a", "secret", originHost = "bank.example").getString("secret"))
        assertFalse(storage.get("ext_a", "secret", originHost = "evil.example").has("secret"))

        storage.clear("ext_a")
        assertFalse(storage.get("ext_a", "secret", originHost = "bank.example").has("secret"))
        assertEquals("beta", storage.get("ext_b", "secret", originHost = "bank.example").getString("secret"))
    }

    @Test
    fun rejectsStorageWithoutDeclaredPermissionAndEnforcesRateLimitAndRevocation() {
        val root = temp.newFolder("nxr_sec")
        NxrRuntime.initializeForTest(root, "/* shim */")
        val noStoragePkg = buildZip(
            "no_storage.zip",
            mapOf(
                "manifest.json" to """
                    {
                      "manifest_version": 3,
                      "name": "No Storage Ext",
                      "version": "1.0.0",
                      "permissions": [],
                      "host_permissions": ["https://example.com/*"],
                      "content_scripts": [{ "matches": ["https://example.com/*"], "js": ["c.js"] }],
                      "browser_specific_settings": { "gecko": { "id": "nostorage@ext.test" } }
                    }
                """.trimIndent().toByteArray(),
                "c.js" to "console.log(1);".toByteArray()
            )
        )
        val installed = NxrPackageInstaller.install(root, noStoragePkg, listOf("https://example.com/*"))
        val sessionKey = 2002
        val grant = NxrRuntime.issueGrant(
            sessionKey = sessionKey,
            extId = installed.id,
            url = "https://example.com/app",
            docGen = 1L,
            isPrivate = false,
            hasStoragePermission = false
        )

        // Sin permiso "storage" en manifest -> rechazado
        val deniedStorage = NxrRuntime.onPageMessage(
            sessionKey = sessionKey,
            docGen = 1L,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_storage",
            payload = JSONObject().put("extId", installed.id).put("token", grant.token).put("op", "get")
        )
        assertNotNull(deniedStorage.error)
        assertTrue(deniedStorage.error!!.contains("storage", ignoreCase = true))

        // Rate limit: >30 llamadas en 10 s deben rechazarse
        var rateLimited = false
        for (i in 1..35) {
            val r = NxrRuntime.onPageMessage(
                sessionKey = sessionKey,
                docGen = 1L,
                isPrivate = false,
                origin = "https://example.com",
                isMainFrame = true,
                kind = "nxr_message",
                payload = JSONObject().put("extId", installed.id).put("token", grant.token)
                    .put("message", JSONObject().put("type", "PING_$i"))
            )
            if (r.error?.contains("frecuencia", ignoreCase = true) == true) {
                rateLimited = true
                break
            }
        }
        assertTrue("Debe activarse el límite de frecuencia NXR", rateLimited)

        // Revocación inmediata de grants
        val freshGrant = NxrRuntime.issueGrant(3003, installed.id, "https://example.com/app", 1L, false, false)
        NxrRuntime.revokeGrantsForExtension(installed.id)
        val afterRevoke = NxrRuntime.onPageMessage(
            sessionKey = 3003,
            docGen = 1L,
            isPrivate = false,
            origin = "https://example.com",
            isMainFrame = true,
            kind = "nxr_message",
            payload = JSONObject().put("extId", installed.id).put("token", freshGrant.token)
                .put("message", JSONObject().put("type", "AFTER_REVOKE"))
        )
        assertNotNull("Token revocado debe fallar de inmediato", afterRevoke.error)
    }

    @Test
    fun bundleNeverExposesWindowNxrDispatchAndDeduplicatesStagesPerDocument() {
        val bundle = NxrRuntime.buildJsBundleScript(
            shim = "/* shim */",
            extId = "demo@ext.test",
            token = "secret-cap-token",
            actionNonce = "nonce-1234",
            manifestSummary = JSONObject().put("name", "Demo"),
            localeMap = JSONObject(),
            scripts = listOf("content.js" to "void 0;")
        )
        assertFalse("No debe exponer window.__nxrDispatch", bundle.contains("__nxrDispatch"))
        assertTrue("Debe registrar el canal de acción CustomEvent", bundle.contains("nxr-act:nonce-1234"))

        // Deduplicación por generación de documento
        assertTrue(NxrRuntime.markStageInjected(999, 1L, "demo:DOCUMENT_IDLE:0"))
        assertFalse(NxrRuntime.markStageInjected(999, 1L, "demo:DOCUMENT_IDLE:0"))
        assertTrue(NxrRuntime.markStageInjected(999, 2L, "demo:DOCUMENT_IDLE:0"))
        NxrRuntime.clearSessionGrants(999)
    }

    @Test
    fun rejectsZipSlipAndDuplicateEntriesAndPreservesStorageOnUpgrade() {
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

        // Entradas ZIP duplicadas (shadowing attack) deben rechazarse
        val dupFile = temp.newFile("dup.zip")
        ZipOutputStream(dupFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(sampleManifest().toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("./manifest.json"))
            zos.write(sampleManifest(name = "Evil Shadow").toByteArray())
            zos.closeEntry()
        }
        val dupErr = runCatching { NxrPackageInstaller.install(root, dupFile) }.exceptionOrNull()
        assertNotNull("Duplicate ZIP paths must be rejected", dupErr)
        assertTrue(dupErr!!.message!!.contains("duplicad", ignoreCase = true))

        // Actualización transaccional conserva almacenamiento particionado por origen
        val v1 = buildZip(
            "v1.zip",
            mapOf(
                "manifest.json" to sampleManifest(name = "App V1").toByteArray(),
                "content.js" to "void 0;".toByteArray(),
                "style.css" to "body{}".toByteArray()
            )
        )
        val installedV1 = NxrPackageInstaller.install(root, v1)
        val store = NxrStorage(root)
        store.set(installedV1.id, JSONObject().put("k", "keep-me"), originHost = "example.com")

        // Actualización fallida (ZIP corrupto) deja V1 y su storage intactos
        val badUpgrade = temp.newFile("bad.zip").apply { writeText("not-a-zip") }
        assertNotNull(runCatching { NxrPackageInstaller.install(root, badUpgrade) }.exceptionOrNull())
        assertEquals("App V1", NxrPackageInstaller.listInstalled(root).single().name)
        assertEquals("keep-me", store.get(installedV1.id, "k", originHost = "example.com").getString("k"))

        // Actualización válida a V2 conserva el storage por origen
        val v2 = buildZip(
            "v2.zip",
            mapOf(
                "manifest.json" to sampleManifest(name = "App V2").toByteArray(),
                "content.js" to "void 1;".toByteArray(),
                "style.css" to "body{}".toByteArray()
            )
        )
        NxrPackageInstaller.install(root, v2)
        assertEquals("App V2", NxrPackageInstaller.listInstalled(root).single().name)
        assertEquals("keep-me", store.get(installedV1.id, "k", originHost = "example.com").getString("k"))
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
