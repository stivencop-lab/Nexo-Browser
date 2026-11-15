package com.nexo.browser.extensions

/**
 * Qué parte de una extensión de escritorio puede funcionar en el runtime de
 * Nexo sobre WebView, según los permisos de su manifiesto. Se muestra antes
 * de instalar para no prometer más de lo que el motor permite.
 * Puro: probado en la JVM.
 */
object ExtensionCompatibility {
    enum class Level { FULL, PARTIAL, UNSUPPORTED }

    data class Report(
        val level: Level,
        /** Permisos que NXR implementa por completo. */
        val supported: List<String>,
        /** Permisos implementados con límites de WebView (motivo en español). */
        val limited: Map<String, String>,
        /** Permisos sin equivalente: la función que los usa no funcionará. */
        val unsupported: List<String>
    )

    /** Nivel 1 del diseño (§9.4 del documento de migración). */
    private val FULL = setOf(
        "storage", "unlimitedStorage", "tabs", "activeTab", "scripting", "alarms", "contextMenus",
        "menus", "notifications", "declarativeNetRequest", "declarativeNetRequestWithHostAccess",
        "declarativeNetRequestFeedback", "webNavigation", "i18n", "clipboardWrite"
    )

    private val LIMITED = mapOf(
        "webRequest" to "Sin cuerpo de las peticiones ni WebSocket; las redirecciones solo se ven al principio.",
        "webRequestBlocking" to "La decisión debe llegar en menos de 50 ms o la petición sigue.",
        "cookies" to "Las cookies HttpOnly no se pueden leer.",
        "downloads" to "Se descargan con el gestor de Nexo.",
        "history" to "Usa el historial de Nexo.",
        "bookmarks" to "Usa los accesos de la página de inicio.",
        "offscreen" to "Se ejecuta en una vista oculta.",
        "sidePanel" to "Se muestra como hoja inferior.",
        "userScripts" to "Los scripts de usuario comparten el mundo de la página.",
        "clipboardRead" to "Solo tras un gesto del usuario.",
        "webRequestFilterResponse" to "No se pueden reescribir respuestas.",
        "background" to "El fondo vive mientras Nexo está abierto.",
        "identity" to "El inicio de sesión se abre en una pestaña."
    )

    private val UNSUPPORTED = setOf(
        "debugger", "devtools", "nativeMessaging", "proxy", "privacy", "management", "enterprise.deviceAttributes",
        "enterprise.platformKeys", "platformKeys", "certificateProvider", "vpnProvider", "fileSystemProvider",
        "tabCapture", "desktopCapture", "pageCapture", "ttsEngine", "system.cpu", "system.memory", "system.display",
        "browsingData", "contentSettings", "fontSettings", "geckoProfiler", "dns", "theme", "sessions", "search",
        "tabGroups", "readingList", "documentScan", "printing", "printingMetrics", "loginState", "wallpaper"
    )

    fun evaluate(manifest: ExtensionManifest): Report {
        val all = manifest.permissions + manifest.optionalPermissions
        val supported = mutableListOf<String>()
        val limited = linkedMapOf<String, String>()
        val unsupported = mutableListOf<String>()
        for (permission in all.sorted()) {
            when {
                permission in FULL -> supported += permission
                permission in LIMITED -> limited[permission] = LIMITED.getValue(permission)
                permission in UNSUPPORTED -> unsupported += permission
                // Permisos menores o desconocidos: se ignoran como hace Chrome con un aviso.
                else -> limited[permission] = "Permiso no reconocido; se ignora."
            }
        }
        if (manifest.contentScripts.any { it.world == ExtensionManifest.World.ISOLATED }) {
            limited.putIfAbsent("content_scripts", "Sin mundo aislado del motor: comparten algunos objetos con la página.")
        }
        val required = manifest.permissions
        val level = when {
            required.any { it in UNSUPPORTED } && manifest.contentScripts.isEmpty() && manifest.action == null -> Level.UNSUPPORTED
            unsupported.isNotEmpty() || limited.isNotEmpty() -> Level.PARTIAL
            else -> Level.FULL
        }
        return Report(level, supported, limited, unsupported)
    }
}
