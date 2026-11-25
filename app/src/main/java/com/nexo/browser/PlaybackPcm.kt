package com.nexo.browser

object PlaybackPcm {
    const val SAMPLE_RATE = 16000
    fun hasSignal(bytes: ByteArray): Boolean {
        for(i in 0 until bytes.size-1 step 2) {
            val value = ((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt()
            if (kotlin.math.abs(value) > 120) return true
        }
        return false
    }

    /** Detecta señal real incluso a volumen bajo del teléfono (evita falsos silencios al capturar). */
    fun hasWeakSignal(bytes: ByteArray, size: Int = bytes.size, floor: Int = 24): Boolean {
        var i = 0
        while (i + 1 < size) {
            val value = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
            if (kotlin.math.abs(value) > floor) return true
            i += 2
        }
        return false
    }

    fun listenUrl(language: String, model: String, separateSpeakers: Boolean = false): String =
        CaptionProtocol.listenUrl(language, model, separateSpeakers) +
        "&encoding=linear16&sample_rate=$SAMPLE_RATE&channels=1"

    /** Pico absoluto de un bloque PCM16 little-endian. */
    fun peak(bytes: ByteArray, size: Int = bytes.size): Int {
        var peak = 0
        var i = 0
        while (i + 1 < size) {
            val value = kotlin.math.abs(((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt())
            if (value > peak) peak = value
            i += 2
        }
        return peak
    }

    /**
     * Convierte PCM16 little-endian desde el mezclador nativo de Android (p. ej. 48 kHz estéreo,
     * 44.1 kHz estéreo o 48 kHz mono) a 16 kHz mono PCM16 listo para Deepgram.
     */
    fun to16kMono(input: ByteArray, size: Int, sampleRate: Int, channels: Int): ByteArray {
        val safeChannels = channels.coerceIn(1, 2)
        val frameBytes = safeChannels * 2
        val inFrames = (size / frameBytes).coerceAtLeast(0)
        if (inFrames == 0) return ByteArray(0)
        if (sampleRate == SAMPLE_RATE && safeChannels == 1) {
            return input.copyOf(inFrames * 2)
        }

        // Paso 1: mezclar a mono (promedio L+R si es estéreo).
        val mono = ShortArray(inFrames)
        if (safeChannels == 2) {
            var byteIdx = 0
            for (f in 0 until inFrames) {
                val left = ((input[byteIdx].toInt() and 0xff) or (input[byteIdx + 1].toInt() shl 8)).toShort().toInt()
                val right = ((input[byteIdx + 2].toInt() and 0xff) or (input[byteIdx + 3].toInt() shl 8)).toShort().toInt()
                mono[f] = ((left + right) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                byteIdx += 4
            }
        } else {
            var byteIdx = 0
            for (f in 0 until inFrames) {
                mono[f] = ((input[byteIdx].toInt() and 0xff) or (input[byteIdx + 1].toInt() shl 8)).toShort()
                byteIdx += 2
            }
        }

        if (sampleRate == SAMPLE_RATE) {
            return shortsToLittleEndian(mono, inFrames)
        }

        // Caso rápido y exacto: 48 kHz -> 16 kHz (factor 3 con filtro promedio anti-aliasing).
        if (sampleRate == 48000) {
            val outFrames = inFrames / 3
            if (outFrames <= 0) return ByteArray(0)
            val out = ByteArray(outFrames * 2)
            var outIdx = 0
            for (i in 0 until outFrames) {
                val base = i * 3
                val avg = (mono[base].toInt() + mono[base + 1].toInt() + mono[base + 2].toInt()) / 3
                val clamped = avg.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[outIdx++] = (clamped and 0xff).toByte()
                out[outIdx++] = ((clamped shr 8) and 0xff).toByte()
            }
            return out
        }

        // Remuestreo lineal genérico (p. ej. 44100 Hz -> 16000 Hz).
        val ratio = sampleRate.toDouble() / SAMPLE_RATE.toDouble()
        val outFrames = (inFrames / ratio).toInt().coerceAtLeast(1)
        val out = ByteArray(outFrames * 2)
        var outIdx = 0
        for (i in 0 until outFrames) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, inFrames - 1)
            val idx1 = (idx0 + 1).coerceAtMost(inFrames - 1)
            val frac = srcPos - idx0
            val sample = (mono[idx0] * (1.0 - frac) + mono[idx1] * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[outIdx++] = (sample and 0xff).toByte()
            out[outIdx++] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun shortsToLittleEndian(samples: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        var j = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[j++] = (v and 0xff).toByte()
            out[j++] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    /**
     * Ganancia automática suave para voz baja (un video con poco volumen se
     * reconoce peor). Solo amplifica (1×–8×), baja rápido ante picos para no
     * saturar y sube despacio; el ruido de fondo casi mudo no se amplifica.
     * Devuelve la ganancia usada para el siguiente bloque.
     */
    class Gain(private val target: Int = 16000, private val maxGain: Float = 8f, private val floor: Int = 120) {
        var gain = 1f
            private set

        fun apply(bytes: ByteArray, size: Int = bytes.size) {
            val peak = peak(bytes, size)
            if (peak > floor) {
                val wanted = (target.toFloat() / peak).coerceIn(1f, maxGain)
                // Baja al momento (evita recortes); sube un 15 % por bloque como mucho.
                gain = if (wanted < gain) wanted else minOf(wanted, gain * 1.15f)
            }
            if (gain <= 1.001f) return
            var i = 0
            while (i + 1 < size) {
                val sample = ((bytes[i].toInt() and 255) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                bytes[i] = (scaled and 255).toByte()
                bytes[i + 1] = ((scaled shr 8) and 255).toByte()
                i += 2
            }
        }
    }
}
