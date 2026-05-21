package com.helge.droiddashcam.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

object StorageManager {
    private const val TAG = "StorageManager"
    private const val MAX_STORAGE_USAGE_PERCENT = 0.90 // Keep 10% free

    fun cleanupOldFiles(context: Context) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        // Query videos in our app's directory, excluding locked ones
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ?"
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} NOT LIKE ?"
        }

        val selectionArgs = arrayOf("%DroidDashCam%", "%LOCKED%")
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} ASC"

        val videoList = mutableListOf<Pair<android.net.Uri, Long>>()
        var totalUsedSize = 0L

        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val size = cursor.getLong(sizeColumn)
                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                videoList.add(contentUri to size)
                totalUsedSize += size
            }
        }

        // Check actual disk space on the volume where we save movies
        val storageDir = context.getExternalFilesDir(null) ?: return
        val totalSpace = storageDir.totalSpace
        val usableSpace = storageDir.usableSpace

        if (totalSpace > 0 && usableSpace < totalSpace * (1 - MAX_STORAGE_USAGE_PERCENT)) {
            // Delete oldest until we have enough space
            var freed = 0L
            for (video in videoList) {
                try {
                    resolver.delete(video.first, null, null)
                    freed += video.second
                    // Also cleanup JSON metadata
                    val videoName = video.first.lastPathSegment
                    val metaFile = File(context.getExternalFilesDir("telemetry"), "$videoName.json")
                    if (metaFile.exists()) metaFile.delete()

                    if (usableSpace + freed > totalSpace * (1 - MAX_STORAGE_USAGE_PERCENT) + (100 * 1024 * 1024)) break
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete ${video.first}", e)
                }
            }
        }
    }

    fun lockFile(context: Context, videoUri: android.net.Uri) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            // Appending LOCKED to display name
            val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
            resolver.query(videoUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val oldName = cursor.getString(0)
                    if (!oldName.contains("LOCKED")) {
                        put(MediaStore.Video.Media.DISPLAY_NAME, oldName.replace(".mp4", "_LOCKED.mp4"))
                    }
                }
            }
        }

        if (values.size() > 0) {
            resolver.update(videoUri, values, null, null)
        }
    }

    fun getAvailableSpaceText(context: Context?): String {
        val path = context?.getExternalFilesDir(null) ?: return "0 GB Free"
        val stat = android.os.StatFs(path.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val gbAvailable = availableBytes / 1024 / 1024 / 1024
        return "${gbAvailable} GB Free"
    }
}
