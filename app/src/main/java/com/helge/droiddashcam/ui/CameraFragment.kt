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
import com.pedro.encoder.input.gl.render.filters.`object`.SurfaceFilterRender
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment(), ConnectChecker, LocationListener, SensorEventListener {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService

    private var rtmpStream: RtmpStream? = null
    private var isStreamingActive = false
    private var surfaceFilter: SurfaceFilterRender? = null

    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartTime = 0L
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null

    private val updateTimerRunnable = object : Runnable {
        override fun run() {
            if (recording != null) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                binding.textRecTime.text = formatElapsedTime(elapsed)
                handler.postDelayed(this, 1000)
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
        startCamera()
        startClock()
        setupSensors()

        cameraExecutor = Executors.newSingleThreadExecutor()

        try {
            requireContext().registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: Exception) {}
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
        rtmpStream = RtmpStream(requireContext(), this).apply {
            if (prepareVideo(1280, 720, 30, 2000 * 1000, 0, 2) &&
                prepareAudio(44100, true, 128 * 1000, false, false)) {

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

                startStream(url)
                isStreamingActive = true
                Toast.makeText(context, "Streaming Started", Toast.LENGTH_SHORT).show()
                startCamera()
            }
        }
    }

    private fun stopStreaming() {
        rtmpStream?.stopStream()
        rtmpStream?.release()
        rtmpStream = null
        surfaceFilter = null
        isStreamingActive = false
        Toast.makeText(context, "Streaming Stopped", Toast.LENGTH_SHORT).show()
        startCamera()
    }

    private fun toggleRecording() {
        if (recording != null) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val videoCapture = this.videoCapture ?: return

        binding.recLayout.visibility = View.VISIBLE
        startRecAnimation()

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroidDashCam")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireContext().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
            .apply { if (PermissionChecker.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                if (recordEvent is VideoRecordEvent.Start) {
                    recordingStartTime = System.currentTimeMillis()
                    handler.post(updateTimerRunnable)
                } else if (recordEvent is VideoRecordEvent.Finalize) {
                    if (recordEvent.hasError()) {
                        Log.e("CameraFragment", "Recording error: ${recordEvent.error}")
                    }
                }
            }
    }

    private fun stopRecording() {
        recording?.stop()
        recording = null
        binding.recLayout.visibility = View.GONE
        binding.recDot.clearAnimation()
        handler.removeCallbacks(updateTimerRunnable)
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
        } else {
            binding.iconGps.setColorFilter(ContextCompat.getColor(requireContext(), R.color.grey_800))
        }

        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onLocationChanged(location: Location) {
        val speedKmh = (location.speed * 3.6).toInt()
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
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val acceleration = Math.sqrt((x * x + y * y + z * z).toDouble())
            if (acceleration > 15) {
                lockCurrentClip()
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
        Toast.makeText(context, "Clip Locked / Protected", Toast.LENGTH_SHORT).show()
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

        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
        videoCapture = VideoCapture.withOutput(recorder)

        val frontPreview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinderSecondary.surfaceProvider)
        }
        binding.viewFinderSecondary.visibility = View.VISIBLE

        val backGroupBuilder = UseCaseGroup.Builder()
            .addUseCase(backPreview)
            .addUseCase(videoCapture!!)

        val frontGroupBuilder = UseCaseGroup.Builder()
            .addUseCase(frontPreview)

        rtmpStream?.let { stream ->
            val streamPreviewBack = Preview.Builder().build()
            streamPreviewBack.setSurfaceProvider { request ->
                request.provideSurface(stream.getGlInterface().surface, ContextCompat.getMainExecutor(requireContext())) {}
            }
            backGroupBuilder.addUseCase(streamPreviewBack)

            surfaceFilter?.let { filter ->
                val streamPreviewFront = Preview.Builder().build()
                streamPreviewFront.setSurfaceProvider { request ->
                    request.provideSurface(filter.surface, ContextCompat.getMainExecutor(requireContext())) {}
                }
                frontGroupBuilder.addUseCase(streamPreviewFront)
            }
        }

        val backConfig = ConcurrentCamera.SingleCameraConfig(backCameraSelector, backGroupBuilder.build(), viewLifecycleOwner)
        val frontConfig = ConcurrentCamera.SingleCameraConfig(frontCameraSelector, frontGroupBuilder.build(), viewLifecycleOwner)

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
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
        videoCapture = VideoCapture.withOutput(recorder)

        val groupBuilder = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(videoCapture!!)

        rtmpStream?.let { stream ->
            val streamPreview = Preview.Builder().build()
            streamPreview.setSurfaceProvider { request ->
                request.provideSurface(stream.getGlInterface().surface, ContextCompat.getMainExecutor(requireContext())) {}
            }
            groupBuilder.addUseCase(streamPreview)
        }

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
        cameraExecutor.shutdown()
    }
}
