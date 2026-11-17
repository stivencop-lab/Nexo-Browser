package com.nexo.browser.shield

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decisiones nativas de la IA de PhantomShield (sin Android: se prueban en
 * la JVM). El clasificador corre en el anfitrión oculto ([ShieldAiHost]);
 * Nexo decide antes, sin cargarlo, si un sitio puede usar IA, y aplica en la
 * red los dominios publicitarios aprendidos.
 */
internal object ShieldAi {
    private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")
    private val LOCAL_SUFFIX = Regex("(^|\\.)(localhost|local|lan|localdomain|internal|intranet|test|invalid|example|onion|home|arpa)$")
    private const val DAY = 86_400_000L
    private const val TTL_WATCH = 30 * DAY
    private const val TTL_BLOCKED = 90 * DAY

    /** Igual que `PhantomAIConfig.publicHost`: nunca nombres locales ni IP. */
    fun publicHost(host: String?): Boolean {
        if (host == null || host.length > 253 || '.' !in host) return false
        if (!host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return false
        if (host.all { it.isDigit() || it == '.' }) return false
        if (!host.split('.').all { LABEL.matches(it) }) return false
        return !LOCAL_SUFFIX.containsMatchIn(host)
    }

    /** Lo que la página necesita saber (sin la clave ni el proveedor). */
    class Config(
        val enabled: Boolean,
        val scope: String,
        val allowedHosts: List<String>,
        val excludedHosts: List<String>,
        val includeSubdomains: Boolean,
        val detail: String,
        val sensitivity: String,
    ) {
        /** `PhantomAIConfig.hostAllowed` con la IA activa y aceptada. */
        fun allows(host: String?): Boolean {
            if (!enabled || host == null || !publicHost(host)) return false
            fun matches(domain: String) = host == domain || host.endsWith(".$domain")
            if (excludedHosts.any(::matches)) return false
            return scope == "public" || allowedHosts.any { host == it || (includeSubdomains && matches(it)) }
        }

        companion object {
            val OFF = Config(false, "selected", emptyList(), emptyList(), false, "basic", "balanced")

            /** `aiConfig` guardado; ante cualquier duda, IA apagada. */
            fun from(raw: Any?): Config {
                val json = raw as? JSONObject ?: return OFF
                fun hosts(key: String) = json.optJSONArray(key)?.let { array ->
                    (0 until minOf(array.length(), 100)).mapNotNull { (array.opt(it) as? String)?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
                }.orEmpty()
                val usable = json.optBoolean("enabled") && json.optBoolean("consent") &&
                    json.optString("endpoint").startsWith("https://") && json.optString("model").isNotBlank()
                return Config(
                    enabled = usable,
                    scope = if (json.optString("scope") == "public") "public" else "selected",
                    allowedHosts = hosts("allowedHosts"),
                    excludedHosts = hosts("excludedHosts"),
                    includeSubdomains = json.optBoolean("includeSubdomains"),
                    detail = if (json.optString("detail") == "rich") "rich" else "basic",
                    sensitivity = json.optString("sensitivity").takeIf { it == "conservative" || it == "aggressive" } ?: "balanced",
                )
            }
        }
    }

    /** Dominios aprendidos (`aiAdDomains`): los bloqueados vigentes y cuántos se observan. */
    class Learned(val blocked: Set<String>, val allowed: Set<String>, val watching: Int) {
        /** El host (o un dominio padre) está aprendido como bloqueado y no se marcó "Nunca bloquear". */
        fun blocks(host: String?): Boolean {
            if (blocked.isEmpty() || host.isNullOrEmpty()) return false
            if (parents(host).any { it in allowed }) return false
            return parents(host).any { it in blocked }
        }

        private fun parents(host: String): Sequence<String> =
            generateSequence(host.lowercase().trimEnd('.')) { value -> value.substringAfter('.', "").takeIf { '.' in it } }
                .filter { '.' in it }

        companion object {
            val EMPTY = Learned(emptySet(), emptySet(), 0)

            fun from(raw: Any?, now: Long): Learned {
                val json = raw as? JSONObject ?: return EMPTY
                val allowed = strings(json.optJSONArray("allowed"), 200).filter(::publicHost).toSet()
                val blocked = HashSet<String>()
                var watching = 0
                val entries = json.optJSONArray("entries") ?: JSONArray()
                for (i in 0 until minOf(entries.length(), 800)) {
                    val entry = entries.optJSONObject(i) ?: continue
                    val domain = entry.optString("domain").lowercase()
                    val last = entry.optDouble("last", Double.NaN)
                    if (!publicHost(domain) || last.isNaN() || domain in allowed) continue
                    val isBlocked = entry.optString("state") == "blocked"
                    val age = now - last.toLong()
                    if (age > (if (isBlocked) TTL_BLOCKED else TTL_WATCH) || age < -DAY) continue
                    if (isBlocked) blocked += domain else watching++
                }
                return Learned(blocked, allowed, watching)
            }

            private fun strings(array: JSONArray?, max: Int): List<String> =
                if (array == null) emptyList() else (0 until minOf(array.length(), max)).mapNotNull { (array.opt(it) as? String)?.lowercase() }
        }
    }

    /** Actividad de IA en la página actual de una pestaña (lo que muestra el panel). */
    class TabState {
        var active = false
        var scans = 0
        var analyzed = 0
        var hidden = 0
        var local = 0
        var model = 0
        var memory = 0
        var cache = 0
        var learned = 0
        var code = ""
        var at = 0L
        var memoryError = false

        fun toJson(): JSONObject = JSONObject().put("active", active).put("scans", scans).put("analyzed", analyzed)
            .put("hidden", hidden).put("local", local).put("model", model).put("memory", memory).put("cache", cache)
            .put("learned", learned).put("code", code).put("at", at).put("memoryError", memoryError)

        /** Resultado de `AI_CLASSIFY` (como `background.js`). */
        fun onClassified(result: JSONObject?, now: Long) {
            active = true
            at = now
            if (result != null && result.optBoolean("ok")) {
                scans++
                val decisions = result.optJSONArray("decisions")
                if (decisions != null) {
                    for (i in 0 until decisions.length()) {
                        if (decisions.optJSONObject(i)?.optBoolean("deferred") != true) analyzed++
                    }
                }
                code = result.optJSONObject("warning")?.optString("code")?.takeIf { it.isNotEmpty() } ?: "ok"
                learned += result.optInt("learned", 0).coerceIn(0, 40)
            } else {
                code = result?.optString("code")?.takeIf { it.isNotEmpty() } ?: "error"
            }
        }

        /**
         * Informe `AI_PAGE` del detector. Devuelve cuántos bloqueos suma (o
         * resta, si negativo) al contador de la pestaña.
         */
        fun onPage(message: JSONObject, now: Long): Int {
            active = true
            at = now
            var delta = 0
            if (message.optBoolean("restored")) {
                delta -= hidden
                hidden = 0; local = 0; model = 0; memory = 0; cache = 0
            }
            memoryError = message.optBoolean("memoryError")
            message.optJSONObject("sources")?.let { sources ->
                for (key in listOf("local", "model", "memory", "cache")) {
                    val value = sources.opt(key) as? Int ?: continue
                    if (value !in 0..12) continue
                    when (key) {
                        "local" -> local += value
                        "model" -> model += value
                        "memory" -> memory += value
                        else -> cache += value
                    }
                }
            }
            val count = (message.opt("hidden") as? Int)?.takeIf { it > 0 }?.coerceAtMost(12) ?: 0
            hidden += count
            delta += count
            if (message.optBoolean("idle") && (code.isEmpty() || code == "ready")) code = "idle"
            val reported = message.opt("code") as? String
            if (reported != null && Regex("^[a-z]{2,16}$").matches(reported)) code = reported
            return delta
        }
    }
}
