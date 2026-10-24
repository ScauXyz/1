package dev.restricted.backup

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF245C52))) { BackupScreen() }
        }
    }
}

@Composable
private fun BackupScreen(model: BackupViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val jobs by model.jobs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var restoring by remember { mutableStateOf<Pair<RemoteResource, Boolean>?>(null) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        restoring?.let { target -> if (uri != null) model.restore(target.first, target.second, uri) }
        restoring = null
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.select(uris)
    }
    val permissionPicker = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val completed = jobs.count { it.status == "COMPLETE" }
    LaunchedEffect(completed) { if (completed > 0) model.refresh() }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Home", "My backups", "Allowed content").forEachIndexed { index, title ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Text(listOf("⌂", "▣", "✓")[index]) }, label = { Text(title) })
            }
        }
    }) { inset ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(inset),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Device Backup", style = MaterialTheme.typography.headlineMedium)
                Text("Independent client · Mock storage demo", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh") }
                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (state.message.isNotEmpty()) Text(state.message, color = MaterialTheme.colorScheme.primary)
            }
            if (tab == 0) {
                item {
                    Text("Your phone, your backup", style = MaterialTheme.typography.titleLarge)
                    Text("Only this device's backups and approved content are accessible.")
                    Setting("Automatic backup", state.automatic) { model.configure(it, state.wifiOnly) }
                    Setting("Wi-Fi only (unmetered network)", state.wifiOnly) { model.configure(state.automatic, it) }
                    Text("Local photos: ${state.photos}  ·  Local videos: ${state.videos}")
                    Text("Pending: ${jobs.count { it.status in setOf("PENDING", "RUNNING", "RETRY") }}")
                    Text("Backed up: ${state.backups.size}  ·  Used cloud space: ${formatSize(state.backups.sumOf { it.size })}")
                    Text("MVP file limit: 32 MiB. Automatic backup scans at least every 15 minutes when Android allows.")
                    Button(onClick = model::backupNow, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Select files") }
                    OutlinedButton(onClick = { tab = 1 }, modifier = Modifier.fillMaxWidth()) { Text("My backups") }
                    TextButton(onClick = {
                        permissionPicker.launch(if (Build.VERSION.SDK_INT >= 34) arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                        else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                    }) { Text("Grant or update photo/video access") }
                    HorizontalDivider()
                    Text("Backup jobs", style = MaterialTheme.typography.titleMedium)
                    if (jobs.isEmpty()) Text("Select files or grant media access to start a backup. Your originals are never deleted.")
                }
                items(jobs, key = { it.sourceKey }) { job ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(job.name)
                            Text("${job.status} · ${job.progress}% · ${formatSize(job.size)}")
                            if (job.status == "RUNNING") LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            if (job.error.isNotEmpty()) Text(job.error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                val allowed = tab == 2
                val resources = if (allowed) state.allowed else state.backups
                item {
                    Text(if (allowed) "Administrator-approved resources" else "My backups", style = MaterialTheme.typography.titleLarge)
                    if (!allowed && state.deviceId.isNotBlank()) Text(ScopePolicy.namespace(state.deviceId))
                    if (resources.isEmpty()) Text("No ${if (allowed) "approved content" else "backups"} available. Refresh after a job finishes.")
                }
                items(resources, key = { it.id }) { resource ->
                    ResourceCard(resource, allowed, model.repository, !state.busy) {
                        restoring = resource to allowed
                        restorePicker.launch(resource.displayName)
                    }
                }
            }
            item { Spacer(Modifier.size(8.dp)) }
        }
    }
}

@Composable
private fun Setting(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 14.dp))
        Switch(checked = checked, onCheckedChange = change)
    }
}

@Composable
private fun ResourceCard(resource: RemoteResource, allowed: Boolean, repository: BackupRepository, enabled: Boolean, restore: () -> Unit) {
    val thumbnail by produceState<Bitmap?>(null, resource.id) {
        value = try { repository.thumbnail(resource, allowed) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            thumbnail?.let { Image(it.asImageBitmap(), "Thumbnail of ${resource.displayName}", modifier = Modifier.size(64.dp)) }
            Column(Modifier.weight(1f)) {
                Text(resource.displayName, style = MaterialTheme.typography.titleMedium)
                Text("${formatSize(resource.size)} · ${resource.type}")
                Text(DateFormat.getDateTimeInstance().format(Date(resource.backupTime)))
                if (resource.readOnly) Text("Read only")
                TextButton(onClick = restore, enabled = enabled) { Text("Download / restore") }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024))
}
