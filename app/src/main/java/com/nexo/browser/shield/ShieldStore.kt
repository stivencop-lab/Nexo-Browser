package com.nexo.browser.shield

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * `storage.local` de PhantomShield en la variante Chromium: un JSON en el
 * almacenamiento privado de Nexo. Las listas descargadas (varios MB) van en
 * archivos aparte y nunca pasan por el puente.
 */
internal class ShieldStore(private val dir: File) {
    private val file = File(dir, "storage.json")
    private val listsDir = File(dir, "lists")
    private val writer = Executors.newSingleThreadExecutor()
    private val data: JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    private val listeners = ArrayList<(JSONObject) -> Unit>()

    init {
        dir.mkdirs()
        listsDir.mkdirs()
    }

    @Synchronized fun addListener(listener: (JSONObject) -> Unit) { listeners.add(listener) }

    /** Mismo contrato que `storage.local.get`: `null`, una clave, una lista o un objeto con valores por defecto. */
    @Synchronized fun get(keys: Any?): JSONObject {
        val result = JSONObject()
        when (keys) {
            null, JSONObject.NULL -> data.keys().forEach { result.put(it, data.get(it)) }
            is String -> if (data.has(keys)) result.put(keys, data.get(keys))
            is JSONArray -> for (i in 0 until keys.length()) {
                val key = keys.optString(i)
                if (data.has(key)) result.put(key, data.get(key))
            }
            is JSONObject -> keys.keys().forEach { key -> result.put(key, if (data.has(key)) data.get(key) else keys.get(key)) }
        }
        return result
    }

    fun set(values: JSONObject) {
        val changes = JSONObject()
        synchronized(this) {
            values.keys().forEach { key ->
                val value = values.get(key)
                val old = data.opt(key)
                if (old != null && old.toString() == value.toString()) return@forEach
                changes.put(key, JSONObject().put("newValue", value).apply { if (old != null) put("oldValue", old) })
                data.put(key, value)
            }
        }
        if (changes.length() > 0) changed(changes)
    }

    fun remove(keys: List<String>) {
        val changes = JSONObject()
        synchronized(this) {
            for (key in keys) {
                val old = data.opt(key) ?: continue
                data.remove(key)
                changes.put(key, JSONObject().put("oldValue", old))
            }
        }
        if (changes.length() > 0) changed(changes)
    }

    @Synchronized fun boolean(key: String, fallback: Boolean): Boolean =
        if (data.has(key)) data.optBoolean(key, fallback) else fallback

    @Synchronized fun long(key: String): Long = data.optLong(key, 0L)

    @Synchronized fun stringList(key: String): List<Any?> =
        data.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.opt(it) } } ?: emptyList()

    private fun changed(changes: JSONObject) {
        persist()
        val copy = synchronized(this) { listeners.toList() }
        copy.forEach { runCatching { it(changes) } }
    }

    private fun persist() {
        writer.execute {
            val text = synchronized(this) { data.toString() }
            runCatching {
                val tmp = File(dir, "storage.json.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    fun listText(id: String): String? = File(listsDir, "$id.txt").takeIf { it.isFile }?.readText()

    fun saveList(id: String, text: String) {
        val tmp = File(listsDir, "$id.txt.tmp")
        tmp.writeText(text)
        val target = File(listsDir, "$id.txt")
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }

    fun clearAll() {
        synchronized(this) { data.keys().asSequence().toList().forEach { data.remove(it) } }
        listsDir.listFiles()?.forEach { it.delete() }
        persist()
    }
}
