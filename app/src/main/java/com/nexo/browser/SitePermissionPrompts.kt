package com.nexo.browser

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.Uri
import com.nexo.browser.engine.EngineSession

/**
 * Preguntas de permisos de sitios, comunes a todos los motores. Cada
 * solicitud se pregunta mostrando el dominio real y queda ligada a su
 * pestaña, al sitio principal y a la navegación en la que se pidió: si el
 * usuario cambia de página o de pestaña se cierra (rechazada) y un
 * "Permitir" tardío ya no vale.
 */
object SitePermissionPrompts {
    private val locationPermissions = setOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    private class Pending(val session: EngineSession, val epoch: Long, val origin: String, val dialog: AlertDialog)
    private val pending = mutableListOf<Pending>()

    private fun topOrigin(session: EngineSession): String? =
        TabManager.tabForSession(session)?.url?.let { PermissionScope.origin(it) }

    /** La pestaña sigue en la misma página que pidió el permiso y es la visible. */
    private fun stillValid(session: EngineSession, epoch: Long, origin: String): Boolean {
        val tab = TabManager.tabForSession(session) ?: return false
        return TabManager.activeSession() === session && tab.navigationEpoch == epoch &&
            PermissionScope.origin(tab.url) == origin
    }

    /** Al abandonar la página o la pestaña: cierra (rechaza) sus solicitudes pendientes. */
    fun cancelFor(session: EngineSession) {
        pending.filter { it.session === session }.forEach { runCatching { it.dialog.dismiss() } }
        pending.removeAll { it.session === session }
    }

    /**
     * Muestra el diálogo registrado; [onAllow]/[onDeny] se llaman una sola
     * vez. Devuelve false si no se pudo preguntar (pestaña oculta o cerrada):
     * entonces el llamante debe rechazar.
     */
    fun ask(session: EngineSession, message: String, onAllow: () -> Unit, onDeny: () -> Unit): Boolean {
        val activity = BrowserHosts.current()?.hostActivity ?: return false
        val tab = TabManager.tabForSession(session) ?: return false
        val origin = topOrigin(session) ?: return false
        if (TabManager.activeSession() !== session) return false
        val epoch = tab.navigationEpoch
        var answered = false
        lateinit var entry: Pending
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Permiso del sitio")
            .setMessage(message)
            .setNegativeButton("Bloquear") { _, _ -> if (!answered) { answered = true; onDeny() } }
            .setPositiveButton("Permitir") { _, _ ->
                if (answered) return@setPositiveButton
                answered = true
                if (stillValid(session, epoch, origin)) onAllow() else onDeny()
            }
            .setOnDismissListener { pending.remove(entry); if (!answered) { answered = true; onDeny() } }
            .create()
        WindowPrivacy.protect(dialog.window, tab.isPrivate)
        entry = Pending(session, epoch, origin, dialog)
        pending += entry
        return runCatching { dialog.show() }.isSuccess.also { shown -> if (!shown) pending.remove(entry) }
    }

    /**
     * Permisos de Android que necesita el sitio (cámara, micrófono,
     * ubicación). Solo para la pestaña visible; la ubicación aproximada
     * basta para la geolocalización web.
     */
    fun ensureAndroidPermissions(session: EngineSession, permissions: List<String>, onResult: (Boolean) -> Unit) {
        val host = BrowserHosts.current()
        if (permissions.isEmpty()) { onResult(true); return }
        if (host == null || TabManager.activeSession() !== session) { onResult(false); return }
        val activity = host.hostActivity
        val missing = permissions.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { onResult(true); return }
        host.requestAndroidPermissions(missing.toTypedArray()) {
            val granted = permissions.filter { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            onResult(granted.size == permissions.size || (permissions.all { it in locationPermissions } && granted.isNotEmpty()))
        }
    }

    fun host(uri: String?): String =
        runCatching { Uri.parse(uri.orEmpty()).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: "Este sitio"
}
