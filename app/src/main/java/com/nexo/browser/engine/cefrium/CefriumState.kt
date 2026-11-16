package com.nexo.browser.engine.cefrium

import com.nexo.browser.engine.EngineSessionState
import org.json.JSONObject
import java.util.Base64

/**
 * Historial de una pestaña: la URL actual y la pila atrás/adelante completa
 * de Chromium (`serializeNavigationState`), que sí está pensada para guardarse
 * en disco y restaurarse en otro arranque. Si no cabe o no se puede
 * restaurar, se vuelve a cargar la URL. Sin Android: se prueba en la JVM.
 */
internal class CefriumState(override val currentUri: String?, val navigation: ByteArray?) : EngineSessionState {
    override fun serialize(): String = JSONObject()
        .put("v", VERSION)
        .put("url", currentUri ?: "")
        .apply { navigation?.let { put("nav", Base64.getEncoder().encodeToString(it)) } }
        .toString()

    companion object {
        private const val VERSION = 2

        /** Estados mayores no se guardan (solo la URL): el archivo de pestañas debe seguir siendo pequeño. */
        const val MAX_STATE_BYTES = 256 * 1024

        /**
         * Acepta también el formato de WebView (v1) y cualquier otro con "url":
         * de una versión anterior solo se recupera la dirección.
         */
        fun parse(raw: String): CefriumState? = runCatching {
            val json = JSONObject(raw)
            val url = json.optString("url").takeIf { it.isNotBlank() }
            val nav = if (json.optInt("v") == VERSION) {
                json.optString("nav").takeIf { it.isNotBlank() }
                    ?.let { Base64.getDecoder().decode(it) }
                    ?.takeIf { it.size <= MAX_STATE_BYTES }
            } else null
            if (url == null && nav == null) null else CefriumState(url, nav)
        }.getOrNull()

        fun of(url: String?, navigation: ByteArray?): CefriumState =
            CefriumState(url, navigation?.takeIf { it.isNotEmpty() && it.size <= MAX_STATE_BYTES })
    }
}
