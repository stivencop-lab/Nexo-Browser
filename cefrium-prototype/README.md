# Cefrium prototype

Standalone arm64 smoke project for Cefrium 0.21.0 (Chromium 156.0.8078.96). It is intentionally separate from the main build: Cefrium requires JDK 25, Gradle 9.7.1+, AGP 9.4+ and minSdk 29.

## Build

Use the checked-in wrapper:

    gradlew :app:assembleDebug --no-daemon

Install the generated arm64 APK on Android 10+ and verify that https://example.com/ renders.

The prototype includes CefriumSession, a public-API-only adapter covering surface creation, navigation, back/forward, incognito profile selection and state hooks. Do not import org.chromium.* or GEN_JNI.
