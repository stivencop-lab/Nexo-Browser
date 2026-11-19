# Nexo Extension Hub — Arquitectura, Seguridad y Compatibilidad Real

> **Alcance:** Instalación, verificación, actualización y gestión de extensiones Chromium desde **Chrome Web Store**, **Microsoft Edge Add-ons**, **archivos locales** (`.crx`, `.zip`, `.xpi`) y **paquetes HTTPS directos verificables** sobre **NXR (Nexo Extensions Runtime)** y **Cefrium `0.21.0` (Chromium 156)**.

---

## 1. Arquitectura general

Nexo Extension Hub separa estrictamente la resolución de orígenes, la descarga de red aislada, la inspección criptográfica/estática y la ejecución en tiempo de ejecución:

| Componente | Archivo | Responsabilidad |
| --- | --- | --- |
| **Resolutor de tiendas y URLs** | `extensions/hub/ExtensionStoreResolver.kt` | Detecta fichas oficiales de Chrome Web Store (`chromewebstore.google.com/detail/...`, `chrome.google.com/webstore/detail/...`), Microsoft Edge Add-ons (`microsoftedge.microsoft.com/addons/detail/...`), IDs de 32 caracteres `[a-p]`, búsquedas y URLs HTTPS directas (`.crx`/`.zip`/`.xpi`). |
| **Cliente de red aislado** | `extensions/hub/ExtensionStoreClient.kt` | Descarga metadatos y paquetes sin cookies ni sesiones del navegador, con validación manual de cada redirección (máx. 5 saltos), listas blancas de hosts, límite estricto de `25 MB` y verificación SHA-256 + CRX + versión. |
| **Verificador CRX2/CRX3** | `extensions/CrxPackage.kt` | Valida firmas RSA/ECDSA de paquetes CRX2/CRX3, deriva el ID oficial de 32 letras a partir de la clave pública, comprueba integridad de la cabecera contra ZIP EOCD y calcula `publicKeySha256` para continuidad de clave. |
| **Matriz de compatibilidad** | `extensions/ExtensionCompatibility.kt` | Evalúa el manifiesto contra la matriz auditable de **20 APIs WebExtension** sobre Cefrium `0.21.0`, emite `InstallVerdict` y define los 4 estados reales del ciclo de vida. |
| **Instalador transaccional** | `extensions/nxr/NxrPackageInstaller.kt` | Inspecciona permisos/riesgos, defiende contra *Zip Slip*, duplicados y bombas ZIP, exige continuidad de clave (`publicKeySha256`) en actualizaciones, instala atómicamente con *rollback* y aplica **estado seguro por defecto (`enabled = false`)** para paquetes riesgosos/parciales/no verificados. |
| **Gestor de actualizaciones** | `extensions/hub/ExtensionUpdateManager.kt` | Consulta nuevas versiones en CWS/Edge, calcula `PermissionDiff` (permisos y hosts añadidos/eliminados) y exige consentimiento explícito del usuario antes de aplicar cualquier ampliación de privilegios. |
| **Flujos nativos Flow 3.0** | `extensions/hub/ExtensionHubSheets.kt` y `ExtensionsActivity.kt` | Ficha previa a la instalación, progreso real, aviso honesto de compatibilidad, control por sitio, diagnóstico y actualización individual o masiva. |
| **Barra contextual en navegación** | `MainActivity.kt` (`updateExtensionInstallBanner`) | Observador puro de URL en Kotlin: cuando el usuario navega a una ficha oficial de CWS o Edge Add-ons, ofrece «Instalar en Nexo» o «Gestionar en Nexo» **sin inyectar scripts en la tienda ni falsificar el User-Agent**. |

---

## 2. Flujo de fuentes compatibles

### A. Chrome Web Store
1. Búsqueda directa en `https://chromewebstore.google.com/search/<consulta>` o pegado de enlace/ID en el Hub.
2. Consulta al endpoint oficial Omaha XML (`https://clients2.google.com/service/update2/crx?prodversion=...&acceptformat=crx2,crx3&x=id%3D<id>%26uc`) **sin cookies**.
3. Validación estricta del XML (`status="ok"`, `codebase` en lista blanca de hosts Google `clients2.googleusercontent.com` / `*.gvt1.com`, `hash_sha256` y `size <= 25 MB`).
4. Descarga controlada con redirecciones verificadas, comprobación SHA-256, verificación criptográfica `CrxPackage.verify(file, expectedId)` e inspección previa de manifiesto y compatibilidad antes de instalar.

