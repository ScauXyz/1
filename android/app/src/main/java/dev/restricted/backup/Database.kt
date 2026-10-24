package dev.restricted.backup

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "jobs")
data class BackupJob(
    @PrimaryKey val sourceKey: String,
    val uri: String, val name: String, val mime: String, val size: Long,
    val status: String = "PENDING", val progress: Int = 0, val error: String = "",
    val remoteId: String = "", val sha256: String = ""
)

@Dao
interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY rowid DESC") fun observe(): Flow<List<BackupJob>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(job: BackupJob)
    @Query("SELECT * FROM jobs WHERE status='PENDING'") suspend fun pending(): List<BackupJob>
    @Query("UPDATE jobs SET status='PENDING', error='' WHERE status IN ('RUNNING','RETRY')") suspend fun recover()
    @Query("UPDATE jobs SET status='PENDING', error='', progress=0 WHERE status='FAILED'") suspend fun retryFailed()
    @Query("UPDATE jobs SET status=:status, progress=:progress, error=:error, remoteId=:remoteId, sha256=:sha WHERE sourceKey=:key")
    suspend fun update(key: String, status: String, progress: Int, error: String = "", remoteId: String = "", sha: String = "")
}

@Database(entities = [BackupJob::class], version = 1, exportSchema = false)
abstract class JobDatabase : RoomDatabase() { abstract fun jobs(): JobDao }
