# Nexo Browser

> **Versión en Gradle:** `0.37.9` · `versionCode 73` · Paquete `com.nexo.browser`
>
> **Última release publicada:** [v0.37.9](https://github.com/stivencop414-bot/Nexo-Browser/releases/tag/v0.37.9) · Motor **Chromium 156** embebido mediante [Cefrium](https://cefrium.com) `0.21.0` (LGPL-3.0) · Android 10+ (`minSdk 29`, `targetSdk 35`, `compileSdk 37.2`).

<img src="docs/brand/nexo.svg" alt="Logo de Nexo Browser" width="112" />

Nexo Browser es un navegador Android nativo en **Kotlin/XML** con Chromium embebido (Cefrium), sistema de diseño **Nexo UI 7.0 «Liquid Craft»** (Liquid Split Chrome de cristal, temas Obsidian/OLED/Paper, monograma N y emblema Nexo Aperture) y **Nexo AI 5.1** (panel adaptable de 3 estados, conversaciones persistentes al minimizar, indicador flotante in-app, streaming SSE real y laboratorio multimodelo **Comparar modelos**), buscador universal (**Nexo Command**), organización avanzada de pestañas (**Nexo Tabs Pro**), panel unificado por sitio (**Nexo Site Control**), tienda/gestor de extensiones Chromium de PC (**Nexo Extension Hub** sobre **NXR** con modo escritorio automático en tiendas, instalación en streaming y visor HTML aislado para popups/opciones), modo lectura con voz alta y guardado offline (**Nexo Reader**), favoritos y espacios de trabajo (**Nexo Workspace**), bloqueo integrado (**PhantomShield**), reproductor multimedia (**Nexo Media Engine**), modo oscuro temprano (**Nexo Dark Engine**), subtítulos en vivo con captura universal de audio Android y traducción completa en el DOM.

Nexo ya no depende de GeckoView ni de Android System WebView (ver [migración a Chromium nativo](docs/CHROMIUM-NATIVE-MIGRATION.md)). Desde `0.25.0`, el identificador Android es `com.nexo.browser` (ver [cambio de identidad](docs/IDENTIDAD-NEXO.md)).

---

## Arquitectura actual

| Capa | Componentes reales en el repositorio |
| --- | --- |
| **Interfaz nativa — Nexo UI 7.0 «Liquid Craft» (Kotlin/XML)** | `MainActivity`, `NexoSheets`, `SheetGestures`, `NexoUi`, `NexoGlass`, `ThemeManager`, `BrowserColors`, `UiLayout`, `NavConfig`, `NavCustomizerSheet`, `StartPageView`, `SettingsActivity`, `HistoryActivity`, `DownloadsActivity`, `BookmarksActivity` |
| **Nexo Command y Tabs Pro** | `NexoCommandEngine`, `OmniboxSuggestions`, `TabSwitcher`, `TabProModel`, `TabManager`, `TabTools` |
| **Nexo Site Control** | `SiteControlSheet`, `SiteControlModel`, `SitePermissionStore`, `SiteZoomStore`, `SitePermissions` |
| **Motor Chromium (Cefrium público)** | `app/src/main/java/com/nexo/browser/engine/cefrium/` (`CefriumEngine`, `CefriumEngineSession`, `CefriumPageView`, `EngineDiagnostics`) usando exclusivamente la API pública `com.cefrium.*` |
| **Puente página ↔ nativo** | `PageChannel` + `PageBridge` + `assets/nexo/cefrium-port.js` (`cefriumQuery` congelado en `document_start`) + `page-bridge.js`; extracción de Nexo AI, Nexo Reader y Traductor vía retorno directo de `evaluateJavaScript` |
| **Privacidad y bloqueo** | `app/src/main/java/com/nexo/browser/shield/` (`PhantomShield` con caché LRU de red y dedup IA, `ShieldAiHost`, `ShieldPolicy`, `NetworkBlocker`, `BurnerMailbox`) + `assets/extensions/phantomshield/` |
| **Nexo Extension Hub + NXR** | `app/src/main/java/com/nexo/browser/extensions/hub/` (`ExtensionStoreResolver`, `ExtensionStoreClient`, `ExtensionUpdateManager`, `ExtensionHubSheets`), `app/src/main/java/com/nexo/browser/extensions/nxr/` (`NxrRuntime`, `NxrExtensionPanel`, `NxrPackageInstaller`, `NxrPermissions`, `NxrStorage`), `CrxPackage`, `ExtensionCompatibility`, `ExtensionsActivity` |
| **Nexo Reader** | `app/src/main/java/com/nexo/browser/reader/` (`NexoReader`, `OfflineArticleStore`) + `assets/nexo/reader-mode.js` |
| **Nexo Workspace** | `app/src/main/java/com/nexo/browser/workspace/` (`BookmarkStore`, `WorkspaceStore`) + `BookmarksActivity` + `StartPageView` modular |
| **Nexo Media Engine y Subtítulos** | `app/src/main/java/com/nexo/browser/media/` (`NexoMediaEngine`, `MediaSitePrefs`) + `BrowserMediaController` + `CaptionsUi` + `LiveCaptions` + `PlaybackAudioCapture` + `PlaybackPcm` + `SystemCaptions` + `page-bridge.js` |
| **Nexo Dark Engine** | `app/src/main/java/com/nexo/browser/dark/DarkEngine.kt` + `assets/nexo/dark-engine.js` + `NightMode` |
| **Nexo AI 5.1 y Traductor** | `app/src/main/java/com/nexo/browser/ai/` (`NexoAiModels`, `NexoAiStore`, `NexoAiController`, `NexoAiBenchmark`, `NexoAiBenchmarkView`, `NexoAiPillView`, `NexoAiConfig`, `NexoAiClient`, `NexoAiPageAgent`, `NexoAiSheet`) + `assets/nexo/ai-page-agent.js` + `TranslatorManager` + `assets/nexo/page-translator.js` |

---

## Subsistemas principales

### 1. Nexo UI 7.0 «Liquid Craft» y Navegación adaptable
- **Sistema visual Liquid Craft** ([documentación](docs/NEXO-UI-7.0-LIQUID-CRAFT.md)): temas **Obsidian / OLED / Paper** y cinco acentos (Monocromo, Azul Señal, Cobalto, Naranja Internacional, Salvia) con contraste AA/AAA verificado en `BrowserColors`; Plus Jakarta Sans + JetBrains Mono tabular; iconografía lineal de 1.6dp; radios concéntricos (34 / 22 / 20 / 14dp); cristal líquido `NexoGlassDrawable` con `RenderEffect` en API 31+; física `SpringAnimation` (380 / 0.82) y respeto a reducción de movimiento.
- **Liquid Split Chrome (`activity_main.xml`, `MainActivity`):** lienzo edge-to-edge con píldora de cristal `[candado] dominio [recargar]` y clúster desacoplado `[Nexo Aperture] [pestañas] [•••]`, Scroll Compacto en mini-píldora de 34dp, cristal ahumado en modo privado, deslizamiento entre pestañas y Dock Clásico opcional.
- **Tres modos de navegación (`NavConfig`, `NavCustomizerSheet`):** **Clásico**, **Compacto** y **Personalizado** (3 a 6 acciones). Incluye ocultación inteligente del dock al desplazarse (nunca durante edición de URL, búsqueda, IME, inicio, pantalla completa o PiP), *edge-to-edge* y **Predictive Back** en Android 13+.
- **Hojas y tarjetas unificadas:** animaciones suaves de entrada/cierre en `NexoSheets` / `SheetGestures`, gestos bidireccionales y migración completa de diálogos de Extension Hub, NXR, Subtítulos y Nexo AI a `NexoSheets`.

### 2. Nexo Command (`NexoCommandEngine` + `OmniboxSuggestions`)
- **Buscador universal local + web:** busca simultáneamente en pestañas abiertas, grupos, favoritos, accesos rápidos, historial local, ajustes y comandos del navegador, además de sugerencias web cuando corresponde.
- **Prefijos opcionales:** `@tabs`, `@history`, `@actions`, `@settings`, `@bookmarks`.
- **Aislamiento de privacidad:** nunca envía datos locales ni consultas con prefijo `@` o pestañas privadas/Burner a buscadores remotos; las sugerencias remotas jamás pueden invocar comandos privilegiados.

### 3. Nexo Tabs Pro (`TabSwitcher` + `TabProModel`)
- **Experiencia visual renovada:** cabecera compacta con selector segmentado integrado (*Pestañas* | *Privadas*) y cambio rápido de vista (**Cuadrícula**, **Compacta** y **Lista**), buscador y rail único de grupos/duplicadas, tarjetas de altura uniforme con insignias sobre la miniatura y barra inferior ergonómica para una sola mano.
- **Pestañas fijadas sin fuga de RAM:** las pestañas fijadas se anclan al principio con insignia, pero siguen sujetas a `TabManager.trimSessions()` cuando no están activas.
- **Detección no destructiva de duplicados:** señala URLs duplicadas con insignia y filtro rápido sin cerrar ninguna pestaña automáticamente.

### 4. Nexo Site Control (`SiteControlSheet` + `SiteControlModel`)
- **Panel contextual de 5 secciones:** *Sitio y conexión* (dominio, estado HTTPS sin prometer seguridad absoluta, permisos), *Privacidad* (PhantomShield, excepciones, cookies, cámara/micrófono/ubicación), *Experiencia* (Nexo Dark Engine por sitio, zoom real vía Cefrium `setZoomLevel`, silenciar audio vía `setAudioMuted`, traducción, Nexo Reader, Media Engine y subtítulos), *Extensiones* (extensiones NXR activas en el sitio y revocación rápida) e *IA* (permiso explícito por dominio para Nexo AI, aplicado también dentro de `NexoAiSheet`).

### 5. Nexo Extension Hub + NXR (`com.nexo.browser.extensions`)
- **Fuentes compatibles y modo escritorio automático** ([guía completa](docs/NEXO-EXTENSION-HUB.md)): **Chrome Web Store** (Omaha `156.0.0.0` con respaldo directo `response=redirect` sin cookies), **Microsoft Edge Add-ons** (API JSON oficial + descarga CRX3 verificada), **archivos locales** (`.crx`, `.zip`, `.xpi`) y **paquetes HTTPS directos** de desarrolladores. Al detectar Chrome Web Store o Edge Add-ons, `TabManager` activa automáticamente el **modo escritorio** para evitar bloqueos móviles de la tienda.
- **Instalación en streaming para extensiones grandes de PC:** `NxrPackageInstaller` valida y extrae en streaming directo a disco sin los antiguos límites restrictivos de 10 MB / 2 MB / 200 archivos (hasta 512 MB descomprimidos, 256 MB de descarga, 16 MB por script y 20 000 archivos), manteniendo defensa contra *Zip Slip*, duplicados y bombas ZIP.
- **Visor HTML aislado de Popups y Opciones (`NxrExtensionPanel`):** renderiza `action.default_popup` y `options_page` / `options_ui` reales dentro de una hoja inferior aislada sobre `CefriumProfile.get("nexo_nxr_ext_ui")` y `CefriumContentServer` (`https://nxr-ui.nexo.invalid`), con puente `QueryHandler` autenticado por token efímero hacia `NxrStorage` (**cuota de 4 MB**) y mensajería a la pestaña activa.
- **Runtime WebExtensions ampliado (`nxr-runtime-shim.js`):** ámbito léxico compartido entre `content_scripts`, inyección automática en navegaciones SPA (`onUrlChanged`), `storage.local`/`sync`/`session`/`onChanged`, `runtime.connect`/`getPlatformInfo`/`getBrowserInfo`/`getURL`, `permissions.contains`/`getAll`, `action`/`browserAction` e `i18n.getAcceptLanguages`.
- **Tarjetas modernas y barra contextual:** `ExtensionsActivity` muestra los iconos reales del paquete e interruptores `Switch` en 1 toque; `MainActivity` ofrece la barra contextual «Instalar en Nexo» / «Gestionar» con indicador de *Modo escritorio* **sin inyección DOM en la tienda**.
- **Seguridad y honestidad técnica:** validación CRX3 endurecida contra ZIP EOCD, continuidad obligatoria de clave pública (`publicKeySha256`), instalación segura por defecto (`enabled = false`) en paquetes riesgosos/parciales/no verificados, actualizaciones transaccionales con diff de permisos y consentimiento explícito, ciclo de vida de 4 estados (**Descargada / Instalada / Habilitada / Funcionando**) y **matriz auditable de 20 APIs WebExtension** sobre Cefrium `0.21.0`.

### 6. Nexo Reader (`com.nexo.browser.reader`)
- **Lectura nativa saneada:** extractor semántico (`assets/nexo/reader-mode.js`) renderizado únicamente con vistas Android nativas (cero WebView, cero ejecución de HTML/JS de la página, cero evasión de DRM o *paywalls*).
- **Tipografía y voz alta reales:** fuente (Sans/Serif/Mono), tamaño (`14–26 sp`), interlineado, ancho, sincronización con tema Claro/Oscuro/OLED y lectura en voz alta con **`TextToSpeech` de Android** (reproducir/pausar/detener, velocidad `0.75x–2.0x`, voces reales instaladas en el dispositivo y liberación limpia en `shutdown()`).
- **Guardado sin conexión acotado (`OfflineArticleStore`):** máximo 50 artículos / 10 MB con expulsión FIFO y resumen/traducción opcional con Nexo AI solo bajo acción explícita del usuario.

### 7. Nexo Workspace (`com.nexo.browser.workspace` + `StartPageView`)
- **Favoritos (`BookmarkStore`, `BookmarksActivity`):** carpetas, búsqueda insensible a tildes, importación/exportación estándar **Netscape HTML y JSON** con validación estricta `UrlSafety.isWebUrl`.
- **Espacios de trabajo (`WorkspaceStore`):** colecciones con nombre de pestañas y grupos restauradas de forma perezosa (solo despierta la pestaña activa); **excluye estrictamente pestañas privadas y Burner**.
- **Inicio personalizable (`StartPageView`):** bloques activables (*Accesos rápidos* con reordenamiento, *Favoritos*, *Espacios*, *Grupos activos*, *Lectura sin conexión* y *PhantomShield*).

### 8. Nexo Media Engine, Subtítulos en vivo, Dark Engine, PhantomShield y Nexo AI 5.1
- **Nexo Media Engine (`com.nexo.browser.media`) y Subtítulos en vivo (`LiveCaptions`, `PlaybackAudioCapture`, `PlaybackPcm`, `CaptionsUi`):** velocidad (`0.25x–3.0x`), salto rápido (`5–30 s`), gestos en pantalla completa, subtítulos nativos/WebVTT/HLS/SRT o transcripción en vivo con Deepgram, PiP y reproducción en segundo plano. Incluye **captura universal de audio en Android 10+** (`allowAudioPlaybackCapture="true"`, `ALLOW_CAPTURE_BY_ALL` en proceso principal y subprocesos Chromium, negociación nativa `48 kHz estéreo → 44.1 kHz estéreo → 48 kHz mono → 16 kHz mono` con remuestreo `PlaybackPcm.to16kMono`, detección de bajo volumen, soporte de `<audio>`, medidor de señal en tiempo real y activación en 1 toque), además de acceso directo a **Subtítulos instantáneos de Android (Offline)** para contenido con DRM hardware.
- **Nexo Dark Engine (`com.nexo.browser.dark`):** Auto/Claro/Oscuro/OLED, política síncrona por sitio en `document_start`, modo híbrido sin autointerferencia y protección de imágenes/vídeos/canvas/formularios.
- **PhantomShield:** bloqueo de red EasyList/uBlock con caché LRU, reglas cosméticas, YouTube, popunders (`$popup`), limpieza de parámetros de rastreo (también al compartir enlaces desde `MainActivity`) e IA opcional (3 proveedores, soporte NVIDIA/APIs estrictas, timeout 90 s).
- **Nexo AI 5.1 y Traductor** ([documentación completa](docs/NEXO-AI-5.1.md)): panel adaptable de **3 estados** (*Minimizado → Panel parcial → Pantalla expandida*), **conversaciones persistentes** en `NexoAiController` + `NexoAiStore` (minimizar jamás cancela consultas ni borra historial), indicador flotante in-app (`NexoAiPillView`), emblema Nexo Aperture con estados reales, **streaming SSE real** (OpenAI-compatible y Anthropic Claude) con reloj monotónico (`elapsedRealtimeNanos`), vinculación segura `PageBinding` contra acciones en páginas distintas, **laboratorio multimodelo Comparar modelos** (2 a 5 modelos, máx. 2 en paralelo, TTFT real, mediana en repeticiones, fallos aislados y contexto opt-in), claves cifradas en Android Keystore (`SecretStore`), defensa `<UNTRUSTED_PAGE_CONTEXT>` y traducción completa restaurable sin recargar.

---

## Matriz de soporte real

| Área / Capacidad | Estado real | Notas técnicas |
| --- | --- | --- |
| Motor Chromium 156 (Cefrium `0.21.0`), ciclo de vida endurecido (`isClosed`, `docGen`), pestañas, Burner, descargas | **Implementado y verificado en CI** | API pública `com.cefrium.*`; pruebas JVM y política de seguridad en CI |
| **Nexo UI 7.0 «Liquid Craft»**, Navegación adaptable, Predictive Back, Inicio, Selector de pestañas, Ajustes y hojas `NexoSheets` | **Implementado y verificado (JVM + Robolectric)** | Capturas antes/después (Robolectric Native Graphics), contraste AAA/AA, `360 dp`/`412 dp`/`680 dp`/horizontal, Obsidian/OLED/Paper, cinco acentos y escala `200 %` verificados |
| **Nexo AI 5.1** (conversaciones persistentes, minimización in-app, streaming SSE real, laboratorio multimodelo) | **Implementado y probado (JVM + Robolectric)** | Persistencia atómica en disco, marca `INTERRUPTED` si Android mata el proceso, TTFT/tokens reales nunca inventados, guardia `PageBinding` y concurrencia acotada (= 2) |
| **Nexo Command** (`@tabs`, `@history`, `@actions`, `@settings`, `@bookmarks`) y **Nexo Tabs Pro** | **Implementado y probado (JVM + Robolectric)** | Privacidad estricta de búsqueda local; pestañas fijadas y detección no destructiva de duplicados |
| **Nexo Site Control** (5 secciones: sitio, privacidad, experiencia, extensiones, IA) | **Implementado y probado (JVM + Robolectric)** | Conectado a `setZoomLevel`, `setAudioMuted`, `SitePermissionStore`, PhantomShield, Dark Engine, NXR y Nexo AI |
| **Nexo Extension Hub + NXR** (CWS, Edge Add-ons, modo escritorio auto, streaming PC, popups/opciones HTML, CRX3, 20 APIs) | **Parcialmente soportado (progresivo y honesto)** | Descarga/verifica/actualiza CRX3 reales de gran tamaño, renderiza `action.default_popup` y `options_page` en panel Cefrium aislado, y ejecuta `content_scripts`, CSS, `storage` (4 MB) por origen, `i18n` y acciones; **sin** mundos V8 aislados, *service workers* MV3 persistentes, DNR ni `chrome.tabs`/`cookies` de terceros por límites de Cefrium `0.21.0` |
| **Nexo Reader** (extractor semántico, vista nativa saneada, TTS, offline 50/10 MB) y **Nexo Workspace** | **Implementado y probado (JVM + JS)** | Cero ejecución de HTML/JS de página |
| **Nexo Media Engine**, **Subtítulos en vivo (captura universal Android)**, **Nexo Dark Engine** y **Traductor** | **Implementado y probado (JVM + JS)** | Negociación nativa `48/44.1 kHz → 16 kHz mono`, medidor en vivo y captura en 1 toque sobre `AudioPlaybackCapture` |
| Captura de audio de vídeo protegido con DRM/EME hardware (Widevine L1) o evasión de *paywalls* | **No soportado por diseño y SO** | Android bloquea flujos DRM hardware para apps de terceros (se ofrece acceso en 1 toque a Subtítulos instantáneos de Android); Nexo nunca elude DRM ni muros de pago |

---

## APK disponible

La última release firmada publicada desde `main` es **Nexo Browser v0.37.9** (`versionCode 73`), paquete `com.nexo.browser`:

- [Descargar APK `arm64-v8a` (recomendado)](https://github.com/stivencop414-bot/Nexo-Browser/releases/download/v0.37.9/Nexo-v0.37.9-arm64-v8a.apk)
- [Descargar APK `armeabi-v7a` (32 bits)](https://github.com/stivencop414-bot/Nexo-Browser/releases/download/v0.37.9/Nexo-v0.37.9-armeabi-v7a.apk)
- [Descargar `SHA256SUMS.txt`](https://github.com/stivencop414-bot/Nexo-Browser/releases/download/v0.37.9/SHA256SUMS.txt)
- [Ver release v0.37.9](https://github.com/stivencop414-bot/Nexo-Browser/releases/tag/v0.37.9)

Hashes SHA-256 verificados en la release `v0.37.9`:
- `Nexo-v0.37.9-arm64-v8a.apk`: `3507e0ad23bf913c276abb57859e294e410d7875246bba6be885e91c2cd35e6c`
- `Nexo-v0.37.9-armeabi-v7a.apk`: `bee99bf0724a01b5935623a565654119f4b6b94eff7e09b2eb26c989d7061fca`

---

## Modelo de seguridad y privacidad

- **Política de motor y puentes:** prohibido en CI el uso de `org.chromium.*`, `org.jni_zero.*`, `GEN_JNI`, `addJavascriptInterface`, `loadUrl("javascript:")`, `setRemoteDebuggingEnabled(true)` y `.proceed()` en errores TLS.
- **URLs:** centralizadas en `SafetyRules.kt` (`UrlSafety`). Se bloquean `javascript:`, `data:`, `file:`, `content:` e `intent:` directos; intents externos solo hacia actividades `BROWSABLE` sin permisos heredados.
- **Secretos y claves:** todas las claves (Deepgram, DeepL, PhantomShield IA, Nexo AI) se cifran con Android Keystore (`SecretStore`), nunca viajan a las páginas web y se redactan automáticamente en registros locales y diagnósticos exportados.
- **Extensiones NXR y Hub:** cliente de tienda sin cookies, verificación CRX3 + EOCD + continuidad de `publicKeySha256`, instalador transaccional contra *Zip Slip*/duplicados/bombas ZIP, modo seguro por defecto, diff de permisos en actualizaciones, `cefriumQuery` congelado en `document_start`, `storage` particionado por origen, grants efímeros de 256 bits y *rate limiting*.
- **Privacidad de navegación:** limpieza de parámetros de rastreo al navegar y compartir enlaces, pestañas privadas y Burner excluidas de historial/miniaturas/favoritos/espacios de trabajo/Nexo AI/diagnóstico por defecto, y `FLAG_SECURE` propagado a ventanas y hojas privadas.

---

## Desarrollo, compilación y pruebas

Requisitos: **JDK 25** (Cefrium usa bytecode Java 25), **Gradle 9.8.2**, **Node.js 20+** y Android SDK con `platforms;android-37.2` y `build-tools;37.0.0`. Las dependencias de Cefrium provienen de `https://maven.cefrium.com` (hashes en `gradle/cefrium-0.21.0.sha256`).

```bash
# Pruebas de regresión y suite adversarial JS (PhantomShield, puente, Media, Dark, NXR, Nexo AI, Traductor, Reader)
node --test app/src/test/js/*.test.cjs

# Microbenchmarks reproducibles en Node.js (entorno host, no dispositivo Android)
node app/src/test/js/benchmarks/run-benchmarks.cjs

# Pruebas unitarias JVM (debug y release)
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest

# Pruebas Robolectric de interfaz nativa, accesibilidad y capturas Flow 3.0
./gradlew :app:testDebugUnitTest -PnexoScreenshots \
  --tests '*UiThemeRegressionTest' --tests '*SkillPracticeRegressionTest' --tests '*ScreenshotTest'

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

- **Evolución V4 (Nexo Flow 4.0 — botón ✨ de Nexo AI, asistente contextual y rediseño del inicio):**
  - [Implementación Nexo Flow 4.0](docs/NEXO-FLOW-V4-IMPLEMENTATION.md)
- **Evolución V3 (Nexo Flow 3.0, Extension Hub y mejoras):**
  - [Sistema de diseño Nexo Flow 3.0](docs/NEXO-FLOW-V3-DESIGN.md)
  - [Arquitectura, seguridad y matriz real de Nexo Extension Hub](docs/NEXO-EXTENSION-HUB.md)
  - [Propuestas de mejora, métricas y protocolo de validación V3](docs/NEXO-V3-IMPROVEMENTS.md)
- **Auditoría y estabilización V2:** [Auditoría profunda, estabilización, optimización y evolución V2](docs/AUDITORIA-ESTABILIDAD-OPTIMIZACION-V2.md).
- **Arquitectura y migración:** [Migración a Chromium nativo (Cefrium)](docs/CHROMIUM-NATIVE-MIGRATION.md), [Configuración CI Cefrium](docs/CEFrium-CI-SETUP.md), [Cambio de identidad](docs/IDENTIDAD-NEXO.md), [Transferencia cifrada de perfil](docs/TRANSFERENCIA-PERFIL.md), [Actualizaciones y firma](docs/ACTUALIZACIONES-Y-FIRMA.md).
- **Subsistemas y auditorías:** [PhantomShield](docs/PHANTOMSHIELD.md), [Memoria IA local](docs/IA-MEMORIA.md), [Grupos, rutas y Burner](docs/PESTANAS-RED-BURNER.md), [Diagnóstico de subtítulos](docs/DIAGNOSTICO-SUBTITULOS.md), [Estabilidad y audio Android](docs/ESTABILIDAD-Y-AUDIO-ANDROID.md), [Traducción en la página](docs/TRADUCCION-EN-LINEA.md).
- **Auditorías históricas:** [UI 0.30.0](docs/AUDITORIA-UI-0.30.0.md), [Seguridad y recursos 0.30.1](docs/AUDITORIA-SKILLS-2-3-4.md), [Revisión Kotlin/XML y PhantomShield 0.30.2](docs/AUDITORIA-BROWSER-SHIELD.md), [Plan histórico WebView](docs/MIGRACION-CHROMIUM-WEBVIEW.md), [Plan histórico Cefrium 0.31.1](docs/PLAN-TRABAJO-CHROMIUM.md).
