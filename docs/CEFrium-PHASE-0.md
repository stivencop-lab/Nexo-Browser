# Fase 0 — Cefrium como Chromium nativo

## Decisión aprobada

- Objetivo: Cefrium-only desde Android API 29.
- SDK inicial: Cefrium 0.21.0.
- Chromium informado por el POM: 156.0.8078.96, CEF branch 8078.
- El build actual no cambia todavía: usa JDK 21/AGP 9.5 y minSdk 26. Cefrium requiere JDK 25, Gradle 9.7.1, AGP 9.4, compileSdk 37 y minSdk 29.

## Artefactos comprobados

El POM es agregador; los AAR reales son core y tres artefactos de codecs por ABI. Sus SHA-256, además del SHA del POM, están en gradle/cefrium-0.21.0.sha256. Los sidecars .sha256 del Maven fueron descargados y coincidieron con los hashes calculados localmente.

## Verificación pendiente

Las URLs Maven comprobadas no expusieron un .asc junto a los AAR. La release oficial declara SHA256SUMS.asc; antes de integrar hay que descargarlo desde la release 0.21.0, verificarlo con la huella pública del mantenedor y comparar sus hashes.

Hasta entonces no se integran AARs, no se cambia Engines.current y no se elimina Gecko/WebView.

## Siguiente paso

Crear un build Cefrium aislado con JDK 25 y toolchain compatible, inicialmente solo arm64-v8a, validar el AAR y arrancar un prototipo mínimo de navegación.

Referencias: https://cefrium.com/quickstart/ · https://cefrium.com/releases/ · https://cefrium.com/guides/adopting-cefrium/ · https://codeberg.org/cefrium/cef-android
