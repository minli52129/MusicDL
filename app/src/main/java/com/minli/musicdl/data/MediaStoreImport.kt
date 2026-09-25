package com.minli.musicdl.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object MediaStoreImport {
    /** Copy a downloaded audio file into the shared Music collection. Returns content URI or null. */
    fun import(context: Context, file: File): String? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val mime = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            else -> "audio/*"
        }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.TITLE, file.nameWithoutExtension)
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, mime)
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/MusicDL")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    fun openWith(context: Context, item: DownloadItem) {
        val uri = item.musicUri?.let { android.net.Uri.parse(it) } ?: run {
            val f = item.filePath?.let { File(it) } ?: return
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
