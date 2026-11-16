package com.nexo.browser.shield

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageChannelTest {
    private fun events(envelope: String): List<String> {
        val json = JSONObject(envelope)
        val list = json.optJSONArray("events") ?: return emptyList()
        return (0 until list.length()).map { list.getString(it) }
    }

    @Test fun newFramesOnlyLearnTheCurrentSequence() {
        val channel = PageChannel()
        channel.push("""{"event":"storage"}""")
        val envelope = JSONObject(channel.envelope(null, -1))
        assertEquals(1L, envelope.getLong("seq"))
        assertFalse("a frame that just started must not replay old events", envelope.has("events"))
    }

    @Test fun framesReceiveEachEventOnceAndInOrder() {
        val channel = PageChannel()
        val start = JSONObject(channel.envelope(null, -1)).getLong("seq")
        channel.push("a")
        channel.push("b")
        val first = channel.envelope("reply", start)
        assertEquals(listOf("a", "b"), events(first))
        assertEquals("reply", JSONObject(first).getString("reply"))
        val seq = JSONObject(first).getLong("seq")
        channel.push("c")
        assertEquals(listOf("c"), events(channel.envelope(null, seq)))
    }

    @Test fun theQueueIsBounded() {
        val channel = PageChannel()
        repeat(PageChannel.MAX_EVENTS + 10) { channel.push("e$it") }
        val delivered = events(channel.envelope(null, 0))
        assertEquals(PageChannel.MAX_EVENTS, delivered.size)
        assertEquals("e${PageChannel.MAX_EVENTS + 9}", delivered.last())
    }

    @Test fun pushWakesThePageAndConnectionFollowsQueries() {
        val channel = PageChannel()
        var wakes = 0
        channel.wake = { wakes++ }
        assertFalse(channel.connected)
        channel.push("x")
        assertEquals(1, wakes)
        channel.envelope(null, -1)
        assertTrue(channel.connected)
        channel.reset()
        assertFalse("a new document has no Nexo scripts until it asks", channel.connected)
    }

    @Test fun requestsAreParsedDefensively() {
        val channel = PageChannel()
        val request = channel.parse("""{"m":"{\"id\":1,\"kind\":\"get\"}","s":4}""")!!
        assertEquals("""{"id":1,"kind":"get"}""", request.message)
        assertEquals(4L, request.since)
        val poll = channel.parse("""{"m":null,"s":-1}""")!!
        assertNull(poll.message)
        assertNull(channel.parse("not json"))
        assertNull(channel.parse("x".repeat(PageChannel.MAX_REQUEST + 1)))
    }
}
