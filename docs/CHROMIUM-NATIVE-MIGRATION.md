# Migración a Chromium nativo (Cefrium) — estado

## Decisión

Nexo usa un solo motor: **Chromium 158 embebido con Cefrium 0.22.0** (`com.cefrium:*`,
LGPL-3.0-or-later, hashes verificados por GPG y Maven en [`gradle/cefrium-0.22.0.sha256`](../gradle/cefrium-0.22.0.sha256)).
Se retiraron GeckoView y Android System WebView: ya no hay flavors `gecko`/`chromium`, ni
dependencias `org.mozilla.geckoview` o `androidx.webkit`. Desde `0.22.0`, además,
`cefrium-sdk-core` **ya no arrastra Google Play services, Firebase ni ML Kit**.
El código usa solo la API pública `com.cefrium.*` (el CI rechaza `org.chromium.*`,
`org.jni_zero.*` y `GEN_JNI`).

Requisitos: **Android 10 (API 29)** como mínimo y **JDK 25** para compilar (`compileSdk 37.2`).
Para el detalle de los cambios de la migración a `0.22.0` (respuesta al issue `#84`) y los
límites que siguen abiertos en el SDK, consulta [`docs/CEFRIUM-0.22-MIGRACION-Y-LIMITES.md`](CEFRIUM-0.22-MIGRACION-Y-LIMITES.md).

## Cómo encaja

`main` habla con el motor solo a través de `engine/BrowserEngine.kt` y
`engine/EngineSession.kt`. La implementación vive en `engine/cefrium/`:

| Pieza | Archivo | API de Cefrium |
| --- | --- | --- |
| Aislamiento de procesos del motor | `NexoApplication.kt` + manifiesto SDK | `CefriumAppComponentFactory` (fusión automática del AAR) |
| Motor, DoH, depuración, borrado de datos | `CefriumEngine.kt` | `Cefrium.initialize`, `setSecureDns`, `setRemoteDebuggingEnabled` |
| Pestaña, PiP y Client Hints de escritorio | `CefriumEngineSession.kt` | `CefriumBrowser.createWithSurface`, `onPause()` en PiP, `setUserAgentOverride(ua, CefriumUserAgentMetadata)` |
| Navegación, errores, TLS, autenticación HTTP | `CefriumEngineSession.Navigation` | `CefriumNavigationClient` |
| Título, pantalla completa, diálogos JS, archivos nativos, menú contextual, ventanas, permisos | `CefriumEngineSession.Chrome`, `PagePrompts.kt` | `CefriumChromeClient` (selector nativo `<input type=file>` al devolver `false`) |
| Historial persistente (atrás/adelante tras reiniciar) | `CefriumState.kt` | `serializeNavigationState` / `restoreNavigationState` |
| Perfiles normal, privado y Burner; proxy de Burner | `CefriumProfiles.kt` | `CefriumProfile.getDefault/incognito/get`, `setProxy` |
| Descargas con el gestor de Nexo | `CefriumDownloads.kt` | `CefriumDownloadClient` + cookies del perfil |
| Vista, foco de `EditText` y capturas | `CefriumPageView.kt` | `getSurfaceContainer` (foco nativo respetado en `CefriumSurfaceContainer`), `captureBitmap` |
| PhantomShield (red) | `shield/PhantomShield.kt` | `CefriumResourceInterceptor` del navegador y del perfil (service workers) |
| PhantomShield (páginas) y puente de video | `assets/nexo/cefrium-port.js`, `shield/PageChannel.kt` | `addDocumentStartJavaScript`, `setQueryHandler` con `QueryCallback` diferido |
| Panel de PhantomShield | `PhantomShieldPanel.kt` | `CefriumProfile.registerContentServer` |
| Diagnóstico unificado | `EngineDiagnostics.kt` | `Cefrium.diagnostics(context)` + métricas y eventos saneados |
| Cookies de la versión anterior (actualización) | `UpgradeImport.kt`, `ImportedCookies.kt` | `CefriumCookieManager.setCookie` |

Cada navegador de Cefrium se dibuja en una superficie ligada a una Activity: la pestaña crea
su navegador cuando hay una (`ensureBrowser`) y, si esa Activity se destruye, lo vuelve a
crear en la siguiente con su historial completo. `MainActivity` no es una `ComponentActivity`,
así que reenvía `onActivityResult` y `onRequestPermissionsResult` al motor (necesario también
para el selector nativo de archivos y la captura con cámara de Cefrium `0.22.0`).

## Puente de páginas (asíncrono, sin polling)

