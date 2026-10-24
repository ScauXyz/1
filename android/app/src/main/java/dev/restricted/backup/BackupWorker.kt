package dev.restricted.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.io.IOException

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as BackupApplication).repository
        repository.jobs.recover()
        var retry = false
        for (job in repository.jobs.pending()) {
            try {
                repository.jobs.update(job.sourceKey, "RUNNING", 0)
                repository.upload(job) { percent ->
                    repository.jobs.update(job.sourceKey, "RUNNING", percent)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                val transient = if (failure is ApiFailure) BackupRules.retryable(failure.status)
                    else failure is IOException
                retry = retry || transient
                repository.jobs.update(job.sourceKey, if (transient) "RETRY" else "FAILED", 0,
                    error = failure.message?.take(180) ?: "Backup failed")
            }
        }
        return if (retry) Result.retry() else Result.success()
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as BackupApplication).repository
        if (!repository.automatic) return Result.success()
        return try {
            repository.enqueue(repository.scan())
            repository.scheduleUpload()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { Result.retry() }
    }
}
