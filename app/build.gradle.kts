plugins {
    id("com.android.application")
}
android {
    namespace = "com.nexo.browser"
    // GeckoView 159 exige compilar contra Android 37.2 (versión menor de API).
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    defaultConfig {
        applicationId = if (project.hasProperty("nexoTransition")) "com.ejemplo.navegador" else "com.nexo.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 61
        versionName = "0.30.2"
        // Solo la actualización de transición (paquete anterior) entrega sus datos a Nexo nuevo.
        manifestPlaceholders["legacyExport"] = project.hasProperty("nexoTransition").toString()
    }
    /*
     * Motor web: "gecko" (GeckoView, la versión publicada) y "chromium"
     * (Android System WebView, en migración). Las dos usan el mismo paquete:
     * la de Chromium, firmada con la misma clave, se instala como
     * actualización de la de Gecko y conserva historial, accesos, pestañas,
     * ajustes y claves (y trae las cookies de Gecko, ver GeckoProfileImport).
     * Con -PnexoSideBySide se instala aparte (sufijo .chromium) para probarla
     * junto a la publicada.
     */
    flavorDimensions += "engine"
    productFlavors {
        create("gecko") {
            dimension = "engine"
            isDefault = true
        }
        create("chromium") {
            dimension = "engine"
            if (project.hasProperty("nexoSideBySide")) applicationIdSuffix = ".chromium"
            versionNameSuffix = "-chromium"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            // Como Firefox para Android: el motor Gecko (libxul.so, ~150 MB)
            // va comprimido dentro del APK. El APK pasa de ~216 MB a ~100 MB;
            // Android extrae las librerías una sola vez al instalar.
            useLegacyPackaging = true
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    buildFeatures {
        compose = false
        viewBinding = false
        buildConfig = false
    }
    // Capturas locales de la interfaz nativa (./gradlew ... -PnexoScreenshots).
    if (project.hasProperty("nexoScreenshots")) {
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.all { test ->
            project.findProperty("nexoShotsDir")?.let { test.systemProperty("nexo.shots", it.toString()) }
            project.findProperty("robolectricDir")?.let {
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", it.toString())
            }
        }
        sourceSets.getByName("test").kotlin.srcDir("src/screenshotTest/java")
    }
}
androidComponents {
    // AGP 9 does not create this release test component by default. The release
    // workflow runs it explicitly, so keep those tests enabled for both variants.
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17")
        )
    }
}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.10"))
    "geckoImplementation"("org.mozilla.geckoview:geckoview-omni:159.0.20261102144711")
    // Perfiles aislados, scripts de inicio de documento y puente de mensajes de WebView.
    "chromiumImplementation"("androidx.webkit:webkit:1.18.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    // Selector de pestañas: tarjetas recicladas y deslizar para cerrar.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // HCT / Material color utilities; the browser keeps its native View UI.
    implementation("com.materialkolor:material-color-utilities:3.0.1")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    // WebSocket para los subtítulos en vivo (Deepgram).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // org.json real en las pruebas JVM (el de Android es un stub).
    testImplementation("org.json:json:20240303")
    if (project.hasProperty("nexoScreenshots")) {
        testImplementation("org.robolectric:robolectric:4.16")
    }
}
