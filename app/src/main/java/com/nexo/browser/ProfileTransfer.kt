package com.nexo.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Copia cifrada del perfil de Nexo entre instalaciones: ajustes, historial,
 * accesos, pestañas normales y claves de servicios. Solo datos de Nexo: el
 * motor (cookies, almacenamiento de los sitios) no viaja. Se ejecuta en un
 * proceso nuevo con el motor detenido; los originales nunca se borran al
 * exportar.
 *
 * Las copias de versiones anteriores (formato 1) pueden traer datos del motor
 * antiguo (`gecko/`, `addons/`): se validan y se ignoran.
 */
object ProfileTransfer {
    private const val VERSION = 2

    private fun typed(value: Any): JSONObject = JSONObject().apply {
        put("type", when(value) { is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"; is String -> "string"; is Set<*> -> "set"; else -> error("Tipo no compatible") })
        put("value", if (value is Set<*>) JSONArray(value) else value)
    }

    fun export(context: Context, stage: File) {
        check(MigrationMode.cold && !MigrationMode.dataChanged && MigrationMode.ready(context) && !com.nexo.browser.engine.Engines.current.isStarted())
        require(!stage.exists()); stage.mkdirs()
        val native = JSONObject().put("version", VERSION).put("source", context.packageName)
        val prefs = JSONObject()
        for (name in listOf("nexo_browser_settings", "nexo_history", "nexo_quick_access")) {
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key,value) ->
                if (value != null && (name != "nexo_browser_settings" || key in TransferSchema.settingsKeys)) values.put(key, typed(value))
            }
            prefs.put(name, values)
        }
        native.put("prefs", prefs)
        native.put("tabs", JSONArray(TransferSchema.tabs(File(context.filesDir,"tabs.json").takeIf { it.isFile }?.readText() ?: BrowserPrefs.tabsJson(context) ?: "[]")))
        val secrets = JSONObject()
        // Read legacy keys through the normal accessor, then decrypt in this source package.
        val serviceKeys = TransferSchema.secretNames.associateWith { name ->
            if (SecretStore.has(context,name)) SecretStore.get(context,name).also { require(it.isNotEmpty()) { "No se puede descifrar una clave del origen" } }
            else context.getSharedPreferences("nexo_browser_settings", Context.MODE_PRIVATE).getString(name, "").orEmpty()
        }
        serviceKeys.forEach { (k,v) -> if (v.isNotEmpty()) secrets.put(k,v) }
        native.put("secrets", secrets)
        File(stage, "native.json").writeText(native.toString())
    }

