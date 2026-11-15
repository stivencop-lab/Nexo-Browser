package com.nexo.browser.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionMessagesTest {
    private val locales = mapOf(
        "es" to """
            {
              "greeting": { "message": "Hola, §USER§. Tienes §COUNT§ avisos (§§ ahorro).",
                "placeholders": { "user": { "content": "§1" }, "count": { "content": "§2" } } },
              "Title": { "message": "Título en español" }
            }
        """.trimIndent().replace('§', '$'),
        "en" to """{ "title": { "message": "English title" }, "only_en": { "message": "Only $1" } }""",
        "es_419" to """{ "title": { "message": "Título latino" } }"""
    )

    private fun messages(ui: String, default: String? = "en") =
        ExtensionMessages.load("abcdefghijklmnopabcdefghijklmnop", default, ui) { folder -> locales[folder] }

    @Test fun resolvesPlaceholdersAndNumberedSubstitutions() {
        assertEquals(
            "Hola, Ana. Tienes 3 avisos ($ ahorro).",
            messages("es").get("greeting", listOf("Ana", "3"))
        )
        assertEquals("Hola, . Tienes  avisos ($ ahorro).", messages("es").get("GREETING"))
    }

    @Test fun followsChromeLocaleFallback() {
        assertEquals("Título latino", messages("es-419").get("title"))
        assertEquals("Título en español", messages("es-ES").get("title"))
        assertEquals("Only x", messages("es-ES").get("only_en", listOf("x")))
        assertEquals("English title", messages("fr").get("title"))
        assertNull(messages("fr", default = null).get("title"))
        assertEquals(listOf("pt_BR", "pt", "en"), ExtensionMessages.candidateFolders("pt-BR", "en"))
        assertEquals(listOf("en"), ExtensionMessages.candidateFolders("../x", "en"))
    }

    @Test fun predefinedMessagesAndManifestLocalization() {
        val m = messages("ar")
        assertEquals("abcdefghijklmnopabcdefghijklmnop", m.get("@@extension_id"))
        assertEquals("rtl", m.get("@@bidi_dir"))
        assertEquals("ltr", messages("es").get("@@bidi_dir"))
        assertEquals("English title — v2", messages("fr").localize("__MSG_title__ — v2"))
        assertEquals("[]", messages("fr").localize("[__MSG_missing__]"))
    }
}
