# Migración a Cefrium 0.22.0 (Chromium 158) y límites verificados del SDK

> Documento técnico de la migración en respuesta al issue [`#84`](https://github.com/stivencop414-bot/Nexo-Browser/issues/84) abierto por el mantenedor de Cefrium (`vejeta`), con contraste directo frente al código fuente y la firma pública `cefrium-sdk.api` de **Cefrium `0.22.0`** (Chromium `158.0.8127.18`).

---

## 1. Resumen de la migración

- **Motor y SDK:** actualización de `com.cefrium:*` de `0.21.0` (Chromium `156.0.8078.96`) a **`0.22.0`** (Chromium **`158.0.8127.18`**) tanto en `:app` como en `cefrium-prototype/`.
- **Integridad criptográfica:** todas las huellas SHA-256 de `gradle/cefrium-0.22.0.sha256` coinciden con los archivos `.sha256` del repositorio Maven (`https://maven.cefrium.com`) y con `SHA256SUMS-0.22.0` de la release oficial en Codeberg, verificado con GnuPG (`GOODSIG` / `VALIDSIG`, huella `2A840ED00DE669F4219B8B3302A79E3B82151695`, subclave `5B639D9BFE995C6418CEFA8970CB7258A38E268F`).
- **Sin dependencias de Google Play services, Firebase ni ML Kit:** el POM de `com.cefrium:cefrium-sdk-core:0.22.0` elimina todas las dependencias de `com.google.android.gms`, `com.google.firebase`, `com.google.mlkit`, `com.google.android.play` y `com.google.android.datatransport`. El APK de Nexo queda libre de esas librerías de forma nativa.

---

## 2. Respuesta punto por punto al issue `#84`

| # | Sugerencia de `vejeta` (`#84`) | Qué hace Cefrium `0.22.0` | Implementación en Nexo |
| --- | --- | --- | --- |
| 1 | **PiP en blanco al llamar `browser.onPause()`** | `CefriumBrowser.onPause()` detecta `mActivity.isInPictureInPictureMode()`, aplaza la pausa real hasta `onActivityStopped` (`deferPauseUntilStopped()`) y recupera en `onActivityStarted` tras apagar/encender pantalla en Android 10. | `MainActivity.onPause()` reenvía a `CefriumEngineSession.onHostPauseInPip()` cuando la Activity está en PiP y el usuario no requiere audio en segundo plano. Se eliminó `session.setActive(true)` de `TabManager.prepareForPictureInPicture()` y se protegió `MainActivity.onStart()` cuando `isInPictureInPictureMode` es `true` para no cancelar `mPauseDeferred` ni la recuperación de pantalla en Android 10. |
| 2 | **Robo de foco a `EditText` nativos** | `CefriumSurfaceContainer` comprueba `!anotherViewHasFocus()` en `setFocusSink` y `onWindowFocusChanged` antes de devolver foco al navegador; `browser.onResume()` ya no toca el foco. | Se retiraron `isNativeInputFocused` / `hasNativeTextInputFocus()` de `CefriumPageView` y `MainActivity`, `CefResumePolicy.shouldFocusBrowser(...)`, las llamadas `setFocus(true)` de `CefResumeBridge` y `session.setFocused(true)` en `TabManager.resumeActive()`. |
| 3 | **`Application` en procesos hijos del motor** | El AAR fusiona `com.cefrium.CefriumAppComponentFactory` (`tools:replace="android:appComponentFactory"`), entregando un `Application()` plano a `:sandboxed_process*` y `:privileged_process*`. | Se eliminó `isMainProcess()` y los retornos tempranos de `NexoApplication` (`attachBaseContext`, `onCreate`, `onTrimMemory`). `tools/check_release_reflection.py` ahora también valida `android:appComponentFactory` en el APK R8 para asegurar que R8 conserva el constructor de `CefriumAppComponentFactory`. |
| 4 | **`CefResumeBridge` (pantalla negra al reanudar)** | En `0.22.0` no se reproduce pantalla negra en las pruebas automáticas de píxeles del SDK; si ocurriera, requiere dispositivo, versión de Android y pasos en Codeberg. | Se mantuvo `CefResumeBridge` como red de seguridad temporal (sin robo de foco) e instrumentado con contadores `surfaceRecoveries` y `watchdogRecreations` + `Cefrium.diagnostics(context)` en `EngineDiagnostics` para verificar `watchdog=0` en hardware real antes de eliminarlo definitivamente. |
| 5 | **`cefriumQuery` asíncrono (sin polling de 2–4 s)** | `QueryCallback.success/failure` puede retenerse y responderse más tarde desde cualquier hilo (`nativeQueryResult` publica a `TID_UI` con bloqueo `LiveBridgeData`). | Se eliminó `setInterval` de `cefrium-port.js` y el hack `setTimeout(..., 250)` de `bridge-shim.js`. `PageChannel` ahora usa espera aparcada (`awaitEvents`, acotada y dedupada por marco) para push inmediato —incluso en iframes y en el anfitrión oculto `ShieldAiHost`— y `PhantomShield`/`ShieldAiHost` resuelven directamente el `QueryCallback` en RPCs asíncronos (`nexo_dark_load`, `AI_CLASSIFY`, `AI_SCAN`, `FORCE_UPDATE`, `AI_*`, `fetch`). |

---

## 3. Otras capacidades de Cefrium `0.22.0` adoptadas

1. **Diagnóstico oficial del SDK (`Cefrium.diagnostics(context)`):** integrado en `EngineDiagnostics.report(context)` (Ajustes › tocar la versión, saneado por `sanitizeReport`). Incluye versión del SDK y Chromium, ABI del proceso y del dispositivo, confirmación de ausencia de bibliotecas de Google (`google libraries in the app: no`), `appComponentFactory` activo y salidas recientes de procesos (`ApplicationExitInfo` en Android 11+).
2. **Client Hints de escritorio (`CefriumUserAgentMetadata`):** en modo escritorio (incluido el modo automático para Chrome Web Store y Edge Add-ons), `CefriumEngineSession.applyUserAgent()` pasa ahora `setUserAgentOverride(UserAgents.desktop(), UserAgents.desktopMetadata())` en lugar de la sobrecarga de un parámetro (que en Cefrium elimina las cabeceras `Sec-CH-UA*`).
3. **Selector nativo de archivos `<input type="file">`:** `PagePrompts.chooseFiles()` cancela solicitudes de pestañas en segundo plano y devuelve `false` para la pestaña activa (suprimiendo el auto-PiP inmediato), delegando al selector nativo de Chromium en `0.22.0` (`accept`, `capture`, cámara vía `CefriumFileProvider`, selección múltiple y carpetas). `MainActivity` ya reenviaba `onActivityResult` y `onRequestPermissionsResult` al motor.
4. **Zoom por pellizco nativo (`setPinchToZoomEnabled(true)`):** se mantiene activo sin cambios sobre la implementación nativa mejorada de `0.22.0`.

---

## 4. Problemas y limitaciones que todavía existen en Cefrium `0.22.0`

Cada punto siguiente fue verificado directamente en el código fuente del AAR y la especificación `cefrium-sdk.api` de Cefrium `0.22.0`. Sirven tanto como referencia honesta para el desarrollo de Nexo como base técnica para futuros informes en [`codeberg.org/cefrium/cef-android/issues`](https://codeberg.org/cefrium/cef-android/issues).

### 4.1 Puente JS y extensiones

1. **`QueryHandler` no notifica la cancelación de consultas (`onQueryCanceled`) ni ofrece canales persistentes (`MessagePort`):**
   - *Evidencia:* en C++ (`CefriumBridgeHandler::OnQueryCanceled`), el motor borra `query_id` del mapa nativo al navegar o destruir un marco, pero `CefriumBrowser.QueryHandler` en Java solo expone `onQuery(...)` sin callback de cancelación.
   - *Impacto en Nexo:* al aparcar callbacks asíncronos (`awaitEvents`) para evitar polling, un iframe destruido o recargado puede dejar referencias retenidas hasta la siguiente notificación del canal o `reset()`/`dispose()`. Nexo tuvo que añadir deduplicación por clave de marco y un tope estricto (`MAX_WAITERS = 32`).
   - *Mejora sugerida upstream:* exponer `default void onQueryCanceled(long queryId)` en `QueryHandler` (o un canal estilo `WebMessagePort` / `postWebMessage` con destino por marco).

2. **`evaluateJavaScript` no acepta un `frameId` de destino:**
   - *Evidencia:* `CefriumFrameHandler` sí reporta identificadores de subframes, pero `CefriumBrowser` solo ofrece `evaluateJavaScript(String)`, `evaluateJavaScript(String, ValueCallback)` (marco principal) y `evaluateJavaScriptInFocusedFrame(String)`.
   - *Impacto en Nexo:* Dark Reader no puede inyectarse bajo demanda en iframes de origen distinto ni el motor puede dirigirse a un iframe concreto sin pasar por broadcast de `PageChannel`.

3. **No existen mundos V8 aislados para `addDocumentStartJavaScript`:**
   - *Evidencia:* los scripts registrados se ejecutan en el mundo principal (`MAIN`) compartido con la página.
   - *Impacto en Nexo:* bloquea `world: "ISOLATED"` en `content_scripts` del runtime NXR y obliga a medidas defensivas extra (congelar `globalThis.cefriumQuery` en `document_start`, grants efímeros de 256 bits por `(sesión, extensión, origen, docGen)` y particionado de `NxrStorage` por origen).

### 4.2 Multimedia y Picture-in-Picture

4. **`CefriumMediaSession` es completamente opaca (sin API pública de observación):**
   - *Evidencia:* en `cefrium-sdk.api`, `public class com.cefrium.CefriumMediaSession` no expone ningún método ni listener público (ni título, artista, carátula, duración, posición, estado de reproducción ni acciones disponibles).
   - *Impacto en Nexo:* para mostrar notificación multimedia propia, controles en Smart PiP y decidir cuándo mantener audio en segundo plano, Nexo debe seguir inyectando `media-reporter.js` (que conserva un temporizador de respaldo cada 2 s).

5. **`onPause()` en PiP no permite excluir la suspensión al cerrar PiP cuando hay audio en segundo plano:**
   - *Evidencia:* en `CefriumBrowser.deferPauseUntilStopped()`, `onActivityStopped` ejecuta incondicionalmente `pauseNow()` (`setPageVisible(false); suspendMedia(); setAudioMuted(true)`).
   - *Impacto en Nexo:* si el usuario tiene activada la opción «Audio en segundo plano» y cierra la ventana PiP o apaga la pantalla, llamar `browser.onPause()` silencia y suspende el audio. Nexo debe saltarse `onHostPauseInPip()` cuando `BrowserPrefs.backgroundMedia` está activo, perdiendo en ese modo la recuperación automática `onActivityStarted` de Android 10 si la pantalla se apaga con PiP abierto.
   - *Mejora sugerida upstream:* un parámetro o ajuste en `CefriumBrowser` (p. ej. `setBackgroundMediaAllowed(boolean)` o `onPause(boolean allowBackgroundAudio)`) para que `onActivityStopped` oculte la página sin silenciar ni suspender medios cuando la app reproduce audio en segundo plano.

6. **HTML5 `video.requestPictureInPicture()` no tiene ventana flotante nativa:**
   - *Evidencia:* es un no-op seguro (no crashea), pero Chromium en Cefrium no provee controlador nativo de PiP por elemento `<video>`.
   - *Impacto en Nexo:* obliga a mantener el recorte nativo + aislamiento CSS (`pip.css`) en `PageBridge` para centrar el vídeo dentro de la ventana PiP de Android.

### 4.3 Red, cookies, archivos y descargas

7. **`CefriumResourceInterceptor` no puede redirigir ni modificar cabeceras HTTP en vuelo:**
   - *Evidencia:* `CefriumResourceInterceptor` solo permite devolver `boolean` en `shouldBlockRequest` o construir una respuesta sintética completa en `shouldInterceptRequest` (`CefriumContentResponse`). No existe hook de redirección ni mutación de cabeceras de petición/respuesta.
   - *Impacto en Nexo:* bloquea las acciones `redirect` y `modifyHeaders` de `declarativeNetRequest` (MV3) en NXR y la inyección/limpieza nativa de cabeceras (como GPC o cabeceras de rastreo) sin reimplementar el transporte HTTP en Java.

8. **`onCanSendCookie` y `onCanSaveCookie` no reciben la URL de primer nivel (top-level / site-for-cookies):**
   - *Evidencia:* las firmas son `onCanSendCookie(String url, String name, String value)` y `onCanSaveCookie(String url, String name, String value)`.
   - *Impacto en Nexo:* para decidir cookies de terceros (política «solo propias» / futura «equilibrada»), Nexo depende del `topHost` aproximado de la pestaña y carece de contexto exacto en iframes anidados y service workers.

9. **`CefriumFileChooserParams` omite `isCaptureEnabled()`:**
   - *Evidencia:* `CefriumFileChooserParams` solo expone `getMode()`, `getTitle()`, `getAcceptTypes()`, `createIntent()` y `parseResult(...)`.
   - *Impacto en Nexo:* cualquier implementación personalizada de `onShowFileChooser` no puede saber si la página pidió `<input type="file" capture>` (cámara/micrófono directo). En Nexo se resolvió delegando al selector nativo de Cefrium (`return false`), pero cualquier app que necesite un selector propio sigue bloqueada.

10. **`CefriumDownloadClient` / `DownloadItem` no expone método HTTP, cuerpo POST ni handoff de stream:**
    - *Evidencia:* `CefriumBrowser.DownloadItem` expone `getUrl()`, `getSuggestedName()`, `getMimeType()`, `getFullPath()`, progreso y control, pero no el método HTTP ni un flujo de entrada reutilizable antes de escribir en disco.
    - *Impacto en Nexo:* las descargas iniciadas por `POST` que se transfieren al gestor externo de Nexo no pueden re-ejecutar el `POST` original (solo `GET`, `blob:` y `data:` se transfieren de forma fiel).

### 4.4 Ciclo de vida, perfiles y rendimiento

11. **Un `CefriumBrowser` con superficie no puede revincularse a otra `Activity`:**
    - *Evidencia:* `CefriumBrowser.createWithSurface(activity, profile)` ata el navegador a una `Activity`; al recrearse la `Activity` (por ejemplo por cambio de configuración no manejado), hay que serializar estado de navegación, cerrar el navegador y crear uno nuevo, perdiendo estado vivo de DOM, JavaScript y reproducción multimedia.

12. **Proxy solo a nivel de perfil, borrado diferido de perfiles y ausencia de `preconnect`:**
    - *Evidencia:* `CefriumProfile.setProxy(...)` afecta a todo el perfil (no por pestaña); `CefriumProfile.delete(name)` devuelve `false` y aplaza el borrado hasta el siguiente arranque si el perfil se usó en la sesión actual (`sUsedThisRun`); y no existe API pública de `speculativeConnect` / preconexión DNS+TLS al escribir en la barra de direcciones.

13. **`CefResumeBridge` (pantalla negra al reanudar) — pendiente de verificación definitiva en hardware real:**
    - *Estado:* `vejeta` no pudo reproducir pantalla negra en `0.22.0` (issue `#84`). Nexo conserva `CefResumeBridge` (ya sin `setFocus(true)`) con telemetría `recoveries` y `watchdog` en `EngineDiagnostics`. Si en pruebas reales en ARM Android 10–15 el contador `watchdog` permanece en `0`, `CefResumeBridge` podrá retirarse por completo en un PR posterior. Si llegara a dispararse (`watchdog > 0`), el informe copiable de Ajustes › versión ya incluye `Cefrium.diagnostics(context)` listo para abrir un issue en Codeberg.

### 4.5 Higiene de API pública, plugin Gradle y R8

14. **Tipos internos de Chromium filtrados en la API pública del AAR:**
    - *Evidencia:* el propio archivo `cefrium-sdk.api` de `0.22.0` marca fugas públicas en `com.cefrium.CefAndroidProtocolHandler`, `CefContactsPickerDelegate`, `CefModalDialogPresenter`, `CefWindowAndroid` y `com.cefrium.payments.CefPaymentRequestFactory` (exponiendo `org.chromium.*`). Nexo no importa ninguna de ellas (prohibido por CI).

15. **`CefriumPlugin.groovy` genera `R.java` con campos `public static final int` incompatibles con *optimized resource shrinking* de AGP 9 / R8:**
    - *Evidencia:* aunque el comentario de `GenerateCefriumRTask` (`CefriumPlugin.groovy:381`) indica explícitamente *«against the consumer APK's (non-final) resource IDs»*, las líneas `432-434` generan `public static final int` / `public static final int[]` para los 18 paquetes de `CHROMIUM_R_PACKAGES`. Como `0.22.0` retiró la regla global `-keep class org.chromium.**`, AGP 9.5 (con `optimizedResourceShrinking=true` por defecto) emite **199.021 avisos (~83 MiB en el log del daemon)** con `Running optimized resource shrinking with final R class ids is not supported and can lead to missing resources and code necessary for program execution`.
    - *Mitigación temporal en Nexo:* se fijó `android.r8.optimizedResourceShrinking=false` en `gradle.properties` (AGP 9.5 avisa que esta propiedad desaparecerá en AGP 10.0), eliminando por completo los 199.021 avisos y el riesgo de eliminación de recursos en ejecución.
    - *Mejora sugerida upstream:* quitar `final` en `GenerateCefriumRTask` (`public static int` / `public static int[]`) antes de AGP 10.0 para que los `R.java` generados sean coherentes con `nonFinalResIds` y el *resource shrinking* optimizado de R8.

16. **Restricciones del plugin Gradle, toolchain y memoria de R8:**
    - *Evidencia:* el SDK distribuye bytecode Java 25 (exige JDK 25 y `compileSdk 37`), `CefriumPlugin.groovy` mantiene una lista fija `CHROMIUM_R_PACKAGES` de 18 paquetes `R` (añadiendo `com.google.cardboard.sdk` en Chromium 158) para evitar OOM de D8, y `cefrium-sdk-core-0.22.0.pom` declara `com.google.protobuf:protobuf-javalite:4.37.0-RC1` (versión candidata).
    - *Impacto en R8:* al retirar `-keep class org.chromium.**`, `minifyReleaseWithR8` analiza y optimiza todo el bytecode Java de Chromium (reduciendo el DEX final del APK release a solo **2 archivos DEX / 11,9 MB** sin comprimir) y emite cinco avisos `-identifiernamestring` sobre módulos opcionales ausentes (`StackUnwinderModuleContentsImpl`, `XrModuleProviderImpl`, `OnDemandModuleEntryPointsImpl`, `DevUiImpl`). Nexo subió `org.gradle.jvmargs` de `-Xmx3072m` a `-Xmx4608m` en `gradle.properties` para evitar presión de memoria al ejecutar R8 junto a `lintVital`.

---

## 5. Verificación realizada y pruebas pendientes en dispositivo físico

### Comprobaciones automatizadas en esta rama

| Comprobación | Resultado | Detalle |
| --- | --- | --- |
| **Firma GPG y SHA-256 de Cefrium `0.22.0`** | **OK** | `SHA256SUMS-0.22.0.asc` (`GOODSIG` / `VALIDSIG`, huella `2A840ED00DE669F4219B8B3302A79E3B82151695`) y sidecars `.sha256` de Maven verificados en `gradle/cefrium-0.22.0.sha256`. |
| **Política de seguridad sobre código fuente** | **OK** | Sin `org.chromium.*`, `org.jni_zero.*`, `GEN_JNI`, `addJavascriptInterface`, `.proceed()`, `RemoteDebuggingEnabled(true)` ni cargas `javascript:`. |
| **Regresiones JavaScript** (`node --test app/src/test/js/*.test.cjs`) | **269 / 269 OK** | Incluye pruebas específicas del protocolo `cefriumQuery` asíncrono sin `setInterval`, RPC diferido directo, iframes/anfitrión oculto y corte en `-403`. |
| **Pruebas JVM debug y release** (`:app:testDebugUnitTest`, `:app:testReleaseUnitTest`) | **351 / 351 OK** (ambas variantes) | 64 suites, 0 fallos, 0 errores, 0 omitidos (incluyendo `PageChannelTest`, `CefResumePolicyTest`, `CefriumStateTest`, `ExtensionHubStoreTest`). |
| **Regresiones UI nativa y accesibilidad (Robolectric)** (`-PnexoScreenshots`) | **16 / 16 OK** | `UiThemeRegressionTest` (8/8) y `SkillPracticeRegressionTest` (8/8). |
| **Compilación debug y release** (`:app:assembleDebug`, `:app:assembleRelease`) | **OK** | APKs release R8 generados: `arm64-v8a` (`120.747.329 B` / `115,2 MiB`), `armeabi-v7a` (`91.701.109 B` / `87,5 MiB`). |
| **Android Lint** (`:app:lintDebug`, `:app:lintRelease`) | **OK** | 0 errores en ambas variantes. |
| **Reflexión R8** (`tools/check_release_reflection.py`) | **OK** | `65 / 65` clases de arranque conservan `<init>()V` en ambos APKs release, incluida `com.cefrium.CefriumAppComponentFactory`. |
| **Identidad de APK** (`tools/check_apk_identity.py`) | **OK** | Paquete `com.nexo.browser`, componentes y `FileProvider` válidos en los 4 APKs (debug y release). |
| **Auditoría de manifiesto y DEX (APK release `arm64-v8a`)** | **OK** | `android:appComponentFactory="com.cefrium.CefriumAppComponentFactory"` presente, `CefriumFileProvider` presente, 2 servicios `android:nativeService=true` (Android 17+), **0 clases** de `com.google.android.gms`, `com.google.firebase` ni `com.google.mlkit` en los 2 archivos DEX (`11.937.108 B` sin comprimir). |
| **Prototipo independiente** (`cefrium-prototype`, `:app:assembleDebug`) | **OK** | Compilado limpiamente con Cefrium `0.22.0` y JDK 25. |

### Checklist pendiente en dispositivo físico ARM (Android 10+)

Estas comprobaciones requieren hardware Android real con `arm64-v8a` / `armeabi-v7a` y no pueden certificarse solo con pruebas JVM/Robolectric:

- [ ] **PiP (Issue `#84` #1):** reproducir vídeo, entrar en PiP con audio en segundo plano desactivado, verificar vídeo visible y fluido, cerrar la ventana PiP y comprobar que el audio/vídeo se pausa. En Android 10, apagar y encender pantalla durante PiP y confirmar que el vídeo se recupera. Repetir con «Audio en segundo plano» activado y confirmar que el sonido continúa al cerrar PiP.
- [ ] **Foco de `EditText` (Issue `#84` #2):** tocar la barra de direcciones o el buscador en página, abrir/cerrar un diálogo del sistema o volver desde otra app, y confirmar que el teclado y el foco siguen en el `EditText` nativo sin que la página los robe.
- [ ] **Aislamiento de procesos (Issue `#84` #3):** comprobar en Ajustes › versión que `Cefrium.diagnostics(context)` reporta `appComponentFactory: com.cefrium.CefriumAppComponentFactory`, `google libraries in the app: no`, y sin cierres anómalos en `recent process exits`.
- [ ] **`CefResumeBridge` (Issue `#84` #4):** completar 10+ ciclos de segundo plano, diálogos de permisos/archivos y pantalla bloqueada con vídeo activo; verificar en Ajustes › versión si `watchdog=0` para decidir la retirada definitiva de `CefResumeBridge` (o reportar a Codeberg si ocurriera algún caso).
- [ ] **Puente asíncrono sin polling (Issue `#84` #5):** probar PhantomShield (bloqueo cosmético, cambio de ajustes desde el panel, análisis IA con `ShieldAiHost`), modo nocturno (Dark Reader), subtítulos en vivo en iframes y mini vídeo PiP, verificando entrega instantánea y cero errores en `EngineDiagnostics`.
- [ ] **Selector de archivos nativo y modo escritorio:** probar `<input type="file">`, `multiple`, `accept="image/*"`, `capture="environment"` (cámara) y apertura de Chrome Web Store comprobando Client Hints de escritorio.
