package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexoCommandTest {
    private val sampleTabs = listOf(
        NexoCommandEngine.TabCandidate("t1", "Cefrium Documentation", "https://cefrium.com/guides/public-api/", "Desarrollo", false, false),
        NexoCommandEngine.TabCandidate("t2", "Nexo Repo", "https://github.com/stivencop414-bot/NavegadorGecko", "Desarrollo", false, true),
        NexoCommandEngine.TabCandidate("t3", "Noticias", "https://example.org/news", null, true, false)
    )
    private val sampleBookmarks = listOf(
        NexoCommandEngine.BookmarkCandidate("b1", "Android Design", "https://developer.android.com/design", "Diseño")
    )
    private val sampleShortcuts = listOf(
        NexoCommandEngine.ShortcutCandidate("Wikipedia", "https://es.wikipedia.org/")
    )
    private val sampleHistory = listOf(
        NexoCommandEngine.HistoryCandidate("Chrome Extensions", "https://developer.chrome.com/docs/extensions/"),
        NexoCommandEngine.HistoryCandidate("Android Design", "https://developer.android.com/design")
    )

    @Test
    fun parsesOptionalPrefixesAndIncompleteHints() {
        val tabs = NexoCommandEngine.parseQuery("@tabs cefrium")
        assertEquals(NexoCommandEngine.Scope.TABS, tabs.scope)
        assertEquals("cefrium", tabs.term)
        assertTrue(tabs.isPrefixed)
        assertFalse(tabs.incompletePrefix)

        val spanish = NexoCommandEngine.parseQuery("@ajustes dns")
        assertEquals(NexoCommandEngine.Scope.SETTINGS, spanish.scope)
        assertEquals("dns", spanish.term)

        val hints = NexoCommandEngine.searchLocal("@", sampleTabs, sampleBookmarks, sampleShortcuts, sampleHistory, true)
        assertEquals(5, hints.size)
        assertTrue(hints.all { it is NexoCommandEngine.Result.PrefixHint })
    }

    @Test
    fun unprefixedQuerySearchesAllLocalSourcesWithoutMemorizingCommands() {
        val results = NexoCommandEngine.searchLocal("cefrium", sampleTabs, sampleBookmarks, sampleShortcuts, sampleHistory, true)
        assertTrue(results.any { it is NexoCommandEngine.Result.OpenTab && it.tabId == "t1" })

        val actionResults = NexoCommandEngine.searchLocal("descargas", sampleTabs, sampleBookmarks, sampleShortcuts, sampleHistory, false)
        assertTrue(actionResults.any { it is NexoCommandEngine.Result.Command && it.spec.id == "downloads" })
        assertTrue(actionResults.any { it is NexoCommandEngine.Result.Setting && it.spec.key == "downloads" })

        val groupResults = NexoCommandEngine.searchLocal("desarrollo", sampleTabs, sampleBookmarks, sampleShortcuts, sampleHistory, true)
        assertTrue(groupResults.any { it is NexoCommandEngine.Result.OpenGroup && it.groupName == "Desarrollo" && it.tabCount == 2 })

        // Deduplicates history URL already present in bookmarks.
        val bmResults = NexoCommandEngine.searchLocal("android design", sampleTabs, sampleBookmarks, sampleShortcuts, sampleHistory, true)
        assertEquals(1, bmResults.count { it is NexoCommandEngine.Result.Bookmark })
        assertEquals(0, bmResults.count { it is NexoCommandEngine.Result.History && it.url == "https://developer.android.com/design" })
    }

    @Test
    fun pageOnlyCommandsAreHiddenOnStartPageAndVisibleOnWebPages() {
        val onHome = NexoCommandEngine.searchLocal("@actions traducir", sampleTabs, sampleBookmarks, sampleShortcuts, sampleHistory, onWebPage = false)
        assertFalse(onHome.any { it is NexoCommandEngine.Result.Command && it.spec.id == "translate" })

        val onPage = NexoCommandEngine.searchLocal("@actions traducir", sampleTabs, sampleBookmarks, sampleShortcuts, sampleHistory, onWebPage = true)
        assertTrue(onPage.any { it is NexoCommandEngine.Result.Command && it.spec.id == "translate" })
    }

    @Test
    fun privacyBoundaryNeverSendsScopedOrPrivateQueriesToRemoteEngines() {
        assertFalse(NexoCommandEngine.allowsRemoteSuggestions("@tabs gmail", remoteAllowed = true))
        assertFalse(NexoCommandEngine.allowsRemoteSuggestions("@history banco", remoteAllowed = true))
        assertFalse(NexoCommandEngine.allowsRemoteSuggestions("@actions ajustes", remoteAllowed = true))
        assertFalse(NexoCommandEngine.allowsRemoteSuggestions("http://192.168.1.1", remoteAllowed = true))
        assertFalse(NexoCommandEngine.allowsRemoteSuggestions("kotlin", remoteAllowed = false))
        assertTrue(NexoCommandEngine.allowsRemoteSuggestions("kotlin android", remoteAllowed = true))
    }

    @Test
    fun remoteSuggestionsCannotTriggerPrivilegedCommands() {
        val remote = NexoCommandEngine.remoteResults(
            values = listOf("@actions settings", "kotlin coroutines", "@settings dns", "kotlin multiplatform"),
            rawQuery = "kotlin",
            remoteAllowed = true,
            max = 3
        )
        assertEquals(2, remote.size)
        assertTrue(remote.all { it is NexoCommandEngine.Result.RemoteSearch })
        assertFalse(remote.any { it.query.startsWith("@") })
    }
}
