# Migración a Chromium nativo (Cefrium) — estado

## Decisión

Nexo usa un solo motor: **Chromium 156 embebido con Cefrium 0.21.0** (`com.cefrium:*`,
LGPL-3.0, hashes en `gradle/cefrium-0.21.0.sha256`). Se retiran GeckoView y Android System
WebView: ya no hay flavors `gecko`/`chromium`, ni dependencias `org.mozilla.geckoview` o
`androidx.webkit`. El código usa solo la API pública `com.cefrium.*` (el CI rechaza
`org.chromium.*`, `org.jni_zero.*` y `GEN_JNI`).

Requisitos nuevos: **Android 10 (API 29)** como mínimo y **JDK 25** para compilar.

## Cómo encaja

`main` ya hablaba con el motor solo a través de `engine/BrowserEngine.kt` y
`engine/EngineSession.kt`. La implementación vive en `engine/cefrium/`:

| Pieza | Archivo | API de Cefrium |
| --- | --- | --- |
| Motor, DoH, depuración, borrado de datos | `CefriumEngine.kt` | `Cefrium.initialize`, `setSecureDns`, `setRemoteDebuggingEnabled` |
| Pestaña | `CefriumEngineSession.kt` | `CefriumBrowser.createWithSurface(activity, profile)` |
| Navegación, errores, TLS, autenticación HTTP | `CefriumEngineSession.Navigation` | `CefriumNavigationClient` |
| Título, pantalla completa, diálogos JS, archivos, menú contextual, ventanas, permisos | `CefriumEngineSession.Chrome` | `CefriumChromeClient` |
| Historial persistente (atrás/adelante tras reiniciar) | `CefriumState.kt` | `serializeNavigationState` / `restoreNavigationState` |
| Perfiles normal, privado y Burner; proxy de Burner | `CefriumProfiles.kt` | `CefriumProfile.getDefault/incognito/get`, `setProxy` |
| Descargas con el gestor de Nexo | `CefriumDownloads.kt` | `CefriumDownloadClient` + cookies del perfil |
| Vista, capturas | `CefriumPageView.kt` | `getSurfaceContainer`, `captureBitmap` |
| PhantomShield (red) | `shield/PhantomShield.kt` | `CefriumResourceInterceptor` del navegador y del perfil (service workers) |
| PhantomShield (páginas) y puente de video | `assets/nexo/cefrium-port.js`, `shield/PageChannel.kt` | `addDocumentStartJavaScript`, `setQueryHandler` |
| Panel de PhantomShield | `PhantomShieldPanel.kt` | `CefriumProfile.registerContentServer` |
| Cookies de la versión anterior (actualización) | `UpgradeImport.kt`, `ImportedCookies.kt` | `CefriumCookieManager.setCookie` |

Cada navegador de Cefrium se dibuja en una superficie ligada a una Activity: la pestaña crea
su navegador cuando hay una (`ensureBrowser`) y, si esa Activity se destruye, lo vuelve a
crear en la siguiente con su historial completo. `MainActivity` no es una `ComponentActivity`,
así que reenvía `onActivityResult` y `onRequestPermissionsResult` al motor.

## Puente de páginas

`window.cefriumQuery` solo admite respuestas inmediatas y un manejador por navegador. Cada
pestaña tiene un `PageChannel`: las páginas envían `{m, s}` y reciben la respuesta más los
eventos que Nexo publicó desde `s` (ajustes, órdenes de mini video y subtítulos, respuestas
tardías del panel). Los marcos consultan cada 2 s (principal) o 4 s (internos) mientras la
página es visible, y Nexo despierta al marco principal al publicar. `phantom-shim.js` no cambia:
recibe el mismo puerto `__nexoShield` que en WebView.

El puente de Nexo es `assets/nexo/bridge/page-bridge.js` (mini video
con `pip.css`, subtítulos por pista, texto del reproductor o audio del video para Deepgram,
protección de la reproducción en segundo plano, modo nocturno con Dark Reader). `bridge-shim.js`
le da su `browser.runtime` (`connectNative`, `sendNativeMessage`, `sendMessage`): cada marco lleva
un token aleatorio y `PageBridge.kt` decide qué acepta de cada uno (los marcos
con video se ofrecen, Nexo concede la captura al mejor en 200 ms y solo ese marco envía audio).
Dark Reader se inyecta bajo demanda en el marco principal con `evaluateJavaScript`.
`media-reporter.js` informa los medios con sonido (notificación y segundo plano).

Diagnóstico: Ajustes › tocar la versión muestra el estado del motor, de PhantomShield, de los
subtítulos y los últimos eventos (`EngineDiagnostics`), con botón para copiarlo.

Los scripts corren en el mundo de la página (Cefrium no tiene mundos aislados): el puente
solo responde lo que cualquier página ya puede ver. Ajustes, estadísticas e IA solo se
aceptan desde el panel, en su propio origen (`https://phantomshield.nexo.invalid`).

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
- Notificación multimedia y reproducción en segundo plano: el puente informa los medios con sonido
  (y el título de `navigator.mediaSession`); pausa y reanudación las hace Chromium en el proceso
  del navegador, también con la página en segundo plano.

Pendiente (la interfaz lo explica al usuario):

1. **Extensiones de terceros** (tienda AMO, XPI): Chromium embebido no ejecuta WebExtensions;
   llegarán con el runtime NXR. `ExtensionsActivity` ya comprueba la compatibilidad.
2. **Traducción de páginas**: Gecko traducía en el dispositivo; falta traducir en la nube con
   los proveedores ya configurados para subtítulos.
3. **Proxy en pestañas normales o privadas**: el proxy de Chromium es del perfil, así que esas
   pestañas con ruta no se abren (nunca salen por conexión directa).
4. **Retardo simulado, ubicación simulada y variación de huella** (antes, extensión `nexo_network`, retirada).
5. **Modo nocturno en marcos internos**: Dark Reader solo se inyecta en el marco principal.
6. **IA de PhantomShield, parcheador anti-bloqueo**: como en la variante WebView.
7. Descargas resultado de un POST: Nexo vuelve a pedir la URL (las `blob:`/`data:` que genera la
   página sí funcionan: Chromium las guarda y pasan al gestor de Nexo).
8. Política de cookies "equilibrada": hoy es la de Chromium (sin bloqueo de cookies de rastreo
   de terceros como hacía la protección estándar de Gecko).

## Antes de publicar

- Probar en dispositivos físicos arm64 y armeabi-v7a (Android 10+): navegación, pestañas,
  privada/Burner, PhantomShield, descargas, PiP, subtítulos, permisos, pantalla completa.
- Probar la actualización firmada desde la versión Gecko publicada: conserva historial,
  pestañas, ajustes y cookies importadas.
- Subir `versionCode`/`versionName`: quienes usen Android 8 o 9 dejarán de recibir
  actualizaciones (minSdk 29).
- Revisar las obligaciones de la licencia LGPL-3.0 de Cefrium (aviso y código fuente).