    private fun xmlEscape(v: String): String {
        require(v.codePoints().allMatch { it == 9 || it == 10 || it == 13 || it in 0x20..0xd7ff || it in 0xe000..0xfffd || it in 0x10000..0x10ffff }) { "Carácter XML no válido" }
        return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    }
    private fun preferences(values: JSONObject): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map>")
        values.keySet().sorted().forEach { key ->
            require(key.length <= 128)
            val o = values.getJSONObject(key); val type = o.getString("type"); val name = xmlEscape(key)
            when(type) {
                "string" -> { val v = o.getString("value"); require(v.length <= 2*1024*1024); append("<string name=\"$name\">${xmlEscape(v)}</string>") }
                "boolean" -> append("<boolean name=\"$name\" value=\"${o.getBoolean("value")}\" />")
                "int" -> append("<int name=\"$name\" value=\"${o.getInt("value")}\" />")
                "long" -> append("<long name=\"$name\" value=\"${o.getLong("value")}\" />")
                "float" -> { val v = o.getDouble("value").toFloat(); require(v.isFinite()); append("<float name=\"$name\" value=\"$v\" />") }
                "set" -> { val list = o.getJSONArray("value"); require(list.length() <= 5000); append("<set name=\"$name\">"); for(i in 0 until list.length()) append("<string>${xmlEscape(list.getString(i))}</string>"); append("</set>") }
                else -> error("Tipo no compatible")
            }
        }
        append("</map>")
    }
    fun prepare(context: Context, stage: File): Map<String, File?> {
        check(MigrationMode.cold && !MigrationMode.dataChanged && MigrationMode.ready(context) && !com.nexo.browser.engine.Engines.current.isStarted())
        require(File(stage,"native.json").length() <= 8*1024*1024)
        // Formato 1: validar lo que trae del motor antiguo (que nada escape del esquema) y no aplicarlo.
        val legacy = File(stage,"addons.json").takeIf { it.isFile }?.let {
            require(it.length() <= 1024*1024)
            val map = JSONObject(it.readText()).getJSONObject("uuids")
            require(map.length() <= 200 && map.keySet().all { id -> TransferSchema.validId(id) && TransferSchema.uuid(map.getString(id)) })
            map
        } ?: JSONObject()
        val legacyUuids = legacy.keySet().map { legacy.getString(it) }.toSet()
        stage.walkTopDown().filter { it.isFile }.forEach {
            require(TransferSchema.payloadPath(it.relativeTo(stage).invariantSeparatorsPath, legacyUuids, legacy.keySet())) { "Archivo ajeno al esquema" }
        }
        val native = JSONObject(File(stage,"native.json").readText())
        require(native.getInt("version") in 1..VERSION && native.getString("source") in setOf("com.ejemplo.navegador","com.nexo.browser"))
        val output = File(stage.parentFile,"prepared").apply { deleteRecursively(); mkdirs() }
        val files = linkedMapOf<String,File?>()
        fun put(path: String, text: String) { val f = File(output,path); f.parentFile?.mkdirs(); f.writeText(text); files[path] = f }
        val prefs = native.getJSONObject("prefs")
        require(prefs.keySet() == setOf("nexo_browser_settings","nexo_history","nexo_quick_access"))
        val settings = prefs.getJSONObject("nexo_browser_settings")
        require(settings.keySet().all { it in TransferSchema.settingsKeys })
        settings.keySet().forEach { require(settings.getJSONObject(it).getString("type") == TransferSchema.settingsTypes[it]) }
        val history = prefs.getJSONObject("nexo_history")
        require(history.keySet().all { it == "entries" })
        if(history.has("entries")) {
            val item=history.getJSONObject("entries"); require(item.getString("type")=="string")
            val entries=JSONArray(item.getString("value")); require(entries.length()<=500)
            for(i in 0 until entries.length()) {
                val h=entries.getJSONObject(i); require(UrlSafety.isWebUrl(h.getString("url")) && h.getString("url").length<=8192 && h.getString("title").length<=8192)
                h.getLong("timestamp")
            }
        }
        val quick = prefs.getJSONObject("nexo_quick_access")
        require(quick.keySet().all { it in setOf("custom","hidden") })
        if(quick.has("custom")) {
            val item=quick.getJSONObject("custom"); require(item.getString("type")=="string")
            val links=JSONArray(item.getString("value")); require(links.length()<=500)
            for(i in 0 until links.length()) { val l=links.getJSONObject(i); require(UrlSafety.isWebUrl(l.getString("url")) && l.getString("url").length<=8192) }
        }
        if(quick.has("hidden")) require(quick.getJSONObject("hidden").getString("type")=="set")
        prefs.keySet().forEach { name -> put("shared_prefs/$name.xml", preferences(prefs.getJSONObject(name))) }
        put("files/tabs.json", TransferSchema.tabs(native.getJSONArray("tabs").toString()))
        val keys = native.getJSONObject("secrets"); require(keys.keySet().all { it in TransferSchema.secretNames })
        val encrypted = JSONObject()
        keys.keySet().forEach { key -> val v=keys.getString(key); require(v.length <= 16384); encrypted.put(key,typed(SecretStore.encrypt(v))) }
        put("shared_prefs/nexo_secrets.xml", preferences(encrypted))
        // Android AtomicFile and SharedPreferences prefer backup sidecars over the
        // primary file after a crash. Journal and remove them in the same commit.
        files.keys.toList().filter { it.startsWith("shared_prefs/") || it == "files/tabs.json" }.forEach { path ->
            for(suffix in listOf(".bak", ".new")) {
                if(File(context.applicationInfo.dataDir,path+suffix).isFile) files[path+suffix] = null
            }
        }
        require(files.size <= MigrationArchive.MAX_FILES + 100)
        return files
    }
}
