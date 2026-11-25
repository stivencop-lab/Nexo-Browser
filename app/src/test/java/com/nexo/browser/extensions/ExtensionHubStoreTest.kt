package com.nexo.browser.extensions

import com.nexo.browser.extensions.hub.ExtensionStoreClient
import com.nexo.browser.extensions.hub.ExtensionStoreResolver
import com.nexo.browser.extensions.hub.ExtensionUpdateManager
import com.nexo.browser.extensions.nxr.NxrPackageInstaller
import com.nexo.browser.extensions.nxr.NxrStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionHubStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val rsaA: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private val rsaB: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v and 0x7f.inv().toLong() == 0L) { out.write(v.toInt()); break }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        return out.toByteArray()
    }

    private fun field(number: Int, bytes: ByteArray): ByteArray =
        varint((number.toLong() shl 3) or 2) + varint(bytes.size.toLong()) + bytes

    private fun signCrx3(pair: KeyPair, manifestJson: String, jsBody: String = "console.log('ok');"): File {
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("manifest.json"))
                out.write(manifestJson.toByteArray(Charsets.UTF_8))
                out.closeEntry()
                out.putNextEntry(ZipEntry("content.js"))
                out.write(jsBody.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }.toByteArray()
        val crxId = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded).copyOf(16)
        val signedHeader = field(1, crxId)
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update("CRX3 SignedData\u0000".toByteArray(Charsets.US_ASCII))
            update(CrxPackage.u32le(signedHeader.size.toLong()))
            update(signedHeader)
            update(archive)
            sign()
        }
        val header = field(2, field(1, pair.public.encoded) + field(2, sig)) + field(10000, signedHeader)
        return folder.newFile().apply {
            writeBytes("Cr24".toByteArray() + CrxPackage.u32le(3) + CrxPackage.u32le(header.size.toLong()) + header + archive)
        }
    }

    @Test fun detectsChromeWebStoreAndEdgeAddonsDetailUrls() {
        val id = "cjpalhdlnbpafiamejdnhcphjbkeiagm"
        val cws = ExtensionStoreResolver.detectStorePage("https://chromewebstore.google.com/detail/ublock-origin/$id?hl=es")
        assertNotNull(cws)
        assertEquals(ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE, cws!!.store)
        assertEquals(id, cws.extensionId)

        val cwsLegacy = ExtensionStoreResolver.detectStorePage("https://chrome.google.com/webstore/detail/ublock/$id")
        assertNotNull(cwsLegacy)
        assertEquals(ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE, cwsLegacy!!.store)

        val edge = ExtensionStoreResolver.detectStorePage("https://microsoftedge.microsoft.com/addons/detail/dark-reader/ifoakfbpdcdoeenechcleahebpibofpc")
        assertNotNull(edge)
        assertEquals(ExtensionStoreResolver.StoreKind.EDGE_ADDONS, edge!!.store)
        assertEquals("ifoakfbpdcdoeenechcleahebpibofpc", edge.extensionId)

        val edgeLocale = ExtensionStoreResolver.detectStorePage("https://microsoftedge.microsoft.com/es-es/addons/detail/dark-reader/ifoakfbpdcdoeenechcleahebpibofpc")
        assertNotNull(edgeLocale)
        assertEquals(ExtensionStoreResolver.StoreKind.EDGE_ADDONS, edgeLocale!!.store)
        assertEquals("ifoakfbpdcdoeenechcleahebpibofpc", edgeLocale.extensionId)

        // Detección amplia de páginas de tienda para activar modo escritorio automáticamente
        assertTrue(ExtensionStoreResolver.isExtensionStoreUrl("https://chromewebstore.google.com/"))
        assertTrue(ExtensionStoreResolver.isExtensionStoreUrl("https://chromewebstore.google.com/search/dark%20reader"))
        assertTrue(ExtensionStoreResolver.isExtensionStoreUrl("https://chrome.google.com/webstore/category/extensions"))
        assertTrue(ExtensionStoreResolver.isExtensionStoreUrl("https://microsoftedge.microsoft.com/addons/Microsoft-Edge-Extensions-Home"))
        assertTrue(ExtensionStoreResolver.isExtensionStoreUrl("https://microsoftedge.microsoft.com/es-es/addons/detail/dark-reader/ifoakfbpdcdoeenechcleahebpibofpc"))
        assertFalse(ExtensionStoreResolver.isExtensionStoreUrl("https://chrome.google.com/sync"))
        assertFalse(ExtensionStoreResolver.isExtensionStoreUrl("https://microsoftedge.microsoft.com/welcome"))
        assertFalse(ExtensionStoreResolver.isExtensionStoreUrl("https://example.com/chromewebstore.google.com"))
        assertFalse(ExtensionStoreResolver.isExtensionStoreUrl("https://user:pass@chromewebstore.google.com/"))

        // Rechaza HTTP sin cifrar, credenciales en URL, hosts falsos o páginas sin ID de 32 caracteres [a-p]
        assertNull(ExtensionStoreResolver.detectStorePage("http://chromewebstore.google.com/detail/ublock/$id"))
        assertNull(ExtensionStoreResolver.detectStorePage("https://user:pass@chromewebstore.google.com/detail/ublock/$id"))
        assertNull(ExtensionStoreResolver.detectStorePage("https://chromewebstore.google.com/search/ublock"))
        assertNull(ExtensionStoreResolver.detectStorePage("https://evil.example/detail/$id"))
        assertNull(ExtensionStoreResolver.detectStorePage("https://microsoftedge.microsoft.com/addons/detail/invalid/zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
    }

    @Test fun resolvesDirectPackagesIdsAndSearchQueriesSafely() {
        val direct = ExtensionStoreResolver.resolveInput("https://releases.example.org/ext/reader-1.2.crx?dl=1")
        assertTrue(direct is ExtensionStoreResolver.ResolvedTarget.DirectPackage)

        // Rechaza http://, localhost e IPs locales como paquetes directos
        assertFalse(ExtensionStoreResolver.isDirectExtensionPackageUrl("http://releases.example.org/ext.crx"))
        assertFalse(ExtensionStoreResolver.isDirectExtensionPackageUrl("https://localhost/ext.crx"))
        assertFalse(ExtensionStoreResolver.isDirectExtensionPackageUrl("https://192.168.1.5/ext.zip"))
        assertFalse(ExtensionStoreResolver.isDirectExtensionPackageUrl("https://user@releases.example.org/ext.crx"))

        val rawId = ExtensionStoreResolver.resolveInput("EIMADPBCBFNMBKOPOOJFEKHNKHDBIEEH")
        assertTrue(rawId is ExtensionStoreResolver.ResolvedTarget.RawExtensionId)
        assertEquals("eimadpbcbfnmbkopoojfekhnkhdbieeh", (rawId as ExtensionStoreResolver.ResolvedTarget.RawExtensionId).extensionId)

        val search = ExtensionStoreResolver.resolveInput("dark reader")
        assertTrue(search is ExtensionStoreResolver.ResolvedTarget.StoreSearch)
        assertTrue(ExtensionStoreResolver.cwsSearchUrl("dark reader").contains("dark%20reader"))
        assertTrue(ExtensionStoreResolver.edgeSearchUrl("dark reader").contains("dark%20reader"))

        // Parámetros Omaha de escritorio y CDN de Google
        val omaha = ExtensionStoreClient.cwsOmahaUrl("eimadpbcbfnmbkopoojfekhnkhdbieeh")
        assertTrue(omaha.contains("prod=chromecrx"))
        assertTrue(omaha.contains("acceptformat=crx2,crx3"))
        assertTrue(omaha.contains("prodversion=158.0.0.0"))
        assertTrue(ExtensionStoreClient.cwsDirectRedirectUrl("eimadpbcbfnmbkopoojfekhnkhdbieeh").contains("response=redirect"))
        assertTrue(ExtensionStoreResolver.isAllowedCwsDownloadHost("edgedl.me.gvt1.com"))
        assertTrue(ExtensionStoreResolver.isAllowedCwsDownloadHost("r2.sn-test.gvt2.com"))
    }

    @Test fun comparesSemanticVersionsAccurately() {
        assertTrue(ExtensionStoreResolver.compareVersions("1.2.0", "1.1.9") > 0)
        assertTrue(ExtensionStoreResolver.compareVersions("2.0", "1.99.9") > 0)
        assertEquals(0, ExtensionStoreResolver.compareVersions("1.2.0", "1.2"))
        assertTrue(ExtensionStoreResolver.compareVersions("1.2.3", "1.2.10") < 0)
    }

    @Test fun parsesChromeWebStoreOmahaXmlAndRejectsUnsafeResponses() {
        val id = "eimadpbcbfnmbkopoojfekhnkhdbieeh"
        val sha = "a".repeat(64)
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gupdate xmlns="http://www.google.com/update2/response" protocol="2.0">
              <app appid="$id" status="ok">
                <updatecheck status="ok"
                  codebase="https://clients2.googleusercontent.com/crx/blobs/abc123/ext.crx"
                  version="4.9.100"
                  hash_sha256="$sha"
                  size="52428800"/>
              </app>
            </gupdate>
        """.trimIndent()
        val listing = ExtensionStoreClient.parseCwsOmahaXml(id, xml)
        assertEquals(ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE, listing.store)
        assertEquals("4.9.100", listing.version)
        assertEquals(sha, listing.expectedSha256)
        assertEquals(52428800L, listing.expectedSizeBytes)

        // noupdate debe fallar con error claro
        val noUpdateXml = """<gupdate><app appid="$id"><updatecheck status="noupdate"/></app></gupdate>"""
        assertThrows(ExtensionStoreClient.StoreException::class.java) {
            ExtensionStoreClient.parseCwsOmahaXml(id, noUpdateXml)
        }

        // Host no permitido en codebase debe ser rechazado
        val evilXml = """<gupdate><app appid="$id"><updatecheck status="ok" codebase="https://evil.example/fake.crx" version="1.0"/></app></gupdate>"""
        assertThrows(ExtensionStoreClient.StoreException::class.java) {
            ExtensionStoreClient.parseCwsOmahaXml(id, evilXml)
        }

        // Paquete desproporcionado (> 256 MB) rechazado
        val hugeXml = """<gupdate><app appid="$id"><updatecheck status="ok" codebase="https://clients2.googleusercontent.com/crx/blobs/big.crx" version="1.0" size="300000000"/></app></gupdate>"""
        assertThrows(ExtensionStoreClient.StoreException::class.java) {
            ExtensionStoreClient.parseCwsOmahaXml(id, hugeXml)
        }
    }

    @Test fun parsesEdgeAddonsJsonWithRealPreflightAndNoFakeMetrics() {
        val id = "ifoakfbpdcdoeenechcleahebpibofpc"
        val json = """
            {
              "crxId": "$id",
              "name": "Lector Limpio",
              "developer": "Nexo Labs",
              "version": "2.4.1",
              "shortDescription": "Ajusta tipografía en artículos.",
              "privacyUrl": "https://example.org/privacy",
              "averageRating": 4.7,
              "ratingCount": 120,
              "activeInstallCount": 5000,
              "manifest": "{\"manifest_version\":3,\"name\":\"Lector Limpio\",\"version\":\"2.4.1\",\"permissions\":[\"storage\"],\"content_scripts\":[{\"matches\":[\"https://example.org/*\"],\"js\":[\"content.js\"]}]}"
            }
        """.trimIndent()
        val listing = ExtensionStoreClient.parseEdgeDetailsJson(id, json)
        assertEquals("Lector Limpio", listing.name)
        assertEquals("Nexo Labs", listing.developer)
        assertEquals("2.4.1", listing.version)
        assertEquals(4.7, listing.rating!!, 0.001)
        assertEquals(120, listing.ratingCount)
        assertEquals(5000L, listing.activeUsers)
        assertNotNull(listing.preflightCapabilities)
        // storage.local está soportado y storage.sync no -> honestamente PARTIAL
        assertEquals(ExtensionCompatibility.InstallVerdict.PARTIAL, listing.preflightCapabilities!!.verdict)

        // Sin métricas en el JSON -> null (nunca inventa reseñas ni estrellas)
        val minimal = ExtensionStoreClient.parseEdgeDetailsJson(id, """{"crxId":"$id","name":"Simple","version":"1.0"}""")
        assertNull(minimal.rating)
        assertNull(minimal.ratingCount)
        assertNull(minimal.activeUsers)

        // ID distinto rechazado
        assertThrows(ExtensionStoreClient.StoreException::class.java) {
            ExtensionStoreClient.parseEdgeDetailsJson(id, """{"crxId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","name":"Otra","version":"1.0"}""")
        }
    }

    @Test fun transactionalUpdateEnforcesPermissionConsentAndKeyContinuityAndPreservesStorage() {
        val rootDir = folder.newFolder("nxr-root")
        val extId = CrxPackage.extensionIdFor(rsaA.public.encoded)
        val v1Manifest = """
            {
              "manifest_version": 3,
              "name": "Demo Store Ext",
              "version": "1.0.0",
              "permissions": ["storage"],
              "content_scripts": [{"matches": ["https://example.org/*"], "js": ["content.js"]}]
            }
        """.trimIndent()
        val crxV1 = signCrx3(rsaA, v1Manifest)
        val inspectV1 = NxrPackageInstaller.inspect(crxV1, expectedId = extId)
        val sourceV1 = NxrPackageInstaller.SourceInfo(
            sourceOrigin = NxrPackageInstaller.ORIGIN_CHROME_WEB_STORE,
            storeId = extId,
            sourceUrl = ExtensionStoreResolver.cwsDetailUrl(extId),
            publicKeySha256 = inspectV1.publicKeySha256,
            developer = "Demo Dev",
            signatureVerified = true
        )
        val installedV1 = NxrPackageInstaller.install(
            rootDir = rootDir,
            packageFile = crxV1,
            grantedHosts = inspectV1.requestedHosts,
            sourceInfo = sourceV1,
            expectedId = extId
        )
        assertEquals("1.0.0", installedV1.version)

        // Guardar estado en chrome.storage.local para comprobar que sobrevive la actualización
        val storage = NxrStorage(rootDir)
        storage.set(extId, org.json.JSONObject().put("theme", "sepia"))

        // 1) Comprobar detección de actualización
        val listingV2 = ExtensionStoreClient.StoreListing(
            store = ExtensionStoreResolver.StoreKind.CHROME_WEB_STORE,
            extensionId = extId,
            name = "Demo Store Ext",
            developer = "Demo Dev",
            version = "1.1.0",
            shortDescription = "",
            detailUrl = ExtensionStoreResolver.cwsDetailUrl(extId),
            downloadUrl = "https://clients2.googleusercontent.com/crx/blobs/v2.crx"
        )
        val check = ExtensionUpdateManager.checkForUpdate(installedV1) { _, _ -> listingV2 }
        assertTrue(check.hasUpdate)
        assertEquals("1.1.0", check.remoteVersion)

        // 2) Actualización con mismos permisos aplica directamente sin pedir consentimiento extra
        val crxV2 = signCrx3(rsaA, v1Manifest.replace("1.0.0", "1.1.0"))
        val updatedV2 = ExtensionUpdateManager.applyVerifiedUpdate(rootDir, installedV1, crxV2, listingV2, consentGranted = false)
        assertEquals("1.1.0", updatedV2.version)
        assertEquals("sepia", storage.get(extId, "theme").optString("theme"))

        // 3) Actualización que añade nuevo sitio exige consentimiento explícito
        val v3Manifest = """
            {
              "manifest_version": 3,
              "name": "Demo Store Ext",
              "version": "1.2.0",
              "permissions": ["storage", "tabs"],
              "content_scripts": [{"matches": ["https://example.org/*", "https://docs.example.net/*"], "js": ["content.js"]}]
            }
        """.trimIndent()
        val crxV3 = signCrx3(rsaA, v3Manifest)
        val listingV3 = listingV2.copy(version = "1.2.0")
        val consentEx = assertThrows(ExtensionUpdateManager.UpdateConsentRequiredException::class.java) {
            ExtensionUpdateManager.applyVerifiedUpdate(rootDir, updatedV2, crxV3, listingV3, consentGranted = false)
        }
        assertTrue(consentEx.diff.requiresConsent)
        assertTrue(consentEx.diff.addedPermissions.contains("tabs"))
        assertTrue(consentEx.diff.addedHosts.contains("https://docs.example.net/*"))

        // Con consentimiento explícito sí actualiza y mantiene storage
        val updatedV3 = ExtensionUpdateManager.applyVerifiedUpdate(rootDir, updatedV2, crxV3, listingV3, consentGranted = true)
        assertEquals("1.2.0", updatedV3.version)
        assertEquals("sepia", storage.get(extId, "theme").optString("theme"))

        // 4) Paquete firmado con otra clave (cambio de firmante) debe fallar y dejar v3 intacta
        val rogueCrx = signCrx3(rsaB, v3Manifest.replace("1.2.0", "2.0.0"))
        assertThrows(NxrPackageInstaller.InstallException::class.java) {
            ExtensionUpdateManager.applyVerifiedUpdate(rootDir, updatedV3, rogueCrx, listingV3.copy(version = "2.0.0"), consentGranted = true)
        }
        val stillInstalled = NxrPackageInstaller.listInstalled(rootDir).first { it.id == extId }
        assertEquals("1.2.0", stillInstalled.version)
        assertEquals("sepia", storage.get(extId, "theme").optString("theme"))
    }
    @Test fun automaticDesktopModeUsesOnlyTheTwoHttpsStoreOrigins() {
        assertTrue(ExtensionStoreResolver.usesStoreDesktopProfile("https://chromewebstore.google.com/detail/example"))
        assertTrue(ExtensionStoreResolver.usesStoreDesktopProfile("https://microsoftedge.microsoft.com/es-es/addons/"))
        assertFalse(ExtensionStoreResolver.usesStoreDesktopProfile("http://chromewebstore.google.com/"))
        assertFalse(ExtensionStoreResolver.usesStoreDesktopProfile("https://chromewebstore.google.com:8443/"))
        assertFalse(ExtensionStoreResolver.usesStoreDesktopProfile("https://chrome.google.com/webstore/"))
        assertFalse(ExtensionStoreResolver.usesStoreDesktopProfile("https://chromewebstore.google.com.example.net/"))
        assertFalse(ExtensionStoreResolver.usesStoreDesktopProfile("https://microsoftedge.microsoft.com/welcome"))
    }
}
