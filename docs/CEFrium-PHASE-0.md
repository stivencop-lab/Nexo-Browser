# Fase 0 — Cefrium como Chromium nativo

## Decisión aprobada

- Objetivo: Cefrium-only desde Android API 29.
- SDK inicial: Cefrium 0.21.0.
- Chromium informado por el POM: 156.0.8078.96, CEF branch 8078.
- El build actual no cambia todavía: usa JDK 21/AGP 9.5 y minSdk 26. Cefrium requiere JDK 25, Gradle 9.7.1, AGP 9.4, compileSdk 37 y minSdk 29.

## Artefactos comprobados

El POM es agregador; los AAR reales son core y tres artefactos de codecs por ABI. Sus SHA-256, además del SHA del POM, están en gradle/cefrium-0.21.0.sha256. Los sidecars .sha256 del Maven fueron descargados y coincidieron con los hashes calculados localmente.

## Verificación completada

La release oficial de Codeberg `v0.21.0` aporta `SHA256SUMS-0.21.0`, `SHA256SUMS-0.21.0.asc`, SBOM SPDX y NOTICE. La firma detached se verificó localmente con GnuPG contra la huella pública `2A84 0ED0 0DE6 69F4 219B 8B33 02A7 9E3B 8215 1695`; GnuPG reportó `GOODSIG` y `VALIDSIG` de la clave `5B639D9BFE995C6418CEFA8970CB7258A38E268F`.

Los hashes oficiales de los cuatro AAR por ABI coinciden con Maven y con los hashes locales. El SBOM y NOTICE fueron descargados y quedan registrados en `gradle/cefrium-0.21.0.sha256`.

La integración de código sigue bloqueada hasta preparar el build aislado JDK 25/AGP 9.4/Gradle 9.7.1; no se cambia `Engines.current` ni se elimina Gecko/WebView en esta fase.

## Siguiente paso

Crear un build Cefrium aislado con JDK 25 y toolchain compatible, inicialmente solo arm64-v8a, validar el AAR y arrancar un prototipo mínimo de navegación.

Referencias: https://cefrium.com/quickstart/ · https://cefrium.com/releases/ · https://cefrium.com/guides/adopting-cefrium/ · https://codeberg.org/cefrium/cef-android
