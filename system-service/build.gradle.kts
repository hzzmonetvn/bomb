plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hzzmonet.zkbomb.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
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
    // The contract, which transitively brings :domain — the policy this module
    // executes but never re-implements.
    api(project(":core-api"))

    testImplementation(libs.junit)
}
