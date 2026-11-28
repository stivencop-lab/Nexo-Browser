package com.nexo.browser.engine.cefrium

import com.cefrium.Cefrium
import com.cefrium.CefriumProfile
import com.cefrium.CefriumProxyMode
import com.cefrium.CefriumWebRtcIpPolicy
import com.nexo.browser.TabManager
import com.nexo.browser.TabNetworkProfile
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private const val PROXY_NOTICE_COOLDOWN_MS = 5000L

    private val activeBurners = ConcurrentHashMap.newKeySet<String>()
    private val proxiedBurners = ConcurrentHashMap<String, Boolean>()
    private val lastProxyNoticeAt = ConcurrentHashMap<String, Long>()
    @Volatile private var defaultWebRtcPolicy: CefriumWebRtcIpPolicy = CefriumWebRtcIpPolicy.DEFAULT

    fun forSession(isPrivate: Boolean, contextId: String?): CefriumProfile = when {
        contextId != null -> CefriumProfile.get(burnerName(contextId)).also { profile ->
            activeBurners.add(contextId)
            val policy = if (proxiedBurners[contextId] == true) {
                CefriumWebRtcIpPolicy.DISABLE_NON_PROXIED_UDP
            } else {
                defaultWebRtcPolicy
            }
            runCatching { profile.setWebRtcIpHandlingPolicy(policy) }
        }
        isPrivate -> CefriumProfile.incognito()
        else -> CefriumProfile.getDefault()
    }

    fun burnerName(contextId: String): String = BURNER_PREFIX + sanitize(contextId)

    /** Propaga la política WebRTC del usuario a los perfiles Burner sin proxy (los proxied siempre bloquean UDP directo). */
    fun syncDefaultWebRtcPolicy(policy: CefriumWebRtcIpPolicy) {
        defaultWebRtcPolicy = policy
        activeBurners.forEach { contextId ->
            val effective = if (proxiedBurners[contextId] == true) {
                CefriumWebRtcIpPolicy.DISABLE_NON_PROXIED_UDP
            } else {
                policy
            }
            runCatching { CefriumProfile.get(burnerName(contextId)).setWebRtcIpHandlingPolicy(effective) }
        }
    }

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
        activeBurners.remove(contextId)
        proxiedBurners.remove(contextId)
        lastProxyNoticeAt.remove(contextId)
        runCatching {
            val profile = CefriumProfile.get(burnerName(contextId))
            profile.setProxyErrorListener(null)
            wipe(profile) {}
        }
    }

    /** Cookies, almacenamiento de cada sitio y service workers de [profile]. */
    fun wipe(profile: CefriumProfile, done: (Boolean) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val settled = java.util.concurrent.atomic.AtomicBoolean()
        val timeout = Runnable { if (settled.compareAndSet(false, true)) done(false) }
        fun finish(ok: Boolean) {
            if (!settled.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            main.post { done(ok) }
        }
        main.postDelayed(timeout, 15_000L)
        runCatching {
            profile.cookieManager.removeAllCookies {
                runCatching {
                    profile.listSiteData { sites ->
                        if (sites.isEmpty()) {
                            finish(true)
                            return@listSiteData
                        }
                        val remaining = java.util.concurrent.atomic.AtomicInteger(sites.size)
                        sites.forEach { site ->
                            runCatching {
                                profile.clearSiteData(site.site) {
                                    if (remaining.decrementAndGet() == 0) finish(true)
                                }
                            }.onFailure { finish(false) }
                        }
                    }
                }.onFailure { finish(false) }
            }
        }.onFailure { finish(false) }
    }

    /**
     * Ruta de red de una pestaña Burner: proxy fijo, sin conexión directa de
     * respaldo (si el proxy no responde, la página no carga). Devuelve false si
     * Cefrium rechazó la ruta.
     */
    fun applyRoute(contextId: String, route: TabNetworkProfile): Boolean {
        val profile = runCatching { CefriumProfile.get(burnerName(contextId)) }.getOrNull() ?: return false
        val proxied = route.type != "direct"
        activeBurners.add(contextId)
        val ok = runCatching {
            if (!proxied) profile.clearProxy()
            else profile.setProxy(CefriumProxyMode.FIXED, proxyServer(route))
        }.getOrDefault(false)
        if (!ok) return false
        proxiedBurners[contextId] = proxied
        // WebRTC no pasa por el proxy HTTP/SOCKS: bloquear UDP no proxeado mientras haya ruta activa.
        val webrtc = if (proxied) CefriumWebRtcIpPolicy.DISABLE_NON_PROXIED_UDP else defaultWebRtcPolicy
        runCatching { profile.setWebRtcIpHandlingPolicy(webrtc) }
        if (proxied) {
            runCatching {
                profile.setProxyErrorListener { browser, url, error, mainFrame ->
                    if (!mainFrame) return@setProxyErrorListener
                    val host = url?.let { com.nexo.browser.shield.UrlParts.host(it) } ?: "?"
                    EngineDiagnostics.log("error de proxy Burner ($error) en $host")
                    val session = CefriumEngine.sessionOf(browser)
                    TabManager.allTabs().firstOrNull { tab ->
                        (session != null && tab.session === session) || tab.burnerId == contextId
                    }?.let { tab ->
                        tab.networkState = "Proxy sin respuesta ($error)"
                    }
                    val now = System.currentTimeMillis()
                    val last = lastProxyNoticeAt[contextId] ?: 0L
                    if (now - last >= PROXY_NOTICE_COOLDOWN_MS) {
                        lastProxyNoticeAt[contextId] = now
                        TabManager.notifyMessage(describeProxyError(error))
                    }
                }
            }
        } else {
            lastProxyNoticeAt.remove(contextId)
            runCatching { profile.setProxyErrorListener(null) }
        }
        return true
    }

    /** Mensaje claro y accionable para los códigos net_error de proxy de Chromium/Cefrium. */
    fun describeProxyError(errorCode: Int): String = when (errorCode) {
        -130 -> "No se pudo conectar al proxy de esta pestaña Burner (error -130). Comprueba que Tor o el proxy estén activos."
        -120 -> "El proxy SOCKS no respondió o rechazó la conexión (error -120). Revisa host y puerto."
        -121 -> "El proxy SOCKS no pudo alcanzar el sitio de destino (error -121)."
        -111 -> "Falló el túnel HTTPS a través del proxy HTTP (error -111)."
        -115, -127 -> "El proxy exige autenticación no soportada o no configurada (error $errorCode)."
        -136 -> "La configuración del proxy no es válida (error -136)."
        else -> "El proxy de esta pestaña Burner falló (error $errorCode). Nexo bloqueó la conexión directa."
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
