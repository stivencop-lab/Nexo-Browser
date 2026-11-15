# Revisión Kotlin/XML y Browser Shield — 0.30.2

Fecha: 2026-11-15. Base: `main` después de publicar 0.30.1.
Guía revisada: archivo adjunto **Kotlin Xml Browser Shield Master.md**.
Resultado: cambios acotados en el puente, las decisiones automáticas de
PhantomShield y la copia de datos privados. Nexo conserva Kotlin/XML, GeckoView,
su identidad visual y sus preferencias.

## Alcance de la revisión

Se inventariaron los archivos del repositorio y se recorrieron los puntos de
entrada del manifiesto, navegación, extensiones, permisos, persistencia,
descargas, red nativa, subtítulos, reproducción y publicación. El barrido de
patrones abarcó código Kotlin, JavaScript, XML y configuración. La revisión
dirigida y las pruebas se concentraron en las rutas donde la guía aportaba
una mejora comprobable; esto no constituye una certificación MASVS ni una
auditoría exhaustiva de las dependencias o del motor Gecko.

| Pilar de la guía | Estado encontrado y decisión |
| --- | --- |
| XML, recursos y marca | Ya existen controles de 48 dp, hojas adaptables, contraste y pruebas de fuente al 200 %. Se conserva el sistema de temas; no se añade Compose ni se sustituye la marca. |
| Sesiones y rendimiento | `TabManager` mantiene un límite de sesiones, protege la reproducción al descartar pestañas y guarda/restaura el estado. `TabStore` usa escritura atómica fuera del hilo principal; cachés y vistas responden a presión de memoria. No se incorpora el pool simplificado del ejemplo. |
| Almacenamiento y release | `SecretStore` ya usa Android Keystore con AES-GCM e IV aleatorio; las copias manuales se cifran y autentican antes de abrir el ZIP. `allowBackup=false`, R8, validación de firma y constructor por reflexión ya existen. Se añade sensibilidad al portapapeles privado. |
| Puentes y entradas externas | El puente comprueba extensión y sesión; los subtítulos eligen un solo puerto propietario. Las descargas sanean nombres y comprueban el destino canónico; los intents externos se reconstruyen. Se corrige la validación de los arrays del puente. |
| Red y ventanas emergentes | Ya hay motor ABP con excepciones, `$important`, `$badfilter`, `$popup` y protección de ventanas. Se mantienen las reglas explícitas; los nuevos vetos se aplican a la memoria automática de dominios. |
| Heurísticas e IA | Ya hay consentimiento, exclusiones, presupuestos, límites de recorrido, caché/memoria y rechazo de respuestas tardías. Se añaden vetos funcionales antes de clasificar y aprender. |
| Streaming, autenticación y CC | Se protegen rutas de reproductores, audio/vídeo, subtítulos, OAuth, CAPTCHA y pagos frente a las heurísticas. Se conserva la captura de audio elegida por el usuario y su aislamiento respecto al bloqueador. |

## Cambios aplicados

### 1. Mensajes de subtítulos con contenido acotado

Antes, `BridgeLimits` comprobaba la cantidad de elementos de un array, pero
no su contenido. Un array de una sola entrada podía contener texto enorme,
otros arrays u objetos arbitrarios. La validación ocurre después de que Gecko
entrega el JSON: limita el procesamiento nativo, no la asignación inicial del
mensaje dentro del motor.

Ahora:

- `captions_cues` admite hasta 200 objetos con exactamente `i`, `s`, `e`, `t`.
- Los identificadores son enteros no negativos compatibles con `Int`; los
  tiempos son números finitos y el texto tiene un máximo de 300 caracteres.
- `captions_active` conserva el máximo de 16 identificadores y rechaza tipos
  anidados o valores que antes se convertían silenciosamente en enteros.
- Otros arrays, objetos superiores y claves desmesuradas se rechazan sin
  recorrer árboles JSON de profundidad arbitraria.
