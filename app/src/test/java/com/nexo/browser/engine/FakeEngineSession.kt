package com.nexo.browser.engine

import java.io.InputStream

/** Sesión de motor sin motor, para pruebas JVM y capturas de interfaz. */
class FakeEngineSession : EngineSession {
    override var isOpen: Boolean = true
    override var observer: EngineObserver? = null
    val loaded = mutableListOf<String>()
    var isActive = false
        private set
    var closed = false

    override val finder: EngineFinder = object : EngineFinder {
        override fun find(term: String, backwards: Boolean, onResult: (FindResult?) -> Unit) = onResult(null)
        override fun clear() = Unit
    }

    override fun loadUrl(url: String) { loaded += url }
    override fun reload(bypassCache: Boolean) = Unit
    override fun goBack() = Unit
    override fun goForward() = Unit
    override fun setActive(active: Boolean) { isActive = active }
    override fun setFocused(focused: Boolean) = Unit
    override fun setHighPriority(high: Boolean) = Unit
    override fun setSuspendMediaWhenInactive(suspend: Boolean) = Unit
    override fun setDesktopMode(enabled: Boolean) = Unit
    override fun setTrackingProtection(enabled: Boolean) = Unit
    override fun exitFullScreen() = Unit
    override fun flushState() = Unit
    override fun purgeHistory() = Unit
    override fun restoreState(state: EngineSessionState) = Unit
    override fun saveAsPdf(onResult: (InputStream?, Throwable?) -> Unit) = onResult(null, UnsupportedOperationException())
    override fun close() { closed = true; observer = null }
}
