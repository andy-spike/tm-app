package com.tmapp.android

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tmapp.android.sync.SyncWorker
import com.tmapp.data.TransitRepository
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import javax.inject.Inject

@HiltAndroidApp
class TmApplication : Application(), Configuration.Provider {
    @Inject lateinit var transit: TransitRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // Decode the timetable while the first frame draws.
        transit.start()
        MapLibre.getInstance(this)
        SyncWorker.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
