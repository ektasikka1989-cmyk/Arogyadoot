package com.example.arfas

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * One place that creates an export file (PDF, CSV, backup, template).
 *
 * Android 10+: the public Downloads folder through MediaStore, as before. Android 7–9 (minSdk 24):
 * MediaStore.Downloads does not exist there and public storage needs a permission the app never
 * asks for, so every export used to fail silently. There the file goes to the app's own Download
 * folder and is shared through the FileProvider, so the share sheet still works.
 */
object ArogyaDownloads {
    /** True when files land in the public Downloads folder (Android 10+). */
    val publicDownloads: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Creates an empty file named [name] in Downloads[/subdir] and returns a writable Uri. Throws on failure. */
    fun create(context: Context, name: String, mime: String, subdir: String = ""): Uri {
        if (publicDownloads) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + (if (subdir.isBlank()) "" else "/$subdir"))
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
        }
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "Download")
        val dir = (if (subdir.isBlank()) base else File(base, subdir)).apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var f = File(dir, safe); var n = 1
        while (f.exists()) { f = File(dir, safe.substringBeforeLast('.') + " ($n)." + safe.substringAfterLast('.', "bin")); n++ }
        f.createNewFile()
        return FileProvider.getUriForFile(context, ArogyaCameraFiles.authority(context), f)
    }
}
