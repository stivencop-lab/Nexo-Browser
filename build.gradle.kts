plugins {
    id("com.android.application") version "9.5.3" apply false
    // AGP 9 compila Kotlin de forma integrada; esta declaración solo fija
    // la versión del compilador de Kotlin que usa.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("com.cefrium") version "0.22.0" apply false
}
