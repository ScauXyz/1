package dev.restricted.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupUiState(
    val busy: Boolean = false, val message: String = "", val deviceId: String = "",
    val photos: Int = 0, val videos: Int = 0, val automatic: Boolean = false, val wifiOnly: Boolean = true,
    val backups: List<BackupItem> = emptyList(), val allowed: List<AllowedResource> = emptyList()
)

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as BackupApplication).repository
    private val mutable = MutableStateFlow(BackupUiState(automatic = repository.automatic, wifiOnly = repository.wifiOnly))
    val state = mutable.asStateFlow()
    val jobs = repository.jobs.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init { refresh() }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = "") }
            try { block()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(message = error.message?.take(200) ?: "Operation failed") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun refresh() = action { refreshData() }
    private suspend fun refreshData() {
        val media = repository.scan()
        mutable.update { it.copy(photos = media.count { m -> m.mime.startsWith("image/") },
            videos = media.count { m -> m.mime.startsWith("video/") }) }
        val device = repository.device()
        mutable.update { it.copy(deviceId = device.id) }
        val backups = repository.resources(false)
        val allowed = repository.resources(true)
        mutable.update { it.copy(backups = backups, allowed = allowed) }
    }
    fun backupNow() = action {
        repository.enqueue(repository.scan())
        repository.jobs.retryFailed()
        repository.scheduleUpload()
        mutable.update { it.copy(message = "Backup queued. Network constraints apply; originals are kept.") }
    }
    fun select(uris: List<Uri>) = action { repository.select(uris) }
    fun configure(automatic: Boolean, wifiOnly: Boolean) {
        repository.configure(automatic, wifiOnly)
        mutable.update { it.copy(automatic = automatic, wifiOnly = wifiOnly) }
        if (automatic) backupNow()
    }
    fun restore(resource: RemoteResource, allowed: Boolean, destination: Uri) = action {
        repository.restore(resource, allowed, destination)
        mutable.update { it.copy(message = "Verified file saved to your selected destination.") }
    }
}
