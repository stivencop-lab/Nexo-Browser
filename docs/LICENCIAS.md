# Licencias y avisos de terceros

## Código propio de Nexo

El código propio de Nexo Browser se distribuye bajo **Apache License 2.0** (`Apache-2.0`). Al redistribuirlo, incluye una copia de la licencia, conserva los avisos aplicables e indica los archivos que hayas modificado, según la sección 4. Los componentes de terceros conservan sus propios términos. [Texto íntegro de Apache License 2.0](../LICENSE)

## Motor Cefrium, CEF y Chromium

Nexo compila con **Cefrium 0.22.0**, que integra **Chromium 158.0.8127.18**. Gradle resuelve `cefrium-sdk-core` y los módulos nativos de códecs para ARM de 64 y 32 bits. Cada APK lleva las bibliotecas de su arquitectura. La licencia declarada para el SDK es **LGPL-3.0-or-later**. El código original de CEF usa **BSD-3-Clause** y los componentes integrados de Chromium conservan sus propias licencias. [Dependencias de Nexo](../app/build.gradle.kts) · [POM del SDK](https://maven.cefrium.com/com/cefrium/cefrium-sdk-core/0.22.0/cefrium-sdk-core-0.22.0.pom) · [Licencias del motor](https://codeberg.org/cefrium/cef-android/src/tag/v0.22.0/LICENSE-CEFRIUM.md)

| Recurso | Referencia para Cefrium 0.22.0 |
| --- | --- |
| Código fuente y publicación | [Etiqueta v0.22.0](https://codeberg.org/cefrium/cef-android/src/tag/v0.22.0) · [Publicación oficial](https://codeberg.org/cefrium/cef-android/releases/tag/v0.22.0) |
| Textos de LGPL y GPL que acompañan al SDK | [LGPL-3.0-or-later](https://codeberg.org/cefrium/cef-android/src/tag/v0.22.0/LICENSES/LGPL-3.0-or-later.txt) · [GPL-3.0-or-later](https://codeberg.org/cefrium/cef-android/src/tag/v0.22.0/LICENSES/GPL-3.0-or-later.txt) |
| Licencia de CEF | [BSD-3-Clause](https://codeberg.org/cefrium/cef-android/src/tag/v0.22.0/LICENSES/BSD-3-Clause.txt) |
| Atribuciones e inventario de componentes | [NOTICE oficial](https://codeberg.org/cefrium/cef-android/releases/download/v0.22.0/cefrium-sdk-0.22.0-NOTICE.txt) · [Inventario SPDX del SDK](https://codeberg.org/cefrium/cef-android/releases/download/v0.22.0/cefrium-sdk-0.22.0.spdx.json) |
| Integridad de los artefactos | [Huellas fijadas por Nexo](../gradle/cefrium-0.22.0.sha256) · [Sumas del proveedor](https://codeberg.org/cefrium/cef-android/releases/download/v0.22.0/SHA256SUMS-0.22.0) |

## Recursos incluidos y dependencias directas

| Componente | Licencia o aviso |
| --- | --- |
| Dark Reader 4.9.134, usado por Nexo Dark Engine | MIT. [Script incluido](../app/src/main/assets/nexo/bridge/darkreader.js) · [Texto de licencia](../app/src/main/assets/nexo/bridge/darkreader-LICENSE.txt) |
| Plus Jakarta Sans y JetBrains Mono, usadas en la app y PhantomShield | SIL Open Font License 1.1. [Plus Jakarta Sans](../app/src/main/assets/licenses/PlusJakartaSans-OFL.txt) · [JetBrains Mono](../app/src/main/assets/licenses/JetBrainsMono-OFL.txt) |
| Aviso conservado de Inter | SIL Open Font License 1.1. [Texto de licencia](../app/src/main/assets/licenses/Inter-OFL.txt) |
| AndroidX y OkHttp | Apache 2.0. OkHttp también incluye un aviso para su lista de sufijos públicos. [AndroidX](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) · [OkHttp 4.12.0](https://github.com/square/okhttp/blob/parent-4.12.0/LICENSE.txt) |
| MaterialKolor, módulo de utilidades de color | MIT según los metadatos de la versión que usa Nexo. [POM de la versión 3.0.1](https://repo.maven.apache.org/maven2/com/materialkolor/material-color-utilities/3.0.1/material-color-utilities-3.0.1.pom) |
| RE2/J 1.8 | Licencia BSD de tres cláusulas derivada de Go, con los avisos del proyecto. [Texto de licencia](https://github.com/google/re2j/blob/re2j-1.8/LICENSE) |

Las dependencias transitivas se resuelven con Gradle. Para revisar el árbol de una variante de publicación, ejecuta `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` y consulta los avisos de los artefactos resueltos. El inventario SPDX enlazado arriba corresponde al SDK de Cefrium.

Las copias de BlockAdBlock y FuckAdBlock se utilizan en pruebas JavaScript y conservan sus cabeceras MIT. Por su parte, PhantomShield descarga listas de filtros como EasyList, EasyPrivacy y uBlock filters a partir de su catálogo de fuentes. Para cada lista, consulta los términos de su editor. [Materiales de prueba](../app/src/test/js/fixtures/ANTI-BLOCK-SOURCES.md) · [Catálogo de filtros](../app/src/main/assets/extensions/phantomshield/background/filter-lists.js) · [EasyList](https://easylist.to/pages/licence.html) · [uBlock filters](https://github.com/uBlockOrigin/uAssets/blob/master/LICENSE)

## Avisos en la distribución Android

En los dos APK firmados de `v0.40.0` figuran los avisos OFL y MIT de las fuentes y Dark Reader, además de avisos de AndroidX y de la lista de sufijos públicos de OkHttp. El archivo NOTICE oficial de Cefrium se publica en Codeberg y falta como archivo separado en esos APK. [Distribución de Nexo v0.40.0](https://github.com/stivencop-lab/Nexo-Browser/releases/tag/v0.40.0) · [NOTICE de Cefrium](https://codeberg.org/cefrium/cef-android/releases/download/v0.22.0/cefrium-sdk-0.22.0-NOTICE.txt)

Al distribuir una compilación con Cefrium bajo LGPL, acompaña los binarios con los avisos aplicables y los textos de GPL y LGPL. La sección 4 también exige una vía válida de reemplazo o reenlace de la biblioteca que funcione para esa compilación. Documenta cómo se obtiene el código fuente correspondiente y cómo se cumple esa condición para los APK finales. [LGPL 3.0, sección 4](https://spdx.org/licenses/LGPL-3.0-or-later.html) · [Publicación de APK](ACTUALIZACIONES-Y-FIRMA.md)
