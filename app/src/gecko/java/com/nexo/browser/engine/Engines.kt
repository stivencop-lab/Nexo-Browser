package com.nexo.browser.engine

import com.nexo.browser.engine.gecko.GeckoEngine

/** Motor de esta variante de la app. */
object Engines {
    val current: BrowserEngine get() = GeckoEngine
}
