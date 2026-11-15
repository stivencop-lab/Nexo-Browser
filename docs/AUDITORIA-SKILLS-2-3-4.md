# Nexo 0.30.1: revisión de SKILL2, SKILL3 y SKILL4

## Alcance del PR

Se revisaron los tres documentos adjuntos: seguridad de navegador/Kotlin/XML,
infraestructura y optimización, e iconografía/organización de opciones. Este cambio
aplica mejoras comprobables a la arquitectura nativa existente. No introduce una
migración a Compose, Room, Retrofit o un árbol de módulos sin una necesidad concreta.

El PR prepara `versionName 0.30.1`, `versionCode 60`. La fusión y la publicación quedan
pendientes de la confirmación del usuario. La auditoría describe comportamiento y
pruebas; no es una certificación MASVS ni una promesa de rendimiento en todo teléfono.

## SKILL2: Kotlin, navegador y seguridad

### Cambios

- **Intents reconstruidos:** `ExternalApps.buildIntent` conserva únicamente el destino,
  paquete externo y fallback web válido en un nuevo `ACTION_VIEW` con categoría
  `BROWSABLE`. Acciones, categorías, flags de concesión, selector, componente, ClipData
  y extras arbitrarios del intent original no se propagan. Se limita su tamaño y se
  rechazan destinos sin datos, esquemas de archivos/contenido y el propio paquete.
- **Peticiones automáticas de imágenes:** `NativeImageNetwork` sirve a los dos cargadores
  nativos con HTTPS, sin cookies, autenticadores ni caché HTTP en disco, y límites de
  tiempo/tamaño. Cada redirección se valida antes de solicitar el siguiente destino;
  no se permite downgrade a HTTP ni autoridad con credenciales.
- **Direcciones y DNS:** las direcciones literales y todas las respuestas del resolver
  deben ser públicas. Se rechazan loopback, RFC1918, link-local, CGNAT, multicast,
  rangos reservados/documentales y direcciones IPv6 locales. El conector directo de
  OkHttp recibe respuestas DNS validadas, incluso si cambian entre comprobaciones.
  El prefijo NAT64 conocido admite destinos IPv4 públicos y rechaza los privados, para
  conservar iconos en redes móviles IPv6-only sin saltarse esta política.
  Antes de un CONNECT de proxy también se comprueba el destino con el resolver local.
- **Límite de confianza:** un proxy configurado por el usuario sigue siendo parte del
  transporte de confianza y puede tener su propia resolución DNS. No se afirma controlar
  un proxy externo. La política nueva afecta a imágenes automáticas, no a la navegación
  deliberada de Gecko ni a endpoints de proveedores configurados por el usuario.
- **Dominios registrables:** la descarga de la cabecera para descubrir favicons usa la
  Public Suffix List de OkHttp. `victim.co.uk` y `attacker.co.uk`, o dos usuarios de
  `github.io`, ya no se consideran el mismo sitio por compartir las dos últimas etiquetas.
- **Privacidad de ventanas:** la pestaña privada activa aplica `FLAG_SECURE`; al volver
  a una normal se retira ese flag sin borrar los demás. Las hojas comunes lo heredan y
  el selector se protege al mostrar pestañas privadas. El título de la ventana privada
  deja de incorporar el título de la página.
- **Informes de cierre:** `CrashReportRedactor` elimina URLs completas, incluida su
  autoridad con usuario/contraseña, rutas privadas y consultas. También elimina valores
  de autorización Bearer/Basic y parámetros sensibles conservando los frames de la pila.

### Adaptación de las recomendaciones

Gecko ya tiene runtime compartido, sesiones privadas, hibernación y delegados de
crash/kill. Se conservan. Cambiar de WebView a Gecko o copiar sus configuraciones no
es necesario: Nexo ya utiliza GeckoView. Tampoco se activa el aislamiento de procesos
que el proyecto documenta como incompatible con su proceso de extensiones actual.

Bloquear routers e intranets en toda navegación rompería una función normal de un
navegador. La defensa SSRF se aplica a cargas auxiliares automáticas. No se reutiliza
la heurística del ejemplo que puede resolver nombres como `face` al confundirlos con
una IP hexadecimal: las comprobaciones síncronas solo analizan literales conocidos;
el DNS de las imágenes se ejecuta en sus trabajadores de red.

`FLAG_SECURE` protege la captura de la ventana; no demuestra por sí solo borrado
criptográfico ni ausencia de todo rastro. Las descargas solicitadas por el usuario
siguen guardadas. No se borran cookies globales para cerrar una pestaña privada:
eso dañaría sesiones normales y no corresponde a la separación de Gecko.

## SKILL3: infraestructura, memoria y rendimiento

### Cambios

- Los cargadores de iconos comparten un único cliente/pool de conexiones anónimo,
  creado de forma perezosa. Los clientes autenticados de subtítulos conservan sus
  políticas y tiempos específicos; no se mezclan claves, cookies o cachés por cumplir
  literalmente «un cliente para toda la app».
- `RemoteImageLoader` usa una referencia débil a la vista en tareas pendientes. Una
  petición lenta ya no necesita mantener viva toda su Activity para entregar el bitmap;
  una vista reciclada sigue recibiendo resultados solo si conserva la URL solicitada.
- `SiteIconStore` decide de forma atómica quién inicia una búsqueda compartida. Dos
  suscriptores concurrentes no pueden hacer que ambos omitan el trabajo al observar
  que la lista de espera ya tiene más de un elemento.
- `MemoryPressure` distingue los niveles de presión en primer plano (10/15) del aviso
  de UI oculta (20). La presión crítica de primer plano ahora vacía las cachés de iconos;
  presión baja/UI oculta las reduce; avisos moderados/completos las vacían. La prueba
  incluye el tamaño real de una caché de bitmaps.
