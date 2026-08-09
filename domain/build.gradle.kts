plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Deliberately no Android dependency, and no dependency on any other Bomb
// module. This is the layer that must stay testable in milliseconds.
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
