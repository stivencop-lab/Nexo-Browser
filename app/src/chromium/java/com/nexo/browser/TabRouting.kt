package com.nexo.browser

import com.nexo.browser.engine.EngineSession
import java.util.WeakHashMap

/**
 * Rutas de red por pestaña en la variante Chromium.
 *
 * WebView solo admite un proxy para toda la app (decisión: proxy global, en
 * una fase posterior). Mientras tanto, una pestaña con proxy SOCKS/HTTP no se
 * abre por conexión directa: queda bloqueada con aviso, igual que en Gecko
 * cuando la ruta falla. Burner sin proxy abre aislado por su perfil; el
 * retardo, la ubicación simulada y la variación de huella llegan con el
 * puente de páginas.
 */
object TabRouting {
    private val pending = WeakHashMap<EngineSession, Boolean>()
    private val failed = WeakHashMap<EngineSession, Boolean>()
    private val awaiting = WeakHashMap<EngineSession, Boolean>()
    private val purge = WeakHashMap<EngineSession, Boolean>()

    /** Avisos al usuario (sustituible en pruebas JVM, sin Android). */
    internal var notify: (String) -> Unit = { TabManager.notifyMessage(it) }

    fun initialize() = Unit

    fun blocked(session: EngineSession) = failed.containsKey(session)
    fun preparing(session: EngineSession) = pending.containsKey(session) || failed.containsKey(session)
    fun synthetic(session: EngineSession) = preparing(session) || awaiting.containsKey(session)
    fun reserve(session: EngineSession) { pending[session] = true; awaiting[session] = true }

    /** Sin página de arranque: WebView no necesita preparar la pestaña. */
    fun bootstrap(session: EngineSession, url: String) = false
    fun targetStarted(session: EngineSession) { awaiting.remove(session) }
    fun targetFinished(session: EngineSession) { if (purge.remove(session) == true) session.purgeHistory() }
    fun forget(session: EngineSession) { pending.remove(session); failed.remove(session); awaiting.remove(session); purge.remove(session) }

    fun prepare(tab: BrowserTab, session: EngineSession, preserveHistory: Boolean = false, ready: () -> Unit) {
        val profile = tab.networkProfile
        if (profile == null && tab.burnerId == null) { ready(); return }
        if (profile != null && profile.type != "direct") {
            pending.remove(session)
            failed[session] = true
            tab.networkState = "Ruta bloqueada"
            tab.isLoading = false
            notify(
                "La versión Chromium aún no admite proxy por pestaña. Nexo no abrió la página por conexión directa: " +
                    "quita la ruta de la pestaña para continuar."
            )
            return
        }
        // Sin página de arranque intermedia: no hay historial sintético que borrar.
        pending.remove(session)
        awaiting.remove(session)
        tab.networkState = if (tab.burnerId != null) "Burner aislado" else "Conexión directa"
        if (profile != null && (profile.latency > 0 || profile.latitude != null)) {
            notify("El retardo y la ubicación simulada aún no están disponibles en la versión Chromium.")
        }
        ready()
    }
}
