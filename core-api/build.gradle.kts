plugins {
    // No `org.jetbrains.kotlin.android` here: AGP 9 has built-in Kotlin support
    // and rejects that plugin outright ("no longer required since AGP 9.0").
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.hzzmonet.zkbomb.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    buildFeatures {
        // Off by default since AGP 8. This module exists for the AIDL surface.
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += setOf("ChromeOsAbiSupport", "ObsoleteSdkInt")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The vocabulary — FreezeMode, LogLevel, ZramConfig and friends — is defined
    // once in :domain and reused here rather than re-declared as a parallel set
    // of API enums. Two definitions of the same enum is two chances to map them
    // wrongly, and the mapping bug is invisible until a device does the wrong
    // thing. :domain is pure JVM with no Android dependency, so depending on it
    // from an Android library costs nothing.
    api(project(":domain"))

    testImplementation(libs.junit)
}
