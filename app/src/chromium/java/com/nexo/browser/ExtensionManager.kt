package com.nexo.browser

import android.app.Activity
import android.content.Context
import com.nexo.browser.engine.EngineSession
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

    fun bindSession(session: EngineSession) = Unit
    fun forgetSession(session: EngineSession) = Unit
    fun setTabActive(session: EngineSession, active: Boolean) = Unit

    // Puente de páginas (fase 4): sin él, nada que entregar.
    fun sendBrowserState(context: Context) = Unit
    fun setPipMode(session: EngineSession?, active: Boolean, resumePlayback: Boolean = false, isolateCss: Boolean = true) = Unit
    fun sendPipCommand(session: EngineSession?, command: String): Boolean = false
    fun setCaptions(session: EngineSession?, active: Boolean, live: Boolean = false, language: String = "multi", target: String = ""): Boolean = false
    fun restartCaptions(session: EngineSession?): Boolean = false
    fun restoreCaptions(session: EngineSession) = Unit
    fun sendLifecycle(session: EngineSession?, foreground: Boolean) = Unit
    fun hasMediaBridge(session: EngineSession?): Boolean = false
}
