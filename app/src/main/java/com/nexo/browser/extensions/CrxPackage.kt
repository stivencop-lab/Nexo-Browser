package com.nexo.browser.extensions

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Paquete CRX de Chrome Web Store / Edge Add-ons (formatos 3 y 2): valida la cabecera,
 * rechaza firmas ZIP EOCD incrustadas en la cabecera (anti-políglota), comprueba todas las
 * firmas criptográficas sobre el ZIP, deriva el ID y expone el hash SHA-256 de la clave pública
 * para continuidad de clave en actualizaciones. Puro: probado en la JVM.
 *
 * CRX3: `Cr24` · versión (u32 LE) · tamaño de cabecera (u32 LE) ·
 * `CrxFileHeader` (protobuf) · ZIP. Cada prueba firma
 * `"CRX3 SignedData\0" + u32 LE(len(signed_header_data)) + signed_header_data + ZIP`.
 */
object CrxPackage {
    private const val MAGIC = "Cr24"
    private const val MAX_HEADER_BYTES = 256 * 1024
    private val SIGNATURE_CONTEXT = "CRX3 SignedData\u0000".toByteArray(Charsets.US_ASCII)
    private val ZIP_EOCD = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP64_EOCD = byteArrayOf(0x50, 0x4B, 0x06, 0x06)
    private val ZIP64_LOCATOR = byteArrayOf(0x50, 0x4B, 0x06, 0x07)

    class InvalidCrxException(message: String) : Exception(message)

    data class Verified(
        /** ID de 32 letras `a`–`p` derivado de la clave del desarrollador. */
        val extensionId: String,
        val formatVersion: Int,
        /** Posición del ZIP dentro del archivo. */
        val zipOffset: Long,
        /** Clave del desarrollador (SubjectPublicKeyInfo DER), para el campo `key` del manifiesto. */
        val publicKey: ByteArray,
        /** Huella SHA-256 en hexadecimal de [publicKey] para continuidad de clave en actualizaciones. */
        val publicKeySha256: String = sha256Hex(publicKey),
        /** Número de firmas criptográficas válidas verificadas en el paquete. */
        val signerCount: Int = 1
    )

    /** ID de Chrome: primeros 16 bytes del SHA-256 de la clave, en hexadecimal con letras `a`–`p`. */
    fun extensionIdFor(publicKeyDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyDer)
        return idFromCrxId(digest.copyOf(16))
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun idFromCrxId(crxId: ByteArray): String = buildString(32) {
        for (byte in crxId) {
            val value = byte.toInt() and 0xff
            append('a' + (value ushr 4))
            append('a' + (value and 0x0f))
        }
    }

