package com.nexo.browser.engine

import com.nexo.browser.engine.cefrium.CefriumEngine

/** Motor web de Nexo: Chromium embebido (Cefrium). */
object Engines {
    val current: BrowserEngine get() = CefriumEngine
}
