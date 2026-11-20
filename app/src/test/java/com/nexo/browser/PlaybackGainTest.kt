package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGainTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, v -> bytes[i * 2] = (v and 255).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 255).toByte() }
    }

    @Test fun quietSpeechIsRaisedGraduallyWithoutClipping() {
        val gain = PlaybackPcm.Gain()
        val block = pcm(1000, -1000, 500)
        gain.apply(block)
        // Sube como mucho un 15 % por bloque.
        assertEquals(1150.0, PlaybackPcm.peak(block).toDouble(), 2.0)
        repeat(30) { gain.apply(pcm(1000, -1000)) }
        assertEquals(8f, gain.gain, 0.01f)
        val loud = pcm(20000, -20000)
        gain.apply(loud)
        assertTrue("una voz fuerte baja la ganancia al momento", gain.gain <= 1f)
        assertTrue(PlaybackPcm.peak(loud) <= Short.MAX_VALUE)
    }

    @Test fun backgroundNoiseIsNotAmplified() {
        val gain = PlaybackPcm.Gain()
        val noise = pcm(50, -60, 40)
        gain.apply(noise)
        assertEquals(60, PlaybackPcm.peak(noise))
        assertEquals(1f, gain.gain, 0f)
    }

    @Test fun hasWeakSignalDetectsLowVolumeAudioAboveFloor() {
        assertFalse(PlaybackPcm.hasWeakSignal(pcm(0, 12, -24)))
        assertTrue(PlaybackPcm.hasWeakSignal(pcm(0, 25, -10)))
        assertTrue(PlaybackPcm.hasWeakSignal(pcm(50, -60, 40)))
    }

    @Test fun downsamples48kStereoAnd44k1StereoTo16kMono() {
        // 48 kHz stereo: 6 stereo frames (12 samples, 24 bytes) -> 2 mono 16 kHz samples (4 bytes)
        val stereo48 = pcm(
            3000, 3000, 3000, 3000, 3000, 3000,
            -6000, -6000, -6000, -6000, -6000, -6000
        )
        val out48 = PlaybackPcm.to16kMono(stereo48, stereo48.size, sampleRate = 48000, channels = 2)
        assertEquals(4, out48.size)
        val s0 = (out48[0].toInt() and 0xFF) or (out48[1].toInt() shl 8)
        val s1 = (out48[2].toInt() and 0xFF) or (out48[3].toInt() shl 8)
        assertEquals(3000, s0)
        assertEquals(-6000, s1)

        // 44.1 kHz stereo: 441 frames (10 ms) -> ~160 mono 16 kHz samples (320 bytes)
        val frames441 = IntArray(441 * 2) { if (it % 2 == 0) 4000 else 2000 }
        val stereo441 = pcm(*frames441)
        val out441 = PlaybackPcm.to16kMono(stereo441, stereo441.size, sampleRate = 44100, channels = 2)
        assertEquals(160 * 2, out441.size)
        assertEquals(3000, PlaybackPcm.peak(out441))

        // 16 kHz mono passthrough
        val mono16 = pcm(1234, -5678, 910)
        val out16 = PlaybackPcm.to16kMono(mono16, mono16.size, sampleRate = 16000, channels = 1)
        assertEquals(mono16.size, out16.size)
        assertEquals(5678, PlaybackPcm.peak(out16))
    }
}
