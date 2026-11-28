# Cefrium prototype

Independent arm64 smoke project for Cefrium 0.22.0 (Chromium 158.0.8127.18). The project uses JDK 25, Gradle 9.9.2, AGP 9.5.3 and minSdk 29.

## Build

Set the JDK and SDK paths below. Install Android platform 37.2 and build-tools 37.0.0 before building.

    export JAVA_HOME=/path/to/jdk-25
    export ANDROID_HOME=/path/to/android-sdk
    ./gradlew :app:assembleDebug :app:lintDebug

The lint task uses the repository configuration for Cefrium's generated native service declarations.

APK output `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (arm64-v8a only, debug-signed; since 0.22.0 `cefrium-sdk-core` omits Google Play services / Firebase / ML Kit).

## Smoke test on a device (Android 10+, arm64)

    adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
    adb shell am start -n com.nexo.cefriumprototype/.MainActivity
    adb logcat -s NexoCefrium CefriumBrowser

A fresh start serves two loopback origins. The smoke run logs `PASS`/`FAIL` for rendering, the bridge's origin restriction, cookie access, back, forward, reload and state saving. The interceptor must block `/filtered` while the server receives `/control`. Navigation errors and timeouts produce a failure. Repeat the run in the incognito profile to check isolation.

    adb shell am force-stop com.nexo.cefriumprototype
    adb shell am start -n com.nexo.cefriumprototype/.MainActivity --ez incognito true

The incognito run must log `PASS profile does not see nexo_smoke`, and the next normal run `PASS profile does not see nexo_incog`.

Tap **Micrófono** on the loopback page to verify that the browser resolves the request after Android grants or denies the permission.

## Seams (public `com.cefrium.*` API only; never `org.chromium.*` or `GEN_JNI`)

| Class | Cefrium API |
|---|---|
| `CefriumSession` | `createWithSurface(activity, profile)`, navigation client, state save/restore |
| `CefriumShieldInterceptor` | `CefriumResourceInterceptor.shouldBlockRequest` (network thread) |
| `CefriumPageBridge` | `setQueryHandler(QueryOriginPolicy.exactly(...))`, main frame only |
| `CefriumCompositeChromeClient` | `setChromeClient` has one slot: fans callbacks out to the adapters below |
| `CefriumPermissionHandler` | `onPermissionRequest`; `grant()`/`deny()` answer the whole permission set |
| `CefriumWindowHandler` | `onCreateWindow(opener, popup, url, userGesture)`: the popup is a `CefriumBrowser` |
| `CefriumFullscreenHandler` | `onFullscreenChanged` |
| `CefriumMediaController` | `onMediaAccessChanged`, `isPlayingAudio`, `setAudioMuted`, `stopMediaSession` (still no playback-metadata callback in 0.22.0) |
| `CefriumDownloadManager` | `CefriumDownloadClient` (per browser) + `CefriumProfile.DownloadObserver` (per profile) |
| `CefriumCookieStore` | `CefriumCookieManager`: Set-Cookie strings, `getCookie` returns the Cookie header |
