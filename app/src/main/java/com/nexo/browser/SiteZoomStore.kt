package com.nexo.browser

import android.content.Context

/**
 * Zoom persistente por dominio (`nexo_site_zoom`), separado de `BrowserPrefs` para
 * no interferir con `TransferSchema`. Cuando no hay ajuste para un sitio, aplica el
 * tamaño de texto global de [BrowserPrefs.textScale].
 */
object SiteZoomStore {
    private const val PREFS = "nexo_site_zoom"
    private const val MAX_HOSTS = 300

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, host: String?): Int? {
        val clean = host?.let { SiteControlModel.normalizeHost(it) }.orEmpty()
        if (clean.isEmpty()) return null
        val p = prefs(context)
        if (!p.contains(clean)) return null
        return SiteControlModel.clampZoomPercent(p.getInt(clean, 100))
    }

    fun set(context: Context, host: String?, percent: Int?) {
        val clean = host?.let { SiteControlModel.normalizeHost(it) }.orEmpty()
        if (clean.isEmpty()) return
        val p = prefs(context)
        val editor = p.edit()
        val globalDefault = BrowserPrefs.textScale(context)
        if (percent == null || SiteControlModel.clampZoomPercent(percent) == globalDefault) {
            editor.remove(clean)
        } else {
            if (p.all.size >= MAX_HOSTS && !p.contains(clean)) {
                p.all.keys.firstOrNull()?.let { editor.remove(it) }
            }
            editor.putInt(clean, SiteControlModel.clampZoomPercent(percent))
        }
        editor.apply()
    }

    fun effectivePercent(context: Context, url: String?): Int {
        val host = SiteControlModel.hostOf(url)
        return get(context, host) ?: SiteControlModel.clampZoomPercent(BrowserPrefs.textScale(context))
    }

    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
