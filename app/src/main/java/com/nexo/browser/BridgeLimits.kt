package com.nexo.browser

import org.json.JSONObject
import org.json.JSONArray

/**
 * Límites de los mensajes que una página (a través del puente) envía a Nexo.
 * Un marco hostil no puede hacer que Nexo procese cadenas o listas enormes.
 * Puro: probado en la JVM.
 */
object BridgeLimits {
    private const val MAX_KEYS = 20
    private const val MAX_AUDIO_BASE64 = 350_000
    private const val MAX_TEXT = 2_000
    private const val MAX_CUES = 200
    private const val MAX_IDS = 16
    private const val MAX_CUE_TEXT = 300
    private val CUE_KEYS = setOf("i", "s", "e", "t")

    fun accept(json: JSONObject): Boolean {
        if (json.length() > MAX_KEYS) return false
        val type = json.opt("type") as? String ?: return false
        if (type.isEmpty() || type.length > 40) return false
        for (key in json.keys()) {
            if (key.length > 40) return false
            val value = json.opt(key)
            val accepted = when (value) {
                is String -> {
                    val limit = if (key == "data" && type == "captions_audio") MAX_AUDIO_BASE64 else MAX_TEXT
                    value.length <= limit
                }
                is JSONArray -> when {
                    key == "cues" && type == "captions_cues" -> acceptCues(value)
                    key == "ids" && type == "captions_active" ->
                        value.length() <= MAX_IDS && (0 until value.length()).all { validId(value.opt(it)) }
                    else -> false
                }
                is JSONObject -> false
                is Number -> value.toDouble().isFinite()
                is Boolean -> true
                null -> true
                else -> value === JSONObject.NULL
            }
            if (!accepted) return false
        }
        return true
    }

    // El productor envía bloques de hasta 200 entradas {i,s,e,t}, con texto de 300 caracteres.
    // No recorrer árboles JSON arbitrarios: una lista corta también puede ocultar datos enormes.
    private fun acceptCues(cues: JSONArray): Boolean {
        if (cues.length() > MAX_CUES) return false
        return (0 until cues.length()).all { index ->
            val cue = cues.optJSONObject(index) ?: return@all false
            if (cue.length() != CUE_KEYS.size || cue.keys().asSequence().any { it !in CUE_KEYS }) return@all false
            validId(cue.opt("i")) && finiteNumber(cue.opt("s")) && finiteNumber(cue.opt("e")) &&
                (cue.opt("t") as? String)?.let { it.length <= MAX_CUE_TEXT } == true
        }
    }

    private fun finiteNumber(value: Any?): Boolean = value is Number && value.toDouble().isFinite()

    private fun validId(value: Any?): Boolean = value is Number && value.toDouble().let {
        it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble()
    }
}
