package dev.restricted.backup

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class BackupApplication : Application() {
    val repository by lazy { BackupRepository(this) }
}

class BackupRepository(private val context: Context) {
    private val database = Room.databaseBuilder(context, JobDatabase::class.java, "backup-jobs.db").build()
    val jobs = database.jobs()
    private val vault = TokenVault(context)
    private val registrationLock = Mutex()
    private val api = BackupApi()
    private val scanner = MediaScanner(context)
    private val settings = context.getSharedPreferences("backup_settings", Context.MODE_PRIVATE)
    val automatic get() = settings.getBoolean("automatic", false)
    val wifiOnly get() = settings.getBoolean("wifiOnly", true)
    suspend fun device(): Device = registrationLock.withLock {
        withContext(Dispatchers.IO) { vault.load() ?: api.register().also { vault.save(it) } }
    }
    suspend fun resources(allowed: Boolean): List<RemoteResource> = withContext(Dispatchers.IO) { api.list(device(), allowed) }
    suspend fun scan(): List<LocalMedia> = withContext(Dispatchers.IO) { scanner.scan() }
    suspend fun enqueue(media: List<LocalMedia>) {
        media.forEach { item ->
            jobs.insert(BackupJob(item.sourceKey, item.uri, item.name, item.mime, item.size,
                status = if (BackupRules.validSize(item.size)) "PENDING" else "FAILED",
                error = if (BackupRules.validSize(item.size)) "" else "MVP limit: 32 MiB per file"))
        }
    }
    suspend fun select(uris: List<Uri>) = withContext(Dispatchers.IO) {
        val items = uris.map { uri ->
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            scanner.selected(uri)
        }
        enqueue(items)
        scheduleUpload()
    }
    fun scheduleUpload(replace: Boolean = false) {
        val work = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("device-backup",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
    }
    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
    fun configure(automatic: Boolean, wifiOnly: Boolean) {
        val networkChanged = this.wifiOnly != wifiOnly
        settings.edit().putBoolean("automatic", automatic).putBoolean("wifiOnly", wifiOnly).apply()
        val manager = WorkManager.getInstance(context)
        if (automatic) {
            val work = PeriodicWorkRequestBuilder<AutoBackupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints()).build()
            manager.enqueueUniquePeriodicWork("automatic-scan", ExistingPeriodicWorkPolicy.UPDATE, work)
        } else manager.cancelUniqueWork("automatic-scan")
        if (networkChanged) scheduleUpload(replace = true)
    }
    suspend fun upload(job: BackupJob, progress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        require(BackupRules.validSize(job.size)) { "MVP limit: 32 MiB per file" }
        val uri = Uri.parse(job.uri)
        val hash = context.contentResolver.openInputStream(uri)?.use(ContentDigest::sha256)
            ?: error("The local document is no longer readable")
        val device = device()
        val (id, exists) = api.initialize(device, job, hash)
        if (!exists) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                api.upload(device, id, job.size, input, progress)
            } ?: error("The local document is no longer readable")
            api.complete(device, id)
        }
        jobs.update(job.sourceKey, "COMPLETE", 100, remoteId = id, sha = hash)
    }
    suspend fun restore(resource: RemoteResource, allowed: Boolean, uri: Uri) = withContext(Dispatchers.IO) {
        val file = File.createTempFile("verified-restore-", ".tmp", context.cacheDir)
        try {
            api.download(device(), resource, allowed, file)
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("Cannot write the selected restore destination")
        } finally { file.delete() }
    }
    suspend fun thumbnail(resource: RemoteResource, allowed: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        if (!resource.type.startsWith("image/")) return@withContext null
        val file = File.createTempFile("thumbnail-", ".tmp", context.cacheDir)
        try {
            api.download(device(), resource, allowed, file)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1)
            }
            BitmapFactory.decodeFile(file.path, options)
        } finally { file.delete() }
    }
}
