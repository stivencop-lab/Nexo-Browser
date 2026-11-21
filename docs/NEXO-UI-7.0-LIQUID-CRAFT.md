# Nexo UI 7.0 «Liquid Craft»

> Referencia histórica de UI 7.0. La interfaz de v0.39.2 usa UI 9.0 «Liquid Glass». [Cambios de interfaz en v0.39.2](AUDITORIA-0.39.2.md#interfaz-liquid-glass)

Rediseño visual de Nexo Browser sobre la arquitectura existente (Kotlin + Android Views, Cefrium).
No cambia la lógica de navegación, PhantomShield, NXR ni Nexo AI (`NexoAiController`, `NexoAiStore`
y `PageBinding` intactos: minimizar sigue sin cancelar el SSE).

## Principios (anti-estética IA)

- Sin chispas de 4 puntas: `ic_nexo_ai_spark` e `ic_menu_ai` se eliminan. El emblema es
  **Nexo Aperture** (`ic_nexo_aperture.xml` estático y `NexoApertureDrawable` animado): dos arcos
  concéntricos (trazo 1.7dp, extremos redondos) y un punto central que respira durante el streaming.
- Sin degradados de marca: el icono de la app, el emblema y la notificación usan el monograma «N»
  geométrico monocromático (`tools/generate_brand_assets.cjs`). Lienzo #09090B, tinta #F4F4F5.
- Tipografía: Plus Jakarta Sans (400 cuerpo, 500 controles, 600 encabezados con −0.025em).
  `fontBold()` queda limitado a 600. JetBrains Mono con `tnum` solo para métricas.
- Sin ALL-CAPS, sin cajas con borde 1dp dentro de cajas, sin cuadrados de color tras iconos,
  sin subtítulos obvios. Iconos lineales de 20dp con trazo de 1.6dp reales
  (`tools/generate_line_icons.py`).

## Tokens (`BrowserColors.kt`)

| Tema | canvas | surface | elevated | ink | muted |
|---|---|---|---|---|---|
| Obsidian | #09090B | #131316 | #1B1B20 | #F4F4F5 | #8E8E98 |
| OLED | #000000 | #0E0E11 | #17171C | #F4F4F5 | #8E8E98 |
| Paper | #F6F5F1 | #FFFFFF | #ECEAE4 | #111113 | #64646D* |

\* El brief pedía #6E6E77, que solo alcanza 4.1:1 sobre #ECEAE4; #64646D conserva el gris cálido
y cumple AA en todas las superficies de Paper.

Acentos: Monocromo (la tinta del tema), Azul Señal #2563EB, Cobalto #3B82F6, Naranja Internacional
#F97316 y Salvia #16A34A. El color de marca (`accentSwatch`) se usa tal cual en pistas, aros y
puntos; el rol de texto/acción (`accent`) desplaza su tono HCT hasta 4.5:1 sobre todas las
superficies del modo. `BrowserColorsTest` y `NativeThemeTokensTest` lo verifican para cada acento
y modo. Los acentos de UI 6.x se migran al leerlos (`BrowserPrefs.migrateAccent`).

`values/colors.xml` se genera desde `BrowserColors` (`NEXO_WRITE_TOKENS=true` en
`NativeThemeTokensTest`) y `values*/themes.xml` con `tools/generate_native_themes.py`.

## Forma y material (`NexoUi.Tokens`, `NexoGlass.kt`)

- Radios concéntricos: hoja 34dp, tarjetas y miniaturas 22dp (= 34 − 12 de padding), sitios
  fijados 20dp (52×52), controles 14dp o píldora 999dp.
- `NexoGlassDrawable`: en API 31+, si el fondo es una vista nativa (Inicio), se graba en un
  `RenderNode` con `RenderEffect.createBlurEffect(26, 26, CLAMP)` bajo una capa al 72 % (oscuro)
  / 78 % (Paper). Sin desenfoque posible (API < 31 o la superficie de Chromium) usa 92 %.
  Borde especular superior de 0.5dp (blanco 22 % / 95 %); en Paper, además, un contorno de tinta
  al 6 % para que el cristal blanco no desaparezca sobre páginas blancas.
- Las hojas usan `FLAG_BLUR_BEHIND` (desenfoque real de lo que hay detrás, incluida la superficie
  de Chromium) cuando el sistema lo permite; Nexo AI además escala la web al 96 %.
- Movimiento: `NexoMotion` (SpringAnimation, rigidez 380, amortiguación 0.82) en hojas, dock,
  Scroll Compacto, píldora de Nexo AI, indicador de pestañas e interruptores. Respeta la
  reducción de movimiento del sistema.

## Navegación: Liquid Split Chrome

- `activity_main.xml` es un `FrameLayout` por capas: página → velo de barra de estado → avisos →
  cromo inferior. `MainActivity.layoutEdgeToEdge()` reparte cada inset una sola vez.
- Píldora de cristal `[candado] dominio [recargar]` + clúster desacoplado
  `[Aperture] [pestañas] [•••]`. Deslizar la píldora cambia de pestaña (con seguimiento elástico)
  y hacia arriba abre el deck.
- Scroll Compacto: al bajar, el cromo se pliega en una mini-píldora centrada de 34dp
  (objetivo táctil de 48dp) con candado y dominio; al subir o tocarla, vuelve.
- Modo privado: cristal ahumado e icono eye-off en la píldora; solo Burner muestra un chip (abre el buzón).
- Dock Clásico refinado disponible en Configuración › Navegación › Barra inferior.
- **Decisión consciente:** la página web fluye por detrás del cromo inferior, pero empieza bajo la
  barra de estado. Cefrium 0.21.0 no expone insets de viewport, así que hacerla pasar bajo la barra
  de estado taparía la cabecera de cualquier sitio sin `viewport-fit=cover`. La página de Inicio
  (nativa) sí llega hasta arriba.

## Pantallas

- **Inicio** (`StartPageView`): lavado radial al 5 %, píldora de PhantomShield con contador real
  (nunca inventado), iconos fantasma, monograma + saludo, cuadrícula 4×2 de squircles y tira de
  Grupos/Espacios. Sin tarjeta promocional de IA. «Inicio minimalista» oculta favoritos y lecturas.
- **Nexo AI**: «Dynamic Live Pill» ahumada con punto que respira y barras fluidas; hoja con
  pestañas de línea deslizante; chat editorial sin burbujas («Tú · dominio», respuesta directa,
  acciones fantasma, telemetría plegable en mono); compositor en píldora con «Página ▾» y ↑.
  Comparar: tarjetas waterfall (TTFT + generación) con cifras tabulares.
- **Pestañas**: deck de 2 columnas con capturas a sangre completa, degradado inferior, aro de
  acento y chip «Activa»; filtros por grupo; barra inferior de cristal `[Pestañas | Privadas]` + «+».
- **Menú**: tres píldoras de sesión sin subtítulos, tira de utilidades y listas agrupadas.
- **Control del sitio**: dominio y conexión expandible, interruptores inmediatos (PhantomShield con
  contador, Tema oscuro), filas limpias; permisos y cookies en una subhoja.
- **Extensiones**: con 0 CRX no hay cajas 0/0/0 ni filtros vacíos; se prioriza Chrome Web Store,
  Edge Add-ons, archivo .crx y los módulos integrados. Con CRX: franja compacta, tarjetas con
  interruptor y acciones Popup/Opciones.
- **Configuración**: búsqueda, pestañas de categoría con línea deslizante, selectores en vivo de
  Tema y Acento y guardado automático con toast de cristal «Guardado» (sin botón inferior).

## Accesibilidad

- Texto principal ≥ 7:1, secundario y acciones ≥ 4.5:1 en todas las superficies y acentos
  (pruebas JVM). Controles con objetivos de 48dp (segmentados, muestras de acento, mini-píldora,
  acciones fantasma y cerrar pestaña); `UiThemeRegressionTest` los comprueba al 200 % de fuente.
