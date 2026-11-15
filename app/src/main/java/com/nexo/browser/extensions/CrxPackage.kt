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
 * Paquete CRX de Chrome Web Store (formatos 3 y 2): valida la cabecera,
 * comprueba las firmas sobre el ZIP y deriva el ID de la extensión de su
 * clave pública, igual que Chrome. Puro: probado en la JVM.
 *
 * CRX3: `Cr24` · versión (u32 LE) · tamaño de cabecera (u32 LE) ·
 * `CrxFileHeader` (protobuf) · ZIP. Cada prueba firma
 * `"CRX3 SignedData\0" + u32 LE(len(signed_header_data)) + signed_header_data + ZIP`.
 */
object CrxPackage {
    private const val MAGIC = "Cr24"
    private const val MAX_HEADER_BYTES = 256 * 1024
    private val SIGNATURE_CONTEXT = "CRX3 SignedData\u0000".toByteArray(Charsets.US_ASCII)

    class InvalidCrxException(message: String) : Exception(message)

    data class Verified(
        /** ID de 32 letras `a`–`p` derivado de la clave del desarrollador. */
        val extensionId: String,
        val formatVersion: Int,
        /** Posición del ZIP dentro del archivo. */
        val zipOffset: Long,
        /** Clave del desarrollador (SubjectPublicKeyInfo DER), para el campo `key` del manifiesto. */
        val publicKey: ByteArray
    )

    /** ID de Chrome: primeros 16 bytes del SHA-256 de la clave, en hexadecimal con letras `a`–`p`. */
    fun extensionIdFor(publicKeyDer: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyDer)
        return idFromCrxId(digest.copyOf(16))
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
     * el ID o alguna firma no son correctos. [expectedId], si se da, debe coincidir.
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
        val zipOffset = 12L + headerSize
        if (zipOffset >= file.length()) throw InvalidCrxException("El CRX no contiene archivos.")

        val fields = Protobuf.read(header)
        val rsaProofs = fields.filter { it.number == 2 }.map { proof(it.bytes) }
        val ecdsaProofs = fields.filter { it.number == 3 }.map { proof(it.bytes) }
        if (rsaProofs.size + ecdsaProofs.size > 8) throw InvalidCrxException("Demasiadas firmas en el CRX.")
        val signedHeaderData = fields.lastOrNull { it.number == 10000 }?.bytes
            ?: throw InvalidCrxException("El CRX3 no declara su ID.")
        val crxId = Protobuf.read(signedHeaderData).lastOrNull { it.number == 1 }?.bytes
        if (crxId == null || crxId.size != 16) throw InvalidCrxException("ID de CRX3 no válido.")

        val proofs = rsaProofs.map { it to "SHA256withRSA" } + ecdsaProofs.map { it to "SHA256withECDSA" }
        if (proofs.isEmpty()) throw InvalidCrxException("El CRX3 no está firmado.")

        val signedPrefix = ByteArrayOutputStream().apply {
            write(SIGNATURE_CONTEXT)
            write(u32le(signedHeaderData.size.toLong()))
            write(signedHeaderData)
        }.toByteArray()

        var developerKey: ByteArray? = null
        for ((proof, algorithm) in proofs) {
            val key = proof.first
            val signatureBytes = proof.second
            val verifier = Signature.getInstance(algorithm)
            verifier.initVerify(publicKey(key, if (algorithm.endsWith("RSA")) "RSA" else "EC"))
            verifier.update(signedPrefix)
            updateFromFile(verifier, file, zipOffset)
            if (!verifier.verify(signatureBytes)) throw InvalidCrxException("Una firma del CRX no es válida.")
            val keyId = MessageDigest.getInstance("SHA-256").digest(key).copyOf(16)
            if (keyId.contentEquals(crxId)) developerKey = key
        }
        val key = developerKey ?: throw InvalidCrxException("Ninguna firma corresponde al ID del CRX.")
        return Verified(idFromCrxId(crxId), 3, zipOffset, key)
    }

    private fun verifyCrx2(raf: RandomAccessFile, file: File): Verified {
        val keyLength = readU32(raf)
        val signatureLength = readU32(raf)
        if (keyLength !in 1..16_384 || signatureLength !in 1..16_384) throw InvalidCrxException("Cabecera CRX2 no válida.")
        val key = ByteArray(keyLength.toInt()).also { raf.readFully(it) }
        val signatureBytes = ByteArray(signatureLength.toInt()).also { raf.readFully(it) }
        val zipOffset = 16L + keyLength + signatureLength
        if (zipOffset >= file.length()) throw InvalidCrxException("El CRX no contiene archivos.")
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(publicKey(key, "RSA"))
        updateFromFile(verifier, file, zipOffset)
        if (!verifier.verify(signatureBytes)) throw InvalidCrxException("La firma del CRX no es válida.")
        return Verified(extensionIdFor(key), 2, zipOffset, key)
    }

    /** Abre el ZIP contenido (después de [verify]). */
    fun openZip(file: File, verified: Verified): InputStream =
        file.inputStream().also { input ->
            var remaining = verified.zipOffset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) throw InvalidCrxException("CRX truncado.")
                remaining -= skipped
            }
        }

    private fun proof(bytes: ByteArray?): Pair<ByteArray, ByteArray> {
        val fields = Protobuf.read(bytes ?: throw InvalidCrxException("Prueba de firma no válida."))
        val key = fields.lastOrNull { it.number == 1 }?.bytes ?: throw InvalidCrxException("Prueba sin clave.")
        val signature = fields.lastOrNull { it.number == 2 }?.bytes ?: throw InvalidCrxException("Prueba sin firma.")
        return key to signature
    }

    private fun publicKey(der: ByteArray, algorithm: String): PublicKey =
        runCatching { KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(der)) }
            .getOrElse { throw InvalidCrxException("Clave pública del CRX no válida.") }

    private fun updateFromFile(signature: Signature, file: File, offset: Long) {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = raf.read(buffer)
                if (read < 0) break
                signature.update(buffer, 0, read)
            }
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

/** Lector mínimo de protobuf: solo los tipos que usa la cabecera CRX3. */
internal object Protobuf {
    class Field(val number: Int, val bytes: ByteArray?)

    fun read(data: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var index = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (index >= data.size || shift > 63) throw CrxPackage.InvalidCrxException("Cabecera CRX dañada.")
                val byte = data[index++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }
        while (index < data.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> { varint(); fields += Field(number, null) }
                1 -> { index += 8; fields += Field(number, null) }
                2 -> {
                    val length = varint()
                    if (length < 0 || length > data.size - index) throw CrxPackage.InvalidCrxException("Cabecera CRX dañada.")
                    fields += Field(number, data.copyOfRange(index, index + length.toInt()))
                    index += length.toInt()
                }
                5 -> { index += 4; fields += Field(number, null) }
                else -> throw CrxPackage.InvalidCrxException("Cabecera CRX dañada.")
            }
            if (index > data.size) throw CrxPackage.InvalidCrxException("Cabecera CRX dañada.")
        }
        return fields
    }
}
