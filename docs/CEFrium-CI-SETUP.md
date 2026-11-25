# Compilar y probar Nexo con Cefrium

Nexo Browser usa **Cefrium 0.22.0** (Chromium `158.0.8127.18`) como único motor tanto en `:app` como en el proyecto de referencia `cefrium-prototype/`.

## Requisitos del entorno de compilación

Definidos en [`.github/workflows/main.yml`](../.github/workflows/main.yml):

- **JDK 25** (`temurin` 25): el bytecode de `com.cefrium:cefrium-sdk-core:0.22.0` es Java 25 y el plugin `com.cefrium` comprueba la versión del compilador en cada tarea `JavaCompile`.
- **Android SDK**: `platforms;android-37.2` para compilar, `build-tools;36.0.0` que selecciona AGP, `build-tools;37.0.0` para los verificadores del proyecto y `platform-tools` para ADB. La aplicación apunta a API 35 y requiere Android 10 o posterior (API 29).
- **Gradle**: wrapper `9.8.2` (`gradle/wrapper/gradle-wrapper.properties`) con AGP `9.5.1` y Kotlin `2.4.10`.
- **Node.js 20+**: ejecuta las regresiones JavaScript (`node --test app/src/test/js/*.test.cjs`) sin dependencias externas.
- **Python 3**: valida reflexión en APKs R8 y la identidad del paquete.

## Entorno local

Configura `JAVA_HOME` con el JDK 25 y `ANDROID_HOME` con el directorio del SDK. Añade `$JAVA_HOME/bin` y las herramientas de línea de comandos de Android al `PATH`. Desde la raíz del repositorio, comprueba las versiones e instala los paquetes.

```bash
java -version
node --version
sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" \
  'platforms;android-37.2' 'build-tools;36.0.0' 'build-tools;37.0.0' 'platform-tools'
./gradlew --version
```

```bash
node --test app/src/test/js/*.test.cjs
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 tools/check_apk_identity.py \
  --build-tools "$ANDROID_HOME/build-tools/37.0.0" \
  --package com.nexo.browser app/build/outputs/apk/debug/*.apk
```

Los APK de depuración se generan en `app/build/outputs/apk/debug/` para `arm64-v8a` y `armeabi-v7a`. Para comprobar el motor en ejecución, elige el APK que corresponda a tu dispositivo Android ARM.

```bash
adb shell getprop ro.product.cpu.abilist
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb shell am start -W -n com.nexo.browser/.MainActivity
```

El ejemplo instala el APK ARM64. La firma de depuración es distinta a la de los APK publicados. [Actualizaciones y firma](ACTUALIZACIONES-Y-FIRMA.md)

## Jobs activos en GitHub Actions

1. **`ci` (matriz `transition: [false, true]`):**
   - **Política de seguridad sobre código fuente**: bloquea `javascript:` en cargas, `RemoteDebuggingEnabled(true)`, `addJavascriptInterface`, `.proceed()` en errores TLS y cualquier importación interna (`org.chromium.*`, `org.jni_zero.*`, `GEN_JNI`).
   - **Pruebas JavaScript**: `node --test app/src/test/js/*.test.cjs`.
   - **Regresiones de UI nativa y accesibilidad (Robolectric)** (cuando `transition == false`): `:app:testDebugUnitTest -PnexoScreenshots --tests '*UiThemeRegressionTest' --tests '*SkillPracticeRegressionTest'`.
   - **Compilación, pruebas JVM y lint**: `:app:testDebugUnitTest`, `:app:testReleaseUnitTest`, `:app:assembleDebug`, `:app:assembleRelease`, `:app:lintDebug`, `:app:lintRelease`.
   - **Integridad de arranque R8 e identidad**:
     - `tools/check_release_reflection.py`: verifica que todos los componentes declarados en `AndroidManifest.xml` —incluida `com.cefrium.CefriumAppComponentFactory` en `android:appComponentFactory`— y los `Initializer` de AndroidX conservan su constructor sin argumentos en los `.dex` tras R8.
     - `tools/check_apk_identity.py`: verifica el `applicationId` (`com.nexo.browser` o `com.ejemplo.navegador` en variante de transición).
   - **Árbol limpio**: `git diff --exit-code` confirma que la compilación no modifica archivos rastreados.

2. **`prototype`:**
   - Instala JDK 25 y Android SDK `37.2` / `build-tools;37.0.0`.
   - Ejecuta `./gradlew :app:assembleDebug --no-daemon --stacktrace` en `cefrium-prototype/` (que también fija `com.cefrium` `0.22.0`).

3. **`release` (solo desde `main` o tags `v*` integrados en `main`):**
   - Compila el release **sin acceso a los secretos de firma**, valida `check_release_reflection.py` y `check_apk_identity.py`, firma los APKs `arm64-v8a` y `armeabi-v7a` (v2/v3, sin v1), verifica el certificado con `tools/verify_apk_signature.py`, genera `SHA256SUMS.txt` y publica la GitHub Release.

## Hashes verificados del SDK

Las huellas SHA-256 de todos los artefactos `com.cefrium:*:0.22.0` (verificadas contra Maven `.sha256` y `SHA256SUMS-0.22.0.asc` firmado con GPG) están fijadas en [`gradle/cefrium-0.22.0.sha256`](../gradle/cefrium-0.22.0.sha256).

La publicación del SDK ofrece el código fuente y los avisos correspondientes a esa versión. [Licencias y avisos de terceros](LICENCIAS.md)
