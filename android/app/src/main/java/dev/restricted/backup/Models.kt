package dev.restricted.backup

import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

data class Device(val id: String, val token: String)
data class LocalMedia(val uri: String, val name: String, val mime: String, val size: Long, val modified: Long,
    val revision: String? = null) {
    val sourceKey: String get() = "$uri|$size|${revision ?: modified}"
}
data class RemoteResource(
    val id: String, val displayName: String, val type: String, val size: Long,
    val backupTime: Long, val sha256: String, val readOnly: Boolean
)
typealias BackupItem = RemoteResource
typealias AllowedResource = RemoteResource

object ScopePolicy {
    fun resourceId(value: String): String {
        require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
            "Invalid resource ID"
        }
        return value
    }
    fun downloadPath(id: String, allowed: Boolean): String =
        (if (allowed) "allowed/" else "backup/files/") + resourceId(id) + "/download"
    fun namespace(deviceId: String): String = "/DeviceBackup/${resourceId(deviceId)}/"
}

object ContentDigest {
    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object BackupRules {
    const val MAX_BYTES = 32L * 1024 * 1024
    fun validSize(size: Long) = size in 0..MAX_BYTES
    fun retryable(status: Int) = status == 408 || status == 429 || status in 500..599
    fun progress(sent: Long, total: Long) = if (total <= 0) 100 else ((sent * 100 / total).coerceIn(0, 100)).toInt()
}
