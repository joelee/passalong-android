package io.github.joelee.passalong.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.joelee.passalong.data.ClipboardSink
import io.github.joelee.passalong.data.DownloadsSink
import java.io.File
import java.io.IOException

/** The device's clipboard. */
class AndroidClipboard(private val context: Context) : ClipboardSink {
    override fun setText(text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("passalong", text))
    }
}

/**
 * The device's Downloads collection, through MediaStore, which needs no storage permission and
 * never replaces a file: a taken name gets a number, as `passalong load` numbers it.
 */
class MediaStoreDownloads(private val context: Context) : DownloadsSink {
    override fun publish(
        path: String,
        name: String,
        mime: String,
    ): String {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("cannot create $name in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("cannot write $name in Downloads")
            out.use { sink -> File(path).inputStream().use { it.copyTo(sink) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (err: IOException) {
            resolver.delete(uri, null, null)
            throw err
        }
        return displayName(uri) ?: name
    }

    private fun displayName(uri: android.net.Uri): String? {
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }
}
