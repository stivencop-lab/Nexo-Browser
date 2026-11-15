package com.nexo.browser

import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.gecko.GeckoEngine
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource

/**
 * Permisos que piden los sitios en Gecko. Sin este delegado GeckoView
 * rechazaba todo: ubicación, cámara y micrófono (videollamadas), contenido
 * protegido (DRM) y almacenamiento persistente. Las preguntas al usuario
 * son comunes ([SitePermissionPrompts]); Gecko recuerda la respuesta para
 * ese sitio. Las notificaciones web se rechazan porque Nexo aún no las muestra.
 */
object SitePermissions : PermissionDelegate {
    private fun wrap(session: GeckoSession): EngineSession? = GeckoEngine.wrapperOf(session)

    private fun host(uri: String?): String = SitePermissionPrompts.host(uri)

    private fun ask(session: GeckoSession, message: String, onAllow: () -> Unit, onDeny: () -> Unit): Boolean {
        val engineSession = wrap(session) ?: return false
        return SitePermissionPrompts.ask(engineSession, message, onAllow, onDeny)
    }

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: PermissionDelegate.Callback
    ) {
        val engineSession = wrap(session)
        if (engineSession == null) { callback.reject(); return }
        SitePermissionPrompts.ensureAndroidPermissions(engineSession, permissions?.filterNotNull().orEmpty()) { ok ->
            if (ok) callback.grant() else callback.reject()
        }
    }

    override fun onContentPermissionRequest(
        session: GeckoSession,
        perm: ContentPermission
    ): GeckoResult<Int>? {
        val description = when (perm.permission) {
            PermissionDelegate.PERMISSION_GEOLOCATION -> "conocer tu ubicación"
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
                "reproducir contenido protegido (DRM), como películas o música de pago"
            PermissionDelegate.PERMISSION_XR -> "usar realidad virtual o aumentada"
            PermissionDelegate.PERMISSION_STORAGE_ACCESS ->
                "usar sus cookies mientras visitas ${host(perm.thirdPartyOrigin)}"
            PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
            PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS ->
                "conectarse a dispositivos de tu red local"

            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE ->
                return GeckoResult.fromValue(
                    if (perm.privateMode) ContentPermission.VALUE_DENY else ContentPermission.VALUE_ALLOW
                )

            // Nexo no muestra notificaciones web: no prometer algo que no funciona.
            PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION ->
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY)

            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ->
                return GeckoResult.fromValue(
                    if (BrowserPrefs.autoplay(AppContext.get()) == BrowserPrefs.AUTOPLAY_ALLOW) ContentPermission.VALUE_ALLOW
                    else ContentPermission.VALUE_DENY
                )
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ->
                return GeckoResult.fromValue(
                    if (BrowserPrefs.autoplay(AppContext.get()) == BrowserPrefs.AUTOPLAY_BLOCK_ALL) ContentPermission.VALUE_DENY
                    else ContentPermission.VALUE_ALLOW
                )

            // Rastreo y demás: comportamiento por defecto de Gecko.
            else -> return GeckoResult.fromValue(ContentPermission.VALUE_PROMPT)
        }

        val result = GeckoResult<Int>()
        val shown = runCatching {
            ask(session, "${host(perm.uri)} quiere $description.",
                onAllow = { result.complete(ContentPermission.VALUE_ALLOW) },
                onDeny = { result.complete(ContentPermission.VALUE_DENY) })
        }.getOrDefault(false)
        if (!shown) result.complete(ContentPermission.VALUE_DENY)
        return result
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out MediaSource>?,
        audio: Array<out MediaSource>?,
        callback: PermissionDelegate.MediaCallback
    ) {
        // Compartir pantalla no está soportado: solo cámara y micrófono.
        val camera = video?.firstOrNull { it.source == MediaSource.SOURCE_CAMERA }
        val microphone = audio?.firstOrNull { it.source == MediaSource.SOURCE_MICROPHONE }
        if (camera == null && microphone == null) {
            callback.reject()
            return
        }

        val what = when {
            camera != null && microphone != null -> "usar tu cámara y tu micrófono"
            camera != null -> "usar tu cámara"
            else -> "usar tu micrófono"
        }

        val shown = runCatching {
            ask(session, "${host(uri)} quiere $what.",
                onAllow = { callback.grant(camera, microphone) },
                onDeny = { callback.reject() })
        }.getOrDefault(false)
        if (!shown) callback.reject()
    }
}
