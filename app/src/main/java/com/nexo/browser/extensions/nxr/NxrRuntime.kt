package com.nexo.browser.extensions.nxr

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import com.nexo.browser.ExtensionEntry
import com.nexo.browser.NexoSheets
import com.nexo.browser.NexoUi
import com.nexo.browser.TabManager
import com.nexo.browser.ThemeManager
import com.nexo.browser.Toastish
import com.nexo.browser.engine.cefrium.CefriumEngineSession
import com.nexo.browser.extensions.ExtensionManifest
import com.nexo.browser.extensions.ExtensionMessages
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runtime NXR (Nexo Extensions Runtime) sobre APIs públicas de Cefrium.
 *
 * Capacidades reales implementadas:
 * - Instalación y desinstalación limpia de paquetes `.crx`, `.xpi` y `.zip`.
 * - Inyección progresiva de `content_scripts` (`js` y `css`) en `document_start`/`document_end`
 *   y `document_idle` respetando `MatchPattern`, consentimiento por sitio y modo privado.
 * - `chrome.storage.local` aislado por extensión con cuota estricta.
 * - `chrome.runtime` (`id`, `getManifest`, `sendMessage`, `onMessage`) y `chrome.i18n`.
 * - Registro de errores por extensión y notificación transparente de APIs no soportadas.
 *
 * Limitaciones honestas documentadas:
 * - Los service workers MV3 persistentes, `debugger`, `proxy`, `webRequestBlocking` de
 *   terceros y mundos V8 aislados a nivel de renderer no existen en la API pública de Cefrium.
 */
object NxrRuntime {
    private const val SHIM_ASSET = "nexo/nxr-runtime-shim.js"
    private const val ROOT_FOLDER = "nxr_extensions"
    private const val MAX_ERRORS_PER_EXT = 25

    data class DiagnosticEntry(val timestampMs: Long, val source: String, val message: String)

