package com.noiz.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.noiz.data.CatalogRepository
import com.noiz.data.PlayHistoryLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Installs [AppGraph] before any framework-instantiated service can run, so
 * `PlaybackService` and `NoizDownloadService` always find their factories ready.
 *
 * `@HiltAndroidApp` plus [Configuration.Provider] are what make the `@HiltWorker`
 * classes constructible. They were annotated with `@HiltWorker`/`@AssistedInject`
 * while the application was a plain `Application()`, so WorkManager's default factory
 * threw `Could not instantiate worker` for `OutboxWorker`, `UploadWorker` and
 * `CatalogSyncWorker` — meaning sync and uploads could never run at all.
 */
@HiltAndroidApp
class NoizApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var catalog: CatalogRepository

    @Inject
    lateinit var historyLogger: PlayHistoryLogger

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Must be the very first thing: it catches failures in the wiring below too.
        CrashReporter.install(this)
        AppGraph.install(this, catalog, historyLogger)
    }
}