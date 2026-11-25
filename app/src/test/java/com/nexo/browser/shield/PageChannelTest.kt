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
        assertFalse(request.wait)
        assertNull(request.waiterKey)
        val waitReq = channel.parse("""{"m":null,"s":4,"w":true,"k":"t_abc123"}""")!!
        assertNull(waitReq.message)
        assertEquals(4L, waitReq.since)
        assertTrue(waitReq.wait)
        assertEquals("t_abc123", waitReq.waiterKey)
        val invalidKey = channel.parse("""{"m":null,"s":0,"w":true,"k":"bad key!"}""")!!
        assertNull(invalidKey.waiterKey)
        assertNull(channel.parse("not json"))
        assertNull(channel.parse("x".repeat(PageChannel.MAX_REQUEST + 1)))
    }

    @Test fun newFrameReceivesEventsGeneratedDuringItsOwnInitialQuery() {
        val channel = PageChannel()
        channel.push("""{"event":"stale"}""")
        val before = channel.currentSeq()
        channel.push("""{"event":"browser_state"}""")
        channel.push("""{"event":"captions"}""")
        val envelope = channel.envelope("ok", -1, before)
        val json = JSONObject(envelope)
        assertEquals(3L, json.getLong("seq"))
        assertEquals("ok", json.getString("reply"))
        assertEquals(
            listOf("""{"event":"browser_state"}""", """{"event":"captions"}"""),
            events(envelope)
        )
    }

    @Test fun awaitEventsParksUntilPushAndDeliversImmediately() {
        val channel = PageChannel()
        channel.push("stale")
        // Initial handshake (since = -1) returns immediately with current seq without replaying stale events.
        var handshake: String? = null
        channel.awaitEvents(-1L, "t_main") { handshake = it }
        val handshakeJson = JSONObject(checkNotNull(handshake))
        assertEquals(1L, handshakeJson.getLong("seq"))
        assertFalse(handshakeJson.has("events"))
        assertEquals(0, channel.pendingWaiterCount())

        // Caught-up wait parks until push().
        val topReceived = mutableListOf<String>()
        val subReceived = mutableListOf<String>()
        channel.awaitEvents(1L, "t_main") { topReceived += it }
        channel.awaitEvents(1L, "f_sub1") { subReceived += it }
        assertEquals(2, channel.pendingWaiterCount())
        assertTrue(topReceived.isEmpty())
        assertTrue(subReceived.isEmpty())

        channel.push("live_event")
        assertEquals(0, channel.pendingWaiterCount())
        assertEquals(listOf("live_event"), events(topReceived.single()))
        assertEquals(listOf("live_event"), events(subReceived.single()))

        // Events pushed while frame was re-arming are delivered immediately on next awaitEvents().
        channel.push("missed_during_rearm")
        var caughtUp: String? = null
        channel.awaitEvents(2L, "t_main") { caughtUp = it }
        assertEquals(0, channel.pendingWaiterCount())
        assertEquals(listOf("missed_during_rearm"), events(caughtUp!!))
    }

    @Test fun waitersAreDeduplicatedByKeyBoundedAndClearedOnResetOrDispose() {
        val channel = PageChannel()
        val staleCallbacks = mutableListOf<String>()
        val latestCallbacks = mutableListOf<String>()
        channel.awaitEvents(0L, "f_same") { staleCallbacks += it }
        channel.awaitEvents(0L, "f_same") { latestCallbacks += it }
        assertEquals(1, channel.pendingWaiterCount())
        channel.push("ping")
        assertTrue(staleCallbacks.isEmpty())
        assertEquals(listOf("ping"), events(latestCallbacks.single()))

        // Bound prevents unbounded retention when Cefrium detaches iframes without onQueryCanceled.
        repeat(PageChannel.MAX_WAITERS + 10) { idx ->
            channel.awaitEvents(1L, "f_$idx") {}
        }
        assertEquals(PageChannel.MAX_WAITERS, channel.pendingWaiterCount())

        channel.reset()
        assertEquals(0, channel.pendingWaiterCount())

        channel.awaitEvents(1L, "t_main") {}
        assertEquals(1, channel.pendingWaiterCount())
        channel.dispose()
        assertEquals(0, channel.pendingWaiterCount())
    }

    @Test fun replyEnvelopeOmitsSeqSoAsyncRpcCannotSkipEvents() {
        val channel = PageChannel()
        channel.push("ev1")
        val json = JSONObject(channel.replyEnvelope("""{"id":7,"result":true}"""))
        assertEquals("""{"id":7,"result":true}""", json.getString("reply"))
        assertFalse(json.has("seq"))
        assertFalse(json.has("events"))
    }

    @Test fun maxRequestAccommodatesAudioChunks() {
        assertTrue(PageChannel.MAX_REQUEST >= 512 * 1024)
    }
}
