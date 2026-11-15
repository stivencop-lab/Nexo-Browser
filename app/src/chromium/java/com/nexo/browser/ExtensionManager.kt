package com.nexo.browser

import android.app.Activity
import android.content.Context
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.chromium.ChromiumEngineSession
import org.json.JSONObject
import com.nexo.browser.engine.chromium.ChromiumEngineSession
import org.json.JSONObject
import com.nexo.browser.shield.PhantomShield

/**
 * Extensiones en la variante Chromium.
 *
 * WebView no ejecuta WebExtensions: PhantomShield funciona con su motor
 * nativo ([PhantomShield]); el puente de páginas (subtítulos, mini video,
 * modo nocturno) llega en la fase 4 y las extensiones de escritorio con el
 * runtime NXR. Mientras tanto la interfaz común recibe respuestas vacías y
 * explicaciones claras.
 */
object ExtensionManager {
    const val PHANTOMSHIELD_ID = "phantomshield@geckoview.internal"
    private const val NOT_YET = "Las extensiones llegarán en una próxima versión de Nexo Chromium."

    fun initialize(context: Context) = Unit
    fun attachPromptActivity(activity: Activity?) {
        promptActivity = activity?.let { java.lang.ref.WeakReference(it) }
    }

    fun detachPromptActivity(activity: Activity) {
        if (promptActivity?.get() === activity) promptActivity = null
    }
    fun consumePendingAction(onDone: (Boolean, String) -> Unit) = Unit

    private var promptActivity: java.lang.ref.WeakReference<Activity>? = null

    fun phantomEntry(): ExtensionEntry? =
        ExtensionEntry(PHANTOMSHIELD_ID, "PhantomShield", PhantomShield.isEnabled(), null)

    fun actionEntries(): List<ExtensionEntry> = listOfNotNull(phantomEntry()?.takeIf { it.enabled })
    fun phantomBadge(global: Boolean = false): String? = PhantomShield.badge(global)
    fun badgeFor(id: String): String? = if (id == PHANTOMSHIELD_ID) PhantomShield.badge() else null
    fun loadIconFor(id: String, sizePx: Int, onDone: (android.graphics.Bitmap?) -> Unit) = onDone(null)

    fun runActionFor(id: String, onDone: (Boolean, String) -> Unit) {
        if (id != PHANTOMSHIELD_ID) return onDone(false, NOT_YET)
        val activity = promptActivity?.get()
        if (activity == null || !PhantomShieldPanel.show(activity)) {
            onDone(false, "Este WebView no puede mostrar el panel de PhantomShield. Actualiza Android System WebView.")
        } else {
            onDone(true, "PhantomShield")
        }
    }

    fun setEnabledFor(context: Context, id: String, enabled: Boolean, onDone: (Boolean, String) -> Unit) {
        if (id != PHANTOMSHIELD_ID) return onDone(false, NOT_YET)
        PhantomShield.setEnabled(enabled)
        onDone(true, if (enabled) "PhantomShield activado" else "PhantomShield desactivado")
    }
    fun storeScreen(): Class<out Activity> = ExtensionsActivity::class.java

    /** Los paquetes de extensiones se descargan como archivos hasta que exista el instalador NXR. */
    fun isRemoteExtensionPackage(url: String): Boolean = false
    fun installUrl(context: Context, url: String, onDone: (Boolean, String) -> Unit) = onDone(false, NOT_YET)

    fun bindSession(session: EngineSession) { (session as? ChromiumEngineSession)?.let { PhantomShield.bindSession(it.shield, it) } }
    fun forgetSession(session: EngineSession) { (session as? ChromiumEngineSession)?.shield?.session = null }
    fun setTabActive(session: EngineSession, active: Boolean) = Unit

    fun sendBrowserState(context: Context) = Unit
    private fun send(session: EngineSession?, message: JSONObject): Boolean =
        (session as? ChromiumEngineSession)?.let { PhantomShield.sendToSession(it, message) } ?: false
    private fun send(session: EngineSession?, message: JSONObject): Boolean =
        (session as? ChromiumEngineSession)?.let { PhantomShield.sendToSession(it, message) } ?: false
    fun setPipMode(session: EngineSession?, active: Boolean, resumePlayback: Boolean = false, isolateCss: Boolean = true) =
        send(session, JSONObject().put("type", "pip_mode").put("active", active).put("resume", resumePlayback).put("isolate", isolateCss))
    fun sendPipCommand(session: EngineSession?, command: String): Boolean = send(session, JSONObject().put("type", "pip_command").put("command", command))
    fun setCaptions(session: EngineSession?, active: Boolean, live: Boolean = false, language: String = "multi", target: String = ""): Boolean =
        send(session, JSONObject().put("type", "captions").put("active", active).put("live", live).put("lang", language).put("target", target))
    fun restartCaptions(session: EngineSession?): Boolean = send(session, JSONObject().put("type", "captions_restart"))
    fun restoreCaptions(session: EngineSession) = send(session, JSONObject().put("type", "captions_grant"))
    fun sendLifecycle(session: EngineSession?, foreground: Boolean) = send(session, JSONObject().put("type", "lifecycle").put("state", if (foreground) "foreground" else "background"))
    fun hasMediaBridge(session: EngineSession?): Boolean = send(session, JSONObject().put("type", "get_state"))
}
