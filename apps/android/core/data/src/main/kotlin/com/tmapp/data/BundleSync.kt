package com.tmapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed interface SyncResult {
    data object UpToDate : SyncResult
    data class Updated(val version: String) : SyncResult
    data class Failed(val reason: String) : SyncResult
}

/**
 * Checks the Cloudflare API for a newer published release, downloads the files the app uses into a
 * staging directory (resuming partial files with Range requests), verifies them and activates the
 * release. Never touches the active bundle until everything is verified.
 */
@Singleton
class BundleSync @Inject constructor(
    private val store: BundleStore,
    @Named("apiBaseUrl") private val apiBaseUrl: String,
) {
    @Serializable
    private class Release(
        val version: String,
        val schemaVersion: Int = 1,
        val feedStart: String,
        val feedEnd: String,
        val builtAt: String,
        val minAndroidVersion: Int = 1,
        val files: List<BundleFile>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun check(appVersionCode: Int, onInstalled: suspend (InstalledBundle) -> Unit): SyncResult = withContext(Dispatchers.IO) {
        try {
            val release = client.newCall(Request.Builder().url("$apiBaseUrl/v1/releases/latest").build()).execute().use { res ->
                if (res.code == 404) return@withContext SyncResult.UpToDate
                if (!res.isSuccessful) return@withContext SyncResult.Failed("HTTP ${res.code}")
                json.decodeFromString<Release>(res.body.string())
            }
            val active = store.active()
            if (release.schemaVersion != 1 || release.minAndroidVersion > appVersionCode) return@withContext SyncResult.UpToDate
            if (release.version == active.manifest.version || release.builtAt <= active.builtAt) return@withContext SyncResult.UpToDate
            val wanted = release.files.filter { it.name in DOWNLOADED }
            if (wanted.map { it.name }.toSet() != DOWNLOADED) return@withContext SyncResult.Failed("Incomplete release")
            val staging = store.stagingDir(release.version)
            for (f in wanted) download(f, File(staging, f.name))
            val manifest = BundleManifest(release.version, release.builtAt, release.builtAt, release.feedStart, release.feedEnd, wanted)
            val installed = store.install(staging, manifest)
            onInstalled(installed)
            SyncResult.Updated(release.version)
        } catch (e: IOException) {
            SyncResult.Failed(e.message ?: "Network error")
        } catch (e: IllegalStateException) {
            SyncResult.Failed(e.message ?: "Invalid release")
        } catch (e: kotlinx.serialization.SerializationException) {
            SyncResult.Failed("Invalid release")
        }
    }

    private fun download(file: BundleFile, target: File) {
        if (target.length() == file.size) {
            if (BundleStore.sha256(target) == file.sha256) return
            target.delete()
        }
        if (target.length() > file.size) target.delete()
        val path = file.path ?: error("Missing path for ${file.name}")
        val offset = target.length()
        val request = Request.Builder().url(apiBaseUrl + path).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} for ${file.name}")
            val append = offset > 0 && res.code == 206
            java.io.FileOutputStream(target, append).use { out ->
                res.body.byteStream().copyTo(out, 1 shl 16)
            }
        }
        if (target.length() != file.size) throw IOException("Incomplete download of ${file.name}")
    }

    companion object {
        val DOWNLOADED = setOf(BundleStore.TIMETABLE, BundleStore.SHAPES, BundleStore.MAP)
    }
}
