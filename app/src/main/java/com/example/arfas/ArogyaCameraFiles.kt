package com.example.arfas

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where camera photos are written before the app reads them.
 *
 * Photos of MCP cards, registers and patients used to be created in the public Gallery
 * (MediaStore EXTERNAL_CONTENT_URI): backup apps could upload them before they were deleted, a crash
 * left them behind, and on Android 7–9 the insert needed a storage permission the app never asked
 * for, so every camera button failed there. Now each photo is a file in the app's private cache,
 * shared with the camera app only through a FileProvider URI for that one capture.
 */
object ArogyaCameraFiles {
    private const val TAG = "ArogyaCamera"
    private const val DIR = "camera"
    private const val STALE_MS = 6 * 60 * 60 * 1000L

    fun authority(context: Context): String = context.packageName + ".files"

    private fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /** A fresh private output URI for ActivityResultContracts.TakePicture, or null if it cannot be created. */
    fun newPhotoUri(context: Context, prefix: String = "photo"): Uri? = runCatching {
        purgeStale(context)
        val f = File(dir(context), "${prefix}_${System.currentTimeMillis()}.jpg")
        f.createNewFile()
        FileProvider.getUriForFile(context, authority(context), f)
    }.onFailure { Log.w(TAG, "CAMERA_URI_FAILED ${it.javaClass.simpleName}") }.getOrNull()

    /** Photos left by an interrupted capture (app killed while reading) are removed after a few hours. */
    fun purgeStale(context: Context) {
        val cutoff = System.currentTimeMillis() - STALE_MS
        dir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }
}