    fun isCrx(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.length() >= 4 && raf.read(magic) == 4 && String(magic, Charsets.US_ASCII) == MAGIC
        }
    }.getOrDefault(false)

    /**
     * Comprueba el paquete entero; lanza [InvalidCrxException] si la cabecera,
     * el ID, el payload ZIP o alguna firma no son correctos. [expectedId], si se da, debe coincidir.
     */
    fun verify(file: File, expectedId: String? = null): Verified {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4).also { raf.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != MAGIC) throw InvalidCrxException("No es un paquete CRX.")
            val version = readU32(raf)
            val verified = when (version) {
                3L -> verifyCrx3(raf, file)
                2L -> verifyCrx2(raf, file)
                else -> throw InvalidCrxException("Versión de CRX no admitida: $version.")
            }
            if (expectedId != null && verified.extensionId != expectedId) {
                throw InvalidCrxException("El paquete no corresponde a la extensión pedida.")
            }
            return verified
        }
    }

    private fun verifyCrx3(raf: RandomAccessFile, file: File): Verified {
        val headerSize = readU32(raf)
        if (headerSize <= 0 || headerSize > MAX_HEADER_BYTES) throw InvalidCrxException("Cabecera CRX3 no válida.")
        val header = ByteArray(headerSize.toInt()).also { raf.readFully(it) }
        rejectEmbeddedZipEocd(header)
        val zipOffset = 12L + headerSize
        if (zipOffset >= file.length()) throw InvalidCrxException("El CRX no contiene archivos.")
        verifyZipStartsHere(raf, zipOffset)

        val parsed = Crx3Parser.parse(header)
        val signedHeaderData = parsed.signedData
        val crxId = parsed.crxId
        val signedPrefix = ByteArrayOutputStream().apply {
            write(SIGNATURE_CONTEXT)
            write(u32le(signedHeaderData.size.toLong()))
            write(signedHeaderData)
        }.toByteArray()

        val verifiers = parsed.proofs.map { proof ->
            Signature.getInstance(proof.algorithm).apply {
                initVerify(publicKey(proof.key, proof.keyAlgorithm))
                update(signedPrefix)
            }
        }
        updateFromFile(verifiers, raf, zipOffset)
        var developerKey: ByteArray? = null
        for (index in parsed.proofs.indices) {
            val proof = parsed.proofs[index]
            val valid = runCatching { verifiers[index].verify(proof.signature) }.getOrDefault(false)
            if (!valid) throw InvalidCrxException("Una firma del CRX no es válida.")
            val keyId = MessageDigest.getInstance("SHA-256").digest(proof.key).copyOf(16)
            if (keyId.contentEquals(crxId)) developerKey = proof.key
        }
        val key = developerKey ?: throw InvalidCrxException("Ninguna firma corresponde al ID del CRX.")
        return Verified(
            extensionId = idFromCrxId(crxId),
            formatVersion = 3,
            zipOffset = zipOffset,
            publicKey = key,
            publicKeySha256 = sha256Hex(key),
            signerCount = parsed.proofs.size
        )
    }

    private fun verifyCrx2(raf: RandomAccessFile, file: File): Verified {
        val keyLength = readU32(raf)
        val signatureLength = readU32(raf)
        if (keyLength !in 1..16_384 || signatureLength !in 1..16_384) throw InvalidCrxException("Cabecera CRX2 no válida.")
        val key = ByteArray(keyLength.toInt()).also { raf.readFully(it) }
        val signatureBytes = ByteArray(signatureLength.toInt()).also { raf.readFully(it) }
        rejectEmbeddedZipEocd(key)
        rejectEmbeddedZipEocd(signatureBytes)
        val zipOffset = 16L + keyLength + signatureLength
        if (zipOffset >= file.length()) throw InvalidCrxException("El CRX no contiene archivos.")
        verifyZipStartsHere(raf, zipOffset)
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(publicKey(key, "RSA"))
        updateFromFile(listOf(verifier), raf, zipOffset)
        if (!verifier.verify(signatureBytes)) throw InvalidCrxException("La firma del CRX no es válida.")
        return Verified(
            extensionId = extensionIdFor(key),
            formatVersion = 2,
            zipOffset = zipOffset,
            publicKey = key,
            publicKeySha256 = sha256Hex(key),
            signerCount = 1
        )
    }

    /**
     * Rechaza cabeceras CRX que incrusten marcadores ZIP EOCD / ZIP64 EOCD para evitar
     * ataques políglotas donde un parser ZIP lee una tabla distinta a la firmada.
     */
    private fun rejectEmbeddedZipEocd(bytes: ByteArray) {
        if (containsSequence(bytes, ZIP_EOCD) ||
            containsSequence(bytes, ZIP64_EOCD) ||
            containsSequence(bytes, ZIP64_LOCATOR)
        ) {
            throw InvalidCrxException("Cabecera CRX rechazada: contiene marcador ZIP EOCD incrustado.")
        }
    }

    private fun containsSequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    private fun verifyZipStartsHere(raf: RandomAccessFile, zipOffset: Long) {
        if (raf.length() - zipOffset < 4) throw InvalidCrxException("Contenido ZIP truncado en CRX.")
        val prev = raf.filePointer
        raf.seek(zipOffset)
        val sig = ByteArray(4).also { raf.readFully(it) }
        raf.seek(prev)
        val localFile = sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte() && sig[2] == 0x03.toByte() && sig[3] == 0x04.toByte()
        val emptyZip = sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte() && sig[2] == 0x05.toByte() && sig[3] == 0x06.toByte()
        if (!localFile && !emptyZip) {
            throw InvalidCrxException("El bloque firmado del CRX no comienza con un archivo ZIP válido.")
        }
    }

    /** Abre el ZIP contenido (después de [verify]). */
    fun openZip(file: File, verified: Verified): InputStream =
        file.inputStream().let { input ->
            try {
                input.channel.position(verified.zipOffset)
                if (input.channel.size() <= verified.zipOffset) throw InvalidCrxException("CRX truncado.")
                input
            } catch (error: Throwable) {
                input.close()
                throw error
            }
        }

    private fun publicKey(der: ByteArray, algorithm: String): PublicKey =
        runCatching { KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(der)) }
            .getOrElse { throw InvalidCrxException("Clave pública del CRX no válida.") }

    private fun updateFromFile(signatures: List<Signature>, input: RandomAccessFile, offset: Long) {
        input.seek(offset)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            for (signature in signatures) signature.update(buffer, 0, read)
        }
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val bytes = ByteArray(4).also { raf.readFully(it) }
        return (bytes[0].toLong() and 0xff) or
            ((bytes[1].toLong() and 0xff) shl 8) or
            ((bytes[2].toLong() and 0xff) shl 16) or
            ((bytes[3].toLong() and 0xff) shl 24)
    }

    internal fun u32le(value: Long): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte()
    )
}
