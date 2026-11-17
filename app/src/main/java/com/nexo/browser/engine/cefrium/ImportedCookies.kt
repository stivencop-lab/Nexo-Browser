package com.nexo.browser.engine.cefrium

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Una fila de `moz_cookies` (cookies.sqlite de Gecko) y su traducción a la
 * llamada `CefriumCookieManager.setCookie(url, valor)` de Chromium. Sin Android: se
 * prueba en la JVM.
 */
internal data class ImportedCookie(
    val name: String,
    val value: String,
    val host: String,
    val path: String,
    /** Segundos o milisegundos desde 1970, según la versión del esquema de Gecko. */
    val expiry: Long,
    val isSecure: Boolean,
    val isHttpOnly: Boolean,
    /** nsICookie: 0 None, 1 Lax, 2 Strict; otros valores = sin indicar. */
    val sameSite: Int,
    /** nsICookie.schemeMap: 1 http, 2 https, 4 file. */
    val schemeMap: Int,
    /** Vacío = contexto normal; con valor = privado, Burner o particionado (no se importa). */
    val originAttributes: String
) {
    /** Caducidad en milisegundos (Gecko 136+ la guarda en ms; antes, en segundos). */
    val expiryMillis: Long get() = if (expiry > SECONDS_LIMIT) expiry else expiry * 1000

    /** `(url, cookie)` para `setCookie`, o `null` si no debe importarse. */
    fun toSetCookie(nowMillis: Long): Pair<String, String>? {
        if (originAttributes.isNotEmpty()) return null
        if (expiryMillis <= nowMillis) return null
        if (!validToken(name, allowEmpty = true) || !validValue(value)) return null
        if (name.isEmpty() && value.isEmpty()) return null
        val domainCookie = host.startsWith(".")
        val bareHost = host.trimStart('.').lowercase(Locale.ROOT)
        if (!validHost(bareHost)) return null
        val cookiePath = path.takeIf { it.startsWith("/") && validValue(it) } ?: "/"
        // Prefijos de cookie: el navegador rechazaría la cookie si no se respetan.
        if (name.startsWith("__Secure-") && !isSecure) return null
        if (name.startsWith("__Host-") && (!isSecure || domainCookie || cookiePath != "/")) return null

        val plainHttp = schemeMap == SCHEME_HTTP && !isSecure
        val scheme = if (plainHttp) "http" else "https"
        val urlHost = if (':' in bareHost && !bareHost.startsWith("[")) "[$bareHost]" else bareHost
        val url = "$scheme://$urlHost$cookiePath"

        val header = buildString {
            append(name)
            if (name.isNotEmpty()) append('=')
            append(value)
            if (domainCookie) append("; Domain=").append(bareHost)
            append("; Path=").append(cookiePath)
            append("; Expires=").append(httpDate(expiryMillis.coerceAtMost(nowMillis + MAX_AGE_MILLIS)))
            if (isSecure) append("; Secure")
            if (isHttpOnly) append("; HttpOnly")
            when (sameSite) {
                SAME_SITE_LAX -> append("; SameSite=Lax")
                SAME_SITE_STRICT -> append("; SameSite=Strict")
                SAME_SITE_NONE -> if (isSecure) append("; SameSite=None")
            }
        }
        return url to header
    }

    companion object {
        private const val SECONDS_LIMIT = 100_000_000_000L
        /** Igual que Chromium: ninguna cookie dura más de 400 días. */
        private const val MAX_AGE_MILLIS = 400L * 24 * 3600 * 1000
        private const val SCHEME_HTTP = 1
        private const val SAME_SITE_NONE = 0
        private const val SAME_SITE_LAX = 1
        private const val SAME_SITE_STRICT = 2
        private const val MAX_VALUE = 4096

        private fun validToken(text: String, allowEmpty: Boolean): Boolean =
            (allowEmpty || text.isNotEmpty()) && text.length <= 256 &&
                text.none { it.code < 0x21 || it.code == 0x7f || it == ';' || it == '=' || it == ',' }

        private fun validValue(text: String): Boolean =
            text.length <= MAX_VALUE && text.none { it.code < 0x20 || it.code == 0x7f || it == ';' }

        private fun validHost(host: String): Boolean =
            host.isNotEmpty() && host.length <= 253 &&
                host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == ':' || it == '[' || it == ']' }

        fun httpDate(millis: Long): String =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .format(Date(millis))
    }
}
