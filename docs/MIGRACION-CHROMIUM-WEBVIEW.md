# Auditoría y plan: migración de Nexo a Chromium WebView

> Estado: **propuesta, pendiente de decisiones** (ver [§7](#7-decisiones-pendientes)).
> Base revisada: `main` en v0.30.2 (`versionCode 61`), commit `9bcb1ca`.
> Nada de este documento cambia todavía el código de la app.

## 1. Resumen

- La migración es **viable y conveniente** para tamaño (el APK arm64 pasa de ~100 MB a
  un APK universal estimado de menos de 10 MB, sin `libxul.so` ni división por ABI), arranque en
  frío y compatibilidad web, pero **no es 1:1**. Hay cuatro funciones que Android WebView no
  ofrece y que requieren decisión de producto antes de empezar:
  1. tienda AMO e instalación de XPI de terceros;
  2. proxy SOCKS5/HTTP **por pestaña** (WebView solo admite proxy para toda la app);
  3. traducción local de Gecko (Firefox Translations);
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
| `usePrivateMode` | `ProfileStore` + `WebViewCompat.setProfile` (`MULTI_PROFILE`) | 🟡 🔎 versión mínima de WebView |
| `contextId` (Burner) | Un perfil por identidad Burner, borrado al cerrar su última pestaña | 🟡 mismo requisito |
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

## 7. Decisiones pendientes

1. **Extensiones de terceros (AMO/XPI)**: Android WebView no ejecuta WebExtensions.
   Opciones: retirarlas y dejar solo los módulos integrados portados; o mantener dos *flavors*
   (Gecko con extensiones, Chromium ligero) de forma permanente.
2. **Proxy por pestaña / Burner con proxy**: convertirlo en un proxy global de la app
   (`ProxyController`, afecta a todas las pestañas mientras está activo), o retirarlo.
   Pasar solo los subrecursos por OkHttp **no** es seguro (POST, WebSocket y WebRTC escaparían).
3. **Traducción de páginas**: ML Kit en el dispositivo (descarga modelos de Google; el texto no
   sale del teléfono), traducción en la nube, o retirarla temporalmente.
4. **Teléfonos sin `MULTI_PROFILE`**: desactivar pestañas privadas y Burner con aviso
   (recomendado) o permitirlas compartiendo cookies.
5. **Permisos de trabajo**: consulta de documentación oficial (developer.android.com,
   versiones en maven.google.com) e instalación del Android SDK en el contenedor para compilar.

## 8. Puntos a verificar antes de implementar

- Versión mínima de WebView y de `androidx.webkit` para `MULTI_PROFILE`,
  `DOCUMENT_START_SCRIPT` y `WEB_MESSAGE_LISTENER`; versión estable actual de `androidx.webkit`.
- Si `WebResourceRequest.getRequestHeaders()` incluye `Sec-Fetch-Dest`.
- Comportamiento de `adoptedStyleSheets` frente a CSP `style-src`.
- Si `ProfileStore.deleteProfile` acepta perfiles cuyos WebView ya se destruyeron.
- Captura de audio de la reproducción de WebView y continuidad de audio en segundo plano
  (prueba en dispositivo físico).
