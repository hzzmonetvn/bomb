plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // AGP 9 does not allow `com.android.application` beside the KMP plugin, so
    // the shared UI remains an Android library and `:app-preview` is its thin
    // packaging module.
    androidLibrary {
        namespace = "com.hzzmonet.zkbomb.preview.ui"
        compileSdk = 37
        minSdk = 33
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(libs.miuix)
            }
        }
        val androidMain by getting {
            dependencies {
                // `api`, not `implementation`: the packaging module has no Kotlin
                // source of its own and points straight at MainActivity here.
                api(libs.androidx.activity.compose)
                api(libs.androidx.core.ktx)
                // The AIDL surface only. Deliberately NOT :system-service — the
                // UI must not be able to reach a privileged implementation
                // directly, which is the entire reason :core-api exists as a
                // separate module. The service class is named as a string at
                // bind time, so the boundary holds at compile time too.
                implementation(project(":core-api"))
                // Pure-JVM decision logic for the call recorder. The capture
                // engine below is Android-only, but the arm/prompt/record/stop
                // policy and retention math live in :domain so they stay unit
                // tested off-device. Android's androidMain compiles to JVM
                // bytecode, so a plain java-library is a valid dependency here.
                implementation(project(":domain"))
            }
        }
    }
}
