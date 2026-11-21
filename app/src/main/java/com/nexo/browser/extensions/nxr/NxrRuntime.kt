package com.nexo.browser.extensions.nxr

import android.app.Activity
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
import com.nexo.browser.extensions.ExtensionCompatibility
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
 * - Estado de ciclo de vida honesto: Descargada / Instalada / Habilitada / Funcionando.
 *
 * Limitaciones honestas documentadas:
 * - Los service workers MV3 persistentes, `debugger`, `proxy`, `webRequestBlocking` de
 *   terceros y mundos V8 aislados a nivel de renderer no existen en la API pública de Cefrium.
 */
object NxrRuntime {
    private const val SHIM_ASSET = "nexo/nxr-runtime-shim.js"
    private const val ROOT_FOLDER = "nxr_extensions"
    private const val MAX_ERRORS_PER_EXT = 25
    private const val MAX_PAYLOAD_BYTES = 32 * 1024
    private const val RATE_WINDOW_MS = 10_000L
    private const val MAX_CALLS_PER_WINDOW = 30

    data class DiagnosticEntry(val timestampMs: Long, val source: String, val message: String)

    data class RuntimeStats(
        val injectionCount: Long = 0L,
        val lastInjectedAtMs: Long = 0L,
        val lastHost: String = ""
    )

    internal data class CapabilityGrant(
        val token: String,
        val sessionKey: Int,
        val extId: String,
        val originHost: String,
        val docGen: Long,
        val isPrivate: Boolean,
        val hasStoragePermission: Boolean
    )

    private class RateWindow {
        val timestamps = ArrayDeque<Long>()
    }

