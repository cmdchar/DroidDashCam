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
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dashcamDir = File(moviesDir, "DroidDashCam/$subDir")
        if (!dashcamDir.exists()) {
            dashcamDir.mkdirs()
        }
        return dashcamDir
    }

    fun getAvailableSpaceText(context: Context): String {
        val path = Environment.getExternalStorageDirectory()
        val stat = android.os.StatFs(path.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val gbAvailable = availableBytes / 1024 / 1024 / 1024
        return "$gbAvailable GB Free"
    }

    fun cleanupOldFiles(context: Context, minFreeGB: Int) {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE)
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%LOCKED%", "%DroidDashCam%")
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} ASC"

        val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        val usableSpace = stat.availableBlocksLong * stat.blockSizeLong
        val minFreeBytes = minFreeGB.toLong() * 1024 * 1024 * 1024

        if (usableSpace < minFreeBytes) {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selection else null,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) selectionArgs else null,
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