### B. Microsoft Edge Add-ons
1. Búsqueda directa en `https://microsoftedge.microsoft.com/addons/search/<consulta>` o pegado de enlace/ID.
2. Metadatos reales vía JSON oficial `https://microsoftedge.microsoft.com/addons/getproductdetailsbycrxid/<id>` (nombre, desarrollador, versión, descripción, política de privacidad y métricas reales **solo cuando la API las devuelve**; nunca se fabrican reseñas ni valoraciones).
3. Descarga del CRX firmado desde `https://edge.microsoft.com/extensionwebstorebase/v1/crx?...`.
   - **Nota técnica sobre CDN Microsoft:** algunos nodos `*.delivery.mp.microsoft.com` responden `503` bajo TLS al redirigir; Nexo permite salto HTTP **exclusivamente** hacia `*.delivery.mp.microsoft.com` exigiendo **siempre** verificación criptográfica completa `CrxPackage.verify(file, expectedId)` y coincidencia exacta de la versión del manifiesto firmado con los metadatos HTTPS previos. Cualquier discrepancia aborta la instalación.

### C. Archivos locales y paquetes HTTPS directos
- **Archivos locales:** `.crx`, `.zip`, `.xpi` mediante selector SAF del sistema (sin almacenamiento externo amplio).
- **URLs HTTPS directas de desarrolladores:** únicamente `https://` públicos (bloqueo de IP privadas/localhost/credenciales), verificación de firma cuando es `.crx`, e indicación explícita de «Paquete sin firma verificable» en `.zip`/`.xpi` sin firma criptográfica, quedando desactivada por defecto tras instalar hasta revisión manual.

---

## 3. Ciclo de vida real de 4 estados

Nexo Extension Hub nunca mezcla «descargado» con «funcionando»:

| Estado | Significado real |
| --- | --- |
| **Descargada** | Paquete descargado temporalmente y verificado en disco, pendiente de confirmación del usuario. |
| **Instalada** | Extraída e indexada atómicamente en `nxr/`, pero **deshabilitada** (incluido el modo seguro por defecto cuando requiere revisión). |
| **Habilitada** | Activada por el usuario, lista para inyectarse cuando coincida `matches` de una pestaña permitida (no privada salvo permiso explícito). |
| **Funcionando** | Inyectada activamente en al menos una sesión viva de Cefrium con métricas reales de ejecución visibles en el diagnóstico. |

---

## 4. Garantías de seguridad

- **Cero inyección en tiendas y cero *UA spoofing*:** Nexo nunca modifica el DOM de Chrome Web Store ni Edge Add-ons ni finge ser Chrome Desktop.
- **Integridad CRX3 endurecida:** verificación de firma + comprobación de cabecera contra EOCD ZIP + derivación determinista del ID desde la clave pública.
- **Continuidad de clave pública (`publicKeySha256`):** al actualizar una extensión existente, si cambia la clave pública firmante (o se intenta sustituir un paquete firmado por uno sin firma), `NxrPackageInstaller` rechaza la operación.
- **Consentimiento ante escalada de permisos:** `ExtensionUpdateManager.computePermissionDiff()` bloquea actualizaciones silenciosas que soliciten nuevos permisos o nuevos patrones de host (`UpdateConsentRequiredException`) hasta confirmación explícita.
- **Modo seguro por defecto (`enabled = false`):** toda extensión sin firma verificable, con compatibilidad parcial/no soportada, permisos peligrosos o acceso global (`<all_urls>`, `*://*/*`) se instala desactivada hasta que el usuario revise y habilite.
- **Aislamiento en ejecución:** `cefriumQuery` congelado en `document_start`, `storage.local` particionado por extensión y origen (cuota `512 KB`), grants efímeros de 256 bits ligados a `(sesión, extensión, origen, docGen)`, *rate limit* (`30 llamadas / 10 s`) y tope `32 KB` por mensaje.

---

## 5. Matriz honesta de compatibilidad (20 APIs WebExtension sobre Cefrium 0.21.0)

