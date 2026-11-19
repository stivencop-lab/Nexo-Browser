package com.nexo.browser

import android.content.Context
import com.nexo.browser.dark.DarkEngine

/**
 * Fachada compatible hacia [DarkEngine] (Nexo Dark Engine).
 * Activo según el modo configurado (`auto`, `light`, `dark`, `oled`) y
 * excepciones por dominio.
 */
object NightMode {
    /** Color de fondo para las páginas oscurecidas, o null si el modo oscuro no aplica. */
    fun effective(context: Context, hostOrUrl: String? = null): String? =
        DarkEngine.effectiveBackground(context, hostOrUrl)

    /** Cambia la excepción del sitio y la aplica al momento a todos sus marcos. */
    fun toggleSite(context: Context, host: String, excluded: Boolean) {
        BrowserPrefs.setNightException(context, host, excluded)
        if (!excluded && DarkEngine.siteMode(context, host) == DarkEngine.MODE_LIGHT) {
            DarkEngine.setSiteMode(context, host, null)
        } else {
            ExtensionManager.sendBrowserState(context)
        }
    }
}
