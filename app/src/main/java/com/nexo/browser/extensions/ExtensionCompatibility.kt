package com.nexo.browser.extensions

/**
 * Matriz honesta de compatibilidad de WebExtensions sobre NXR (Nexo Extensions Runtime)
 * y las APIs públicas de Cefrium 0.21.0 (Chromium embebido).
 *
 * Nunca promete capacidades que el motor no expone. Distingue explícitamente:
 * - APIs soportadas, limitadas y no soportadas (matriz de 20 APIs clave).
 * - Veredicto de instalación ([InstallVerdict]).
 * - Ciclo de vida real de 4 estados ([LifecycleState]: Descargada, Instalada, Habilitada, Funcionando).
 * Puro: probado en la JVM.
 */
object ExtensionCompatibility {
    enum class Level { FULL, PARTIAL, UNSUPPORTED }

    enum class ApiSupport(val label: String) {
        SUPPORTED("Soportada"),
        LIMITED("Limitada"),
        UNSUPPORTED("No soportada")
    }

    enum class InstallVerdict(val label: String) {
        FULL("Compatible"),
        PARTIAL("Compatible parcialmente"),
        LIMITED_INSTALLABLE("Instalable con límites"),
        UNSUPPORTED("No compatible en Cefrium"),
        BLOCKED_SECURITY("Bloqueada por seguridad"),
        UNVERIFIABLE("Paquete sin firma verificable")
    }

    enum class LifecycleState(val label: String) {
        DOWNLOADED("Descargada"),
        INSTALLED("Instalada"),
        ENABLED("Habilitada"),
        RUNNING("Funcionando")
    }

    data class ApiMatrixRow(
        val api: String,
        val support: ApiSupport,
        val note: String
    )

    data class Report(
        val level: Level,
        /** Permisos que NXR implementa de forma real y completa en su alcance. */
        val supported: List<String>,
        /** Permisos implementados con límites claros sobre Cefrium (motivo en español). */
        val limited: Map<String, String>,
        /** Permisos sin equivalente en la API pública de Cefrium: no funcionarán. */
        val unsupported: List<String>,
        val verdict: InstallVerdict = when (level) {
            Level.FULL -> InstallVerdict.FULL
            Level.PARTIAL -> InstallVerdict.PARTIAL
            Level.UNSUPPORTED -> InstallVerdict.UNSUPPORTED
        },
        val apiBreakdown: List<ApiMatrixRow> = emptyList()
    )

    /** Matriz pública y auditable de las 20 APIs principales de WebExtensions sobre Cefrium 0.21.0. */
    val API_MATRIX: List<ApiMatrixRow> = listOf(
        ApiMatrixRow("storage", ApiSupport.SUPPORTED, "chrome.storage.local/sync/session aislados por extensión y particionados por origen (cuota 4 MB)."),
        ApiMatrixRow("runtime", ApiSupport.LIMITED, "id, getManifest(), getURL(), onMessage y sendMessage locales; sin service worker persistente."),
        ApiMatrixRow("i18n", ApiSupport.SUPPORTED, "chrome.i18n.getMessage, getUILanguage y getAcceptLanguages usando _locales del paquete."),
        ApiMatrixRow("content_scripts", ApiSupport.LIMITED, "Inyección JS/CSS en document_start/end/idle con MatchPattern; sin mundos V8 aislados en Cefrium 0.21.0 (cierre NXR protege tokens)."),
        ApiMatrixRow("host_permissions", ApiSupport.SUPPORTED, "Validación de MatchPattern, permisos por sitio, revocación inmediata y bloqueo en pestañas privadas por defecto."),
        ApiMatrixRow("action", ApiSupport.LIMITED, "Interfaz popup HTML en panel NXR aislado y evento NXR_ACTION_CLICKED hacia la pestaña activa."),
        ApiMatrixRow("options_ui", ApiSupport.LIMITED, "Página HTML de opciones en panel NXR aislado + ajustes nativos de permisos, sitios y almacenamiento en Nexo."),
        ApiMatrixRow("web_accessible_resources", ApiSupport.LIMITED, "Recursos del paquete validados contra Zip Slip; no se montan en chrome-extension:// dentro del renderer."),
        ApiMatrixRow("tabs", ApiSupport.UNSUPPORTED, "Cefrium 0.21.0 no expone chrome.tabs a extensiones de terceros; el shim registra el límite en diagnóstico."),
        ApiMatrixRow("activeTab", ApiSupport.LIMITED, "Acceso manual a la pestaña activa desde la hoja NXR tras gesto del usuario."),
        ApiMatrixRow("scripting", ApiSupport.UNSUPPORTED, "Inyección dinámica chrome.scripting no disponible para terceros (solo content_scripts declarados)."),
        ApiMatrixRow("alarms", ApiSupport.UNSUPPORTED, "No hay planificador persistente en segundo plano fuera del ciclo de vida del documento."),
        ApiMatrixRow("contextMenus", ApiSupport.UNSUPPORTED, "Menús contextuales personalizados de terceros no disponibles en Cefrium 0.21.0."),
        ApiMatrixRow("notifications", ApiSupport.UNSUPPORTED, "chrome.notifications no está expuesto a extensiones de terceros."),
        ApiMatrixRow("declarativeNetRequest", ApiSupport.LIMITED, "PhantomShield ejecuta reglas estáticas de bloqueo y excepción por dominio. Reglas dinámicas, expresiones regulares y redirecciones siguen fuera de alcance."),
        ApiMatrixRow("webNavigation", ApiSupport.UNSUPPORTED, "Eventos chrome.webNavigation no expuestos a extensiones de terceros."),
        ApiMatrixRow("clipboardWrite", ApiSupport.LIMITED, "Solo mediante la API web estándar del navegador tras gesto directo del usuario."),
        ApiMatrixRow("cookies", ApiSupport.UNSUPPORTED, "chrome.cookies bloqueado para extensiones de terceros por aislamiento y seguridad."),
        ApiMatrixRow("webRequest", ApiSupport.UNSUPPORTED, "Interceptación/bloqueo de red webRequest no disponible para terceros en la API pública de Cefrium."),
        ApiMatrixRow("background.service_worker", ApiSupport.UNSUPPORTED, "Service workers MV3 persistentes, debugger, proxy y nativeMessaging no existen en la API pública de Cefrium.")
    )

