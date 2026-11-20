package com.nexo.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.nexo.browser.ai.NexoAiConfig
import com.nexo.browser.ai.NexoAiSheet
import com.nexo.browser.dark.DarkEngine
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import com.nexo.browser.extensions.nxr.NxrPermissions
import com.nexo.browser.extensions.nxr.NxrRuntime
import com.nexo.browser.media.NexoMediaEngine
import com.nexo.browser.shield.PhantomShield

/**
 * Nexo Site Control: panel contextual unificado para la página activa.
 *
 * Agrupa las 5 secciones del sitio actual:
 * 1. Sitio y conexión (dominio, HTTPS/HTTP/SSL honesto, resumen de permisos y datos de Cefrium).
 * 2. Privacidad (PhantomShield, bloqueos, excepciones, cookies reales y permisos del motor).
 * 3. Experiencia (tema por sitio, zoom 75%–200%, silenciar pestaña, traducción, multimedia y subtítulos).
 * 4. Extensiones (extensiones NXR aplicables al sitio, permisos concedidos y revocación por host).
 * 5. IA (estado de Nexo AI y permiso por sitio para analizar esta página).
 *
 * Mantiene acceso en 1 toque a [TabTools] (Burner, Proxy/Tor, DNS seguro y Grupos).
 * Presentación UI 7.0: dominio con estado de conexión expandible, interruptores inmediatos
 * y filas limpias con iconos desnudos; permisos y cookies viven en una subhoja.
 */
object SiteControlSheet {
    fun show(activity: Activity) {
        val tab = TabManager.activeTab() ?: return
        val session = tab.session as? CefriumEngineSession
        val url = tab.url
        val isWeb = UrlSafety.isWebUrl(url) && !BrowserPrefs.isNexoHome(url)
        val conn = SiteControlModel.inspectConnection(url, session?.lastBlockedSslHost())
        val host = conn.host
        val origin = conn.origin
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }

        lateinit var sheet: Dialog
        val body = NexoUi.column(activity, horizontalDp = NexoUi.Tokens.SHEET_PADDING)
        fun gap(view: View, top: Float = 10f) = body.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        fun group(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
            clipToOutline = true
            NexoGlass.paperShadow(this, p)
        }
        fun groupAdd(card: LinearLayout, view: View) {
            if (card.childCount > 0) card.addView(NexoUi.insetDivider(activity).apply {
                (layoutParams as LinearLayout.LayoutParams).marginStart = dp(if (view is LinearLayout && view.childCount > 0 && view.getChildAt(0) is android.widget.ImageView) 52f else 16f)
            })
            card.addView(view)
        }