`window.cefriumQuery` admite un manejador por navegador, pero su `QueryCallback` es
asíncrono y seguro entre hilos: Nexo puede retener el callback y responderlo más tarde desde
cualquier hilo cuando llegue un evento o concluya una operación asíncrona.

Cada pestaña (y el anfitrión oculto de IA `ShieldAiHost`) tiene un `PageChannel`:

- **RPC diferido directo**: las operaciones asíncronas (`nexo_dark_load`, `AI_CLASSIFY`,
  `tabs.sendMessage(AI_SCAN)`, `FORCE_UPDATE`, `AI_*`, `fetch` de IA) guardan el
  `QueryCallback` del `cefriumQuery` y responden directamente al completarse, sin ciclos de
  espera.
- **Push sin `setInterval`**: cada marco mantiene una única petición `cefriumQuery` en espera
  (`{m: null, s, w: true, k}`) aparcada en `PageChannel.awaitEvents(...)`. En cuanto Nexo
  publica un evento (ajustes, órdenes de mini video y subtítulos, avisos del panel), el canal
  despierta inmediatamente a los callbacks en espera —incluidos los de iframes internos y del
  anfitrión oculto— sin usar temporizadores de 2–4 s ni llamadas `evaluateJavaScript("__nexoWake")`.
  Como `QueryHandler` en Cefrium `0.22.0` todavía no expone `onQueryCanceled`, los callbacks
  en espera se dedupan por clave de marco, se acotan (`MAX_WAITERS = 32`) y se liberan en
  `reset()` y `dispose()`.
- `phantom-shim.js` no cambia: recibe el mismo puerto `__nexoShield`.

El puente de Nexo es `assets/nexo/bridge/page-bridge.js` (mini video
con `pip.css`, subtítulos por pista, texto del reproductor o audio del video para Deepgram,
protección de la reproducción en segundo plano, modo nocturno con Dark Reader). `bridge-shim.js`
le da su `browser.runtime` (`connectNative`, `sendNativeMessage`, `sendMessage`): cada marco lleva
un token aleatorio y `PageBridge.kt` decide qué acepta de cada uno (los marcos
con video se ofrecen, Nexo concede la captura al mejor en 200 ms y solo ese marco envía audio).
Dark Reader se inyecta bajo demanda en el marco principal con `evaluateJavaScript`.
`media-reporter.js` informa los medios con sonido (notificación y segundo plano), ya que
`CefriumMediaSession` no expone observadores públicos de metadatos ni estado.

Diagnóstico: Ajustes › tocar la versión muestra el estado del motor, el bloque oficial de
`Cefrium.diagnostics(context)` (versión, ABI, ausencia de bibliotecas de Google,
`appComponentFactory` y salidas recientes de procesos), contadores de recuperación/watchdog de
`CefResumeBridge`, PhantomShield, subtítulos y los últimos eventos (`EngineDiagnostics`), con
botón para copiarlo.

Los scripts corren en el mundo de la página (Cefrium `0.22.0` todavía no tiene mundos aislados):
`cefrium-port.js` congela `globalThis.cefriumQuery` en `document_start` y el puente solo
responde lo que cualquier página ya puede ver. Ajustes, estadísticas e IA solo se aceptan desde
el panel, en su propio origen (`https://phantomshield.nexo.invalid`).

IA de PhantomShield (opcional, con la API del usuario): el detector (`content/ai-detector.js`)
corre en la página principal; Nexo decide si el sitio puede usar IA (`ShieldAi`: activada,
aceptada, sitio autorizado, no privada, escudo activo) y pasa cada lote al clasificador
original, que se ejecuta en un navegador Chromium oculto (`ShieldAiHost`, `host/ai-host.html`,
perfil del panel). La clave de API solo existe en ese anfitrión y en los ajustes; las
llamadas al proveedor las hace Nexo (HTTPS, sin cookies ni redirecciones, respuesta de 64 KB
como máximo). Las direcciones de enlaces y marcos de los candidatos se comprueban en el
dispositivo y nunca llegan al proveedor. Los dominios publicitarios aprendidos se cortan en la
red (peticiones de terceros, no medios) y en las ventanas emergentes.

## Sin Gecko

La app no incluye GeckoView ni librerías de Mozilla. Lo único que conoce el formato de la
versión anterior es la importación de cookies al actualizar (`UpgradeImport.kt`, solo lee
`files/mozilla/` si existe y lo borra a los 30 días) y la validación de copias de perfil
antiguas (`TransferSchema`), que se aceptan pero ignoran los datos de aquel motor. Se
retiraron la tienda AMO, la traducción local, `nexo_network`, el esquema `moz-extension:` y la
identidad de Firefox de PhantomShield. La transferencia de perfil ya no necesita el perfil de
Gecko (antes fallaba en una instalación con Chromium).

