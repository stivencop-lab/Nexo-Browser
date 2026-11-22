package com.nexo.browser

/** Diagnostics keep exception/stack information, never URL userinfo, paths or query data. */
object CrashReportRedactor {
    private val secretQuery = Regex("""(?i)(token|access[_-]?token|refresh[_-]?token|auth|authorization|session|secret|api[_-]?key|password)=([^&\s]+)""")
    private val authorization = Regex("""(?i)((?:authorization|x-api-key|x-goog-api-key)\s*[:=]\s*)(?:(?:bearer|basic|token|deepl-auth-key)\s+)?[^\s,;]+""")
    private val webUrl = Regex("""(?:https?|wss?)://[^\s<>"')]+""", RegexOption.IGNORE_CASE)
    private val appPath = Regex("""/(?:data/(?:data|user(?:_de)?/\d+)|storage/emulated/\d+/Android/data)/[^\s:]+""")

    fun redact(raw: String): String {
        val urlsRemoved = webUrl.replace(raw, "<url-redacted>")
        val headersRemoved = authorization.replace(urlsRemoved) { "${it.groupValues[1]}<redacted>" }
        val secretsRemoved = secretQuery.replace(headersRemoved) { "${it.groupValues[1]}=<redacted>" }
        return appPath.replace(secretsRemoved, "<app-path-redacted>")
    }
}
