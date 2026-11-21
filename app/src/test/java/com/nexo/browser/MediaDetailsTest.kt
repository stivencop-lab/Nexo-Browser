package com.nexo.browser

import com.nexo.browser.MediaNotificationLayout.Button
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDetailsTest {
    private fun json(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun parsesMetadataAndBoundsValues() {
        val d = MediaDetails.fromJson(json(
            "artist" to "Artista‮\nuno", "album" to "x".repeat(500),
            "artwork" to "https://cdn.example/a.jpg", "duration" to 200.0, "position" to 250.0,
            "rate" to 99.0, "next" to true, "seek" to true, "video" to true
        ), nowMs = 1_000L)
        assertEquals("Artista uno", d.artist)
        assertEquals(200, d.album.length)
        assertEquals("https://cdn.example/a.jpg", d.artworkUrl)
        assertEquals(200.0, d.positionSec, 0.0)
        assertEquals(4f, d.rate)
        assertTrue(d.hasNext)
        assertFalse(d.hasPrevious)
        assertTrue(d.canSeek)
        assertTrue(d.isVideo)
    }

    @Test
    fun rejectsUnsafeArtworkAndSeekingLiveStreams() {
        listOf("http://a.example/x.jpg", "data:image/png;base64,AAA", "blob:https://a/1", "https://user:pw@a.example/x.jpg", "file:///sdcard/x.jpg", "javascript:alert(1)")
            .forEach { assertEquals(it, "", MediaDetails.artwork(it)) }
        val live = MediaDetails.fromJson(json("duration" to 0, "seek" to true, "position" to 30), 0L)
        assertFalse("sin duración no hay barra de progreso", live.canSeek)
        assertTrue(live.isLive)
        val broken = MediaDetails.fromJson(json("duration" to Double.MAX_VALUE, "rate" to -1), 0L)
        assertEquals(0.0, broken.durationSec, 0.0)
        assertEquals(1f, broken.rate)
    }

    @Test
    fun positionIsExtrapolatedOnlyWhilePlaying() {
        val d = MediaDetails(positionSec = 10.0, durationSec = 60.0, rate = 2f, reportedAtMs = 1_000L)
        assertEquals(16.0, d.positionAt(4_000L, playing = true), 0.001)
        assertEquals(10.0, d.positionAt(4_000L, playing = false), 0.001)
        assertEquals(60.0, d.positionAt(100_000L, playing = true), 0.001)
    }

    @Test
    fun privateTabsDropArtistAndArtwork() {
        val d = MediaDetails(artist = "A", album = "B", artworkUrl = "https://x/y.jpg", durationSec = 5.0).forPrivate()
        assertEquals("", d.artist)
        assertEquals("", d.artworkUrl)
        assertEquals(5.0, d.durationSec, 0.0)
    }

    @Test
    fun notificationButtonsFollowPageCapabilities() {
        val playlist = MediaNotificationLayout.layout(MediaDetails(hasNext = true, hasPrevious = true, durationSec = 100.0, canSeek = true), playing = true)
        assertEquals(listOf(Button.PREVIOUS, Button.PAUSE, Button.NEXT, Button.CLOSE), playlist.buttons)
        assertArrayEquals(intArrayOf(0, 1, 2), playlist.compact)

        val video = MediaNotificationLayout.layout(MediaDetails(durationSec = 100.0, canSeek = true), playing = false)
        assertEquals(listOf(Button.REWIND, Button.PLAY, Button.FORWARD, Button.CLOSE), video.buttons)

        val live = MediaNotificationLayout.layout(null, playing = true)
        assertEquals(listOf(Button.PAUSE, Button.CLOSE), live.buttons)
        assertArrayEquals(intArrayOf(0), live.compact)
    }

    @Test
    fun formatsTimes() {
        assertEquals("0:00", MediaNotificationLayout.formatTime(Double.NaN))
        assertEquals("1:05", MediaNotificationLayout.formatTime(65.9))
        assertEquals("1:02:03", MediaNotificationLayout.formatTime(3723.0))
    }
}
