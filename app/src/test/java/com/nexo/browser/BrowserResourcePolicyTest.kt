package com.nexo.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserResourcePolicyTest {
    @Test fun stableTabIdsDistinguishHashCollisionsAndSurviveReordering() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val ids = StableTabIds()
        val first = ids.id("Aa")
        val second = ids.id("BB")
        assertNotEquals(first, second)
        ids.retain(setOf("BB", "Aa"))
        assertEquals(second, ids.id("BB"))
        assertEquals(first, ids.id("Aa"))
        ids.retain(setOf("BB"))
        assertEquals(second, ids.id("BB"))
        assertNotEquals(first, ids.id("new-tab"))
    }

    @Test fun foregroundCriticalMemoryIsNotMistakenForAWeakSignal() {
        assertEquals(MemoryPressure.CacheAction.CLEAR, MemoryPressure.cacheAction(15))
        assertEquals(MemoryPressure.CacheAction.REDUCE, MemoryPressure.cacheAction(10))
        assertEquals(MemoryPressure.CacheAction.REDUCE, MemoryPressure.cacheAction(20))
        assertEquals(MemoryPressure.CacheAction.CLEAR, MemoryPressure.cacheAction(60))
        assertEquals(MemoryPressure.CacheAction.CLEAR, MemoryPressure.cacheAction(80))
        assertEquals(MemoryPressure.CacheAction.KEEP, MemoryPressure.cacheAction(5))
    }

    @Test fun menuGroupingNeverDropsOrDuplicatesAnAvailableAction() {
        val ids = listOf(R.drawable.ic_menu_copy, R.drawable.ic_menu_settings, R.drawable.ic_menu_desktop,
            R.drawable.ic_menu_find, R.drawable.ic_menu_download, R.drawable.ic_menu_translate,
            R.drawable.ic_menu_history, R.drawable.ic_menu_pdf, R.drawable.ic_menu_share,
            R.drawable.ic_menu_pin, R.drawable.ic_menu_screenshot, R.drawable.ic_menu_reopen, -1)
        val actions = ids.map { SheetAction(it, "Action $it") {} }
        val layout = MenuSections.arrange(actions)
        assertEquals(4, layout.quick.size)
        assertEquals(actions.toSet(), (layout.quick + layout.groups.flatMap { it.actions }).toSet())
        assertEquals(actions.size, layout.quick.size + layout.groups.sumOf { it.actions.size })
        assertTrue(layout.groups.all { it.actions.size <= 5 })
        assertTrue(layout.groups.any { group -> group.actions.any { it.icon == -1 } })
    }

    @Test fun registrableDomainsDoNotConfusePublicSuffixesOrHostedTenants() {
        assertFalse(SiteIconDiscovery.sameSite("victim.co.uk", "attacker.co.uk"))
        assertFalse(SiteIconDiscovery.sameSite("victim.github.io", "attacker.github.io"))
        assertFalse(SiteIconDiscovery.sameSite(null, null))
        assertTrue(SiteIconDiscovery.sameSite("www.example.co.uk", "cdn.example.co.uk"))
        assertTrue(SiteIconDiscovery.sameSite("EXAMPLE.com.", "www.example.com"))
    }
}
