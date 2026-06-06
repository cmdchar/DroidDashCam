package com.helge.droiddashcam.utils

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class TelemetryRecorder(private val context: Context, private val videoName: String) {
    private val dataPoints = JSONArray()

    fun addData(lat: Double, lon: Double, speed: Float) {
        val point = JSONObject()
        point.put("timestamp", System.currentTimeMillis())
        point.put("lat", lat)
        point.put("lon", lon)
        point.put("speed", speed)
        dataPoints.put(point)
    }

    fun save() {
        try {
            // Store in app-private external storage to avoid Scoped Storage issues
            val dir = context.getExternalFilesDir("telemetry")
            if (dir != null && !dir.exists()) dir.mkdirs()

            val jsonFile = File(dir, "$videoName.json")
            FileOutputStream(jsonFile).use {
                it.write(dataPoints.toString().toByteArray())
            }
            Log.d("TelemetryRecorder", "Saved telemetry to ${jsonFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("TelemetryRecorder", "Error saving telemetry", e)
        }
    }
}
