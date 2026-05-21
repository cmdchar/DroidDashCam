package com.helge.droiddashcam.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

object StorageManagerV2 {
    private const val TAG = "StorageManagerV2"

    fun getOutputDirectory(context: Context, subDir: String): File {
        val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
            File(it, "Dashcam/$subDir").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists()) mediaDir else context.filesDir
    }

    fun getAvailableSpaceText(context: Context): String {
        val path = context.getExternalFilesDir(null) ?: return "0 GB"
        val stat = android.os.StatFs(path.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val gbAvailable = availableBytes / 1024 / 1024 / 1024
        return "$gbAvailable GB"
    }

    fun cleanupOldFiles(context: Context, minFreeGB: Int) {
        val storageDir = context.getExternalFilesDir(null) ?: return
        val totalSpace = storageDir.totalSpace
        val usableSpace = storageDir.usableSpace
        val minFreeBytes = minFreeGB.toLong() * 1024 * 1024 * 1024

        if (usableSpace < minFreeBytes) {
            val resolver = context.contentResolver
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE)
            val selection = "${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ?"
            val selectionArgs = arrayOf("%LOCKED%")
            val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} ASC"

            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                var currentUsable = usableSpace
                while (cursor.moveToNext() && currentUsable < minFreeBytes) {
                    val id = cursor.getLong(idColumn)
                    val size = cursor.getLong(sizeColumn)
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    try {
                        resolver.delete(uri, null, null)
                        currentUsable += size
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete old file", e)
                    }
                }
            }
        }
    }
}
