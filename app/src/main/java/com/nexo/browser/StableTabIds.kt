package com.nexo.browser

/** RecyclerView IDs must be unique; String.hashCode() only provides 32 bits. */
class StableTabIds {
    private val ids = HashMap<String, Long>()
    private var next = 0L

    fun id(key: String): Long = ids.getOrPut(key) { next++ }

    fun retain(keys: Set<String>) {
        ids.keys.retainAll(keys)
    }
}
