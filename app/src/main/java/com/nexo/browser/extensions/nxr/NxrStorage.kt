package com.nexo.browser.extensions.nxr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Almacenamiento `chrome.storage.local` aislado por extensión NXR.
 *
 * Cada extensión escribe únicamente en su propio archivo `storage_local.json`
 * dentro de su directorio dedicado, con cuota estricta (512 KB, máximo 512 claves).
 * Nunca accede a `SecretStore`, `BrowserPrefs` ni al almacenamiento de otras extensiones.
 * Puro sobre [File]: probado en la JVM.
 */
class NxrStorage(private val rootDir: File) {
    private val lock = Any()

    private fun storageFile(extId: String): File {
        val safe = sanitizeId(extId)
        return File(File(rootDir, safe), STORAGE_FILE)
    }

    private fun readAll(extId: String): JSONObject {
        val file = storageFile(extId)
        if (!file.isFile) return JSONObject()
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }.getOrDefault(JSONObject())
    }

    private fun writeAll(extId: String, data: JSONObject) {
        val serialized = data.toString()
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_STORAGE_BYTES) {
            "Cuota de almacenamiento excedida (máximo ${MAX_STORAGE_BYTES / 1024} KB por extensión)."
        }
        require(data.length() <= MAX_KEYS) {
            "Demasiadas claves en storage.local (máximo $MAX_KEYS)."
        }
        val file = storageFile(extId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$STORAGE_FILE.tmp")
        tmp.writeText(serialized, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(serialized, Charsets.UTF_8)
            tmp.delete()
        }
    }

    /** Lee `null` (todo), un `String`, un `JSONArray` de claves o un `JSONObject` con valores por defecto. */
    fun get(extId: String, keys: Any?): JSONObject = synchronized(lock) {
        val all = readAll(extId)
        when (keys) {
            null, JSONObject.NULL -> JSONObject(all.toString())
            is String -> JSONObject().apply {
                if (all.has(keys)) put(keys, all.get(keys))
            }
            is JSONArray -> JSONObject().apply {
                for (i in 0 until minOf(keys.length(), MAX_KEYS)) {
                    val k = keys.optString(i).takeIf { it.isNotEmpty() } ?: continue
                    if (all.has(k)) put(k, all.get(k))
                }
            }
            is JSONObject -> JSONObject().apply {
                val it = keys.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    put(k, if (all.has(k)) all.get(k) else keys.get(k))
                }
            }
            else -> JSONObject()
        }
    }

    fun set(extId: String, values: JSONObject) = synchronized(lock) {
        val all = readAll(extId)
        val it = values.keys()
        while (it.hasNext()) {
            val key = it.next()
            require(key.length <= MAX_KEY_LENGTH) { "Clave demasiado larga." }
            val value = values.get(key)
            val valueSize = value.toString().toByteArray(Charsets.UTF_8).size
            require(valueSize <= MAX_VALUE_BYTES) { "Valor demasiado grande para storage.local." }
            all.put(key, value)
        }
        writeAll(extId, all)
    }

    fun remove(extId: String, keys: Any?) = synchronized(lock) {
        val all = readAll(extId)
        var changed = false
        when (keys) {
            is String -> if (all.remove(keys) != null) changed = true
            is JSONArray -> {
                for (i in 0 until keys.length()) {
                    val k = keys.optString(i)
                    if (k.isNotEmpty() && all.remove(k) != null) changed = true
                }
            }
        }
        if (changed) writeAll(extId, all)
    }

    fun clear(extId: String) = synchronized(lock) {
        storageFile(extId).delete()
    }

    fun byteSize(extId: String): Long = synchronized(lock) {
        storageFile(extId).takeIf { it.isFile }?.length() ?: 0L
    }

    companion object {
        private const val STORAGE_FILE = "storage_local.json"
        const val MAX_STORAGE_BYTES = 512 * 1024
        const val MAX_VALUE_BYTES = 128 * 1024
        const val MAX_KEYS = 512
        const val MAX_KEY_LENGTH = 128

        fun sanitizeId(extId: String): String {
            val clean = extId.trim().lowercase().replace(Regex("[^a-z0-9._@-]"), "_")
            require(clean.isNotEmpty() && clean != "." && clean != ".." && !clean.contains("..")) {
                "Identificador de extensión no válido."
            }
            return clean.take(96)
        }
    }
}
