# Nexo Browser V3 — Propuestas de Mejora, Métricas y Protocolo de Validación

> **Línea base medida:** `main` en `a6a484e` (`Nexo 0.34.0`, `versionCode 68`, `Cefrium 0.21.0` / Chromium `156.0.8078.96`).
> **Rama de evolución:** `feat/nexo-v3-evolution`.
> **Nota metodológica:** todas las cifras siguientes distinguen explícitamente mediciones ejecutadas en **host CI (Linux x64 / JVM 25 / Node.js 24 / Robolectric)** de las pruebas que requieren **hardware Android físico**. Ninguna métrica ha sido estimada ni simulada.

---

## 1. Catálogo priorizado de mejoras V3 (≥ 10 propuestas)

| ID | Prioridad | Propuesta | Estado en V3 | Impacto / Justificación técnica |
| --- | --- | --- | --- | --- |
| **M-01** | **P0** | Endurecimiento de verificación CRX3 + EOCD ZIP + continuidad obligatoria de clave pública (`publicKeySha256`) y diff de permisos en actualizaciones de extensiones | **Implementada** | Cierra vectores de suplantación de paquete y escalada silenciosa de permisos en Chrome Web Store / Edge Add-ons (`CrxPackage`, `NxrPackageInstaller`, `ExtensionUpdateManager`). |
| **M-02** | **P0** | Aislamiento estricto de privacidad en Nexo Command, Bookmarks y Workspaces (cero envío remoto con `@` o modo privado; exclusión total de pestañas privadas/Burner; rechazo de `javascript:`/`data:`/`file:`) | **Implementada** | Garantiza que los nuevos flujos de productividad no filtren datos de navegación privada ni permitan XSS en importación HTML/JSON. |
| **M-03** | **P1** | Permiso explícito por dominio para Nexo AI sincronizado entre `SiteControlSheet` y `NexoAiSheet` | **Implementada** | Evita discrepancias entre el panel de control por sitio y la hoja de Nexo AI: bloquear un dominio en Site Control impide realmente extraer/enviar contexto desde `NexoAiSheet`. |
| **M-04** | **P1** | Limpieza automática de parámetros de rastreo y credenciales al compartir enlaces (`shareCurrent`, `shareText`, `HistoryActivity`, `DownloadsActivity`) | **Implementada** | Reutiliza `ShieldPolicy.stripTrackingParams` + `UrlSafety.stripCredentials` para eliminar `utm_*`, `fbclid`, `gclid`, etc. al compartir URLs hacia otras apps. |
| **M-05** | **P1** | Navegación adaptable (`Clásico` / `Compacto` / `Personalizado`), ocultación inteligente al desplazarse y **Predictive Back** (API 33+) | **Implementada** | Maximiza área útil de lectura sin ocultar controles al editar URL/buscar/IME y cede el gesto del sistema cuando la pestaña está en la raíz. |
| **M-06** | **P1** | Restauración perezosa de `WorkspaceStore` y liberación de memoria en pestañas fijadas (`TabManager.trimSessions`) | **Implementada** | Permite restaurar decenas de pestañas y fijar sitios frecuentes sin abrir múltiples procesos renderizadores Chromium simultáneos. |
| **M-07** | **P2** | Indicador visual y accesible de audio silenciado / identidad Burner / ruta de red en `tabToolsButton` y tinte de error AA en `findCount` (`0/0`) | **Implementada** | Feedback inmediato en la barra principal cuando la pestaña tiene audio silenciado vía Cefrium `setAudioMuted`, ruta personalizada o cero coincidencias en búsqueda en página. |
| **M-08** | **P2** | Hojas contextuales Flow 3.0 en pulsación larga para `HistoryActivity` (abrir nueva/privada, guardar favorito, copiar limpio, quitar) y `DownloadsActivity` (abrir, compartir vía `FileProvider`, copiar origen, eliminar) | **Implementada** | Reduce fricción en pantallas secundarias y unifica su lenguaje visual con `NexoSheets` y `NexoUi`. |
| **M-09** | **P2** | Vista de lectura nativa saneada (`NexoReader`) con tipografía adaptable, `TextToSpeech` real de Android y biblioteca offline acotada (50 artículos / 10 MB FIFO) | **Implementada** | Lectura y audio accesibles sin crear un WebView secundario ni ejecutar HTML/JS arbitrario, con cuota rígida en disco. |
| **M-10** | **P2** | Exportación e importación estándar de favoritos (Netscape Bookmark HTML + JSON) vía Storage Access Framework (`BookmarksActivity`) | **Implementada** | Portabilidad real con Chrome, Edge y Firefox sin solicitar permisos amplios de almacenamiento. |
| **M-11** | **P2** | Mundos V8 aislados para `content_scripts`, `chrome.tabs`, `declarativeNetRequest` y `background.service_worker` para extensiones de terceros | **Diferida (bloqueada por Cefrium 0.21.0)** | Requiere soporte de API pública en versiones futuras de Cefrium; Nexo mantiene la matriz honesta de 20 APIs y no usa JNI privado ni reflexión interna. |
| **M-12** | **P3** | Sincronización cifrada extremo a extremo (E2EE) opcional entre dispositivos para favoritos y espacios de trabajo | **Diferida (roadmap V4)** | Complementaría la transferencia de perfil existente (`TransferSchema`) sin añadir servidores propietarios obligatorios; requiere diseño criptográfico dedicado. |

