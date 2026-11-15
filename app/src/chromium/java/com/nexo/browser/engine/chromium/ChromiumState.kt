package com.nexo.browser.engine.chromium

import android.os.Bundle
import android.os.Parcel
import android.util.Base64
import com.nexo.browser.engine.EngineSessionState
import org.json.JSONObject

/**
 * Historial de una pestaña de WebView: la URL actual y, si cabe, el estado
 * atrás/adelante que entrega `WebView.saveState`. Si el estado no se puede
 * restaurar (otra versión de WebView), se vuelve a cargar la URL.
 */
internal class ChromiumState(override val currentUri: String?, val bundle: ByteArray?) : EngineSessionState {
    override fun serialize(): String = JSONObject()
        .put("v", 1)
        .put("url", currentUri ?: "")
        .apply { bundle?.let { put("state", Base64.encodeToString(it, Base64.NO_WRAP)) } }
        .toString()

    fun toBundle(): Bundle? {
        val bytes = bundle ?: return null
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } catch (_: Throwable) {
            null
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        /** Estados mayores no se guardan (solo la URL): el archivo de pestañas debe seguir siendo pequeño. */
        const val MAX_STATE_BYTES = 256 * 1024

        fun parse(raw: String): ChromiumState? = runCatching {
            val json = JSONObject(raw)
            if (json.optInt("v") != 1) return null
            val url = json.optString("url").takeIf { it.isNotBlank() }
            val state = json.optString("state").takeIf { it.isNotBlank() }
                ?.let { Base64.decode(it, Base64.NO_WRAP) }
                ?.takeIf { it.size <= MAX_STATE_BYTES }
            ChromiumState(url, state)
        }.getOrNull()

        fun of(url: String?, bundle: Bundle?): ChromiumState {
            val bytes = bundle?.let {
                val parcel = Parcel.obtain()
                try {
                    it.writeToParcel(parcel, 0)
                    parcel.marshall()
                } catch (_: Throwable) {
                    null
                } finally {
                    parcel.recycle()
                }
            }?.takeIf { it.size <= MAX_STATE_BYTES }
            return ChromiumState(url, bytes)
        }
    }
}
