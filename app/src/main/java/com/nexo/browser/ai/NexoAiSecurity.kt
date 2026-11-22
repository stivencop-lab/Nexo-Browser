package com.nexo.browser.ai

import com.nexo.browser.shield.MalwareSignals
import java.net.URI
import java.util.Locale

/**
 * Capa de seguridad defensiva para Nexo AI y su Agente de Navegador (pura JVM, sin dependencias Android):
 *
 * 1. **Defensa contra Prompt Injection indirecto**:
 *    - Elimina caracteres invisibles/BiDi/Unicode Tags (`U+E0000..U+E007F`, zero-width) usados para
 *      ocultar instrucciones maliciosas dentro de páginas web.
 *    - Neutraliza etiquetas de escape de delimitador (`</UNTRUSTED_PAGE_CONTEXT>`, `</UNTRUSTED_TOOL_OUTPUT>`,
 *      `<system>`, `<|im_start|>`, `[INST]`) y desarma bloques falsos ```` ```nexo-tool ```` o ```` ```nexo-actions ````
 *      incrustados dentro del texto de una web.
 *    - Detecta patrones de inyección (en español e inglés) y añade un aviso explícito al modelo.
 * 2. **Firewall de URLs, Anti-SSRF y Anti-Exfiltración**:
 *    - Bloquea direcciones privadas/locales/loopback/metadata (`localhost`, `127.0.0.1`, `10.x`, `172.16-31.x`,
 *      `192.168.x`, `169.254.169.254`, `::1`, `fc00::/7`, IPs decimales/hex ofuscadas).
 *    - Bloquea intentos de exfiltrar claves, tokens, cookies o blobs largos en query/fragmento hacia dominios externos.
 *    - Bloquea navegación autónoma del agente a destinos detectados como phishing/malware por [MalwareSignals].
 * 3. **Protección de acciones destructivas y páginas sensibles**:
 *    - Impide clics automáticos sobre pagos, compras, transferencias, borrado de cuenta, permisos,
 *      descargas o formularios de contraseñas/tarjetas/OTP.
 *    - Redacta secretos y tokens antes de enviar contexto al proveedor de IA.
 */
object NexoAiSecurity {

    data class SanitizedUntrusted(
        val text: String,
        val injectionDetected: Boolean,
        val signals: List<String>
    )

    data class UrlCheckResult(
        val allowed: Boolean,
        val normalizedUrl: String? = null,
        val reason: String? = null
    )

    private val BOUNDARY_ESCAPE_REGEX = Regex(
        "</?\\s*(UNTRUSTED_PAGE_CONTEXT|UNTRUSTED_TOOL_OUTPUT|SYSTEM|ASSISTANT|USER|DEVELOPER|SECURITY_ALERT)\\s*>|<\\|[^>]{1,32}\\|>|\\[/?INST\\]",
        RegexOption.IGNORE_CASE
    )

    private val EMBEDDED_FENCE_REGEX = Regex(
        "```\\s*(nexo-tool|nexo-actions|nexo-chart|nexo-card)",
        RegexOption.IGNORE_CASE
    )

