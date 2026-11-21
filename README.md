<div align="center">
  <img src="docs/brand/nexo.svg" alt="Monograma de Nexo Browser" width="96" />

  <h1>Nexo Browser</h1>
  <p>Navegador nativo para Android con Chromium, bloqueo integrado y herramientas para leer, buscar y organizar la web.</p>
  <p><strong>v0.39.2 · Liquid Glass</strong> · Android 10+ · Chromium 156 · Cefrium 0.21.0</p>
  <p><a href="#descargar">Descargar APK</a> · <a href="#primeros-pasos">Primeros pasos</a> · <a href="#compatibilidad-de-extensiones">Extensiones</a> · <a href="#desarrollo">Desarrollo</a></p>
</div>

## Descargar

**Nexo Browser v0.39.2** · `versionCode 76` · Paquete `com.nexo.browser`

| Dispositivo | Tamaño | APK firmado |
| --- | ---: | --- |
| Android ARM de 64 bits | 111,2 MiB | [Descargar arm64-v8a](https://github.com/stivencop414-bot/Nexo-Browser/releases/download/v0.39.2/Nexo-v0.39.2-arm64-v8a.apk) |
| Android ARM de 32 bits | 83,8 MiB | [Descargar armeabi-v7a](https://github.com/stivencop414-bot/Nexo-Browser/releases/download/v0.39.2/Nexo-v0.39.2-armeabi-v7a.apk) |

Puedes consultar la lista oficial de archivos y sus sumas. [Ver release v0.39.2](https://github.com/stivencop414-bot/Nexo-Browser/releases/tag/v0.39.2) · [Descargar SHA256SUMS.txt](https://github.com/stivencop414-bot/Nexo-Browser/releases/download/v0.39.2/SHA256SUMS.txt)

<details>
<summary>Elegir arquitectura y comprobar la descarga</summary>

Comprueba las arquitecturas admitidas con el teléfono conectado por ADB.

```bash
adb shell getprop ro.product.cpu.abilist
```

Elige `arm64-v8a` si aparece en la lista. Si el dispositivo admite ARM de 32 bits, usa `armeabi-v7a`. En Linux, guarda el APK y `SHA256SUMS.txt` en la misma carpeta para verificar la descarga.

```bash
sha256sum --ignore-missing -c SHA256SUMS.txt
```

Las sumas oficiales para esta versión son las siguientes.

| APK | SHA-256 |
| --- | --- |
| `Nexo-v0.39.2-arm64-v8a.apk` | `998cbd9c039d7c7052b8112c55e8c9a031f378bf2b66f7d04d8c5dfd1ee28cd1` |
| `Nexo-v0.39.2-armeabi-v7a.apk` | `091f6a9de85e038fb554a65fde39e365edfd3ebbecbe811b06f294433b7d84e7` |

Las actualizaciones conservan los datos cuando el APK mantiene el paquete y el certificado de firma y aumenta el `versionCode`. Consulta la ruta de importación si vienes del paquete anterior. [Firma y actualizaciones](docs/ACTUALIZACIONES-Y-FIRMA.md) · [Transferencia de perfil](docs/TRANSFERENCIA-PERFIL.md)

</details>

## Novedades en v0.39.2

| Área | Qué cambia |
| --- | --- |
| **Liquid Glass** | Isla de 56 dp y contador de pestañas separado sobre la página. Temas Obsidian, OLED y Paper. Intensidad de cristal ajustable y Dock Clásico disponible. |
| **Reanudación** | Nexo mantiene una captura de la página mientras Cefrium recupera la superficie. Un watchdog de 450 ms puede recrear el navegador de la pestaña y restaurar el estado de navegación. |
| **Memoria** | Las pestañas normales aptas se descartan tras cinco minutos de inactividad. Las miniaturas nuevas se guardan en WebP. En equipos con más de 4 GiB, Nexo prepara un host Cefrium en reposo. |
| **Extensiones de escritorio** | Verificación CRX3 multifirma, popups con viewport adaptable y origen por extensión, y reglas DNR estáticas evaluadas en el interceptor de PhantomShield. |

La auditoría detalla las pruebas JVM y las mediciones pendientes en Android ARM, entre ellas los diez ciclos de reanudación, el tiempo de fotograma a 120 Hz y la ejecución del popup de Dark Reader. [Auditoría v0.39.2](docs/AUDITORIA-0.39.2.md)

<p align="center">
  <img src="docs/capturas/v0.39.2-paper.png" alt="Configuración de Nexo en tema Paper" width="215" />
  <img src="docs/capturas/v0.39.2-oled.png" alt="Configuración de Nexo en tema OLED" width="215" />
</p>
<p align="center"><sub>Configuración en Paper y OLED · vistas nativas renderizadas con Robolectric en API 35.</sub></p>

## Primeros pasos

| Quiero… | Ejemplo |
| --- | --- |
| **Cambiar el tema y la barra** | Abre **Configuración › Apariencia** y elige **Paper** para un fondo marfil. En **Navegación › Intensidad del cristal**, prueba **Sutil**, **Equilibrado** o **Máximo Cristal**. |
| **Volver a una pestaña abierta** | Escribe `@tabs android` en la barra de direcciones para buscar «android» entre tus pestañas y grupos. Las búsquedas con prefijo se procesan de forma local. |
| **Leer un artículo sin conexión** | Abre el artículo, escribe `@actions lectura` y elige **Activar modo lectura**. Pulsa **Guardar sin conexión** y vuelve al artículo con `@actions sin conexion`. |
| **Ajustar un sitio** | Toca el icono junto al dominio para abrir **Control del sitio**. Desde ahí puedes ajustar el zoom, el modo oscuro y los permisos del sitio. |
| **Revisar una extensión antes de activarla** | Abre **Extensiones**, busca **uBlock Origin Lite** en Chrome Web Store y pulsa **Instalar en Nexo** en su ficha. Revisa los permisos, la firma y el veredicto del Hub antes de habilitarla. |

Nexo Command ofrece también `@history`, `@settings` y `@bookmarks`. Prueba `@settings tema` para abrir el ajuste de apariencia desde la barra de direcciones.

## Herramientas del navegador

| Función | Uso |
| --- | --- |
| **Tabs Pro y Workspace** | Cambia entre cuadrícula, vista compacta y lista. Agrupa pestañas, detecta duplicados, fija páginas y guarda espacios de trabajo. Los favoritos admiten carpetas e importación o exportación en HTML Netscape y JSON. |
| **Nexo Reader y traducción** | Lee en una vista nativa, ajusta tipografía e interlineado, usa la voz instalada en Android y guarda hasta 50 artículos o 10 MB. Puedes traducir una página desde el menú y recuperar el original. |
| **PhantomShield y Burner** | Bloqueo de red y cosmético, limpieza de parámetros de rastreo y excepciones por sitio. Burner añade un perfil separado y correo temporal. [PhantomShield](docs/PHANTOMSHIELD.md) · [Grupos y Burner](docs/PESTANAS-RED-BURNER.md) |
| **Multimedia y subtítulos** | PiP, gestos en vídeo a pantalla completa, velocidad, MediaSession y reproducción en segundo plano. Los subtítulos en vivo admiten transcripción con Deepgram cuando Android permite capturar el audio. [Diagnóstico de subtítulos](docs/DIAGNOSTICO-SUBTITULOS.md) |
| **Nexo AI 5.1** | Conversaciones persistentes, sesión minimizable durante el streaming y laboratorio para comparar modelos. El contexto de página se comparte con permiso explícito. [Guía de Nexo AI](docs/NEXO-AI-5.1.md) |

## Compatibilidad de extensiones

El Hub acepta fichas de **Chrome Web Store** y **Edge Add-ons**, archivos locales CRX/ZIP/XPI y paquetes HTTPS directos. NXR revisa el manifiesto, las firmas, los permisos y la continuidad de clave antes de la instalación o actualización.

| Capacidad | Alcance en v0.39.2 |
| --- | --- |
| **CRX3 y manifiestos MV2/MV3** | El instalador interpreta el manifiesto, verifica las firmas del CRX y ofrece un informe de compatibilidad. |
| **Scripts, CSS y popups** | NXR ejecuta scripts declarados en sitios permitidos y abre popups y opciones en un panel Cefrium con origen separado por extensión. El panel expone un conjunto limitado de APIs. |
| **DNR estático** | PhantomShield aplica reglas admitidas de bloqueo y excepción a subrecursos. La prueba JVM con uBlock Origin Lite admitió 11.624 reglas y bloqueó tres solicitudes de prueba. |
| **APIs pendientes** | Service Workers MV3 de extensión, Ports por marco, sincronización entre contextos, mundos V8 aislados, reglas DNR dinámicas, redirecciones y modificación de cabeceras. |

El veredicto del Hub describe las capacidades disponibles para cada paquete. Los permisos nuevos de una actualización requieren confirmación. [Guía del Hub, matriz y ejemplo de ruleset](docs/NEXO-EXTENSION-HUB.md)

## Desarrollo

**Requisitos** · JDK 25, Node.js 20+, Android SDK `platforms;android-37.2` y `build-tools;37.0.0`. El wrapper usa Gradle 9.8.2. La app se compila con SDK 37.2, `minSdk 29` y `targetSdk 35`. Cefrium se distribuye bajo LGPL-3.0 y las huellas de sus dependencias están fijadas en el repositorio. [Huellas de Cefrium](gradle/cefrium-0.21.0.sha256) · [Configuración del CI de Cefrium](docs/CEFrium-CI-SETUP.md)

```bash
# Regresiones JavaScript
node --test app/src/test/js/*.test.cjs

# APK de depuración, pruebas JVM y lint
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# Temas y accesibilidad con Robolectric
./gradlew :app:testDebugUnitTest -PnexoScreenshots \
  --tests '*UiThemeRegressionTest' --tests '*SkillPracticeRegressionTest'
```

<details>
<summary>Arquitectura, seguridad y publicación</summary>

| Componente | Entrada en el código |
| --- | --- |
| UI Kotlin/XML y material | `activity_main.xml`, `MainActivity`, `NexoTokens`, `NexoGlass`, `NexoGlassBackdrop`, `GlassPreferences` |
| Cefrium y ciclo de vida | `engine/cefrium/`, `CefResumeBridge`, `CefriumEngineSession`, `SpareRendererPool` |
| Pestañas y miniaturas | `TabManager`, `TabFreezeManager`, `TabPreviewStore`, `TabSwitcher` |
| NXR y privacidad | `extensions/hub/`, `extensions/nxr/`, `Crx3Parser`, `DnrBridge`, `shield/PhantomShield` |
| Herramientas de página | `PageBridge`, `PageChannel`, `assets/nexo/`, `reader/`, `workspace/`, `ai/` |

La integración consume la API pública `com.cefrium.*`. Las claves de proveedores se almacenan con Android Keystore. El historial, las miniaturas y los espacios de trabajo excluyen las pestañas privadas. [Arquitectura de Chromium nativo](docs/CHROMIUM-NATIVE-MIGRATION.md)

**CI y firma.** GitHub Actions ejecuta regresiones JavaScript y JVM, pruebas Robolectric, compilación y lint. El flujo de release verifica la identidad del APK y el certificado de firma antes de publicar los dos APK y su archivo de sumas. Los secretos se usan en el paso de firma. [Flujo de GitHub Actions](.github/workflows/main.yml) · [Firma y publicación](docs/ACTUALIZACIONES-Y-FIRMA.md)

</details>

## Documentación

| Tema | Guías |
| --- | --- |
| **v0.39.2** | [Auditoría y estado de validación](docs/AUDITORIA-0.39.2.md), [Extension Hub](docs/NEXO-EXTENSION-HUB.md) |
| **Interfaz y organización** | [Nexo UI 7.0, referencia histórica](docs/NEXO-UI-7.0-LIQUID-CRAFT.md), [Grupos, rutas y Burner](docs/PESTANAS-RED-BURNER.md) |
| **Navegación y multimedia** | [PhantomShield](docs/PHANTOMSHIELD.md), [Traducción en página](docs/TRADUCCION-EN-LINEA.md), [Audio y estabilidad](docs/ESTABILIDAD-Y-AUDIO-ANDROID.md) |
| **IA y datos** | [Nexo AI 5.1](docs/NEXO-AI-5.1.md), [Memoria IA local](docs/IA-MEMORIA.md), [Transferencia de perfil](docs/TRANSFERENCIA-PERFIL.md) |
| **Desarrollo y distribución** | [Migración a Chromium](docs/CHROMIUM-NATIVE-MIGRATION.md), [Identidad Android](docs/IDENTIDAD-NEXO.md), [Actualizaciones y firma](docs/ACTUALIZACIONES-Y-FIRMA.md) |

<details>
<summary>Historial de diseño y auditorías</summary>

[Flow 4.0](docs/NEXO-FLOW-V4-IMPLEMENTATION.md) · [Diseño Flow 3.0](docs/NEXO-FLOW-V3-DESIGN.md) · [Validación V3](docs/NEXO-V3-IMPROVEMENTS.md) · [Auditoría V2](docs/AUDITORIA-ESTABILIDAD-OPTIMIZACION-V2.md) · [Auditoría UI 0.30.0](docs/AUDITORIA-UI-0.30.0.md) · [Auditoría Kotlin y PhantomShield](docs/AUDITORIA-BROWSER-SHIELD.md) · [Plan histórico WebView](docs/MIGRACION-CHROMIUM-WEBVIEW.md) · [Plan histórico Cefrium](docs/PLAN-TRABAJO-CHROMIUM.md)

</details>
