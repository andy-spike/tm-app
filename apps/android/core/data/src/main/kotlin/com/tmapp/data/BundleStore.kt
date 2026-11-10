package com.tmapp.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BundleFile(val name: String, val size: Long, val sha256: String, val path: String? = null)

@Serializable
data class BundleManifest(
    val version: String,
    val builtAt: String? = null,
    val createdAt: String? = null,
    val feedStart: String,
    val feedEnd: String,
    val files: List<BundleFile>,
)

/** Where the active bundle came from: shipped in the APK or downloaded from the API. */
enum class BundleOrigin { BUILT_IN, DOWNLOADED }

data class InstalledBundle(val manifest: BundleManifest, val dir: File, val origin: BundleOrigin) {
    fun file(name: String) = File(dir, name)
    val builtAt: String get() = manifest.builtAt ?: manifest.createdAt ?: manifest.version
}

/**
 * Versioned bundle directories under filesDir/bundles. The APK's assets/bundle is installed on first
 * run so the app is fully offline from the first launch. A downloaded bundle becomes active only after
 * every file is verified, by atomically rewriting the `active` pointer.
 */
@Singleton
class BundleStore @Inject constructor(private val context: Context) {
    private val root = File(context.filesDir, "bundles")
    private val pointer = File(root, "active")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun active(): InstalledBundle {
        root.mkdirs()
        val builtIn = builtInManifest()
        val current = pointer.takeIf { it.exists() }?.readText()?.trim()?.let(::load)
        // A newer APK can ship a newer bundle than the one previously downloaded.
        if (current != null && current.builtAt >= (builtIn.builtAt ?: builtIn.version)) return current
        return installBuiltIn(builtIn)
    }

    private fun load(version: String): InstalledBundle? {
        val dir = File(root, version)
        val manifestFile = File(dir, MANIFEST)
        if (!manifestFile.exists()) return null
        val manifest = runCatching { json.decodeFromString<BundleManifest>(manifestFile.readText()) }.getOrNull() ?: return null
        if (manifest.files.any { it.name in REQUIRED && File(dir, it.name).length() != it.size }) return null
        val origin = if (File(dir, BUILT_IN_MARK).exists()) BundleOrigin.BUILT_IN else BundleOrigin.DOWNLOADED
        return InstalledBundle(manifest, dir, origin)
    }

    private fun builtInManifest(): BundleManifest =
        context.assets.open("bundle/$MANIFEST").use { json.decodeFromString(it.readBytes().decodeToString()) }

    private fun installBuiltIn(manifest: BundleManifest): InstalledBundle {
        val dir = File(root, manifest.version)
        load(manifest.version)?.let { activate(it.manifest.version); return it }
        val staging = File(root, "${manifest.version}.staging").apply { deleteRecursively(); mkdirs() }
        for (name in context.assets.list("bundle").orEmpty()) {
            context.assets.open("bundle/$name").use { input -> File(staging, name).outputStream().use { input.copyTo(it, 1 shl 16) } }
        }
        File(staging, BUILT_IN_MARK).writeText("1")
        dir.deleteRecursively()
        check(staging.renameTo(dir)) { "Could not install built-in bundle" }
        activate(manifest.version)
        prune(keep = manifest.version)
        return InstalledBundle(manifest, dir, BundleOrigin.BUILT_IN)
    }

    /** Moves a fully verified staging directory into place and makes it active. */
    @Synchronized
    fun install(staging: File, manifest: BundleManifest): InstalledBundle {
        for (f in manifest.files) {
            val file = File(staging, f.name)
            check(file.length() == f.size) { "Size mismatch for ${f.name}" }
            check(sha256(file) == f.sha256) { "Checksum mismatch for ${f.name}" }
        }
        File(staging, MANIFEST).writeText(json.encodeToString(BundleManifest.serializer(), manifest))
        val dir = File(root, manifest.version)
        dir.deleteRecursively()
        check(staging.renameTo(dir)) { "Could not install ${manifest.version}" }
        activate(manifest.version)
        prune(keep = manifest.version)
        return InstalledBundle(manifest, dir, BundleOrigin.DOWNLOADED)
    }

    fun stagingDir(version: String): File = File(root, "$version.download").apply { mkdirs() }

    private fun activate(version: String) {
        val tmp = File(root, "active.tmp")
        tmp.writeText(version)
        check(tmp.renameTo(pointer)) { "Could not activate $version" }
    }

    private fun prune(keep: String) {
        root.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != keep && !f.name.endsWith(".download")) f.deleteRecursively()
        }
    }

    companion object {
        const val MANIFEST = "manifest.json"
        const val TIMETABLE = "timetable.bin"
        const val SHAPES = "shapes.json"
        const val MAP = "bogota.pmtiles"
        private const val BUILT_IN_MARK = ".built-in"
        private val REQUIRED = setOf(TIMETABLE, SHAPES, MAP)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
