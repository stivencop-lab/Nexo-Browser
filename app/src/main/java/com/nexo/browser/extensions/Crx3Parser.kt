package com.nexo.browser.extensions

/** CRX3 protobuf header. Unknown fields are skipped and signatures are checked by CrxPackage. */
internal object Crx3Parser {
    class Proof(val key: ByteArray, val signature: ByteArray, val keyAlgorithm: String) {
        val algorithm = if (keyAlgorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
    }
    class Header(val signedData: ByteArray, val crxId: ByteArray, val proofs: List<Proof>)

    fun parse(header: ByteArray): Header {
        val fields = read(header, 2, 3, 10000)
        val proofs = fields.count { it.first == 2 || it.first == 3 }
        if (proofs !in 1..8) invalid("Cantidad de firmas CRX3 no válida.")
        val signed = fields.lastOrNull { it.first == 10000 }?.second ?: invalid("El CRX3 no declara su ID.")
        val id = read(signed, 1).lastOrNull()?.second
        if (id == null || id.size != 16) invalid("ID de CRX3 no válido.")
        return Header(signed, id, fields.filter { it.first in 2..3 }.map { (number, data) ->
            val proof = read(data, 1, 2)
            val key = proof.lastOrNull { it.first == 1 }?.second ?: invalid("Prueba sin clave.")
            val signature = proof.lastOrNull { it.first == 2 }?.second ?: invalid("Prueba sin firma.")
            Proof(key, signature, if (number == 2) "RSA" else "EC")
        })
    }

    private fun read(data: ByteArray, vararg selected: Int): List<Pair<Int, ByteArray>> {
        val fields = mutableListOf<Pair<Int, ByteArray>>()
        var index = 0
        fun varint(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                if (index == data.size) invalid()
                val byte = data[index++].toInt() and 0xff
                if (shift == 63 && byte > 1) invalid()
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte < 128) return result
            }
            invalid()
        }
        fun skip(count: Int) {
            if (count !in 0..(data.size - index)) invalid()
            index += count
        }
        while (index < data.size) {
            val key = varint()
            val number = key ushr 3
            if (number !in 1..0x1FFFFFFFL) invalid()
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> skip(8)
                2 -> {
                    val length = varint()
                    if (length !in 0L..(data.size - index).toLong()) invalid()
                    val start = index
                    skip(length.toInt())
                    if (number.toInt() in selected) fields += number.toInt() to data.copyOfRange(start, index)
                }
                5 -> skip(4)
                else -> invalid()
            }
        }
        return fields
    }

    private fun invalid(message: String = "Cabecera CRX3 dañada."): Nothing =
        throw CrxPackage.InvalidCrxException(message)
}
