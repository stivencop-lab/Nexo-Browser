# Cefrium prototype CI setup

The standalone prototype requires JDK 25, Android platform 37/build-tools 37.0.0 and Gradle 9.7.1. Its build must run as a separate job from the main JDK 21/Gradle 9.8.2 Nexo matrix; do not raise the main workflow toolchain or include the large Cefrium AARs in the Gecko/WebView jobs.

The intended job is `cefrium-prototype`: checkout, setup-java 25, Android SDK platform 37/build-tools 37.0.0, Gradle 9.7.1, then `gradle :app:assembleDebug --no-daemon --stacktrace` with working-directory `cefrium-prototype`.

The GitHub MCP file endpoint currently returns 404 for writes under `.github/workflows/` on this feature branch. Add this job to the workflow in the GitHub UI or grant workflow-path writes before claiming this build runs in CI. The current checks cover only the existing Gecko/Chromium-WebView source and do not validate Cefrium.
