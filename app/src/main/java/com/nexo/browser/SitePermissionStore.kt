package com.nexo.browser

import android.content.Context
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumContentSetting
import com.cefrium.CefriumPermission

/**
 * Registro por origen sincronizado directamente con `CefriumBrowser.setContentSetting`.
 * No mantiene políticas paralelas contradictorias: cada cambio se aplica al motor Chromium
 * y se consulta en [SitePermissions] para coherencia total.
 */
object SitePermissionStore {
    private const val PREFS = "nexo_site_permissions"

    enum class Decision(val id: String, val label: String, val cefrium: CefriumContentSetting) {
        ASK("ask", "Preguntar", CefriumContentSetting.ASK),
        ALLOW("allow", "Permitir", CefriumContentSetting.ALLOW),
        BLOCK("block", "Bloquear", CefriumContentSetting.BLOCK);

        companion object {
            fun fromId(raw: String?): Decision =
                entries.firstOrNull { it.id == raw } ?: ASK
        }
    }

    val MANAGED_PERMISSIONS = listOf(
        CefriumPermission.CAMERA to "Cámara",
        CefriumPermission.MICROPHONE to "Micrófono",
        CefriumPermission.GEOLOCATION to "Ubicación",
        CefriumPermission.NOTIFICATIONS to "Notificaciones"
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(origin: String, permission: CefriumPermission): String =
        "${origin.lowercase()}|${permission.name}"

    fun get(context: Context, origin: String?, permission: CefriumPermission): Decision {
        val clean = SiteControlModel.originOf(origin) ?: return Decision.ASK
        val raw = prefs(context).getString(key(clean, permission), null)
        return Decision.fromId(raw)
    }

    fun set(context: Context, origin: String?, permission: CefriumPermission, decision: Decision, browser: CefriumBrowser? = null) {
        val clean = SiteControlModel.originOf(origin) ?: return
        val editor = prefs(context).edit()
        if (decision == Decision.ASK) editor.remove(key(clean, permission))
        else editor.putString(key(clean, permission), decision.id)
        editor.apply()
        if (browser != null && !browser.isClosed) {
            runCatching { browser.setContentSetting(clean, permission, decision.cefrium) }
        }
    }

    fun applyAllToBrowser(context: Context, browser: CefriumBrowser, url: String?) {
        if (browser.isClosed) return
        val origin = SiteControlModel.originOf(url) ?: return
        for ((perm, _) in MANAGED_PERMISSIONS) {
            val stored = get(context, origin, perm)
            if (stored != Decision.ASK) {
                runCatching { browser.setContentSetting(origin, perm, stored.cefrium) }
            }
        }
    }

    fun resetOrigin(context: Context, origin: String?, browser: CefriumBrowser? = null) {
        val clean = SiteControlModel.originOf(origin) ?: return
        val editor = prefs(context).edit()
        for ((perm, _) in MANAGED_PERMISSIONS) {
            editor.remove(key(clean, perm))
            if (browser != null && !browser.isClosed) {
                runCatching { browser.setContentSetting(clean, perm, CefriumContentSetting.DEFAULT) }
            }
        }
        editor.apply()
    }

    fun summary(context: Context, origin: String?): String {
        val clean = SiteControlModel.originOf(origin) ?: return "Sin permisos web"
        val custom = MANAGED_PERMISSIONS.mapNotNull { (perm, label) ->
            when (get(context, clean, perm)) {
                Decision.ALLOW -> "$label: permitido"
                Decision.BLOCK -> "$label: bloqueado"
                Decision.ASK -> null
            }
        }
        return if (custom.isEmpty()) "Todos en preguntar (predeterminado)" else custom.joinToString(" · ")
    }
}
