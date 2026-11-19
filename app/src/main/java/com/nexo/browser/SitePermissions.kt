package com.nexo.browser

import android.Manifest
import com.cefrium.CefriumPermission
import com.cefrium.CefriumPermissionRequest
import com.nexo.browser.engine.EngineSession

/**
 * Permisos que piden los sitios: cámara, micrófono y ubicación. Se pregunta
 * siempre mostrando el dominio real ([SitePermissionPrompts]) y después se
 * piden los permisos de Android que haga falta.
 *
 * Chromium responde la petición entera (todos sus permisos a la vez): si trae
 * alguno que Nexo no ofrece (notificaciones, portapapeles, NFC…), se rechaza
 * completa para no concederlo de rebote. El contenido protegido (DRM) no pasa
 * por aquí: Chromium lo gestiona solo.
 */
object SitePermissions {
    private val OFFERED = setOf(CefriumPermission.CAMERA, CefriumPermission.MICROPHONE, CefriumPermission.GEOLOCATION)

    fun onPermissionRequest(session: EngineSession, request: CefriumPermissionRequest) {
        val wanted = request.permissions
        if (wanted.isEmpty() || wanted.any { it !in OFFERED }) {
            runCatching { request.deny() }
            return
        }
        val appContext = AppContext.getOrNull()
        val origin = request.origin
        if (appContext != null && !origin.isNullOrBlank()) {
            val decisions = wanted.map { SitePermissionStore.get(appContext, origin, it) }
            if (decisions.any { it == SitePermissionStore.Decision.BLOCK }) {
                runCatching { request.deny() }
                return
            }
            if (decisions.all { it == SitePermissionStore.Decision.ALLOW }) {
                val android = buildList {
                    if (CefriumPermission.CAMERA in wanted) add(Manifest.permission.CAMERA)
                    if (CefriumPermission.MICROPHONE in wanted) {
                        add(Manifest.permission.RECORD_AUDIO)
                        add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
                    }
                    if (CefriumPermission.GEOLOCATION in wanted) {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                }
                SitePermissionPrompts.ensureAndroidPermissions(session, android) { ok ->
                    runCatching { if (ok) request.grant() else request.deny() }
                }
                return
            }
        }
        val camera = CefriumPermission.CAMERA in wanted
        val microphone = CefriumPermission.MICROPHONE in wanted
        val location = CefriumPermission.GEOLOCATION in wanted
        val what = listOfNotNull(
            "usar tu cámara".takeIf { camera },
            "usar tu micrófono".takeIf { microphone },
            "conocer tu ubicación".takeIf { location }
        ).joinToString(" y ")
        val host = SitePermissionPrompts.host(origin)
        var answered = false
        fun deny() { if (!answered) { answered = true; runCatching { request.deny() } } }
        val shown = SitePermissionPrompts.ask(session, "$host quiere $what.",
            onAllow = {
                val android = buildList {
                    if (camera) add(Manifest.permission.CAMERA)
                    if (microphone) { add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.MODIFY_AUDIO_SETTINGS) }
                    if (location) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
                }
                SitePermissionPrompts.ensureAndroidPermissions(session, android) { ok ->
                    if (answered) return@ensureAndroidPermissions
                    answered = true
                    runCatching { if (ok) request.grant() else request.deny() }
                }
            },
            onDeny = { deny() })
        if (!shown) deny()
    }

    /** La página retiró la petición: cerrar el diálogo pendiente de esa pestaña. */
    fun onPermissionRequestCanceled(session: EngineSession) {
        SitePermissionPrompts.cancelFor(session)
    }
}
