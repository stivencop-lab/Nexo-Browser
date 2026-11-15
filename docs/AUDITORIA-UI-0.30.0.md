# Nexo 0.30.0: revisión de la skill y aplicación a la interfaz nativa

## Criterio de implementación

Se revisó el documento adjunto `ultimate_android_ui_ux_day_night_master_skill_skill_md.md`.
Sus principios de color semántico, contraste, accesibilidad, áreas seguras y adaptación
son aplicables a Nexo. Sus archivos de ejemplo son plantillas de otra arquitectura:
Nexo usa Activities, Views/XML y GeckoView, con preferencias y sesiones existentes.
No se incorporan Compose, WebView ni una segunda fuente de preferencias solo para
copiar esas plantillas. La selección Sistema/Claro/Midnight/OLED conserva sus claves
actuales y las actualizaciones conservan los datos de la aplicación.

La skill es una referencia de diseño; sus ejemplos no sustituyen las APIs públicas
del proyecto, las pruebas ni el proceso de firma y publicación existente.

## Revisión técnica del documento

- **Tokens de color:** la tabla llamada «29 roles» incluye roles adicionales de
  superficies. Importa cubrir las funciones utilizadas y sus pares de texto/fondo,
  no declarar un número de propiedades sin conectarlas a las vistas.
- **HCT:** copiar hexadecimales no genera una paleta para los siete acentos de Nexo.
  `BrowserColors` usa SchemeTonalSpot, contraste normal y especificación 2021 explícita
  de `material-color-utilities` 3.0.1 (MaterialKolor, API pública, sin Compose).
  No se usan las clases internas de la biblioteca Material para Android.
- **Contraste:** un fondo de vídeo al 86 % no garantiza por sí solo una relación
  determinada en todos los fotogramas. Los botones Mini/CC usan superficie opaca
  y texto semántico. Los subtítulos conservan las opciones de color/fondo que eligió
  el usuario; no se certifica contraste de un subtítulo transparente sobre cualquier vídeo.
- **Tema del sistema:** Nexo sigue `Configuration.UI_MODE_NIGHT_MASK`; no convierte
  la selección «Sistema» en un horario solar ni sobrescribe esa preferencia al resolverla.
- **Persistencia:** migrar solo el tema a DataStore introduciría otra fuente de verdad
  y una migración innecesaria. Se conservan SharedPreferences y la recreación de Activity
  ya existente; no se promete recomposición Compose ni ausencia absoluta de parpadeo.
- **Insets:** `ADJUST_RESIZE` y padding de IME no deben sumarse sin definir quién es
  responsable. Cada ventana tiene un único propietario que combina barras, teclado y
  recorte con `max`, consume lo aplicado y no acumula padding en cada despacho.
- **Adaptación:** los controles de Atrás/Recargar/Nueva pestaña son acciones de un
  navegador, no destinos de navegación. No se convierten artificialmente en un drawer.
  Se limita el ancho de lectura y de hojas y se adapta la cuadrícula de acciones/inicio.
- **Movimiento:** se aplica un resorte amortiguado al gesto de las hojas; se respeta
  la desactivación de animaciones del sistema. No se añaden animaciones a cada cambio
  de color, contador o vídeo que puedan provocar trabajo continuo por fotograma.
- **Auditoría:** un grep no distingue una máscara de ripple, un logo o un color de
  subtítulo elegido por el usuario de un color de interfaz codificado sin semántica.
  Del mismo modo, una captura Robolectric no sustituye TalkBack ni una prueba en teléfono.

## Cambios incluidos

`BrowserPalette` define familias primary/secondary/tertiary/error y success/warning/info,
con sus colores de contenido y contenedor, niveles de superficie, bordes, colores
inversos y scrim. Sus alias mantienen la compatibilidad de los componentes existentes.
Las paletas se reutilizan mediante una caché acotada de 16 entradas que no guarda Activities.

Los temas nativos comparten tokens HCT para textos, acentos, selección, menús y diálogos.
OLED conserva un lienzo negro y contenedores delimitados. «Del sistema (Material You)»
aparece en Android 12 o posterior y usa el acento del sistema como semilla HCT; en versiones
anteriores utiliza cobalto si la preferencia llegó desde otra versión. La clave visual
incluye la semilla resuelta para detectar cambios de color al volver a la Activity.

Los controles de las barras alcanzan 48 dp; los campos, spinners y botones de texto
crecen con el contenido. Ajustes e inicio limitan el ancho de lectura a 720 dp; las hojas
usan un máximo de 640 dp. Los chips dejan de tener altura fija de 38 dp y usan la
semántica de selección. La opción de tamaño web añade el 200 % que ya admitían las preferencias.

Las ventanas aplican barras/teclado/cutout una vez. Las hojas recalculan el espacio útil
y mantienen contenido desplazable. Los iconos de las barras del sistema siguen el modo
claro/oscuro. El fondo de Gecko se establece con el tema antes de mostrar el contenido.

