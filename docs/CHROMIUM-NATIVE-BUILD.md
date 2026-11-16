# Chromium nativo: selección y build reproducible

## Estado de fase 1

Esta fase define cómo se selecciona y reproduce el motor; no integra todavía un navegador ni modifica el motor de release. `main` sigue siendo Gecko. El PR #45 documenta la verificación de estado y los bloqueos. La rama de trabajo deriva de `main` con SHA `d88e8e38210b0db6db1c441eb356de1bb320285f`; no arrastra los cambios de código inestables del PR #44 fusionado.

## Decisión de implementación

Usar un checkout Chromium fijado por SHA, build Android desde Linux con `depot_tools` y un embedder de Content/WebLayer propio para Nexo. No usar el System WebView como fallback. WebLayer upstream está archivado/deprecado como API pública; tratarlo solo como referencia de arquitectura, no como SDK mantenido. No compilar `chrome` completo y asumir que eso constituye un embedder de navegador para la app.

## Selección del milestone y revision pin

No escoger un SHA por ser el más reciente. Antes de fijarlo, evaluar una revisión que:

1. tenga Android arm64-v8a y armeabi-v7a soportados por el producto;
2. permita construir y ejecutar el embedder mínimo `content_shell` o equivalente de Content en un runner Linux reproducible;
3. tenga todos los componentes necesarios para embedding: Content, compositor, renderer sandbox/process, navegación, red, cookies/storage y media;
4. permita construir una biblioteca compartida JNI para app Android sin depender del paquete instalado de System WebView;
5. tenga licencia/NOTICE y superficie de actualización de seguridad auditables;
6. compile en una imagen builder con recursos medidos, no asumidos.

El SHA exacto no está fijado aún: no hay builder Chromium ni artefacto nativo en el repositorio para validar una revisión. Registrar el primer SHA solo después de una compilación repetible arm64 y una prueba de arranque/navegación en dispositivo. Luego guardar el pin en `third_party/chromium/REVISION` (SHA completo), junto a versión de `depot_tools`, imagen Linux builder, argumentos GN y hashes de artefactos.

## Build reproducible

La guía upstream oficial es [Checking out and building Chromium for Android](https://chromium.googlesource.com/chromium/src/+/main/docs/android_build_instructions.md). Requiere un host Linux y `depot_tools`; los comandos exactos dependen del SHA elegido.

Flujo que debe automatizarse tras fijar la revisión:

1. Aprovisionar una imagen Ubuntu builder por digest; instalar dependencias con `build/install-build-deps.sh` según la guía del pin.
2. Obtener el checkout mediante `fetch`/`gclient` y comprobar `git rev-parse HEAD` == `third_party/chromium/REVISION`.
3. Generar GN args versionados por ABI (primera meta: arm64; añadir armeabi-v7a al medir coste/soporte), con símbolos release y sandbox habilitado.
4. Construir con `autoninja` los targets de Content y el embedder, no el APK Chrome completo salvo que una dependencia lo exija y quede justificada.
5. Producir `libnexo_chromium_native.so`, assets/resources/paks necesarios, NOTICE/licencias y manifiesto de hashes.
6. Importar artefactos en CI desde un almacén inmutable; verificar hash, ABI y revisión antes del empaquetado. El checkout y binarios pesados no se guardan en Git.

CI actual usa `ubuntu-latest` para Kotlin/Gecko; no se ha demostrado que tenga almacenamiento/tiempo suficiente para compilar Chromium. La decisión builder-hosted vs. self-hosted queda pendiente de una medición de checkout/build; ningún workflow debe descargar/build Chromium en cada PR sin medir coste.

## Artefactos y seguridad

- Pin obligatorio por SHA completo; no `main`, `lkgr` ni tags mutables como pin de producción.
- Build hermético con dependencias/submodules revisados; registrar `gn args`, toolchain, builder image digest, SHA de fuente y hashes de salida.
- SBOM/NOTICE de Chromium y dependencias, revisión de licencias, política de CVE y ventana de actualización antes de distribuir APK.
- No incluir claves de firma en builder ni almacenar keystore/credenciales con los artefactos del motor.
- El prototipo JNI debe devolver “no listo” hasta enlazar el motor real; no mostrar navegador en blanco ni sustituirlo con WebView.

## Gate de salida de fase 1

Fase 1 termina cuando se añadan al repo: SHA upstream completo; builder reproducible; GN args por ABI; target embedder identificado; artefacto arm64 construido dos veces con hashes documentados; dependencias/licencias inventariadas; tiempo y almacenamiento del build medidos. El primer APK de prueba queda para fase 2, junto con la integración de Content/JNI.

## Referencias

- [Android Chromium build instructions](https://chromium.googlesource.com/chromium/src/+/main/docs/android_build_instructions.md)
- [Chromium source and build overview](https://www.chromium.org/developers/how-tos/get-the-code/)
- [WebLayer README / deprecation notice](https://chromium.googlesource.com/chromium/src/+/main/weblayer/README.md)
