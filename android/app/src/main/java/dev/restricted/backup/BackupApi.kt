package dev.restricted.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class ApiFailure(val status: Int) : IOException(
    if (status == 401) "Device token expired or invalid. Contact the backend operator; identity was preserved."
    else "Backup service returned HTTP $status"
)

class BackupApi(private val base: String = BuildConfig.BACKUP_BASE_URL) {
    init {
        val uri = URI(base)
        require(uri.userInfo == null && uri.query == null && uri.fragment == null)
        require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http" &&
            uri.host in setOf("10.0.2.2", "localhost", "127.0.0.1"))) { "An HTTPS backend is required" }
    }
    private fun connection(path: String, method: String, device: Device? = null): HttpURLConnection {
        require(!path.startsWith("/") && !path.contains(".."))
        return (URL(base.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            device?.let { setRequestProperty("Authorization", "Bearer ${it.token}") }
        }
    }
    private fun json(path: String, device: Device? = null, body: JSONObject? = null): String {
        val c = connection(path, if (body == null) "GET" else "POST", device)
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().encodeToByteArray()) }
            }
            if (c.responseCode !in 200..299) throw ApiFailure(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    fun register(): Device {
        val result = JSONObject(json("auth/device/register", body = JSONObject()
            .put("display_name", "Android phone").put("demo", BuildConfig.DEMO_MODE)))
        return Device(ScopePolicy.resourceId(result.getString("deviceId")), result.getString("deviceToken"))
    }
    fun list(device: Device, allowed: Boolean): List<RemoteResource> {
        val array = JSONArray(json(if (allowed) "allowed" else "backup/files", device))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RemoteResource(ScopePolicy.resourceId(item.getString("id")), item.getString("displayName"),
                item.getString("type"), item.getLong("size"),
                (item.getDouble("backupTime") * 1000).toLong(), item.getString("sha256"), item.getBoolean("readOnly"))
        }
    }
    fun initialize(device: Device, job: BackupJob, hash: String): Pair<String, Boolean> {
        val response = JSONObject(json("backup/init", device, JSONObject()
            .put("name", job.name).put("mime_type", job.mime).put("size", job.size).put("sha256", hash)))
        return ScopePolicy.resourceId(response.getString("resourceId")) to response.getBoolean("complete")
    }
    suspend fun upload(device: Device, id: String, size: Long, input: InputStream, progress: suspend (Int) -> Unit) {
        require(BackupRules.validSize(size))
        val c = connection("backup/files/${ScopePolicy.resourceId(id)}/content", "PUT", device)
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(size)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                var lastProgress = -5
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    sent += count
                    require(sent <= size) { "Local file changed; scan it again" }
                    output.write(buffer, 0, count)
                    val percent = BackupRules.progress(sent, size)
                    if (percent >= lastProgress + 5) { progress(percent); lastProgress = percent }
                }
                require(sent == size) { "Local file changed; scan it again" }
            }
            if (c.responseCode !in 200..299) throw ApiFailure(c.responseCode)
        } finally { c.disconnect() }
    }
    fun complete(device: Device, id: String) {
        json("backup/complete", device, JSONObject().put("resource_id", ScopePolicy.resourceId(id)))
    }
    fun download(device: Device, resource: RemoteResource, allowed: Boolean, destination: File) {
        require(BackupRules.validSize(resource.size))
        val c = connection(ScopePolicy.downloadPath(resource.id, allowed), "GET", device)
        try {
            if (c.responseCode != 200) throw ApiFailure(c.responseCode)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            c.inputStream.use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    size += count
                    check(size <= resource.size) { "Download size mismatch" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(size == resource.size && hash == resource.sha256) { "Download integrity check failed" }
        } finally { c.disconnect() }
    }
}
