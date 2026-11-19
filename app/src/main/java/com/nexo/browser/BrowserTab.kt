package com.nexo.browser

import com.nexo.browser.engine.EngineSession
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var title: String = "Nueva pestaña",
    val isPrivate: Boolean = false,
    var desktopMode: Boolean = false,
    var pinned: Boolean = false,
    var groupName: String? = null,
    val burnerId: String? = null,
    val burnerSeed: Int = java.security.SecureRandom().nextInt(),
    var networkProfile: TabNetworkProfile? = null,
    var networkState: String = "Sistema",
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var lastUsed: Long = System.currentTimeMillis(),
    var isLoading: Boolean = false,
    var navigationEpoch: Long = 0,
    var sessionState: String? = null,
    var isFullScreen: Boolean = false,
    var session: EngineSession? = null
) {
    init { require(burnerId == null || (isPrivate && burnerId.isNotBlank())) { "Burner requiere navegación privada." } }
}