## Respecto a la versión anterior

Igual o mejor:

- Pestañas privadas fuera de registro (solo memoria) y Burner con perfil propio, en todos los
  dispositivos (WebView necesitaba `MULTI_PROFILE`).
- DNS sobre HTTPS con proveedor y modo estricto (WebView no lo permitía).
- Proxy SOCKS5/HTTP por pestaña **en pestañas Burner** (perfil propio, sin conexión directa de
  respaldo).
- PhantomShield recibe el tipo exacto de cada petición y la página principal desde Chromium.
- Historial atrás/adelante restaurado tras reiniciar la app.
- PDF directo (`printToPdf`) y política de cookies (todas, propias, ninguna) en el interceptor; el
  filtro de cookies solo se activa cuando la política lo necesita.
- Picture-in-Picture gestionado mediante `browser.onPause()` en `MainActivity.onPause()`
  (mantiene el vídeo visible en PiP, pausa al cerrar la ventana PiP y recupera tras apagar y
  encender la pantalla en Android 10), manteniendo audio en segundo plano cuando el usuario lo
  activa.
- Selector nativo de archivos de Chromium (`accept`, `capture`, cámara vía `CefriumFileProvider`,
  selección múltiple y carpetas) y Client Hints completos (`CefriumUserAgentMetadata`) en modo
  escritorio.
- Notificación multimedia y reproducción en segundo plano: el puente informa los medios con sonido
  (y el título de `navigator.mediaSession`); pausa y reanudación las hace Chromium en el proceso
  del navegador, también con la página en segundo plano.
- APK libre de dependencias de Google Play services, Firebase y ML Kit desde Cefrium `0.22.0`.

Pendiente o condicionado por límites actuales de Cefrium `0.22.0` (ver detalle técnico completo en
[`docs/CEFRIUM-0.22-MIGRACION-Y-LIMITES.md`](CEFRIUM-0.22-MIGRACION-Y-LIMITES.md)):

1. **Extensiones de terceros (NXR)**: ya existen instalador CRX3, popups y reglas DNR estáticas de
   bloqueo/excepción, pero siguen bloqueados por el SDK los **mundos V8 aislados**, las
   **redirecciones/modificación de cabeceras** en `CefriumResourceInterceptor` y los
   **Service Workers MV3**.
2. **Proxy en pestañas normales o privadas**: el proxy de Chromium es del perfil, así que esas
   pestañas con ruta no se abren (nunca salen por conexión directa).
3. **Retardo simulado, ubicación simulada y variación de huella** (antes, extensión `nexo_network`, retirada).
4. **Modo nocturno en marcos internos**: `evaluateJavaScript` solo apunta al marco principal o al
   marco con foco; no existe `evaluateJavaScript(frameId, script)`.
5. **Parcheador anti-bloqueo de PhantomShield** (la IA ya funciona desde 0.32.0).
6. **Descargas resultado de un POST**: `DownloadItem` no expone método HTTP ni transferencia del
   flujo de bytes al gestor externo de Nexo (las `blob:`/`data:` sí funcionan: Chromium las guarda
   y pasan al gestor de Nexo).
7. **Política de cookies «equilibrada»**: `onCanSendCookie` / `onCanSaveCookie` no reciben la URL
   de primer nivel (top-level), lo que impide un filtro exacto de terceros para subrecursos y
   service workers.
8. **Retirada definitiva de `CefResumeBridge`**: instrumentado con contador `watchdog` en
   `EngineDiagnostics` para verificar en dispositivo físico con `0.22.0` si ya puede eliminarse.

## Antes de publicar

- Probar en dispositivos físicos `arm64-v8a` y `armeabi-v7a` (Android 10+): navegación, pestañas,
  privada/Burner, PhantomShield, descargas, PiP (incluyendo apagado/encendido de pantalla en
  Android 10 y audio en segundo plano), selector de archivos/cámara, foco de la barra de
  direcciones tras diálogos, subtítulos, permisos, pantalla completa y reanudación (`watchdog=0`
  en Ajustes › versión).
- Probar la actualización firmada desde la versión anterior: conserva historial, pestañas, ajustes
  y cookies.
- Revisar las obligaciones de la licencia LGPL-3.0 de Cefrium (aviso y código fuente).

