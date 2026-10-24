package dev.restricted.backup

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns

class MediaScanner(private val context: Context) {
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun scan(): List<LocalMedia> {
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                val partial = Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                if (partial || granted(Manifest.permission.READ_MEDIA_IMAGES)) add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                if (partial || granted(Manifest.permission.READ_MEDIA_VIDEO)) add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            } else if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            }
        }
        return sources.flatMap { source ->
            val result = mutableListOf<LocalMedia>()
            val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            context.contentResolver.query(source, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(source, cursor.getLong(0))
                    result += LocalMedia(uri.toString(), safeName(cursor.getString(1)),
                        cursor.getString(2) ?: "application/octet-stream", cursor.getLong(3), cursor.getLong(4))
                }
            }
            result
        }
    }
    fun selected(uri: Uri): LocalMedia {
        require(uri.scheme == "content") { "Select a document using the system picker" }
        var name = "selected-file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = safeName(cursor.getString(0))
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (size < 0) {
            size = 0
            context.contentResolver.openInputStream(uri)!!.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    size += n
                    require(size <= BackupRules.MAX_BYTES) { "MVP limit: 32 MiB per file" }
                }
            }
        }
        val revision = if (BackupRules.validSize(size)) context.contentResolver.openInputStream(uri)?.use(ContentDigest::sha256) else null
        return LocalMedia(uri.toString(), name, context.contentResolver.getType(uri) ?: "application/octet-stream", size, 0, revision)
    }
    private fun safeName(value: String?): String = value.orEmpty()
        .map { if (it == '/' || it == '\\' || it.code < 32) '_' else it }.joinToString("")
        .take(160).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "selected-file"
}
