# Auditoría y plan: migración de Nexo a Chromium WebView

> Estado: **decisiones tomadas; fase 1 implementada** (ver [§7](#7-decisiones) y [§10](#10-estado-de-la-fase-1)).
> Base revisada: `main` en v0.30.2 (`versionCode 61`), commit `9bcb1ca`.

## 1. Resumen

- La migración es **viable y conveniente** para tamaño (el APK arm64 pasa de ~100 MB a
  un APK universal estimado de menos de 10 MB, sin `libxul.so` ni división por ABI), arranque en
  frío y compatibilidad web, pero **no es 1:1**. Hay cuatro funciones que Android WebView no
  ofrece y que requieren decisión de producto antes de empezar:
  1. tienda AMO e instalación de XPI de terceros → **runtime de extensiones propio** ([§9](#9-runtime-de-extensiones-de-nexo-nxr));
  2. proxy SOCKS5/HTTP **por pestaña** (WebView solo admite proxy para toda la app) → **proxy global**;
  3. traducción local de Gecko (Firefox Translations) → **traducción en la nube** con los proveedores ya configurados;
  4. DNS sobre HTTPS configurable, ETP de Gecko, protección de huellas y aislamiento total de
     cookies (FPI/TCP).
- La *skill* entregada (`Chromium_WebView_Migration_Master_Skill.md`, v4.0.0) sirve como
  **referencia de primitivas de WebView** (`shouldInterceptRequest`,
  `addDocumentStartJavaScript`, `addWebMessageListener`, `ProfileStore`, `onRenderProcessGone`),
  pero **no está hecha para este repositorio**: describe pantallas, IDs, recursos y una extensión
  (`smart-shield@browser.local`) que no existen aquí, y aplicada tal cual produciría
  regresiones graves de bloqueo, privacidad y seguridad (detalle en [§3](#3-revisión-de-la-skill)).
- Recomendación: migrar **por fases detrás de una capa de motor** (`BrowserEngine`), manteniendo
  la interfaz nativa actual (Activity + vistas propias), portando a Kotlin solo la ruta crítica
  de PhantomShield (motor de red) y **reutilizando su JavaScript** (scripts de página, detector IA,
  panel y opciones) con un *shim* mínimo de `browser.*`. Así siguen valiendo las 206 pruebas JS.

## 2. Inventario del estado actual

| Área | Dato |
| --- | --- |
| Kotlin | 88 archivos, 21 449 líneas; 21 importan `org.mozilla.geckoview` (más `ScreenshotTest`) |
| Núcleo acoplado a Gecko | `ExtensionManager` (2 037 l., 130 líneas con tipos Gecko), `TabManager` (1 488 l., 74), `LiveCaptions` (43), `PagePrompts` (23), `TabRouting` (22), `GeckoRuntimeHolder` (16), `MainActivity` (15), `SitePermissions` (12) |
| UI | `android.app.Activity` + vistas propias (`NexoUi`, `NexoSheets`, `GestureDock`, `PullRefreshLayout`); **sin** AppCompat, Material, Fragments ni CoordinatorLayout |
| Extensiones integradas | `phantomshield` 9.2.1 (916 KB), `nexo_bridge` 1.14.0 (436 KB, incluye Dark Reader), `nexo_network` 1.0.0 (36 KB) |
| Pruebas | 206 pruebas JS (`node --test`, **todas pasan**), 24 archivos de pruebas JVM, 3 suites Robolectric |
| Dependencias | `geckoview-omni` 159, `androidx.core` 1.19.1, RecyclerView, OkHttp 4.12, color HCT |
| Build | AGP 9.5.1, Kotlin 2.4.10, compileSdk 37.2 (impuesto por GeckoView), min 26, target 35 |

### 2.1 Superficie WebExtension usada por las extensiones integradas

- **PhantomShield**: `webRequest.onBeforeRequest` (bloqueante, con `redirectUrl`),
  `webRequest.filterResponseData` (parcheador anti-bloqueo), `tabs.insertCSS` con
  `cssOrigin: 'user'`, `tabs.query/sendMessage/reload/onUpdated/onRemoved`,
  `storage.local` (24 escrituras, 19 lecturas, `onChanged`), `alarms`, `browserAction.setBadgeText`,
  `permissions`, `runtime.onMessage/sendMessage/getURL/openOptionsPage`, contenido en mundo
  `MAIN` (`page-hooks.js`) y aislado.
- **nexo_bridge**: `runtime.connectNative`, `tabs.executeScript` (Dark Reader bajo demanda),
  `wrappedJSObject` (API del reproductor de YouTube), `mozCaptureStream`/`captureStream` +
  `MediaRecorder` para subtítulos.
- **nexo_network**: `proxy.onRequest` por pestaña, `privacy.network.peerConnectionEnabled` y
  `networkPredictionEnabled`, `webRequest` (retardo simulado), `storage.session`,
  `exportFunction`/`cloneInto` (geolocalización y variación Canvas/WebGL).

## 3. Revisión de la skill

### 3.1 Lo que sí se aprovecha

- Mapeo correcto de primitivas: `shouldInterceptRequest` ↔ `webRequest`,
  `addDocumentStartJavaScript` ↔ `content_scripts` en `document_start`,
  `addWebMessageListener` ↔ `connectNative`, `WebViewClient.onRenderProcessGone` ↔
  `onCrash/onKill`, `WebView.findAllAsync` ↔ `SessionFinder`, `onShowCustomView` ↔ pantalla completa.
- Respuestas sintéticas por tipo (JS vacío, GIF 1×1, HTML vacío, 204) para que los detectores
  anti-bloqueo no vean `onerror`.
- Endurecimiento de `WebSettings` (`allowFileAccess=false`, `allowContentAccess=false`,
  Safe Browsing, sin acceso universal desde `file://`) y `setAttributionRegistrationBehavior`.
- Uso de `MutableContextWrapper` para no retener la Activity, prioridad de renderer por
  visibilidad y `saveState`/`restoreState` al descartar pestañas.
- Captura de audio con `AudioPlaybackCaptureConfiguration` + `addMatchingUid(myUid())`: en WebView
  el audio sale por el proceso de la app, a diferencia de Gecko (ver `PlaybackAudioCapture.kt:166`).

### 3.2 Desajustes con este repositorio

| La skill asume | En el repositorio |
| --- | --- |
| `fragment_browser.xml`, `BrowserActivity`, `CoordinatorLayout` + `AppBarLayout` | `activity_main.xml`, `MainActivity`, barra superior fija + `GestureDock` inferior |
| IDs `appBarLayout`, `findInPageStub`, `popupBlockedStub`, `liveCaptionsStub`, `downloadBannerStub`, `btnShield` | IDs `geckoView`, `browserFrame`, `findBar`, `captionView`, `browserDock`, `omnibox`… |
| `@dimen/toolbar_height`, `@drawable/bg_toolbar_icon_ripple`, `@string/cd_*`, `sheet_shield_panel.xml` | No existen; compilación falla |
| AppCompat, Material, ConstraintLayout, CoordinatorLayout, Room + KSP | Ninguna de esas dependencias; tema y hojas propios |
| Extensión `smart-shield@browser.local` con `background/shield.js`, `content/scriptlets.js`, `content/detector.js` | PhantomShield 9.2.1: 16 scripts de fondo, 5 de contenido, popup y opciones |
| `GeckoSessionPoolManager` | `TabManager` (objeto) + `BrowserTab` |

### 3.3 Regresiones y fallos si se aplica tal cual

**Bloqueo y privacidad**

1. **Motor de bloqueo**: sustituye el motor ABP/uBO de PhantomShield (EasyList, EasyPrivacy,
   uBlock filters/privacy/unbreak, EasyList Spanish; hasta 220 000 reglas; `$important`,
   `$badfilter`, `$popup`, `domain=`, `third-party`, tipos de recurso) por **19 dominios fijos**.
2. **Lista blanca funcional antes que las reglas**: deja pasar cualquier iframe cuya ruta
   contenga `/e/`, `/d/`, `/v/`… y cualquier URL `.ts`, `.mp4`… antes de evaluar reglas. En el
   repositorio esos vetos (`functional-safety.js`) **solo** se aplican a IA y dominios aprendidos,
   nunca a reglas explícitas.
3. **Host de página por `Referer`**: los subrecursos de un iframe toman el host del iframe, así que
   la pausa por sitio deja de aplicarse dentro de marcos. Debe usarse el host principal de la pestaña.
4. **IA que ejecuta su propia respuesta**: aplica `recommendedCssSelector` y
   `recommendedBlockHost` devueltos por el modelo. PhantomShield prohíbe expresamente ejecutar
   selectores, URLs o código del modelo (una página puede inducir con su texto a que el modelo
   devuelva `div.content`). El saneador de la skill no lo impide.
5. **Sin consentimiento, presupuesto ni sitios autorizados para IA**; un solo proveedor
   (formato OpenAI); se pierde la cadena de respaldo, la memoria IA y los dominios aprendidos con sal.
6. **Puente expuesto a todos los orígenes y marcos** (`setOf("*")`) y confiando en `pageHost`
   enviado por la página: un iframe publicitario puede inflar contadores o gastar tokens de IA.
   El repositorio ya tiene `BridgeLimits` y verificación de remitente; hay que conservarlos
   y usar `sourceOrigin` + `isMainFrame` de `WebMessageListener`.
7. **Modo privado sin `MULTI_PROFILE`**: si el WebView del teléfono no admite perfiles, la skill
   abre pestañas "privadas" con las cookies de las normales sin avisar.
8. **Perfil incógnito persistente**: un perfil de WebView vive en disco hasta borrarlo; si la app
   muere no se purga nunca. Hay que borrarlo también al arrancar.
9. `deleteProfile` lanza `IllegalStateException` si algún WebView sigue usando el perfil; la skill
   no lo controla.

**Funcionalidad**

10. `shouldOverrideUrlLoading` bloquea **todos** los esquemas que no sean http/https: rompe
    `tel:`, `mailto:`, `intent://`, Play Store (hoy los gestiona `ExternalApps` con intent saneado).
11. `onCreateWindow` con un *WebView sonda* pierde `window.opener` (rompe inicios de sesión OAuth
    por ventana, que el repositorio preserva a propósito), filtra el WebView sonda si la ventana
    no navega, y no pide confirmación para ventanas de otro origen.
12. Envuelve `EventTarget.prototype.addEventListener` y **descarta oyentes** cuyo código contenga
    `window.open(`: rompe botones de compartir, inicio de sesión y pagos de sitios legítimos.
13. El "neutralizador de clickjacking" oculta capas transparentes grandes con `z-index ≥ 500`,
    incluidas capas de control de reproductores y fondos de diálogos.
14. `mediaPlaybackRequiresUserGesture = false` fijo: ignora la preferencia de reproducción
    automática. `onPermissionRequest` deniega cámara/micrófono siempre y concede DRM sin preguntar;
    el repositorio pregunta por sitio (`SitePermissions`).
15. `ShieldBottomSheetDialogFragment` recibe parámetros por constructor: se cierra la app al
    recrear el fragmento.
16. `setVerticalClipping` solo guarda un número; `NestedScrollWebView` despacha eventos
    desplazados a `super.onTouchEvent` y añade un segundo *fling* sobre el propio de Chromium.
    Además el repositorio no tiene barras colapsables: no hace falta.
17. Recuperación de crash "solo de esa pestaña": varias instancias de WebView pueden compartir el
    proceso renderer; cuando muere, **todas** las asociadas deben destruirse
    (`onRenderProcessGone` llega a cada una).
18. No registra `ServiceWorkerClient`: las peticiones de *service workers* no pasan por
    `shouldInterceptRequest` y escapan al bloqueo.
19. Dependencias de 2024 (`webkit 1.12.1`, `core-ktx 1.13.1`) con un proyecto que ya usa
    `androidx.core 1.19.1` y AGP 9.5.

## 4. Matriz de equivalencias real

Leyenda: ✅ equivalente directo · 🟡 parcial / con trabajo · ❌ sin equivalente en WebView ·
🔎 a verificar en documentación o dispositivo.

### 4.1 Motor y pestañas

| Gecko (archivo) | WebView | Estado |
| --- | --- | --- |
| `GeckoRuntime` (`GeckoRuntimeHolder`) | `WebView` + `WebViewCompat` / `WebSettingsCompat` | ✅ |
| `GeckoSession` + `GeckoView` | Un `WebView` por pestaña viva, reasignado al marco del navegador | ✅ |
| Sesiones LRU (`trimSessions`, `onTrimMemory`) | Destruir WebView y guardar `saveState(Bundle)` | ✅ |
| `SessionState` persistido entre reinicios (`TabStore`) | `saveState` no está pensado para persistir en disco | 🟡 guardar URL + lista atrás/adelante (`copyBackForwardList`) y restaurar la URL |
| `usePrivateMode` | `ProfileStore` + `WebViewCompat.setProfile` (`MULTI_PROFILE`) con **perfiles rotativos** (ver §8) | 🟡 |
| `contextId` (Burner) | Un perfil por identidad Burner; al cerrar su última pestaña se vacían cookies y almacenamiento y el perfil se borra en el siguiente arranque | 🟡 |
| `onCrash`/`onKill` | `onRenderProcessGone` (`didCrash()`), recrear todas las afectadas | ✅ |
| `setActive`/`setPriorityHint`/`setFocused` | `onPause/onResume`, `setRendererPriorityPolicy` | ✅ |
| `onLoadRequest`/`onLocationChange`/`onCanGoBack` | `shouldOverrideUrlLoading`, `doUpdateVisitedHistory`, `canGoBack()` | ✅ |
| `onNewSession` (conserva `opener`) | `onCreateWindow` + `WebViewTransport` con el WebView real de la pestaña nueva | ✅ |
| `onFullScreen` | `onShowCustomView`/`onHideCustomView` | ✅ |
| `onContextMenu` | `onCreateContextMenu` + `getHitTestResult` | 🟡 menos datos (sin `altText`, tipo de medio limitado) |
| `onExternalResponse` (cuerpo en *stream*) | `DownloadListener` + descarga propia con OkHttp y cookies del perfil | 🟡 se pierden descargas resultado de POST y `blob:` sin puente JS |
| `SessionFinder` | `findAllAsync`/`findNext`/`setFindListener` | ✅ |
| `capturePixels` | `PixelCopy` sobre la ventana o `draw` en `Bitmap` | ✅ |
| `saveAsPdf` | `createPrintDocumentAdapter` + diálogo de impresión del sistema | 🟡 pasa por el diálogo "Guardar como PDF" |
| `PanZoomController` (pull-to-refresh) | `scrollY`/`canScrollVertically(-1)` + `onOverScrolled` | 🟡 no se sabe si la página consumió el gesto |
| Modo escritorio (`userAgentMode`, `viewportMode`) | `userAgentString` + `useWideViewPort`/`loadWithOverviewMode` | ✅ |
| `MediaSession.Delegate` | No existe: datos desde `nexo_bridge` (eventos de `<video>` y `navigator.mediaSession.metadata`) | 🟡 |
| `TranslationsController` | — | ❌ (ver decisión 3) |
| `WebExtensionController` (AMO, XPI) | — | ❌ (ver decisión 1) |

### 4.2 Ajustes de privacidad y red (`applyRuntimePrefs`)

| Ajuste Nexo | WebView | Estado |
| --- | --- | --- |
| DoH (Cloudflare, Google, Quad9, propio; modo estricto) | Sin API | ❌ |
| Solo HTTPS | Reescritura en `shouldOverrideUrlLoading` + aviso; subrecursos mixtos con `MIXED_CONTENT_NEVER_ALLOW` | 🟡 |
| Global Privacy Control | `navigator.globalPrivacyControl` por script; la cabecera `Sec-GPC` no se puede añadir a subrecursos | 🟡 |
| Política de cookies (todas / propias / ninguna / aislar terceros) | `setAcceptCookie`, `setAcceptThirdPartyCookies` por WebView | 🟡 sin aislamiento total por sitio |
| ETP estándar/estricta, social tracking | Sustituido por PhantomShield (listas EasyPrivacy) | 🟡 |
| Safe Browsing | `WebSettings.safeBrowsingEnabled` | ✅ |
| Limpieza de parámetros | `shouldOverrideUrlLoading` (marco principal) | ✅ |
| Protección de huellas | Solo mitigaciones por script | 🟡 |
| Tamaño de texto | `textZoom` | ✅ |
| Forzar zoom | Reescribir `meta viewport` por script | 🟡 |
| JavaScript | `javaScriptEnabled` | ✅ |
| Depuración remota | `WebView.setWebContentsDebuggingEnabled` (global) | ✅ (la regla de CI debe actualizarse) |
| Esquema de color preferido | Tema de la app + `setAlgorithmicDarkeningAllowed` | ✅ |
| Latencia de audio (`media.cubeb_*`) | Innecesario | ✅ se elimina |
| Proxy por pestaña (`nexo_network`) | `ProxyController` es **global** | ❌ por pestaña (ver decisión 2) |
| Retardo simulado, geolocalización simulada, variación Canvas/WebGL | `shouldInterceptRequest` (retardo) y script en `document_start` | ✅ |
| Bloqueo de WebRTC y precarga al usar ruta | Sin API; solo por script | 🟡 |

### 4.3 Diálogos y permisos (`PagePrompts`, `SitePermissions`)

| Gecko | WebView | Estado |
| --- | --- | --- |
| alert / confirm / prompt | `onJsAlert` / `onJsConfirm` / `onJsPrompt` | ✅ (se conserva el bloqueo de diálogos repetidos) |
| "¿Salir de la página?" | `onJsBeforeUnload` | ✅ |
| Archivo | `onShowFileChooser` | ✅ |
| Autenticación HTTP | `onReceivedHttpAuthRequest` | ✅ |
| `<select>`, fecha/hora, color | Los dibuja WebView | ✅ (se pierde el estilo propio de Nexo) |
| Reenvío de formulario | `onFormResubmission` | ✅ |
| `navigator.share()` | No existe en WebView | 🟡 *polyfill* con puente |
| Ubicación | `onGeolocationPermissionsShowPrompt` | ✅ |
| Cámara / micrófono / DRM | `onPermissionRequest` | ✅ |
| Notificaciones | Se siguen rechazando | ✅ |

### 4.4 Funciones propias

| Función | Plan | Estado |
| --- | --- | --- |
| Subtítulos por pista/archivo/DOM | El mismo `content_script.js` en `document_start` | ✅ |
| Subtítulos por audio de la página | `captureStream()` en Chromium no silencia el `<video>` | ✅ 🔎 probar en dispositivo |
| Subtítulos por audio del sistema | `AudioPlaybackCapture` filtrado por UID propio | ✅ 🔎 |
| API del reproductor de YouTube (`wrappedJSObject`) | El script ya corre en el mundo de la página | ✅ |
| Modo nocturno (Dark Reader) | Inyección bajo demanda (`evaluateJavascript` del archivo) | ✅ |
| PiP con rectángulo del video | Igual: el rectángulo viene del puente | ✅ |
| Descargas paralelas con reanudación | OkHttp + cookies de `CookieManager` (o del perfil) | ✅ |
| Logos de sitios, historial, grupos, selector, Burner mail | Sin dependencia del motor | ✅ |
| Transferencia de perfil (`MigrationActivity`) | Ya no hay que apagar Gecko; WebView no bloquea los archivos de Nexo | ✅ simplificable |

## 5. Arquitectura propuesta

```
MainActivity / vistas Nexo (sin cambios de diseño)
        │
TabManager ── BrowserTab (sin tipos Gecko)
        │
BrowserEngine (interfaz)            ← fase 1
 ├─ GeckoEngine   (actual, temporal)
 └─ ChromiumEngine                  ← fase 2
      ├─ ChromiumTab (WebView + WebViewClient + WebChromeClient)
      ├─ ProfilePolicy (Default / privado / Burner, purga al arrancar)
      ├─ ShieldInterceptor (hilo IO, Kotlin)  ← motor de red portado
      ├─ PageScripts (document_start: page-hooks, cosmetic-filter, ai-detector, nexo_bridge)
      └─ NexoBridge (WebMessageListener por origen y marco, BridgeLimits)
```

### 5.1 PhantomShield en Chromium (híbrido)

| Pieza | Dónde | Motivo |
| --- | --- | --- |
| `network-engine.js`, `filter-lists.js`, `updater.js`, `surrogate-redirects.js`, listas de dominios, rutas de anuncios de YouTube, parámetros de rastreo | **Kotlin**, en `shouldInterceptRequest` y `ServiceWorkerClient` | Ruta crítica: cada petición. Un viaje a JS por petición añadiría milisegundos por recurso |
| `anti-block-patcher.js` | Kotlin: descarga propia con OkHttp solo para esas dos URL exactas, comprobación SHA-256 y respuesta reescrita | Sustituye `filterResponseData` |
| `page-hooks.js` (MAIN) | Sin cambios, `addDocumentStartJavaScript` en todos los marcos | Ya no usa APIs de extensión |
| `content-script.js`, `cosmetic-filter.js`, `ai-detector.js`, `heuristic-vision.js` | Sin cambios de lógica + *shim* `browser.runtime/storage` sobre el puente | Conservan sus pruebas |
| `tabs.insertCSS` con `cssOrigin:'user'` | Hoja construida (`adoptedStyleSheets`) desde el script, con la ruta `inline` que ya existe como respaldo | 🔎 comportamiento con CSP |
| `ai-classifier.js`, `ai-memory.js`, `ai-domains.js`, `ai-config.js` | JS en un WebView de fondo no visible con *shim*; `fetch` a proveedores pasa por OkHttp nativo | Reutiliza la cadena de respaldo, presupuestos y consentimiento. Sin CORS ni claves en páginas |
| `popup/`, `options/`, `privacy/` | Mismas páginas servidas con `WebViewAssetLoader` en una hoja inferior | Sin reescribir la interfaz |
| Contadores (`browserAction.setBadgeText`) | Estado nativo leído por el escudo de la barra | Sin cambios visibles |

Limitaciones a asumir: WebView no indica el tipo de recurso ni el documento que hace la petición.
El tipo se deduce de `Sec-Fetch-Dest` 🔎 (si WebView lo expone en `requestHeaders`), luego de
`Accept` y de la extensión de la URL; el documento se aproxima con el host principal de la pestaña.
Las opciones `domain=` dentro de iframes serán menos precisas que en Gecko. Las conexiones
WebSocket no pasan por `shouldInterceptRequest`.

### 5.2 Puente nativo

- `addWebMessageListener` con nombre propio y **lista de orígenes acotada** donde sea posible;
  validación de `isMainFrame` y `sourceOrigin` en Kotlin (nunca el host que envía la página).
- Mensajes limitados con `BridgeLimits`; el detector IA solo desde el marco principal.
- El objeto inyectado se captura en una clausura en `document_start` y se borra de `window`.

## 6. Plan por fases

| Fase | Contenido | Criterio de aceptación |
| --- | --- | --- |
| 0 | Decisiones de §7; instalar SDK en CI/local; fijar versión mínima de WebView | Decisiones registradas en este documento |
| 1 | Capa `BrowserEngine`: `BrowserTab.session` deja de ser `GeckoSession`; `TabManager`, `PagePrompts`, `SitePermissions`, `LiveCaptions`, `DownloadStore` hablan con la interfaz. Gecko sigue siendo el único motor | Mismo comportamiento; pruebas JS/JVM/Robolectric verdes |
| 2 | `ChromiumEngine` en un *flavor* `chromium` (Gecko intacto en `gecko`): pestañas, perfiles, navegación, diálogos, permisos, descargas, buscar, PiP, pantalla completa, crash | Navegación diaria completa en el *flavor* chromium |
| 3 | PhantomShield híbrido (§5.1) + pruebas JVM del motor de red con los mismos casos que `phantom-blocking.test.cjs` | Paridad de bloqueo en los casos de prueba existentes |
| 4 | `nexo_bridge` (subtítulos, PiP, modo nocturno, medios) y simulaciones de `nexo_network` | Subtítulos y PiP probados en dispositivo físico |
| 5 | Retirar Gecko, AMO/XPI (según decisión), `compileSdk 37.2`, ABI splits; actualizar CI (`useMultiprocess`, `loadUri("javascript:` → reglas WebView), README y docs | APK universal firmado; CI verde |

## 7. Decisiones

Tomadas por el propietario del proyecto el 15-11-2026:

| Tema | Decisión |
| --- | --- |
| Extensiones de terceros | Crear un **runtime de extensiones propio** sobre WebView que instale extensiones de escritorio (Chrome Web Store, AMO y archivos) — diseño en [§9](#9-runtime-de-extensiones-de-nexo-nxr) |
| Proxy por pestaña / Burner con proxy | **Proxy global** de la app (`ProxyController`) con aviso visible; retardo, ubicación simulada y variación Canvas siguen por pestaña |
| Traducción de páginas | **En la nube**, con el proveedor ya configurado para subtítulos (Google sin clave, DeepL o IA) |
| Permisos de trabajo | Documentación oficial, instalación del Android SDK en el contenedor y comienzo de la fase 1 |
| Teléfonos sin `MULTI_PROFILE` | Pendiente de confirmar; propuesta: desactivar pestañas privadas y Burner con aviso |

## 8. Verificaciones

Documentación oficial consultada el 15-11-2026 (developer.android.com):

| Punto | Resultado | Consecuencia |
| --- | --- | --- |
| Versión de `androidx.webkit` | Estable **1.18.0** (21-10-2026); `minSdk` 24 desde 1.16.0 | Compatible con `minSdk 26` |
| `ProfileStore.deleteProfile` | Lanza `IllegalStateException` si hay WebView vivos con ese perfil **o si el perfil se cargó en memoria** con `getOrCreateProfile`/`getProfile`; no se puede borrar `Default` | El perfil privado **no se puede borrar en el mismo proceso**. Diseño: un perfil privado con nombre aleatorio por sesión privada; al cerrar la última pestaña privada se vacían cookies y almacenamiento, la siguiente usa un perfil nuevo y los antiguos se borran al arrancar la app antes de cargarlos |
| `WebViewCompat.setProfile` | Antes de cualquier otro uso del WebView (salvo añadirlo a la vista); falla si ya navegó o ejecutó JS | Se asigna en el constructor de la pestaña |
| Objetos por perfil | `CookieManager`, `WebStorage`, `GeolocationPermissions`, `ServiceWorkerController`, caché HTTP, precarga | Borrado y permisos por perfil |
| `addDocumentStartJavaScript` | Todos los marcos cuyo **origen** cumple las reglas (sin rutas); solo marcos que empiezan a cargar después de la llamada | Los patrones `matches` de las extensiones se comprueban dentro del script |
| Mundos aislados | Existe `addJavaScriptOnEvent` con `JavaScriptExecutionWorld` (1.16.0) | 🔎 comprobar en 1.18.0 si permite mundos aislados por extensión |
| `addWebMessageListener` con `*` | Se inyecta en todos los marcos; la documentación exige tratar los mensajes como no fiables | Validar `sourceOrigin`, `isMainFrame` y un secreto por pestaña |
| `onRenderProcessGone` | Varios WebView pueden compartir proceso; se llama para cada uno afectado | Recuperar cada pestaña afectada |
| `shouldInterceptRequest` | Fuera del hilo principal; en redirecciones solo se llama para la URL inicial | Los bloqueos por URL final no son posibles |

Pendiente de comprobar en dispositivo: si `getRequestHeaders()` incluye `Sec-Fetch-Dest`,
`adoptedStyleSheets` frente a CSP, captura de audio de WebView y audio en segundo plano.

## 9. Runtime de extensiones de Nexo (NXR)

### 9.1 Por qué no Kiwi

[Kiwi Browser](https://github.com/kiwibrowser/src) (BSD-3) es un *fork* completo de Chromium: su
soporte de extensiones es el subsistema C++ de Chrome compilado dentro del navegador. **No se
puede cargar en el WebView del sistema.** Usarlo implicaría compilar y publicar un Chromium propio
(cientos de GB de código, horas de compilación por versión, APK de 80–100 MB y rebase mensual de
parches de seguridad). El proyecto se archivó en enero de 2025 y su código de extensiones pasó a
Microsoft Edge Canary (código cerrado). Se descarta.

### 9.2 Referencia usada

[WebToApp](https://github.com/shiaho777/web-to-app) (dominio público, *Unlicense*) implementa
un runtime MV3 sobre WebView (~9 500 líneas): página de fondo en un WebView oculto, *polyfill*
`chrome.*`, motor `declarativeNetRequest`, descarga de CRX desde Chrome Web Store y registro de
`content_scripts`. Sirve como guía, pero es una app de una sola vista: no tiene pestañas, usa
`addJavascriptInterface` (síncrono y visible para la página) y su `webRequest` bloquea por patrón
sin consultar al oyente. NXR toma la idea, no el código.

### 9.3 Diseño

| Pieza | Diseño |
| --- | --- |
| Formatos | CRX3 (Chrome Web Store), XPI (AMO), ZIP y carpeta. CRX3: se valida la cabecera *protobuf*, la firma y que el ID salga de la clave pública |
| Instalación | Chrome Web Store por el *endpoint* de actualización `update2`, AMO con `AmoClient`, archivo con los validadores actuales (rutas, nº de entradas, tamaño descomprimido) |
| Almacenamiento | `files/extensions/<id>/<versión>/` descomprimido; servido por `shouldInterceptRequest` en `https://<id>.extension.nexo.invalid/` (`.invalid` nunca resuelve si la interceptación fallara) |
| Fondo | Un WebView oculto por extensión con perfil propio: páginas MV2 y *service worker* MV3 emulado como página de eventos |
| Scripts de contenido | `document_start/end/idle`, `all_frames`, `match_about_blank`, `world: MAIN`; mundo aislado si `JavaScriptExecutionWorld` lo permite, si no clausura con intrínsecos capturados al inicio |
| Mensajería | `WebMessageListener` propio; cada pestaña recibe un secreto aleatorio dentro del script inyectado (la página no puede leerlo) y Kotlin rechaza mensajes sin él, de otro origen o de marcos no permitidos |
| Red | Una sola tubería en `shouldInterceptRequest` y `ServiceWorkerClient`: PhantomShield nativo → reglas `declarativeNetRequest` (Kotlin) → oyentes `webRequest` bloqueantes con tiempo límite |
| Páginas de extensión | *Popup* en hoja inferior, opciones en pestaña, CSP de MV3 añadida a las respuestas |
| Seguridad | Diálogo de permisos al instalar, "permitida en privado" desactivado por defecto, sin acceso a páginas internas de Nexo, actualizaciones con verificación de firma |

### 9.4 APIs por niveles

| Nivel | APIs |
| --- | --- |
| 1 | `runtime`, `storage` (`local`, `sync` local, `session`, `onChanged`), `tabs`, `scripting`, `i18n`, `action`/`browserAction`, `alarms`, `permissions`, `declarativeNetRequest`, `webRequest` (bloqueo con límite de tiempo), `webNavigation`, `contextMenus` (en el menú de pulsación larga), `notifications`, `extension` |
| 2 | `cookies` (sin cookies `HttpOnly` de lectura directa), `downloads`, `history`, `bookmarks` (accesos de inicio), `windows` (una ventana), `offscreen`, `sidePanel` (hoja), `userScripts` |
| No | `debugger`, `devtools`, `nativeMessaging`, `proxy` por extensión, `privacy`, `management` completo |

Límites inherentes a WebView: sin cuerpo de las peticiones (`requestBody`), sin cambiar
cabeceras de respuesta salvo repitiendo la petición con OkHttp, redirecciones de marco principal
solo en `shouldOverrideUrlLoading`, sin WebSocket en `webRequest`. Al instalar, Nexo compara el
manifiesto con esta tabla y muestra la compatibilidad esperada (completa, parcial o no compatible).

PhantomShield y el puente de Nexo no pasan por NXR: son módulos integrados con motor nativo
(§5.1); sus páginas (panel y opciones) sí usan el alojamiento de páginas de NXR.

## 10. Estado de la fase 1

Hecho en la rama de trabajo, sin cambios de comportamiento:

- Paquete `engine/`: `BrowserEngine`, `EngineSession`, `EngineObserver`, `EngineView`,
  `EngineFinder`, `EngineMedia`, `EngineDownload` y `SessionParams`.
- `engine/gecko/GeckoEngine.kt`: GeckoView detrás de la interfaz; traduce sus delegados a
  `EngineObserver` y mantiene una única envoltura por sesión.
- `BrowserTab`, `TabManager`, `MainActivity`, `LiveCaptions`, `CaptionsUi`, `SystemCaptions`,
  `PlaybackAudioCapture`, `PerfLog`, `BrowserMediaController` y `BrowsingData` ya no importan
  GeckoView. `TabManager` recibe los eventos en un `TabObserver` por pestaña.
- Siguen ligados a Gecko, como módulos del motor que la fase 2 sustituye: `ExtensionManager`,
  `TabRouting`, `TranslatorManager`, `PagePrompts`, `SitePermissions`, `DownloadStore` y la
  interfaz de extensiones. Su API pública ya recibe `EngineSession`.
- La vista de página se llama `pageView` en el *layout*.
