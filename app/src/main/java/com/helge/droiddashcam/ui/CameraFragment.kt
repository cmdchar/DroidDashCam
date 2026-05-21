package com.helge.droiddashcam.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.helge.droiddashcam.R
import com.helge.droiddashcam.databinding.FragmentCameraBinding
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.base.recording.RecordController
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import com.pedro.encoder.input.gl.render.filters.`object`.TextObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.helge.droiddashcam.utils.StorageManager
import com.helge.droiddashcam.utils.TelemetryRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraFragment : Fragment(), ConnectChecker, LocationListener, SensorEventListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var rtmpStream: RtmpStream? = null
    private var isStreamingActive = false
    private var isRecordingActive = false
    private var surfaceFilter: SurfaceFilterRender? = null
    private var watermarkFilter: TextObjectFilterRender? = null

    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartTime = 0L
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null

    private var currentVideoUri: android.net.Uri? = null
    private var currentVideoPath: String? = null
    private var telemetryRecorder: TelemetryRecorder? = null
    private var lastKnownLocation: Location? = null

    private var maxSpeed = 0f
    private var incidentCount = 0
    private var driveStartTime = 0L
    private var isFrontMain = false
    private var isLockedCurrent = false

    private val updateTimerRunnable = object : Runnable {
        override fun run() {
            val b = _binding ?: return
            if (isRecordingActive) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                b.textRecTime.text = formatElapsedTime(elapsed)

                lastKnownLocation?.let { loc ->
                    telemetryRecorder?.addData(loc.latitude, loc.longitude, loc.speed)
                }

                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val loopDurationMin = (prefs.getString("loop_duration", "5") ?: "5").toLong()
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
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            _binding?.let {
                it.textBattery.text = "${level}%"
                it.textTemp.text = "${temp / 10}°C"
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                if (prefs.getBoolean("auto_start_bt", false)) {
                    if (!isRecordingActive) {
                        toggleRecording()
                        Toast.makeText(context, "Car BT Connected: Auto-Starting", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (allPermissionsGranted()) {
            initApp()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun initApp() {
        try {
            setupButtons()
            startClock()
            setupSensors()
            initStreamEngine()
            setupGauges()
            startCamera()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
                requireContext().registerReceiver(bluetoothReceiver, IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED), Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                requireContext().registerReceiver(bluetoothReceiver, IntentFilter(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED))
            }
        } catch (e: Exception) {
            Log.e("CameraFragment", "Error during initApp", e)
        }
        updateStorageText()
    }

    private fun initStreamEngine() {
        rtmpStream = RtmpStream(requireContext(), this).apply {
            // Setup filters immediately
            watermarkFilter = TextObjectFilterRender().apply {
                setText("DroidDashCam PRO", 24f, android.graphics.Color.WHITE)
                setDefaultScale(1280, 720)
                setPosition(TranslateTo.BOTTOM_LEFT)
            }
            getGlInterface().setFilter(watermarkFilter!!)

            val isConcurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
            } else false

            if (isConcurrent) {
                surfaceFilter = SurfaceFilterRender().apply {
                    setScale(30f, 30f)
                    setPosition(70f, 70f)
                }
                getGlInterface().addFilter(surfaceFilter!!)
            }
        }
    }

    private fun prepareStreamEngine() {
        // Defer video/audio preparation until camera is ready to avoid race conditions with MediaCodec
        rtmpStream?.let { stream ->
            if (!stream.isOnPreview) {
                stream.prepareVideo(1280, 720, 30, 4000 * 1000, 0, 2)
                stream.prepareAudio(44100, true, 128 * 1000, false, false)
            }
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

        val backPreview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        val streamPreviewBack = Preview.Builder().build().also {
            it.setSurfaceProvider { request ->
                rtmpStream?.let { stream ->
                    prepareStreamEngine()
                    request.provideSurface(stream.getGlInterface().surface, ContextCompat.getMainExecutor(requireContext())) {}
                }
            }
        }

        val streamPreviewFront = Preview.Builder().build().also {
            it.setSurfaceProvider { request ->
                surfaceFilter?.let { filter ->
                    request.provideSurface(filter.surface, ContextCompat.getMainExecutor(requireContext())) {}
                }
            }
        }

        val backGroup = UseCaseGroup.Builder()
            .addUseCase(backPreview)
            .addUseCase(streamPreviewBack)
            .build()
        val frontGroup = UseCaseGroup.Builder().addUseCase(streamPreviewFront).build()

        val backConfig = ConcurrentCamera.SingleCameraConfig(backCameraSelector, backGroup, viewLifecycleOwner)
        val frontConfig = ConcurrentCamera.SingleCameraConfig(frontCameraSelector, frontGroup, viewLifecycleOwner)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(listOf(backConfig, frontConfig))
        } catch (exc: Exception) {
            Log.e("CameraFragment", "Concurrent bind failed, falling back", exc)
            bindSingleCamera(cameraProvider)
        }
    }

    private fun bindSingleCamera(cameraProvider: ProcessCameraProvider) {
        val backPreview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }
        val streamPreview = Preview.Builder().build().also {
            it.setSurfaceProvider { request ->
                rtmpStream?.let { stream ->
                    prepareStreamEngine()
                    request.provideSurface(stream.getGlInterface().surface, ContextCompat.getMainExecutor(requireContext())) {}
                }
            }
        }
        val group = UseCaseGroup.Builder()
            .addUseCase(backPreview)
            .addUseCase(streamPreview)
            .build()
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        } catch (exc: Exception) {
            Log.e("CameraFragment", "Binding failed", exc)
        }
    }

    private fun setupButtons() {
        binding.btnRec.setOnClickListener { toggleRecording() }
        binding.btnGallery.setOnClickListener { findNavController().navigate(R.id.action_camera_to_gallery) }
        binding.btnSettings.setOnClickListener { findNavController().navigate(R.id.action_camera_to_settings) }
        binding.btnLock.setOnClickListener { lockCurrentClip() }
        binding.btnPhoto.setOnClickListener { takePhoto() }
        binding.btnSwitch.setOnClickListener { switchCameras() }
        binding.btnRec.setOnLongClickListener { toggleStreaming(); true }
    }

    private fun toggleStreaming() {
        if (isStreamingActive) {
            rtmpStream?.stopStream()
            isStreamingActive = false
            Toast.makeText(context, "Streaming Stopped", Toast.LENGTH_SHORT).show()
        } else {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val url = prefs.getString("rtmp_url", "")
            if (url.isNullOrEmpty()) {
                Toast.makeText(context, "Configure RTMP URL in settings", Toast.LENGTH_SHORT).show()
                return
            }
            rtmpStream?.startStream(url)
            isStreamingActive = true
            Toast.makeText(context, "Streaming Started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleRecording() {
        if (isRecordingActive) {
            stopRecording()
            showDriveSummary()
        } else {
            driveStartTime = System.currentTimeMillis()
            maxSpeed = 0f
            incidentCount = 0
            startRecording()
        }
    }

    private fun startRecording() {
        isLockedCurrent = false
        binding.btnLock.clearColorFilter()
        binding.recLayout.visibility = View.VISIBLE
        startRecAnimation()
        StorageManager.cleanupOldFiles(requireContext())

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val videoName = "$name.mp4"
        val tempFile = File(requireContext().cacheDir, videoName)
        currentVideoPath = tempFile.absolutePath

        try {
            rtmpStream?.startRecord(tempFile.absolutePath, object : RecordController.Listener {
                override fun onStatusChange(status: RecordController.Status) {
                    Log.d("CameraFragment", "Record status: $status")
                }
            })
            isRecordingActive = true
            recordingStartTime = System.currentTimeMillis()
            telemetryRecorder = TelemetryRecorder(requireContext(), videoName)
            handler.post(updateTimerRunnable)
        } catch (e: Exception) {
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

        currentVideoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val uri = moveFileToMediaStore(file)
                currentVideoUri = uri
                if (isLockedCurrent && uri != null) {
                    StorageManager.lockFile(requireContext(), uri)
                    isLockedCurrent = false
                }
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
        val uri = requireContext().contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { targetUri ->
            try {
                requireContext().contentResolver.openOutputStream(targetUri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    requireContext().contentResolver.update(targetUri, values, null, null)
                }
                file.delete()
                return targetUri
            } catch (e: Exception) {
                Log.e("CameraFragment", "Error moving file", e)
            }
        }
        return null
    }

    private fun showDriveSummary() {
        val duration = (System.currentTimeMillis() - driveStartTime) / 1000 / 60
        AlertDialog.Builder(requireContext(), R.style.Theme_DroidDashCam)
            .setTitle("Drive Summary")
            .setMessage("Duration: $duration min\nMax Speed: ${(maxSpeed * 3.6).toInt()} km/h\nIncidents: $incidentCount")
            .setPositiveButton("OK", null).show()
    }

    private fun updateStorageText() {
        binding.textStorage.text = StorageManager.getAvailableSpaceText(context)
    }

    private fun setupSensors() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
            binding.iconGps.setColorFilter(ContextCompat.getColor(requireContext(), R.color.green_status))
        }
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastKnownLocation = location
        if (location.speed > maxSpeed) maxSpeed = location.speed
        binding.gaugeSpeed.setValue(location.speed * 3.6f)
        binding.iconGps.setColorFilter(ContextCompat.getColor(requireContext(), R.color.green_status))
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
            val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble())
            val gForce = (acceleration / 9.81).toFloat()
            binding.gaugeGforce.setValue(gForce)

            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            if (prefs.getBoolean("impact_detection", true)) {
                val sensitivity = prefs.getInt("g_sensor_sensitivity", 5)
                if (acceleration > (31.0 - (sensitivity * 2.0))) {
                    if (isRecordingActive) {
                        incidentCount++
                        lockCurrentClip()
                        binding.btnLock.setColorFilter(ContextCompat.getColor(requireContext(), R.color.red_rec))
                        handler.postDelayed({ _binding?.btnLock?.clearColorFilter() }, 2000)
                    }
                }
            }
        }
    }

    private fun takePhoto() {
        try {
            rtmpStream?.getGlInterface()?.takePhoto { bitmap ->
                val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DroidDashCam")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { targetUri ->
                    requireContext().contentResolver.openOutputStream(targetUri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        requireContext().contentResolver.update(targetUri, values, null, null)
                    }
                    activity?.runOnUiThread { Toast.makeText(context, "Photo Saved", Toast.LENGTH_SHORT).show() }
                }
            }
        } catch (e: Exception) {}
    }

    private fun switchCameras() {
        surfaceFilter?.let { filter ->
            isFrontMain = !isFrontMain
            if (isFrontMain) {
                filter.setScale(100f, 100f); filter.setPosition(0f, 0f)
            } else {
                filter.setScale(30f, 30f); filter.setPosition(70f, 70f)
            }
        }
    }

    private fun lockCurrentClip() {
        if (isRecordingActive) {
            isLockedCurrent = true
            binding.btnLock.setColorFilter(ContextCompat.getColor(requireContext(), R.color.red_rec))
            Toast.makeText(context, "Clip Locked", Toast.LENGTH_SHORT).show()
        } else {
            currentVideoUri?.let { StorageManager.lockFile(requireContext(), it) }
        }
    }

    private fun setupGauges() {
        binding.gaugeSpeed.apply { setMaxValue(240f); setUnit("km/h"); setLabel("SPEED") }
        binding.gaugeGforce.apply { setMaxValue(4f); setUnit("G"); setLabel("G-FORCE"); setProgressColor(android.graphics.Color.parseColor("#FF6D00")) }
    }

    private fun startClock() {
        handler.post(object : Runnable {
            override fun run() {
                _binding?.textTime?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 30000)
            }
        })
    }

    private fun startRecAnimation() {
        val anim = AlphaAnimation(1.0f, 0.2f)
        anim.duration = 500
        anim.repeatMode = Animation.REVERSE
        anim.repeatCount = Animation.INFINITE
        binding.recDot.startAnimation(anim)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onConnectionSuccess() { activity?.runOnUiThread { Toast.makeText(context, "Stream Success", Toast.LENGTH_SHORT).show() } }
    override fun onConnectionFailed(reason: String) { activity?.runOnUiThread { Toast.makeText(context, "Stream Failed", Toast.LENGTH_SHORT).show(); isStreamingActive = false } }
    override fun onDisconnect() { activity?.runOnUiThread { Toast.makeText(context, "Disconnected", Toast.LENGTH_SHORT).show() } }
    override fun onConnectionStarted(url: String) {}
    override fun onNewBitrate(bitrate: Long) {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun formatElapsedTime(ms: Long): String {
        val s = (ms / 1000) % 60; val m = (ms / (1000 * 60)) % 60; val h = (ms / (1000 * 60 * 60)) % 24
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unregisterReceiver(batteryReceiver)
            requireContext().unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
        locationManager?.removeUpdates(this)
        sensorManager?.unregisterListener(this)
        rtmpStream?.release()
        _binding = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.toTypedArray()
    }
}