    @Volatile private var rootDir: File? = null
    @Volatile private var storage: NxrStorage? = null
    @Volatile private var shimSource: String? = null
    private val errors = ConcurrentHashMap<String, CopyOnWriteArrayList<DiagnosticEntry>>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun initialize(context: Context) {
        val dir = File(context.applicationContext.filesDir, ROOT_FOLDER)
        dir.mkdirs()
        rootDir = dir
        storage = NxrStorage(dir)
        if (shimSource == null) {
            shimSource = runCatching {
                context.applicationContext.assets.open(SHIM_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
    }

    /** Inicializa con un directorio personalizado (usado en pruebas JVM). */
    internal fun initializeForTest(dir: File, shim: String) {
        dir.mkdirs()
        rootDir = dir
        storage = NxrStorage(dir)
        shimSource = shim
        errors.clear()
    }

    fun rootDirectory(context: Context): File {
        return rootDir ?: File(context.applicationContext.filesDir, ROOT_FOLDER).also {
            it.mkdirs()
            rootDir = it
            if (storage == null) storage = NxrStorage(it)
        }
    }

    fun installed(context: Context? = null): List<NxrPackageInstaller.InstalledExtension> {
        val dir = rootDir ?: context?.let { rootDirectory(it) } ?: return emptyList()
        return NxrPackageInstaller.listInstalled(dir)
    }

    fun extensionEntries(context: Context? = null): List<ExtensionEntry> {
        val dir = rootDir ?: context?.let { rootDirectory(it) } ?: return emptyList()
        return NxrPackageInstaller.listInstalled(dir).map { ext ->
            val policy = NxrPermissions.readPolicy(NxrPackageInstaller.extensionDir(dir, ext.id))
            ExtensionEntry(ext.id, ext.name, policy.enabled, null)
        }
    }

    fun policyFor(extId: String): NxrPermissions.Policy {
        val dir = rootDir ?: return NxrPermissions.Policy(enabled = false)
        return NxrPermissions.readPolicy(NxrPackageInstaller.extensionDir(dir, extId))
    }

    fun setEnabled(context: Context, extId: String, enabled: Boolean): Boolean {
        val dir = rootDirectory(context)
        val extDir = NxrPackageInstaller.extensionDir(dir, extId)
        if (!extDir.isDirectory) return false
        val updated = NxrPermissions.readPolicy(extDir).copy(enabled = enabled)
        NxrPermissions.writePolicy(extDir, updated)
        return true
    }

    fun setAllowInPrivate(context: Context, extId: String, allow: Boolean): Boolean {
        val dir = rootDirectory(context)
        val extDir = NxrPackageInstaller.extensionDir(dir, extId)
        if (!extDir.isDirectory) return false
        val updated = NxrPermissions.readPolicy(extDir).copy(allowInPrivate = allow)
        NxrPermissions.writePolicy(extDir, updated)
        return true
    }

    fun setHostAllowed(context: Context, extId: String, host: String, allowed: Boolean): Boolean {
        val dir = rootDirectory(context)
        val extDir = NxrPackageInstaller.extensionDir(dir, extId)
        if (!extDir.isDirectory) return false
        val normalized = host.lowercase().removePrefix("www.").trim()
        if (normalized.isEmpty()) return false
        val current = NxrPermissions.readPolicy(extDir)
        val next = if (allowed) {
            current.copy(
                deniedHosts = current.deniedHosts - normalized,
                allowedHostsOverride = current.allowedHostsOverride + normalized
            )
        } else {
            current.copy(
                deniedHosts = current.deniedHosts + normalized,
                allowedHostsOverride = current.allowedHostsOverride - normalized
            )
        }
        NxrPermissions.writePolicy(extDir, next)
        return true
    }

    fun uninstall(context: Context, extId: String): Boolean {
        val dir = rootDirectory(context)
        errors.remove(extId)
        return NxrPackageInstaller.uninstall(dir, extId)
    }

    fun loadIcon(context: Context, extId: String, maxSizePx: Int): Bitmap? {
        val dir = rootDirectory(context)
        val meta = NxrPackageInstaller.listInstalled(dir).firstOrNull { it.id == extId } ?: return null
        val path = meta.iconPath ?: return null
        val file = File(NxrPackageInstaller.filesDir(dir, extId), path)
        if (!file.isFile || file.length() > 512 * 1024) return null
        return runCatching {
            val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            if (maxSizePx > 0 && (decoded.width > maxSizePx || decoded.height > maxSizePx)) {
                Bitmap.createScaledBitmap(decoded, maxSizePx, maxSizePx, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else decoded
        }.getOrNull()
    }

    fun diagnostics(extId: String): List<DiagnosticEntry> = errors[extId]?.toList() ?: emptyList()

    fun clearDiagnostics(extId: String) {
        errors.remove(extId)
    }

    fun recordError(extId: String, source: String, message: String) {
        val list = errors.getOrPut(extId) { CopyOnWriteArrayList() }
        list.add(DiagnosticEntry(System.currentTimeMillis(), source.take(40), message.take(300)))
        while (list.size > MAX_ERRORS_PER_EXT) {
            list.removeAt(0)
        }
    }

    // --- Inyección de content scripts en pestañas Cefrium ---

    fun onDocumentAvailable(session: CefriumEngineSession, url: String?) {
        if (url.isNullOrBlank()) return
        injectForStage(session, url, setOf(ExtensionManifest.RunAt.DOCUMENT_START, ExtensionManifest.RunAt.DOCUMENT_END))
    }

    fun onDocumentIdle(session: CefriumEngineSession, url: String?) {
        if (url.isNullOrBlank()) return
        injectForStage(session, url, setOf(ExtensionManifest.RunAt.DOCUMENT_IDLE))
    }

    private fun injectForStage(
        session: CefriumEngineSession,
        url: String,
        stages: Set<ExtensionManifest.RunAt>
    ) {
        val dir = rootDir ?: return
        val shim = shimSource ?: return
        if (!NxrPermissions.isWebHttpUrl(url)) return
        val installed = NxrPackageInstaller.listInstalled(dir)
        if (installed.isEmpty()) return

        for (ext in installed) {
            val extDir = NxrPackageInstaller.extensionDir(dir, ext.id)
            val policy = NxrPermissions.readPolicy(extDir)
            if (!NxrPermissions.isAuthorizedForUrl(policy, url, session.isPrivate)) continue
            val manifest = NxrPackageInstaller.readManifest(dir, ext.id) ?: continue
            val matching = manifest.contentScripts.filter { cs ->
                cs.runAt in stages && cs.matcher.matches(url)
            }
            if (matching.isEmpty()) continue

            val localeMap = buildLocaleJson(dir, ext.id, manifest)
            val manifestSummary = JSONObject()
                .put("manifest_version", manifest.manifestVersion)
                .put("name", ext.name)
                .put("version", manifest.version)
                .put("description", ext.description)

            for (cs in matching) {
                for (cssPath in cs.css) {
                    val cssText = NxrPackageInstaller.readPackagedText(dir, ext.id, cssPath) ?: continue
                    val cssScript = buildCssInjectionScript(ext.id, cssPath, cssText)
                    session.evaluateInMainFrame(cssScript) {}
                }
                if (cs.js.isNotEmpty()) {
                    val sources = cs.js.mapNotNull { path ->
                        NxrPackageInstaller.readPackagedText(dir, ext.id, path)?.let { path to it }
                    }
                    if (sources.isNotEmpty()) {
                        val jsBundle = buildJsBundleScript(
                            shim = shim,
                            extId = ext.id,
                            token = session.nxrToken,
                            manifestSummary = manifestSummary,
                            localeMap = localeMap,
                            scripts = sources
                        )
                        session.evaluateInMainFrame(jsBundle) {}
                    }
                }
            }
        }
    }

    /** Construye el script seguro que inyecta CSS de una extensión mediante `textContent`. */
    internal fun buildCssInjectionScript(extId: String, cssPath: String, cssText: String): String {
        val key = JSONObject.quote("$extId:$cssPath")
        val content = JSONObject.quote(cssText)
        return """
            (function(){
              try {
                var key = $key;
                if (document.querySelector('style[data-nxr-css="' + key.replace(/"/g, '') + '"]')) return;
                var el = document.createElement('style');
                el.setAttribute('data-nxr-css', key.replace(/"/g, ''));
                el.textContent = $content;
                (document.head || document.documentElement).appendChild(el);
              } catch (_) {}
            })();
        """.trimIndent()
    }

    /**
     * Envuelve los content scripts de una extensión en un cierre estricto con `chrome`/`browser`
     * acotado al token de la sesión. No usa `eval()` ni expone APIs nativas del navegador.
     */
    internal fun buildJsBundleScript(
        shim: String,
        extId: String,
        token: String,
        manifestSummary: JSONObject,
        localeMap: JSONObject,
        scripts: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.append("(function(){\n'use strict';\n")
        sb.append(shim).append("\n")
        sb.append("var __scope = createNxrScope({")
            .append("extId:").append(JSONObject.quote(extId))
            .append(",token:").append(JSONObject.quote(token))
            .append(",manifest:").append(manifestSummary.toString())
            .append(",messages:").append(localeMap.toString())
            .append("});\n")
        sb.append("var chrome = __scope.chrome; var browser = __scope.browser;\n")
        // Registro privado de despacho de acciones autenticado por token+extId.
        sb.append("if(!window.__nxrDispatch){try{Object.defineProperty(window,'__nxrDispatch',{value:Object.create(null),enumerable:false,configurable:false});}catch(_){}}\n")
        sb.append("try{window.__nxrDispatch[").append(JSONObject.quote("$token:$extId")).append("]=__scope.dispatchIncoming;}catch(_){}\n")
        for ((path, code) in scripts) {
            sb.append("try{\n")
                .append("(function(chrome,browser){\n")
                .append(code)
                .append("\n})(chrome,browser);\n")
                .append("}catch(__nxrErr){__scope.reportError(")
                .append(JSONObject.quote(path))
                .append(",__nxrErr);}\n")
        }
        sb.append("})();")
        return sb.toString()
    }

    private fun buildLocaleJson(rootDir: File, extId: String, manifest: ExtensionManifest): JSONObject {
        val out = JSONObject()
        val localeTag = Locale.getDefault().toLanguageTag()
        val messages = ExtensionMessages.load("", manifest.defaultLocale, localeTag) { folder ->
            NxrPackageInstaller.readPackagedText(rootDir, extId, "_locales/$folder/messages.json")
        }
        // Extraer claves si hay un messages.json localizado para chrome.i18n.getMessage.
        val candidates = listOfNotNull(
            localeTag.replace('-', '_'),
            localeTag.substringBefore('-'),
            manifest.defaultLocale
        ).distinct()
        for (folder in candidates) {
            val raw = NxrPackageInstaller.readPackagedText(rootDir, extId, "_locales/$folder/messages.json") ?: continue
            runCatching {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (!out.has(k)) {
                        val localized = messages.localize("__MSG_${k}__")
                        if (localized.isNotBlank()) out.put(k, localized)
                    }
                }
            }
        }
        return out
    }

    // --- Mensajes entrantes desde el canal de página (PageChannel) ---

    data class MessageReply(val result: Any? = null, val error: String? = null)

    /**
     * Valida y ejecuta un mensaje `nxr_storage`, `nxr_message` o `nxr_error`.
     * Requiere marco principal, token de sesión válido y autorización de origen.
     */
    fun onPageMessage(
        sessionToken: String,
        isPrivate: Boolean,
        origin: String,
        isMainFrame: Boolean,
        kind: String,
        payload: JSONObject?
    ): MessageReply {
        if (!isMainFrame || payload == null) return MessageReply(error = "Contexto no autorizado.")
        val token = payload.optString("token")
        if (token.isEmpty() || token != sessionToken) {
            return MessageReply(error = "Token NXR no válido.")
        }
        val extId = payload.optString("extId").takeIf { it.isNotBlank() }
            ?: return MessageReply(error = "Extensión no especificada.")
        val dir = rootDir ?: return MessageReply(error = "NXR no inicializado.")
        val extDir = NxrPackageInstaller.extensionDir(dir, extId)
        if (!extDir.isDirectory) return MessageReply(error = "Extensión no instalada.")
        val policy = NxrPermissions.readPolicy(extDir)
        if (!NxrPermissions.isAuthorizedForUrl(policy, origin, isPrivate)) {
            return MessageReply(error = "Sin permiso para este origen.")
        }

        return when (kind) {
            "nxr_storage" -> handleStorage(extId, payload)
            "nxr_error" -> {
                recordError(extId, payload.optString("source", "script"), payload.optString("message", "Error"))
                MessageReply(result = true)
            }
            "nxr_message" -> {
                // Mensajes de content script sin service worker: registrar de forma transparente.
                val msg = payload.opt("message")
                val type = (msg as? JSONObject)?.optString("type").orEmpty()
                if (type.isNotEmpty()) {
                    recordError(extId, "runtime.sendMessage", "Mensaje «$type» recibido (sin service worker de fondo en Cefrium).")
                }
                MessageReply(result = JSONObject().put("ok", true).put("nxr", true))
            }
            else -> MessageReply(error = "Operación NXR desconocida.")
        }
    }

    private fun handleStorage(extId: String, payload: JSONObject): MessageReply {
        val store = storage ?: return MessageReply(error = "Almacenamiento NXR no inicializado.")
        return runCatching {
            when (payload.optString("op")) {
                "get" -> MessageReply(result = store.get(extId, payload.opt("keys")))
                "set" -> {
                    val values = payload.optJSONObject("values")
                        ?: return MessageReply(error = "Se esperaba un objeto en storage.local.set.")
                    store.set(extId, values)
                    MessageReply(result = true)
                }
                "remove" -> {
                    store.remove(extId, payload.opt("keys"))
                    MessageReply(result = true)
                }
                "clear" -> {
                    store.clear(extId)
                    MessageReply(result = true)
                }
                else -> MessageReply(error = "Operación de almacenamiento no válida.")
            }
        }.getOrElse { err ->
            recordError(extId, "storage.local", err.message ?: "Error de almacenamiento")
            MessageReply(error = err.message ?: "Error de almacenamiento")
        }
    }

    /** Envía un evento de acción al content script de [extId] en la pestaña activa. */
    fun dispatchActionToActiveTab(extId: String): Boolean {
        val tab = TabManager.activeTab() ?: return false
        val session = tab.session as? CefriumEngineSession ?: return false
        val policy = policyFor(extId)
        if (!NxrPermissions.isAuthorizedForUrl(policy, tab.url, tab.isPrivate)) return false
        val key = JSONObject.quote("${session.nxrToken}:$extId")
        val payload = JSONObject()
            .put("type", "NXR_ACTION_CLICKED")
            .put("url", tab.url)
            .put("title", tab.title)
            .toString()
        val script = "(function(){try{var fn=window.__nxrDispatch&&window.__nxrDispatch[$key];if(typeof fn==='function')fn($payload);}catch(_){}})();"
        session.evaluateInMainFrame(script) {}
        return true
    }

    /** Hoja nativa de gestión y diagnóstico para una extensión instalada en NXR. */
    fun showExtensionSheet(activity: Activity, extId: String, onChanged: () -> Unit = {}) {
        val dir = rootDirectory(activity)
        val ext = NxrPackageInstaller.listInstalled(dir).firstOrNull { it.id == extId } ?: return
        val policy = NxrPermissions.readPolicy(NxrPackageInstaller.extensionDir(dir, ext.id))
        val activeTab = TabManager.activeTab()
        val activeHost = activeTab?.url?.takeIf { NxrPermissions.isWebHttpUrl(it) }?.let { NxrPermissions.extractHost(it) }
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)

        body.addView(NexoUi.switchRow(
            activity,
            "Extensión activa",
            "Ejecuta sus content scripts autorizados al cargar páginas coincidentes.",
            policy.enabled
        ) { enabled ->
            setEnabled(activity, ext.id, enabled)
            onChanged()
        })

        body.addView(NexoUi.switchRow(
            activity,
            "Permitir en pestañas privadas",
            "Desactivado por defecto para proteger tu privacidad.",
            policy.allowInPrivate
        ) { allow ->
            setAllowInPrivate(activity, ext.id, allow)
            onChanged()
        })

        if (!activeHost.isNullOrEmpty()) {
            val allowedHere = activeTab != null && NxrPermissions.isAuthorizedForUrl(policy, activeTab.url, activeTab.isPrivate)
            body.addView(NexoUi.switchRow(
                activity,
                "Permitir en $activeHost",
                if (allowedHere) "Autorizada en este sitio." else "Bloqueada o sin permiso en este sitio.",
                allowedHere
            ) { allow ->
                setHostAllowed(activity, ext.id, activeHost, allow)
                onChanged()
            })

            if (allowedHere) {
                body.addView(NexoUi.button(activity, "Ejecutar acción en esta pestaña", primary = true) {
                    val sent = dispatchActionToActiveTab(ext.id)
                    sheet.dismiss()
                    Toastish.show(activity, if (sent) "Acción enviada a ${ext.name}." else "Esta pestaña no admite acciones.")
                }, NexoUi.buttonParams(activity, 8f))
            }
        }

        // Permisos y alcance real
        body.addView(NexoUi.section(activity, "Permisos y compatibilidad"))
        val info = buildString {
            appendLine("Formato: ${ext.sourceType} · Manifest V${ext.manifestVersion} · v${ext.version}")
            if (ext.hostPermissions.isNotEmpty()) {
                appendLine("Sitios solicitados: ${ext.hostPermissions.take(6).joinToString(", ")}")
            }
            if (ext.permissions.isNotEmpty()) {
                appendLine("Permisos: ${ext.permissions.joinToString(", ")}")
            }
            if (ext.limitedApis.isNotEmpty()) {
                appendLine("Con límites: ${ext.limitedApis.keys.joinToString(", ")}")
            }
            if (ext.unsupportedApis.isNotEmpty()) {
                append("No soportado sobre Cefrium: ${ext.unsupportedApis.joinToString(", ")}")
            }
        }.trim()
        body.addView(NexoUi.note(activity, info))

        // Registro de errores recientes
        val logs = diagnostics(ext.id)
        body.addView(NexoUi.section(activity, "Registro de diagnóstico (${logs.size})"))
        if (logs.isEmpty()) {
            body.addView(NexoUi.note(activity, "Sin errores registrados en esta sesión."))
        } else {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            val text = logs.takeLast(6).joinToString("\n") { entry ->
                "[${fmt.format(Date(entry.timestampMs))}] ${entry.source}: ${entry.message}"
            }
            body.addView(NexoUi.note(activity, text))
        }

        val usedKb = (storage?.byteSize(ext.id) ?: 0L) / 1024L
        body.addView(NexoUi.note(activity, "Almacenamiento aislado: $usedKb KB / ${NxrStorage.MAX_STORAGE_BYTES / 1024} KB"))

        body.addView(NexoUi.button(activity, "Desinstalar extensión", danger = true) {
            AlertDialog.Builder(activity)
                .setTitle("¿Desinstalar ${ext.name}?")
                .setMessage("Se borrarán sus archivos, permisos y todo su almacenamiento local.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desinstalar") { _, _ ->
                    uninstall(activity, ext.id)
                    sheet.dismiss()
                    onChanged()
                    Toastish.show(activity, "${ext.name} desinstalada.")
                }.show()
        }, NexoUi.buttonParams(activity, 12f))

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, ext.name, ext.description.ifBlank { "Extensión NXR" }), body)
        )
    }
}
