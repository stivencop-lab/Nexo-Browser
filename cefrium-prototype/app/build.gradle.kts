plugins {
    id("com.android.application")
    id("com.cefrium")
}
android {
    namespace = "com.nexo.cefriumprototype"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "com.nexo.cefriumprototype"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { lintConfig = rootProject.file("../app/lint.xml") }
    packaging { jniLibs { useLegacyPackaging = true } }
    splits {
        abi { isEnable = true; reset(); include("arm64-v8a"); isUniversalApk = false }
    }

}
dependencies {
    implementation("androidx.activity:activity:1.8.0")
    implementation("com.cefrium:cefrium-sdk-core:0.22.0")
    implementation("com.cefrium:cefrium-native-codecs-arm64-v8a:0.22.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
}
