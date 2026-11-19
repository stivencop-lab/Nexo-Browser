package com.nexo.browser

import com.nexo.browser.reader.NexoReader
import com.nexo.browser.reader.OfflineArticleStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NexoReaderTest {
    @get:Rule val folder = TemporaryFolder()

    private fun sampleArticle(
        url: String,
        title: String,
        savedAtMs: Long = 1000L,
        bodyPadding: String = ""
    ): OfflineArticleStore.ReaderArticle = OfflineArticleStore.ReaderArticle(
        id = OfflineArticleStore.idForUrl(url),
        url = url,
        title = title,
        byline = "Equipo Nexo",
        siteName = "example.org",
        lang = "es",
        wordCount = 240,
        readingMinutes = 2,
        savedAtMs = savedAtMs,
        blocks = listOf(
            OfflineArticleStore.ReaderBlock("p", "Primer párrafo del artículo de prueba. $bodyPadding"),
            OfflineArticleStore.ReaderBlock("h2", "Sección técnica"),
            OfflineArticleStore.ReaderBlock(
                "quote",
                "Cita verificada.",
                listOf(OfflineArticleStore.ReaderLink("Enlace seguro", "https://example.org/ref"))
            )
        )
    )

    @Test fun parsesExtractorJsonAndDropsUnsafeLinks() {
        val raw = JSONObject().apply {
            put("ok", true)
            put("title", "Arquitectura de Nexo Reader")
            put("byline", "Autora")
            put("siteName", "nexo.example")
            put("lang", "es")
            put("url", "https://nexo.example/post?token=secret&id=4")
            put("wordCount", 420)
            put("readingMinutes", 2)
            put("blocks", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "p")
                    put("text", "Contenido saneado para lectura nativa.")
                    put("links", JSONArray().apply {
                        put(JSONObject().put("label", "Ok").put("href", "https://nexo.example/docs"))
                        put(JSONObject().put("label", "XSS").put("href", "javascript:alert(1)"))
                        put(JSONObject().put("label", "Data").put("href", "data:text/html,<script>1</script>"))
                    })
                })
            })
        }

        val parsed = OfflineArticleStore.parseFromExtractorJson(raw, "https://fallback.example/")
        assertNotNull(parsed)
        assertEquals("Arquitectura de Nexo Reader", parsed!!.title)
        assertFalse(parsed.url.contains("token=secret"))
        assertEquals(1, parsed.blocks.size)
        assertEquals(1, parsed.blocks[0].links.size)
        assertEquals("https://nexo.example/docs", parsed.blocks[0].links[0].href)

        val rejected = OfflineArticleStore.parseFromExtractorJson(JSONObject().put("ok", false), "https://nexo.example/")
        assertNull(rejected)
    }

    @Test fun savesListsDeduplicatesAndDeletesOfflineArticles() {
        val dir = folder.newFolder("reader-store")
        val a1 = sampleArticle("https://example.org/a", "Artículo A", savedAtMs = 100L)
        val a2 = sampleArticle("https://example.org/b", "Artículo B", savedAtMs = 200L)

        assertTrue(OfflineArticleStore.save(dir, a1))
        assertTrue(OfflineArticleStore.save(dir, a2))

        val listed = OfflineArticleStore.list(dir)
        assertEquals(listOf("Artículo B", "Artículo A"), listed.map { it.title })

        // Re-guardar misma URL actualiza sin duplicar
        assertTrue(OfflineArticleStore.save(dir, a1.copy(title = "Artículo A v2", savedAtMs = 300L)))
        val updated = OfflineArticleStore.list(dir)
        assertEquals(2, updated.size)
        assertEquals("Artículo A v2", updated.first().title)

        assertNotNull(OfflineArticleStore.findByUrl(dir, "https://example.org/a"))
        assertTrue(OfflineArticleStore.delete(dir, a1.id))
        assertEquals(1, OfflineArticleStore.list(dir).size)
        OfflineArticleStore.clear(dir)
        assertTrue(OfflineArticleStore.list(dir).isEmpty())
    }

    @Test fun enforcesMaxCountAndMaxBytesQuotasDeterministically() {
        val dirCount = folder.newFolder("reader-quota-count")
        for (i in 1..12) {
            assertTrue(
                OfflineArticleStore.save(
                    dir = dirCount,
                    article = sampleArticle("https://example.org/$i", "Art $i", savedAtMs = i * 1000L),
                    maxArticles = 5,
                    maxTotalBytes = OfflineArticleStore.MAX_TOTAL_BYTES
                )
            )
        }
        val remaining = OfflineArticleStore.list(dirCount)
        assertEquals(5, remaining.size)
        // Deben conservarse los 5 más recientes (12..8)
        assertEquals(listOf("Art 12", "Art 11", "Art 10", "Art 9", "Art 8"), remaining.map { it.title })

        val dirBytes = folder.newFolder("reader-quota-bytes")
        val pad = "x".repeat(1500)
        for (i in 1..6) {
            assertTrue(
                OfflineArticleStore.save(
                    dir = dirBytes,
                    article = sampleArticle("https://example.org/big/$i", "Big $i", savedAtMs = i * 1000L, bodyPadding = pad),
                    maxArticles = 50,
                    maxTotalBytes = 4200L
                )
            )
        }
        assertTrue(OfflineArticleStore.totalBytes(dirBytes) <= 4200L)
        assertTrue(OfflineArticleStore.list(dirBytes).first().title == "Big 6")
    }

    @Test fun clampsTypographyAndSpeechRatePrefsSafely() {
        assertEquals(14, NexoReader.ReaderPrefs.clampFontSize(6))
        assertEquals(26, NexoReader.ReaderPrefs.clampFontSize(48))
        assertEquals(1.2f, NexoReader.ReaderPrefs.clampLineSpacing(0.5f), 0.001f)
        assertEquals(1.8f, NexoReader.ReaderPrefs.clampLineSpacing(3.0f), 0.001f)
        assertEquals(0.75f, NexoReader.ReaderPrefs.clampTtsRate(0.2f), 0.001f)
        assertEquals(2.0f, NexoReader.ReaderPrefs.clampTtsRate(5.0f), 0.001f)
    }
}