---

## 2. Comparativa cuantitativa: Antes (`a6a484e`) vs Después (`feat/nexo-v3-evolution`)

### A. Cobertura de pruebas automatizadas

| Suite de pruebas | Línea base (`main` `a6a484e`) | Evolución V3 | Delta |
| --- | --- | --- | --- |
| Pruebas JS + suite adversarial (`node --test app/src/test/js/*.test.cjs`) | **258 / 258** | **261 / 261** | **+3** (`reader-mode.test.cjs`) |
| Pruebas unitarias JVM Debug (`:app:testDebugUnitTest`) | **224 / 224** | **260 / 260** (51 suites) | **+36** |
| Pruebas unitarias JVM Release (`:app:testReleaseUnitTest`) | **224 / 224** | **260 / 260** (51 suites) | **+36** |
| Regresiones UI / Accesibilidad Robolectric (`UiThemeRegressionTest`, `SkillPracticeRegressionTest`, `ScreenshotTest`) | **PASS** (13 capturas) | **PASS** (17 capturas: 13 base + 4 nuevas V3) | **+4 pantallas verificadas** |
| Política de seguridad CI (4 greps de motor/puentes/TLS) | **4 / 4 PASS** | **4 / 4 PASS** | **0 violaciones** |
| Verificación R8 y paquete (`check_release_reflection.py`, `check_apk_identity.py`) | **PASS** | **PASS** (83 clases de arranque OK en ambos ABI) | **0 regresiones** |

### B. Microbenchmarks JS reproducibles (Node.js v24.22.3, Linux x64 — entorno host)

| Benchmark (`app/src/test/js/benchmarks/run-benchmarks.cjs`) | Línea base (`a6a484e`) | Evolución V3 | Variación |
| --- | --- | --- | --- |
| `PhantomShield network-engine.js` (10 000 iter., 312 reglas) | `94.03 ms` (`9.40 µs/op`) | `86.59 ms` (`8.66 µs/op`, `115 493 ops/s`) | `-7.9 %` (dentro de margen host) |
| `Nexo AI ai-page-agent.js` (200 iter., 1 000 nodos / 200 trampas) | `117.27 ms` (`0.586 ms/op`) | `101.48 ms` (`0.507 ms/op`) | `-13.5 %` (dentro de margen host) |
| `Page Translator page-translator.js` (100 iter., 500 párrafos) | `108.50 ms` (`1.085 ms/op`) | `115.93 ms` (`1.159 ms/op`) | `+6.8 %` (fluctuación normal host) |
| `Nexo Dark Engine dark-engine.js` (1 000 iter., `bootstrapEarly` + CSS) | `13.89 ms` (`13.89 µs/op`) | `11.67 ms` (`11.67 µs/op`) | `-16.0 %` (dentro de margen host) |

