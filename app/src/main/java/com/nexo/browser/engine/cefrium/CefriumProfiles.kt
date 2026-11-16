package com.nexo.browser.engine.cefrium

import com.cefrium.Cefrium
import com.cefrium.CefriumProfile
import com.cefrium.CefriumProxyMode
import com.nexo.browser.TabNetworkProfile
import java.io.File

/**
 * Perfiles de Cefrium de Nexo:
 * - normal: el perfil por defecto de Cefrium;
 * - privado: el perfil fuera de registro, que solo vive en memoria y Cefrium
 *   vacía al cerrar el último navegador privado (no queda nada en disco);
 * - Burner: un perfil con nombre por identidad, vaciado al cerrarla y borrado
 *   del disco en el siguiente arranque, antes de abrir ninguno.
 *
 * El proxy de Cefrium es del perfil: una pestaña Burner con ruta SOCKS/HTTP lo
 * usa sin afectar al resto ([applyRoute]).
 */
internal object CefriumProfiles {
    private const val BURNER_PREFIX = "nexo_burner_"
    private const val PROFILE_DIR_PREFIX = "profile-"

    fun forSession(isPrivate: Boolean, contextId: String?): CefriumProfile = when {
        contextId != null -> CefriumProfile.get(burnerName(contextId))
        isPrivate -> CefriumProfile.incognito()
        else -> CefriumProfile.getDefault()
    }

    fun burnerName(contextId: String): String = BURNER_PREFIX + sanitize(contextId)

    /** Arranque: borra los perfiles Burner de sesiones anteriores. */
    fun purgeStale() {
        val root = runCatching { Cefrium.getDataDirectory() }.getOrNull() ?: return
        root.listFiles()?.asSequence()
            ?.map(File::getName)
            ?.filter { it.startsWith(PROFILE_DIR_PREFIX + BURNER_PREFIX) }
            ?.forEach { dir -> runCatching { CefriumProfile.delete(dir.removePrefix(PROFILE_DIR_PREFIX)) } }
    }

    /** Se cerró la última pestaña privada: Cefrium ya descarta su sesión; las cookies, por si acaso. */
    fun endPrivateSession() {
        runCatching { CefriumProfile.incognito().cookieManager.removeAllCookies(null) }
    }

    /** Burner cerrado: vaciar el perfil de esa identidad (el directorio se borra al arrancar). */
    fun wipeContext(contextId: String) {
        runCatching { wipe(CefriumProfile.get(burnerName(contextId))) {} }
    }

    /** Cookies, almacenamiento de cada sitio y service workers de [profile]. */
    fun wipe(profile: CefriumProfile, done: () -> Unit) {
        val result = runCatching {
            profile.cookieManager.removeAllCookies {
                profile.listSiteData { sites ->
                    if (sites.isEmpty()) { done(); return@listSiteData }
                    var left = sites.size
                    sites.forEach { site -> profile.clearSiteData(site.site) { if (--left == 0) done() } }
                }
            }
        }
        if (result.isFailure) done()
    }

    /**
     * Ruta de red de una pestaña Burner: proxy fijo, sin conexión directa de
     * respaldo (si el proxy no responde, la página no carga). Devuelve false si
     * Cefrium rechazó la ruta.
     */
    fun applyRoute(contextId: String, route: TabNetworkProfile): Boolean {
        val profile = runCatching { CefriumProfile.get(burnerName(contextId)) }.getOrNull() ?: return false
        return runCatching {
            if (route.type == "direct") profile.clearProxy()
            else profile.setProxy(CefriumProxyMode.FIXED, proxyServer(route))
        }.getOrDefault(false)
    }

    /** `socks5://host:puerto` o `http://host:puerto`, con corchetes para IPv6. */
    fun proxyServer(route: TabNetworkProfile): String {
        val scheme = if (route.type == "socks") "socks5" else "http"
        val host = if (':' in route.host && !route.host.startsWith("[")) "[${route.host}]" else route.host
        return "$scheme://$host:${route.port}"
    }

    private fun sanitize(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64 - BURNER_PREFIX.length).ifEmpty { "x" }
}
