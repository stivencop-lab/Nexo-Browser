package com.nexo.browser

import com.nexo.browser.engine.EngineSession
import java.util.WeakHashMap

/**
 * Rutas de red por pestaña.
 *
 * En Chromium el proxy es del perfil: una pestaña Burner tiene el suyo, así
 * que su ruta SOCKS/HTTP se aplica a ese perfil y no toca al resto (sin
 * conexión directa de respaldo: si el proxy no responde, la página no carga).
 * Una pestaña normal o privada con proxy compartiría perfil con otras: no se
 * abre por conexión directa: queda bloqueada con aviso, como cuando la ruta
 * falla. El retardo, la ubicación simulada y la variación de
 * huella llegan con el puente de páginas.
 */
object TabRouting {
    private val pending = WeakHashMap<EngineSession, Boolean>()
    private val failed = WeakHashMap<EngineSession, Boolean>()
    private val awaiting = WeakHashMap<EngineSession, Boolean>()
    private val purge = WeakHashMap<EngineSession, Boolean>()

    /** Avisos al usuario (sustituible en pruebas JVM, sin Android). */
    internal var notify: (String) -> Unit = { TabManager.notifyMessage(it) }

    /** Aplica la ruta al perfil Burner; false si el motor la rechazó (sustituible en pruebas JVM). */
    internal var applyBurnerRoute: (String, TabNetworkProfile) -> Boolean = { id, route ->
        com.nexo.browser.engine.cefrium.CefriumProfiles.applyRoute(id, route)
    }

    fun initialize() = Unit

    fun blocked(session: EngineSession) = failed.containsKey(session)
    fun preparing(session: EngineSession) = pending.containsKey(session) || failed.containsKey(session)
    fun synthetic(session: EngineSession) = preparing(session) || awaiting.containsKey(session)
    fun reserve(session: EngineSession) { pending[session] = true; awaiting[session] = true }

    /** Sin página de arranque: Chromium no necesita preparar la pestaña. */
    fun bootstrap(session: EngineSession, url: String) = false
    fun targetStarted(session: EngineSession) { awaiting.remove(session) }
    fun targetFinished(session: EngineSession) { if (purge.remove(session) == true) session.purgeHistory() }
    fun forget(session: EngineSession) { pending.remove(session); failed.remove(session); awaiting.remove(session); purge.remove(session) }

    fun prepare(tab: BrowserTab, session: EngineSession, preserveHistory: Boolean = false, ready: () -> Unit) {
        val profile = tab.networkProfile
        if (profile == null && tab.burnerId == null) { ready(); return }
        val burner = tab.burnerId
        val proxied = profile != null && profile.type != "direct"
        if (proxied && (burner == null || !applyBurnerRoute(burner, profile!!))) {
            pending.remove(session)
            failed[session] = true
            tab.networkState = "Ruta bloqueada"
            tab.isLoading = false
            notify(
                if (burner == null) "El proxy por pestaña solo funciona en pestañas Burner. Nexo no abrió la página por conexión directa: " +
                    "ábrela en una pestaña Burner o quita la ruta de la pestaña."
                else "No se pudo aplicar el proxy de la pestaña. Nexo no abrió la página por conexión directa: revisa la ruta."
            )
            return
        }
        // Burner sin proxy: su perfil vuelve a conexión directa (por si antes tuvo uno).
        if (burner != null && !proxied) applyBurnerRoute(burner, TabNetworkProfile())
        // Sin página de arranque intermedia: no hay historial sintético que borrar.
        pending.remove(session)
        awaiting.remove(session)
        tab.networkState = when {
            proxied -> "Burner por ${if (profile!!.type == "socks") "SOCKS" else "HTTP"} ${profile.host}:${profile.port}"
            tab.burnerId != null -> "Burner aislado"
            else -> "Conexión directa"
        }
        if (profile != null && (profile.latency > 0 || profile.latitude != null)) {
            notify("El retardo y la ubicación simulada aún no están disponibles en esta versión de Nexo.")
        }
        ready()
    }
}
