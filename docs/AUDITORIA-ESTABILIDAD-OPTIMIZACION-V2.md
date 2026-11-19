# Auditoría Profunda, Estabilización, Optimización y Evolución V2

> **Rama de trabajo:** `fix/v2-audit-security-perf` (base `main` @ `e76e77a`, tras PR #55 y release firmada `v0.33.0` / `versionCode 67`).
>
> **Prioridad aplicada:** `SEGURIDAD → ESTABILIDAD → CORRECCIÓN FUNCIONAL → RENDIMIENTO → EXPERIENCIA DE USUARIO → NUEVAS CAPACIDADES`.
>
> **Principio de honestidad técnica:** Todas las cifras aquí reportadas proceden de mediciones reales en Node.js / JVM / CI. No se incluyen métricas simuladas de dispositivos Android físicos; el protocolo de validación en hardware real mediante `adb` figura en la Sección 7.

---

## 1. Hallazgos confirmados vs. descartados (P0–P3)

| ID | Severidad | Subsistema | Diagnóstico | Estado | Causa raíz y corrección aplicada |
| --- | --- | --- | --- | --- | --- |
| **V2-01** | **P0 (Seguridad)** | NXR Extensions Runtime / Puente JS | **Confirmado** | **Corregido** | `session.nxrToken` era un token estático compartido entre NXR, Nexo AI y TranslatorManager y viajaba por `window.cefriumQuery` mutable; además `window.__nxrDispatch` quedaba expuesto en el mundo JS compartido. Se eliminó `session.nxrToken` por completo: `cefrium-port.js` congela `globalThis.cefriumQuery` (`writable: false, configurable: false`) en `document_start`, NXR emite `CapabilityGrant` efímeros de 256 bits (`SecureRandom`) vinculados a `(sessionKey, extId, originHost, docGen, isPrivate, hasStoragePermission)` con revocación inmediata en navegación/desactivación/cambio de host, `NxrStorage` particiona datos por origen (`origins/<sha256>.json`) bajo cuota agregada de 512 KB, se eliminó `window.__nxrDispatch` (sustituido por `CustomEvent` con nonce por acción), se aplica rate limit (30 llamadas / 10 s) y tope de 32 KB por sobre. |
| **V2-02** | **P0 (Seguridad)** | Nexo AI & TranslatorManager | **Confirmado** | **Corregido** | Ambos enviaban el token estático de sesión por `window.cefriumQuery` para devolver instantáneas/segmentos. Migrados al retorno directo de `CefriumBrowser.evaluateJavaScript(script, ValueCallback<String>)` mediante `CefriumEngineSession.evaluateJsonInMainFrame(...)` y eliminados los endpoints `nexo_ai_snapshot` y `nexo_translate_segments` de `PhantomShield.onPageMessage`. |
| **V2-03** | **P0 (Seguridad)** | Nexo AI (`ai-page-agent.js`) | **Confirmado** | **Corregido** | El extractor solo filtraba inputs sensibles, permitiendo *prompt injection* oculto mediante `display:none`, `visibility:hidden`, `opacity:0`, `font-size:0`, `hidden`, `aria-hidden="true"`, `inert`, `clip:rect(0...)`, `clip-path:inset(50%/100%)`, bloques `1×1px overflow:hidden` (`sr-only`) y texto fuera de pantalla (`left <= -1000px`). Se añadieron `isHiddenElement()`, `hasHiddenAncestor()` y `redactInlineSecrets()` (`Bearer`, `sk-*`, `AIza*`, JWT) sobre texto visible, títulos, encabezados, enlaces, tablas, formularios y selección. |
| **V2-04** | **P1 (Estabilidad)** | Cefrium `0.21.0` (`CefriumEngineSession`) | **Confirmado** | **Corregido** | Llamadas a `CefriumBrowser` tras cierre de renderer o cierre asíncrono podían lanzar `IllegalStateException` si `browser.isClosed` era `true`; además no existía generación de documento monotónica para descartar operaciones en vuelo. Se añadió `liveBrowser() = browser?.takeIf { !closed && !it.isClosed }`, guardas `onMain {}` y `docGen: AtomicLong` incrementado en `onLoadStart`, `releaseBrowser()` y `close()`. |
| **V2-05** | **P1 (Corrección)** | Nexo Dark Engine (`dark-engine.js`) | **Confirmado** | **Corregido** | **Autointerferencia de detección nativa:** tras ejecutar `bootstrapEarly()` en `document_start`, `html[data-nexo-dark-early="true"]` pintaba `#0c1222`; luego `evaluate() → detectNativeDark()` leía `getComputedStyle(documentElement).backgroundColor` en páginas con `<body>` transparente y clasificaba falsamente páginas claras como «oscuro nativo», desactivando el modo oscuro y provocando destello blanco. Además `enableDarkReaderLayer()` aplicaba CSS incluso cuando `runEnable` había clasificado la página como `native`. Corregido suspendiendo temporalmente los `<style>`/`data-nexo-dark-*` propios dentro de un bloque `try/finally` durante la lectura de `getComputedStyle`, y respetando `state.activeEngine !== "native"`. |
| **V2-06** | **P1 (Estabilidad)** | NXR Instalador (`NxrPackageInstaller`) | **Confirmado** | **Corregido** | Una actualización fallida podía dejar el directorio de la extensión en estado inconsistente; tampoco había protección contra bombas ZIP por ratio de compresión ni entradas duplicadas normalizadas. Se implementó intercambio transaccional (`extDir → backup`, `staging → extDir`, rollback ante error) preservando `origins/`, límite de ratio de compresión (`100:1` sobre > 2 MB), rechazo de entradas duplicadas y limpieza de directorios huérfanos (`cleanupOrphanDirs()`). |
| **V2-07** | **P1 (Concurrencia)** | Traductor (`TranslatorManager` + `page-translator.js`) | **Confirmado** | **Corregido** | Traducciones en curso podían aplicar texto obsoleto tras navegar/recargar o mutar nodos desconectados en SPAs; además esperaba a todos los lotes antes de pintar el primero y truncaba silenciosamente a 180 segmentos. Corregido con validación estricta por `docGen` + generación JS en cada lote, cancelación inmediata de `okhttp3.Call` en `forget()`/`restoreActive()`, aplicación progresiva lote a lote, verificación `node.isConnected`, tope ampliado a 400 segmentos y reporte honesto de bloques omitidos. |
| **V2-08** | **P2 (Funcional / UX)** | Nexo AI (`NexoAiClient`, `NexoAiSheet`, `NexoAiPageAgent`) | **Confirmado** | **Corregido** | (a) Modelos de razonamiento (`o1`/`o3`/`o4`/`gpt-5`/`deepseek-reasoner`/`qwq`) fallaban al enviar `temperature` o `max_tokens` (en OpenAI); ahora se omite `temperature` y se usa `max_completion_tokens` cuando corresponde. (b) Respuestas tardías de peticiones anteriores podían sobrescribir la tarjeta actual; protegido con contador `requestGen`. (c) Acciones estructuradas propuestas en ```` ```nexo-actions ```` ahora se validan bajo esquema estricto (allowlist `highlight`/`open_link`/`fill_draft`, rechazo de `javascript:`/`file:`/credenciales/campos sensibles, máx. 4) y toda acción con efectos secundarios pasa obligatoriamente por `AlertDialog`. (d) El alcance «Comparar pestañas» captura ahora contexto real saneado de hasta 4 pestañas vivas dentro del presupuesto de caracteres. |
| **V2-09** | **P2 (Rendimiento)** | PhantomShield (`PhantomShield.kt`) | **Confirmado** | **Corregido** | Cada subrecurso repetido recorría `NetworkEngine` completo y ráfagas de `AI_CLASSIFY` en una misma página podían lanzar peticiones concurrentes. Se añadió caché LRU acotada y sincronizada (`DecisionLruCache`, máx. 1024 entradas, invalidada en `reloadLists`, `setEnabled`, `onStorageChanged` y `readAi`) + deduplicación de `AI_CLASSIFY` en vuelo por `(tab.id, tab.page)`. |
| **V2-10** | **P2 (Estabilidad)** | `TabManager.restoreOrCreate()` | **Confirmado** | **Corregido** | Una sola entrada malformada en `tabs.json` lanzaba `JSONException` en `array.getJSONObject(i)` abortando la restauración del resto de pestañas válidas. Cambiado a `array.optJSONObject(i) ?: continue` con `runCatching` por elemento. |
| **V2-11** | **P3 (Observabilidad)** | Diagnóstico técnico (`EngineDiagnostics`, `SettingsActivity`, `LiveCaptions`) | **Mejorado** | **Implementado** | Añadidos tiempos de carga (`lastLoadMs`, media), memoria del proceso app explícitamente diferenciada del RSS de renderers Chromium, consulta bajo demanda de `DOM` y `Navigation Timing` en pestaña activa no privada, telemetría de `NxrRuntime`, `TranslatorManager`, `LiveCaptions` (latencia captura→transcripción sin tocar `video.currentTime`), caché de `PhantomShield`, ocultamiento de hosts privados y exportación «Compartir» saneada mediante `CrashReportRedactor` + redactor de claves API. |
| **V2-D1** | *Descartado* | PhantomShield Service Workers | **Descartado** | Ya existía | `PhantomShield.attachProfile(profile)` ya instala `profile.setResourceInterceptor(Interceptor(null))` sobre todos los perfiles de Cefrium. |
| **V2-D2** | *Descartado* | Persistencia atómica de pestañas (`TabStore`) | **Descartado** | Ya existía | `TabStore.kt` ya utiliza `AtomicFile` para evitar ficheros truncados en cierres bruscos. |
| **V2-D3** | *Descartado* | Actualización de versión Cefrium | **Descartado** | Ya actualizado | Según `https://cefrium.com/releases/`, Cefrium `0.21.0` (Chromium `156.0.8078.96`) ya es la última versión publicada. Se añadió `.github/dependabot.yml` para monitorizar futuras actualizaciones mediante PRs revisables. |

---

## 2. Arquitectura de seguridad y aislamiento

1. **Eliminación de tokens estáticos por sesión**: no existe `session.nxrToken` en `CefriumEngineSession`.
   - Nexo AI y TranslatorManager extraen datos exclusivamente mediante el valor de retorno directo de `CefriumBrowser.evaluateJavaScript(script, ValueCallback<String>)` en el marco principal.
   - NXR Extensions Runtime opera con `CapabilityGrant` efímeros de 256 bits (`SecureRandom`), ligados a `(sessionKey, extId, originHost, docGen, isPrivate, hasStoragePermission)`, revocados inmediatamente ante nueva navegación, cambio de host, desactivación o cierre de pestaña.
2. **Defensa contra hooking de `cefriumQuery`**: `assets/nexo/cefrium-port.js` congela `globalThis.cefriumQuery` (`writable: false, configurable: false`) en `document_start`. `nxr-runtime-shim.js` captura referencias inmutables a `JSON.stringify` y `JSON.parse` y limita el tamaño de mensaje a 32 KB.
3. **Particionamiento por origen de `NxrStorage`**: debido a que Cefrium `0.21.0` no expone mundos V8 aislados para *content scripts* de terceros, el almacenamiento de cada extensión se particiona por `sha256(originHost)` (`origins/<hash>.json`) con cuota agregada de 512 KB, impidiendo que un sitio comprometido lea o sobrescriba datos guardados bajo otro origen.
4. **Defensa contra *prompt injection* y fuga de secretos**: `ai-page-agent.js` descarta subárboles invisibles/ocultos/off-screen y redacta tokens (`Bearer`, `sk-*`, `AIza*`, JWT) antes de construir el paquete `<UNTRUSTED_PAGE_CONTEXT>`. Las acciones estructuradas del modelo se validan contra esquema estricto y exigen `AlertDialog` para cualquier efecto secundario.
5. **Política de CI intacta**: cero usos de `org.chromium.*`, `org.jni_zero.*`, `GEN_JNI`, `addJavascriptInterface`, `loadUrl("javascript:")`, `setRemoteDebuggingEnabled(true)`, `.proceed()` ni `eval()`.

---

## 3. Matriz de capacidades y límites reales de Cefrium `0.21.0`

| Capacidad | Soporte en Cefrium `0.21.0` público | Implementación en Nexo Browser |
| --- | --- | --- |
| `CefriumBrowser.isClosed()` | **Sí** | Validado en `CefriumEngineSession.liveBrowser()` antes de cualquier operación |
| `evaluateJavaScript(script, ValueCallback<String>)` | **Sí** | Usado en `evaluateJsonInMainFrame` para Nexo AI, TranslatorManager y sonda de diagnóstico |
| `addDocumentStartJavaScript(script, originRules)` | **Sí** (sin API pública de revocación por instancia) | Reservado a infraestructura permanente (`cefrium-port.js`, `dark-engine.js`, PhantomShield); extensiones NXR revocables inyectadas vía `onDocumentAvailable` / `onDocumentIdle` |
| `CefriumProfile.setResourceInterceptor(...)` | **Sí** (incluye Service Workers) | Usado por PhantomShield + caché LRU de decisiones |
| Mundos V8 aislados para *content scripts* de terceros | **No expuesto en API pública `0.21.0`** | Mitigado con grants efímeros por documento/origen, `cefriumQuery` congelado y `NxrStorage` particionado por origen (límite documentado honestamente) |
| MV3 `service_worker` persistente / `declarativeNetRequest` / `chrome.tabs` global | **No soportado por el motor embebido** | Fuera de alcance de NXR progresivo; rechazado o informado con claridad en la vista previa de instalación |
| Captura de audio de vídeo protegido por DRM/EME | **Bloqueado por diseño por Android y Chromium** | Detectado y comunicado honestamente sin alterar reproducción |

---

## 4. Pruebas automatizadas y suite adversarial

- **Suite JS (`node --test app/src/test/js/*.test.cjs`)**: **243 / 243 tests en verde** (~4,0 s en Node.js v24.22.3 linux/x64), incluyendo:
  - `adversarial-suite.test.cjs` + fixture local `fixtures/adversarial-prompt-injection.html`: verifica eliminación de 6 técnicas de *prompt injection* oculto, redacción de secretos en título/cuerpo, exclusión de campos `password`/`hidden`/`cc-number`, congelación de `globalThis.cefriumQuery` frente a scripts hostiles que intentan reemplazarla, y protección combinada de Dark Engine y Translator.
  - `nxr-runtime.test.cjs`: verifica eliminación de `window.__nxrDispatch`, uso de eventos con nonce, referencias inmutables a `JSON`, límite de 32 KB y aislamiento de almacenamiento.
  - `dark-engine.test.cjs`: test de regresión específico del bug `bootstrapEarly()` → `<body>` transparente → `detectNativeDark()`.
  - `page-translator.test.cjs`: test de regresión de nodos SPA desconectados (`isConnected = false`), guarda de generación tras `restore()` y conteo honesto de truncado sobre > 400 párrafos.
  - `ai-page-agent.test.cjs`: tests de trampas CSS/ARIA/off-screen y redacción de secretos inline.
- **Suite JVM (`./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest` en CI)**:
  - `NxrRuntimeTest.kt`: grants efímeros, revocación por `docGen`/host/desactivación, particionado por origen en `NxrStorage`, cuota agregada, rate limiting, permiso `"storage"`, intercambio transaccional y rechazo de zip-bomb / duplicados.
  - `NexoAiTest.kt`: adaptación de parámetros para modelos de razonamiento (`o3-mini`, `deepseek-reasoner`), validación estricta de bloques `nexo-actions` rechazando `javascript:`/`file:`/credenciales/`execute_js`/`user_password`, desempaquetado JSON de `evaluateJavaScript` y cancelación de `TranslatorManager.ActiveTranslation`.
  - `ShieldParityTest.kt`: evicción LRU y limpieza de `PhantomShield.DecisionLruCache`.
  - `CrashReportRedactorTest.kt`: saneamiento de informes de `EngineDiagnostics` y parseo de métricas de página.

---

## 5. Mediciones reales vs. métricas pendientes en dispositivo físico

> **Importante:** Las mediciones de la primera tabla corresponden a **microbenchmarks reproducibles en Node.js v24.22.3 (linux/x64)** ejecutados con `node app/src/test/js/benchmarks/run-benchmarks.cjs`. **NO** representan tiempos de CPU ARM ni consumo real de batería/RSS en un teléfono Android físico.

### 5.1 Microbenchmarks medidos en entorno host (Node.js v24.22.3, linux/x64)

| Microbenchmark | Iteraciones | Tiempo total | Media por operación | Detalle verificado |
| --- | ---: | ---: | ---: | --- |
| `PhantomShield network-engine.js` (312 reglas de dominio empaquetadas) | 10 000 | 58,99 ms | **5,90 µs** (~169 517 ops/s) | 5 000 bloqueos / 5 000 permitidos |
| `Nexo AI ai-page-agent.js` (DOM de 1 000 nodos, 200 trampas ocultas) | 200 | 67,28 ms | **0,336 ms** | 6 200 caracteres útiles extraídos, 0 trampas filtradas |
| `Page Translator page-translator.js` (DOM 500 párrafos: extract + apply + restore) | 100 | 94,12 ms | **0,941 ms** | 400 segmentos capturados, 100 omitidos reportados |
| `Nexo Dark Engine dark-engine.js` (`bootstrapEarly` + `applyConfig css`) | 1 000 | 7,42 ms | **7,42 µs** | Sin autointerferencia ni nodos `<style>` duplicados |

### 5.2 Métricas pendientes de validación en hardware Android físico

| Métrica | Estado | Herramienta / Protocolo |
| --- | --- | --- |
| Arranque en frío (*cold start* `TotalTime`) | Pendiente de dispositivo físico | `adb shell am start -W` (ver Sección 6) |
| Memoria RSS real del proceso app + renderers aislados Chromium | Pendiente de dispositivo físico | `adb shell dumpsys meminfo com.nexo.browser` |
| Ausencia visual de destello blanco (*FOUC*) en primer fotograma (Dark Engine) | Pendiente de dispositivo físico | Grabación a 60 fps / `gfxinfo framestats` |
| Sincronía audiovisual real y latencia de subtítulos en vídeo 1080p/60fps | Pendiente de dispositivo físico | Reproducción en dispositivo + Panel de Diagnóstico |
| Estabilidad tras rotación, background/foreground prolongado y `TRIM_MEMORY_RUNNING_CRITICAL` | Pendiente de dispositivo físico | `adb shell am send-trim-memory` |

---

## 6. Protocolo reproducible de validación en dispositivo Android físico (`adb`)

```bash
# 1. Instalar APK debug/release compilado desde CI
adb install -r Nexo-arm64-v8a.apk

# 2. Medir arranque en frío (repetir 5 veces y tomar mediana)
adb shell am force-stop com.nexo.browser
adb shell am start -W -n com.nexo.browser/.MainActivity

# 3. Medir memoria real (proceso app + procesos renderer aislados de Cefrium)
adb shell dumpsys meminfo com.nexo.browser
adb shell ps -A -o PID,RSS,NAME | grep nexo

# 4. Simular presión crítica de memoria para verificar preservación de pestaña activa/media
adb shell am send-trim-memory com.nexo.browser RUNNING_CRITICAL

# 5. Revisar jank/frames durante scroll con Dark Engine y PhantomShield activos
adb shell dumpsys gfxinfo com.nexo.browser reset
# (navegar 30 s en sitios pesados)
adb shell dumpsys gfxinfo com.nexo.browser

# 6. Verificar ausencia de excepciones fatales o IllegalStateException de Cefrium
adb logcat -d -s AndroidRuntime:E NexoEngine:W
```

---

## 7. Limitaciones honestas conocidas

1. **Sin mundos V8 aislados en Cefrium `0.21.0`**: los *content scripts* de extensiones NXR comparten entorno JS con la página. Aunque congelar `cefriumQuery`, usar grants efímeros y particionar `NxrStorage` por origen mitiga el robo lateral entre orígenes y la reutilización de tokens, una página maliciosa en el mismo origen podría observar variables globales no encapsuladas si una extensión de terceros las expone.
2. **NXR no sustituye un motor completo de WebExtensions MV3**: extensiones dependientes de `background.service_worker` persistente, `declarativeNetRequest` complejo o APIs de pestañas/cookies globales siguen fuera del alcance de la API pública de Cefrium.
3. **Panel de diagnóstico de memoria**: la memoria mostrada dentro de la app (`Runtime` + `Debug.getNativeHeapAllocatedSize`) corresponde únicamente al proceso principal de Android; la memoria de los procesos *renderer* de Chromium solo es observable externamente mediante `adb shell dumpsys meminfo`.
4. **Sin verificación en hardware físico desde este entorno**: toda la verificación de esta PR se realizó mediante Node.js, pruebas unitarias JVM, Robolectric, lint Android y análisis de seguridad en GitHub Actions CI.
