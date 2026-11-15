package com.nexo.browser

/** Android's foreground pressure levels (5/10/15) are below UI_HIDDEN (20). */
object MemoryPressure {
    enum class CacheAction { KEEP, REDUCE, CLEAR }

    fun cacheAction(level: Int): CacheAction = when {
        level == 15 || level >= 60 -> CacheAction.CLEAR
        level == 10 || level == 20 -> CacheAction.REDUCE
        else -> CacheAction.KEEP
    }
}
