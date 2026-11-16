# Cefrium prototype

Standalone arm64 smoke project for Cefrium 0.21.0 (Chromium 156.0.8078.96). It is intentionally separate from the main build: Cefrium requires JDK 25, Gradle 9.7.1+, AGP 9.4+ and minSdk 29.

## Build

Use Linux or a JDK 25 environment, then run `gradle :app:assembleDebug --no-daemon` from this directory. Install the generated arm64 APK on Android 10+ and verify that `https://example.com/` renders.

The project uses only the public `com.cefrium.*` API. Do not import `org.chromium.*` or `GEN_JNI`.

Before distributing an artifact, verify it against `gradle/cefrium-0.21.0.sha256` and the official signed Codeberg release sums, SBOM and NOTICE.
