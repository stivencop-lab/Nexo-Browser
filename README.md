# Nexo Browser

> **Versión de desarrollo: v0.30.2** · `versionCode 61` · Paquete `com.nexo.browser`
>
> La actualización está preparada para revisión; el APK publicado sigue disponible en
> [la última release](https://github.com/stivencop414-bot/NavegadorGecko/releases/latest).
> Consulta la [auditoría de interfaz de 0.30.0](docs/AUDITORIA-UI-0.30.0.md) y las
> [mejoras de seguridad, recursos y organización de 0.30.1](docs/AUDITORIA-SKILLS-2-3-4.md), y la
> [revisión de Kotlin/XML y PhantomShield de 0.30.2](docs/AUDITORIA-BROWSER-SHIELD.md).

<img src="docs/brand/nexo.svg" alt="Logo de Nexo Browser" width="112" />

Desde **0.25.0**, el identificador Android es **`com.nexo.browser`**.
Esta versión se instala como una app nueva junto a las anteriores; no transfiere
automáticamente historial, pestañas, extensiones ni ajustes. Consulta
[el cambio de identidad](docs/IDENTIDAD-NEXO.md) antes de instalarla.

Navegador Android en Kotlin/XML con Chromium 156 embebido ([Cefrium](https://cefrium.com) 0.21.0, LGPL-3.0), desde Android 10.
Nexo ya no depende de GeckoView ni de Android System WebView (ver [migración](docs/CHROMIUM-NATIVE-MIGRATION.md)).

## Incluye
- **Paso desde la app anterior** (`com.ejemplo.navegador`): los releases ya solo publican la app nueva
  (`com.nexo.browser`). Si aún tienes instalada la actualización de transición 0.26.x, Ajustes › Traer datos de
  Nexo anterior sigue funcionando (ver [transferencia](docs/TRANSFERENCIA-PERFIL.md)).
- Omnibox: URL o búsqueda por palabras.
- Google, DuckDuckGo, Bing, Brave Search y Startpage.
- Multipestañas, sesiones LRU y pestañas privadas. Grupos con nombre y color, píldoras con
  contador para filtrar, mover pestañas con pulsación larga y renombrar/desagrupar/cerrar un grupo.
- Selector de pestañas fluido: deslizar una tarjeta hacia un lado la cierra (con **Deshacer**), mantenerla
  pulsada y arrastrarla la cambia de orden (sin moverla, abre sus opciones de grupo), las
  tarjetas muestran el logo del sitio y las miniaturas no parpadean ni se recargan al abrirlo.
  "Reabrir cerrada" en el menú y en la pulsación larga del contador de pestañas.
- Gestos: deslizar la barra inferior a los lados cambia de pestaña y hacia arriba abre el selector;
  pulsación larga en Recargar = recarga sin caché, en "+" = pestaña privada, en el contador = acciones
  rápidas. Todas las hojas y los paneles de extensiones se cierran arrastrándolos hacia abajo.
  Los gestos de la barra y las descargas en varias conexiones se pueden desactivar en Ajustes.
- Herramientas en la barra de direcciones (hoja inferior): grupo, proxy SOCKS5/HTTP por pestaña
  (en pestañas Burner, que tienen perfil propio), Orbot externo. DNS DoH configurable para todo el navegador.
- Burner (menú › Burner): contenedor privado aislado, correo temporal de mail.tm con buzón que se
  actualiza solo, códigos de verificación copiables y limpieza al cerrar la última pestaña de la
  identidad. La franja superior muestra la dirección y abre el buzón. Variación experimental de
  Canvas/WebGL; cada identidad tiene su propio perfil de Chromium, borrado del disco al siguiente arranque.
- Modo escritorio: User-Agent + viewport.
- Temas Midnight, OLED y claro.
- Siete acentos HCT con cobalto por defecto y color del sistema (Material You) opcional desde Android 12.
- Barras y componentes compartidos con controles de 48 dp, campos que crecen con la fuente y hojas adaptadas a tablet.
- Tema de ventanas y botones flotantes con contraste verificado; áreas seguras para barras, recortes y teclado.
- Historial con búsqueda en vivo (título, dominio o dirección, sin distinguir tildes), descargas y compartir.
- Descargas aceleradas: los archivos grandes de servidores que admiten rangos se reparten en 2–4
  conexiones (misma sesión, nunca con proxy ni Burner), una descarga cortada se reanuda desde el último
  byte y ya no se guarda incompleta. Notificación con progreso y velocidad; se cancelan desde Descargas.
- Menú con captura de la página (PNG), guardar como PDF, fijar el sitio en inicio y reabrir pestañas.
- Subtítulos en vivo para cualquier video (botón CC):
  - Si el video trae subtítulos propios (WebVTT/HLS), se usan esos sin Deepgram ni clave y se traducen
    por adelantado en lotes grandes, también las líneas que llegan durante la reproducción.
  - Si el reproductor dibuja sus propios subtítulos (YouTube, Netflix, Prime Video, video.js, JW Player,
    Shaka, Plyr, Bitmovin), Nexo los lee sin clave y los muestra con su estilo y traducidos. En YouTube
    los activa solo; en otros sitios basta con encender el CC del reproductor.
  - Si el reproductor descarga su propio archivo de subtítulos (`.vtt`/`.srt`), Nexo lo lee y lo sincroniza
    con el video (prefiere el del idioma al que traduces). Texto, sin audio ni servidores.
  - Si el video no trae ningún texto, Nexo **pregunta**: subtítulos instantáneos de Android (generados en el
    teléfono, sin enviar nada) o transcribir con Deepgram. El audio nunca se envía sin elegirlo, salvo que
    actives «Transcribir el audio sin preguntar». Nunca se usa el micrófono: se toma el sonido digital del
    reproductor, así que da igual usar audífonos.
  - Con Deepgram, el audio se comprime en la página (Opus, ~24 kbps) y se transcribe en streaming desde el
    proceso nativo; la clave nunca llega a la página.
  - Traducción a 37 idiomas sin esperas artificiales: cada frase sale al momento si no hay otra petición en
    curso (lo que llega mientras tanto viaja junto en la siguiente), el texto provisional se traduce por
    anticipado y la frase final suele aparecer ya traducida. Google sin clave, DeepL o un modelo de IA
    (OpenAI, Claude, OpenRouter o propio) con contexto; con IA, Google muestra un respaldo rápido mientras
    responde el modelo. Modos: traducido, ambos u original.
  - Tocar CC con los subtítulos activos abre el selector: idioma de destino, modo e idioma del video; los
    cambios se aplican al instante sin cortar el audio.
  - Claves cifradas con el Android Keystore. Reconexión con espera creciente (máx. 3), control de flujo,
    contador de minutos y límite diario. Solo transcribe mientras el video suena en primer plano.
- Bloqueo de ventanas emergentes y "popunders" (reglas $popup de las listas y toques robados), con interruptor.
- Deslizar hacia abajo para recargar (solo cuando la página está arriba y el gesto no lo usa la propia web).
- Recuperación automática del video al volver a la app: si el reproductor quedó congelado, se repara sin recargar.
- Ajustes de navegador: tamaño del texto, zoom en todas las páginas, versión de escritorio por defecto,
  reproducción automática, JavaScript, protección estricta, aviso de sitios peligrosos, bloqueo de huellas
  digitales, limpieza de parámetros de rastreo, borrar datos al salir y depuración remota.
- Limpieza de datos.
- Administrador de extensiones.
- PhantomShield 9.2.1 integrado: bloqueo de red, ocultación con reglas de EasyList/uBlock,
  eliminación de anuncios de YouTube, contadores reales y controles por sitio. IA opcional
  (tu propia API) para los anuncios que las reglas no reconocen; las señales inequívocas se
  resuelven en el dispositivo sin gastar tokens y el panel muestra lo revisado/ocultado en cada página.
- Memoria IA opcional en el dispositivo: coincidencias precisas por dominio, caducidad y controles para olvidar o excluir sitios.
- IA opcional en sitios elegidos o automática, con exclusiones, presupuestos y apagado rápido.
- OpenAI, Claude y OpenRouter, direcciones personalizables y contadores acumulados de tokens y decisiones IA.
- Inicio y menú basados en el prototipo: barra inferior, accesos editables y fuentes locales.
- Logos en accesos rápidos y pestañas: recursos locales y el icono que declara el propio sitio
  (`apple-touch-icon`/`icon`, si no `/favicon.ico`), sin servicios externos; si no hay, inicial de color.
  Accesos de Nexo en inicio (Descargas, Historial, Burner, Extensiones) y pulsación larga en cada acceso.
- Paneles de extensiones como hoja inferior redondeada y accesos directos en el menú: todas las
  extensiones con su icono e insignia en una fila deslizable; aviso claro si una no está permitida en privado.
- Diálogos de páginas: `<select>`, alert/confirm/prompt, fecha y hora, color, subida de
  archivos, inicio de sesión HTTP, "¿salir de la página?" y `navigator.share()`.
- Enlaces `target=_blank` y `window.open` en pestaña nueva; bloqueo de ventanas emergentes.
- Permisos de sitios con confirmación: ubicación, cámara/micrófono, DRM.
- Enlaces a apps (`tel:`, `mailto:`, `intent://`, Play Store…) con intent saneado.
- Puede ser el navegador predeterminado y recibir enlaces/texto compartido de otras apps.
- Buscar en la página.
- Sitios en modo oscuro cuando Nexo usa un tema oscuro.
- Comprobación de compatibilidad de extensiones de escritorio (CRX, XPI, ZIP) con el runtime NXR;
  la instalación de extensiones de terceros llegará con ese runtime.
- Mini reproductor (Picture-in-Picture) con título del video, botón flotante translúcido (pulsación
  larga lo oculta en esa página) y reproducción en segundo plano.
- Parcheador anti-bloqueo opcional, apagado inicialmente: modifica solo dos bibliotecas
  conocidas cuando sus bytes coinciden con un hash verificado.

## APK disponible

La versión estable actual es **Nexo Browser v0.30.1** (`versionCode 60`) para Android, publicada desde `main` y firmada con la clave persistente del proyecto. El paquete de la aplicación es `com.nexo.browser`.

- [Descargar APK arm64-v8a (recomendado para la mayoría de teléfonos)](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.30.1/Nexo-v0.30.1-arm64-v8a.apk)
- [Descargar APK armeabi-v7a (teléfonos Android de 32 bits)](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.30.1/Nexo-v0.30.1-armeabi-v7a.apk)
- [Descargar SHA256SUMS.txt](https://github.com/stivencop414-bot/NavegadorGecko/releases/download/v0.30.1/SHA256SUMS.txt)
- [Ver release v0.30.1](https://github.com/stivencop414-bot/NavegadorGecko/releases/tag/v0.30.1)

Hashes SHA-256 publicados:

- `Nexo-v0.30.1-arm64-v8a.apk`: `45d4fe9aa6da49f3d8d500482680e2cd47ba1c5127c4c913a72dabce364b198b`
- `Nexo-v0.30.1-armeabi-v7a.apk`: `3f6deb73d22243d3a2649980f21cdc6ec3e38a3b9f0445cd9939bfa92a2eaf86`

## Cambios recientes de estabilidad

La versión actual incorpora las correcciones de estabilidad fusionadas en la PR [#33](https://github.com/stivencop414-bot/NavegadorGecko/pull/33):

- Los botones de la barra inferior reciben sus toques correctamente; los gestos solo actúan sobre el fondo de la barra.
- La captura de audio para subtítulos en vivo funciona de forma interna y no reposiciona ni reescribe currentTime del video.
- El búfer de captura se mantiene alrededor de 250 ms para reducir la cola de audio sin eliminar el margen contra pérdidas.
- Al volver a primer plano no se reinicia automáticamente el video únicamente por activar subtítulos.
- El modo oscuro aplica un fondo base temprano mientras se cargan los estilos completos, evitando ocultar la página durante la carga.

Estas correcciones no cambian los modelos, proveedores, endpoints ni la transcripción de subtítulos. La sincronización A/V debe seguir validándose en dispositivos Android físicos con el mismo contenido y con subtítulos activados y desactivados.

## Extensiones propias
Chromium embebido no ejecuta WebExtensions. PhantomShield funciona con su motor nativo
(`app/src/main/java/com/nexo/browser/shield/`) y los scripts de contenido de
`app/src/main/assets/extensions/phantomshield/`, que hablan con Nexo por `window.cefriumQuery`.

## Modelo de seguridad
- **URLs**: las reglas están centralizadas en `SafetyRules.kt` (`UrlSafety`).
  - Barra de direcciones y pantallas propias: `http`, `https`, `about`.
  - Otras apps (Activity exportada): solo `http`/`https`. Las pantallas de Nexo usan el
    alias no exportado `InternalOpenActivity` (`MainActivity.openUrlIntent`).
  - Pestañas abiertas por el escudo o el puente: `http`, `https` y `about:blank`.
  - `javascript:`, `data:`, `file:`, `content:` e `intent:` nunca se cargan.
- **Extensiones**: Nexo no instala extensiones de terceros (llegarán con el runtime NXR). La
  comprobación de compatibilidad valida los paquetes (rutas, nº de entradas y tamaño real
  descomprimido) y el temporal se borra siempre.
- **Otras apps**: `tel:`, `mailto:`, `intent://`… se abren sin componente ni selector
  explícitos, solo con actividades BROWSABLE y sin permisos de URI heredados. Las
  redirecciones sin toque del usuario o en pestaña privada piden confirmación.
- **Permisos de sitios**: se pregunta siempre mostrando el dominio real; las notificaciones
  web se rechazan porque Nexo aún no las muestra.
- **Puente nativo** (`assets/nexo/`): un único canal por pestaña sobre `window.cefriumQuery`
  (`cefrium-port.js` ↔ `PageChannel`). Corre en el mundo de la página, así que Nexo solo
  responde lo que cualquier página ya puede ver (estado de bloqueo, selectores cosméticos) y
  órdenes de video de su propia pestaña; ajustes, estadísticas e IA solo desde el panel, en su
  propio origen. Solo con CC activo se reciben pistas de subtítulos; las claves permanecen en el proceso nativo.
- **Privacidad**: el historial elimina parámetros sensibles (tokens, sesiones, firmas) antes de
  guardarse; las pestañas privadas no se guardan en historial, pestañas ni miniaturas; la
  notificación multimedia no muestra el título en la pantalla de bloqueo.
- **Descargas**: Chromium las entrega al gestor de Nexo, que las pide con las cookies del perfil de la pestaña, se guardan en la
  carpeta privada de la app con nombres saneados, y se borran si quedan incompletas.
- **Rutas y Burner**: el proxy de una pestaña Burner se aplica a su perfil de Chromium, sin
  conexión directa de respaldo; una pestaña normal o privada con proxy no se abre. No instala una VPN
  ni incluye Tor. Buzón, IA y listas tienen conexiones propias; las simulaciones no cambian
  la IP. El borrado remoto de mail.tm depende de la conexión y de que siga vivo el proceso.

Los logos de accesos y pestañas se consultan sin cookies ni referer, solo por HTTPS: la
cabecera de la página principal del sitio (redirecciones solo dentro del mismo sitio) y los
iconos que declara, o `/apple-touch-icon.png` y `/favicon.ico`. Se guardan en una caché limitada.
Se admiten imágenes raster compatibles con Android y PNG dentro de ICO (no SVG); si no hay un
formato compatible se muestra la inicial. No se solicitan desde pestañas privadas.

## Desarrollo
Requisitos: **JDK 25** (el SDK de Cefrium es bytecode Java 25), Gradle 9.8.2 y Android SDK con
`platforms;android-37.2` y `build-tools;37.0.0` (AGP 9.5). Las dependencias de Cefrium vienen de
`https://maven.cefrium.com`; sus hashes están en `gradle/cefrium-0.21.0.sha256`.

```bash
node --test app/src/test/js/*.test.cjs  # regresiones de PhantomShield, puente y mini reproductor
gradle :app:testDebugUnitTest            # tests unitarios
gradle :app:assembleDebug                # APK arm64-v8a y armeabi-v7a
gradle :app:lintRelease
```

Pruebas de interfaz nativa (Robolectric, también en CI):

```bash
gradle :app:testDebugUnitTest -PnexoScreenshots --tests '*UiThemeRegressionTest' --tests '*SkillPracticeRegressionTest'
```

Capturas adicionales de la interfaz nativa (opcionales):

```bash
gradle :app:testDebugUnitTest --tests 'com.nexo.browser.ScreenshotTest' \
  -PnexoScreenshots -PnexoShotsDir=/ruta/capturas
```

Las librerías nativas de Chromium (`libcef.so`) van comprimidas en el APK; Android las extrae
una vez al instalar. Hay un APK por ABI (arm64-v8a, armeabi-v7a).

## CI y releases
El workflow `.github/workflows/main.yml` compila, ejecuta tests y lint en cada PR y push a `main`.

Para publicar: *Actions → Nexo Secure CI and Release → Run workflow → mode = release* desde `main`.
Requiere estos secrets del repositorio:

| Secret | Contenido |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore en base64 (`base64 -w0 nexo.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | Contraseña del keystore |
| `ANDROID_KEY_ALIAS` | Alias de la clave |
| `ANDROID_KEY_PASSWORD` | Contraseña de la clave |

La compilación corre sin acceso a esos secrets; solo el paso de firma los recibe. Si el tag
`v<versionName>` ya existe, el release se rechaza: sube `versionCode`/`versionName` primero.
Usa siempre la misma clave: un APK firmado con otra clave no se puede instalar encima.

La integración se documenta en [PhantomShield](docs/PHANTOMSHIELD.md).
El nuevo traductor se documenta en [traducción en la página](docs/TRADUCCION-EN-LINEA.md).
Los alcances y límites están en [grupos, rutas y Burner](docs/PESTANAS-RED-BURNER.md).
Para conservar los datos al actualizar, consulta [actualizaciones y firma](docs/ACTUALIZACIONES-Y-FIRMA.md).

La memoria y el diagnóstico de IA se explican en [aprendizaje local](docs/IA-MEMORIA.md).

## Transición de paquete y subtítulos

Consulta [transferencia cifrada del perfil](docs/TRANSFERENCIA-PERFIL.md) para la actualización de transición `-PnexoTransition`, importación y recuperación. El [diagnóstico de subtítulos](docs/DIAGNOSTICO-SUBTITULOS.md) explica la captura, sus estados y las verificaciones reales pendientes.

Los cambios de [estabilidad de pestañas y captura de reproducción de Android](docs/ESTABILIDAD-Y-AUDIO-ANDROID.md) explican la confirmación de ventanas, la opción nativa de CC y sus permisos/límites.

La [versión 0.28.0](docs/VERSION-0.28.0.md) reúne la captura de Android que ya no cancela la página, el modo nocturno con Dark Reader, las sugerencias de la barra, la revisión de seguridad y las mediciones de rendimiento, con lo que falta validar en el teléfono. La 0.28.1 corrige las extensiones (PhantomShield, instaladas y tienda), que la 0.28.0 dejaba sin arrancar. La [0.29.0](docs/VERSION-0.29.0.md) mejora los subtítulos: menos tirones con la captura de Android, menos retraso, dos modelos de IA de traducción en carrera, diseño configurable y borrado tras el silencio. También trae PhantomShield 9.2: menos bloqueos innecesarios, más ventanas emergentes cortadas, dominios publicitarios aprendidos con la IA y hasta dos modelos de respaldo (ChatGPT, Claude, OpenRouter, Gemini, Groq, DeepSeek, Mistral, xAI o una API propia) con cambio automático ([detalle](app/src/main/assets/extensions/phantomshield/README.md#cambios-en-92-modelos-de-respaldo-con-cambio-automático)).
