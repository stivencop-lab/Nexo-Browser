package com.nexo.browser.engine.cefrium

import android.app.Activity
import android.app.ActivityManager
import android.os.Looper
import android.os.MessageQueue
import com.cefrium.Cefrium
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumProfile
import java.lang.ref.WeakReference

/** Pre-creates one unused, normal-profile Cefrium host while the main thread is idle. */
internal object SpareRendererPool {
    private var owner = WeakReference<Activity>(null)
    private var spare: CefriumBrowser? = null
    private var scheduled = false
    private var supported = false
    private val warm = MessageQueue.IdleHandler {
        scheduled = false
        val activity = owner.get()
        if (supported && activity != null && !activity.isDestroyed && !activity.isFinishing &&
            Cefrium.isInitialized() && spare == null) {
            spare = runCatching {
                CefriumBrowser.createWithSurface(activity, CefriumProfile.getDefault()).apply { setPageVisible(false) }
            }.onFailure { EngineDiagnostics.log("No se pudo precalentar Cefrium", it) }.getOrNull()
        }
        false
    }

    fun start(activity: Activity) {
        if (owner.get() !== activity) {
            clear()
            owner = WeakReference(activity)
            val am = activity.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memory)
            supported = am != null && !am.isLowRamDevice && !memory.lowMemory && memory.totalMem > (4L shl 30)
        }
        schedule()
    }

    fun take(activity: Activity, profile: CefriumProfile): CefriumBrowser? {
        if (activity !== owner.get() || profile != CefriumProfile.getDefault()) return null
        val result = spare?.takeUnless { it.isClosed }
        spare = null
        schedule()
        return result
    }

    fun stop(activity: Activity) {
        if (owner.get() === activity) clear()
    }

    fun clear() {
        Looper.myQueue().removeIdleHandler(warm)
        scheduled = false
        val browser = spare
        spare = null
        owner.clear()
        supported = false
        if (browser != null && !browser.isClosed) runCatching {
            browser.close()
            CefriumBrowser.reapUnusedRenderers()
        }
    }

    private fun schedule() {
        if (supported && spare == null && !scheduled) {
            scheduled = true
            Looper.myQueue().addIdleHandler(warm)
        }
    }
}