### C. Impacto en tamaño de APK Release (tras R8 y optimización de recursos)

| Artefacto (`:app:assembleRelease`) | Línea base (`a6a484e`) | Evolución V3 | Delta exacto |
| --- | --- | --- | --- |
| `app-arm64-v8a-release-unsigned.apk` | `120 052 469 B` (`114.49 MiB`) | `120 271 289 B` (`114.70 MiB`) | **+218 820 B (`+213.7 KiB`, `+0.18 %`)** |
| `app-armeabi-v7a-release-unsigned.apk` | `91 264 089 B` (`87.04 MiB`) | `91 482 909 B` (`87.24 MiB`) | **+218 820 B (`+213.7 KiB`, `+0.24 %`)** |

> Cero dependencias externas nuevas añadidas en `app/build.gradle.kts`.

---

## 3. Límites técnicos honestos de Cefrium `0.21.0`

1. **Sin mundos V8 aislados para `content_scripts` de terceros:** NXR aísla tokens efímeros en cierres privados y particiona `storage.local` por origen, pero el script comparte DOM/globales de la página.
2. **Sin *service workers* MV3 persistentes, `declarativeNetRequest`, `webRequest`, `chrome.tabs` ni `chrome.cookies` para terceros:** reflejado de forma transparente en `ExtensionCompatibility.API_MATRIX` y en la ficha previa de instalación de Extension Hub.
3. **Sin captura digital de audio DRM/EME ni evasión de *paywalls*:** respetado por diseño en Nexo Media Engine y Nexo Reader.

---

## 4. Protocolo de validación en dispositivo físico Android (pendiente de hardware real)

Las pruebas automatizadas cubren lógica JVM, seguridad, extracción JS, R8, contraste y layout Robolectric, pero **no sustituyen** la verificación física. Al instalar el APK firmado en un teléfono Android real (Android 10–15):

1. **Arranque en frío y navegación adaptable:**
   ```bash
   adb shell am force-stop com.nexo.browser
   adb shell am start-activity -W -n com.nexo.browser/.MainActivity
   ```
   Comprobar cambio entre modos **Clásico**, **Compacto** y **Personalizado**, auto-ocultación suave al deslizar hacia abajo/arriba, apertura de IME sin ocultar la barra y gesto **Predictive Back** en Android 13+.
2. **Nexo Extension Hub en red real:**
   - Abrir `https://chromewebstore.google.com/` y `https://microsoftedge.microsoft.com/addons`, abrir una ficha de extensión y comprobar la barra contextual **«Instalar en Nexo»** (sin alteración visual de la tienda).
   - Instalar una extensión de contenido, verificar los 4 estados (**Descargada → Instalada → Habilitada → Funcionando**) y probar *Buscar actualizaciones*.
3. **Nexo Reader y TTS real:**
   - Abrir un artículo largo, activar **Modo lectura**, probar cambio de fuente/tamaño/tema (Claro/Oscuro/OLED), pulsar **Escuchar** con voces del dispositivo (`0.75x–2.0x`), pausar/detener y verificar lectura sin conexión en Modo Avión.
4. **Nexo Site Control, Tabs Pro y Workspace:**
   - Probar zoom real (`50 %–200 %`), silenciar audio de un vídeo, bloqueo por sitio de Nexo AI, creación/restauración perezosa de un espacio de trabajo (comprobando `adb shell dumpsys meminfo com.nexo.browser`) y exportación/importación Netscape HTML de favoritos.
5. **Rendimiento y estabilidad en sesión prolongada:**
   - Revisar *Ajustes › Mediciones de rendimiento* y *Ajustes › Diagnóstico del motor*, además de `adb logcat -d | grep -E "AndroidRuntime|StrictMode|cefrium"`.
