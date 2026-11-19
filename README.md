# Nexo Browser

> **Versión en Gradle:** `0.32.0` · `versionCode 66` · Paquete `com.nexo.browser`
>
> **Última release publicada:** [v0.32.0](https://github.com/stivencop414-bot/NavegadorGecko/releases/tag/v0.32.0) · Motor **Chromium 156** embebido mediante [Cefrium](https://cefrium.com) `0.21.0` (LGPL-3.0) · Android 10+ (`minSdk 29`, `targetSdk 35`, `compileSdk 37.2`).

<img src="docs/brand/nexo.svg" alt="Logo de Nexo Browser" width="112" />

Nexo Browser es un navegador Android nativo en **Kotlin/XML** con Chromium embebido (Cefrium), bloqueo de anuncios y rastreadores integrado (**PhantomShield**), reproductor multimedia avanzado (**Nexo Media Engine**), modo oscuro temprano (**Nexo Dark Engine**), runtime progresivo de extensiones (**NXR Extensions Runtime**), asistente de inteligencia artificial opcional (**Nexo AI**), subtítulos en vivo y traducción completa de páginas en el DOM.

Nexo ya no depende de GeckoView ni de Android System WebView (ver [migración a Chromium nativo](docs/CHROMIUM-NATIVE-MIGRATION.md)). Desde `0.25.0`, el identificador Android es `com.nexo.browser` (ver [cambio de identidad](docs/IDENTIDAD-NEXO.md)).

---

## Arquitectura actual

| Capa | Componentes reales en el repositorio |
| --- | --- |
| **Interfaz nativa (Kotlin/XML)** | `MainActivity`, `NexoSheets`, `NexoUi`, `ThemeManager`, `TabSwitcher`, `TabTools`, `SettingsActivity`, `ExtensionsActivity`, `HistoryActivity`, `DownloadsActivity` |
| **Motor Chromium (Cefrium público)** | `app/src/main/java/com/nexo/browser/engine/cefrium/` (`CefriumEngine`, `CefriumEngineSession`, `CefriumPageView`, `EngineDiagnostics`) usando exclusivamente la API pública `com.cefrium.*` |
| **Puente página ↔ nativo** | `PageChannel` + `PageBridge` + `assets/nexo/cefrium-port.js` + `page-bridge.js` sobre `window.cefriumQuery` con validación de marco, origen y token por sesión |
| **Privacidad y bloqueo** | `app/src/main/java/com/nexo/browser/shield/` (`PhantomShield`, `ShieldAiHost`, `NetworkBlocker`, `BurnerMailbox`) + `assets/extensions/phantomshield/` |
| **Nexo Media Engine** | `app/src/main/java/com/nexo/browser/media/` (`NexoMediaEngine`, `MediaSitePrefs`) + `BrowserMediaController` + `CaptionsUi` + `LiveCaptions` + `page-bridge.js` |
| **Nexo Dark Engine** | `app/src/main/java/com/nexo/browser/dark/DarkEngine.kt` + `assets/nexo/dark-engine.js` + `NightMode` |
| **NXR Extensions Runtime** | `app/src/main/java/com/nexo/browser/extensions/nxr/` (`NxrRuntime`, `NxrPackageInstaller`, `NxrPermissions`, `NxrStorage`) + `assets/nexo/nxr-runtime-shim.js` + `ExtensionManager` |
| **Nexo AI y Traductor** | `app/src/main/java/com/nexo/browser/ai/` (`NexoAiConfig`, `NexoAiClient`, `NexoAiPageAgent`, `NexoAiSheet`) + `assets/nexo/ai-page-agent.js` + `TranslatorManager` + `assets/nexo/page-translator.js` |

---

## Subsistemas principales

### 1. Nexo Media Engine (`com.nexo.browser.media`)
Reproductor multimedia mejorado sobre el elemento HTML5 `<video>` / `<audio>` sin romper la reproducción nativa ni alterar `video.currentTime` durante la captura de subtítulos:
- **Control de velocidad** (`0.25x` a `3.0x`) y **salto rápido configurable** (`5 s`, `10 s`, `15 s`, `30 s`) por sitio.
- **Gestos en pantalla completa (`FullscreenOverlay`)**: doble toque lateral para retroceder/avanzar y arrastre vertical en los bordes para brillo y volumen.
- **Subtítulos avanzados**:
  - Lectura nativa de pistas WebVTT/HLS, subtítulos dibujados por el reproductor (YouTube, Netflix, Prime Video, video.js, JW Player, Shaka, Plyr, Bitmovin) o archivos `.vtt`/`.srt` sin clave ni micrófono.
  - Si no hay pista de texto, opción explícita entre subtítulos instantáneos de Android o transcripción digital interna con Deepgram (Opus ~24 kbps).
  - **Desfase de sincronización (offset)** en milisegundos aplicado solo al calendario de subtítulos, sin reescribir `video.currentTime`.
  - **Tipografía y escala configurables** (pequeño, normal, grande, extra grande).
- **Mini reproductor (Picture-in-Picture)** y reproducción en segundo plano configurable, con preferencias persistentes por dominio (`MediaSitePrefs`).

### 2. Nexo Dark Engine (`com.nexo.browser.dark`)
Modo oscuro web optimizado desde el inicio de carga para reducir destellos blancos y preservar contenido multimedia:
- **Modos globales**: **Auto** (sigue el tema de Nexo y del sistema), **Claro**, **Oscuro** (`#0C1222`) y **OLED** (`#000000`).
- **Estrategias de adaptación**: **Híbrido** (oscuro nativo de Chromium + CSS inteligente), **Nativo** (solo `setDarkMode` de Chromium) y **CSS inteligente** (`dark-engine.js`).
- **Inicio temprano**: aplica `setBackgroundColor` sobre la superficie de Chromium antes del primer pintado e inyecta el tema al comenzar el documento.
- **Protección de elementos**: detecta páginas ya oscuras y evita invertir imágenes, vídeos, `<canvas>`, `<svg>` y editores de texto.
- **Control por sitio**: activación/exclusión por dominio desde la hoja de Herramientas (`TabTools`) sin recargar la pestaña.

### 3. NXR Extensions Runtime (`com.nexo.browser.extensions.nxr`)
Runtime progresivo y acotado para extensiones de terceros sobre Chromium embebido:
- **Instalador seguro (`.crx`, `.xpi`, `.zip`)**: valida encabezado CRX2/CRX3 o ZIP, bloquea *Zip Slip* (rutas relativas/absolutas), impone límites de número de archivos y tamaño descomprimido y presenta una **vista previa de permisos y compatibilidad** antes de instalar.
- **Aislamiento**: `storage.local` / `storage.sync` aislado por extensión en `NxrStorage`, permisos por origen evaluados en `NxrPermissions` y canal de mensajes verificado por token de sesión (`session.nxrToken`).
- **Ciclo de vida y diagnóstico**: inyección de `content_scripts` y CSS según `run_at` (`document_start`, `document_end`, `document_idle`) y `matches`, gestión de acciones (`action`/`browser_action`), activación/desactivación y panel de diagnóstico por extensión en `ExtensionsActivity`.
- **Soporte progresivo honesto**: ver la [matriz de soporte real](#matriz-de-soporte-real) para las APIs WebExtension que aún no son viables sobre la API pública de Cefrium.

### 4. Nexo AI (`com.nexo.browser.ai`) y traducción completa de páginas (`TranslatorManager`)
Asistente integrado y traductor de páginas bajo control total del usuario:
- **Opt-in y privacidad estricta**: Nexo AI viene **desactivado por defecto** y requiere consentimiento explícito antes de realizar cualquier solicitud.
- **Claves en Android Keystore**: las claves API se cifran únicamente mediante `SecretStore` (nunca se escriben en texto plano ni en registros). Los endpoints remotos exigen `HTTPS` (solo se permite `http://` en `localhost`/`127.0.0.1`/`::1`).
- **Extracción segura de contexto (`ai-page-agent.js`)**: excluye contraseñas, campos ocultos, `file`, `cc-*`/tarjetas, OTP/CSRF, cookies, `localStorage` y parámetros sensibles en URL; bloqueado en pestañas privadas por defecto.
- **Defensa contra *prompt injection***: separa las instrucciones del sistema del contenido web mediante delimitadores `<UNTRUSTED_PAGE_CONTEXT>` con escape de etiquetas de cierre.
- **Funciones disponibles**: resumir página, explicar selección, traducir texto, extraer tablas/datos, listar enlaces clave, comparar pestañas abiertas, preguntas libres, resaltado de solo lectura y acciones asistidas (`open_link`, `fill_draft`) que **siempre exigen confirmación explícita (`AlertDialog`)** y nunca envían formularios automáticamente.
- **Control de ejecución y honestidad**: cancelación inmediata (`CancelHandle`), presupuesto configurable de contexto (4–16 KB), timeout configurable, registro local saneado y conteo de tokens mostrado **solo cuando la API lo devuelve**.
- **Traducción completa de páginas (`TranslatorManager` + `page-translator.js`)**: traduce nodos de texto visibles en lotes (Google sin clave, DeepL o modelo IA vía `CaptionProtocol`), omitiendo `<script>`, `<style>`, `<code>`, `<pre>`, formularios, `contenteditable` y `.notranslate`, con **restauración instantánea al idioma original sin recargar**.

---

## Otras funciones integradas
- **Omnibox y buscadores**: Google, DuckDuckGo, Bing, Brave Search y Startpage.
- **Pestañas, grupos y Burner**: sesiones LRU, pestañas privadas, grupos por color, deshacer cierre, gestos en barra inferior y modo **Burner** (perfil aislado de Chromium + correo temporal mail.tm + limpieza automática).
- **PhantomShield 9.2.1**: bloqueo de red con listas EasyList/uBlock, reglas cosméticas, eliminación de anuncios de YouTube, bloqueo de popunders (`$popup`), IA opcional con memoria local por dominio y modelos de respaldo.
- **Descargas aceleradas**: segmentación multi-conexión (solo en servidores con rangos, nunca en proxy ni Burner), reanudación tras cortes y limpieza de archivos incompletos.
- **Herramientas de página**: modo escritorio, captura PNG, exportar PDF, fijar en inicio, buscar en página, compartir, diálogos web nativos y permisos con confirmación mostrando el dominio real.

---

## Matriz de soporte real

| Área / Capacidad | Estado real | Notas técnicas |
| --- | --- | --- |
| Motor Chromium 156 (Cefrium `0.21.0`), pestañas, grupos, Burner, descargas, historial | **Implementado y verificado en CI** | API pública `com.cefrium.*`; tests JVM y política de seguridad en CI |
| Estabilidad de sesiones, recuperación de renderers, gestos de dock y puente `PageChannel` | **Implementado y verificado** | Pruebas JS (235 tests) + pruebas JVM; validación visual continua en dispositivos físicos |
| **Nexo Media Engine** (velocidad, salto, gestos fullscreen, offset CC, prefs por sitio) | **Implementado y probado (JVM + JS)** | Validación de sincronía A/V real y gestos táctiles recomendada en dispositivo físico |
| **Nexo Dark Engine** (Auto/Claro/Oscuro/OLED, híbrido, excepciones por sitio) | **Implementado y probado (JVM + JS)** | La ausencia de destello blanco en primer fotograma requiere verificación visual en hardware real |
| **NXR Extensions Runtime** (`.crx`/`.xpi`/`.zip`, `content_scripts`, `storage`, permisos, diagnóstico) | **Parcialmente soportado (progresivo)** | Soporta scripts de contenido, CSS, `storage`, mensajes y acciones básicas; **no soporta** MV3 `service_worker` persistente, `declarativeNetRequest` completo, `webRequestBlocking` de terceros ni `chrome.tabs`/`cookies` globales debido a los límites de la API pública de Cefrium |
| **Nexo AI** (proveedores OpenAI/Claude/compatibles, contexto seguro, confirmaciones, Keystore) | **Implementado y probado (JVM + JS)** | Requiere clave del usuario o endpoint local (`localhost`); sin telemetría ni clave embebida |
| **Traducción completa de páginas** (`TranslatorManager` + `page-translator.js`) | **Implementado y probado (JVM + JS)** | Traducción en la nube vía Google/DeepL/IA; sustituye a `TranslationsController` de GeckoView |
| Captura de audio de vídeo protegido con DRM/EME | **No soportado por el motor/SO** | Android y Chromium bloquean la captura digital de flujos protegidos por DRM; se muestra aviso claro sin romper reproducción |

---

## APK disponible

La última release firmada publicada desde `main` es **Nexo Browser v0.32.0** (`versionCode 66`), paquete `com.nexo.browser`:

- [Descargar APK `arm64-v8a` (recomendado)](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.32.0/Nexo-v0.32.0-arm64-v8a.apk)
- [Descargar APK `armeabi-v7a` (32 bits)](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.32.0/Nexo-v0.32.0-armeabi-v7a.apk)
- [Descargar `SHA256SUMS.txt`](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.32.0/SHA256SUMS.txt)
- [Ver release v0.32.0](https://github.com/stivencop414-bot/NavegadorGecko/releases/tag/v0.32.0)

Hashes SHA-256 verificados en la release `v0.32.0`:
- `Nexo-v0.32.0-arm64-v8a.apk`: `6a5c1cc3b90a7f76ae655401b454e1a2d6b71f2d3ce58af44205dfafab58d5d0`
- `Nexo-v0.32.0-armeabi-v7a.apk`: `64e1087d0e5355f339f15cfbadfd51d3e0bac70ce7f54a12fd6da72f32efd6b3`

---

## Modelo de seguridad y privacidad

- **Política de motor y puentes**: prohibido en CI el uso de `org.chromium.*`, `org.jni_zero.*`, `GEN_JNI`, `addJavascriptInterface`, `loadUrl("javascript:")`, `setRemoteDebuggingEnabled(true)` y `.proceed()` en errores TLS.
- **URLs**: centralizadas en `SafetyRules.kt` (`UrlSafety`). Se bloquean `javascript:`, `data:`, `file:`, `content:` e `intent:` directos; intents externos solo hacia actividades `BROWSABLE` sin permisos heredados.
- **Secretos y claves**: todas las claves (Deepgram, DeepL, PhantomShield IA, Nexo AI) se cifran con Android Keystore (`SecretStore`), nunca viajan a las páginas web y se redactan automáticamente en registros locales.
- **Extensiones NXR**: verificación estricta de archivos comprimidos contra *Zip Slip*, límites de entradas/tamaño, almacenamiento aislado por ID y verificación por token de sesión + origen permitido.
- **Privacidad de navegación**: limpieza de parámetros de rastreo y secretos en historial, pestañas privadas excluidas de historial/miniaturas/Nexo AI por defecto, y notificaciones multimedia sin exponer títulos en pantalla de bloqueo.

---

## Desarrollo, compilación y pruebas

Requisitos: **JDK 25** (Cefrium usa bytecode Java 25), **Gradle 9.8.2**, **Node.js 20+** y Android SDK con `platforms;android-37.2` y `build-tools;37.0.0`. Las dependencias de Cefrium provienen de `https://maven.cefrium.com` (hashes en `gradle/cefrium-0.21.0.sha256`).

```bash
# Pruebas de regresión JS (PhantomShield, puente, Media Engine, Dark Engine, NXR, Nexo AI, Traductor)
node --test app/src/test/js/*.test.cjs

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
2. Pruebas JS (`node --test app/src/test/js/*.test.cjs`).
3. Pruebas Robolectric de interfaz y accesibilidad (`UiThemeRegressionTest`, `SkillPracticeRegressionTest`).
4. Compilación, pruebas JVM y lint en `debug` y `release` (tanto `com.nexo.browser` como variante de transición `-PnexoTransition`).
5. Verificación de constructores de arranque tras R8 (`tools/check_release_reflection.py`) e identidad de paquete (`tools/check_apk_identity.py`).
6. Compilación de `cefrium-prototype`.

Para publicar un APK firmado: *Actions → Nexo Secure CI and Release → Run workflow → `mode = release`* únicamente desde `main`, subiendo previamente `versionCode`/`versionName` en `app/build.gradle.kts`. La compilación corre sin acceso a secretos; solo el paso final de firma usa `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD`.

---

## Documentación técnica

- **Arquitectura y migración**: [Migración a Chromium nativo (Cefrium)](docs/CHROMIUM-NATIVE-MIGRATION.md), [Configuración CI Cefrium](docs/CEFrium-CI-SETUP.md), [Cambio de identidad](docs/IDENTIDAD-NEXO.md), [Transferencia cifrada de perfil](docs/TRANSFERENCIA-PERFIL.md), [Actualizaciones y firma](docs/ACTUALIZACIONES-Y-FIRMA.md).
- **Subsistemas y auditorías**: [PhantomShield](docs/PHANTOMSHIELD.md), [Memoria IA local](docs/IA-MEMORIA.md), [Grupos, rutas y Burner](docs/PESTANAS-RED-BURNER.md), [Diagnóstico de subtítulos](docs/DIAGNOSTICO-SUBTITULOS.md), [Estabilidad y audio Android](docs/ESTABILIDAD-Y-AUDIO-ANDROID.md), [Traducción en la página](docs/TRADUCCION-EN-LINEA.md).
- **Auditorías históricas**: [UI 0.30.0](docs/AUDITORIA-UI-0.30.0.md), [Seguridad y recursos 0.30.1](docs/AUDITORIA-SKILLS-2-3-4.md), [Revisión Kotlin/XML y PhantomShield 0.30.2](docs/AUDITORIA-BROWSER-SHIELD.md), [Plan histórico WebView](docs/MIGRACION-CHROMIUM-WEBVIEW.md), [Plan histórico Cefrium 0.31.1](docs/PLAN-TRABAJO-CHROMIUM.md).
