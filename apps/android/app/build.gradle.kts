import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

val repoRoot = rootProject.file("../..")

/** The checkout-local API port from `pnpm worktree:setup`, so each worktree talks to its own Worker. */
val worldApiPort: String = run {
    val state = File(repoRoot, ".world/state.json")
    if (state.exists()) ((JsonSlurper().parse(state) as Map<*, *>)["bundle"]).toString() else "8090"
}

android {
    namespace = "com.tmapp.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.tmapp.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            // Emulators and modern phones only, to keep debug installs small.
            ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
            // Reached through `adb reverse tcp:<port> tcp:<port>` on the assigned emulator.
            buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("tm.apiBaseUrl").getOrElse("http://localhost:$worldApiPort")}\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"${providers.gradleProperty("tm.apiBaseUrl").getOrElse("https://api.tmapp.invalid")}\"")
            // Local performance testing only; replace with a real signing config before distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    androidResources {
        // Already compressed (DEFLATE / PMTiles); storing them uncompressed allows fast copies.
        noCompress += listOf("bin", "pmtiles")
        localeFilters += listOf("es", "en")
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { jvmToolchain(21) }

/** Copies the pipeline's latest bundle into generated assets; binaries never enter git. */
abstract class SyncBundle : DefaultTask() {
    @get:Internal abstract val pipelineOut: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    /** Checks and lint may run without data (fresh worktrees); installable builds may not. */
    @get:Input abstract val allowMissing: Property<Boolean>

    init { outputs.upToDateWhen { false } }

    @TaskAction fun run() {
        val out = pipelineOut.get().asFile
        val latest = File(out, "latest.json")
        if (!latest.exists()) {
            if (allowMissing.get()) { logger.warn("No timetable bundle; building without data."); return }
            throw GradleException("No timetable bundle. Run `pnpm bundle:build` (and `pnpm bundle:maps` for the map) first.")
        }
        val version = (JsonSlurper().parse(latest) as Map<*, *>)["version"] as String
        val source = File(out, version)
        val target = outputDir.get().asFile.resolve("bundle").apply { deleteRecursively(); mkdirs() }
        for (name in listOf("timetable.bin", "shapes.json", "bogota.pmtiles", "manifest.json")) {
            val f = File(source, name)
            if (!f.exists()) throw GradleException("Bundle $version is missing $name; rebuild it with `pnpm bundle:build`.")
            f.copyTo(File(target, name), overwrite = true)
        }
    }
}

val syncBundle = tasks.register<SyncBundle>("syncBundle") {
    pipelineOut.set(File(repoRoot, "services/pipeline/out"))
    allowMissing.set(providers.gradleProperty("tm.allowMissingBundle").map { it.toBoolean() }.orElse(false))
    outputDir.set(layout.buildDirectory.dir("generated/tmBundle"))
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(syncBundle, SyncBundle::outputDir) }
}

dependencies {
    implementation(project(":core:data"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)
    implementation(libs.work.runtime)
    implementation(libs.room.runtime)
    implementation(libs.serialization.json)
    implementation(libs.maplibre)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))
}
