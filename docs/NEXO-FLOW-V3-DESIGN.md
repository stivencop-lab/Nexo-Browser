# Nexo Flow 3.0 — Sistema de Diseño y Arquitectura Visual

> **Versión objetivo:** Evolución V3 sobre Nexo Browser `0.34.0` · Cefrium `0.21.0` (Chromium `156`) · Android 10–16 (`minSdk 29`, `targetSdk 35`, `compileSdk 37.2`).
>
> **Tecnología UI:** 100 % Kotlin + XML / Android Views nativos (sin Jetpack Compose ni dependencias de UI adicionales).

---

## 1. Principios de Nexo Flow 3.0

Nexo Flow 3.0 consolida una identidad visual propia, contemporánea y coherente con las guías de diseño de Android y Material 3, sin copiar interfaces de terceros ni sacrificar rendimiento:

1. **Legibilidad y contraste verificables:** texto principal AAA ($\ge 7{,}0{:}1$), texto secundario, acentos y estados semánticos AA ($\ge 4{,}5{:}1$), verificados automáticamente en `BrowserColorsTest`.
2. **Cuatro temas reales:** **Claro**, **Midnight (Oscuro)**, **OLED negro puro (`#FF000000`)** con jerarquía ascendente de luminancia, y **Color dinámico** (Android 12+ con respaldo Cobalto en API 29–30), más 7 acentos curados en `ThemeManager` y `BrowserColors`.
3. **Cero costes gráficos innecesarios:** sin `RenderEffect` blur en capas sobre Chromium, sin transparencias excesivas ni sombras profundas en modo oscuro, preservando fluidez en el renderizador y en los reproductores multimedia.
4. **Respeto estricto al movimiento reducido:** toda animación del chrome pasa por `NexoUi.animDuration(context, baseMs)` que lee `Settings.Global.ANIMATOR_DURATION_SCALE` y colapsa a `0 ms` cuando el usuario desactiva animaciones.
5. **Accesibilidad real y escalado 200 %:** objetivos táctiles mínimos de `48 dp` (`@dimen/touch_target`), alineación consistente en filas (`16 dp` icono / `68 dp` texto / `>= 64 dp` altura), `contentDescription` claros, `FLAG_SECURE` propagado a hojas en modo privado (`WindowPrivacy`) y verificación a escala de fuente `2.0x` en `UiThemeRegressionTest` y `SkillPracticeRegressionTest`.

---

## 2. Primitivas y tokens unificados (`NexoUi`, `ThemeManager`, `NexoSheets`)

| Primitiva | Archivo | Uso y garantías |
| --- | --- | --- |
| `NexoUi.Tone` (`NEUTRAL`, `ACCENT`, `SUCCESS`, `WARNING`, `DANGER`, `INFO`) | `NexoUi.kt` | Tono semántico unificado que devuelve `(fondo, texto)` desde `BrowserPalette` con contraste AA/AAA. |
| `NexoUi.statusPill()` | `NexoUi.kt` | Insignia compacta para estados (`HTTPS`, `Activa`, `Fijada`, `Duplicada ×N`, `Funcionando`, `Bloqueado`). |
| `NexoUi.metricTile()` | `NexoUi.kt` | Tarjeta compacta de métrica numérica (`fontFeatureSettings = "tnum"`) para contadores reales. |
| `NexoUi.emptyState()` | `NexoUi.kt` | Estado vacío/error estandarizado con burbuja de icono, título, descripción y acción opcional. |
| `NexoUi.searchBar()` | `NexoUi.kt` | Barra de búsqueda tipo píldora con icono, filtrado en vivo y botón de limpieza de `48 dp`. |
| `NexoUi.divider()` | `NexoUi.kt` | Separador uniforme de `1 dp` con `outlineVariant`. |
| `NexoUi.actionRow()` / `switchRow()` / `chip()` / `chipRow()` | `NexoUi.kt` | Filas de acción (keylines `16/68 dp`), interruptores accesibles y chips seleccionables. |
| `NexoSheets.show()` / `primaryActions()` / `grid()` | `NexoSheets.kt` | Hojas inferiores nativas con ancho acotado (`640 dp` en tablet), `ContentColumn` (`720 dp`) y `WindowPrivacy`. |
| `ThemeManager.styleStatusChip()` / `styleSearchPill()` | `ThemeManager.kt` | Estilos unificados para barras de búsqueda y píldoras entre Activities y hojas. |

Iconografía vectorial unificada (`24×24 dp`, trazo limpio `1.8–2 dp`, tintado con tokens del tema):
`ic_menu_ai`, `ic_menu_bookmark`, `ic_menu_extensions`, `ic_menu_reader`, `ic_menu_shield`, `ic_menu_volume_off`, `ic_menu_volume_on`, `ic_menu_workspace`.

---

## 3. Navegación adaptable (`NavConfig`, `NavCustomizerSheet`, `MainActivity`)