    private val MATRIX_BY_API = API_MATRIX.associateBy { it.api }

    /** Permisos realmente soportados por NXR en Cefrium 0.21.0. */
    private val SUPPORTED_PERMS = setOf("storage", "i18n")

    /** Permisos con soporte parcial honesto sobre NXR/Cefrium. */
    private val LIMITED_PERMS = mapOf(
        "declarativeNetRequest" to MATRIX_BY_API.getValue("declarativeNetRequest").note,
        "activeTab" to "Acción manual sobre la pestaña activa desde la ficha NXR tras consentimiento explícito.",
        "clipboardWrite" to "Solo mediante la API web estándar tras un gesto directo del usuario.",
        "clipboardRead" to "Solo tras gesto directo del usuario y permisos web estándar.",
        "unlimitedStorage" to "NXR mantiene una cuota fija de 4 MB por extensión para proteger el almacenamiento.",
        "userScripts" to "Se ejecutan como content scripts sin mundo V8 aislado del motor."
    )

    /** Permisos cuya API no existe para terceros en Cefrium 0.21.0. */
    val UNSUPPORTED_PERMS = setOf(
        "tabs", "scripting", "alarms", "contextMenus", "menus", "notifications",
        "declarativeNetRequestWithHostAccess", "declarativeNetRequestFeedback",
        "webNavigation", "webRequest", "webRequestBlocking", "webRequestFilterResponse", "cookies",
        "downloads", "history", "bookmarks", "offscreen", "sidePanel", "identity", "background",
        "debugger", "devtools", "nativeMessaging", "proxy", "privacy", "management",
        "enterprise.deviceAttributes", "enterprise.platformKeys", "platformKeys", "certificateProvider",
        "vpnProvider", "fileSystemProvider", "tabCapture", "desktopCapture", "pageCapture", "ttsEngine",
        "system.cpu", "system.memory", "system.display", "browsingData", "contentSettings", "fontSettings",
        "geckoProfiler", "dns", "theme", "sessions", "search", "tabGroups", "readingList", "documentScan",
        "printing", "printingMetrics", "loginState", "wallpaper"
    )

    fun isBroadHostPattern(pattern: String): Boolean {
        val clean = pattern.trim().lowercase()
        return clean == "<all_urls>" ||
            clean == "*://*/*" ||
            clean == "http://*/*" ||
            clean == "https://*/*" ||
            clean == "*://*.*" ||
            clean.startsWith("*://*.") && clean.endsWith("/*") && clean.count { it == '.' } <= 1
    }

