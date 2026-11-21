# Auditoría de Nexo Browser v0.39.2

Base auditada `7750425` (v0.39.1). Motor Chromium 156 con Cefrium 0.21.0, minSdk 29 y targetSdk 35. La maqueta HTML facilitada describe el objetivo visual y simula la reanudación.

| Área | Cambio en esta rama | Verificación pendiente |
| --- | --- | --- |
| Liquid Glass | Paleta, tipografía y radios compartidos. Isla de 56 dp y satélite de pestañas sobre el contenido web. Captura compartida a un cuarto de escala, RenderEffect desde API 31 y StackBlur en API 29–30. La intensidad se guarda en DataStore. | Trazas de scroll a 120 Hz y comprobación visual del cristal y ventanas en API 29, 31 y 35. |
| Reanudación | Snapshot en onPause, validación del SurfaceHolder, reactivación al recibir foco, fundido de 90 ms y reconstrucción limitada tras 450 ms. El estado de navegación de Chromium incluye la posición. El flujo existente de recuperación del renderer conserva la última posición de scroll observada. | Diez ciclos por API, con vídeo, PiP y páginas de color uniforme. Medir negro visible y latencia de copia. |
| Memoria | Descarte tras cinco minutos de inactividad para pestañas aptas, miniaturas WebP con lectura de JPEG anteriores y caché dimensionada a 1/10 del heap. Precreación de un host Cefrium del perfil normal en dispositivos con más de 4 GiB. | Medir PSS de todos los procesos y latencia de pestaña nueva. Cefrium gestiona si el renderer se comparte o se crea de nuevo. El reciclaje de bitmaps que comparten el selector y el historial de cierres requiere controlar sus referencias antes de usar inBitmap. |
| Extensiones | CRX3 de múltiples firmas en una sola pasada de lectura. Popups y opciones con origen por extensión y viewport adaptable. DNR estático de bloqueo y excepción integrado en el interceptor de PhantomShield. El UA automático se activa por destino principal HTTPS de tienda y se restablece al salir. | Verificar ejecución de popups en el APK. El override de UA es de Cefrium por navegador y se hereda en subrecursos de la tienda. Los Service Workers MV3 de extensión, los Ports con direccionamiento por marco, las áreas de almacenamiento sincronizadas entre contextos, DNR dinámico y el conjunto de 28 API siguen pendientes. |

## Límites del motor y del puente

- En modo Surface, Cefrium ofrece `setPageVisible`, `setFocus`, `invalidate` y una vista con su propio ciclo de superficie. El callback de pintura del SDK pertenece al modo off-screen. PixelCopy comprueba si existe un búfer legible y detecta una muestra negra, con una excepción para páginas que pintan negro legítimamente. La lectura por sí sola carece de un identificador de swap de Chromium. La pérdida de GPU se cubre cuando invalida la superficie durante una reanudación. El SDK carece de un callback público dedicado de GPU.
- El prearranque conserva el perfil de destino y abre un navegador sin historial, por lo que puede usarse en una pestaña nueva o restaurada. El ahorro de RAM, la apertura en menos de 80 ms y la ausencia de interrupciones requieren medición física.
- StrictMode depura lecturas y escrituras de disco con registro y penaliza la red en el hilo principal en debug. DataStore se usa para la nueva preferencia de intensidad. El resto de las preferencias también interviene en la transferencia de perfil y conserva su formato existente.
- DNR aplica reglas estáticas indexadas por dominio para subrecursos. Evalúa excepciones por prioridad por extensión y conserva las exclusiones del usuario por sitio y para modo privado. Las acciones de redirección y modificación de cabeceras, expresiones regulares de manifiesto y reglas dinámicas tienen compatibilidad pendiente. Si una excepción requerida queda fuera de la gramática, el compilador omite ese conjunto de reglas de la extensión.

## Paquetes oficiales verificados en JVM

| Paquete | Resultado del instalador |
| --- | --- |
| Dark Reader 4.9.135 | CRX3/MV3 con tres firmas verificadas. Se extrajo `ui/popup/index.html`. El manifiesto requiere `background/index.js` como Service Worker MV3. |
| uBlock Origin Lite 2026.1116.1650 | CRX3/MV3 con tres firmas verificadas. El compilador admitió 11.624 reglas estáticas y bloqueó las solicitudes de prueba a `doubleclick.net`, `adservice.google.com` y `pagead2.googlesyndication.com`. Permitió el control negativo `example.org`. |

La comprobación JVM cubre firma, extracción, manifiesto y decisiones DNR. La aceptación de los popups, la consola de Chromium, el tiempo por fotograma y la reanudación se evalúan en Android ARM.
