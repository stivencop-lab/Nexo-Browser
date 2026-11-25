# Nexo Extension Hub

> Guía para Nexo Browser, NXR y Cefrium **0.22.0** (Chromium 158).

## Instalar y revisar un complemento

1. Abre **Extensiones** y busca un complemento en **Chrome Web Store** o **Edge Add-ons**. Puedes pegar el enlace de la ficha en el Hub.
2. En la ficha de la tienda, pulsa **Instalar en Nexo**. Revisa el manifiesto, los permisos solicitados y el veredicto que muestra el Hub.
3. Después de instalarlo, usa **Gestionar** para habilitarlo o revisar sus permisos por sitio. Si el paquete aporta una interfaz, abre **Abrir interfaz de…** desde su ficha.

**Ejemplo con uBlock Origin Lite.** Busca el nombre completo del complemento y revisa su compatibilidad. La prueba JVM con el CRX3 oficial admitió 11.624 reglas estáticas. Las funciones de la extensión que dependen del Service Worker MV3 quedan pendientes. [Resultado de la auditoría](AUDITORIA-0.39.2.md#paquetes-oficiales-verificados-en-jvm)

El Hub también acepta archivos `.crx`, `.zip` y `.xpi` del teléfono y enlaces HTTPS directos a un paquete.

## 1. Arquitectura general

El Hub separa la resolución de enlaces, las descargas, la inspección de paquetes y la ejecución.

| Componente | Archivo | Responsabilidad |
| --- | --- | --- |
| **Resolutor de tiendas y URLs** | `extensions/hub/ExtensionStoreResolver.kt` | Detecta fichas oficiales de Chrome Web Store (`chromewebstore.google.com/detail/...`, `chrome.google.com/webstore/detail/...`), Microsoft Edge Add-ons (`microsoftedge.microsoft.com/addons/detail/...`), IDs de 32 caracteres `[a-p]`, búsquedas y URLs HTTPS directas (`.crx`/`.zip`/`.xpi`). |
| **Cliente de red aislado** | `extensions/hub/ExtensionStoreClient.kt` | Descarga metadatos y paquetes sin cookies ni sesiones del navegador, con validación manual de cada redirección (máx. 5 saltos), listas blancas de hosts, límite de descarga de `256 MiB` y verificación SHA-256 + CRX + versión. |
| **Verificador CRX2/CRX3** | `extensions/CrxPackage.kt` y `extensions/Crx3Parser.kt` | Lee la cabecera, verifica las firmas RSA/ECDSA de CRX3 en una pasada sobre el ZIP, comprueba el identificador de la extensión y calcula `publicKeySha256` para las actualizaciones. Conserva la verificación CRX2. |
| **Matriz de compatibilidad** | `extensions/ExtensionCompatibility.kt` | Evalúa el manifiesto contra la matriz auditable de **20 APIs WebExtension** sobre Cefrium `0.22.0`, emite `InstallVerdict` y define los 4 estados reales del ciclo de vida. |
| **Instalador transaccional** | `extensions/nxr/NxrPackageInstaller.kt` | Inspecciona permisos/riesgos, defiende contra *Zip Slip*, duplicados y bombas ZIP, exige continuidad de clave (`publicKeySha256`) en actualizaciones, instala atómicamente con *rollback* y aplica **estado seguro por defecto (`enabled = false`)** para paquetes riesgosos/parciales/no verificados. |
| **Gestor de actualizaciones** | `extensions/hub/ExtensionUpdateManager.kt` | Consulta nuevas versiones en CWS/Edge, calcula `PermissionDiff` (permisos y hosts añadidos/eliminados) y exige consentimiento explícito del usuario antes de aplicar cualquier ampliación de privilegios. |
| **Gestión de extensiones** | `extensions/hub/ExtensionHubSheets.kt` y `ExtensionsActivity.kt` | Ficha previa a la instalación, progreso real, aviso honesto de compatibilidad, control por sitio, diagnóstico y actualización individual o masiva. |
| **Barra contextual en navegación** | `MainActivity.kt` (`updateExtensionInstallBanner`) | Ofrece «Instalar en Nexo» o «Gestionar en Nexo» al detectar una ficha oficial. El modo escritorio automático se configura en Cefrium (con `CefriumUserAgentMetadata`) para la navegación a la tienda. |
| **Panel HTML** | `extensions/nxr/NxrExtensionPanel.kt` y `assets/nexo/nxr-popup-viewport.js` | Abre popups y opciones con un origen HTTPS sintético por extensión, token de sesión y viewport adaptable. |
| **Reglas de red** | `extensions/nxr/DnrBridge.kt` y `shield/PhantomShield.kt` | Evalúa reglas estáticas admitidas de bloqueo y excepción para subrecursos, con prioridad por extensión. |

## 2. Flujo de fuentes compatibles

### A. Chrome Web Store
1. Búsqueda directa en `https://chromewebstore.google.com/search/<consulta>` o pegado de enlace/ID en el Hub.
2. Consulta al endpoint oficial Omaha XML (`https://clients2.google.com/service/update2/crx?prodversion=...&acceptformat=crx2,crx3&x=id%3D<id>%26uc`) **sin cookies**.
3. Validación estricta del XML (`status="ok"`, `codebase` en lista blanca de hosts Google `clients2.googleusercontent.com` / `*.gvt1.com`, `hash_sha256` cuando está disponible y `size <= 256 MiB`).
4. Descarga controlada con redirecciones verificadas, comprobación SHA-256 cuando se dispone de la suma, verificación criptográfica `CrxPackage.verify(file, expectedId)` e inspección previa del manifiesto. Si Omaha no ofrece metadatos útiles, el cliente puede usar la respuesta directa `response=redirect` y verificar el CRX.

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

La ficha muestra el estado del complemento.

| Estado | Significado real |
| --- | --- |
| **Descargada** | Paquete descargado temporalmente y verificado en disco, pendiente de confirmación del usuario. |
| **Instalada** | Extraída e indexada atómicamente en `nxr_extensions/`, con la ejecución deshabilitada hasta la activación. |
| **Habilitada** | Activada por el usuario para los sitios y perfiles permitidos. |
| **Funcionando** | NXR registró inyección de contenido o PhantomShield aplicó un bloqueo válido de sus reglas DNR estáticas. |

---

## 4. Garantías de seguridad

- **Modo escritorio de tienda:** se activa automáticamente al visitar por HTTPS `chromewebstore.google.com` o las rutas de Add-ons de `microsoftedge.microsoft.com`. El override de Cefrium (UA + `CefriumUserAgentMetadata`) se aplica al navegador y llega a los subrecursos mientras se visita la tienda. Nexo restablece la selección del usuario al salir.
- **Integridad CRX3 endurecida:** verificación de firma + comprobación de cabecera contra EOCD ZIP + derivación determinista del ID desde la clave pública.
- **Continuidad de clave pública (`publicKeySha256`):** al actualizar una extensión existente, si cambia la clave pública firmante (o se intenta sustituir un paquete firmado por uno sin firma), `NxrPackageInstaller` rechaza la operación.
- **Consentimiento ante escalada de permisos:** `ExtensionUpdateManager.computePermissionDiff()` bloquea actualizaciones silenciosas que soliciten nuevos permisos o nuevos patrones de host (`UpdateConsentRequiredException`) hasta confirmación explícita.
- **Modo seguro por defecto (`enabled = false`):** toda extensión sin firma verificable, con compatibilidad parcial/no soportada, permisos peligrosos o acceso global (`<all_urls>`, `*://*/*`) se instala desactivada hasta que el usuario revise y habilite.
- **Aislamiento en ejecución:** `cefriumQuery` congelado en `document_start`, `storage.local` particionado por extensión y origen (cuota de 4 MiB por extensión y tope de 512 KiB por valor), grants efímeros de 256 bits ligados a `(sesión, extensión, origen, docGen)`, *rate limit* (`30 llamadas / 10 s`) y tope `32 KB` por mensaje.

---

## 5. Matriz honesta de compatibilidad (20 APIs WebExtension sobre Cefrium 0.22.0)

| # | API / Capacidad | Estado en NXR | Alcance real y limitación en Cefrium 0.22.0 |
| --- | --- | --- | --- |
| 1 | `storage` | **Soportada** | `chrome.storage.local` aislado por extensión y origen (4 MiB por extensión). La sincronización entre contextos sigue pendiente. |
| 2 | `i18n` | **Soportada** | `chrome.i18n.getMessage` y `getUILanguage` usando `_locales` del paquete. |
| 3 | `host_permissions` | **Soportada** | Validación `MatchPattern`, revocación por sitio y bloqueo en pestañas privadas por defecto. |
| 4 | `runtime` | **Limitada** | `id`, `getManifest()`, `getURL()`, `onMessage`/`sendMessage` locales; sin *service worker* persistente. |
| 5 | `content_scripts` | **Limitada** | Inyección JS/CSS en `document_start`/`end`/`idle`; Cefrium 0.22.0 **no expone mundos V8 aislados** (el cierre NXR aísla tokens, pero comparte DOM/globales con la página). |
| 6 | `action` | **Limitada** | Acción desde la ficha y popup HTML en un panel Cefrium adaptado al ancho, con puente NXR limitado. |
| 7 | `options_ui` | **Limitada** | Ajustes de permisos y página HTML de opciones en un panel Cefrium con origen por extensión. |
| 8 | `web_accessible_resources` | **Limitada** | Recursos validados contra *Zip Slip*; no se montan como `chrome-extension://` en el renderer. |
| 9 | `activeTab` | **Limitada** | Acceso manual sobre la pestaña activa tras gesto explícito desde la hoja NXR. |
| 10 | `clipboardWrite` | **Limitada** | Solo vía API web estándar tras gesto directo del usuario. |
| 11 | `tabs` | **No soportada** | Cefrium 0.22.0 no expone `chrome.tabs` a terceros; el shim registra el límite en diagnóstico. |
| 12 | `scripting` | **No soportada** | Sin inyección dinámica `chrome.scripting` (solo `content_scripts` declarados). |
| 13 | `alarms` | **No soportada** | Sin planificador persistente en segundo plano fuera del documento. |
| 14 | `contextMenus` | **No soportada** | Menús contextuales de terceros no expuestos en Cefrium 0.22.0. |
| 15 | `notifications` | **No soportada** | `chrome.notifications` no expuesto a extensiones de terceros. |
| 16 | `declarativeNetRequest` | **Limitada** | PhantomShield admite reglas estáticas de bloqueo y excepción para subrecursos. Las reglas dinámicas, las redirecciones y las modificaciones de cabeceras quedan pendientes (requieren ampliación en `CefriumResourceInterceptor`). |
| 17 | `webNavigation` | **No soportada** | Eventos `chrome.webNavigation` no expuestos a terceros. |
| 18 | `cookies` | **No soportada** | `chrome.cookies` bloqueado para terceros por aislamiento. |
| 19 | `webRequest` | **No soportada** | Interceptación de red `webRequest` no disponible para terceros en API pública de Cefrium. |
| 20 | `background.service_worker` | **No soportada** | *Service workers* MV3 persistentes, `debugger`, `proxy` y `nativeMessaging` no existen en la API pública de Cefrium 0.22.0. |

### Veredictos mostrados al usuario (`ExtensionCompatibility.InstallVerdict`)
- **Compatible (`FULL`):** `content_scripts` ejecutables y permisos totalmente cubiertos.
- **Compatible parcialmente (`PARTIAL`):** ejecutable con permisos limitados claramente listados.
- **Instalable con límites (`LIMITED_INSTALLABLE`):** ofrece scripts de contenido o declara reglas estáticas elegibles y también solicita APIs pendientes.
- **No compatible en Cefrium (`UNSUPPORTED`):** carece de scripts de contenido ejecutables y de un ruleset estático elegible, por ejemplo si depende exclusivamente del Service Worker MV3.
- **Bloqueada por seguridad (`BLOCKED_SECURITY`):** estructura inválida, *Zip Slip*, bomba ZIP, firma corrupta o ruptura de continuidad de clave.
- **Paquete sin firma verificable (`UNVERIFIABLE`):** `.zip`/`.xpi` sin firma criptográfica verificada; requiere confirmación explícita y se instala desactivada.

## Ejemplo de ruleset estático

Declara el ruleset en `manifest.json` con un manifiesto MV3.

```json
{
  "manifest_version": 3,
  "name": "Ejemplo DNR para Nexo",
  "version": "1.0",
  "permissions": ["declarativeNetRequest"],
  "declarative_net_request": {
    "rule_resources": [
      { "id": "local", "enabled": true, "path": "rules.json" }
    ]
  }
}
```

Guarda las dos reglas en `rules.json`. La primera bloquea los scripts de un dominio de prueba. La segunda permite una ruta con prioridad mayor.

```json
[
  {
    "id": 1,
    "priority": 1,
    "action": { "type": "block" },
    "condition": {
      "requestDomains": ["ads.example"],
      "resourceTypes": ["script"]
    }
  },
  {
    "id": 2,
    "priority": 2,
    "action": { "type": "allow" },
    "condition": {
      "urlFilter": "||ads.example/permitido.js|",
      "resourceTypes": ["script"]
    }
  }
]
```

Crea el ZIP con los dos archivos en la raíz.

```bash
zip ejemplo-dnr.zip manifest.json rules.json
```

Importa el paquete desde Extensiones y revisa el aviso de firma antes de habilitarlo. La ficha muestra el número de reglas admitidas. Una excepción que el compilador no puede aplicar deja el conjunto de reglas de la extensión sin cargar para evitar bloqueos incorrectos. [Implementación y pruebas DNR](../app/src/test/java/com/nexo/browser/extensions/nxr/DnrBridgeTest.kt)
