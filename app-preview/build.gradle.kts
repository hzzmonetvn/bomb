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
        versionCode = 6
        versionName = "0.6.0"

        // arm64 only. Every HyperOS device this targets is arm64-v8a, and
        // dropping the other ABIs roughly halves the file to move to a phone.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // 98.8% of the unminified release APK is dex (19.7 MB of 19.96 MB),
            // and the single native library is 10 KB, so shrinking this app is
            // entirely an R8 question — there is nothing else of size to remove.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                // The -optimize variant, not the plain one: it permits the
                // optimization passes on top of shrinking, which is most of the
                // benefit on a Compose-heavy dex.
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // AGP injects META-INF/version-control-info.textproto at packaging
            // time, after the resource merge — a `packaging.resources.excludes`
            // entry cannot reach it, which is why this is a build-type flag and
            // not a line in the exclude set below.
            vcsInfo { include = false }
            // A production ROM build must replace this with its stable release key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                // One 6-7 byte marker per AndroidX artifact, ~90 files. Tiny, but
                // they are build metadata with no runtime reader.
                "/META-INF/*.version",
                // 30 KB of Kotlin builtin declarations, read only by
                // kotlin-reflect. Bomb does not depend on kotlin-reflect; if that
                // ever changes this line must go, or reflection breaks at runtime.
                "/kotlin/**.kotlin_builtins",
                // Coroutines debug agent metadata, used only by DebugProbes.
                "DebugProbesKt.bin",
            )
            // Deliberately NOT excluded: META-INF/androidx/**/LICENSE.txt.
            // Four identical copies of the Apache-2.0 text, 40 KB total, and the
            // obvious next thing to strip — but Apache-2.0 §4(a) requires giving
            // recipients a copy of the licence, so shipping none of them would
            // trade 1.8% of the APK for a licence violation. This project already
            // has a stated third-party licensing policy (docs/BOMB_PLAN.md D16);
            // this is the same policy applied to its own artifact.
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
    // Brings BombCoreService and its manifest entry into the APK, so the service
    // is actually bindable rather than merely compiled. The UI does not consume
    // it yet — that is the next step — but a service nothing can bind to cannot
    // be verified on a device at all.
    implementation(project(":system-service"))
}
