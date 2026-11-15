package com.nexo.browser.engine

import com.nexo.browser.engine.chromium.ChromiumEngine

/** Motor de esta variante de la app. */
object Engines {
    val current: BrowserEngine get() = ChromiumEngine
}