    private val SECRET_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("\\bsk-(?:proj-|live-|test-|ant-)?[A-Za-z0-9_\\-]{20,}\\b") to "[REDACTADO:API_KEY]",
        Regex("\\bAIza[0-9A-Za-z_\\-]{30,}\\b") to "[REDACTADO:GOOGLE_KEY]",
        Regex("\\b(?:gsk_|nvapi-|ghp_|gho_|ghu_|github_pat_|xoxb-|xoxp-)[A-Za-z0-9_\\-]{16,}\\b") to "[REDACTADO:TOKEN]",
        Regex("\\bBearer\\s+[A-Za-z0-9._\\-]{20,}\\b", RegexOption.IGNORE_CASE) to "Bearer [REDACTADO]",
        Regex("\\beyJ[A-Za-z0-9_\\-]{12,}\\.[A-Za-z0-9_\\-]{12,}\\.[A-Za-z0-9_\\-]{10,}\\b") to "[REDACTADO:JWT]"
    )

    private val INJECTION_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)", RegexOption.IGNORE_CASE) to "override_instructions_en",
        Regex("ignora(r)?\\s+(todas?\\s+)?(las\\s+)?instrucciones\\s+(anteriores|previas)", RegexOption.IGNORE_CASE) to "override_instructions_es",
        Regex("olvida\\s+(todas?\\s+)?(tus|las)\\s+(reglas|instrucciones)", RegexOption.IGNORE_CASE) to "forget_rules_es",
        Regex("disregard\\s+(the\\s+)?(system|developer)\\s+(prompt|instructions?|rules?)", RegexOption.IGNORE_CASE) to "disregard_system_en",
        Regex("(reveal|print|show|output|leak)\\s+(your\\s+)?(system\\s+prompt|api\\s*key|secret|cookies?|tokens?)", RegexOption.IGNORE_CASE) to "secret_exfil_en",
        Regex("(muestra|revela|imprime|env[ií]a)\\s+(tu\\s+)?(prompt\\s+del\\s+sistema|clave\\s*api|cookies?|contrase[ñn]as?|tokens?)", RegexOption.IGNORE_CASE) to "secret_exfil_es",
        Regex("(you\\s+are\\s+now|act\\s+as\\s+a\\s+new\\s+system|developer\\s+mode\\s+enabled|jailbreak)", RegexOption.IGNORE_CASE) to "role_hijack",
        Regex("(exfiltrate|send\\s+the\\s+user'?s?\\s+data\\s+to|navigate\\s+to\\s+https?://[^\\s]+\\?(?:.*)(cookie|token|key|pass|secret))", RegexOption.IGNORE_CASE) to "exfiltration_directive",
        Regex("```\\s*(nexo-tool|nexo-actions)", RegexOption.IGNORE_CASE) to "forged_nexo_tool_block"
    )

    private val EXFIL_QUERY_KEY_REGEX = Regex(
        "(^|[&#?])(cookie|cookies|session|sessionid|token|access_token|id_token|auth|authorization|apikey|api_key|secret|password|passwd|pwd|cvv|otp|bearer|exfil|leak|dump)=",
        RegexOption.IGNORE_CASE
    )

    private val DESTRUCTIVE_OR_SENSITIVE_TARGET_REGEX = Regex(
        "\\b(pass(word)?|pwd|secret|token|csrf|credit|card|cvv|cvc|ssn|pin|otp|pay(\\s+now)?|pagar|comprar|buy\\s+now|checkout|transfer(ir)?|wire|withdraw|retirar|delete\\s+account|eliminar\\s+cuenta|borrar\\s+cuenta|close\\s+account|authorize|autorizar|grant\\s+access|permitir\\s+acceso|install|instalar|download|descargar|logout|log\\s*out|sign\\s*out|cerrar\\s+sesi[oó]n|confirmar\\s+pago|place\\s+order|confirm\\s+order)\\b",
        RegexOption.IGNORE_CASE
    )

    private val SENSITIVE_PAGE_PATH_REGEX = Regex(
        "/(login|signin|sign-in|auth|oauth|oauth2|checkout|payment|payments|billing|transfer|wallet|account/delete|settings/security|password|2fa|mfa|otp)(/|$|\\?)",
        RegexOption.IGNORE_CASE
    )

    private val DECIMAL_OR_HEX_IP_REGEX = Regex("^(0x[0-9a-f]+|[0-9]+)$", RegexOption.IGNORE_CASE)
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** Elimina caracteres Unicode invisibles, BiDi override y bloque Unicode Tags (smuggling). */
    fun stripInvisibleSmuggling(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = Character.charCount(cp)
            val invisible = cp in 0x200B..0x200F ||
                cp in 0x202A..0x202E ||
                cp in 0x2060..0x206F ||
                cp == 0xFEFF ||
                cp == 0x00AD ||
                cp in 0xE0000..0xE007F
            if (!invisible) sb.appendCodePoint(cp)
            i += charCount
        }
        return sb.toString()
    }

    /** Redacta claves API, tokens Bearer y JWTs antes de enviar contexto o abrir URLs. */
    fun redactSecrets(input: String): String {
        var out = input
        for ((regex, replacement) in SECRET_PATTERNS) {
            out = regex.replace(out, replacement)
        }
        return out
    }

    /**
     * Inspecciona, detecta intentos de prompt injection y sanea contenido web o salidas de herramientas
     * antes de entregarlas al LLM.
     */
    fun inspectAndSanitizeUntrusted(raw: String, maxChars: Int): SanitizedUntrusted {
        if (raw.isBlank()) return SanitizedUntrusted("", false, emptyList())
        val withoutInvisible = stripInvisibleSmuggling(raw)
        val invisibleRemoved = withoutInvisible.length < raw.length - 4

        val signals = LinkedHashSet<String>()
        if (invisibleRemoved) signals += "unicode_smuggling"
        for ((regex, label) in INJECTION_PATTERNS) {
            if (regex.containsMatchIn(withoutInvisible)) signals += label
        }

        var cleaned = redactSecrets(withoutInvisible)
            .replace("</UNTRUSTED_TOOL_OUTPUT>", "[/UNTRUSTED_TOOL_OUTPUT]", ignoreCase = true)
            .replace("</UNTRUSTED_PAGE_CONTEXT>", "[/UNTRUSTED_PAGE_CONTEXT]", ignoreCase = true)
        cleaned = BOUNDARY_ESCAPE_REGEX.replace(cleaned) { "[TAG_BLOQUEADA]" }
        cleaned = EMBEDDED_FENCE_REGEX.replace(cleaned) { "'''${it.groupValues[1]}-texto-plano" }
        cleaned = cleaned.take(maxChars.coerceAtLeast(200))

        return SanitizedUntrusted(
            text = cleaned,
            injectionDetected = signals.isNotEmpty(),
            signals = signals.toList()
        )
    }

    /** Verifica si un host apunta a red local, loopback, enlace-local o metadata cloud (anti-SSRF). */
    fun isLocalOrPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".lan") || host.endsWith(".home") ||
            host == "metadata.google.internal"
        ) return true
        if (DECIMAL_OR_HEX_IP_REGEX.matches(host)) return true

        // IPv6 loopback, link-local, unique-local y mapeadas IPv4
        if (':' in host) {
            if (host == "::1" || host == "::" || host.startsWith("fe80:") ||
                host.startsWith("fc") || host.startsWith("fd") ||
                host.startsWith("::ffff:")
            ) return true
            return false
        }

        val m = IPV4_REGEX.matchEntire(host) ?: return false
        val a = m.groupValues[1].toIntOrNull() ?: return true
        val b = m.groupValues[2].toIntOrNull() ?: return true
        val c = m.groupValues[3].toIntOrNull() ?: return true
        val d = m.groupValues[4].toIntOrNull() ?: return true
        if (a > 255 || b > 255 || c > 255 || d > 255) return true
        return a == 0 ||
            a == 10 ||
            a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }

    /**
     * Valida estrictamente una URL antes de que el agente navegue o la abra:
     * - Solo http/https sin credenciales.
     * - Bloquea SSRF/red local/metadata.
     * - Bloquea exfiltración de secretos/tokens o blobs sospechosos hacia dominios distintos.
     * - Bloquea URLs marcadas como phishing/malware por [MalwareSignals].
     */
    fun validateAgentUrl(raw: String, currentHost: String = ""): UrlCheckResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 512) {
            return UrlCheckResult(false, reason = "URL vacía o demasiado larga.")
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return UrlCheckResult(false, reason = "URL mal formada.")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return UrlCheckResult(false, reason = "Solo se permiten enlaces http/https.")
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            return UrlCheckResult(false, reason = "URL con credenciales incrustadas bloqueada.")
        }
        val host = uri.host?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (host.isEmpty()) {
            return UrlCheckResult(false, reason = "Host inválido.")
        }
        if (isLocalOrPrivateHost(host)) {
            return UrlCheckResult(false, reason = "Bloqueado acceso del agente a red local o privada ($host).")
        }

        val queryAndFrag = buildString {
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
        if (queryAndFrag.isNotEmpty()) {
            for ((regex, _) in SECRET_PATTERNS) {
                if (regex.containsMatchIn(queryAndFrag)) {
                    return UrlCheckResult(false, reason = "Bloqueada posible exfiltración de secreto en la URL.")
                }
            }
            val normalizedCurrent = currentHost.trim().lowercase(Locale.ROOT).removePrefix("www.")
            val normalizedTarget = host.removePrefix("www.")
            val crossDomain = normalizedCurrent.isNotEmpty() && normalizedCurrent != normalizedTarget &&
                !normalizedTarget.endsWith(".$normalizedCurrent")
            if (crossDomain && EXFIL_QUERY_KEY_REGEX.containsMatchIn(queryAndFrag)) {
                return UrlCheckResult(false, reason = "Bloqueado envío de parámetro sensible a otro dominio ($host).")
            }
            if (crossDomain && queryAndFrag.length > 260) {
                return UrlCheckResult(false, reason = "Bloqueada URL externa con carga de datos inusualmente larga.")
            }
        }

        val assessment = MalwareSignals.assess(trimmed, facts = null, inThreatFeed = false)
        if (MalwareSignals.decide(assessment, null).block || assessment.score >= 55) {
            return UrlCheckResult(false, reason = "Bloqueado destino sospechoso de phishing/malware ($host).")
        }

        return UrlCheckResult(true, normalizedUrl = trimmed)
    }

    /** True si el objetivo de un clic o acción parece sensible, financiero, destructivo o de permisos. */
    fun isSensitiveOrDestructiveTarget(target: String): Boolean =
        DESTRUCTIVE_OR_SENSITIVE_TARGET_REGEX.containsMatchIn(target)

    /** True si la URL corresponde a una pantalla sensible (login, pago, OAuth, borrado de cuenta). */
    fun isSensitivePageUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val path = uri.rawPath.orEmpty()
        return SENSITIVE_PAGE_PATH_REGEX.containsMatchIn(path)
    }
}
