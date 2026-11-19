package com.nexo.browser

import android.app.Activity
import android.content.Context
import com.nexo.browser.engine.EngineSession
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import com.nexo.browser.extensions.nxr.NxrRuntime
import com.nexo.browser.shield.PhantomShield
import java.lang.ref.WeakReference

/**
 * Extensiones de Nexo sobre Chromium (Cefrium).
 *
 * Integra:
 * - PhantomShield (motor nativo integrado de bloqueo y privacidad).
 * - Puente de páginas (`PageBridge`: mini video, subtítulos, Nexo Dark Engine, Nexo Media Engine).
 * - Runtime NXR (`NxrRuntime`: extensiones de terceros `.crx`/`.xpi`/`.zip` con content scripts,
 *   almacenamiento local aislado, permisos por sitio y diagnóstico de APIs).
 */
object ExtensionManager {
    const val PHANTOMSHIELD_ID = "phantomshield@nexo.internal"

    private var promptActivity: WeakReference<Activity>? = null

    fun initialize(context: Context) {
        NxrRuntime.initialize(context)
    }

    fun attachPromptActivity(activity: Activity?) {
        promptActivity = activity?.let { WeakReference(it) }
    }

    fun detachPromptActivity(activity: Activity) {
        if (promptActivity?.get() === activity) promptActivity = null
    }

    fun consumePendingAction(onDone: (Boolean, String) -> Unit) = Unit

    fun phantomEntry(): ExtensionEntry? =
        ExtensionEntry(PHANTOMSHIELD_ID, "PhantomShield", PhantomShield.isEnabled(), null)

    fun actionEntries(): List<ExtensionEntry> {
        val ctx = runCatching { AppContext.get() }.getOrNull()
        val builtIn = listOfNotNull(phantomEntry()?.takeIf { it.enabled })
        val nxr = NxrRuntime.extensionEntries(ctx).filter { it.enabled }
        return builtIn + nxr
    }

    fun phantomBadge(global: Boolean = false): String? = PhantomShield.badge(global)
    fun badgeFor(id: String): String? = if (id == PHANTOMSHIELD_ID) PhantomShield.badge() else null

    fun loadIconFor(id: String, sizePx: Int, onDone: (android.graphics.Bitmap?) -> Unit) {
        if (id == PHANTOMSHIELD_ID) {
            onDone(null)
            return
        }
        val ctx = runCatching { AppContext.get() }.getOrNull()
        onDone(ctx?.let { NxrRuntime.loadIcon(it, id, sizePx) })
    }

    fun runActionFor(id: String, onDone: (Boolean, String) -> Unit) {
        val activity = promptActivity?.get()
        if (id == PHANTOMSHIELD_ID) {
            if (activity == null || !PhantomShieldPanel.show(activity)) {
                onDone(false, "No se pudo abrir el panel de PhantomShield. Detalles en Ajustes › diagnóstico del motor.")
            } else {
                onDone(true, "PhantomShield")
            }
            return
        }
        if (activity == null) {
            onDone(false, "Abre Nexo para gestionar esta extensión.")
            return
        }
        NxrRuntime.showExtensionSheet(activity, id)
        onDone(true, id)
    }

    fun setEnabledFor(context: Context, id: String, enabled: Boolean, onDone: (Boolean, String) -> Unit) {
        if (id == PHANTOMSHIELD_ID) {
            PhantomShield.setEnabled(enabled)
            onDone(true, if (enabled) "PhantomShield activado" else "PhantomShield desactivado")
            return
        }
        val ok = NxrRuntime.setEnabled(context, id, enabled)
        onDone(ok, if (ok) (if (enabled) "Extensión activada" else "Extensión desactivada") else "Extensión no encontrada.")
    }

    fun storeScreen(): Class<out Activity> = ExtensionsActivity::class.java

    /** Paquetes HTTPS directos (`.crx`, `.xpi`, `.zip`) pasan por verificación y consentimiento del Hub. */
    fun isRemoteExtensionPackage(url: String): Boolean =
        com.nexo.browser.extensions.hub.ExtensionStoreResolver.isDirectExtensionPackageUrl(url)

    fun installUrl(context: Context, url: String, onDone: (Boolean, String) -> Unit) {
        val activity = promptActivity?.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            com.nexo.browser.extensions.hub.ExtensionHubSheets.openDirectUrlInstallFlow(activity, url) {
                onDone(true, "Extensión instalada en NXR.")
            }
            return
        }
        onDone(false, "Abre Extensiones › Nexo Extension Hub y pega la URL HTTPS para revisarla e instalarla.")
    }

    fun bindSession(session: EngineSession) {
        (session as? CefriumEngineSession)?.let { PhantomShield.bindSession(it.shield, it) }
    }

    fun forgetSession(session: EngineSession) {
        LiveCaptions.stop(session)
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