    fun evaluate(manifest: ExtensionManifest): Report {
        val all = (manifest.permissions + manifest.optionalPermissions).sorted()
        val supported = mutableListOf<String>()
        val limited = linkedMapOf<String, String>()
        val unsupported = linkedSetOf<String>()
        val breakdown = linkedMapOf<String, ApiMatrixRow>()

        for (permission in all) {
            when {
                permission in SUPPORTED_PERMS -> {
                    supported += permission
                    MATRIX_BY_API[permission]?.let { breakdown[permission] = it }
                }
                permission in LIMITED_PERMS -> {
                    limited[permission] = LIMITED_PERMS.getValue(permission)
                    breakdown[permission] = MATRIX_BY_API[permission]
                        ?: ApiMatrixRow(permission, ApiSupport.LIMITED, LIMITED_PERMS.getValue(permission))
                }
                permission in UNSUPPORTED_PERMS -> {
                    unsupported += permission
                    val key = if (permission == "webRequestBlocking" || permission == "webRequestFilterResponse") "webRequest"
                    else if (permission == "menus") "contextMenus"
                    else if (permission.startsWith("declarativeNetRequest")) "declarativeNetRequest"
                    else permission
                    breakdown[permission] = MATRIX_BY_API[key]?.copy(api = permission)
                        ?: ApiMatrixRow(permission, ApiSupport.UNSUPPORTED, "Sin equivalente en la API pública de Cefrium 0.21.0.")
                }
                else -> {
                    limited[permission] = "Permiso no reconocido por NXR; se ignora de forma segura."
                    breakdown[permission] = ApiMatrixRow(permission, ApiSupport.LIMITED, "Permiso no reconocido; ignorado.")
                }
            }
        }

        if (manifest.hostPermissions.isNotEmpty() || manifest.contentScripts.any { it.matches.isNotEmpty() }) {
            breakdown.putIfAbsent("host_permissions", MATRIX_BY_API.getValue("host_permissions"))
        }
        if (manifest.contentScripts.isNotEmpty()) {
            if (manifest.contentScripts.any { it.world == ExtensionManifest.World.ISOLATED }) {
                limited.putIfAbsent(
                    "content_scripts",
                    "Cefrium 0.21.0 no expone mundos V8 aislados: NXR aísla tokens en cierre privado, pero comparte DOM/globales con la página."
                )
            }
            breakdown.putIfAbsent("content_scripts", MATRIX_BY_API.getValue("content_scripts"))
        }
        if (manifest.action != null) {
            limited.putIfAbsent("action", MATRIX_BY_API.getValue("action").note)
            breakdown.putIfAbsent("action", MATRIX_BY_API.getValue("action"))
        }
        if (manifest.optionsPage != null) {
            limited.putIfAbsent("options_ui", MATRIX_BY_API.getValue("options_ui").note)
            breakdown.putIfAbsent("options_ui", MATRIX_BY_API.getValue("options_ui"))
        }
        if (manifest.webAccessibleResources.isNotEmpty()) {
            limited.putIfAbsent("web_accessible_resources", MATRIX_BY_API.getValue("web_accessible_resources").note)
            breakdown.putIfAbsent("web_accessible_resources", MATRIX_BY_API.getValue("web_accessible_resources"))
        }
        if (manifest.background?.serviceWorker != null) {
            unsupported += "background.service_worker"
            breakdown["background.service_worker"] = MATRIX_BY_API.getValue("background.service_worker")
        } else if (manifest.background != null) {
            unsupported += "background"
            breakdown["background"] = ApiMatrixRow(
                "background",
                ApiSupport.UNSUPPORTED,
                "Scripts de fondo persistentes no se ejecutan en proceso separado sobre Cefrium 0.21.0."
            )
        }
        if (manifest.dnrRulesets.isNotEmpty()) {
            limited["declarativeNetRequest"] = MATRIX_BY_API.getValue("declarativeNetRequest").note
            breakdown["declarativeNetRequest"] = MATRIX_BY_API.getValue("declarativeNetRequest")
        }

        val runnableScripts = manifest.contentScripts.count { it.js.isNotEmpty() || it.css.isNotEmpty() }
        val unsupportedList = unsupported.toList().sorted()
        val hasStaticDnr = manifest.manifestVersion == 3 && "declarativeNetRequest" in manifest.permissions &&
            manifest.dnrRulesets.any { it.enabled }
        val level = when {
            runnableScripts == 0 && !hasStaticDnr -> Level.UNSUPPORTED
            unsupportedList.isNotEmpty() || limited.isNotEmpty() -> Level.PARTIAL
            else -> Level.FULL
        }
        val verdict = when {
            level == Level.UNSUPPORTED -> InstallVerdict.UNSUPPORTED
            unsupportedList.isNotEmpty() -> InstallVerdict.LIMITED_INSTALLABLE
            limited.isNotEmpty() -> InstallVerdict.PARTIAL
            else -> InstallVerdict.FULL
        }
        return Report(
            level = level,
            supported = supported,
            limited = limited,
            unsupported = unsupportedList,
            verdict = verdict,
            apiBreakdown = breakdown.values.toList()
        )
    }
}
