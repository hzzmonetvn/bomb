plugins {
    alias(libs.plugins.android.application)
}

/**
 * Packaging shell for the Android application.
 *
 * Deliberately has no Kotlin source: manifest, resources and signing only.
 * `MainActivity` lives in `:preview` alongside the UI it hosts, which keeps this
 * module free of the Compose compiler and makes the APK a pure wrapper.
 */
android {
    namespace = "com.hzzmonet.zkbomb"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hzzmonet.zkbomb"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.2.0"

        // arm64 only. Every HyperOS device this targets is arm64-v8a, and
        // dropping the other ABIs roughly halves the file to move to a phone.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // A production ROM build must replace this with its stable release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Bomb targets arm64 HyperOS devices, not ChromeOS.
        disable += setOf("ChromeOsAbiSupport", "ObsoleteSdkInt")
    }
}

dependencies {
    implementation(project(":preview"))
}
