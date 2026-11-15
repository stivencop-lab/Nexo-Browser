package com.nexo.browser.extensions

import org.junit.Assert.assertEquals
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
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CrxPackageTest {
    @get:Rule val folder = TemporaryFolder()

    private val rsa: KeyPair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    private val ec: KeyPair by lazy { KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair() }

    private fun zip(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write("""{"manifest_version":3,"name":"Prueba","version":"1.0"}""".toByteArray())
            out.closeEntry()
        }
    }.toByteArray()

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

    private fun sign(algorithm: String, pair: KeyPair, signedHeader: ByteArray, archive: ByteArray): ByteArray =
        Signature.getInstance(algorithm).run {
            initSign(pair.private)
            update("CRX3 SignedData\u0000".toByteArray(Charsets.US_ASCII))
            update(CrxPackage.u32le(signedHeader.size.toLong()))
            update(signedHeader)
            update(archive)
            sign()
        }

    /** CRX3 firmado por [developer] (y opcionalmente por una segunda clave, como la de la tienda). */
    private fun crx3(developer: KeyPair, extra: KeyPair? = null, archive: ByteArray = zip(), corruptAfterSigning: Boolean = false): File {
        val crxId = MessageDigest.getInstance("SHA-256").digest(developer.public.encoded).copyOf(16)
        val signedHeader = field(1, crxId)
        val developerAlgorithm = if (developer.public.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
        val developerField = if (developer.public.algorithm == "RSA") 2 else 3
        var header = field(
            developerField,
            field(1, developer.public.encoded) + field(2, sign(developerAlgorithm, developer, signedHeader, archive))
        )
        if (extra != null) {
            header += field(2, field(1, extra.public.encoded) + field(2, sign("SHA256withRSA", extra, signedHeader, archive)))
        }
        header += field(10000, signedHeader)
        val body = archive.copyOf().also { if (corruptAfterSigning) it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        return folder.newFile().apply {
            writeBytes("Cr24".toByteArray() + CrxPackage.u32le(3) + CrxPackage.u32le(header.size.toLong()) + header + body)
        }
    }

    @Test fun verifiesCrx3AndDerivesTheChromeId() {
        val file = crx3(rsa)
        val verified = CrxPackage.verify(file)
        assertEquals(3, verified.formatVersion)
        assertEquals(CrxPackage.extensionIdFor(rsa.public.encoded), verified.extensionId)
        assertTrue(verified.extensionId.matches(Regex("[a-p]{32}")))
        assertTrue(CrxPackage.isCrx(file))
        val names = ZipInputStream(CrxPackage.openZip(file, verified)).use { input ->
            generateSequence { input.nextEntry?.name }.toList()
        }
        assertEquals(listOf("manifest.json"), names)
    }

    @Test fun acceptsEcdsaDevelopersAndExtraPublisherProofs() {
        val verified = CrxPackage.verify(crx3(ec, extra = rsa))
        assertEquals(CrxPackage.extensionIdFor(ec.public.encoded), verified.extensionId)
    }

    @Test fun rejectsTamperedArchivesAndWrongIds() {
        assertThrows(CrxPackage.InvalidCrxException::class.java) {
            CrxPackage.verify(crx3(rsa, corruptAfterSigning = true))
        }
        assertThrows(CrxPackage.InvalidCrxException::class.java) {
            CrxPackage.verify(crx3(rsa), expectedId = "a".repeat(32))
        }
        val notCrx = folder.newFile().apply { writeBytes(zip()) }
        assertThrows(CrxPackage.InvalidCrxException::class.java) { CrxPackage.verify(notCrx) }
        assertEquals(false, CrxPackage.isCrx(notCrx))
        val truncated = folder.newFile().apply { writeBytes("Cr24".toByteArray() + CrxPackage.u32le(3) + CrxPackage.u32le(999_999)) }
        assertThrows(CrxPackage.InvalidCrxException::class.java) { CrxPackage.verify(truncated) }
    }

    @Test fun rejectsAHeaderWhoseIdDoesNotMatchAnySigner() {
        // Firmado correctamente por otra clave, pero declarando el ID de la víctima.
        val victimId = MessageDigest.getInstance("SHA-256").digest(rsa.public.encoded).copyOf(16)
        val archive = zip()
        val signedHeader = field(1, victimId)
        val header = field(2, field(1, ec.public.encoded) + field(2, sign("SHA256withECDSA", ec, signedHeader, archive))) +
            field(10000, signedHeader)
        val file = folder.newFile().apply {
            writeBytes("Cr24".toByteArray() + CrxPackage.u32le(3) + CrxPackage.u32le(header.size.toLong()) + header + archive)
        }
        // La firma ECDSA se declara como RSA (campo 2): falla la verificación de clave o de firma.
        assertThrows(CrxPackage.InvalidCrxException::class.java) { CrxPackage.verify(file) }

        val honestHeader = field(3, field(1, ec.public.encoded) + field(2, sign("SHA256withECDSA", ec, signedHeader, archive))) +
            field(10000, signedHeader)
        val forged = folder.newFile().apply {
            writeBytes("Cr24".toByteArray() + CrxPackage.u32le(3) + CrxPackage.u32le(honestHeader.size.toLong()) + honestHeader + archive)
        }
        assertThrows(CrxPackage.InvalidCrxException::class.java) { CrxPackage.verify(forged) }
    }

    @Test fun verifiesLegacyCrx2() {
        val archive = zip()
        val signature = Signature.getInstance("SHA1withRSA").run { initSign(rsa.private); update(archive); sign() }
        val key = rsa.public.encoded
        val file = folder.newFile().apply {
            writeBytes(
                "Cr24".toByteArray() + CrxPackage.u32le(2) + CrxPackage.u32le(key.size.toLong()) +
                    CrxPackage.u32le(signature.size.toLong()) + key + signature + archive
            )
        }
        val verified = CrxPackage.verify(file)
        assertEquals(2, verified.formatVersion)
        assertEquals(CrxPackage.extensionIdFor(key), verified.extensionId)
    }
}
