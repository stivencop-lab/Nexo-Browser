plugins {
    id("com.android.application")
    // Cefrium (Chromium embebido): genera los R de Chromium y el manifiesto
    // de sus procesos aislados. Exige compilar con JDK 25.
    id("com.cefrium")
}
android {
    namespace = "com.nexo.browser"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    defaultConfig {
        applicationId = if (project.hasProperty("nexoTransition")) "com.ejemplo.navegador" else "com.nexo.browser"
        // Cefrium 0.22.0 (Chromium 158) exige Android 10.
        minSdk = 29
        targetSdk = 35
        versionCode = 82
        versionName = "0.40.2"
        // Solo la actualización de transición (paquete anterior) entrega sus datos a Nexo nuevo.
        manifestPlaceholders["legacyExport"] = project.hasProperty("nexoTransition").toString()
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
            // El motor (libcef.so) va comprimido dentro del APK; Android
            // extrae las librerías una sola vez al instalar.
            useLegacyPackaging = true
            // Nexo solo publica ARM: Cefrium no trae libcef.so para x86/x86_64 y
            // su plugin rechaza un APK con esas ABI (las aporta androidx.datastore).
            excludes += listOf("lib/x86/**", "lib/x86_64/**")
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
    // La interfaz de Nexo es en español: solo se empaquetan los recursos es/en (y los
    // predeterminados). Chromium trae ~120 idiomas que inflaban resources.arsc (~4.7 MB).
    androidResources {
        localeFilters += listOf("es", "en")
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
    implementation("androidx.activity:activity:1.8.0")
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.10"))
    // Motor web: Chromium 158 embebido (Cefrium, LGPL-3.0). Hashes verificados
    // en gradle/cefrium-0.22.0.sha256. Un módulo nativo por ABI publicada.
    // Desde 0.22.0, cefrium-sdk-core no arrastra Google Play services, Firebase ni ML Kit.
    implementation("com.cefrium:cefrium-sdk-core:0.22.0")
    implementation("com.cefrium:cefrium-native-codecs-arm64-v8a:0.22.0")
    implementation("com.cefrium:cefrium-native-codecs-armeabi-v7a:0.22.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    // Selector de pestañas: tarjetas recicladas y deslizar para cerrar.
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // HCT / Material color utilities; the browser keeps its native View UI.
    implementation("com.materialkolor:material-color-utilities:6.0.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    // WebSocket para los subtítulos en vivo (Deepgram).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.re2j:re2j:1.8")

    testImplementation("junit:junit:4.13.2")
    // org.json real en las pruebas JVM (el de Android es un stub).
    testImplementation("org.json:json:20260814")
    if (project.hasProperty("nexoScreenshots")) {
        testImplementation("org.robolectric:robolectric:4.18.1")
    }
}
