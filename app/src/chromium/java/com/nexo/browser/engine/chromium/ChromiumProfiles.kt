package com.nexo.browser.engine.chromium

import android.annotation.SuppressLint
import android.webkit.CookieManager
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.security.SecureRandom

/**
 * Perfiles de WebView de Nexo: el perfil normal ("Default"), uno privado que
 * comparten las pestañas privadas de una misma sesión y uno por identidad
 * Burner.
 *
 * WebView no permite borrar un perfil que ya se cargó en el proceso, así que
 * el privado rota: al cerrar la última pestaña privada se vacían sus cookies
 * y su almacenamiento, la siguiente sesión privada usa un perfil nuevo y los
 * antiguos se borran en el siguiente arranque, antes de cargarlos.
 */
@SuppressLint("RequiresFeature")
internal object ChromiumProfiles {
    private const val PRIVATE_PREFIX = "nexo_private_"
    private const val BURNER_PREFIX = "nexo_burner_"

    @Volatile private var privateName: String? = null

    /** Cookies por perfil, guardadas en el hilo principal para las descargas en segundo plano. */
    private val cookieManagers = java.util.concurrent.ConcurrentHashMap<String, CookieManager>()

    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Arranque: borra los perfiles privados y Burner que quedaron de sesiones anteriores. */
    fun purgeStale() {
        if (!supported()) return
        val store = ProfileStore.getInstance()
        store.allProfileNames
            .filter { it != Profile.DEFAULT_PROFILE_NAME }
            .forEach { name -> runCatching { store.deleteProfile(name) } }
    }

    /** Nombre del perfil de una pestaña; `null` para el perfil normal. */
    fun nameFor(isPrivate: Boolean, contextId: String?): String? {
        if (!isPrivate && contextId == null) return null
        check(supported()) { "Este WebView no admite perfiles aislados." }
        return when {
            contextId != null -> BURNER_PREFIX + sanitize(contextId)
            else -> privateName ?: (PRIVATE_PREFIX + randomSuffix()).also { privateName = it }
        }
    }

    /** Debe llamarse en el hilo principal. */
    fun profile(name: String): Profile =
        ProfileStore.getInstance().getOrCreateProfile(name).also { profile ->
            cookieManagers[name] = profile.cookieManager
        }

    /**
     * Cookies del perfil normal (`null`) o de otro perfil ya cargado. Nunca
     * devuelve las del perfil normal para un perfil aislado que ya no existe.
     */
    fun cookieManager(name: String?): CookieManager? =
        if (name == null) CookieManager.getInstance() else cookieManagers[name]

    /** Cookies de las descargas: privadas si la pestaña lo es y su perfil existe. */
    fun cookieManagerForDownloads(isPrivate: Boolean): CookieManager? =
        if (isPrivate) privateName?.let { cookieManagers[it] } else CookieManager.getInstance()

    /** Se cerró la última pestaña privada: vaciar su perfil y usar otro la próxima vez. */
    fun endPrivateSession() {
        val name = privateName ?: return
        privateName = null
        wipe(name)
    }

    /** Burner cerrado: vaciar el perfil de esa identidad. */
    fun wipeContext(contextId: String) {
        wipe(BURNER_PREFIX + sanitize(contextId))
    }

    private fun wipe(name: String) {
        if (!supported()) return
        val profile = runCatching { ProfileStore.getInstance().getProfile(name) }.getOrNull() ?: return
        runCatching {
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
        }
        runCatching { profile.webStorage.deleteAllData() }
        runCatching { profile.geolocationPermissions.clearAll() }
        cookieManagers.remove(name)
    }

    private fun sanitize(value: String): String =
        value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "x" }

    private fun randomSuffix(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
