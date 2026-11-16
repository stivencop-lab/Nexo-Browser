# Cefrium prototype

Standalone arm64 smoke project for Cefrium 0.21.0 (Chromium 156.0.8078.96). It is intentionally separate from the main build: Cefrium requires JDK 25, Gradle 9.7.1+, AGP 9.4+ and minSdk 29.

## Build

Use Linux or a JDK 25 environment, then run `gradle :app:assembleDebug --no-daemon` from this directory. Install the generated arm64 APK on Android 10+ and verify that `https://example.com/` renders.

The project uses only the public `com.cefrium.*` API. Do not import `org.chromium.*` or `GEN_JNI`.

Before distributing an artifact, verify it against `gradle/cefrium-0.21.0.sha256` and the official signed Codeberg release sums, SBOM and NOTICE.


## Local smoke result

The arm64 debug prototype compiled successfully on Windows with JDK 25.0.5, Gradle 9.7.1, Android platform 37.2 and build-tools 37.0.0. Generated APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`; local SHA-256: `D7AF44C747B854E0305852B7951EAE84E6C3EB4E2E449C44668A5C14FFF4F871`. The APK is a temporary build artifact and is not committed. Device rendering is still pending on an Android 10+ arm64 device.