- Se conserva el bloque de audio de hasta 350 000 caracteres y el formato de
  estado de vídeo. Las dimensiones y coordenadas negativas válidas siguen
  admitidas.

Los límites coinciden con el productor actual de pistas WebVTT/HLS y archivos
de subtítulos. No cambia el protocolo de captura ni el envío a Deepgram.

### 2. Veto funcional en las decisiones automáticas

`data/functional-safety.js` comparte las comprobaciones entre el detector DOM y
el proceso de fondo. Se carga antes que sus consumidores en el manifiesto.
Comprueba el hostname y el pathname de URLs HTTP(S); las menciones dentro del
query o fragmento no convierten una dirección en un servicio protegido.
Los hosts se comparan por etiquetas: `auth0.com.ejemplo.net` no es `auth0.com`.

El detector conserva los contenedores de reproductores conocidos, elementos
multimedia y marcos con rutas de streaming, autenticación o pago. Comprueba
todos los iframes del candidato, hasta un máximo de 16; ante más marcos se
abstiene de clasificar el contenedor. Así, un iframe publicitario situado antes
que el reproductor no provoca la ocultación del contenedor completo. También
protege enlaces directos a pistas y recursos multimedia. Revisa hasta 32
enlaces antes de tomar la muestra de señales; con más enlaces se abstiene.

La memoria de dominios no aprende esas URLs. Si una versión anterior ya
aprendió un dominio, sus peticiones `media` y URLs funcionales se conservan
frente a ese bloqueo automático. Las listas explícitas de red se evalúan
primero y mantienen sus bloqueos, incluso para medios y reglas `$popup`.
El chequeo funcional de red solo analiza la URL cuando el dominio figura
efectivamente en la memoria bloqueada.

Este criterio es deliberadamente conservador: un anuncio podría imitar una
ruta funcional y quedar fuera de la heurística. Seguiría sujeto a las reglas
explícitas. No se pretende reconocer todos los reproductores de Internet.

### 3. Exclusiones de dominios consistentes

«Nunca bloquear» respeta dominios padre y sus subdominios. Si el usuario
permite un hijo de un dominio aprendido, ese hijo y sus descendientes quedan
permitidos; los hermanos mantienen el bloqueo. Las comparaciones respetan
los límites de etiquetas.

Al restaurar la memoria se eliminan las entradas que ahora correspondan a
servicios protegidos o exclusiones. Se mantienen los límites de tamaño,
caducidad y huellas con sal de los sitios de origen. No se envían nuevos
datos al proveedor de IA ni se activa la IA por defecto.

### 4. Copias sensibles

`ClipboardPrivacy` marca las copias desde pestañas privadas y el buzón Burner
con `android.content.extra.IS_SENSITIVE`. Android 13+ puede ocultar su vista
previa; la clave es compatible con las versiones anteriores soportadas.
Los avisos de copia de OTP ya no repiten el código en el Toast.

El texto copiado y las copias desde pestañas normales conservan su formato.
Esta marca no cifra ni borra el portapapeles y no controla los teclados o
gestores de portapapeles de terceros.

## Ejemplos de la guía que requieren adaptación

- Forzar HTTPS-Only cambiaría una preferencia explícita y rompería navegación
  HTTP válida. Gecko usa su propia pila de red; una configuración Android no
  sustituye la política de Gecko. Se conserva la opción ya existente.
- Cambiar Keystore por `EncryptedSharedPreferences` no aporta una mejora a la
  implementación AES-GCM actual y exigiría una migración innecesaria.
- El pool de ejemplo puede cerrar pestañas privadas o multimedia sin conservar
  sus sesiones. Nexo mantiene su política existente.
- Reemplazar `window.open` con objetos simulados o prohibir todos los destinos
  externos puede romper accesos y pagos. Se mantienen las protecciones actuales
  de gestos y las pruebas de ventanas funcionales.
