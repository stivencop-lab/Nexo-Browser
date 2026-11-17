# Plan de trabajo — Nexo sobre Chromium (Cefrium)

Documento de traspaso para el siguiente agente. Léelo entero antes de tocar código.
Complementa a `docs/CHROMIUM-NATIVE-MIGRATION.md` (arquitectura y decisiones).

## 0. Estado en una línea

Nexo ya no usa Gecko ni WebView: compila y pasa todas las pruebas con Chromium 156 embebido
(Cefrium 0.21.0), pero **en el teléfono del usuario no carga ninguna página, ni siquiera el panel
de PhantomShield** (versión 0.31.0). La 0.31.1 corrige la causa más probable y añade diagnóstico;
falta confirmarlo en el dispositivo. **Eso es lo primero (§3).**

## 1. Contexto

| Ítem | Valor |
| --- | --- |
| Repositorio | `stivencop414-bot/NavegadorGecko` |
| PR abierto | #46 (borrador), rama `claude/brave-bardeen-ngyh23` → `main` |
| Motor | Cefrium 0.21.0 (`com.cefrium:cefrium-sdk-core` + `cefrium-native-codecs-{arm64-v8a,armeabi-v7a}`), Chromium 156.0.8078.96, LGPL-3.0 |
| Versión app | 0.31.1 (versionCode 63), paquete `com.nexo.browser` |
| Toolchain | JDK 25 (el AAR es bytecode Java 25), Gradle 9.8.2, AGP 9.5.1, Kotlin 2.4.10, compileSdk 37.2, build-tools 37.0.0, minSdk 29, targetSdk 35 |
| ABIs | arm64-v8a y armeabi-v7a (x86/x86_64 se excluyen del APK) |
| Documentación de Cefrium | `https://cefrium.com/llms.txt` (resumen), Javadoc en `https://cefrium.com/docs/`, fuentes: `cefrium-sdk-core-0.21.0-sources.jar` en `maven.cefrium.com` |

### Reglas que no se negocian

