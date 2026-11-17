package com.nexo.browser

import android.app.Activity
import android.content.Context
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import com.nexo.browser.shield.PhantomShield
import java.lang.ref.WeakReference

/**
 * Extensiones de Nexo sobre Chromium (Cefrium).
 *
 * Cefrium no ejecuta WebExtensions: PhantomShield funciona con su motor
 * nativo ([PhantomShield]) y el puente de páginas (mini video, subtítulos,
 * modo nocturno) con el mismo `content_script.js` de Gecko ([PageBridge]). Las extensiones de terceros
 * llegarán con el runtime NXR; mientras tanto la interfaz común recibe
 * respuestas vacías y explicaciones claras.
 */
object ExtensionManager {
    const val PHANTOMSHIELD_ID = "phantomshield@geckoview.internal"
    private const val NOT_YET = "Las extensiones de terceros llegarán en una próxima versión de Nexo."

    private var promptActivity: WeakReference<Activity>? = null

    fun initialize(context: Context) = Unit

    fun attachPromptActivity(activity: Activity?) {
        promptActivity = activity?.let { WeakReference(it) }
    }

    fun detachPromptActivity(activity: Activity) {
        if (promptActivity?.get() === activity) promptActivity = null
    }

    fun consumePendingAction(onDone: (Boolean, String) -> Unit) = Unit

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
            onDone(false, "No se pudo abrir el panel de PhantomShield. Detalles en Ajustes › diagnóstico del motor.")
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

    fun bindSession(session: EngineSession) {
        (session as? CefriumEngineSession)?.let { PhantomShield.bindSession(it.shield, it) }
    }

    fun forgetSession(session: EngineSession) {
        (session as? CefriumEngineSession)?.shield?.session = null
        PageBridge.forget(session)
    }

    fun setTabActive(session: EngineSession, active: Boolean) = Unit

    // Mini video, subtítulos y estado del navegador: el puente de Nexo en las páginas (PageBridge).

    fun sendBrowserState(context: Context) = PageBridge.sendBrowserState(context)

    fun setPipMode(session: EngineSession?, active: Boolean, resumePlayback: Boolean = false, isolateCss: Boolean = true) =
        PageBridge.setPipMode(session, active, resumePlayback, isolateCss)

    fun sendPipCommand(session: EngineSession?, command: String): Boolean = PageBridge.sendPipCommand(session, command)

    fun setCaptions(session: EngineSession?, active: Boolean, live: Boolean = false, language: String = "multi", target: String = ""): Boolean =
        PageBridge.setCaptions(session, active, live, language, target)

    fun restartCaptions(session: EngineSession?): Boolean = PageBridge.restartCaptions(session)
    fun restoreCaptions(session: EngineSession) = PageBridge.restoreCaptions(session)
    fun sendLifecycle(session: EngineSession?, foreground: Boolean) = PageBridge.sendLifecycle(session, foreground)
    fun hasMediaBridge(session: EngineSession?): Boolean = PageBridge.hasMediaBridge(session)
}
