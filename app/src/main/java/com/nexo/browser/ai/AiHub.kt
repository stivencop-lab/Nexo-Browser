package com.nexo.browser.ai

import android.content.Context
import com.nexo.browser.BrowserPrefs
import com.nexo.browser.CaptionProtocol
import org.json.JSONObject

/**
 * «Una API para todo»: una sola conexión de IA (la de Nexo AI) para el asistente, Nexo Reader,
 * la traducción de páginas, los subtítulos en vivo y la IA de PhantomShield.
 *
 * - Activado por defecto. Cada función sigue teniendo su propio interruptor de uso; aquí solo se
 *   decide **con qué proveedor, modelo y clave** habla.
 * - La clave maestra vive cifrada en Android Keystore ([SecretStore]); en modo unificado nunca se
 *   copia al almacén de PhantomShield (que es JSON en disco).
 * - Si la conexión maestra no está configurada, cada función usa sus ajustes propios, como antes.
 */
object AiHub {
    data class Connection(val provider: String, val endpoint: String, val model: String, val key: String) {
        /** Endpoint completo (también para proveedores con dirección predefinida). */
        val resolvedEndpoint: String
            get() = if (provider == "custom") endpoint.trim().trimEnd('/')
            else CaptionProtocol.AI_ENDPOINTS[provider].orEmpty().trimEnd('/')
    }

    fun unified(context: Context): Boolean = BrowserPrefs.unifiedAi(context)

    /** Conexión de Nexo AI si está completa y es segura (HTTPS o localhost, con clave si hace falta). */
    fun master(context: Context): Connection? {
        val settings = NexoAiConfig.read(context)
        val key = NexoAiConfig.apiKey(context)
        if (NexoAiConfig.validate(settings, key) != null) return null
        return Connection(
            provider = settings.provider,
            endpoint = if (settings.provider == "custom") settings.effectiveEndpoint() else "",
            model = settings.effectiveModel(),
            key = key
        )
    }

    /** Conexión que usa una función concreta: la maestra en modo unificado, o [ownSettings]. */
    fun connectionFor(context: Context, ownSettings: () -> Connection?): Connection? =
        (if (unified(context)) master(context) else null) ?: ownSettings()

    /** Traducción de páginas y subtítulos (modelo 1). */
    fun translation(context: Context): Connection? = connectionFor(context) {
        val provider = BrowserPrefs.aiTranslateProvider(context)
        val key = BrowserPrefs.aiTranslateKey(context)
        if (key.isBlank() && provider != "custom") null
        else Connection(provider, BrowserPrefs.aiTranslateEndpoint(context), BrowserPrefs.aiTranslateModel(context), key)
    }

    /** Si la traducción por IA usa la conexión maestra (para explicarlo en la interfaz). */
    fun translationUsesMaster(context: Context): Boolean = unified(context) && master(context) != null

    /**
     * PhantomShield guarda `aiConfig` en su almacén JSON. En modo unificado se le presenta la
     * conexión maestra al leer (sin persistirla) y se evita guardar la clave al escribir.
     */
    fun overlayShieldRead(context: Context?, values: JSONObject): JSONObject {
        if (context == null || !unified(context)) return values
        return overlayShield(values, master(context))
    }

    fun stripShieldWrite(context: Context?, values: JSONObject): JSONObject {
        if (context == null || !unified(context)) return values
        return stripShield(values, master(context))
    }

    /** Puro (probado en JVM): presenta [m] como conexión de la IA de PhantomShield. */
    internal fun overlayShield(values: JSONObject, m: Connection?): JSONObject {
        val config = values.optJSONObject("aiConfig") ?: return values
        if (m == null) return values
        val provider = if (m.provider == "openai" || m.provider == "claude") m.provider else "custom"
        val merged = JSONObject(config.toString())
            .put("provider", provider)
            .put("endpoint", m.resolvedEndpoint)
            .put("model", m.model)
            .put("apiKey", m.key)
        return JSONObject(values.toString()).put("aiConfig", merged)
    }

    /** Puro: nunca persiste en el JSON de PhantomShield la clave maestra cifrada en Keystore. */
    internal fun stripShield(values: JSONObject, m: Connection?): JSONObject {
        val config = values.optJSONObject("aiConfig") ?: return values
        if (m == null || m.key.isEmpty() || config.optString("apiKey") != m.key) return values
        val clean = JSONObject(config.toString()).put("apiKey", "")
        return JSONObject(values.toString()).put("aiConfig", clean)
    }
}
