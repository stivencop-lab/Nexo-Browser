package com.nexo.browser

import android.Manifest
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import com.nexo.browser.engine.EngineSession

/**
 * Permisos que piden los sitios en WebView: cámara, micrófono, contenido
 * protegido (DRM) y ubicación. Se pregunta siempre mostrando el dominio
 * real ([SitePermissionPrompts]) y después se piden los permisos de Android
 * que haga falta. MIDI y el resto se rechazan.
 */
object SitePermissions {
    fun onPermissionRequest(session: EngineSession, request: PermissionRequest) {
        val wanted = request.resources.toSet()
        val camera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted
        val microphone = PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted
        val drm = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in wanted
        val granted = buildList {
            if (camera) add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            if (microphone) add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            if (drm) add(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
        }
        if (granted.isEmpty()) {
            request.deny()
            return
        }
        val what = listOfNotNull(
            "usar tu cámara".takeIf { camera },
            "usar tu micrófono".takeIf { microphone },
            "reproducir contenido protegido (DRM), como películas o música de pago".takeIf { drm }
        ).joinToString(" y ")
        val host = SitePermissionPrompts.host(request.origin?.toString())
        var answered = false
        fun deny() { if (!answered) { answered = true; runCatching { request.deny() } } }
        val shown = SitePermissionPrompts.ask(session, "$host quiere $what.",
            onAllow = {
                val android = buildList {
                    if (camera) add(Manifest.permission.CAMERA)
                    if (microphone) { add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.MODIFY_AUDIO_SETTINGS) }
                }
                SitePermissionPrompts.ensureAndroidPermissions(session, android) { ok ->
                    if (answered) return@ensureAndroidPermissions
                    answered = true
                    runCatching { if (ok) request.grant(granted.toTypedArray()) else request.deny() }
                }
            },
            onDeny = { deny() })
        if (!shown) deny()
    }

    /** La página retiró la petición: cerrar el diálogo pendiente de esa pestaña. */
    fun onPermissionRequestCanceled(session: EngineSession) {
        SitePermissionPrompts.cancelFor(session)
    }

    fun onGeolocation(session: EngineSession, origin: String, callback: GeolocationPermissions.Callback) {
        var answered = false
        fun answer(allow: Boolean) {
            if (answered) return
            answered = true
            runCatching { callback.invoke(origin, allow, false) }
        }
        val shown = SitePermissionPrompts.ask(session, "${SitePermissionPrompts.host(origin)} quiere conocer tu ubicación.",
            onAllow = {
                SitePermissionPrompts.ensureAndroidPermissions(
                    session,
                    listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                ) { ok -> answer(ok) }
            },
            onDeny = { answer(false) })
        if (!shown) answer(false)
    }
}
