package com.nexo.browser

import com.nexo.browser.engine.EngineSession
import java.util.Collections
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
    private enum class State { PREPARING, BLOCKED }
    private val states = Collections.synchronizedMap(WeakHashMap<EngineSession, State>())

    /** Avisos al usuario (sustituible en pruebas JVM, sin Android). */
    internal var notify: (String) -> Unit = { TabManager.notifyMessage(it) }

    /** Aplica la ruta al perfil Burner; false si el motor la rechazó (sustituible en pruebas JVM). */
    internal var applyBurnerRoute: (String, TabNetworkProfile) -> Boolean = { id, route ->
        com.nexo.browser.engine.cefrium.CefriumProfiles.applyRoute(id, route)
    }

    fun blocked(session: EngineSession) = states[session] == State.BLOCKED
    fun preparing(session: EngineSession) = states.containsKey(session)
    fun reserve(session: EngineSession) { states[session] = State.PREPARING }
    fun forget(session: EngineSession) { states.remove(session) }

    fun prepare(tab: BrowserTab, session: EngineSession, ready: () -> Unit) {
        reserve(session)
        val profile = tab.networkProfile
        val burner = tab.burnerId
        val proxied = profile != null && profile.type != "direct"
        val routeReady = when {
            burner == null -> !proxied
            profile?.type == "invalid" -> false
            else -> applyBurnerRoute(burner, profile ?: TabNetworkProfile())
        }
        if (!routeReady) {
            states[session] = State.BLOCKED
            tab.networkState = "Ruta bloqueada"
            tab.isLoading = false
            notify(
                if (burner == null) "El proxy por pestaña solo funciona en pestañas Burner. Nexo no abrió la página por conexión directa: " +
                    "ábrela en una pestaña Burner o quita la ruta de la pestaña."
                else "No se pudo aplicar el proxy de la pestaña. Nexo no abrió la página por conexión directa: revisa la ruta."
            )
            return
        }
        tab.networkState = when {
            proxied -> "Burner por ${if (profile.type == "socks") "SOCKS" else "HTTP"} ${profile.host}:${profile.port}"
            tab.burnerId != null -> "Burner aislado"
            else -> "Conexión directa"
        }
        if (profile != null && (profile.latency > 0 || profile.latitude != null)) {
            notify("El retardo y la ubicación simulada aún no están disponibles en esta versión de Nexo.")
        }
        states.remove(session)
        ready()
    }
}
