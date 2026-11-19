# Nexo Flow 4.0 — Implementación

Nexo Flow 4.0 convierte a Nexo AI en un asistente de primera clase, accesible con un
solo toque desde cualquier página, y evoluciona visualmente la pantalla de inicio y el
menú principal sin migrar fuera de Kotlin/XML ni del motor Cefrium (Chromium).

## Cambios implementados

### 1. Botón permanente ✨ de Nexo AI

- `res/layout/activity_main.xml`: nuevo `ImageButton` `aiButton` en la barra superior,
  entre el omnibox y el contador de pestañas.
- Icono propio `res/drawable/ic_nexo_ai_spark.xml` (destello ✨ de Nexo AI).
- `MainActivity.applyTheme()` lo estiliza con `ThemeManager.styleIconButton(active = true)`:
  resaltado con el color de acento del tema activo (Claro, Oscuro y OLED).
- Comportamiento exigido y cumplido:
  - Visible por defecto, sin activar ninguna opción.
  - Funciona en los tres modos de navegación (Clásico, Compacto y Personalizado),
    porque la barra superior no se desmonta en `applyNavigationMode()`.
  - Un toque abre Nexo AI (`NexoAiSheet.show`), sin recargar ni navegar.
  - `contentDescription` accesible (`@string/nexo_ai_action`), objetivo táctil
    `@dimen/touch_target` y sin animaciones costosas.
  - Solo desaparece cuando se oculta toda la barra (pantalla completa), igual que
    el resto de controles.

### 2. Asistente contextual (rediseño de NexoAiSheet)

- Encabezado con el dominio de la pestaña activa y una insignia de estado del
  proveedor («`proveedor · modelo`» o «Sin configurar»).
- El asistente es visible aunque Nexo AI esté apagado: una tarjeta compacta
  «Activar Nexo AI» sustituye al antiguo interruptor que ocultaba todo el panel.
  La hoja ya no parece un formulario de configuración.
- Acciones rápidas mantenidas: Resumir, Explicar selección, Traducir, Extraer
  datos, Enlaces clave y Comparar pestañas, ahora seguidas del campo de pregunta
  libre y del botón de consulta siempre accesible.
- Nueva acción «Reintentar» junto a «Copiar respuesta», reutilizando el último
  prompt (`lastPrompt`/`lastLabel`) sin volver a capturar el contexto.
- Cancelación, errores y gestión de permisos por dominio se conservan intactos.
- Privacidad: abrir el panel no envía nada al proveedor; solo al pulsar una
  acción y con consentimiento + permiso de dominio ya existentes.

### 3. Pantalla de inicio (Nexo Flow 4)

- Saludo dinámico por franja horaria («Buenos días / Buenas tardes / Buenas noches»).
- Subtítulo de identidad «Nexo Flow · navega con tu IA a un toque».
- Nueva tarjeta destacada «Pregúntale a Nexo AI ✨» en acento suave: abre el
  asistente desde el inicio (`startPageAction("ai")`), sin recargar nada.
- Logo más compacto (64→56 dp) y jerarquía más clara del héroe.

### 4. Menú principal

- `MenuSections` crea el grupo destacado «Nexo AI ✨» como primero del menú.
- La entrada del menú usa el icono propio de destello y la etiqueta «Nexo AI ✨».
- Se conservan todas las acciones existentes; ninguna se elimina ni duplica
  (verificado por `menuLeadsWithNexoAiWithoutDroppingAnyExistingAction`).

## Pruebas

- Nueva clase `NexoFlowV4RegressionTest` (Robolectric, corrida dentro de
  `:app:testDebugUnitTest` y `:app:testReleaseUnitTest`):
  1. `permanentAiSparkButtonInflatesVisibleAndAccessibleNextToTheOmnibox`:
     infla `activity_main` y comprueba que `aiButton` existe, está visible,
     tiene descripción accesible y comparte barra con el omnibox.
  2. `menuLeadsWithNexoAiWithoutDroppingAnyExistingAction`: el grupo
     «Nexo AI ✨» es el primero y el particionado conserva todas las acciones.
  3. `aiRemainsSelectableInEveryCustomNavigationBar`: `Action.AI` sigue
     disponible en barras personalizadas con su icono intacto.
- Las pruebas existentes (`BrowserResourcePolicyTest`, `UiThemeRegressionTest`,
  `SkillPracticeRegressionTest`, JS de extensiones, lint) se mantienen sin cambios
  y deben seguir en verde.
- Las pruebas solo pueden ejecutarse en la CI de GitHub Actions (el entorno de
  desarrollo de esta tarea no incluye JDK 25 ni el SDK de Android; no fue posible
  generar capturas de emulador aquí). Validación visual: extraer los PNG de
  `app/build/reports/ui-previews` del artefacto «Nexo-CI-<sha>-transition-false».

## Métricas verificadas

- Sin nuevos servicios, procesos ni animaciones añadidas: el botón reutiliza
  `styleIconButton` (ripple ligero ya existente) y la tarjeta de inicio usa los
  mismos drawables del tema.
- Sin cambios de red, ciclo de vida de Cefrium ni consumo RAM/CPU: todo el
  trabajo es creación de vistas al abrir los paneles.

## Limitaciones pendientes

- Menú contextual del sistema al seleccionar texto («Preguntar a Nexo AI»):
  Cefrium no expone callbacks de selección del sistema; la selección se cubre
  con el alcance «Selección» del asistente. Requiere API pública del motor.
- Capturas antes/después reales deben generarse desde los artefactos de CI o
  un dispositivo/emulador con el APK firmado.
- PNG de comparación de pestañas/workspace/ajustes pendiente hasta disponer
  de entorno con SDK.