1. **Solo API pública `com.cefrium.*`.** Nada de `org.chromium.*`, `org.jni_zero.*` ni `GEN_JNI` (el CI lo rechaza).
2. **La clave de firma nunca entra al repositorio** (ni `.jks`, ni contraseñas, ni base64). El job `release`
   del CI firma con los secretos `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
   `ANDROID_KEY_PASSWORD`. Certificado esperado: `config/android-signing-certificate.sha256`
   (`a5b4232c…694f`). Si necesitas firmar localmente, pide el respaldo al usuario, úsalo fuera del repo y bórralo.
3. Los errores TLS siempre se cancelan; ningún puente JS sin control de origen; nada de `loadUrl("javascript:`.
4. No crear PR ni hacer push a ramas distintas de la asignada sin permiso del usuario.
5. Commits en español o inglés, sin identificadores de modelo.

## 2. Lo que ya está hecho

### Commits de la rama (más antiguo primero)

| Commit | Qué |
| --- | --- |
| `119fca6`…`7cb1ed6` | Prototipo aislado `cefrium-prototype/` (adaptadores iniciales, de otra sesión) |
| `9cc4c8f` | Adaptadores del prototipo alineados con el SDK real + Gradle wrapper + smoke test con PASS/FAIL en logcat |
| `d80d61a` | **Migración**: un solo motor Cefrium, fuera flavors `gecko`/`chromium`, motor en `engine/cefrium/`, PhantomShield sobre el interceptor de Cefrium, puente `cefriumQuery`, CI a JDK 25 |
| `dd0fa65` | APK solo ARM, `check_release_reflection.py` acepta servicios nativos, pruebas UI en API 29 |
| `6fd77d7` | Correcciones de PhantomShield/puente/medios, descargas `blob:`, 0.31.0 |
| (siguiente) | 0.31.1: Nexo solo se inicia en su proceso principal, diagnóstico `NexoEngine`, este plan |

### Mapa del código

```
app/src/main/java/com/nexo/browser/
├── engine/
│   ├── BrowserEngine.kt / EngineSession.kt   # interfaz neutral (no tocar sin motivo)
│   ├── Engines.kt                            # Engines.current = CefriumEngine
│   └── cefrium/
│       ├── CefriumEngine.kt        # init, DoH, depuración, borrado, Activity registrada, reenvío de resultados
│       ├── CefriumEngineSession.kt # una pestaña = un CefriumBrowser (creación diferida, recreación si muere la Activity)
│       ├── CefriumPageView.kt      # EngineView: cuelga la superficie del navegador activo
│       ├── CefriumProfiles.kt      # normal / incógnito / Burner (+ proxy de Burner)
│       ├── CefriumState.kt         # historial persistente (serializeNavigationState)
│       ├── CefriumDownloads.kt     # descargas con OkHttp + cookies del perfil; blob:/data: vía Chromium
│       ├── ImportedCookies.kt / UpgradeImport.kt  # cookies de la versión anterior al actualizar
├── shield/
│   ├── PhantomShield.kt   # reglas, interceptor, puente de páginas, panel, política de cookies
│   ├── PageChannel.kt     # canal {m,s} sobre cefriumQuery (respuesta inmediata + cola de eventos)
│   ├── NetworkEngine.kt / FilterLists.kt / CosmeticEngine.kt / ShieldPolicy.kt / ShieldStore.kt
├── PhantomShieldPanel.kt  # popup.html de la extensión en un CefriumBrowser dentro de un Dialog
├── PagePrompts.kt / SitePermissions.kt / TabRouting.kt / ExtensionManager.kt / TranslatorManager.kt
app/src/main/assets/nexo/
├── cefrium-port.js   # crea __nexoShield sobre window.cefriumQuery
├── phantom-shim.js   # browser.* mínimo para los scripts de PhantomShield
├── bridge/page-bridge.js, pip.css, darkreader.js  # puente de páginas (mini video, subtítulos, modo nocturno)
├── bridge-shim.js    # browser.runtime para page-bridge.js
└── media-reporter.js # medios con sonido (notificación, segundo plano)
```

### Comportamientos que hay que conocer

- **Creación diferida**: `CefriumBrowser.createWithSurface(activity, profile)` exige una Activity. La sesión crea
  su navegador en `ensureBrowser()` cuando `CefriumEngine.activity()` existe (la registra `createView`/`attach`).
  Si la Activity se destruye (p. ej. cambio de tema → `recreate()`), el navegador se rehace con su historial.
- **Puente**: `cefriumQuery` solo responde de forma síncrona y admite un manejador por navegador. Por eso
  `PageChannel`: la página manda `{m: mensaje, s: último evento}` y recibe `{reply, seq, events}`. Nexo despierta
  al marco principal con `evaluateJavaScript("window.__nexoWake&&window.__nexoWake()")`; los marcos consultan
  cada 2 s (principal) / 4 s (internos) como respaldo.
- **Interceptor**: `shouldBlockRequest` decide en el hilo de red; los scripts sustitutos y la página de bloqueo de
  popups se responden en `shouldInterceptRequest`. El filtro por cookie solo se instala con "propias"/"ninguna".
- **Medios**: Cefrium no da eventos de reproducción sin activar su MediaSession (que pinta su propia
  notificación). El puente informa `media_state` (elementos audibles + `navigator.mediaSession`) y la sesión
  emite `EngineObserver.onMedia`; play/pause usan `resumeMedia()`/`suspendMedia()`.
- **`onCreateWindow`**: devolver `false` hace que Cefrium cargue la URL en la pestaña actual. Nexo siempre
  devuelve `true` y cierra la ventana si la bloquea.
- **Cleartext**: `usesCleartextTraffic="true"` porque Chromium respeta la política de Android (si no, ningún `http://`).

### Pruebas que existen y pasan

```bash
node --test app/src/test/js/*.test.cjs                       # 215 (incluye cefrium-port.test.cjs)
gradle :app:testDebugUnitTest :app:testReleaseUnitTest       # 187 JVM (PageChannelTest, CefriumStateTest, TabRoutingTest…)
gradle :app:testDebugUnitTest -PnexoScreenshots --tests '*UiThemeRegressionTest' --tests '*SkillPracticeRegressionTest'  # 17
gradle :app:assembleRelease :app:lintRelease
python3 tools/check_release_reflection.py --build-tools "$ANDROID_HOME/build-tools/37.0.0" app/build/outputs/apk/release/*.apk
python3 tools/check_apk_identity.py --build-tools "$ANDROID_HOME/build-tools/37.0.0" --package com.nexo.browser app/build/outputs/apk/release/*.apk
```

Nunca se ha ejecutado en un dispositivo ni emulador: el entorno anterior era x86_64 sin KVM.

## 3. P0 — No carga ninguna página (bloqueante)

**Síntoma (0.31.0, release firmado, instalado por el usuario):** ninguna página carga; el panel de PhantomShield
tampoco. El panel no usa red ni el interceptor (sirve `popup.html` con `registerContentServer`), así que el fallo
es **de arranque del motor o de dibujado**, no de PhantomShield ni de la red.

**Cambio ya hecho en 0.31.1 (hipótesis principal):** Chromium dibuja en procesos aislados
(`com.cefrium.ChildServices$Sandboxed*`, `isolatedProcess=true`) y Android crea `NexoApplication` también ahí.
Nexo leía archivos/preferencias y registraba su gestor de cierres en esos procesos. Ahora
`NexoApplication` retorna enseguida si `Application.getProcessName() != packageName`.

### Procedimiento (en este orden; detente cuando encuentres la causa)

0. **Sin adb** (0.31.2+): Ajustes › tocar la línea de la versión → *Diagnóstico del motor* → **Copiar**. Muestra si
   Cefrium se inició, cuántos navegadores y procesos de página hay, si la pestaña activa tiene navegador y puente,
   el estado de PhantomShield y de los subtítulos, y los últimos eventos (creación de navegadores, errores de carga
   con su código, procesos que terminan, fallos del panel).
1. **Recoger el registro** con la 0.31.1 o posterior instalada:
   ```bash
   adb logcat -c
   adb shell am start -n com.nexo.browser/.MainActivity
   adb logcat -v time -s NexoEngine CefriumBrowser chromium cr_ChildProcessConn cr_LibraryLoader AndroidRuntime ActivityManager DEBUG
   ```
   Buscar: `NexoEngine: Cefrium … iniciado=false` o excepción en `Cefrium.initialize`; `createWithSurface falló`;
   `el proceso de la página terminó: CRASHED`; `FATAL EXCEPTION` en un proceso `:sandboxed_process*` o
   `:privileged_process*`; `UnsatisfiedLinkError`; `ClassNotFoundException`/`NoSuchMethodError` (R8).
   También `adb shell dumpsys activity processes | grep com.nexo.browser` para ver si los procesos hijos viven.
2. **Si hay `ClassNotFound`/`NoSuchMethod`/`NoSuchField` o crash solo en release → R8.** Compilar el release con
   `isMinifyEnabled = false` (temporal) o instalar el debug (`gradle :app:assembleDebug`, firma debug: hay que
   desinstalar la versión firmada antes). Si el debug funciona, añadir reglas `-keep` en `app/proguard-rules.pro`
   para lo que falte y verificar con el mapping (`app/build/outputs/mapping/release/mapping.txt`).
3. **Separar Nexo de Cefrium con el prototipo** (`cefrium-prototype/`, solo arm64):
   ```bash
   cd cefrium-prototype && ./gradlew :app:assembleDebug --no-daemon
   adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
   adb shell am start -n com.nexo.cefriumprototype/.MainActivity
   adb logcat -s NexoCefrium CefriumBrowser
   ```
   Debe mostrar example.com y líneas `PASS`. Si **el prototipo tampoco** pinta → problema del dispositivo o de
   Cefrium 0.21.0 (revisar `https://cefrium.com/releases/` "known issues", probar otro teléfono/versión de Android).
   Si el prototipo funciona → el fallo está en la integración de Nexo (pasos 4–6).
4. **Dibujado**: comprobar que `CefriumPageView` contiene la superficie (`adb shell dumpsys activity top` debe
   listar `CefriumSurfaceContainer` → `ContentViewRenderView` con tamaño > 0 dentro de `pageView`). Si no está:
   revisar el orden `TabManager` → `CefriumEngine.register(activity)` → `ensureBrowser()` → `attachBrowser()`.
   Probar a llamar `browser.onResume()` tras `attachBrowser` y en `MainActivity.onResume`.
5. **Panel en Dialog**: si las pestañas cargan pero el panel no, mover el navegador del panel a una vista sobre
   la Activity (no un `Dialog`): la superficie de Chromium está ligada a la ventana de la Activity.
6. **Interceptor**: si cargan con PhantomShield apagado (menú del escudo) y no encendido, sospechar del
   `shouldInterceptRequest` (todas las peticiones esperan esa respuesta). Prueba: devolver siempre `null` sin
   sobrescribir el método y ver si cambia.
7. **Actualización desde Gecko**: probar con instalación limpia (desinstalar) frente a actualización. Si solo falla
   al actualizar, revisar `UpgradeImport.runOnce` (miles de `setCookie` en el hilo principal) y el estado
   de pestañas heredado.

**Terminado cuando:** en un teléfono arm64 (Android 10+) y uno armeabi-v7a, tanto con instalación limpia como con
actualización desde la 0.30.x (Gecko), se cargan https://example.com, una página `http://`, el panel de
PhantomShield, y el registro no muestra cierres. Añadir lo encontrado a este documento.

## 4. Fase 1 — Validación en dispositivo (después de P0)

Lista mínima, con logcat abierto (`NexoEngine`, `CefriumBrowser`):

| Área | Prueba | Esperado |
| --- | --- | --- |
| Navegación | escribir URL, atrás/adelante, recargar, cerrar y reabrir la app | historial atrás/adelante restaurado |
| Privada | abrir sitio con login, cerrar todas las privadas | sin cookies al reabrir |
| Burner | Burner con ruta SOCKS (Orbot 127.0.0.1:9050) | carga por el proxy; sin Orbot, no carga |
| PhantomShield | sitio con anuncios, insignia, panel, pausar sitio, actualizar listas | contador sube; panel abre y guarda |
| Descargas | PDF normal, archivo `blob:` (p. ej. exportar en una web app) | ambos en Descargas de Nexo |
| Medios | YouTube: mini video, notificación, pausa desde la notificación, segundo plano | controles funcionan con la app en segundo plano |
| Subtítulos | video con pista VTT y con subtítulos DOM | sin reinicios cada segundo |
| Permisos | cámara/micro/ubicación (permission.site) | diálogo con dominio real |
| Pantalla completa | video HTML5 | barras ocultas, salir con atrás |
| Popups | `target=_blank`, inicio de sesión OAuth | `window.opener` funciona; popunders bloqueados |
| Tema | cambiar tema claro/oscuro | pestañas se rehacen sin cierre |

## 5. Fase 2 — Funciones pendientes respecto a Gecko

Por prioridad. Cada una: código + pruebas JVM/JS + nota en `docs/CHROMIUM-NATIVE-MIGRATION.md`.

1. **Proxy en pestañas normales y privadas.** El proxy de Cefrium es del perfil. Diseño propuesto: un perfil con
   nombre por ruta (`nexo_route_<hash>`), efímero como Burner (borrado al arrancar), y `CefriumProfiles.forSession`
   que lo elija cuando la pestaña tenga `networkProfile`. Hay que pasar la ruta a `SessionParams` (hoy no la lleva) o
   decidir el perfil en `TabRouting.prepare` antes de crear el navegador. Avisar al usuario de que esas pestañas no
   comparten sesión con las normales.
2. **Modo nocturno en marcos internos.** Hecho para el marco principal (0.31.2: `PageBridge.loadDarkReader`).
   Los iframes responden `false`: valorar inyectar Dark Reader con `addDocumentStartJavaScript` solo mientras el
   modo nocturno esté activo.
3. **Traducción de páginas** (`TranslatorManager.translateActive`): extraer texto con un script, traducir con los
   proveedores ya configurados para subtítulos (Google/DeepL/IA en `BrowserPrefs`) y reinsertar. Sin enviar URLs.
4. **Simulaciones de red** (antes `nexo_network`, retirada): retardo, ubicación simulada y variación de Canvas/WebGL por Burner. Ubicación y
   Canvas/WebGL: script en `addDocumentStartJavaScript` por pestaña con la semilla `burnerSeed`; retardo: no hay
   API, valorar si se elimina de la interfaz.
5. **Política de cookies "equilibrada"**: bloquear cookies de terceros cuando el host está en las listas de
   rastreo (`rules.network` con tipo `xmlhttprequest`), con caché por host para no evaluar reglas en cada cookie.
6. ~~Puente completo~~ **Hecho en 0.31.2**: `bridge/page-bridge.js` con `bridge-shim.js` y `PageBridge.kt`.
   Validar en dispositivo: subtítulos por audio (clave de Deepgram), pistas VTT, YouTube, mini video.
7. **IA y parcheador anti-bloqueo de PhantomShield**: hoy responden `AI_UNAVAILABLE`.
8. **Extensiones de terceros (runtime NXR)**: `ExtensionsActivity` ya evalúa compatibilidad de CRX/XPI/ZIP.

## 6. Fase 3 — Publicación

1. Confirmar con el usuario que los cuatro secretos `ANDROID_*` existen en GitHub (Settings → Secrets → Actions).
2. Quitar el borrador del PR #46, fusionar a `main`, lanzar *Actions → Nexo Secure CI and Release → mode=release*.
3. Actualizar la sección "APK disponible" del README con la nueva versión y hashes.
4. LGPL-3.0 de Cefrium: incluir aviso de licencia y enlace al código fuente (Codeberg `cefrium/cef-android`) en
   Ajustes › Acerca de; el SBOM/NOTICE oficiales están referenciados en `gradle/cefrium-0.21.0.sha256`.
5. Comunicar: Android 8/9 dejan de recibir actualizaciones (minSdk 29); se pierden la tienda AMO y la traducción
   local de Gecko.

## 7. Entorno para compilar (Linux)

```bash
# JDK 25 (Temurin), Gradle 9.8.2, SDK con platforms;android-37.2 y build-tools;37.0.0
export JAVA_HOME=/ruta/jdk-25 ANDROID_HOME=/ruta/android-sdk
export PATH="$JAVA_HOME/bin:/ruta/gradle-9.8.2/bin:$PATH"
gradle :app:assembleRelease --no-daemon
```

Maven Central a veces responde 429 (demasiadas peticiones): reintentar tras un minuto. Las dependencias de
Cefrium vienen de `https://maven.cefrium.com` (filtrado por `includeGroup("com.cefrium")` en `settings.gradle.kts`).