| # | API / Capacidad | Estado en NXR | Alcance real y limitación en Cefrium 0.21.0 |
| --- | --- | --- | --- |
| 1 | `storage` | **Soportada** | `chrome.storage.local` aislado por extensión y particionado por origen (512 KB). |
| 2 | `i18n` | **Soportada** | `chrome.i18n.getMessage` y `getUILanguage` usando `_locales` del paquete. |
| 3 | `host_permissions` | **Soportada** | Validación `MatchPattern`, revocación por sitio y bloqueo en pestañas privadas por defecto. |
| 4 | `runtime` | **Limitada** | `id`, `getManifest()`, `getURL()`, `onMessage`/`sendMessage` locales; sin *service worker* persistente. |
| 5 | `content_scripts` | **Limitada** | Inyección JS/CSS en `document_start`/`end`/`idle`; Cefrium 0.21.0 **no expone mundos V8 aislados** (el cierre NXR aísla tokens, pero comparte DOM/globales con la página). |
| 6 | `action` | **Limitada** | Acción disparada desde la ficha NXR (`NXR_ACTION_CLICKED`); sin popups HTML privilegiados nativos. |
| 7 | `options_ui` | **Limitada** | Ajustes nativos de permisos, sitios y almacenamiento en Nexo; las páginas HTML de opciones no tienen APIs privilegiadas. |
| 8 | `web_accessible_resources` | **Limitada** | Recursos validados contra *Zip Slip*; no se montan como `chrome-extension://` en el renderer. |
| 9 | `activeTab` | **Limitada** | Acceso manual sobre la pestaña activa tras gesto explícito desde la hoja NXR. |
| 10 | `clipboardWrite` | **Limitada** | Solo vía API web estándar tras gesto directo del usuario. |
| 11 | `tabs` | **No soportada** | Cefrium 0.21.0 no expone `chrome.tabs` a terceros; el shim registra el límite en diagnóstico. |
| 12 | `scripting` | **No soportada** | Sin inyección dinámica `chrome.scripting` (solo `content_scripts` declarados). |
| 13 | `alarms` | **No soportada** | Sin planificador persistente en segundo plano fuera del documento. |
| 14 | `contextMenus` | **No soportada** | Menús contextuales de terceros no expuestos en Cefrium 0.21.0. |
| 15 | `notifications` | **No soportada** | `chrome.notifications` no expuesto a extensiones de terceros. |
| 16 | `declarativeNetRequest` | **No soportada** | Rulesets DNR de terceros no se inyectan en el interceptor de red (PhantomShield opera integrado). |
| 17 | `webNavigation` | **No soportada** | Eventos `chrome.webNavigation` no expuestos a terceros. |
| 18 | `cookies` | **No soportada** | `chrome.cookies` bloqueado para terceros por aislamiento. |
| 19 | `webRequest` | **No soportada** | Interceptación de red `webRequest` no disponible para terceros en API pública de Cefrium. |
| 20 | `background.service_worker` | **No soportada** | *Service workers* MV3 persistentes, `debugger`, `proxy` y `nativeMessaging` no existen en la API pública de Cefrium 0.21.0. |

### Veredictos mostrados al usuario (`ExtensionCompatibility.InstallVerdict`)
- **Compatible (`FULL`):** `content_scripts` ejecutables y permisos totalmente cubiertos.
- **Compatible parcialmente (`PARTIAL`):** ejecutable con permisos limitados claramente listados.
- **Instalable con límites (`LIMITED_INSTALLABLE`):** tiene `content_scripts` ejecutables, pero algunas APIs declaradas no existen en Cefrium y no funcionarán.
- **No compatible en Cefrium (`UNSUPPORTED`):** carece de `content_scripts` ejecutables (p. ej. depende exclusivamente de `service_worker` MV3 o `declarativeNetRequest`).
- **Bloqueada por seguridad (`BLOCKED_SECURITY`):** estructura inválida, *Zip Slip*, bomba ZIP, firma corrupta o ruptura de continuidad de clave.
- **Paquete sin firma verificable (`UNVERIFIABLE`):** `.zip`/`.xpi` sin firma criptográfica verificada; requiere confirmación explícita y se instala desactivada.
