# Migración a Chromium nativo — estado y criterios de corte

## Situación verificada en main

- El motor seleccionado por `app/src/gecko/java/.../Engines.kt` es Gecko.
- El flavor `chromium` depende de Android System WebView y `androidx.webkit`; no es Chromium embebido.
- El PR anterior #44 fue fusionado, pero su último CI falló en la compilación de Chromium. Errores: imports/funciones duplicados en `ExtensionManager.kt`, `appContext` sin definir, `ExtensionManager` sin resolver.
- `chromiumNative` y el esqueleto JNI estaban solo en una rama ya fusionada/eliminada; no existen en `main`.
- La ruta nativa no está lista para ser el engine por defecto.

## Bloqueos antes de cambiar la app publicada

1. Integrar una compilación fijada de Chromium Content/WebLayer y embedder JNI real. Una biblioteca stub no es un motor.
2. Implementar el `BrowserEngine` completo: vista/renderizador, navegación, storage/cookies, perfiles, sandbox, red, ventanas, permisos, descargas y recuperación de renderer.
3. Portar media, PiP, fullscreen, PDF, subtítulos, PhantomShield y runtime de extensiones al embedder nativo.
4. Compilar variantes arm64-v8a y armeabi-v7a con hash del artefacto y licencias auditadas.
5. Pasar las suites JVM/JS/CI y pruebas en dispositivo físico; probar actualización firmada conservando datos.
6. Solo entonces cambiar `Engines.current` de Gecko a Chromium nativo, actualizar CI/release y retirar Gecko/WebView.

No borrar Gecko ni WebView mientras el runtime nativo no cumpla esos gates: hacerlo ahora dejaría el proyecto sin un navegador funcional/buildable.
