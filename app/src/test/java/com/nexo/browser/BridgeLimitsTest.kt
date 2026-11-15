package com.nexo.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BridgeLimitsTest {
    private fun message(type: String, key: String, value: Any) = JSONObject().put("type", type).put(key, value)
    private fun cue(id: Number = 0, text: Any = "Hola") =
        JSONObject().put("i", id).put("s", 0.0).put("e", 2.5).put("t", text)
    private fun cues(vararg values: Any) = message("captions_cues", "cues", JSONArray(values.toList()))

    @Test fun preservesFullCaptionBatchesAudioAndActiveIds() {
        val batch = JSONArray((0 until 200).map { cue(it, "x".repeat(300)) })
        assertTrue(BridgeLimits.accept(message("captions_cues", "cues", batch)))
        assertTrue(BridgeLimits.accept(message("captions_active", "ids", JSONArray((0 until 16).toList()))))
        assertTrue(BridgeLimits.accept(message("captions_audio", "data", "A".repeat(350_000))))
        assertTrue(BridgeLimits.accept(JSONObject("""{"type":"video_state","present":true,"playing":false,"width":1920,"height":1080,"rx":-20,"ry":0,"rw":1920,"rh":1080}""")))
    }

    @Test fun rejectsLargeOrNestedCuePayloadsEvenInsideASingleItem() {
        assertFalse(BridgeLimits.accept(cues(cue(text = "x".repeat(301)))))
        assertFalse(BridgeLimits.accept(cues(cue(text = JSONArray().put("x".repeat(100_000))))))
        assertFalse(BridgeLimits.accept(cues(cue().put("extra", JSONObject().put("data", "x".repeat(100_000))))))
        assertFalse(BridgeLimits.accept(cues(JSONArray().put(cue()))))
        assertFalse(BridgeLimits.accept(cues("x".repeat(100_000))))
    }

    @Test fun rejectsCueTypeConfusionMissingFieldsAndNonIntegerIds() {
        assertFalse(BridgeLimits.accept(cues(cue(0.5))))
        assertFalse(BridgeLimits.accept(cues(cue(-1))))
        assertFalse(BridgeLimits.accept(cues(cue(Int.MAX_VALUE.toLong() + 1))))
        assertFalse(BridgeLimits.accept(cues(cue().put("i", "0"))))
        assertFalse(BridgeLimits.accept(cues(cue().put("s", JSONObject()))))
        assertFalse(BridgeLimits.accept(cues(cue().put("e", "2.5"))))
        assertFalse(BridgeLimits.accept(cues(cue().apply { remove("t") })))
    }

    @Test fun activeIdsMustBeBoundedFlatIntegers() {
        for (value in listOf(-1, 0.5, Int.MAX_VALUE.toLong() + 1, "0", JSONObject(), JSONArray().put(0))) {
            assertFalse(BridgeLimits.accept(message("captions_active", "ids", JSONArray().put(value))))
        }
        assertFalse(BridgeLimits.accept(message("captions_active", "ids", JSONArray((0..16).toList()))))
        assertTrue(BridgeLimits.accept(message("captions_active", "ids", JSONArray())))
    }

    @Test fun unknownArraysAndOversizedKeysCannotBypassScalarLimits() {
        assertFalse(BridgeLimits.accept(message("video_state", "items", JSONArray().put("x".repeat(100_000)))))
        assertFalse(BridgeLimits.accept(message("captions_audio", "cues", JSONArray().put(cue()))))
        assertFalse(BridgeLimits.accept(message("captions_text", "x".repeat(2_001), "ok")))
        assertFalse(BridgeLimits.accept(JSONObject().put("type", 123)))
    }
}
