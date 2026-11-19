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

        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)

        // =====================================================================
        // 1. SITIO Y CONEXIÓN
        // =====================================================================
        body.addView(NexoUi.section(activity, "Sitio y conexión"))
        val connCard = NexoUi.card(activity)
        connCard.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = conn.headline
                textSize = 14.5f
                typeface = NexoUi.bold(activity)
                setTextColor(p.text)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(NexoUi.statusPill(activity, conn.badge, conn.tone))
        })
        connCard.addView(TextView(activity).apply {
            text = conn.explanation
            textSize = 12.5f
            setTextColor(p.muted)
            setLineSpacing(0f, 1.18f)
            setPadding(0, NexoUi.dp(activity, 6f), 0, 0)
        })
        val metaParts = mutableListOf<String>()
        if (isWeb && origin != null) metaParts += "Origen: $origin"
        if (isWeb) metaParts += "Permisos: ${SitePermissionStore.summary(activity, origin)}"
        session?.lastLoadDurationMs?.takeIf { isWeb && it > 0L }?.let { ms ->
            metaParts += "Última carga: ${ms} ms"
        }
        if (tab.isPrivate) {
            metaParts += if (tab.burnerId != null) "Perfil aislado Burner" else "Perfil privado en memoria"
        }
        if (metaParts.isNotEmpty()) {
            connCard.addView(TextView(activity).apply {
                text = metaParts.joinToString("\n")
                textSize = 12f
                setTextColor(p.muted)
                setPadding(0, NexoUi.dp(activity, 8f), 0, 0)
            })
        }
        body.addView(connCard)

        // =====================================================================
        // 2. PRIVACIDAD
        // =====================================================================
        body.addView(NexoUi.section(activity, "Privacidad"))
        val shieldGlobalOn = PhantomShield.isEnabled()
        val siteDisabled = isWeb && PhantomShield.isSiteDisabled(host)
        val blockedHere = session?.let { PhantomShield.tabBlockedCount(it.shield) } ?: 0
        if (isWeb && host.isNotBlank()) {
            val shieldSub = when {
                !shieldGlobalOn -> "PhantomShield está desactivado globalmente."
                siteDisabled -> "Pausado en $host (excepción activa)."
                blockedHere > 0 -> "$blockedHere bloqueos en esta carga en $host."
                else -> "Activo en $host (bloqueo de anuncios y rastreadores)."
            }
            body.addView(NexoUi.switchRow(
                activity,
                "PhantomShield en $host",
                shieldSub,
                shieldGlobalOn && !siteDisabled
            ) { on ->
                if (!PhantomShield.isEnabled() && on) PhantomShield.setEnabled(true)
                PhantomShield.setSiteDisabled(host, disabled = !on)
                Toastish.show(activity, if (on) "PhantomShield activado en $host" else "PhantomShield pausado en $host")
            })
        }
        body.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_shield,
            "Panel completo de PhantomShield",
            if (blockedHere > 0) "$blockedHere bloqueos aquí · listas, excepciones e IA" else "Listas de filtros, estadísticas y excepciones"
        ) {
            sheet.dismiss()
            ExtensionManager.runActionFor(ExtensionManager.PHANTOMSHIELD_ID) { ok, message ->
                if (!ok) activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
            }
        })

        // Cookies y datos del sitio (recuento real desde CefriumProfile)
        if (isWeb && origin != null && session != null) {
            val cookieStatus = TextView(activity).apply {
                text = "Consultando cookies de $host…"
                textSize = 12.5f
                setTextColor(p.muted)
                setPadding(NexoUi.dp(activity, 4f), NexoUi.dp(activity, 4f), NexoUi.dp(activity, 4f), NexoUi.dp(activity, 6f))
            }
            session.countSiteCookies(url) { count ->
                cookieStatus.text = when (count) {
                    null -> "Política de cookies activa según Ajustes."
                    0 -> "0 cookies almacenadas para $host."
                    1 -> "1 cookie almacenada para $host."
                    else -> "$count cookies almacenadas para $host."
                }
            }
            body.addView(cookieStatus)
            body.addView(NexoUi.actionRow(
                activity,
                R.drawable.ic_menu_close,
                "Borrar cookies y datos de $host",
                "Elimina cookies y almacenamiento local solo de este origen",
                danger = true
            ) {
                AlertDialog.Builder(activity)
                    .setTitle("¿Borrar datos de $host?")
                    .setMessage("Se eliminarán las cookies y el almacenamiento local de $origin en este perfil. Es posible que debas iniciar sesión de nuevo.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Borrar") { _, _ ->
                        session.clearSiteDataAndCookies(url) {
                            cookieStatus.text = "0 cookies almacenadas para $host."
                            Toastish.show(activity, "Cookies y datos de $host eliminados")
                        }
                    }.show()
            })

            // Permisos del motor (sincronizados con CefriumBrowser.setContentSetting)
            val permsCard = NexoUi.card(activity)
            permsCard.addView(TextView(activity).apply {
                text = "Permisos de $host (motor Chromium)"
                textSize = 13.5f
                typeface = NexoUi.bold(activity)
                setTextColor(p.text)
                setPadding(0, 0, 0, NexoUi.dp(activity, 6f))
            })
            SitePermissionStore.MANAGED_PERMISSIONS.forEach { (perm, label) ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, NexoUi.dp(activity, 4f), 0, NexoUi.dp(activity, 4f))
                }
                row.addView(TextView(activity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(p.text)
                    setPadding(0, 0, 0, NexoUi.dp(activity, 4f))
                })
                val chipHolder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                fun renderPermChips() {
                    val current = SitePermissionStore.get(activity, origin, perm)
                    chipHolder.removeAllViews()
                    chipHolder.addView(NexoUi.chipRow(activity, SitePermissionStore.Decision.entries.map { decision ->
                        NexoUi.chip(activity, decision.label, current == decision) {
                            session.setSiteContentSetting(origin, perm, decision)
                            renderPermChips()
                        }
                    }, sidePaddingDp = 0f))
                }
                renderPermChips()
                row.addView(chipHolder)
                permsCard.addView(row)
            }
            body.addView(permsCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = NexoUi.dp(activity, 8f) })
        }

        // =====================================================================
        // 3. EXPERIENCIA
        // =====================================================================
        body.addView(NexoUi.section(activity, "Experiencia"))
        if (isWeb && host.isNotBlank()) {
            val excluded = BrowserPrefs.isNightException(activity, host)
            body.addView(NexoUi.switchRow(
                activity,
                "Tema oscuro en $host",
                if (excluded) "Excluido: muestra los colores originales del sitio."
                else "Nexo Dark Engine (${DarkEngine.summary(activity)}).",
                !excluded
            ) { on ->
                NightMode.toggleSite(activity, host, excluded = !on)
            })

            // Zoom por sitio (75% - 200%)
            if (session != null) {
                var zoomPct = SiteZoomStore.effectivePercent(activity, url)
                val zoomCard = NexoUi.card(activity)
                val zoomLabel = TextView(activity).apply {
                    text = "Zoom en $host: $zoomPct%"
                    textSize = 14f
                    typeface = NexoUi.bold(activity)
                    setTextColor(p.text)
                }
                val zoomSub = TextView(activity).apply {
                    text = "Se guarda solo para este dominio (predeterminado: ${BrowserPrefs.textScale(activity)}%)."
                    textSize = 12f
                    setTextColor(p.muted)
                    setPadding(0, NexoUi.dp(activity, 2f), 0, NexoUi.dp(activity, 8f))
                }
                fun applyZoom(next: Int) {
                    zoomPct = SiteControlModel.clampZoomPercent(next)
                    zoomLabel.text = "Zoom en $host: $zoomPct%"
                    session.setSiteZoom(url, zoomPct)
                }
                val zoomRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(NexoUi.button(activity, "−") {
                        applyZoom(SiteControlModel.nextZoomStep(zoomPct, -1))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = NexoUi.dp(activity, 6f) })
                    addView(NexoUi.button(activity, "100%") {
                        applyZoom(100)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = NexoUi.dp(activity, 6f) })
                    addView(NexoUi.button(activity, "+") {
                        applyZoom(SiteControlModel.nextZoomStep(zoomPct, +1))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
                zoomCard.addView(zoomLabel)
                zoomCard.addView(zoomSub)
                zoomCard.addView(zoomRow)
                body.addView(zoomCard, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = NexoUi.dp(activity, 6f); bottomMargin = NexoUi.dp(activity, 6f) })

                val mutedNow = session.isAudioMuted()
                body.addView(NexoUi.switchRow(
                    activity,
                    "Silenciar audio de esta pestaña",
                    if (mutedNow) "El sonido de esta pestaña está silenciado." else "Reproducción de audio normal.",
                    mutedNow
                ) { mute ->
                    session.setAudioMuted(mute)
                    Toastish.show(activity, if (mute) "Pestaña silenciada" else "Sonido activado")
                })
            }

            body.addView(NexoUi.actionRow(
                activity,
                R.drawable.ic_menu_translate,
                if (TranslatorManager.isTranslated(tab.session)) "Restaurar idioma original" else "Traducir esta página",
                "Destino configurado: ${BrowserPrefs.translatorTarget(activity).uppercase()}"
            ) {
                sheet.dismiss()
                TranslatorManager.translateActive(activity) { _, message ->
                    activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
                }
            })
            body.addView(NexoUi.actionRow(
                activity,
                R.drawable.ic_menu_reader,
                "Modo lectura (Nexo Reader)",
                "Vista limpia saneada, lectura en voz alta y guardado sin conexión"
            ) {
                sheet.dismiss()
                com.nexo.browser.reader.NexoReader.openActiveTab(activity)
            })
        }
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_settings, "Nexo Dark Engine", DarkEngine.summary(activity)) {
            sheet.dismiss()
            DarkEngine.showSheet(activity, host)
        })
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_play, "Nexo Media Engine", "Velocidad, salto rápido, gestos y subtítulos") {
            sheet.dismiss()
            NexoMediaEngine.showSheet(activity)
        })
        body.addView(NexoUi.actionRow(activity, R.drawable.ic_menu_translate, "Subtítulos en vivo", CaptionsUi.summary(activity)) {
            sheet.dismiss()
            CaptionsUi.showSheet(activity)
        })

        // =====================================================================
        // 4. EXTENSIONES
        // =====================================================================
        body.addView(NexoUi.section(activity, "Extensiones en este sitio"))
        val installedExts = NxrRuntime.installed(activity)
        if (!isWeb || installedExts.isEmpty()) {
            body.addView(NexoUi.note(
                activity,
                if (!isWeb) "Las extensiones no se ejecutan en páginas internas de Nexo."
                else "No hay extensiones NXR de terceros instaladas."
            ))
        } else {
            val matching = installedExts.filter { ext ->
                val policy = NxrRuntime.policyFor(ext.id)
                host in policy.deniedHosts || host in policy.allowedHostsOverride ||
                    NxrPermissions.isAuthorizedForUrl(policy.copy(enabled = true, allowInPrivate = true, deniedHosts = emptySet()), url, false)
            }
            if (matching.isEmpty()) {
                body.addView(NexoUi.note(activity, "Ninguna extensión instalada solicita acceso a $host."))
            } else {
                matching.forEach { ext ->
                    val policy = NxrRuntime.policyFor(ext.id)
                    val activeHere = NxrPermissions.isAuthorizedForUrl(policy, url, tab.isPrivate)
                    val permText = if (ext.permissions.isEmpty()) "Sin permisos adicionales" else ext.permissions.take(4).joinToString(", ")
                    body.addView(NexoUi.switchRow(
                        activity,
                        ext.name,
                        "v${ext.version} · $permText" + if (!policy.enabled) " · (extensión desactivada)" else "",
                        activeHere
                    ) { allow ->
                        if (allow && !policy.enabled) NxrRuntime.setEnabled(activity, ext.id, true)
                        NxrRuntime.setHostAllowed(activity, ext.id, host, allow)
                        Toastish.show(
                            activity,
                            if (allow) "Acceso concedido a ${ext.name} en $host (recarga para aplicar)"
                            else "Acceso revocado a ${ext.name} en $host"
                        )
                    })
                }
            }
        }
        body.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_extensions,
            "Administrar extensiones",
            "Abrir Nexo Extension Hub, permisos globales y diagnóstico"
        ) {
            sheet.dismiss()
            activity.startActivity(Intent(activity, ExtensionsActivity::class.java))
        })

        // =====================================================================
        // 5. IA (NEXO AI)
        // =====================================================================
        body.addView(NexoUi.section(activity, "IA (Nexo AI)"))
        val aiSettings = NexoAiConfig.read(activity)
        val aiState = if (aiSettings.enabled) "Activa (${aiSettings.provider})" else "Desactivada"
        if (isWeb && host.isNotBlank()) {
            val aiAllowedHere = NexoAiConfig.isHostAllowed(activity, host)
            body.addView(NexoUi.switchRow(
                activity,
                "Permitir análisis de Nexo AI en $host",
                if (aiAllowedHere) "Estado global: $aiState. Solo envía contenido cuando tú lo pides."
                else "Bloqueado en $host: Nexo AI no puede leer esta página.",
                aiAllowedHere
            ) { allowed ->
                NexoAiConfig.setHostAllowed(activity, host, allowed)
                Toastish.show(
                    activity,
                    if (allowed) "Nexo AI permitido en $host" else "Nexo AI bloqueado en $host"
                )
            })
        }
        body.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_menu_ai,
            "Abrir Nexo AI",
            "Estado: $aiState · resumir, analizar y configurar"
        ) {
            sheet.dismiss()
            NexoAiSheet.show(activity)
        })

        // =====================================================================
        // HERRAMIENTAS AVANZADAS DE PESTAÑA (BURNER / PROXY / DNS / GRUPOS)
        // =====================================================================
        body.addView(NexoUi.section(activity, "Red, identidad y grupos"))
        body.addView(NexoUi.actionRow(
            activity,
            R.drawable.ic_tab_tools,
            "Herramientas de pestaña (Burner, Proxy, DNS, Grupos)",
            "Ruta: ${tab.networkState}" + (tab.groupName?.let { " · Grupo: $it" } ?: "")
        ) {
            sheet.dismiss()
            TabTools.show(activity)
        })

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(
                activity,
                NexoSheets.header(
                    activity,
                    if (isWeb) host else "Control de Nexo",
                    if (isWeb) conn.headline else "Página de inicio",
                    badge = conn.badge,
                    badgeTone = conn.tone
                ),
                body
            )
        )
    }
}
