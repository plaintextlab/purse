package app.purse.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.purse.container
import app.purse.data.SyncConfig
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val status = applicationContext.container.gitSync.sync()
        return if (status.startsWith("Failed") && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

object SyncScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Debounced: a burst of edits results in one sync shortly after the last one. */
    fun afterLocalChange(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sync-after-change",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build(),
        )
    }

    fun schedulePeriodic(context: Context, cfg: SyncConfig) {
        val wm = WorkManager.getInstance(context)
        if (cfg.auto && cfg.configured) {
            wm.enqueueUniquePeriodicWork(
                "sync-periodic",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build(),
            )
        } else {
            wm.cancelUniqueWork("sync-periodic")
        }
    }
}
