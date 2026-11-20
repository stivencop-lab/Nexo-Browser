# Nexo AI 5.1 — Arquitectura, Persistencia, Minimización y Laboratorio Multimodelo

Documento técnico de la evolución **Nexo AI 5.1** implementada sobre la rama `feat/nexo-ai-5.1`.

## 1. Diagnóstico inicial (qué fallaba antes)

En el estado previo en `main`:
- `NexoAiSheet.show()` creaba todo el estado conversacional (`activeCancel`, `requestGen`, historial visual) dentro del método que construía el `Dialog`.
- `sheet.setOnDismissListener` cancelaba `activeCancel` al cerrar o bajar el panel, interrumpiendo cualquier consulta en curso.
- Al volver a abrir Nexo AI se construía un diálogo nuevo vacío y el historial desaparecía.
- `NexoSheets.configureAndShow()` desactivaba las animaciones (`setWindowAnimations(0)`) provocando una aparición abrupta.
- `NexoAiClient.execute()` realizaba únicamente llamadas HTTP síncronas sin soporte de streaming SSE real ni medición monotónica detallada.

---

## 2. Arquitectura de conversaciones persistentes

Se separó completamente el ciclo de vida del asistente respecto a `Activity`, `Dialog` y las vistas Android:

- **`NexoAiModels.kt`**: modelos inmutables (`AiConversation`, `AiMessage`, `MessageStatus`, `PageBinding`, `AiMetrics`, `PanelState`, `IndicatorState`, `ControllerSnapshot`).
- **`NexoAiStore.kt`**: almacén local persistente basado en `File` (`filesDir/nexo_ai/conversations.json`) con escritura atómica (`tmp` + rename), tope acotado (25 hilos / 80 mensajes por hilo) y sin almacenar jamás claves API ni cabeceras de autorización.
  - **Recuperación honesta tras muerte del proceso**: al recargar desde disco, cualquier mensaje que hubiera quedado en estado `SENDING` o `STREAMING` se marca automáticamente como `INTERRUPTED` con su `retryPrompt` intacto para que el usuario pueda reintentarlo con un toque.
- **`NexoAiController.kt`**: controlador observable independiente de las vistas:
  - Utiliza únicamente `applicationContext` (nunca retiene referencias de `Activity`, `Dialog` ni vistas dentro de operaciones de red).
  - Mantiene múltiples hilos de conversación, cada consulta con su propio `requestId` y `NexoAiClient.CancelHandle`.
  - **Minimizar jamás cancela**: `NexoAiSheet` solo se desuscribe del controlador y pasa a `PanelState.MINIMIZED`; la consulta continúa en segundo plano y al finalizar actualiza el estado del indicador a `READY`.
  - **Cancelación explícita aislada**: `cancelRequest(requestId)` detiene únicamente esa solicitud sin afectar otros hilos ni comparaciones del laboratorio.
  - **Seguridad del agente de página (`PageBinding`)**: cada consulta contextual vincula `(tabId, url, host, title, documentGeneration)`. Si el usuario navega a otra URL o pestaña mientras llega la respuesta, la conversación se conserva íntegra, pero `NexoAiPageAgent.confirmAndExecuteAction()` bloquea cualquier acción de DOM (`highlight`, `fill_draft`) que pertenezca a una página anterior.

---

## 3. Experiencia visual 3 estados, botón ✨ y vista minimizada

- **Botón ✨ rediseñado (`ic_nexo_ai_spark.xml`, `ThemeManager.styleAiSparkButton`)**:
  - Nueva iconografía geométrica multi-destello con área táctil cómoda (`44dp × 48dp`).
  - Estados visuales diferenciados: `IDLE`, `GENERATING`, `READY` (punto indicador verde) y `ERROR`.
  - Microanimación breve al pulsar (`animateAiSparkTap`) respetando `ValueAnimator.areAnimatorsEnabled()` (sin animaciones infinitas).
- **Panel adaptable de 3 estados (`NexoSheets.showAdaptiveFixed`, `SheetGestures.kt`, `NexoAiSheet.kt`)**:
  - **Minimizado → Panel parcial (66 %) → Pantalla expandida (94 %)** mediante botón en cabecera o gestos verticales hacia arriba/abajo, sin reconstruir la conversación ni cancelar peticiones.
  - Transición suave de entrada (`animateEnter`) y cierre (`dismissAnimated`) respetando reducción de movimiento.
  - Selector segmentado en cabecera: **Chat** | **Comparar** | **Ajustes**, más carril de hilos recientes (`+ Nueva` sin borrar conversaciones anteriores).
