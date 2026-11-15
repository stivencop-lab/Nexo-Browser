package com.nexo.browser.extensions

import org.json.JSONObject

/**
 * `chrome.i18n` de una extensión: mensajes de `_locales/<idioma>/messages.json`
 * con marcadores (`$NOMBRE$`, `$1`…`$9`) y reserva al idioma por defecto.
 * Puro: probado en la JVM.
 */
class ExtensionMessages private constructor(
    private val chain: List<Map<String, Message>>,
    private val extensionId: String,
    val uiLocale: String
) {
    private class Message(val text: String, val placeholders: Map<String, String>)

    /** `null` si el mensaje no existe en ningún idioma (Chrome devuelve cadena vacía). */
    fun get(name: String, substitutions: List<String> = emptyList()): String? {
        val key = name.lowercase()
        when (key) {
            "@@extension_id" -> return extensionId
            "@@ui_locale" -> return uiLocale.replace('-', '_')
            "@@bidi_dir" -> return if (rtl()) "rtl" else "ltr"
            "@@bidi_reversed_dir" -> return if (rtl()) "ltr" else "rtl"
            "@@bidi_start_edge" -> return if (rtl()) "right" else "left"
            "@@bidi_end_edge" -> return if (rtl()) "left" else "right"
        }
        val message = chain.firstNotNullOfOrNull { it[key] } ?: return null
        val subs = substitutions.take(9)
        val withPlaceholders = PLACEHOLDER.replace(message.text) { match ->
            val raw = match.value
            if (raw == "$$") return@replace "$"
            val placeholder = message.placeholders[match.groupValues[1].lowercase()] ?: return@replace raw
            substitute(placeholder, subs)
        }
        return substitute(withPlaceholders, subs)
    }

    /** Sustituye `__MSG_nombre__` en el manifiesto y en el CSS de la extensión. */
    fun localize(text: String): String = MSG.replace(text) { match -> get(match.groupValues[1]) ?: "" }

    private fun rtl(): Boolean = uiLocale.substringBefore('-').substringBefore('_') in RTL

    private fun substitute(text: String, subs: List<String>): String =
        NUMBERED.replace(text) { match ->
            val index = match.groupValues[1].toInt() - 1
            subs.getOrNull(index) ?: ""
        }

    companion object {
        private val PLACEHOLDER = Regex("""\$\$|\$([A-Za-z0-9_@]+)\$""")
        private val NUMBERED = Regex("""\$([1-9])""")
        private val MSG = Regex("""__MSG_([A-Za-z0-9_@]+)__""")
        private val RTL = setOf("ar", "fa", "he", "iw", "ur", "yi", "ps", "sd", "ug", "dv")

        /**
         * [readLocale] devuelve el texto de `_locales/<carpeta>/messages.json`
         * o `null` si no existe. [uiLocale] con formato `es-419`, `pt_BR` o `en`.
         */
        fun load(
            extensionId: String,
            defaultLocale: String?,
            uiLocale: String,
            readLocale: (String) -> String?
        ): ExtensionMessages {
            val folders = candidateFolders(uiLocale, defaultLocale)
            val chain = folders.mapNotNull { folder -> readLocale(folder)?.let { parse(it) } }
            return ExtensionMessages(chain, extensionId, uiLocale)
        }

        /** Orden de búsqueda de Chrome: `es_419` → `es` → idioma por defecto. */
        fun candidateFolders(uiLocale: String, defaultLocale: String?): List<String> {
            val normalized = uiLocale.replace('-', '_')
            val language = normalized.substringBefore('_')
            return listOfNotNull(normalized, language, defaultLocale?.replace('-', '_'))
                .filter { it.matches(Regex("[A-Za-z]{2,3}(_[A-Za-z0-9]{2,4})?")) }
                .distinct()
        }

        private fun parse(text: String): Map<String, Message>? = runCatching {
            val json = JSONObject(text.removePrefix("\uFEFF"))
            json.keys().asSequence().take(10_000).mapNotNull { key ->
                val entry = json.optJSONObject(key) ?: return@mapNotNull null
                val message = entry.optString("message").takeIf { entry.has("message") } ?: return@mapNotNull null
                val placeholders = entry.optJSONObject("placeholders")?.let { holders ->
                    holders.keys().asSequence().mapNotNull { name ->
                        holders.optJSONObject(name)?.optString("content")?.let { name.lowercase() to it }
                    }.toMap()
                }.orEmpty()
                key.lowercase() to Message(message.take(10_000), placeholders)
            }.toMap()
        }.getOrNull()
    }
}
