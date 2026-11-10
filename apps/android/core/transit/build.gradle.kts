plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

// Tests read the real pipeline output when it exists; see BundleFixture.
tasks.test {
    systemProperty("tm.bundleDir", rootProject.file("../../services/pipeline/out").absolutePath)
    maxHeapSize = "2g"
    testLogging { events("failed"); showStandardStreams = true }
}
