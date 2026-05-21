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
        // We use public Movies directory for better visibility in Gallery
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
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dashcamRoot = File(moviesDir, "DroidDashCam")
        if (!dashcamRoot.exists()) return

        val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
        val usableSpace = stat.availableBlocksLong * stat.blockSizeLong
        val minFreeBytes = minFreeGB.toLong() * 1024 * 1024 * 1024

        if (usableSpace < minFreeBytes) {
            // Collect all non-locked mp4 files across subfolders
            val files = mutableListOf<File>()
            dashcamRoot.walkTopDown().forEach { file ->
                if (file.isFile && file.extension == "mp4" && !file.name.contains("_LOCKED")) {
                    files.add(file)
                }
            }

            // Sort by oldest
            files.sortBy { it.lastModified() }

            var currentUsable = usableSpace
            for (file in files) {
                if (currentUsable >= minFreeBytes) break
                val size = file.length()
                if (file.delete()) {
                    currentUsable += size
                    Log.d(TAG, "Deleted old file: ${file.name}")
                }
            }
        }
    }
}
