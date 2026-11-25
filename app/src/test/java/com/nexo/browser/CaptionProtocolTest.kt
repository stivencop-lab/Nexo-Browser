package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionProtocolTest {
    @Test fun listenUrlUsesStreamingOptionsAndSafeLanguage() {
        val url = CaptionProtocol.listenUrl("multi")
        assertTrue(url.startsWith("wss://api.deepgram.com/v1/listen?model=nova-3&language=multi"))
        assertTrue(url.contains("interim_results=true"))
        assertTrue(CaptionProtocol.listenUrl("es").contains("model=nova-2&language=es"))
        assertTrue(CaptionProtocol.listenUrl("es&x=1").contains("language=multi"))
    }

    @Test fun parsesInterimAndFinalResults() {
        val interim = CaptionProtocol.parseResult(
            """{"type":"Results","is_final":false,"speech_final":false,"channel":{"alternatives":[{"transcript":"hola a"}]}}""")
        assertEquals("hola a", interim?.text)
        assertFalse(interim!!.isFinal)
        val final = CaptionProtocol.parseResult(
            """{"type":"Results","is_final":true,"speech_final":true,"channel":{"alternatives":[{"transcript":" Hola a todos. "}]}}""")
        assertEquals("Hola a todos.", final?.text)
        assertTrue(final!!.isFinal)
        assertNull(CaptionProtocol.parseResult("""{"type":"Metadata"}"""))
        assertTrue(CaptionProtocol.isUtteranceEnd("""{"type":"UtteranceEnd","last_word_end":2.1}"""))
    }

    @Test fun parsesTranslators() {
        assertEquals("Hola mundo. Adiós.", CaptionProtocol.parseGoogle("""[[["Hola mundo. ","Hello world. ",null],["Adiós.","Bye.",null]],null,"en"]"""))
        assertNull(CaptionProtocol.parseGoogle("<html>"))
        assertEquals("Hola", CaptionProtocol.parseDeepl("""{"translations":[{"detected_source_language":"EN","text":"Hola"}]}"""))
        assertEquals("https://api-free.deepl.com/v2/translate", CaptionProtocol.deeplEndpoint("abc:fx"))
        assertEquals("EN-US", CaptionProtocol.deeplTarget("en"))
        assertTrue(CaptionProtocol.googleUrl("a b", "es").endsWith("tl=es&dt=t&q=a+b"))
    }

    @Test fun captionTextKeepsRecentLinesAndPrefersTranslations() {
        val text = CaptionText(maxChars = 40)
        val first = text.onTranscript(CaptionProtocol.Transcript("Hello everyone.", isFinal = true, speechFinal = true))!!
        text.onTranscript(CaptionProtocol.Transcript("Today we", isFinal = false, speechFinal = false))
        assertEquals("" to "Hello everyone. Today we", text.display(translating = true))
        text.setTranslation(first.id, "Hola a todos.")
        assertEquals("Hola a todos." to "Today we", text.display(translating = true))
        assertEquals("Hello everyone." to "Today we", text.display(translating = false))
        repeat(5) { text.onTranscript(CaptionProtocol.Transcript("Sentence number $it here.", isFinal = true, speechFinal = true)) }
        val (done, pending) = text.display(translating = false)
        assertTrue(done.length + pending.length <= 41)
        assertTrue(done.endsWith("number 4 here.") || pending.endsWith("number 4 here."))
    }

    @Test fun batchesTranslationsWithOneRequestInFlight() {
        val batcher = TranslationBatcher(delayMs = 1000, maxChars = 30, maxLines = 3)
        batcher.add(TranslationBatcher.Item(1, "Hello."), now = 0)
        assertEquals(1000L, batcher.dueAt())
        assertNull(batcher.take(500))
        batcher.add(TranslationBatcher.Item(2, "How are you?"), now = 400)
        val first = batcher.take(1000)!!
        assertEquals(listOf(1L, 2L), first.map { it.id })
        batcher.add(TranslationBatcher.Item(3, "Fine."), now = 1100)
        assertNull("only one request at a time", batcher.dueAt())
        batcher.done()
        // Too much text waiting: send right away, without exceeding the size of a batch.
        batcher.add(TranslationBatcher.Item(4, "A rather long subtitle line."), now = 1200)
        assertEquals(0L, batcher.dueAt())
        assertEquals(listOf(3L), batcher.take(1200)!!.map { it.id })
    }

    @Test fun batchFormatsKeepLineOrder() {
        assertEquals(listOf("Hola.", "¿Cómo estás?"),
            CaptionProtocol.parseGoogleLines("""[[["Hola.\n","Hello.\n",null],["¿Cómo estás?","How are you?",null]],null,"en"]""", 2))
        assertNull(CaptionProtocol.parseGoogleLines("""[[["Hola. ¿Cómo estás?","x",null]],null,"en"]""", 2))
        assertTrue(CaptionProtocol.deeplBatchBody(listOf("a", "b"), "es").startsWith("text=a&text=b&target_lang=ES"))
        assertEquals(listOf("x", "y"), CaptionProtocol.parseDeeplLines("""{"translations":[{"text":"x"},{"text":"y"}]}""", 2))
    }

    @Test fun aiTranslationRequestsAndParsing() {
        val openai = CaptionProtocol.aiRequest("openai", "", "", "k", "Spanish", listOf("Hi", "Bye"), listOf("Before"))
        assertEquals("https://api.openai.com/v1/chat/completions", openai.url)
        assertEquals("Bearer k", openai.headers["Authorization"])
        assertTrue(openai.body.contains("gpt-4.1-mini"))
        val user = org.json.JSONObject(openai.body).getJSONArray("messages").getJSONObject(1).getString("content")
        assertEquals("[\"Hi\",\"Bye\"]", org.json.JSONObject(user).getJSONArray("lines").toString())
        assertEquals("[\"Before\"]", org.json.JSONObject(user).getJSONArray("context").toString())
        val claude = CaptionProtocol.aiRequest("claude", "", "claude-haiku-5-5", "k", "Spanish", listOf("Hi"), emptyList())
        assertEquals("https://api.anthropic.com/v1/messages", claude.url)
        assertEquals("k", claude.headers["x-api-key"])
        assertEquals(listOf("Hola", "Adiós"), CaptionProtocol.parseAiLines(
            """{"choices":[{"message":{"content":"```json\n{\"t\":[\"Hola\",\"Adiós\"]}\n```"}}]}""", 2))
        assertEquals(listOf("Hola"), CaptionProtocol.parseAiLines(
            """{"content":[{"type":"text","text":"{\"t\":[\"Hola\"]}"}]}""", 1))
        assertNull("a wrong count never misaligns subtitles", CaptionProtocol.parseAiLines(
            """{"content":[{"type":"text","text":"{\"t\":[\"Hola\",\"extra\"]}"}]}""", 1))
    }

    @Test fun bothModeShowsOriginalUnderTranslation() {
        val text = CaptionText()
        val segment = text.onTranscript(CaptionProtocol.Transcript("Good morning.", isFinal = true, speechFinal = true))!!
        text.setTranslation(segment.id, "Buenos días.")
        assertEquals(CaptionDisplay("Buenos días.", "", "Good morning."), text.view("both", translating = true))
        assertEquals(CaptionDisplay("Buenos días.", "", ""), text.view("translated", translating = true))
        assertEquals(listOf("Good morning."), text.context())
    }

    @Test fun speculativeInterimAndProvisionalTranslations() {
        val text = CaptionText()
        text.onTranscript(CaptionProtocol.Transcript("I think that we should", isFinal = false, speechFinal = false))
        text.setInterimTranslation("I think that we should", "Creo que deberíamos")
        text.onTranscript(CaptionProtocol.Transcript("I think that we should go now", isFinal = false, speechFinal = false))
        assertEquals("Creo que deberíamos go now", text.view("translated", translating = true).pending)
        val segment = text.onTranscript(CaptionProtocol.Transcript("I think that we should go now.", isFinal = true, speechFinal = true))!!
        text.setTranslation(segment.id, "Creo que deberíamos irnos ya.", provisional = true)
        assertEquals("Creo que deberíamos irnos ya.", text.view("translated", translating = true).primary)
        text.setTranslation(segment.id, "Creo que deberíamos irnos ahora.")
        text.setTranslation(segment.id, "respaldo tardío", provisional = true)
        assertEquals("a late fallback never replaces the final translation",
            "Creo que deberíamos irnos ahora.", text.view("translated", translating = true).primary)
        assertEquals(1, text.resetTranslations().size)
        assertEquals("I think that we should go now.", text.view("translated", translating = true).pending)
    }

    @Test fun languageCodesPerService() {
        assertEquals("zh-CN", CaptionProtocol.googleTarget("zh"))
        assertEquals("iw", CaptionProtocol.googleTarget("he"))
        assertTrue(CaptionProtocol.deeplSupports("es"))
        assertFalse(CaptionProtocol.deeplSupports("hi"))
        assertEquals("ZH-HANT", CaptionProtocol.deeplTarget("zh-TW"))
        assertEquals(37, CaptionProtocol.TARGET_LANGUAGES.size)
        assertEquals("Japanese", CaptionProtocol.LANGUAGE_NAMES["ja"])
        assertFalse(CaptionProtocol.listenUrl("multi").contains("smart_format"))
    }

    private fun texts(list: List<CaptionProtocol.Transcript>) = list.map { (if (it.isFinal) "F:" else "I:") + it.text }

    @Test fun domTrackerTurnsGrowingLinesIntoInterimText() {
        val tracker = DomCaptionTracker()
        assertEquals(listOf("I:Hello"), texts(tracker.update("Hello")))
        assertEquals(listOf("I:Hello there"), texts(tracker.update("Hello  there")))
        assertTrue(tracker.update("Hello there").isEmpty())
        assertEquals("Hello there", tracker.settle()?.text)
        assertNull(tracker.settle())
        // Tras darlo por terminado solo se envía lo nuevo.
        assertEquals(listOf("I:friend"), texts(tracker.update("Hello there friend")))
    }

    @Test fun domTrackerDoesNotRepeatLinesThatScrollUp() {
        val tracker = DomCaptionTracker()
        tracker.update("line one text line two text")
        assertEquals(
            listOf("F:line one text line two text", "I:line three"),
            texts(tracker.update("line two text line three"))
        )
        assertEquals(listOf("F:line three"), texts(tracker.update("")))
    }

    @Test fun domTrackerReplacesWholeSentences() {
        val tracker = DomCaptionTracker()
        tracker.update("Where are you going?")
        assertEquals(listOf("F:Where are you going?", "I:Home."), texts(tracker.update("Home.")))
    }

    @Test fun domOverlapRespectsWordBoundaries() {
        assertEquals(9, DomCaptionTracker.overlap("we went home", "went home now"))
        assertEquals(0, DomCaptionTracker.overlap("the cathedral", "dral is old"))
        assertEquals(0, DomCaptionTracker.overlap("abc", "xyz"))
    }

    @Test fun nova2FallbackRefusesMultiLanguageAndSingleRetry() {
        assertFalse("nova-2 rejects language=multi with HTTP 400",
            CaptionProtocol.shouldFallbackToNova2(400, "nova-3", "multi", alreadyTried = false))
        assertFalse(CaptionProtocol.shouldFallbackToNova2(403, "nova-3", "multi", alreadyTried = false))
        assertTrue(CaptionProtocol.shouldFallbackToNova2(400, "nova-3", "en", alreadyTried = false))
        assertTrue(CaptionProtocol.shouldFallbackToNova2(400, "nova-3", "es", alreadyTried = false))
        assertFalse("HTTP 401 is an auth error and must not retry with nova-2",
            CaptionProtocol.shouldFallbackToNova2(401, "nova-3", "es", alreadyTried = false))
        assertFalse(CaptionProtocol.shouldFallbackToNova2(400, "nova-3", "en", alreadyTried = true))
        assertFalse(CaptionProtocol.shouldFallbackToNova2(400, "nova-2", "en", alreadyTried = false))
        assertFalse(CaptionProtocol.shouldFallbackToNova2(500, "nova-3", "en", alreadyTried = false))
    }

    @Test fun audioChunkRateLimiterAllowsInitialWebmHeaderBurst() {
        val limiter = AudioChunkRateLimiter(burstCapacity = 12, refillIntervalMs = 40L)
        repeat(12) { idx ->
            assertTrue("initial header/cluster chunk #$idx must not be dropped", limiter.tryAcquire(1000L))
        }
        assertFalse("13th instantaneous chunk exceeds burst cap", limiter.tryAcquire(1000L))
        assertFalse("no refill before 40ms", limiter.tryAcquire(1020L))
        assertTrue("refills 1 token after 40ms", limiter.tryAcquire(1040L))
        assertFalse("spent refilled token", limiter.tryAcquire(1040L))
        limiter.reset()
        assertTrue("reset restores burst capacity", limiter.tryAcquire(2000L))
    }

    @Test fun silenceDoesNotRenewReconnectBudget() {
        val budget = CaptionReconnectBudget()
        repeat(3) { attempt ->
            assertEquals(attempt, budget.next(3))
            budget.onTranscript("  ")
        }
        assertNull(budget.next(3))
        budget.onTranscript("Hola")
        assertEquals(0, budget.next(3))
    }

    @Test fun speakerDetectionIsAnOptInForBothAudioSources() {
        assertFalse(CaptionProtocol.listenUrl("es").contains("diarize_model"))
        assertTrue(CaptionProtocol.listenUrl("multi", separateSpeakers = true).contains("&diarize_model=latest"))
        val pcm = PlaybackPcm.listenUrl("es", "nova-2", separateSpeakers = true)
        assertTrue(pcm.contains("&diarize_model=latest&encoding=linear16"))
    }

    @Test fun confirmedWordsSeparateSpeakersWithoutSendingMarkersToTheTranslator() {
        val transcript = CaptionProtocol.parseResult("""
            {"type":"Results","start":0,"duration":2,"is_final":true,"speech_final":true,
             "channel":{"alternatives":[{"transcript":"Hola. ¿Qué tal?","words":[
               {"word":"hola","punctuated_word":"Hola.","start":0.05,"end":0.5,"speaker":0},
               {"word":"qué","punctuated_word":"¿Qué","start":0.55,"end":0.8,"speaker":1},
               {"word":"tal","punctuated_word":"tal?","start":0.8,"end":1.1,"speaker":1}
             ]}]}}
        """.trimIndent())!!
        val text = CaptionText()
        text.onSpeechTranscript(transcript.copy(isFinal = false))
        assertEquals("Hola. ¿Qué tal?", text.view("original", translating = false).pending)
        assertEquals(listOf(0, 1, 1), transcript.words.map { it.speaker })
        val (a, b) = text.onSpeechTranscript(transcript)
        assertEquals("Hola.", a.original)
        assertEquals("¿Qué tal?", b.original)
        assertEquals("Hola.\n— ¿Qué tal?", text.view("original", translating = false).primary)
        text.setTranslation(a.id, "Hello.")
        text.setTranslation(b.id, "How are you?")
        assertEquals(CaptionDisplay("Hello.\n— How are you?", "", "Hola.\n— ¿Qué tal?"),
            text.view("both", translating = true))
    }

    @Test fun aShortEndpointDoesNotBreakTheLineButALongGapInsideAResultDoes() {
        val text = CaptionText()
        text.onSpeechTranscript(CaptionProtocol.Transcript("Hola", true, true, listOf(
            CaptionProtocol.Word("Hola", 0.0, 0.2, 0)
        ), 0.45))
        text.onSpeechTranscript(CaptionProtocol.Transcript("amiga. Bienvenida.", true, false, listOf(
            CaptionProtocol.Word("amiga.", 0.5, 0.8, 0),
            CaptionProtocol.Word("Bienvenida.", 1.5, 1.8, 0)
        ), 1.9))
        assertEquals("Hola amiga.\nBienvenida.", text.view("original", translating = false).primary)
    }

    @Test fun recognizesAnUtteranceEndOnlyForTheLatestFinishedWords() {
        val text = CaptionText()
        text.onSpeechTranscript(CaptionProtocol.Transcript("Uno.", true, false, listOf(
            CaptionProtocol.Word("Uno.", 0.4, 0.9, null)
        ), 0.9))
        text.onUtteranceEnd(0.2)
        text.onSpeechTranscript(CaptionProtocol.Transcript("Dos.", true, false, listOf(
            CaptionProtocol.Word("Dos.", 1.1, 1.3, null)
        ), 1.3))
        assertEquals("Uno. Dos.", text.view("original", translating = false).primary)
        text.onUtteranceEnd(1.3)
        text.onSpeechTranscript(CaptionProtocol.Transcript("Tres.", true, false))
        assertEquals("Uno. Dos.\nTres.", text.view("original", translating = false).primary)
        assertNull(CaptionProtocol.parseMessage("""{"type":"UtteranceEnd","last_word_end":-1}"""))
    }

    @Test fun thePartOfAnInterimAfterTheFinalResultRemainsVisible() {
        val text = CaptionText()
        val first = CaptionProtocol.Word("Hola", 0.1, 0.4, null)
        val second = CaptionProtocol.Word("mundo", 0.9, 1.2, null)
        text.onSpeechTranscript(CaptionProtocol.Transcript("Hola mundo", false, false, listOf(first, second), 1.3))
        text.onSpeechTranscript(CaptionProtocol.Transcript("", false, false, end = 1.35))
        assertEquals("Hola mundo", text.interimText)
        text.onSpeechTranscript(CaptionProtocol.Transcript("Hola", true, true, listOf(first), 0.7))
        assertEquals(CaptionDisplay("Hola", "mundo"), text.view("original", translating = false))
        text.onSpeechTranscript(CaptionProtocol.Transcript("mundo", true, true, listOf(second), 1.3))
        assertEquals(CaptionDisplay("Hola\nmundo", ""), text.view("original", translating = false))
    }

    @Test fun resetOfTheStreamClockDoesNotCompareSpeakerIdsAcrossConnections() {
        val text = CaptionText()
        text.onSpeechTranscript(CaptionProtocol.Transcript("Antes.", true, false, listOf(
            CaptionProtocol.Word("Antes.", 40.0, 41.0, 2)
        ), 41.0))
        text.resetSpeechClock()
        text.onSpeechTranscript(CaptionProtocol.Transcript("Después.", true, false, listOf(
            CaptionProtocol.Word("Después.", 0.0, 0.6, 0)
        ), 0.6))
        assertEquals("Antes.\nDespués.", text.view("original", translating = false).primary)
    }

    @Test fun captionTextHasTranslationTracksProvisionalAndFinal() {
        val text = CaptionText()
        val seg = text.onTranscript(CaptionProtocol.Transcript("Hello world", isFinal = true, speechFinal = true))!!
        assertFalse(text.hasTranslation(seg.id))
        text.setTranslation(seg.id, "Hola mundo", provisional = true)
        assertTrue(text.hasTranslation(seg.id))
        text.resetTranslations()
        assertFalse(text.hasTranslation(seg.id))
    }
}
