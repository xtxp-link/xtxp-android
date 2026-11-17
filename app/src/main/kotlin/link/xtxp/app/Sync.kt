package link.xtxp.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import link.xtxp.core.Document
import java.io.File
import java.util.concurrent.TimeUnit

/** The one document this first version edits, stored in the app's files. */
object Documents {
    const val NAME = "demo"
    @Volatile private var doc: Document? = null

    fun get(context: Context): Document = doc ?: synchronized(this) {
        doc ?: Document(File(context.filesDir, "docs/$NAME").absolutePath).also { doc = it }
    }
}

/**
 * Merges the phone's session with its remotes, both ways. WorkManager runs
 * it when there's a network, retries it with backoff, and keeps it going
 * across restarts. Offline (paused) skips it unless it's forced by `+`.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val report = Documents.get(applicationContext).sync(force).getOrElse { return Result.retry() }
        return when {
            report.skipped -> Result.success()
            report.mergedRemotes == 0u && report.errors.isNotEmpty() ->
                if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(KEY_ERROR to report.errors.first()))
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        const val KEY_ERROR = "error"
    }
}

/** The schedule: what `<` and `+` and each drag ask WorkManager for. */
class SyncSchedule(context: Context) {
    private val work = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Online: a merge soon after every change, and a pull every 15 minutes. */
    fun online() {
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
        )
        soon()
    }

    /** Offline: nothing merges either way until `+` or going back online. */
    fun offline() {
        work.cancelUniqueWork(PERIODIC)
        work.cancelUniqueWork(SOON)
    }

    /** After a local change, while online. */
    fun soon() = enqueue(SOON, force = false)

    /** The `+` button: merge now, both ways, even while offline. */
    fun mergeNow() = enqueue(MERGE, force = true)

    private fun enqueue(name: String, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_FORCE to force))
            .build()
        work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PERIODIC = "sync-periodic"
        const val SOON = "sync-soon"
        const val MERGE = "sync-merge"
        val ALL = listOf(PERIODIC, SOON, MERGE)
    }
}