    @Volatile private var rootDir: File? = null
    @Volatile private var storage: NxrStorage? = null
    @Volatile private var shimSource: String? = null
    private val errors = ConcurrentHashMap<String, CopyOnWriteArrayList<DiagnosticEntry>>()
    private val runtimeStatsMap = ConcurrentHashMap<String, RuntimeStats>()
    private val grants = ConcurrentHashMap<String, CapabilityGrant>()
    private val panelTokens = ConcurrentHashMap<String, String>() // token -> extId
    private val actionNonces = ConcurrentHashMap<Pair<Int, String>, Pair<Long, String>>()
    private val injectedStages = ConcurrentHashMap<Int, Pair<Long, MutableSet<String>>>()
    private val rateWindows = ConcurrentHashMap<Pair<Int, String>, RateWindow>()
    private val random = java.security.SecureRandom()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun nextSecretHex(bytes: Int = 32): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    fun initialize(context: Context) {
        val dir = File(context.applicationContext.filesDir, ROOT_FOLDER)
        dir.mkdirs()
        NxrPackageInstaller.cleanupOrphanDirs(dir)
        rootDir = dir
        storage = NxrStorage(dir)
        DnrBridge.initialize(dir)
        if (shimSource == null) {
            shimSource = runCatching {
                context.applicationContext.assets.open(SHIM_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        }
    }

    /** Inicializa con un directorio personalizado (usado en pruebas JVM). */
    internal fun initializeForTest(dir: File, shim: String) {
        dir.mkdirs()
        NxrPackageInstaller.cleanupOrphanDirs(dir)
        rootDir = dir
        storage = NxrStorage(dir)
        shimSource = shim
        errors.clear()
        runtimeStatsMap.clear()
        grants.clear()
        panelTokens.clear()
        actionNonces.clear()
        injectedStages.clear()
        rateWindows.clear()
    }

    fun rootDirectory(context: Context): File {
        return rootDir ?: File(context.applicationContext.filesDir, ROOT_FOLDER).also {
            it.mkdirs()
            NxrPackageInstaller.cleanupOrphanDirs(it)
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

    fun runtimeStats(extId: String): RuntimeStats? = runtimeStatsMap[extId]

    internal fun recordInjection(extId: String, host: String, nowMs: Long = System.currentTimeMillis()) {
        runtimeStatsMap.compute(extId) { _, prev ->
            RuntimeStats(
                injectionCount = (prev?.injectionCount ?: 0L) + 1L,
                lastInjectedAtMs = nowMs,
                lastHost = host
            )
        }
    }

    /**
     * Devuelve el estado real y honesto del ciclo de vida de [extId]:
     * - [ExtensionCompatibility.LifecycleState.DOWNLOADED] si no está instalada.
     * - [ExtensionCompatibility.LifecycleState.INSTALLED] si está instalada pero desactivada.
     * - [ExtensionCompatibility.LifecycleState.ENABLED] si está habilitada pero aún no ha inyectado código en esta sesión.
     * - [ExtensionCompatibility.LifecycleState.RUNNING] solo cuando está habilitada y ha inyectado realmente CSS/JS en una pestaña.
     */
    fun lifecycleState(extId: String, context: Context? = null): ExtensionCompatibility.LifecycleState {
        val dir = rootDir ?: context?.let { rootDirectory(it) } ?: return ExtensionCompatibility.LifecycleState.DOWNLOADED
        val extDir = NxrPackageInstaller.extensionDir(dir, extId)
        if (!extDir.isDirectory) return ExtensionCompatibility.LifecycleState.DOWNLOADED
        val policy = NxrPermissions.readPolicy(extDir)
        if (!policy.enabled) return ExtensionCompatibility.LifecycleState.INSTALLED
        val stats = runtimeStatsMap[extId]
        return if (stats != null && stats.injectionCount > 0L || DnrBridge.hasBlocked(extId)) {
            ExtensionCompatibility.LifecycleState.RUNNING
        } else {
            ExtensionCompatibility.LifecycleState.ENABLED
        }
    }

    fun setEnabled(context: Context, extId: String, enabled: Boolean): Boolean {
        val dir = rootDirectory(context)
        val extDir = NxrPackageInstaller.extensionDir(dir, extId)
        if (!extDir.isDirectory) return false
        val updated = NxrPermissions.readPolicy(extDir).copy(enabled = enabled)
        NxrPermissions.writePolicy(extDir, updated)
        DnrBridge.refresh(dir, extId)
        if (!enabled) {
            revokeGrantsForExtension(extId)
            runtimeStatsMap.remove(extId)
        }
        return true
    }

    fun setAllowInPrivate(context: Context, extId: String, allow: Boolean): Boolean {
        val dir = rootDirectory(context)
        val extDir = NxrPackageInstaller.extensionDir(dir, extId)
        if (!extDir.isDirectory) return false
        val updated = NxrPermissions.readPolicy(extDir).copy(allowInPrivate = allow)
        NxrPermissions.writePolicy(extDir, updated)
        DnrBridge.refresh(dir, extId)
        if (!allow) revokeGrantsForExtension(extId, onlyPrivate = true)
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
        DnrBridge.refresh(dir, extId)
        if (!allowed) revokeGrantsForExtension(extId, originHost = normalized)
        return true
    }

    fun uninstall(context: Context, extId: String): Boolean {
        val dir = rootDirectory(context)
        revokeGrantsForExtension(extId)
        errors.remove(extId)
        runtimeStatsMap.remove(extId)
        return NxrPackageInstaller.uninstall(dir, extId).also {
            if (it) DnrBridge.refresh(dir, extId)
        }
    }

    /** Revoca inmediatamente las capacidades activas de [extId] (total, privadas o de un host). */
    internal fun revokeGrantsForExtension(
        extId: String,
        originHost: String? = null,
        onlyPrivate: Boolean = false
    ) {
        val cleanHost = originHost?.lowercase()?.removePrefix("www.")?.trim()
        val it = grants.entries.iterator()
        while (it.hasNext()) {
            val g = it.next().value
            if (g.extId != extId) continue
            if (onlyPrivate && !g.isPrivate) continue
            if (cleanHost != null && g.originHost != cleanHost) continue
            it.remove()
        }
        if (cleanHost == null && !onlyPrivate) {
            actionNonces.keys.removeIf { it.second == extId }
        }
    }

    /** Emite un token de capacidad efímero ligado estrictamente a `(sessionKey, extId, originHost, docGen)`. */
    internal fun issueGrant(
        sessionKey: Int,
        extId: String,
        url: String,
        docGen: Long,
        isPrivate: Boolean,
        hasStoragePermission: Boolean
    ): CapabilityGrant {
        val host = NxrPermissions.extractHost(url)
        val token = nextSecretHex(32)
        val grant = CapabilityGrant(
            token = token,
            sessionKey = sessionKey,
            extId = extId,
            originHost = host,
            docGen = docGen,
            isPrivate = isPrivate,
            hasStoragePermission = hasStoragePermission
        )
        grants[token] = grant
        return grant
    }

    internal fun onNewDocument(session: CefriumEngineSession) {
        clearSessionGrants(System.identityHashCode(session))
    }

    internal fun forgetSession(session: CefriumEngineSession) {
        clearSessionGrants(System.identityHashCode(session))
    }

    internal fun clearSessionGrants(sessionKey: Int) {
        grants.entries.removeIf { it.value.sessionKey == sessionKey }
        actionNonces.keys.removeIf { it.first == sessionKey }
        injectedStages.remove(sessionKey)
        rateWindows.keys.removeIf { it.first == sessionKey }
    }

    fun loadIcon(context: Context, extId: String, maxSizePx: Int): Bitmap? {
        val dir = rootDirectory(context)
        val meta = NxrPackageInstaller.listInstalled(dir).firstOrNull { it.id == extId } ?: return null
        val path = meta.iconPath ?: return null
        val file = File(NxrPackageInstaller.filesDir(dir, extId), path)
        if (!file.isFile || file.length() > 4 * 1024 * 1024) return null
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

    fun diagnosticsSummary(): String {
        val count = rootDir?.let { NxrPackageInstaller.listInstalled(it).size } ?: 0
        val totalErrors = errors.values.sumOf { it.size }
        return "instaladas=$count · grants=${grants.size} · errores=$totalErrors"
    }

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
        // Orden estricto: primero document_start y después document_end, deduplicados por generación.
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_START)
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_END)
    }

    fun onDocumentIdle(session: CefriumEngineSession, url: String?) {
        if (url.isNullOrBlank()) return
        // Cubrir también document_start/end si la página llegó desde caché sin disparar onDocumentAvailable.
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_START)
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_END)
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_IDLE)
    }

    /**
     * Navegación SPA (`history.pushState` / cambios de ruta sin recarga completa, p. ej. YouTube `/watch`):
     * inyecta scripts cuyos patrones ahora coinciden con [url] sin duplicar los ya ejecutados.
     */
    fun onUrlChanged(session: CefriumEngineSession, url: String?) {
        if (url.isNullOrBlank()) return
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_START)
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_END)
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_IDLE)
    }

    /**
     * Inyecta inmediatamente [extId] en la pestaña activa si la página actual coincide y está autorizada
     * (tras instalar o activar la extensión sin obligar al usuario a recargar manualmente).
     */
    fun injectActiveTabIfMatching(extId: String) {
        val tab = TabManager.activeTab() ?: return
        val session = tab.session as? CefriumEngineSession ?: return
        val url = session.currentLocationUrl() ?: tab.url
        if (url.isBlank() || !NxrPermissions.isWebHttpUrl(url)) return
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_START, onlyExtId = extId)
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_END, onlyExtId = extId)
        injectForStage(session, url, ExtensionManifest.RunAt.DOCUMENT_IDLE, onlyExtId = extId)
    }

    /** Marca `(sessionKey, docGen, marker)` como inyectado; devuelve false si ya se ejecutó en este documento. */
    internal fun markStageInjected(sessionKey: Int, docGen: Long, marker: String): Boolean {
        val current = injectedStages.compute(sessionKey) { _, prev ->
            if (prev == null || prev.first != docGen) {
                docGen to java.util.Collections.synchronizedSet(HashSet())
            } else prev
        } ?: return false
        return current.second.add(marker)
    }

    private fun injectForStage(
        session: CefriumEngineSession,
        url: String,
        stage: ExtensionManifest.RunAt,
        onlyExtId: String? = null
    ) {
        val dir = rootDir ?: return
        val shim = shimSource ?: return
        if (!NxrPermissions.isWebHttpUrl(url)) return
        val installed = NxrPackageInstaller.listInstalled(dir)
        if (installed.isEmpty()) return
        val sessionKey = System.identityHashCode(session)
        val docGen = session.documentGeneration()

        for (ext in installed) {
            if (onlyExtId != null && ext.id != onlyExtId) continue
            val extDir = NxrPackageInstaller.extensionDir(dir, ext.id)
            val policy = NxrPermissions.readPolicy(extDir)
            if (!NxrPermissions.isAuthorizedForUrl(policy, url, session.isPrivate)) continue
            val manifest = NxrPackageInstaller.readManifest(dir, ext.id) ?: continue
            val matching = manifest.contentScripts.mapIndexedNotNull { idx, cs ->
                if (cs.runAt == stage && cs.matcher.matches(url)) idx to cs else null
            }
            if (matching.isEmpty()) continue

            val localeMap = buildLocaleJson(dir, ext.id, manifest)
            val manifestSummary = JSONObject()
                .put("manifest_version", manifest.manifestVersion)
                .put("name", ext.name)
                .put("version", manifest.version)
                .put("description", ext.description)
                .put("permissions", JSONArray(manifest.permissions))
                .put("host_permissions", JSONArray(manifest.hostPermissions))
            val hasStoragePerm = "storage" in manifest.permissions || "unlimitedStorage" in manifest.permissions

            val host = NxrPermissions.extractHost(url)
            for ((idx, cs) in matching) {
                if (!markStageInjected(sessionKey, docGen, "${ext.id}:${stage.name}:$idx")) continue
                var injectedAny = false
                for (cssPath in cs.css) {
                    val cssText = NxrPackageInstaller.readPackagedText(dir, ext.id, cssPath) ?: continue
                    val cssScript = buildCssInjectionScript(ext.id, cssPath, cssText)
                    session.evaluateInMainFrame(cssScript) {}
                    injectedAny = true
                }
                if (cs.js.isNotEmpty()) {
                    val sources = cs.js.mapNotNull { path ->
                        NxrPackageInstaller.readPackagedText(dir, ext.id, path)?.let { path to it }
                    }
                    if (sources.isNotEmpty()) {
                        val grant = issueGrant(
                            sessionKey = sessionKey,
                            extId = ext.id,
                            url = url,
                            docGen = docGen,
                            isPrivate = session.isPrivate,
                            hasStoragePermission = hasStoragePerm
                        )
                        val actionNonce = actionNonces.compute(sessionKey to ext.id) { _, prev ->
                            if (prev != null && prev.first == docGen) prev else docGen to nextSecretHex(16)
                        }!!.second
                        val jsBundle = buildJsBundleScript(
                            shim = shim,
                            extId = ext.id,
                            token = grant.token,
                            actionNonce = actionNonce,
                            manifestSummary = manifestSummary,
                            localeMap = localeMap,
                            scripts = sources
                        )
                        session.evaluateInMainFrame(jsBundle) {}
                        injectedAny = true
                    }
                }
                if (injectedAny) recordInjection(ext.id, host)
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
     * Envuelve los content scripts de una extensión en un cierre con `chrome`/`browser`
     * acotado al token efímero de capacidad. Nunca escribe tokens ni tablas en `window`.
     *
     * Todos los archivos `js` de una misma entrada `content_scripts` comparten el mismo ámbito
     * léxico (igual que Chromium), y no se fuerza `'use strict'` sobre código de terceros
     * para evitar fallos en bundles MV2/MV3 minificados.
     * Las acciones del usuario se despachan mediante un `CustomEvent` con [actionNonce] independiente
     * del token de capacidad.
     */
    internal fun buildJsBundleScript(
        shim: String,
        extId: String,
        token: String,
        actionNonce: String,
        manifestSummary: JSONObject,
        localeMap: JSONObject,
        scripts: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.append("(function(){\n")
        sb.append("var __scope = (function(){\n'use strict';\n")
        sb.append(shim).append("\n")
        sb.append("return createNxrScope({")
            .append("extId:").append(JSONObject.quote(extId))
            .append(",token:").append(JSONObject.quote(token))
            .append(",manifest:").append(manifestSummary.toString())
            .append(",messages:").append(localeMap.toString())
            .append("});\n})();\n")
        // Canal de acciones independiente del token de capacidad (cero propiedades en window).
        sb.append("if(typeof window!=='undefined'&&typeof window.addEventListener==='function'){")
            .append("window.addEventListener(").append(JSONObject.quote("nxr-act:$actionNonce"))
            .append(",function(ev){try{if(ev&&ev.detail)__scope.dispatchIncoming(ev.detail);}catch(_){}});}\n")
        val bundleLabel = scripts.joinToString(",") { it.first }.take(120).ifBlank { "content_scripts" }
        sb.append("(function(chrome,browser){\ntry{\n")
        for ((index, pair) in scripts.withIndex()) {
            if (index > 0) sb.append("\n;\n")
            sb.append(pair.second).append("\n")
        }
        sb.append("}catch(__nxrErr){__scope.reportError(")
            .append(JSONObject.quote(bundleLabel))
            .append(",__nxrErr);}\n")
            .append("})(__scope.chrome,__scope.browser);\n")
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

    private fun checkRateLimit(sessionKey: Int, extId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val win = rateWindows.getOrPut(sessionKey to extId) { RateWindow() }
        synchronized(win) {
            while (win.timestamps.isNotEmpty() && nowMs - win.timestamps.first() > RATE_WINDOW_MS) {
                win.timestamps.removeFirst()
            }
            if (win.timestamps.size >= MAX_CALLS_PER_WINDOW) return false
            win.timestamps.addLast(nowMs)
            return true
        }
    }

    /**
     * Valida y ejecuta un mensaje `nxr_storage`, `nxr_message` o `nxr_error`.
     * Requiere marco principal, token de capacidad efímero válido para `(sessionKey, extId, originHost, docGen)`,
     * límite de tamaño y tasa, autorización vigente de origen y permiso `"storage"` cuando aplica.
     */
    fun onPageMessage(
        sessionKey: Int,
        docGen: Long,
        isPrivate: Boolean,
        origin: String,
        isMainFrame: Boolean,
        kind: String,
        payload: JSONObject?
    ): MessageReply {
        if (!isMainFrame || payload == null) return MessageReply(error = "Contexto no autorizado.")
        if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            return MessageReply(error = "Carga útil NXR demasiado grande.")
        }
        val token = payload.optString("token")
        if (token.isEmpty()) return MessageReply(error = "Token NXR no válido.")
        val grant = grants[token] ?: return MessageReply(error = "Token NXR no válido o revocado.")

        val extId = payload.optString("extId").takeIf { it.isNotBlank() }
            ?: return MessageReply(error = "Extensión no especificada.")
        val originHost = NxrPermissions.extractHost(origin)
        if (grant.sessionKey != sessionKey ||
            grant.docGen != docGen ||
            grant.extId != extId ||
            grant.isPrivate != isPrivate ||
            grant.originHost != originHost
        ) {
            return MessageReply(error = "Capacidad NXR no coincide con el contexto actual.")
        }

        val dir = rootDir ?: return MessageReply(error = "NXR no inicializado.")
        val extDir = NxrPackageInstaller.extensionDir(dir, extId)
        if (!extDir.isDirectory) return MessageReply(error = "Extensión no instalada.")
        val policy = NxrPermissions.readPolicy(extDir)
        if (!NxrPermissions.isAuthorizedForUrl(policy, origin, isPrivate)) {
            grants.remove(token)
            return MessageReply(error = "Sin permiso para este origen.")
        }
        if (!checkRateLimit(sessionKey, extId)) {
            recordError(extId, "rate_limit", "Demasiadas llamadas NXR en ${RATE_WINDOW_MS / 1000} s.")
            return MessageReply(error = "Límite de frecuencia NXR excedido.")
        }

        return when (kind) {
            "nxr_storage" -> {
                if (!grant.hasStoragePermission) {
                    recordError(extId, "storage.local", "Permiso 'storage' no declarado en manifest.json.")
                    return MessageReply(error = "La extensión no declara el permiso 'storage'.")
                }
                handleStorage(extId, grant.originHost, payload)
            }
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

    /** Emite un token efímero para la interfaz HTML (`popup`/`options`) de [extId] en [NxrExtensionPanel]. */
    internal fun issuePanelToken(extId: String): String {
        val token = nextSecretHex(32)
        panelTokens[token] = extId
        return token
    }

    internal fun revokePanelToken(token: String) {
        panelTokens.remove(token)
    }

    /** Construye el script `document_start` que expone `chrome`/`browser` dentro de [NxrExtensionPanel]. */
    internal fun buildExtensionUiBootScript(
        context: Context,
        extId: String,
        panelToken: String,
        baseUrl: String
    ): String? {
        initialize(context)
        val dir = rootDir ?: return null
        val shim = shimSource ?: return null
        val ext = NxrPackageInstaller.listInstalled(dir).firstOrNull { it.id == extId } ?: return null
        val manifest = NxrPackageInstaller.readManifest(dir, extId) ?: return null
        val localeMap = buildLocaleJson(dir, extId, manifest)
        val manifestSummary = JSONObject()
            .put("manifest_version", manifest.manifestVersion)
            .put("name", ext.name)
            .put("version", manifest.version)
            .put("description", ext.description)
            .put("permissions", org.json.JSONArray(manifest.permissions.sorted()))
            .put("host_permissions", org.json.JSONArray(NxrPermissions.requestedHostPatterns(manifest)))
        return buildString {
            append("(function(){\n'use strict';\n")
            append(shim).append("\n")
            append("var __scope = createNxrScope({")
                .append("extId:").append(JSONObject.quote(extId))
                .append(",token:").append(JSONObject.quote(panelToken))
                .append(",baseUrl:").append(JSONObject.quote(baseUrl))
                .append(",manifest:").append(manifestSummary.toString())
                .append(",messages:").append(localeMap.toString())
                .append("});\n")
            append("window.chrome = __scope.chrome; window.browser = __scope.browser;\n")
            append("})();")
        }
    }

    /** Maneja mensajes `nxr_storage`, `nxr_message` y `nxr_error` provenientes de [NxrExtensionPanel]. */
    internal fun onPanelMessage(
        extId: String,
        panelToken: String,
        kind: String,
        payload: JSONObject?,
        originHost: String? = null
    ): MessageReply {
        if (payload == null) return MessageReply(error = "Carga útil vacía.")
        val token = payload.optString("token")
        if (token != panelToken || panelTokens[token] != extId) {
            return MessageReply(error = "Token de panel NXR no válido.")
        }
        val activeHost = originHost ?: runCatching {
            TabManager.activeTab()?.url
                ?.takeIf { NxrPermissions.isWebHttpUrl(it) }
                ?.let { NxrPermissions.extractHost(it) }
        }.getOrNull()
        return when (kind) {
            "nxr_storage" -> handleStorage(extId, activeHost, payload)
            "nxr_error" -> {
                recordError(extId, payload.optString("source", "panel"), payload.optString("message", "Error"))
                MessageReply(result = true)
            }
            "nxr_message" -> {
                val msg = payload.opt("message")
                val delivered = dispatchMessageToActiveTab(extId, msg)
                MessageReply(result = JSONObject().put("ok", true).put("nxr", true).put("delivered", delivered))
            }
            else -> MessageReply(error = "Operación NXR desconocida.")
        }
    }

    private fun handleStorage(extId: String, originHost: String?, payload: JSONObject): MessageReply {
        val store = storage ?: return MessageReply(error = "Almacenamiento NXR no inicializado.")
        return runCatching {
            when (payload.optString("op")) {
                "get" -> MessageReply(result = store.get(extId, payload.opt("keys"), originHost))
                "set" -> {
                    val values = payload.optJSONObject("values")
                        ?: return MessageReply(error = "Se esperaba un objeto en storage.local.set.")
                    store.set(extId, values, originHost)
                    MessageReply(result = true)
                }
                "remove" -> {
                    store.remove(extId, payload.opt("keys"), originHost)
                    MessageReply(result = true)
                }
                "clear" -> {
                    store.clear(extId, originHost)
                    MessageReply(result = true)
                }
                else -> MessageReply(error = "Operación de almacenamiento no válida.")
            }
        }.getOrElse { err ->
            recordError(extId, "storage.local", err.message ?: "Error de almacenamiento")
            MessageReply(error = err.message ?: "Error de almacenamiento")
        }
    }

    /** Reenvía un mensaje JSON desde el popup/opciones de [extId] a sus content scripts en la pestaña activa. */
    fun dispatchMessageToActiveTab(extId: String, message: Any?): Boolean {
        val tab = TabManager.activeTab() ?: return false
        val session = tab.session as? CefriumEngineSession ?: return false
        val policy = policyFor(extId)
        if (!NxrPermissions.isAuthorizedForUrl(policy, tab.url, tab.isPrivate)) return false
        val sessionKey = System.identityHashCode(session)
        val docGen = session.documentGeneration()
        val slot = actionNonces[sessionKey to extId] ?: return false
        if (slot.first != docGen) return false
        val eventName = JSONObject.quote("nxr-act:${slot.second}")
        val detailJson = when (message) {
            is JSONObject -> message.toString()
            is org.json.JSONArray -> message.toString()
            is String -> JSONObject.quote(message)
            is Number, is Boolean -> message.toString()
            else -> JSONObject().put("type", "NXR_MESSAGE").toString()
        }
        val script = "(function(){try{window.dispatchEvent(new CustomEvent($eventName,{detail:$detailJson}));}catch(_){}})();"
        session.evaluateInMainFrame(script) {}
        return true
    }

    /** Envía un evento de acción al content script de [extId] en la pestaña activa mediante `CustomEvent` efímero. */
    fun dispatchActionToActiveTab(extId: String): Boolean {
        val tab = TabManager.activeTab() ?: return false
        return dispatchMessageToActiveTab(
            extId,
            JSONObject()
                .put("type", "NXR_ACTION_CLICKED")
                .put("url", tab.url)
                .put("title", tab.title)
        )
    }

    /** Hoja nativa de gestión y diagnóstico para una extensión instalada en NXR. */
    fun showExtensionSheet(
        activity: Activity,
        extId: String,
        onCheckUpdate: (() -> Unit)? = null,
        onChanged: () -> Unit = {}
    ) {
        val dir = rootDirectory(activity)
        val ext = NxrPackageInstaller.listInstalled(dir).firstOrNull { it.id == extId } ?: return
        val manifest = NxrPackageInstaller.readManifest(dir, ext.id)
        val policy = NxrPermissions.readPolicy(NxrPackageInstaller.extensionDir(dir, ext.id))
        val state = lifecycleState(ext.id, activity)
        val stats = runtimeStats(ext.id)
        val activeTab = TabManager.activeTab()
        val activeHost = activeTab?.url?.takeIf { NxrPermissions.isWebHttpUrl(it) }?.let { NxrPermissions.extractHost(it) }
        lateinit var sheet: Dialog
        val body = NexoUi.column(activity)

        // Estado real del ciclo de vida + firma criptográfica
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, NexoUi.dp(activity, 8f))
        }
        val dnrRules = DnrBridge.ruleCount(ext.id)
        val stateTone = when (state) {
            ExtensionCompatibility.LifecycleState.RUNNING -> NexoUi.Tone.SUCCESS
            ExtensionCompatibility.LifecycleState.ENABLED -> NexoUi.Tone.INFO
            ExtensionCompatibility.LifecycleState.INSTALLED -> NexoUi.Tone.NEUTRAL
            ExtensionCompatibility.LifecycleState.DOWNLOADED -> NexoUi.Tone.WARNING
        }
        statusRow.addView(NexoUi.statusPill(activity, "Estado: ${state.label}", stateTone))
        statusRow.addView(
            NexoUi.statusPill(
                activity,
                if (ext.signatureVerified) "Firma CRX verificada" else "Sin firma CRX",
                if (ext.signatureVerified) NexoUi.Tone.SUCCESS else NexoUi.Tone.WARNING
            ),
            LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = NexoUi.dp(activity, 8f) }
        )
        body.addView(statusRow)
        if (dnrRules > 0) body.addView(NexoUi.note(activity, "Reglas DNR estáticas cargadas: $dnrRules"))

        if (stats != null && stats.injectionCount > 0L) {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            body.addView(NexoUi.note(
                activity,
                "Ejecuciones reales en esta sesión: ${stats.injectionCount} · Último sitio: ${stats.lastHost} (${fmt.format(Date(stats.lastInjectedAtMs))})"
            ))
        } else if (policy.enabled && manifest?.contentScripts?.isNotEmpty() == true) {
            body.addView(NexoUi.note(
                activity,
                "Habilitada y lista: se inyectará automáticamente al abrir o recargar páginas coincidentes."
            ))
        }

        // Interfaces HTML reales de la extensión (popup y opciones en visor Cefrium aislado)
        val popupPage = manifest?.action?.popup
        val optionsPage = manifest?.optionsPage
        if (!popupPage.isNullOrBlank()) {
            body.addView(NexoUi.button(activity, "Abrir interfaz de ${ext.name}", primary = true) {
                sheet.dismiss()
                if (!NxrExtensionPanel.show(activity, ext.id, ext.name, popupPage)) {
                    Toastish.show(activity, "No se pudo abrir la interfaz HTML de ${ext.name}.")
                }
            }, NexoUi.buttonParams(activity, 6f))
        }
        if (!optionsPage.isNullOrBlank()) {
            body.addView(NexoUi.button(activity, "Abrir opciones de la extensión", primary = popupPage.isNullOrBlank()) {
                sheet.dismiss()
                if (!NxrExtensionPanel.show(activity, ext.id, "${ext.name} · Opciones", optionsPage)) {
                    Toastish.show(activity, "No se pudo abrir la página de opciones.")
                }
            }, NexoUi.buttonParams(activity, 6f))
        }

        body.addView(NexoUi.switchRow(
            activity,
            "Extensión activa",
            "Ejecuta sus content scripts autorizados al cargar páginas coincidentes.",
            policy.enabled
        ) { enabled ->
            setEnabled(activity, ext.id, enabled)
            if (enabled) injectActiveTabIfMatching(ext.id)
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

        if (activeTab != null && !activeHost.isNullOrEmpty()) {
            val allowedHere = NxrPermissions.isAuthorizedForUrl(policy, activeTab.url, activeTab.isPrivate)
            body.addView(NexoUi.switchRow(
                activity,
                "Permitir en $activeHost",
                if (allowedHere) "Autorizada en este sitio." else "Bloqueada o sin permiso en este sitio.",
                allowedHere
            ) { allow ->
                setHostAllowed(activity, ext.id, activeHost, allow)
                if (allow) injectActiveTabIfMatching(ext.id)
                onChanged()
            })

            if (allowedHere) {
                body.addView(NexoUi.button(activity, "Ejecutar acción en esta pestaña", primary = popupPage.isNullOrBlank() && optionsPage.isNullOrBlank()) {
                    val sent = dispatchActionToActiveTab(ext.id)
                    sheet.dismiss()
                    Toastish.show(activity, if (sent) "Acción enviada a ${ext.name}." else "Recarga la pestaña para inicializar la extensión.")
                }, NexoUi.buttonParams(activity, 8f))
            }
        }

        // Procedencia y verificación criptográfica
        body.addView(NexoUi.section(activity, "Origen y verificación"))
        val provenance = buildString {
            appendLine("Fuente: ${ext.sourceInfo().originLabel()} · Formato: ${ext.sourceType} · Manifest V${ext.manifestVersion} · v${ext.version}")
            if (!ext.developer.isNullOrBlank()) appendLine("Desarrollador: ${ext.developer}")
            appendLine("ID: ${ext.id}")
            if (!ext.publicKeySha256.isNullOrBlank()) {
                append("Clave pública SHA-256: ${ext.publicKeySha256.take(16)}…${ext.publicKeySha256.takeLast(8)}")
            } else {
                append("Clave pública: paquete sin firma CRX (continuidad no aplicable).")
            }
        }.trim()
        body.addView(NexoUi.note(activity, provenance))

        // Permisos y alcance real (matriz de compatibilidad sobre Cefrium 0.21.0)
        body.addView(NexoUi.section(activity, "Permisos y compatibilidad (Cefrium 0.21.0)"))
        val info = buildString {
            if (ext.hostPermissions.isNotEmpty()) {
                appendLine("Sitios solicitados: ${ext.hostPermissions.take(6).joinToString(", ")}")
            }
            if (ext.permissions.isNotEmpty()) {
                appendLine("Permisos declarados: ${ext.permissions.joinToString(", ")}")
            }
            if (ext.limitedApis.isNotEmpty()) {
                appendLine("Con límites: ${ext.limitedApis.entries.joinToString("; ") { "${it.key} (${it.value})" }}")
            }
            if (ext.unsupportedApis.isNotEmpty()) {
                append("No soportado en Cefrium: ${ext.unsupportedApis.joinToString(", ")}")
            }
        }.trim().ifBlank { "Sin permisos privilegiados ni APIs restringidas." }
        body.addView(NexoUi.note(activity, info))

        if (onCheckUpdate != null) {
            body.addView(NexoUi.button(activity, "Buscar actualización", primary = false) {
                sheet.dismiss()
                onCheckUpdate()
            }, NexoUi.buttonParams(activity, 8f))
        }

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
            lateinit var confirmSheet: Dialog
            val confirmBody = NexoUi.column(activity)
            confirmBody.addView(NexoUi.note(activity, "Se borrarán sus archivos extraídos, permisos y todo su almacenamiento aislado en Nexo."))
            confirmBody.addView(NexoUi.button(activity, "Sí, desinstalar", danger = true) {
                uninstall(activity, ext.id)
                confirmSheet.dismiss()
                sheet.dismiss()
                onChanged()
                Toastish.show(activity, "${ext.name} desinstalada.")
            }, NexoUi.buttonParams(activity, 10f))
            confirmBody.addView(NexoUi.button(activity, "Cancelar", primary = false) {
                confirmSheet.dismiss()
            }, NexoUi.buttonParams(activity, 6f))
            confirmSheet = NexoSheets.show(
                activity,
                NexoSheets.column(activity, NexoSheets.header(activity, "¿Desinstalar ${ext.name}?", "Esta acción no se puede deshacer"), confirmBody)
            )
        }, NexoUi.buttonParams(activity, 12f))

        sheet = NexoSheets.show(
            activity,
            NexoSheets.column(activity, NexoSheets.header(activity, ext.name, ext.description.ifBlank { "Extensión NXR" }), body)
        )
    }
}
