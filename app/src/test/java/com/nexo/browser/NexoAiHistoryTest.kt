package com.nexo.browser

import com.nexo.browser.ai.NexoAiClient
import com.nexo.browser.ai.NexoAiConfig
import com.nexo.browser.ai.NexoAiController
import com.nexo.browser.ai.NexoAiHistory
import com.nexo.browser.ai.NexoAiModels
import com.nexo.browser.ai.NexoAiModels.Role
import com.nexo.browser.ai.NexoAiStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Calendar
import java.util.TimeZone

class NexoAiHistoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val settings = NexoAiConfig.Settings(enabled = true, consentAccepted = true, provider = "openai", model = "gpt-4.1-mini")

    private fun msg(id: String, role: Role, text: String = id) = NexoAiModels.AiMessage(id = id, role = role, text = text, timestampMs = 0L)

    private fun conv(id: String, updated: Long, title: String = id, host: String = "", vararg messages: NexoAiModels.AiMessage) =
        NexoAiModels.AiConversation(id = id, title = title, createdAtMs = updated, updatedAtMs = updated, messages = messages.toList(), hostHint = host)

    private fun answering() = NexoAiController.Transport { _, _, task, _, _, _, _, _, _ ->
        Result.success(NexoAiClient.AiResponse("Respuesta a $task", null))
    }

    @Test
    fun deletingAQuestionTakesItsAnswerButNotTheNextTurn() {
        val messages = listOf(msg("u1", Role.USER), msg("a1", Role.ASSISTANT), msg("u2", Role.USER), msg("a2", Role.ASSISTANT))
        assertEquals(setOf("u1", "a1"), NexoAiHistory.messagesToDelete(messages, "u1"))
        assertEquals(setOf("a2"), NexoAiHistory.messagesToDelete(messages, "a2"))
        assertEquals(emptySet<String>(), NexoAiHistory.messagesToDelete(messages, "nope"))
    }

    @Test
    fun searchIgnoresCaseAndAccentsAndLooksInsideMessages() {
        val c = conv("c", 0L, "Resumen de noticias", "elpais.com", msg("u", Role.USER, "¿Qué dice la sección de economía?"))
        assertTrue(NexoAiHistory.matches(c, "RESUMEN"))
        assertTrue(NexoAiHistory.matches(c, "economia"))
        assertTrue(NexoAiHistory.matches(c, "elpais"))
        assertTrue(NexoAiHistory.matches(c, "  "))
        assertFalse(NexoAiHistory.matches(c, "deportes"))
    }

    @Test
    fun sectionsGroupByDayAndSkipEmptyThreads() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(2026, Calendar.NOVEMBER, 21, 15, 0, 0)
        val now = cal.timeInMillis
        val hour = 3600_000L
        val list = listOf(
            conv("today", now - hour, messages = arrayOf(msg("1", Role.USER))),
            conv("yesterday", now - 20 * hour, messages = arrayOf(msg("2", Role.USER))),
            conv("week", now - 4 * 24 * hour, messages = arrayOf(msg("3", Role.USER))),
            conv("old", now - 40 * 24 * hour, messages = arrayOf(msg("4", Role.USER))),
            conv("empty", now)
        )
        val sections = NexoAiHistory.sections(list, "", now)
        assertEquals(
            listOf(NexoAiHistory.Group.TODAY, NexoAiHistory.Group.YESTERDAY, NexoAiHistory.Group.WEEK, NexoAiHistory.Group.OLDER),
            sections.map { it.first }
        )
        assertFalse(sections.flatMap { it.second }.any { it.id == "empty" })
        assertEquals(NexoAiHistory.Group.TODAY, NexoAiHistory.group(now, now, Calendar.getInstance(TimeZone.getTimeZone("UTC"))))
    }

    @Test
    fun titlesAreCleanedAndBounded() {
        assertEquals("Hola mundo", NexoAiHistory.cleanTitle("  Hola‮\n  mundo "))
        assertEquals(60, NexoAiHistory.cleanTitle("x".repeat(200))!!.length)
        assertNull(NexoAiHistory.cleanTitle(" \n "))
    }

    @Test
    fun pendingContextKeepsItsConversationAndIsDiscardedAfterDeletion() {
        val file = temp.newFile("pending.json")
        NexoAiController.initializeForTest(file, testTransport = answering())
        val reserved = requireNotNull(NexoAiController.reserveSubmission())
        assertNull(NexoAiController.reserveSubmission())
        val other = NexoAiController.newConversation()
        assertTrue(NexoAiController.finishSubmission(reserved))
        NexoAiController.sendPrompt(settings, "sk-test", "Consulta original", "", conversationId = reserved.conversationId)
        val snapshot = NexoAiController.snapshot()
        assertEquals(other, snapshot.activeConversationId)
        assertTrue(snapshot.activeConversation!!.messages.isEmpty())
        assertEquals("Consulta original", snapshot.conversations.single { it.id == reserved.conversationId }.messages.first().text)

        val removed = requireNotNull(NexoAiController.reserveSubmission())
        NexoAiController.deleteConversation(removed.conversationId)
        assertFalse(NexoAiController.finishSubmission(removed))
        assertEquals("", NexoAiController.sendPrompt(settings, "sk-test", "Consulta eliminada", "", conversationId = removed.conversationId))
        assertFalse(file.readText().contains("Consulta eliminada"))
    }

    @Test
    fun controllerDeletesMessagesRenamesAndWipesEverythingOnDisk() {
        val file = temp.newFile("history.json")
        NexoAiController.initializeForTest(storageFile = file, testTransport = answering())
        NexoAiController.sendPrompt(settings, "sk-test", "Primera", "")
        NexoAiController.sendPrompt(settings, "sk-test", "Segunda", "")
        var active = NexoAiController.snapshot().activeConversation!!
        assertEquals(4, active.messages.size)

        val firstQuestion = active.messages.first { it.role == Role.USER }
        NexoAiController.deleteMessage(active.id, firstQuestion.id)
        active = NexoAiController.snapshot().activeConversation!!
        assertEquals(listOf("Segunda", "Respuesta a Segunda"), active.messages.map { it.text })

        NexoAiController.renameConversation(active.id, "  Mi hilo  ")
        assertEquals("Mi hilo", NexoAiController.snapshot().activeConversation!!.title)
        NexoAiController.renameConversation(active.id, "   ")
        assertEquals("Mi hilo", NexoAiController.snapshot().activeConversation!!.title)

        NexoAiController.newConversation()
        NexoAiController.sendPrompt(settings, "sk-test", "Otra", "")
        assertEquals(2, NexoAiController.snapshot().conversations.count { it.messages.isNotEmpty() })

        NexoAiController.deleteAllConversations()
        val after = NexoAiController.snapshot()
        assertEquals(1, after.conversations.size)
        assertTrue(after.conversations.single().messages.isEmpty())
        val onDisk = NexoAiStore(file).load()
        assertTrue("el archivo ya no guarda mensajes", onDisk.conversations.all { it.messages.isEmpty() })
        assertFalse(file.readText().contains("Segunda"))
    }
}