- El selector usa IDs `Long` únicos durante la vida del adaptador, sin comprimir el ID
  de pestaña a un hash de 32 bits. La prueba utiliza `Aa` y `BB`, que colisionan en
  `String.hashCode`, y comprueba estabilidad al cambiar el orden. Se eliminan referencias
  a IDs retirados y no se reutilizan sus números dentro del mismo adaptador.
- Se conservan `DiffUtil`, snapshots y payloads de miniaturas existentes para respetar
  arrastrar/reordenar y evitar rebindeos de imágenes sin cambios. No se sustituye ese
  comportamiento por una plantilla de lista que pierda sus operaciones de edición.

### Adaptación de las recomendaciones

Un `SharedFlow`, un `Channel` y `WhileSubscribed(5_000)` tienen semánticas distintas;
ninguno garantiza entrega exactamente una vez tras muerte del proceso. No se añaden
Flows artificiales a componentes que no los necesitan. La persistencia normal de
pestañas ya está en `TabStore`; las privadas permanecen efímeras, deliberadamente.

No se crean pools globales de vistas con Context de Activity: compartirlos sin comprobar
tipos y ciclos de vida puede introducir retenciones. Los trabajadores existentes de
disco/red tienen vida de proceso; no se sustituyen todos por coroutines durante este PR.
Los streams/respuestas nuevos se cierran con `use`, incluidos errores, redirecciones y
respuestas demasiado grandes. TLS y la validación de certificados permanecen activas.

No se cambian flags R8 ni reglas amplias a partir de la plantilla. Se mantiene la
configuración probada por el proyecto y las comprobaciones de constructores de arranque
tras minificar. Los objetivos de 300 ms, 120 Hz, cero ANR y cero asignaciones por frame
requieren medición en hardware; este PR no inventa esas mediciones.

## SKILL4: iconografía y organización

El menú muestra hasta cuatro accesos frecuentes y organiza el resto en «Esta página»,
«Pestañas e historial» y «Preferencias». Una sección contiene como máximo cinco opciones;
las acciones nuevas desconocidas tienen un grupo de respaldo y no desaparecen. Las
pruebas comprueban que cada comando disponible aparece exactamente una vez.

Las filas comunes usan un icono tonal de 40 dp a 16 dp del borde y texto a la keyline de
68 dp, con altura mínima de 64 dp y contenido flexible. La fila completa ejecuta la
acción. El icono y texto decorativos se excluyen del foco redundante y la descripción
accesible incluye título y resumen. El chevron es vectorial y se refleja en RTL, al
igual que Atrás/Adelante. El control Escritorio expone su estado checkable/checked y
seleccionado al lector de pantalla, además de su color.

En ajustes, «Protección estricta» depende del bloqueo de rastreadores y la configuración
de subtítulos depende de habilitar subtítulos. Deshabilitar el padre atenúa e inhabilita
sus hijos, pero conserva sus valores para recuperarlos al reactivarlo. Los pickers
existentes siguen mostrando su selección actual. Las acciones destructivas conservan
sus confirmaciones; no se introducen borrados o restablecimientos nuevos.

Los logos de sitios/extensiones pueden ser mapas de bits: no son iconos de controles
nativos y reemplazarlos por vectores inventados perdería su identidad. El icono de
launcher ya contiene capa monocroma. No se añade un animated-vector sin un estado real
que animar, ni se retiran controles del navegador solo para alcanzar un número de iconos.

## Evidencia y límites

| Área | Comprobación |
| --- | --- |
| Red auxiliar | Literales IPv4/IPv6, respuestas DNS mixtas/vacías, DNS cambiante antes de conectar, redirecciones HTTP/privadas/con credenciales, límite de cuatro solicitudes y límite de bytes |
| Separación de navegación | La prueba confirma que una URL de intranet sigue siendo válida para Gecko |
| Intents | ACTION_VIEW/BROWSABLE y flags definidos por Nexo; no se propagan extras, componente, selector o ClipData |
| Privacidad | Flags de ventana y herencia en hojas; se conservan otros flags al retirar privacidad |
| Diagnóstico | Usuario/contraseña en URL, consulta, rutas privadas y autorización no aparecen en el informe redactado; se conserva la pila |
| Memoria | Presión 15 no se confunde con un nivel menor que UI_HIDDEN; caché real reducida/vaciada |
| Identidad de pestañas | Colisiones de hash y estabilidad de IDs durante reordenación |
| Organización | Todas las acciones conservadas exactamente una vez y grupos limitados |
| Accesibilidad | Geometría 16/68 dp con fuente al 200 %, estado checkable y dependencias de ajustes |
| Regresiones | Pruebas JS, JVM debug/release, UI Robolectric y builds/lint de la app normal y de transición |

El harness de interfaz nuevo se ejecuta junto al de temas en CI y antes de release:

```sh
node --test app/src/test/js/*.test.cjs
gradle :app:testDebugUnitTest -PnexoScreenshots \
  --tests '*UiThemeRegressionTest' --tests '*SkillPracticeRegressionTest'
gradle :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease
# Repetir las tareas ordinarias con -PnexoTransition para validar el paquete anterior.
```

Las pruebas JVM no arrancan el motor en un teléfono. Permanecen pendientes la matriz
física de memoria/proceso muerto, capturas/recents/PiP, TalkBack/teclados OEM, navegación
con proxies y la medición de arranque/frames/audio. No se declara certificación de
seguridad, supervivencia total al proceso muerto ni rendimiento universal a partir de
estos checks. La fusión y el lanzamiento se realizan tras la revisión del usuario.