- **Indicador flotante minimizado in-app (`NexoAiPillView`)**:
  - Integrado dentro del `browserFrame` de `MainActivity` (no requiere permiso de overlay del sistema).
  - Muestra estado en tiempo real (*Generando…*, *Respuesta lista*, *Error*, *Retomar chat*), restaura la conversación en un toque y ofrece un cierre discreto `×` que oculta la píldora sin borrar el historial.

---

## 4. Cliente de IA y Laboratorio multimodelo (Nexo AI Benchmark)

- **Streaming SSE real (`NexoAiClient.executeStreaming`)**:
  - Soporta SSE real para APIs compatibles con OpenAI (`stream: true`, `stream_options.include_usage`) y Anthropic Claude (`content_block_delta`, `message_start`, `message_delta`), con reintento automático sin `stream_options` si un servidor local/proxy lo rechaza con HTTP 400.
  - Si un proveedor responde `application/json` en lugar de `text/event-stream`, procesa la respuesta normalmente con `streamed = false` y `timeToFirstTokenMs = null` (nunca simula streaming ni inventa TTFT).
  - Descubrimiento de modelos vía `GET /models` (`fetchAvailableModels`) con respaldo sugerido etiquetado con transparencia.
- **Laboratorio Comparar modelos (`NexoAiBenchmark.kt`, `NexoAiBenchmarkView.kt`)**:
  - Permite elegir conexión API, cargar modelos, seleccionar entre **2 y 5 modelos**, escribir un prompt único y configurar nivel de razonamiento (aplicado solo cuando el modelo soporta `reasoning_effort`).
  - **Privacidad y costos**: aviso explícito de consumo de cuota/créditos; el contexto de página está desactivado por defecto y solo se adjunta si el usuario activa el interruptor explícitamente.
  - **Equidad y métricas reales**: concurrencia acotada a un máximo de **2 solicitudes simultáneas**, reloj monotónico (`SystemClock.elapsedRealtimeNanos()`), diferenciación entre latencia de red, primer token (solo streaming real), tiempo de generación y duración total; `tokens/s` calculado únicamente si existen tokens reales de salida (`>= 5`) y duración medible (`>= 100 ms`).
  - **Repeticiones y tolerancia a fallos**: calcula la mediana exacta por modelo al repetir una prueba; un error HTTP (429/404/timeout) o cancelación individual nunca detiene los demás modelos; señala el modelo más rápido de la ejecución sin atribuirle mayor inteligencia.

---

## 5. Limitaciones honestas

- **Muerte del proceso por Android**: si el sistema operativo cierra el proceso de Nexo mientras una solicitud de red está abierta, la petición HTTP se interrumpe (no se utiliza `WorkManager` persistente para mantener streams SSE vivos en segundo plano). Al reabrir, el historial completado se conserva y el mensaje afectado aparece marcado como *Interrumpida* con botón **Reintentar**.
- **Validación visual**: las capturas generadas en este entorno corresponden a **Robolectric Native Graphics** (no a un emulador ni dispositivo físico Android). Las animaciones táctiles y la interacción con el teclado físico/IME requieren verificación adicional en un teléfono Android real.

---

## 6. Verificación ejecutada

- Política de seguridad (`git grep` de APIs prohibidas): **`SECURITY_GREP_CLEAN`**.
- Pruebas JS (`node --test app/src/test/js/*.test.cjs`): **263/263 OK**.
- Pruebas JVM (`:app:testDebugUnitTest` y `:app:testReleaseUnitTest`, incluyendo `NexoAi51Test`): **274/274 OK**.
- Pruebas Robolectric UI, tema, accesibilidad 200 % y capturas (`UiThemeRegressionTest`, `SkillPracticeRegressionTest`, `ScreenshotTest`): **22/22 OK**.
- Compilación y Lint: `assembleDebug`, `lintDebug`, `assembleRelease` (`arm64-v8a` + `armeabi-v7a`), `check_release_reflection.py`, `check_apk_identity.py` y `lintRelease` en verde.
