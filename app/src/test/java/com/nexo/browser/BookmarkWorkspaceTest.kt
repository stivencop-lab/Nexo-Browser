package com.nexo.browser

import com.nexo.browser.workspace.BookmarkStore
import com.nexo.browser.workspace.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookmarkWorkspaceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun bookmarkCrudSearchAndToggleWork() {
        val file = File(tmp.root, "bookmarks.json")

        val added = BookmarkStore.add(file, "Guía Técnica", "https://example.org/guia", "Estudio", nowMs = 1000L)
        assertNotNull(added)
        BookmarkStore.add(file, "Noticias", "https://news.example.com/", "Lectura", nowMs = 2000L)

        // Misma URL actualiza en lugar de duplicar.
        val updated = BookmarkStore.add(file, "Guía Técnica v2", "https://example.org/guia/", "Estudio", nowMs = 3000L)
        assertNotNull(updated)
        assertEquals(2, BookmarkStore.list(file).size)
        assertEquals(listOf("General", "Lectura", "Estudio"), BookmarkStore.folders(file))

        // Búsqueda insensible a tildes y por carpeta.
        val hits = BookmarkStore.search(file, "tecnica", folder = "Estudio")
        assertEquals(1, hits.size)
        assertEquals("Guía Técnica v2", hits.first().title)

        // Actualización explícita por ID.
        assertTrue(BookmarkStore.update(file, updated!!.id, "Documentación", "https://example.org/docs", "Trabajo"))
        assertEquals("Trabajo", BookmarkStore.findByUrl(file, "https://example.org/docs")?.folder)

        // Alternar quita si ya existe y vuelve a añadir después.
        assertFalse(BookmarkStore.toggle(file, "Documentación", "https://example.org/docs"))
        assertNull(BookmarkStore.findByUrl(file, "https://example.org/docs"))
        assertTrue(BookmarkStore.toggle(file, "Documentación", "https://example.org/docs"))
        assertNotNull(BookmarkStore.findByUrl(file, "https://example.org/docs"))

        // Borrado.
        assertTrue(BookmarkStore.delete(file, BookmarkStore.findByUrl(file, "https://example.org/docs")!!.id))
        assertEquals(1, BookmarkStore.list(file).size)
    }

    @Test
    fun bookmarkStoreRejectsUnsafeSchemesAndEscapesHtml() {
        val file = File(tmp.root, "safe_bookmarks.json")

        assertNull(BookmarkStore.add(file, "XSS", "javascript:alert(1)"))
        assertNull(BookmarkStore.add(file, "Data", "data:text/html,<script>alert(1)</script>"))
        assertNull(BookmarkStore.add(file, "File", "file:///etc/passwd"))
        assertEquals(0, BookmarkStore.list(file).size)

        BookmarkStore.add(file, "A & B <test>", "https://safe.example.org/a?x=1&y=2", "Dev & Ops", nowMs = 5000L)
        val html = BookmarkStore.exportNetscapeHtml(BookmarkStore.list(file))
        assertTrue(html.contains("A &amp; B &lt;test&gt;"))
        assertTrue(html.contains("Dev &amp; Ops"))
        assertFalse(html.contains("<test>"))

        // Importación HTML maliciosa solo acepta HTTP/HTTPS y conserva carpetas legítimas.
        val targetHtmlFile = File(tmp.root, "imported_html.json")
        val payload = html + """
            <DT><A HREF="javascript:evil()">Malicious</A>
            <DT><A HREF="file:///data/secret">Local</A>
        """.trimIndent()
        val importedHtmlCount = BookmarkStore.importNetscapeHtml(targetHtmlFile, payload)
        assertEquals(1, importedHtmlCount)
        val roundTripped = BookmarkStore.list(targetHtmlFile).single()
        assertEquals("A & B", roundTripped.title)
        assertEquals("https://safe.example.org/a?x=1&y=2", roundTripped.url)
        assertEquals("Dev & Ops", roundTripped.folder)

        // Importación/exportación JSON rechaza esquemas no web y evita duplicados.
        val jsonOut = BookmarkStore.exportJson(BookmarkStore.list(file))
        val targetJsonFile = File(tmp.root, "imported_json.json")
        assertEquals(1, BookmarkStore.importJson(targetJsonFile, jsonOut))
        assertEquals(0, BookmarkStore.importJson(targetJsonFile, jsonOut))
        val maliciousJson = """{"bookmarks":[{"title":"bad","url":"javascript:alert(2)"},{"title":"ok","url":"https://kotlinlang.org/"}]}"""
        assertEquals(1, BookmarkStore.importJson(targetJsonFile, maliciousJson))
        assertEquals(2, BookmarkStore.list(targetJsonFile).size)
    }

    @Test
    fun workspaceStrictlyExcludesPrivateAndBurnerTabsAndPersistsMetadata() {
        val file = File(tmp.root, "workspaces.json")
        val normal1 = BrowserTab(
            id = "t1",
            url = "https://user:secret@docs.example.org/guide",
            title = "Guía Cefrium",
            groupName = "Investigación",
            desktopMode = true,
            pinned = true
        )
        val privateTab = BrowserTab(
            id = "t2",
            url = "https://private.example.org/secret",
            title = "Privada",
            isPrivate = true
        )
        val burnerTab = BrowserTab(
            id = "t3",
            url = "https://burner.example.org/temp",
            title = "Burner",
            isPrivate = true,
            burnerId = "burner-123"
        )
        val homeTab = BrowserTab(
            id = "t4",
            url = "about:blank",
            title = "Inicio"
        )
        val normal2 = BrowserTab(
            id = "t5",
            url = "https://developer.android.com/design",
            title = "Android Design",
            groupName = "UI"
        )

        val captured = WorkspaceStore.captureFromTabs(
            name = "Proyecto Nexo",
            tabs = listOf(normal1, privateTab, burnerTab, homeTab, normal2),
            activeTabId = "t5",
            nowMs = 10_000L
        )
        assertNotNull(captured)
        assertEquals(2, captured!!.tabs.size)
        assertEquals(1, captured.activeIndex)
        assertEquals("https://docs.example.org/guide", captured.tabs[0].url)
        assertTrue(captured.tabs[0].pinned)
        assertTrue(captured.tabs[0].desktopMode)
        assertEquals("Investigación", captured.tabs[0].groupName)
        assertEquals("https://developer.android.com/design", captured.tabs[1].url)
        assertFalse(captured.tabs.any { it.url.contains("private") || it.url.contains("burner") })

        // Solo privadas o home devuelve null.
        assertNull(WorkspaceStore.captureFromTabs("Vacío", listOf(privateTab, burnerTab, homeTab), "t2"))

        // Persistencia: guardar, listar, renombrar, borrar.
        assertTrue(WorkspaceStore.save(file, captured))
        val loaded = WorkspaceStore.list(file)
        assertEquals(1, loaded.size)
        assertEquals("Proyecto Nexo", loaded.first().name)
        assertTrue(loaded.first().groupSummary().contains("2 grupos"))

        assertTrue(WorkspaceStore.rename(file, loaded.first().id, "Arquitectura V3", nowMs = 20_000L))
        assertEquals("Arquitectura V3", WorkspaceStore.list(file).first().name)

        assertTrue(WorkspaceStore.delete(file, loaded.first().id))
        assertTrue(WorkspaceStore.list(file).isEmpty())
    }

    @Test
    fun startPageShortcutReorderMovesDeterministically() {
        val visible = listOf(
            "https://m.youtube.com/",
            "https://www.google.com/",
            "https://es.wikipedia.org/",
            "https://github.com/"
        )
        val movedUp = StartPageView.reorderShortcut(
            currentCustom = emptyList(),
            visibleWebOrigins = visible,
            targetOrigin = "https://github.com/",
            delta = -1
        )
        assertEquals(
            listOf(
                "https://m.youtube.com/",
                "https://www.google.com/",
                "https://github.com/",
                "https://es.wikipedia.org/"
            ),
            movedUp
        )

        val movedDown = StartPageView.reorderShortcut(
            currentCustom = movedUp,
            visibleWebOrigins = visible,
            targetOrigin = "https://m.youtube.com/",
            delta = +1
        )
        assertEquals("https://www.google.com/", movedDown[0])
        assertEquals("https://m.youtube.com/", movedDown[1])
    }
}