- Un servicio remoto no recibe permiso para generar CSS o bloquear hosts sin
  validación. No se incorpora el servicio backend del ejemplo ni una captura
  de audio adicional desde los scripts del bloqueador.
- No se publicitan 60 fps, mejoras medidas de batería, latencia garantizada ni
  cumplimiento MASVS sin pruebas específicas en dispositivos.

## Validación

| Comprobación | Resultado local |
| --- | --- |
| Regresiones JavaScript | 206 pruebas correctas; incluye red, ventanas, privacidad, rutas por pestaña, PiP y los nuevos vetos. |
| Tests JVM | 123 en debug y 123 en release, tanto con el paquete normal como con `-PnexoTransition`; sin fallos ni omisiones. |
| Regresiones nativas de interfaz | 17 correctas en Robolectric; incluye el portapapeles sensible en API 26, 33 y 35. |
| Builds | Debug y release para ARM64 y ARM32 en ambos paquetes. |
| Lint | 0 errores en las cuatro combinaciones. Permanecen 332 advertencias por informe, igual que en la base revisada; no se desactiva lint ni se añade un baseline para ocultarlas. |
| APK optimizado | Los 4 APK release conservan los constructores de 107 clases de arranque por APK. |
| Identidad y FileProvider | Comprobación correcta de los 8 APK: paquete normal `com.nexo.browser` y transición `com.ejemplo.navegador`, debug/release, ambas ABI. |
| DOM real | Correcto con el clasificador/memoria reales y servicios simulados, según el detalle inferior. |
| Secretos y política de fuente | Gitleaks sin coincidencias; barrido sin multiproceso desactivado, navegación `javascript:` ni depuración habilitada incondicionalmente. |

Los APK locales de release son candidatos sin firmar. La firma se reserva al
workflow de publicación ya existente. Los XML de tests/lint y los registros
de las comprobaciones se conservaron en el entorno de validación; el CI vuelve
a ejecutar las suites sobre el commit del PR y adjunta sus resultados.

Comandos principales:

```sh
node --test app/src/test/js/*.test.cjs
node tools/check_ai_dom.cjs
gradle :app:testDebugUnitTest -PnexoScreenshots \
  --tests '*UiThemeRegressionTest' --tests '*SkillPracticeRegressionTest'
gradle :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease
# Repetir la última línea con -PnexoTransition.
python3 tools/check_release_reflection.py --build-tools "$ANDROID_HOME/build-tools/37.0.0" APK
python3 tools/check_apk_identity.py --build-tools "$ANDROID_HOME/build-tools/37.0.0" --package PAQUETE APK
```

La prueba DOM usa Chromium real con el detector, clasificador y memoria reales;
simula las respuestas del proveedor y los documentos de los iframes. Comprueba
PiP, restauración al desactivar/olvidar, reutilización de memoria, reproductores
hermanos de anuncios, OAuth, CAPTCHA y enlaces de subtítulos. No sustituye una
prueba integrada de GeckoView en un dispositivo.

Gitleaks 8.30.1, descargado de su release oficial y comprobado contra su archivo
de sumas, no encontró secretos en el snapshot de fuentes del repositorio.
La revisión de dependencias aquí se limita a compilación, lint y configuración;
no se ha ejecutado un análisis OSV/CVE completo ni de todo el historial Git.

Antes de una publicación conviene comprobar en un teléfono: reproducción HLS
y DASH, PiP, CC de texto y captura Android, OAuth/pagos reales, instalación
encima de 0.30.1 y persistencia de ajustes/extensiones. Las pruebas automatizadas
no prueban el hardware de audio, DRM ni los servicios externos en producción.

La preparación incrementa Nexo a 0.30.2 (`versionCode 61`) y PhantomShield a
9.2.1 para que `ensureBuiltIn` actualice la extensión conservando su almacén.
La firma sigue en el workflow existente, separada de las pruebas de PR.
El README enlaza los APK y las sumas de la estable publicada 0.30.1; la
versión de desarrollo se distingue de esa descarga.
