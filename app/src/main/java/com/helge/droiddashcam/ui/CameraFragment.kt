package com.helge.droiddashcam.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.helge.droiddashcam.R
import com.helge.droiddashcam.databinding.FragmentCameraBinding
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.base.recording.RecordController
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.helge.droiddashcam.utils.StorageManager
import com.helge.droiddashcam.utils.TelemetryRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), ConnectChecker, LocationListener, SensorEventListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    // We use RtmpStream as our primary mixer/recorder engine
    private var rtmpStream: RtmpStream? = null
    private var isStreamingActive = false
    private var isRecordingActive = false
    private var surfaceFilter: SurfaceFilterRender? = null

    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartTime = 0L
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null

    private var currentVideoUri: android.net.Uri? = null
    private var currentVideoPath: String? = null
    private var telemetryRecorder: TelemetryRecorder? = null
    private var lastKnownLocation: Location? = null

    // Drive Stats
    private var maxSpeed = 0f
    private var incidentCount = 0
    private var driveStartTime = 0L

    private val updateTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecordingActive) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                binding.textRecTime.text = formatElapsedTime(elapsed)

                lastKnownLocation?.let { loc ->
                    telemetryRecorder?.addData(loc.latitude, loc.longitude, loc.speed)
                }

                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val loopDurationStr = prefs.getString("loop_duration", "5") ?: "5"
                val loopDurationMin = loopDurationStr.toLong()
                if (elapsed >= loopDurationMin * 60 * 1000) {
                    restartRecording()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            binding.textTemp.text = "${level}%"
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupButtons()
        startClock()
        setupSensors()

        // Initialize the stream/mixer engine
        initStreamEngine()
        startCamera()

        try {
            requireContext().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {}

        updateStorageText()
    }

    private fun initStreamEngine() {
        rtmpStream = RtmpStream(requireContext(), this).apply {
            // Prepare with high quality
            prepareVideo(1280, 720, 30, 4000 * 1000, 0, 2)
            prepareAudio(44100, true, 128 * 1000, false, false)

            val isConcurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
            } else false

            if (isConcurrent) {
                surfaceFilter = SurfaceFilterRender().apply {
                    setScale(30f, 30f)
                    setPosition(70f, 70f)
                }
                getGlInterface().setFilter(surfaceFilter!!)
            }
        }
    }

    private fun setupButtons() {
        binding.btnRec.setOnClickListener { toggleRecording() }
        binding.btnGallery.setOnClickListener {
            findNavController().navigate(R.id.action_camera_to_gallery)
        }
        binding.btnSettings.setOnClickListener {
            findNavController().navigate(R.id.action_camera_to_settings)
        }
        binding.btnLock.setOnClickListener { lockCurrentClip() }

        binding.btnRec.setOnLongClickListener {
            toggleStreaming()
            true
        }
    }

    private fun toggleStreaming() {
        if (isStreamingActive) {
            stopStreaming()
        } else {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val url = prefs.getString("rtmp_url", "")
            if (url.isNullOrEmpty()) {
                Toast.makeText(context, "Configure RTMP URL in settings", Toast.LENGTH_SHORT).show()
                return
            }
            startStreaming(url)
        }
    }

    private fun startStreaming(url: String) {
        rtmpStream?.let { stream ->
            if (!stream.isStreaming) {
                stream.startStream(url)
                isStreamingActive = true
                Toast.makeText(context, "Streaming Started", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopStreaming() {
        rtmpStream?.stopStream()
        isStreamingActive = false
        Toast.makeText(context, "Streaming Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRecording() {
        if (isRecordingActive) {
            stopRecording()
            showDriveSummary()
        } else {
            startDriveSession()
            startRecording()
        }
    }

    private fun startDriveSession() {
        driveStartTime = System.currentTimeMillis()
        maxSpeed = 0f
        incidentCount = 0
    }

    private fun startRecording() {
        val stream = rtmpStream ?: return

        binding.recLayout.visibility = View.VISIBLE
        startRecAnimation()
        StorageManager.cleanupOldFiles(requireContext())

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val videoName = "$name.mp4"

        // We need a file path for RootEncoder to record to
        // On modern Android we'll record to a temporary internal file then move to MediaStore
        val tempFile = File(requireContext().cacheDir, videoName)
        currentVideoPath = tempFile.absolutePath

        try {
            stream.startRecord(tempFile.absolutePath, object : RecordController.Listener {
                override fun onStatusChange(status: RecordController.Status) {
                    Log.d("CameraFragment", "Record status: $status")
                }
            })
            isRecordingActive = true
            recordingStartTime = System.currentTimeMillis()
            telemetryRecorder = TelemetryRecorder(requireContext(), videoName)
            handler.post(updateTimerRunnable)
        } catch (e: Exception) {
            Log.e("CameraFragment", "Failed to start recording", e)
            Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartRecording() {
        stopRecording()
        startRecording()
    }

    private fun stopRecording() {
        if (!isRecordingActive) return

        rtmpStream?.stopRecord()
        isRecordingActive = false
        binding.recLayout.visibility = View.GONE
        binding.recDot.clearAnimation()
        handler.removeCallbacks(updateTimerRunnable)

        telemetryRecorder?.save()
        telemetryRecorder = null

        // Move recorded file to MediaStore
        currentVideoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val uri = moveFileToMediaStore(file)
                currentVideoUri = uri
            }
        }

        updateStorageText()
    }

    private fun moveFileToMediaStore(file: File): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroidDashCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = requireContext().contentResolver.insert(collection, values)

        uri?.let { targetUri ->
            try {
                requireContext().contentResolver.openOutputStream(targetUri)?.use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    requireContext().contentResolver.update(targetUri, values, null, null)
                }
                file.delete()
                return targetUri
            } catch (e: Exception) {
                Log.e("CameraFragment", "Error moving file to MediaStore", e)
            }
        }
        return null
    }

    private fun showDriveSummary() {
        val duration = (System.currentTimeMillis() - driveStartTime) / 1000 / 60
        AlertDialog.Builder(requireContext(), R.style.Theme_DroidDashCam)
            .setTitle("Drive Summary")
            .setMessage("Duration: $duration min\nMax Speed: ${(maxSpeed * 3.6).toInt()} km/h\nIncidents detected: $incidentCount")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun getDisplayNameFromUri(uri: android.net.Uri): String? {
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        requireContext().contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    private fun updateStorageText() {
        Log.d("CameraFragment", StorageManager.getAvailableSpaceText())
    }

    private fun setupSensors() {
        val hasGps = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasGps) {
            locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            try {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
                binding.iconGps.setColorFilter(ContextCompat.getColor(requireContext(), R.color.green_status))
            } catch (e: Exception) {
                binding.iconGps.setColorFilter(ContextCompat.getColor(requireContext(), R.color.yellow_status))
            }
        }

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = location
        val speed = location.speed
        if (speed > maxSpeed) maxSpeed = speed

        val speedKmh = (speed * 3.6).toInt()
        binding.textSpeed.text = "$speedKmh km/h"
        binding.iconGps.setColorFilter(ContextCompat.getColor(requireContext(), R.color.green_status))
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            binding.iconGps.setColorFilter(ContextCompat.getColor(requireContext(), R.color.yellow_status))
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            binding.iconGps.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey_800))
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            if (!prefs.getBoolean("impact_detection", true)) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble())

            val sensitivity = prefs.getInt("g_sensor_sensitivity", 5)
            val threshold = 31.0 - (sensitivity * 2.0)

            if (acceleration > threshold) {
                if (isRecordingActive) {
                    incidentCount++
                    lockCurrentClip()
                    // Visual feedback for incident
                    binding.root.post {
                        binding.btnLock.setColorFilter(ContextCompat.getColor(requireContext(), R.color.red_rec))
                        handler.postDelayed({
                            binding.btnLock.clearColorFilter()
                        }, 2000)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startRecAnimation() {
        val anim = AlphaAnimation(1.0f, 0.2f)
        anim.duration = 500
        anim.repeatMode = Animation.REVERSE
        anim.repeatCount = Animation.INFINITE
        binding.recDot.startAnimation(anim)
    }

    private fun startClock() {
        val clockRunnable = object : Runnable {
            override fun run() {
                binding.textTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 30000)
            }
        }
        handler.post(clockRunnable)
    }

    private fun lockCurrentClip() {
        currentVideoUri?.let { uri ->
            StorageManager.lockFile(requireContext(), uri)
            Toast.makeText(context, "Clip Protected", Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(context, "No active recording to lock", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val isConcurrentSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
            } else false

            if (isConcurrentSupported) {
                bindConcurrentCamera(cameraProvider)
            } else {
                bindSingleCamera(cameraProvider)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindConcurrentCamera(cameraProvider: ProcessCameraProvider) {
        val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        // Back preview for user display
        val backPreview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        // Mixer surface for rtmpStream
        val streamPreviewBack = Preview.Builder().build()
        streamPreviewBack.setSurfaceProvider { request ->
            request.provideSurface(rtmpStream!!.getGlInterface().surface, ContextCompat.getMainExecutor(requireContext())) {}
        }

        // Front preview for mixer (surface filter)
        val streamPreviewFront = Preview.Builder().build()
        streamPreviewFront.setSurfaceProvider { request ->
            request.provideSurface(surfaceFilter!!.surface, ContextCompat.getMainExecutor(requireContext())) {}
        }

        val backGroup = UseCaseGroup.Builder()
            .addUseCase(backPreview)
            .addUseCase(streamPreviewBack)
            .build()

        val frontGroup = UseCaseGroup.Builder()
            .addUseCase(streamPreviewFront)
            .build()

        val backConfig = ConcurrentCamera.SingleCameraConfig(backCameraSelector, backGroup, viewLifecycleOwner)
        val frontConfig = ConcurrentCamera.SingleCameraConfig(frontCameraSelector, frontGroup, viewLifecycleOwner)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(listOf(backConfig, frontConfig))
        } catch (exc: Exception) {
            bindSingleCamera(cameraProvider)
        }
    }

    private fun bindSingleCamera(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        val streamPreview = Preview.Builder().build()
        streamPreview.setSurfaceProvider { request ->
            request.provideSurface(rtmpStream!!.getGlInterface().surface, ContextCompat.getMainExecutor(requireContext())) {}
        }

        val groupBuilder = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(streamPreview)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, groupBuilder.build())
        } catch (exc: Exception) {
            Log.e("CameraFragment", "Binding failed", exc)
        }
    }

    private fun formatElapsedTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60)) % 24
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        activity?.runOnUiThread { Toast.makeText(context, "Stream Success", Toast.LENGTH_SHORT).show() }
    }
    override fun onConnectionFailed(reason: String) {
        activity?.runOnUiThread {
            Toast.makeText(context, "Stream Failed: $reason", Toast.LENGTH_SHORT).show()
            isStreamingActive = false
        }
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        activity?.runOnUiThread { Toast.makeText(context, "Stream Disconnected", Toast.LENGTH_SHORT).show() }
    }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
        locationManager?.removeUpdates(this)
        sensorManager?.unregisterListener(this)
        rtmpStream?.release()
        _binding = null
    }
}
