package com.nexo.browser.extensions.nxr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Almacenamiento `chrome.storage.local` aislado por extensión NXR y particionado por origen.
 *
 * Seguridad crítica sobre Cefrium:
 * - Cefrium 0.22.0 no expone mundos V8 aislados para content scripts de terceros, por lo que
 *   el script de contenido comparte el mundo JS principal con la página visitada.
 * - Para impedir que un sitio malicioso (`evil.com`) lea o corrompa datos guardados por la misma
 *   extensión en otro sitio (`bank.com`), las operaciones procedentes de content scripts se
 *   particionan estrictamente por `(extId, originHost)` bajo `origins/<sha256(host)>.json`.
 * - Cuota agregada estricta (4 MB totales por extensión entre todos sus orígenes, máximo 2048 claves).
 * - Nunca accede a `SecretStore`, `BrowserPrefs` ni al almacenamiento de otras extensiones.
 * Puro sobre [File]: probado en la JVM.
 */
class NxrStorage(private val rootDir: File) {
    private val lock = NxrPackageInstaller.mutationLock

    private fun extDir(extId: String): File = File(rootDir, sanitizeId(extId))

    private fun storageFile(extId: String, originHost: String? = null): File {
        val base = extDir(extId)
        val cleanOrigin = originHost?.let { sanitizeOriginHost(it) }
        return if (cleanOrigin == null) {
            File(base, STORAGE_FILE)
        } else {
            val hash = sha256Hex(cleanOrigin).take(24)
            File(File(base, ORIGINS_DIR), "$hash.json")
        }
    }

    private fun readAll(extId: String, originHost: String? = null): JSONObject {
        val file = storageFile(extId, originHost)
        if (!file.isFile) return JSONObject()
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }.getOrDefault(JSONObject())
    }

    private fun writeAll(extId: String, data: JSONObject, originHost: String? = null) {
        val serialized = data.toString()
        val newBytes = serialized.toByteArray(Charsets.UTF_8).size
        val file = storageFile(extId, originHost)
        val currentFileBytes = if (file.isFile) file.length() else 0L
        val totalOtherBytes = (totalByteSizeUnlocked(extId) - currentFileBytes).coerceAtLeast(0L)
        require(totalOtherBytes + newBytes <= MAX_STORAGE_BYTES) {
            "Cuota de almacenamiento excedida (máximo ${MAX_STORAGE_BYTES / 1024} KB por extensión)."
        }
        require(data.length() <= MAX_KEYS) {
            "Demasiadas claves en storage.local (máximo $MAX_KEYS)."
        }
        com.nexo.browser.writeAtomicFile(file, serialized.toByteArray(Charsets.UTF_8))
    }

    private fun totalByteSizeUnlocked(extId: String): Long {
        val base = extDir(extId)
        var total = File(base, STORAGE_FILE).takeIf { it.isFile }?.length() ?: 0L
        val origins = File(base, ORIGINS_DIR)
        if (origins.isDirectory) {
            origins.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".json")) total += f.length()
            }
        }
        return total
    }

    /** Lee `null` (todo), un `String`, un `JSONArray` de claves o un `JSONObject` con valores por defecto. */
    fun get(extId: String, keys: Any?, originHost: String? = null): JSONObject = synchronized(lock) {
        val all = readAll(extId, originHost)
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

    fun set(extId: String, values: JSONObject, originHost: String? = null) = synchronized(lock) {
        val all = readAll(extId, originHost)
        val it = values.keys()
        while (it.hasNext()) {
            val key = it.next()
            require(key.length <= MAX_KEY_LENGTH) { "Clave demasiado larga." }
            val value = values.get(key)
            val valueSize = value.toString().toByteArray(Charsets.UTF_8).size
            require(valueSize <= MAX_VALUE_BYTES) { "Valor demasiado grande para storage.local." }
            all.put(key, value)
        }
        writeAll(extId, all, originHost)
    }

    fun remove(extId: String, keys: Any?, originHost: String? = null) = synchronized(lock) {
        val all = readAll(extId, originHost)
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
        if (changed) writeAll(extId, all, originHost)
    }

    fun clear(extId: String, originHost: String? = null) = synchronized(lock) {
        if (originHost != null) {
            storageFile(extId, originHost).delete()
        } else {
            storageFile(extId, null).delete()
            File(extDir(extId), ORIGINS_DIR).deleteRecursively()
        }
    }

    fun byteSize(extId: String): Long = synchronized(lock) {
        totalByteSizeUnlocked(extId)
    }

    companion object {
        internal const val STORAGE_FILE = "storage_local.json"
        internal const val ORIGINS_DIR = "origins"
        const val MAX_STORAGE_BYTES = 4 * 1024 * 1024
        const val MAX_VALUE_BYTES = 512 * 1024
        const val MAX_KEYS = 2048
        const val MAX_KEY_LENGTH = 128

        fun sanitizeId(extId: String): String {
            val clean = extId.trim().lowercase().replace(Regex("[^a-z0-9._@-]"), "_")
            require(clean.isNotEmpty() && clean != "." && clean != ".." && !clean.contains("..")) {
                "Identificador de extensión no válido."
            }
            return clean.take(96)
        }

        internal fun sanitizeOriginHost(originHost: String): String {
            val clean = originHost.trim().lowercase().removePrefix("www.").trimEnd('.')
            require(clean.isNotEmpty() && clean.length <= 253 && !clean.contains('/') && !clean.contains("..")) {
                "Origen no válido para almacenamiento NXR."
            }
            return clean
        }

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