Mini tiene su propia acción accesible; los botones flotantes usan tokens opacos. La tienda
expone reintento y anuncia el estado de búsqueda sin borrar resultados anteriores tras
un error. El envío de audio, los proveedores de subtítulos, las rutas de red y el
aislamiento de pestañas conservan su comportamiento.

## Matriz de los 16 puntos

| Punto de la skill | Aplicación / evidencia | Límite de la afirmación |
| --- | --- | --- |
| 1. Colores semánticos | Motor HCT, recursos nativos, componentes comunes, inicio y HUD de botones | Logos, favicons y estilos de subtítulos tienen otros significados; no se afirma cero literales en todo el repositorio |
| 2. Roles y estados | Familias completas con pares foreground/container; pruebas de contraste | Los alias de la UI XML no son `MaterialTheme` de Compose |
| 3. Cuatro modos | Preferencias existentes, resolución del sistema, prueba con night/notnight y capturas | La Activity se recrea al cambiar su tema |
| 4. Dinámico | Semilla Android 12+, prueba API 30/31 y fallback | Usa SchemeTonalSpot sobre la semilla; no promete replicar cada paleta OEM |
| 5. Profundidad tonal | Niveles Low/Container/High/Highest y retirada de sombras en dock oscuro | No se rediseñan todos los paneles de extensiones externas |
| 6. Bordes OLED | Tarjetas/hojas comunes con outlineVariant, prueba de jerarquía tonal | Logos y superficies web pertenecen a su contenido |
| 7. Barras del sistema | WindowCompat sincroniza iconos y conserva flags de layout | Validar navegación de tres botones y OEM en dispositivo |
| 8. Insets | Pruebas de teclado + cutout + barras, sin acumulación al repetir | Falta matriz física de IME, rotación, PiP y Android 15/16 |
| 9. Espaciado y formas | Recursos compartidos, 48 dp y anchos máximos, componentes comunes | No se ha sustituido cada medida histórica de cada pantalla |
| 10. Texto y números | Spinners multilínea y contador de pestañas con cifras tabulares | No hay migración global a la escala tipográfica Compose de 15 estilos |
| 11. Fuente 200 % | Medición y capturas de ajustes en cuatro modos; campos/chips sin altura fija | No equivale a certificar todas las webs, idiomas o extensiones |
| 12. Accesibilidad | Hit areas, foco de acciones, estado seleccionado, Mini y búsqueda anunciada | TalkBack, switch access y teclado físico requieren comprobación manual |
| 13. Movimiento | SpringAnimation en asentamiento/cierre de hojas, sin rebote y con animación desactivable | No se han sustituido todas las animaciones antiguas de la app |
| 14. Asíncrono | Tienda mantiene loading/empty/content/error y añade recuperación visible | Fallos de red reales y proveedores externos necesitan su propia validación |
| 15. Estabilidad Compose | No aplicable; Nexo usa Views y RecyclerView | La mejora medible aquí es reutilizar paletas sin retener Activities, no recomposición |
| 16. Previews | Harness Robolectric con capturas claro/oscuro/OLED/sistema al 200 % y hoja tablet | No son previews Compose ni ejecución de Gecko en hardware |

## Validación reproducible

Usar JDK 21, Gradle 9.8.2 y el SDK indicado en README. Desde la raíz:

```sh
node --test app/src/test/js/*.test.cjs
gradle :app:testDebugUnitTest -PnexoScreenshots \
  -PnexoShotsDir="$PWD/app/build/reports/ui-previews" \
  --tests '*UiThemeRegressionTest' --no-daemon --max-workers=4
gradle :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease \
  --no-daemon --max-workers=4
# CI también valida el paquete de transición con las mismas tareas y -PnexoTransition.
```

El harness comprueba 107 pruebas JVM por variante, nueve casos de UI incluyendo APIs
26/30/31/35, cuatro modos, tamaño de fuente, ancho tablet, insets repetidos y acento nativo.
Las pruebas de color recorren los siete acentos y semillas límite: texto principal ≥ 7:1
y texto de apoyo, acciones y pares de estado ≥ 4.5:1 en superficies opacas utilizadas.
La CI incorpora el harness de UI antes de las tareas ordinarias y antes del flujo de release.

`tools/GenerateThemeColors.java` permite regenerar o comprobar `values/colors.xml` con
el JAR de `material-color-utilities-jvm` 3.0.1 y kotlin-stdlib en el classpath de Java 21:

```sh
java --class-path "$MCU_JAR:$KOTLIN_JAR" tools/GenerateThemeColors.java \
  --check app/src/main/res/values/colors.xml
```

Para publicar se mantiene el flujo de [actualizaciones y firma](ACTUALIZACIONES-Y-FIRMA.md).
Se comprueban identidad de paquete, constructores tras R8 y firma contra la huella pública
versionada. El keystore y sus contraseñas nunca forman parte de este PR ni de los artefactos
de prueba. El PR prepara 0.30.0 (`versionCode 59`); publicar en GitHub requiere primero
fusionar el código validado en main y ejecutar el workflow de release existente.
