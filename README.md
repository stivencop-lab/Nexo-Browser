# Nexo Browser

> **Versión en Gradle:** `0.34.0` · `versionCode 68` · Paquete `com.nexo.browser`
>
> **Última release publicada:** [v0.34.0](https://github.com/stivencop414-bot/NavegadorGecko/releases/tag/v0.34.0) · Motor **Chromium 156** embebido mediante [Cefrium](https://cefrium.com) `0.21.0` (LGPL-3.0) · Android 10+ (`minSdk 29`, `targetSdk 35`, `compileSdk 37.2`).

<img src="docs/brand/nexo.svg" alt="Logo de Nexo Browser" width="112" />

Nexo Browser es un navegador Android nativo en **Kotlin/XML** con Chromium embebido (Cefrium), bloqueo de anuncios y rastreadores integrado (**PhantomShield**), reproductor multimedia avanzado (**Nexo Media Engine**), modo oscuro temprano (**Nexo Dark Engine**), runtime progresivo de extensiones (**NXR Extensions Runtime**), asistente de inteligencia artificial opcional (**Nexo AI**), subtítulos en vivo y traducción completa de páginas en el DOM.

Nexo ya no depende de GeckoView ni de Android System WebView (ver [migración a Chromium nativo](docs/CHROMIUM-NATIVE-MIGRATION.md)). Desde `0.25.0`, el identificador Android es `com.nexo.browser` (ver [cambio de identidad](docs/IDENTIDAD-NEXO.md)).

---

## Arquitectura actual

| Capa | Componentes reales en el repositorio |
| --- | --- |
| **Interfaz nativa (Kotlin/XML)** | `MainActivity`, `NexoSheets`, `NexoUi`, `ThemeManager`, `TabSwitcher`, `TabTools`, `SettingsActivity`, `ExtensionsActivity`, `HistoryActivity`, `DownloadsActivity` |
| **Motor Chromium (Cefrium público)** | `app/src/main/java/com/nexo/browser/engine/cefrium/` (`CefriumEngine`, `CefriumEngineSession`, `CefriumPageView`, `EngineDiagnostics`) usando exclusivamente la API pública `com.cefrium.*` |
| **Puente página ↔ nativo** | `PageChannel` + `PageBridge` + `assets/nexo/cefrium-port.js` (`cefriumQuery` congelado en `document_start`) + `page-bridge.js`; extracción de Nexo AI y Traductor vía retorno directo de `evaluateJavaScript` |
| **Privacidad y bloqueo** | `app/src/main/java/com/nexo/browser/shield/` (`PhantomShield` con caché LRU de red y dedup IA, `ShieldAiHost`, `NetworkBlocker`, `BurnerMailbox`) + `assets/extensions/phantomshield/` |
| **Nexo Media Engine** | `app/src/main/java/com/nexo/browser/media/` (`NexoMediaEngine`, `MediaSitePrefs`) + `BrowserMediaController` + `CaptionsUi` + `LiveCaptions` + `page-bridge.js` |
| **Nexo Dark Engine** | `app/src/main/java/com/nexo/browser/dark/DarkEngine.kt` + `assets/nexo/dark-engine.js` + `NightMode` |
| **NXR Extensions Runtime** | `app/src/main/java/com/nexo/browser/extensions/nxr/` (`NxrRuntime`, `NxrPackageInstaller`, `NxrPermissions`, `NxrStorage`) + `assets/nexo/nxr-runtime-shim.js` + `ExtensionManager` |
| **Nexo AI y Traductor** | `app/src/main/java/com/nexo/browser/ai/` (`NexoAiConfig`, `NexoAiClient`, `NexoAiPageAgent`, `NexoAiSheet`) + `assets/nexo/ai-page-agent.js` + `TranslatorManager` + `assets/nexo/page-translator.js` |

---

## Subsistemas principales

### 1. Nexo Media Engine (`com.nexo.browser.media`)
Reproductor multimedia mejorado sobre el elemento HTML5 `<video>` / `<audio>` sin romper la reproducción nativa ni alterar `video.currentTime` durante la captura de subtítulos:
- **Control de velocidad** (`0.25x` a `3.0x`) y **salto rápido configurable** (`5 s`, `10 s`, `15 s`, `30 s`) por sitio.
- **Gestos en pantalla completa (`FullscreenOverlay`)**: arrastre vertical en los bordes para brillo y volumen, más bloqueo táctil de controles.
- **Subtítulos avanzados**:
  - Lectura nativa de pistas WebVTT/HLS, subtítulos dibujados por el reproductor (YouTube, Netflix, Prime Video, video.js, JW Player, Shaka, Plyr, Bitmovin) o archivos `.vtt`/`.srt` sin clave ni micrófono.
  - Si no hay pista de texto, opción explícita entre subtítulos instantáneos de Android o transcripción digital interna con Deepgram (Opus ~24 kbps), con telemetría ligera de latencia captura→transcripción en diagnóstico.
  - **Desfase de sincronización (offset)** en milisegundos aplicado solo al calendario de subtítulos, sin reescribir `video.currentTime`.
  - **Tipografía y escala configurables** (pequeño, normal, grande, extra grande).
- **Mini reproductor (Picture-in-Picture)** y reproducción en segundo plano configurable, con preferencias persistentes por dominio (`MediaSitePrefs`).

### 2. Nexo Dark Engine (`com.nexo.browser.dark`)
Modo oscuro web optimizado desde el inicio de carga para reducir destellos blancos y preservar contenido multimedia:
- **Modos globales**: **Auto** (sigue el tema de Nexo y del sistema), **Claro**, **Oscuro** (`#0C1222`) y **OLED** (`#000000`).
- **Estrategias de adaptación**: **Híbrido** (detección de oscuro nativo + CSS ligero seguro + Dark Reader opcional), **CSS ligero** y **Dark Reader dinámico**.
- **Inicio temprano sin autointerferencia**: aplica `setBackgroundColor` sobre la superficie de Chromium antes del primer pintado, inyecta lienzo oscuro en `document_start` y suspende temporalmente su propio estilo al medir el color real del sitio para evitar falsos positivos de «oscuro nativo».
- **Protección de elementos**: detecta páginas ya oscuras y evita invertir imágenes, vídeos, `<canvas>`, `<svg>`, PDF, formularios y editores de código/texto.
- **Control por sitio**: activación/exclusión por dominio desde la hoja de Herramientas (`TabTools`) sin recargar la pestaña.

### 3. NXR Extensions Runtime (`com.nexo.browser.extensions.nxr`)
Runtime progresivo y acotado para extensiones de terceros sobre Chromium embebido:
- **Instalador transaccional y seguro (`.crx`, `.xpi`, `.zip`)**: valida encabezado CRX2/CRX3 o ZIP, bloquea *Zip Slip*, rechaza entradas duplicadas y bombas ZIP (ratio de compresión), impone límites de archivos/tamaño, instala mediante intercambio atómico con *rollback* preservando almacenamiento y muestra vista previa de permisos antes de instalar.
- **Aislamiento endurecido**: `storage.local` / `storage.sync` particionado por extensión y por origen (`origins/<hash>.json`, cuota agregada 512 KB) en `NxrStorage`, permisos evaluados en `NxrPermissions`, grants efímeros de 256 bits vinculados a `(sesión, extensión, origen, docGen)` con revocación inmediata, `cefriumQuery` congelado en `document_start`, *rate limiting* (30 llamadas / 10 s) y tope de 32 KB por mensaje.
- **Ciclo de vida y diagnóstico**: inyección ordenada de `content_scripts` y CSS según `run_at` (`document_start`, `document_end`, `document_idle`) y `matches`, gestión de acciones (`action`/`browser_action`) mediante eventos con nonce (sin `window.__nxrDispatch`), activación/desactivación y diagnóstico por extensión en `ExtensionsActivity`.
- **Soporte progresivo honesto**: ver la [matriz de soporte real](#matriz-de-soporte-real) y la [auditoría V2](docs/AUDITORIA-ESTABILIDAD-OPTIMIZACION-V2.md) para las APIs WebExtension y límites de aislamiento V8 de Cefrium `0.21.0`.

### 4. Nexo AI (`com.nexo.browser.ai`) y traducción completa de páginas (`TranslatorManager`)
Asistente integrado y traductor de páginas bajo control total del usuario:
- **Opt-in y privacidad estricta**: Nexo AI viene **desactivado por defecto** y requiere consentimiento explícito antes de realizar cualquier solicitud.
- **Claves en Android Keystore**: las claves API se cifran únicamente mediante `SecretStore` (nunca se escriben en texto plano ni en registros). Los endpoints remotos exigen `HTTPS` (solo se permite `http://` en `localhost`/`127.0.0.1`/`::1`).
- **Extracción segura sin token por `evaluateJavaScript` (`ai-page-agent.js`)**: excluye contraseñas, campos ocultos, `file`, `cc-*`/tarjetas, OTP/CSRF, cookies, `localStorage`, parámetros sensibles en URL y subárboles ocultos (`display:none`, `opacity:0`, `aria-hidden`, `hidden`, `sr-only`, *off-screen*); redacta secretos inline (`Bearer`, `sk-*`, `AIza*`, JWT); bloqueado en pestañas privadas por defecto.
- **Defensa contra *prompt injection* y acciones validadas**: delimita el contenido web con `<UNTRUSTED_PAGE_CONTEXT>`, adapta automáticamente parámetros para modelos de razonamiento (`o1`/`o3`/`o4`/`gpt-5`/`reasoner`), valida cualquier bloque ```` ```nexo-actions ```` bajo esquema estricto y **siempre exige confirmación explícita (`AlertDialog`)** para `open_link` y `fill_draft` (nunca envía formularios automáticamente).
- **Comparación real de pestañas**: el alcance «Comparar pestañas» extrae contexto saneado y acotado de hasta 4 pestañas vivas no privadas además de metadatos del resto.
- **Control de ejecución y honestidad**: guarda `requestGen` contra respuestas tardías, cancelación inmediata (`CancelHandle`), presupuesto configurable de contexto (4–16 KB), timeout configurable, registro local saneado y conteo de tokens mostrado **solo cuando la API lo devuelve**.
- **Traducción completa de páginas (`TranslatorManager` + `page-translator.js`)**: extracción directa vía `evaluateJavaScript` aislada por `docGen`, cancelación inmediata de llamadas HTTP al navegar/restaurar, aplicación progresiva por lotes (hasta 400 bloques con aviso honesto si hay omisiones), protección de nodos desconectados en SPAs y **restauración instantánea al idioma original sin recargar**.

---

## Otras funciones integradas
- **Omnibox y buscadores**: Google, DuckDuckGo, Bing, Brave Search y Startpage.
- **Pestañas, grupos y Burner**: sesiones LRU, pestañas privadas, grupos por color, deshacer cierre, gestos en barra inferior, restauración tolerante ante entradas dañadas y modo **Burner** (perfil aislado de Chromium + correo temporal mail.tm + limpieza automática).
- **PhantomShield 9.2.1**: bloqueo de red con listas EasyList/uBlock + caché LRU acotada de decisiones, reglas cosméticas, eliminación de anuncios de YouTube, bloqueo de popunders (`$popup`), IA opcional con deduplicación por página, memoria local por dominio y modelos de respaldo.
- **Panel de diagnóstico saneado**: accesible en *Ajustes › toca la versión* con contadores de carga/renderer, memoria del proceso app, consulta bajo demanda de nodos DOM/Navigation Timing, estado de NXR/Traductor/Subtítulos/PhantomShield y exportación «Copiar / Compartir» redactada.
- **Descargas aceleradas**: segmentación multi-conexión (solo en servidores con rangos, nunca en proxy ni Burner), reanudación tras cortes y limpieza de archivos incompletos.
- **Herramientas de página**: modo escritorio, captura PNG, exportar PDF, fijar en inicio, buscar en página, compartir, diálogos web nativos y permisos con confirmación mostrando el dominio real.

---

## Matriz de soporte real

| Área / Capacidad | Estado real | Notas técnicas |
| --- | --- | --- |
| Motor Chromium 156 (Cefrium `0.21.0`), ciclo de vida endurecido (`isClosed`, `docGen`), pestañas, Burner, descargas | **Implementado y verificado en CI** | API pública `com.cefrium.*`; tests JVM y política de seguridad en CI |
| Estabilidad de sesiones, recuperación de renderers, gestos de dock y puente `PageChannel` | **Implementado y verificado** | Pruebas JS (243 tests, incluida suite adversarial) + pruebas JVM; validación física vía protocolo ADB |
| **Nexo Media Engine** (velocidad, salto, gestos fullscreen, offset CC, telemetría de latencia, prefs por sitio) | **Implementado y probado (JVM + JS)** | Validación de sincronía A/V real y gestos táctiles recomendada en dispositivo físico |
| **Nexo Dark Engine** (Auto/Claro/Oscuro/OLED, híbrido sin autointerferencia, excepciones por sitio) | **Implementado y probado (JVM + JS)** | La ausencia de destello blanco en primer fotograma requiere verificación visual en hardware real |
| **NXR Extensions Runtime** (instalador transaccional, grants efímeros, storage por origen, permisos, rate limit) | **Parcialmente soportado (progresivo)** | Soporta `content_scripts`, CSS, `storage` por origen, mensajes y acciones; **no dispone** de mundos V8 aislados, MV3 `service_worker` persistente, `declarativeNetRequest` completo ni `chrome.tabs`/`cookies` globales debido a límites de Cefrium `0.21.0` |
| **Nexo AI** (proveedores OpenAI/Claude/razonamiento, extracción sin token, anti-prompt-injection, acciones validadas) | **Implementado y probado (JVM + JS)** | Requiere clave del usuario o endpoint local (`localhost`); sin telemetría ni clave embebida |
| **Traducción completa de páginas** (`TranslatorManager` + `page-translator.js`, lotes progresivos + `docGen`) | **Implementado y probado (JVM + JS)** | Traducción en la nube vía Google/DeepL/IA; sustituye a `TranslationsController` de GeckoView |
| Captura de audio de vídeo protegido con DRM/EME | **No soportado por el motor/SO** | Android y Chromium bloquean la captura digital de flujos protegidos por DRM; se muestra aviso claro sin romper reproducción |

---

## APK disponible

La última release firmada publicada desde `main` es **Nexo Browser v0.34.0** (`versionCode 68`), paquete `com.nexo.browser`:

- [Descargar APK `arm64-v8a` (recomendado)](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.34.0/Nexo-v0.34.0-arm64-v8a.apk)
- [Descargar APK `armeabi-v7a` (32 bits)](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.34.0/Nexo-v0.34.0-armeabi-v7a.apk)
- [Descargar `SHA256SUMS.txt`](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.34.0/SHA256SUMS.txt)
- [Ver release v0.34.0](https://github.com/stivencop414-bot/NavegadorGecko/releases/tag/v0.34.0)

Hashes SHA-256 verificados en la release `v0.34.0`:
- `Nexo-v0.34.0-arm64-v8a.apk`: `2a3fb15d0d0e7d98089ebe545123e619a2062f1de312164043f1c7284d0385ad`
- `Nexo-v0.34.0-armeabi-v7a.apk`: `28921cea27f8157b493655ffbcbda170f079f01a0289ca921838ba2b9b1f56a5`

---

## Modelo de seguridad y privacidad

- **Política de motor y puentes**: prohibido en CI el uso de `org.chromium.*`, `org.jni_zero.*`, `GEN_JNI`, `addJavascriptInterface`, `loadUrl("javascript:")`, `setRemoteDebuggingEnabled(true)` y `.proceed()` en errores TLS.
- **URLs**: centralizadas en `SafetyRules.kt` (`UrlSafety`). Se bloquean `javascript:`, `data:`, `file:`, `content:` e `intent:` directos; intents externos solo hacia actividades `BROWSABLE` sin permisos heredados.
- **Secretos y claves**: todas las claves (Deepgram, DeepL, PhantomShield IA, Nexo AI) se cifran con Android Keystore (`SecretStore`), nunca viajan a las páginas web y se redactan automáticamente en registros locales y diagnósticos exportados.
- **Extensiones NXR**: instalador transaccional contra *Zip Slip*, duplicados y bombas ZIP; `cefriumQuery` congelado en `document_start`; almacenamiento particionado por origen (`origins/<hash>.json`); grants efímeros de 256 bits por `(sesión, extensión, origen, docGen)` y *rate limiting*.
- **Privacidad de navegación**: limpieza de parámetros de rastreo y secretos en historial, pestañas privadas excluidas de historial/miniaturas/Nexo AI/diagnóstico por defecto, y notificaciones multimedia sin exponer títulos en pantalla de bloqueo.

---

## Desarrollo, compilación y pruebas

Requisitos: **JDK 25** (Cefrium usa bytecode Java 25), **Gradle 9.8.2**, **Node.js 20+** y Android SDK con `platforms;android-37.2` y `build-tools;37.0.0`. Las dependencias de Cefrium provienen de `https://maven.cefrium.com` (hashes en `gradle/cefrium-0.21.0.sha256`).

```bash
# Pruebas de regresión y suite adversarial JS (PhantomShield, puente, Media, Dark, NXR, Nexo AI, Traductor)
node --test app/src/test/js/*.test.cjs

# Microbenchmarks reproducibles en Node.js (entorno host, no dispositivo Android)
node app/src/test/js/benchmarks/run-benchmarks.cjs

# Pruebas unitarias JVM (debug y release)
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest

# Pruebas Robolectric de interfaz nativa y accesibilidad
./gradlew :app:testDebugUnitTest -PnexoScreenshots \
  --tests '*UiThemeRegressionTest' --tests '*SkillPracticeRegressionTest'

# Compilación y lint
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease
```

---

## CI y releases

El workflow [`.github/workflows/main.yml`](.github/workflows/main.yml) ejecuta en cada PR y push a `main`:
1. Política de seguridad del código fuente.
2. Pruebas JS y suite adversarial (`node --test app/src/test/js/*.test.cjs`).
3. Pruebas Robolectric de interfaz y accesibilidad (`UiThemeRegressionTest`, `SkillPracticeRegressionTest`).
4. Compilación, pruebas JVM y lint en `debug` y `release` (tanto `com.nexo.browser` como variante de transición `-PnexoTransition`).
5. Verificación de constructores de arranque tras R8 (`tools/check_release_reflection.py`) e identidad de paquete (`tools/check_apk_identity.py`).
6. Compilación de `cefrium-prototype`.

Además, [`.github/dependabot.yml`](.github/dependabot.yml) vigila semanalmente actualizaciones de Gradle/Cefrium y GitHub Actions mediante PRs revisables (sin fusión automática).

Para publicar un APK firmado: *Actions → Nexo Secure CI and Release → Run workflow → `mode = release`* únicamente desde `main`, subiendo previamente `versionCode`/`versionName` en `app/build.gradle.kts`. La compilación corre sin acceso a secretos; solo el paso final de firma usa `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`.

---

## Documentación técnica

- **Auditoría y estabilización V2**: [Auditoría profunda, estabilización, optimización y evolución V2](docs/AUDITORIA-ESTABILIDAD-OPTIMIZACION-V2.md).
- **Arquitectura y migración**: [Migración a Chromium nativo (Cefrium)](docs/CHROMIUM-NATIVE-MIGRATION.md), [Configuración CI Cefrium](docs/CEFrium-CI-SETUP.md), [Cambio de identidad](docs/IDENTIDAD-NEXO.md), [Transferencia cifrada de perfil](docs/TRANSFERENCIA-PERFIL.md), [Actualizaciones y firma](docs/ACTUALIZACIONES-Y-FIRMA.md).
- **Subsistemas y auditorías**: [PhantomShield](docs/PHANTOMSHIELD.md), [Memoria IA local](docs/IA-MEMORIA.md), [Grupos, rutas y Burner](docs/PESTANAS-RED-BURNER.md), [Diagnóstico de subtítulos](docs/DIAGNOSTICO-SUBTITULOS.md), [Estabilidad y audio Android](docs/ESTABILIDAD-Y-AUDIO-ANDROID.md), [Traducción en la página](docs/TRADUCCION-EN-LINEA.md).
- **Auditorías históricas**: [UI 0.30.0](docs/AUDITORIA-UI-0.30.0.md), [Seguridad y recursos 0.30.1](docs/AUDITORIA-SKILLS-2-3-4.md), [Revisión Kotlin/XML y PhantomShield 0.30.2](docs/AUDITORIA-BROWSER-SHIELD.md), [Plan histórico WebView](docs/MIGRACION-CHROMIUM-WEBVIEW.md), [Plan histórico Cefrium 0.31.1](docs/PLAN-TRABAJO-CHROMIUM.md).