        // ===== Cabecera: dominio + estado de conexión expandible en 1 toque =====
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), 0, dp(22f), dp(10f))
        }
        header.addView(TextView(activity).apply {
            text = if (isWeb) host else "Inicio de Nexo"
            textSize = 22f
            letterSpacing = -0.025f
            typeface = NexoUi.semiBold(activity)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            isAccessibilityHeading = true
        })
        val connDot = when (conn.tone) {
            NexoUi.Tone.SUCCESS -> p.success.color
            NexoUi.Tone.DANGER -> p.danger
            NexoUi.Tone.WARNING -> p.warning.color
            else -> p.muted
        }
        val details = TextView(activity).apply {
            val meta = buildList {
                add(conn.explanation)
                if (isWeb && origin != null) add("Origen: $origin")
                if (isWeb) add("Permisos: ${SitePermissionStore.summary(activity, origin)}")
                session?.lastLoadDurationMs?.takeIf { isWeb && it > 0L }?.let { add("Última carga: $it ms") }
                if (tab.isPrivate) add(if (tab.burnerId != null) "Perfil aislado Burner" else "Perfil privado en memoria")
            }
            text = meta.joinToString("\n")
            textSize = 13f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(4f), 0, dp(4f))
            visibility = View.GONE
        }
        val chevron = activity.getDrawable(R.drawable.ic_nav_chevron)?.mutate()?.apply {
            setTint(p.muted); setBounds(0, 0, dp(14f), dp(14f))
        }
        val connRow = TextView(activity).apply {
            text = if (isWeb) conn.headline else "Página interna"
            textSize = 13.5f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44f)
            compoundDrawablePadding = dp(8f)
            setCompoundDrawablesRelative(android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(connDot)
                setBounds(0, 0, dp(7f), dp(7f))
            }, null, chevron, null)
            background = ThemeManager.ripple(activity, null, 12f)
            contentDescription = "${text}. Ver detalles de la conexión"
            setOnClickListener {
                val open = details.visibility != View.VISIBLE
                details.visibility = if (open) View.VISIBLE else View.GONE
                NexoMotion.pulse(this, 0.98f)
            }
        }
        header.addView(connRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(details)

        // ===== Interruptores inmediatos: PhantomShield y Tema oscuro =====
        val shieldGlobalOn = PhantomShield.isEnabled()
        val siteDisabled = isWeb && PhantomShield.isSiteDisabled(host)
        val blockedHere = session?.let { PhantomShield.tabBlockedCount(it.shield) } ?: 0
        if (isWeb && host.isNotBlank()) {
            val toggles = group()
            groupAdd(toggles, NexoUi.switchRow(
                activity,
                "PhantomShield",
                when {
                    !shieldGlobalOn -> "Desactivado globalmente"
                    siteDisabled -> "Pausado en este sitio"
                    blockedHere == 1 -> "1 rastreador bloqueado"
                    blockedHere > 1 -> "$blockedHere rastreadores bloqueados"
                    else -> "Activo"
                },
                shieldGlobalOn && !siteDisabled
            ) { on ->
                if (!PhantomShield.isEnabled() && on) PhantomShield.setEnabled(true)
                PhantomShield.setSiteDisabled(host, disabled = !on)
                Toastish.show(activity, if (on) "PhantomShield activado en $host" else "PhantomShield pausado en $host")
            })
            val excluded = BrowserPrefs.isNightException(activity, host)
            groupAdd(toggles, NexoUi.switchRow(activity, "Tema oscuro", if (excluded) "Colores originales" else DarkEngine.summary(activity), !excluded) { on ->
                NightMode.toggleSite(activity, host, excluded = !on)
            })
            if (session != null) {
                val mutedNow = session.isAudioMuted()
                groupAdd(toggles, NexoUi.switchRow(activity, "Silenciar pestaña", null, mutedNow) { mute ->
                    session.setAudioMuted(mute)
                    Toastish.show(activity, if (mute) "Pestaña silenciada" else "Sonido activado")
                })
            }
            gap(toggles, 2f)
        }

        // ===== Filas limpias con iconos desnudos =====
        val rows = group()
        if (isWeb && host.isNotBlank()) {
            groupAdd(rows, NexoUi.actionRow(activity, R.drawable.ic_menu_translate,
                if (TranslatorManager.isTranslated(tab.session)) "Restaurar idioma original"
                else "Traducir a ${BrowserPrefs.translatorTarget(activity).uppercase()}") {
                sheet.dismiss()
                TranslatorManager.translateActive(activity) { _, message ->
                    activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
                }
            })
            groupAdd(rows, NexoUi.actionRow(activity, R.drawable.ic_menu_reader, "Modo lectura") {
                sheet.dismiss()
                com.nexo.browser.reader.NexoReader.openActiveTab(activity)
            })
        }
        groupAdd(rows, NexoUi.actionRow(activity, R.drawable.ic_menu_play, "Media Engine y Subtítulos") {
            sheet.dismiss()
            NexoMediaEngine.showSheet(activity)
        })
        if (isWeb && origin != null && session != null) {
            groupAdd(rows, NexoUi.actionRow(activity, R.drawable.ic_tab_tools, "Permisos del sitio", SitePermissionStore.summary(activity, origin)) {
                sheet.dismiss()
                showPermissions(activity, tab, session, url, host, origin)
            })
        }
        groupAdd(rows, NexoUi.actionRow(activity, R.drawable.ic_menu_shield, "Panel completo PhantomShield") {
            sheet.dismiss()
            ExtensionManager.runActionFor(ExtensionManager.PHANTOMSHIELD_ID) { ok, message ->
                if (!ok) activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
            }
        })
        gap(rows)

        // ===== Zoom por sitio (75 %–200 %): control segmentado tabular =====
        if (isWeb && host.isNotBlank() && session != null) {
            var zoomPct = SiteZoomStore.effectivePercent(activity, url)
            val zoomCard = group().apply { setPadding(dp(16f), dp(12f), dp(12f), dp(12f)) }
            val zoomRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            val zoomLabel = TextView(activity).apply {
                text = "Zoom"
                textSize = 15f
                typeface = NexoUi.medium(activity)
                setTextColor(p.text)
            }
            val zoomValue = TextView(activity).apply {
                textSize = 14f
                typeface = NexoUi.mono(activity)
                fontFeatureSettings = "tnum"
                setTextColor(p.muted)
                minWidth = dp(52f)
                gravity = Gravity.CENTER
            }
            fun applyZoom(next: Int) {
                zoomPct = SiteControlModel.clampZoomPercent(next)
                zoomValue.text = "$zoomPct%"
                zoomValue.contentDescription = "Zoom de $host: $zoomPct por ciento"
                session.setSiteZoom(url, zoomPct)
            }
            zoomValue.text = "$zoomPct%"
            fun step(label: String, desc: String, onClick: () -> Unit) = TextView(activity).apply {
                text = label
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(p.text)
                contentDescription = desc
                background = ThemeManager.ripple(activity, android.graphics.drawable.InsetDrawable(
                    ThemeManager.rounded(activity, p.surfaceContainerHighest, NexoUi.Tokens.RADIUS_PILL), dp(4f)), NexoUi.Tokens.RADIUS_PILL)
                setOnClickListener { onClick() }
            }
            zoomRow.addView(zoomLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            zoomRow.addView(step("−", "Reducir zoom") { applyZoom(SiteControlModel.nextZoomStep(zoomPct, -1)) }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            zoomRow.addView(zoomValue.apply { setOnClickListener { applyZoom(100) } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)))
            zoomRow.addView(step("+", "Aumentar zoom") { applyZoom(SiteControlModel.nextZoomStep(zoomPct, +1)) }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
            zoomCard.addView(zoomRow)
            gap(zoomCard)
        }

        // ===== Extensiones, Nexo AI y herramientas de red =====
        val more = group()
        if (isWeb) {
            val installedExts = NxrRuntime.installed(activity)
            installedExts.filter { ext ->
                val policy = NxrRuntime.policyFor(ext.id)
                host in policy.deniedHosts || host in policy.allowedHostsOverride ||
                    NxrPermissions.isAuthorizedForUrl(policy.copy(enabled = true, allowInPrivate = true, deniedHosts = emptySet()), url, false)
            }.forEach { ext ->
                val policy = NxrRuntime.policyFor(ext.id)
                val activeHere = NxrPermissions.isAuthorizedForUrl(policy, url, tab.isPrivate)
                groupAdd(more, NexoUi.switchRow(activity, ext.name, if (!policy.enabled) "Desactivada" else null, activeHere) { allow ->
                    if (allow && !policy.enabled) NxrRuntime.setEnabled(activity, ext.id, true)
                    NxrRuntime.setHostAllowed(activity, ext.id, host, allow)
                    Toastish.show(activity, if (allow) "Acceso concedido a ${ext.name} en $host (recarga para aplicar)"
                        else "Acceso revocado a ${ext.name} en $host")
                })
            }
            if (host.isNotBlank()) {
                val aiAllowedHere = NexoAiConfig.isHostAllowed(activity, host)
                groupAdd(more, NexoUi.switchRow(activity, "Nexo AI en este sitio", null, aiAllowedHere) { allowed ->
                    NexoAiConfig.setHostAllowed(activity, host, allowed)
                    Toastish.show(activity, if (allowed) "Nexo AI permitido en $host" else "Nexo AI bloqueado en $host")
                })
            }
        }
        groupAdd(more, NexoUi.actionRow(activity, R.drawable.ic_menu_extensions, "Extensiones") {
            sheet.dismiss()
            activity.startActivity(Intent(activity, ExtensionsActivity::class.java))
        })
        groupAdd(more, NexoUi.actionRow(activity, R.drawable.ic_tab_tools, "Red, identidad y grupos", tab.networkState.toString()) {
            sheet.dismiss()
            TabTools.show(activity)
        })
        groupAdd(more, NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Nexo Dark Engine") {
            sheet.dismiss()
            DarkEngine.showSheet(activity, host)
        })
        if (!isWeb) groupAdd(more, NexoUi.actionRow(activity, R.drawable.ic_nexo_aperture, "Nexo AI") {
            sheet.dismiss()
            NexoAiSheet.show(activity)
        })
        gap(more)

        sheet = NexoSheets.show(activity, NexoSheets.column(activity, header, body))
    }

    /** Subhoja de permisos del motor y datos del sitio (cookies reales del perfil Cefrium). */
    private fun showPermissions(activity: Activity, tab: BrowserTab, session: CefriumEngineSession, url: String, host: String, origin: String) {
        val p = ThemeManager.palette(activity)
        val dp = { v: Float -> NexoUi.dp(activity, v) }
        val body = NexoUi.column(activity, horizontalDp = NexoUi.Tokens.SHEET_PADDING)
        SitePermissionStore.MANAGED_PERMISSIONS.forEach { (perm, label) ->
            body.addView(NexoUi.section(activity, label))
            val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fun render() {
                holder.removeAllViews()
                val current = SitePermissionStore.get(activity, origin, perm)
                holder.addView(NexoUi.segmented(activity, SitePermissionStore.Decision.entries.map { it to it.label }, current) { decision ->
                    session.setSiteContentSetting(origin, perm, decision)
                    render()
                })
            }
            render()
            body.addView(holder)
        }
        val cookieCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.rounded(activity, NexoSheets.groupFill(p), NexoUi.Tokens.RADIUS_CARD)
            clipToOutline = true
        }
        val cookieStatus = TextView(activity).apply {
            text = "Consultando cookies…"
            textSize = 13f
            typeface = NexoUi.regular(activity)
            setTextColor(p.muted)
            setPadding(dp(16f), dp(14f), dp(16f), dp(4f))
        }
        session.countSiteCookies(url) { count ->
            cookieStatus.text = when (count) {
                null -> "Política de cookies según Ajustes"
                1 -> "1 cookie guardada"
                else -> "$count cookies guardadas"
            }
        }
        cookieCard.addView(cookieStatus)
        cookieCard.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_close, "Borrar cookies y datos", danger = true) {
            AlertDialog.Builder(activity)
                .setTitle("¿Borrar datos de $host?")
                .setMessage("Se eliminarán las cookies y el almacenamiento local de $origin en este perfil. Es posible que debas iniciar sesión de nuevo.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar") { _, _ ->
                    session.clearSiteDataAndCookies(url) {
                        cookieStatus.text = "0 cookies guardadas"
                        Toastish.show(activity, "Cookies y datos de $host eliminados")
                    }
                }.show()
        })
        body.addView(cookieCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18f) })
        NexoSheets.show(activity, NexoSheets.column(activity, NexoSheets.header(activity, "Permisos de $host"), body))
    }
}
