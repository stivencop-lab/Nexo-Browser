package com.nexo.browser.ai

import android.content.Context
import com.nexo.browser.CaptionProtocol
import com.nexo.browser.SecretStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configuración segura y registro local saneado de Nexo AI.
 *
 * - Usa `SharedPreferences` propio (`nexo_ai`) para no interferir con `TransferSchema`.
 * - Las claves API se almacenan únicamente cifradas en [SecretStore] (Android Keystore).
 * - Desactivado por defecto y con consentimiento explícito requerido antes de enviar contexto.
 * - Registro local saneado que nunca guarda claves API ni cabeceras de autorización.
 */
object NexoAiConfig {
    private const val PREFS = "nexo_ai"
    private const val SECRET_API_KEY = "nexo_ai_api_key"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CONSENT = "consent"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_MODEL = "model"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_TIMEOUT_SEC = "timeout_sec"
    private const val KEY_MAX_CONTEXT = "max_context_chars"
    private const val KEY_ALLOW_PRIVATE = "allow_private"
    private const val KEY_LOG = "action_log"

    private const val MAX_LOG_ENTRIES = 30

    val PROVIDERS: List<Pair<String, String>> = CaptionProtocol.AI_PROVIDERS

    data class Settings(
        val enabled: Boolean = false,
        val consentAccepted: Boolean = false,
        val provider: String = "openai",
        val model: String = "",
        val customEndpoint: String = "",
        val timeoutSeconds: Int = 35,
        val maxContextChars: Int = 8000,
        val allowPrivateTabs: Boolean = false
    ) {
        fun effectiveEndpoint(): String = when (provider) {
            "custom" -> customEndpoint.trim().trimEnd('/')
            else -> CaptionProtocol.AI_ENDPOINTS[provider].orEmpty().trimEnd('/')
        }

        fun effectiveModel(): String = model.trim().ifBlank { CaptionProtocol.AI_MODELS[provider].orEmpty() }
    }

    data class LogEntry(
        val timestampMs: Long,
        val action: String,
        val host: String,
        val status: String
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            enabled = p.getBoolean(KEY_ENABLED, false),
            consentAccepted = p.getBoolean(KEY_CONSENT, false),
            provider = p.getString(KEY_PROVIDER, "openai") ?: "openai",
            model = p.getString(KEY_MODEL, "") ?: "",
            customEndpoint = p.getString(KEY_ENDPOINT, "") ?: "",
            timeoutSeconds = clampTimeout(p.getInt(KEY_TIMEOUT_SEC, 35)),
            maxContextChars = clampContextChars(p.getInt(KEY_MAX_CONTEXT, 8000)),
            allowPrivateTabs = p.getBoolean(KEY_ALLOW_PRIVATE, false)
        )
    }

    fun save(context: Context, settings: Settings) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putBoolean(KEY_CONSENT, settings.consentAccepted)
            .putString(KEY_PROVIDER, settings.provider.ifBlank { "openai" })
            .putString(KEY_MODEL, settings.model.trim().take(120))
            .putString(KEY_ENDPOINT, settings.customEndpoint.trim().take(300))
            .putInt(KEY_TIMEOUT_SEC, clampTimeout(settings.timeoutSeconds))
            .putInt(KEY_MAX_CONTEXT, clampContextChars(settings.maxContextChars))
            .putBoolean(KEY_ALLOW_PRIVATE, settings.allowPrivateTabs)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setConsentAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONSENT, accepted).apply()
    }

    fun apiKey(context: Context): String = SecretStore.get(context, SECRET_API_KEY)

    fun setApiKey(context: Context, key: String) = SecretStore.put(context, SECRET_API_KEY, key)

    fun hasApiKey(context: Context): Boolean = SecretStore.has(context, SECRET_API_KEY) && apiKey(context).isNotBlank()

    fun clampTimeout(sec: Int): Int = sec.coerceIn(10, 90)

    fun clampContextChars(chars: Int): Int = chars.coerceIn(2000, 16000)

    /** Valida endpoint y clave de forma pura (probado en la JVM). */
    fun validate(settings: Settings, apiKey: String): String? {
        val ep = settings.effectiveEndpoint()
        if (ep.isBlank() || !CaptionProtocol.isSafeKeyedEndpoint(ep)) {
            return "El endpoint debe usar HTTPS (o http:// solo en localhost/127.0.0.1) sin credenciales en la URL."
        }
        if (settings.provider != "custom" && apiKey.isBlank()) {
            return "Configura tu clave API (se guarda cifrada en Android Keystore)."
        }
        if (settings.effectiveModel().isBlank()) {
            return "Indica un nombre de modelo válido."
        }
        return null
    }

    /** Limpia cualquier patrón que parezca una clave API o token Bearer antes de registrar en disco. */
    fun sanitizeLogText(raw: String): String {
        return raw
            .replace(Regex("(sk-[A-Za-z0-9_-]{8,}|Bearer\\s+[A-Za-z0-9._-]+|AIza[0-9A-Za-z_-]{16,})"), "[REDACTADO]")
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()
            .take(180)
    }

    fun appendLog(context: Context, action: String, host: String, status: String) {
        val current = readLog(context).toMutableList()
        current.add(
            LogEntry(
                timestampMs = System.currentTimeMillis(),
                action = sanitizeLogText(action),
                host = sanitizeLogText(host),
                status = sanitizeLogText(status)
            )
        )
        while (current.size > MAX_LOG_ENTRIES) current.removeAt(0)
        val arr = JSONArray()
        for (e in current) {
            arr.put(
                JSONObject()
                    .put("t", e.timestampMs)
                    .put("a", e.action)
                    .put("h", e.host)
                    .put("s", e.status)
            )
        }
        prefs(context).edit().putString(KEY_LOG, arr.toString()).apply()
    }

    fun readLog(context: Context): List<LogEntry> {
        val raw = prefs(context).getString(KEY_LOG, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                LogEntry(o.optLong("t"), o.optString("a"), o.optString("h"), o.optString("s"))
            }
        }.getOrDefault(emptyList())
    }

    fun clearLog(context: Context) {
        prefs(context).edit().remove(KEY_LOG).apply()
    }
}
