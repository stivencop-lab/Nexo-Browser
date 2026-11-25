# Subtítulos en vivo en Nexo

Reproduce un video y toca **CC** para activar los subtítulos. Con CC activo, otro toque abre la hoja rápida para elegir idioma, cambiar qué texto se muestra o apagar los subtítulos. Mantén pulsado **CC** para abrir la configuración completa. También puedes abrirla desde **Configuración › Subtítulos en vivo › Configurar subtítulos y traducción**.

La guía describe la rama `main`. La captura más frecuente, los cambios de hablante y el botón **Reintentar** se incorporaron después del APK publicado como `v0.40.0`. [Cambios de la PR #87](https://github.com/stivencop-lab/Nexo-Browser/pull/87)

## Elegir la fuente

| Fuente | Cuándo se usa | Datos que procesa |
| --- | --- | --- |
| Pista del video | El reproductor expone una pista legible, por ejemplo WebVTT. | Nexo muestra las líneas por el reloj del video. Con traducción activada, prepara por adelantado las líneas disponibles. |
| Texto del reproductor | El sitio dibuja los subtítulos en una capa que Nexo puede leer. | Nexo muestra el texto conforme aparece. El acceso depende del reproductor y de cómo exponga sus subtítulos. |
| Audio del reproductor | El video carece de texto legible y autorizas la transcripción con una clave de Deepgram. | Nexo copia y envía a Deepgram el audio que el reproductor permite capturar. |
| Captura universal de Android | El audio directo de la página falla o eliges esta fuente desde la hoja de CC. | Android entrega el audio multimedia permitido del dispositivo. Puede incluir sonido de otras aplicaciones. Requiere los permisos del sistema y una clave de Deepgram. |

Las dos fuentes de texto funcionan sin clave de Deepgram. En **Mostrar**, elige **Original**, **Traducido** o **Ambos**. Google, DeepL y los modelos de IA reciben texto cuando se usa traducción. La traducción anticipada y el respaldo de IA pueden usar Google. Si configuras dos modelos de IA en paralelo, ambos pueden recibir el mismo lote.

## Transcribir audio

1. Abre los ajustes de subtítulos, guarda tu clave de Deepgram y elige el idioma del video. **Detección automática** sirve para audio multilingüe.
2. Vuelve al video y activa **CC**. Si hace falta transcribir audio, Nexo pide permiso. La opción **Transcribir el audio sin preguntar** permite autorizar esa fuente de forma automática y viene apagada.
3. Si el sitio impide capturar su audio, abre la hoja de **CC**, elige **Capturar todo el audio con Android** y autoriza la captura de reproducción. Durante la captura aparece una notificación con la acción **Detener**. Apagar CC o cambiar de pestaña detiene esa captura.
4. En los ajustes puedes configurar el **Límite diario de Deepgram** y activar **Detectar cambios de hablante**. Esta última opción viene apagada. Una raya marca los cambios de voz confirmados y Deepgram puede cobrar el complemento. La precisión depende del audio, especialmente si varias voces se superponen. [Detección de hablantes](https://developers.deepgram.com/docs/diarization) · [Precios de Deepgram](https://deepgram.com/pricing)

Android solicita permiso de audio y autorización mediante MediaProjection para capturar la reproducción. Nexo usa esa ruta para leer audio multimedia, sin tomar audio del micrófono ni imágenes de la pantalla. El sistema o el contenido protegido pueden impedir la captura.

## Cómo se reduce la espera

La captura web de Chromium/Cefrium usa `captureStream()` y solicita a `MediaRecorder` fragmentos Opus de 125 ms. La entrega puede variar según el codificador y el dispositivo. Se valida el contenedor WebM u Ogg del primer fragmento. La captura universal usa bloques PCM de aproximadamente 100 ms, remuestreados a 16 kHz mono.

El texto parcial aparece al recibirlo. Si el resultado definitivo solo cubre el principio de la frase, Nexo conserva visible la parte pendiente que tiene marcas de tiempo posteriores. Las pausas entre palabras y los cambios de hablante se fijan con resultados confirmados. En modo de una línea, se da prioridad al texto más reciente. [Resultados parciales de Deepgram](https://developers.deepgram.com/docs/using-interim-results)

Con una pista legible, Nexo puede preparar traducciones futuras porque ya dispone del texto con sus tiempos. Con audio en directo, la captura recibe el sonido conforme se reproduce. La red, el reconocimiento y el traductor añaden sus propios tiempos. En una prueba con audio sintético en Chromium de escritorio, la mediana entre fragmentos bajó de 283 a 130 ms. Esa medida cubre la entrega de fragmentos del capturador. [Resultado en la PR #87](https://github.com/stivencop-lab/Nexo-Browser/pull/87)

## Diagnóstico

| Lo que ves | Qué comprobar |
| --- | --- |
| **CC** no aparece | Activa **Botón CC en los videos** en Configuración, sal de PiP o del modo inmersivo y reproduce audio o video en la pestaña. |
| El video tiene subtítulos, pero Nexo no los muestra | Activa los subtítulos del propio reproductor. Si el sitio no expone su texto a Nexo, prueba un video con pista WebVTT conocida antes de usar transcripción. |
| Hay bytes, pero aún no aparece texto | Comprueba que el audio contiene voz, que el idioma del video coincide y que la clave y el saldo están vigentes. El medidor indica señal de audio, incluso durante música o ruido. |
| Hay cortes o aparece un error de conexión | Abre **CC** y toca **Reintentar** cuando esté disponible. Con WebM u Ogg, una reconexión empieza una grabación con una cabecera nueva para mantener la integridad del flujo. |
| El original aparece antes que la traducción | La traducción tiene su propio tiempo de respuesta. **Solo traducido**, disponible en la configuración de subtítulos, oculta el original mientras espera la traducción. Si el proveedor falla, Nexo puede mostrar el original. |

El diagnóstico del motor registra el tiempo entre la llegada del primer fragmento de audio al flujo nativo y el primer texto recibido. Esa medida de arranque puede incluir el silencio inicial. Las claves se usan en el proceso nativo y se envían al proveedor en la cabecera de autenticación. Al informar un problema, indica versión, dispositivo, sitio, tipo de captura y estado de CC. Omite las claves y el audio o texto privado.

## Verificación para desarrollo

```bash
node --test app/src/test/js/pip-bridge.test.cjs
./gradlew :app:testDebugUnitTest \
  --tests '*CaptionProtocolTest' --tests '*CaptionsLatencyTest'
./gradlew :app:testDebugUnitTest -PnexoScreenshots \
  --tests '*ScreenshotTest.captionsOverlay' --tests '*ScreenshotTest.captionsPositions'
```

Los dobles de JavaScript cubren el orden de los fragmentos y el ciclo de captura. Las pruebas JVM cubren protocolo, pausas, cambios de voz, texto parcial y traducción. Robolectric comprueba el dibujo y la posición. Para medir la latencia completa, usa un Android ARM con una clave propia y un clip con silencios y dos hablantes. Registra el desfase entre el inicio de la voz y el texto visible, y repite en captura web y universal. Prueba también silencio, conversación solapada, red intermitente, pausa, PiP y cierre de CC. [Entorno de desarrollo](CEFrium-CI-SETUP.md) · [Captura de audio en Android](https://developer.android.com/media/platform/av-capture) · [MediaRecorder](https://developer.mozilla.org/en-US/docs/Web/API/MediaRecorder)
