package com.tmapp.android.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tmapp.android.BuildConfig
import com.tmapp.data.BundleSync
import com.tmapp.data.SettingsRepository
import com.tmapp.data.SyncResult
import com.tmapp.data.TransitRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Daily timetable check on unmetered networks, so riders' data plans are never used for updates. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updater: TimetableUpdater,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (updater.check()) {
        is SyncResult.Failed -> Result.retry()
        else -> Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("timetable-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

@Singleton
class TimetableUpdater @Inject constructor(
    private val sync: BundleSync,
    private val transit: TransitRepository,
    private val settings: SettingsRepository,
) {
    suspend fun check(): SyncResult {
        val result = sync.check(BuildConfig.VERSION_CODE) { transit.activate(it) }
        settings.recordSync(when (result) {
            SyncResult.UpToDate -> "up_to_date"
            is SyncResult.Updated -> "updated"
            is SyncResult.Failed -> "failed"
        })
        return result
    }
}