Nexo Flow 3.0 introduce tres modos de barra principal configurables desde el menú de pestañas, Nexo Command (`@actions`) o *Ajustes › Navegación*:

- **Clásico (`classic`):** barra de direcciones superior/inferior + barra de 5 acciones predeterminadas (`Atrás`, `Adelante`, `Inicio`, `Recargar`, `Nueva pestaña`).
- **Compacto (`compact`):** oculta el dock inferior e integra un botón `Atrás` compacto (`compactBackButton`) en la barra principal para maximizar el área web.
- **Personalizado (`custom`):** permite elegir y reordenar entre **3 y 6 acciones** (`Atrás`, `Adelante`, `Inicio`, `Recargar`, `Nueva pestaña`, `Historial`, `Descargas`, `Nexo AI`, `PhantomShield`, `Extensiones`, `Modo lectura`, `Favoritos`).

### Comportamiento inteligente y protección ergonómica
- **Auto-ocultación al desplazarse (`NavConfig.shouldAutoHideDock`):** detecta desplazamiento descendente `>= 120 px` desde `page-bridge.js` y oculta el dock con histéresis de `350 ms` y recuperación instantánea al subir.
- **Protección frente a interrupciones:** **nunca** oculta controles cuando el usuario edita la URL, busca en la página, tiene el teclado IME abierto, está en la página de inicio, en pantalla completa o en PiP.
- **Insets y Predictive Back (Android 13+):** `WindowInsetsCompat` combina `systemBars`, `displayCutout` e `ime` una sola vez; `OnBackInvokedCallback` solo se registra mientras Nexo tiene historial interno, búsqueda, foco en omnibox o pantalla completa, liberando el gesto del sistema en la raíz.

---

## 4. Pantallas evolucionadas en V3

1. **Página de inicio modular (`StartPageView`):** bloques activables desde el botón *Personalizar* (`Accesos rápidos` con reordenamiento, `Favoritos`, `Espacios de trabajo`, `Grupos activos`, `Lectura sin conexión` y tarjeta de `PhantomShield`).
2. **Nexo Command (`NexoCommandEngine` + `OmniboxSuggestions`):** resultados diferenciados visualmente con etiquetas por fuente (`Pestaña`, `Grupo`, `Favorito`, `Acceso`, `Acción`, `Ajuste`, `Historial`, `Buscar`) y prefijos opcionales (`@tabs`, `@history`, `@actions`, `@settings`, `@bookmarks`).
3. **Nexo Tabs Pro (`TabSwitcher` + `TabProModel`):** búsqueda instantánea, 3 modos de vista (**Cuadrícula**, **Compacta**, **Lista**), pestañas fijadas con insignia y sin retener RAM permanente, detección no destructiva de URLs duplicadas con filtro rápido y reordenamiento fluido.
4. **Nexo Site Control (`SiteControlSheet` + `SiteControlModel`):** panel contextual de 5 secciones (*Sitio y conexión*, *Privacidad*, *Experiencia*, *Extensiones*, *IA*) conectado a APIs públicas de Cefrium (`setZoomLevel`, `setAudioMuted`, `SitePermissionStore`, `PhantomShield`, `DarkEngine`, `NxrRuntime`, `NexoAiConfig`). Nunca afirma que HTTPS garantice seguridad absoluta.
5. **Nexo Extension Hub (`ExtensionsActivity` + `ExtensionHubSheets`):** búsqueda directa, tiendas Chromium (Chrome Web Store / Edge Add-ons), archivo local, filtros por estado real (**Descargada / Instalada / Habilitada / Funcionando**), matriz de compatibilidad y actualizaciones transaccionales.
6. **Nexo Reader (`NexoReader`):** vista de lectura nativa 100 % saneada (sin WebView ni HTML crudo), controles tipográficos sincronizados con tema Claro/Oscuro/OLED, lectura en voz alta mediante `TextToSpeech` de Android y biblioteca sin conexión acotada.
7. **Pantallas secundarias (`SettingsActivity`, `HistoryActivity`, `DownloadsActivity`, `BookmarksActivity`, `NexoAiSheet`):** hojas Flow 3.0 en pulsación larga, exportación/importación estándar de favoritos, permisos por sitio de Nexo AI visibles y respetados, y enlaces compartidos libres de parámetros de rastreo.

---

## 5. Validación visual y de accesibilidad

- **Regresiones automatizadas:** `UiThemeRegressionTest` (4 temas a escala `2.0x`, tablets `1000×900 dp`, color dinámico, acento de diálogos), `SkillPracticeRegressionTest` (keylines `16/68 dp`, estados `checkable`/`checked`, `FLAG_SECURE`, dependencias en Ajustes) y `BrowserColorsTest` (ratios WCAG AAA/AA).
- **Capturas de referencia:** generables en cualquier momento con:
  ```bash
  ./gradlew :app:testDebugUnitTest -PnexoScreenshots -PnexoShotsDir=/tmp/nexo-shots \
    --tests '*ScreenshotTest' --tests '*UiThemeRegressionTest'
  ```
