package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexoTabsProTest {
    private val sampleTabs = listOf(
        TabProModel.TabSummary("1", "GitHub Repo", "https://github.com/stivencop414-bot/NavegadorGecko", "Dev", false, false),
        TabProModel.TabSummary("2", "GitHub Repo (copia)", "https://www.github.com/stivencop414-bot/NavegadorGecko/#readme", "Dev", false, false),
        TabProModel.TabSummary("3", "Documentación Cefrium", "https://cefrium.com/guides/public-api/", "Dev", false, true),
        TabProModel.TabSummary("4", "Inicio", "nexo://home", null, false, false),
        TabProModel.TabSummary("5", "Inicio 2", "nexo://home", null, false, false),
        TabProModel.TabSummary("6", "Privada", "https://github.com/stivencop414-bot/NavegadorGecko", null, true, false)
    )

    @Test
    fun canonicalUrlNormalizesHostTrailingSlashAndFragmentAndSkipsInternalPages() {
        assertEquals(
            TabProModel.canonicalUrl("https://github.com/stivencop414-bot/NavegadorGecko"),
            TabProModel.canonicalUrl("https://www.github.com/stivencop414-bot/NavegadorGecko/#section")
        )
        assertNull(TabProModel.canonicalUrl("nexo://home"))
        assertNull(TabProModel.canonicalUrl("about:blank"))
    }

    @Test
    fun detectsDuplicatesWithoutClosingAnyTabsAndIgnoresCrossMode() {
        val (organized, dupes) = TabProModel.organize(
            tabs = sampleTabs,
            showPrivate = false,
            selectedGroup = null,
            query = "",
            onlyDuplicates = false
        ) { it }
        // All 5 normal tabs remain intact — duplicates are NEVER auto-closed.
        assertEquals(5, organized.size)
        // Pinned tab "3" appears first.
        assertEquals("3", organized.first().id)
        // Tabs "1" and "2" are flagged as duplicate x2; "4"/"5" (nexo://home) are not.
        assertEquals(2, dupes["1"])
        assertEquals(2, dupes["2"])
        assertNull(dupes["4"])
        assertNull(dupes["5"])
        assertNull(dupes["6"])
    }

    @Test
    fun instantSearchAndOnlyDuplicatesFilterWorkTogether() {
        val (byQuery, _) = TabProModel.organize(sampleTabs, false, null, "documentacion", false) { it }
        assertEquals(listOf("3"), byQuery.map { it.id })

        val (onlyDupes, _) = TabProModel.organize(sampleTabs, false, null, "", true) { it }
        assertEquals(listOf("1", "2"), onlyDupes.map { it.id })
    }

    @Test
    fun prefetchPreviewsOnlyEnabledInGridMode() {
        assertTrue(TabProModel.shouldPrefetchPreviews(TabProModel.ViewMode.GRID))
        assertFalse(TabProModel.shouldPrefetchPreviews(TabProModel.ViewMode.COMPACT))
        assertFalse(TabProModel.shouldPrefetchPreviews(TabProModel.ViewMode.LIST))
        assertEquals(TabProModel.ViewMode.LIST, TabProModel.ViewMode.fromId("list"))
        assertEquals(TabProModel.ViewMode.GRID, TabProModel.ViewMode.fromId("unknown"))
    }

    @Test
    fun transferSchemaPreservesPinnedFlag() {
        val input = """[{"url":"https://example.org","title":"Ejemplo","pinned":true,"groupName":"Trabajo","desktopMode":false,"lastUsed":123}]"""
        val out = TransferSchema.tabs(input)
        assertTrue(out.contains("\"pinned\":true"))
    }
}
