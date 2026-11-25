# Cefrium prototype

Standalone arm64 smoke project for Cefrium 0.22.0 (Chromium 158.0.8127.18). It is intentionally separate from the main build: Cefrium requires JDK 25, Gradle 9.7.1+, AGP 9.4+ and minSdk 29.

## Build

JDK 25 and an Android SDK with platform 37 and build-tools 37.0.0 are required. From this directory:

    export JAVA_HOME=/path/to/jdk-25
    export ANDROID_HOME=/path/to/android-sdk
    ./gradlew :app:assembleDebug --no-daemon      # Windows: gradlew.bat from CMD or Git Bash, not PowerShell

Output: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (arm64-v8a only, debug-signed; since 0.22.0 `cefrium-sdk-core` omits Google Play services / Firebase / ML Kit).

## Smoke test on a device (Android 10+, arm64)

    adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
    adb logcat -c
    adb shell am start -n com.nexo.cefriumprototype/.MainActivity
    adb logcat -s NexoCefrium CefriumBrowser

A fresh start walks example.com -> iana.org -> back -> forward -> reload and logs `PASS`/`FAIL` lines:
rendering, JS bridge on example.com, bridge denied on a foreign origin, shield blocking doubleclick.net,
cookies, back/forward/reload and saveState. Then check incognito isolation:

    adb shell am force-stop com.nexo.cefriumprototype
    adb shell am start -n com.nexo.cefriumprototype/.MainActivity --ez incognito true

The incognito run must log `PASS profile does not see nexo_smoke`, and the next normal run `PASS profile does not see nexo_incog`.

Manual checks (callbacks are logged under the same tag): permissions and popups on https://permission.site,
fullscreen on any HTML5 video, downloads from any file link, camera/microphone access on a WebRTC test page.

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
