package com.nexo.browser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Schedules idle-tab discards using elapsed time, independent of wall-clock changes. */
internal class TabFreezeManager(private val sweep: (now: Long) -> Long?) {
    private val handler = Handler(Looper.getMainLooper())
    private val task = Runnable { schedule(sweep(SystemClock.elapsedRealtime())) }

    fun schedule(deadline: Long?) {
        handler.removeCallbacks(task)
        if (deadline != null) {
            handler.postDelayed(task, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        }
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L
        const val BUSY_RECHECK_MS = 30_000L
    }
}
