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
| Cookies de la versión Gecko | `GeckoProfileImport.kt` | `CefriumCookieManager.setCookie` |

Cada navegador de Cefrium se dibuja en una superficie ligada a una Activity: la pestaña crea
su navegador cuando hay una (`ensureBrowser`) y, si esa Activity se destruye, lo vuelve a
crear en la siguiente con su historial completo. `MainActivity` no es una `ComponentActivity`,
así que reenvía `onActivityResult` y `onRequestPermissionsResult` al motor.

## Puente de páginas

`window.cefriumQuery` solo admite respuestas inmediatas y un manejador por navegador. Cada
pestaña tiene un `PageChannel`: las páginas envían `{m, s}` y reciben la respuesta más los
eventos que Nexo publicó desde `s` (ajustes, órdenes de mini video y subtítulos, respuestas
tardías del panel). Los marcos consultan cada 1 s (principal) o 3 s (internos) mientras la
página es visible, y Nexo despierta al marco principal al publicar. `phantom-shim.js` y
`nexo-bridge.js` no cambian: reciben el mismo puerto `__nexoShield` que en WebView.

Los scripts corren en el mundo de la página (Cefrium no tiene mundos aislados): el puente
solo responde lo que cualquier página ya puede ver. Ajustes, estadísticas e IA solo se
aceptan desde el panel, en su propio origen (`https://phantomshield.nexo.invalid`).

## Respecto a Gecko

Igual o mejor:

- Pestañas privadas fuera de registro (solo memoria) y Burner con perfil propio, en todos los
  dispositivos (WebView necesitaba `MULTI_PROFILE`).
- DNS sobre HTTPS con proveedor y modo estricto (WebView no lo permitía).
- Proxy SOCKS5/HTTP por pestaña **en pestañas Burner** (perfil propio, sin conexión directa de
  respaldo).
- PhantomShield recibe el tipo exacto de cada petición y la página principal desde Chromium.
- Historial atrás/adelante restaurado tras reiniciar la app.
- PDF directo (`printToPdf`) y política de cookies (todas, propias, ninguna) en el interceptor.

Pendiente (la interfaz lo explica al usuario):

1. **Extensiones de terceros** (tienda AMO, XPI): Chromium embebido no ejecuta WebExtensions;
   llegarán con el runtime NXR. `ExtensionsActivity` ya comprueba la compatibilidad.
2. **Traducción de páginas**: Gecko traducía en el dispositivo; falta traducir en la nube con
   los proveedores ya configurados para subtítulos.
3. **Proxy en pestañas normales o privadas**: el proxy de Chromium es del perfil, así que esas
   pestañas con ruta no se abren (nunca salen por conexión directa).
4. **Retardo simulado, ubicación simulada y variación de huella**: dependían de `nexo_network`.
5. **Puente completo de `nexo_bridge`** (Dark Reader para páginas, protección de la
   reproducción en segundo plano): el puente reducido cubre mini video y subtítulos.
6. **IA de PhantomShield, parcheador anti-bloqueo**: como en la variante WebView.
7. Descargas `blob:`/`data:` y resultado de un POST: Nexo vuelve a pedir la URL, como con WebView.
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
