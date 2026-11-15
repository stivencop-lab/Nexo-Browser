package com.nexo.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El contrato que TabManager espera de cualquier motor, comprobado con un motor falso. */
class EngineContractTest {
    @Test fun observerDefaultsLeaveDecisionsToTheEngine() {
        val session = FakeEngineSession()
        val observer = object : EngineObserver {}
        val request = EngineLoadRequest("https://example.com/", hasUserGesture = true, isDirectNavigation = true, opensNewWindow = false)
        assertEquals(LoadDecision.DEFAULT, observer.onLoadRequest(session, request) { })
        assertNull(observer.onNewWindow(session, "https://example.com/"))
    }

    @Test fun closingDetachesTheObserver() {
        val session = FakeEngineSession()
        session.observer = object : EngineObserver {}
        session.setActive(true)
        assertTrue(session.isActive)
        session.close()
        assertTrue(session.closed)
        assertNull(session.observer)
    }

    @Test fun findResultKeepsCounts() {
        val result = FindResult(found = true, current = 2, total = 5)
        assertTrue(result.found)
        assertEquals(2, result.current)
        assertEquals(5, result.total)
        assertFalse(FindResult(false, 0, 0).found)
    }
}
