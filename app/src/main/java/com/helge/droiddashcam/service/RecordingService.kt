package com.helge.droiddashcam.service

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.preference.PreferenceManager
import com.helge.droiddashcam.MainActivity
import com.helge.droiddashcam.R
import com.helge.droiddashcam.data.db.RecordingDao
import com.helge.droiddashcam.data.db.RecordingEntity
import com.helge.droiddashcam.utils.StorageManager
import com.helge.droiddashcam.utils.TelemetryRecorder
import com.pedro.library.rtmp.RtmpStream
import com.pedro.common.ConnectChecker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service(), LifecycleOwner, LocationListener, SensorEventListener, ConnectChecker {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    @Inject lateinit var recordingDao: RecordingDao

    private var videoCaptureFront: VideoCapture<Recorder>? = null
    private var videoCaptureBack: VideoCapture<Recorder>? = null
    private var recordingFront: Recording? = null
    private var recordingBack: Recording? = null

    // Preview use cases for UI
    var backPreview: Preview? = null
    var frontPreview: Preview? = null

    private var rtmpStream: RtmpStream? = null
    private var telemetryRecorder: TelemetryRecorder? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var loopDurationMin = 5
    private var recordingStartTime = 0L

    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null

    inner class ServiceBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = ServiceBinder()
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> get() = _isRecording

    private val _isConcurrent = MutableStateFlow(false)
    val isConcurrent: StateFlow<Boolean> get() = _isConcurrent

    private val _speedKmh = MutableStateFlow(0f)
    val speedKmh: StateFlow<Float> get() = _speedKmh

    private val _gForce = MutableStateFlow(0f)
    val gForce: StateFlow<Float> get() = _gForce

    companion object {
        private const val TAG = "RecordingService"
        const val CHANNEL_ID = "DashcamRecordingChannel"
        const val NOTIFICATION_ID = 101
        const val ACTION_START = "com.helge.droiddashcam.START"
        const val ACTION_STOP = "com.helge.droiddashcam.STOP"
        const val ACTION_LOCK = "com.helge.droiddashcam.LOCK"
        const val ACTION_PHOTO = "com.helge.droiddashcam.PHOTO"
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createNotificationChannel()
        setupSensors()
        initStreamEngine()
        setupCamera()
    }

    private fun initStreamEngine() {
        rtmpStream = RtmpStream(this, this)
        rtmpStream?.prepareVideo(1280, 720, 30, 4000 * 1024, 0, 2)
        rtmpStream?.prepareAudio(44100, true, 128 * 1024, false, false)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Dashcam Service", "Active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action) {
            ACTION_START -> if (!_isRecording.value) startRecordingService()
            ACTION_STOP -> stopRecordingService()
            ACTION_LOCK -> lockCurrentEvent()
            ACTION_PHOTO -> takeStillPhoto()
        }
        return START_STICKY
    }

    private fun startRecordingService() {
        _isRecording.value = true
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        loopDurationMin = (prefs.getString("loop_duration", "5") ?: "5").toInt()

        val rtmpUrl = prefs.getString("rtmp_url", "")
        if (!rtmpUrl.isNullOrEmpty()) {
            rtmpStream?.startStream(rtmpUrl)
        }

        startRecordingFiles()
    }

    private fun setupCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            try {
                val provider = ProcessCameraProvider.getInstance(this).get()
                val concurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_CONCURRENT)
                } else false
                _isConcurrent.value = concurrent

                if (concurrent) bindConcurrentCameras(provider)
                else bindSingleCamera(provider)
            } catch (e: Exception) { Log.e(TAG, "setupCamera failed", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindConcurrentCameras(cameraProvider: ProcessCameraProvider) {
        val recorderBack = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        videoCaptureBack = VideoCapture.withOutput(recorderBack)
        val recorderFront = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build()
        videoCaptureFront = VideoCapture.withOutput(recorderFront)

        backPreview = Preview.Builder().build()
        frontPreview = Preview.Builder().build()

        val streamPreview = Preview.Builder().build()
        streamPreview.setSurfaceProvider { request ->
            rtmpStream?.let { stream ->
                request.provideSurface(stream.getGlInterface().surface, ContextCompat.getMainExecutor(this)) {}
            }
        }

        val backGroup = UseCaseGroup.Builder()
            .addUseCase(videoCaptureBack!!)
            .addUseCase(backPreview!!)
            .addUseCase(streamPreview)
            .build()
        val frontGroup = UseCaseGroup.Builder()
            .addUseCase(videoCaptureFront!!)
            .addUseCase(frontPreview!!)
            .build()

        val backConfig = ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, backGroup, this)
        val frontConfig = ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, frontGroup, this)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(listOf(backConfig, frontConfig))
        } catch (exc: Exception) {
            Log.e(TAG, "Concurrent binding failed", exc)
            _isConcurrent.value = false
            bindSingleCamera(cameraProvider)
        }
    }

    private fun bindSingleCamera(cameraProvider: ProcessCameraProvider) {
        val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
        videoCaptureBack = VideoCapture.withOutput(recorder)
        backPreview = Preview.Builder().build()

        val streamPreview = Preview.Builder().build()
        streamPreview.setSurfaceProvider { request ->
            rtmpStream?.let { stream ->
                request.provideSurface(stream.getGlInterface().surface, ContextCompat.getMainExecutor(this)) {}
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, videoCaptureBack, backPreview, streamPreview)
        } catch (exc: Exception) { Log.e(TAG, "bindSingleCamera failed", exc) }
    }

    private fun startRecordingFiles() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        recordingStartTime = System.currentTimeMillis()
        telemetryRecorder = TelemetryRecorder(this, timestamp)

        videoCaptureBack?.let {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${timestamp}_BACK")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroidDashCam/Back")
            }
            recordingBack = it.output.prepareRecording(this, MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build())
                .withAudioEnabled().start(ContextCompat.getMainExecutor(this)) { ev -> if (ev is VideoRecordEvent.Finalize) saveToDb("${timestamp}_BACK.mp4", "BACK") }
        }

        videoCaptureFront?.let {
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${timestamp}_FRONT")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroidDashCam/Front")
            }
            recordingFront = it.output.prepareRecording(this, MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build())
                .start(ContextCompat.getMainExecutor(this)) { ev -> if (ev is VideoRecordEvent.Finalize) saveToDb("${timestamp}_FRONT.mp4", "FRONT") }
        }

        updateNotification("Recording Active", "Started at ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}")
        startLoopTimer()
    }

    private fun saveToDb(n: String, t: String) { serviceScope.launch { recordingDao.insert(RecordingEntity(fileName = n, timestamp = System.currentTimeMillis(), cameraType = t)) } }

    private fun startLoopTimer() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (_isRecording.value) {
                    if (System.currentTimeMillis() - recordingStartTime >= loopDurationMin * 60 * 1000) restartRecording()
                    else {
                        try {
                            locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc ->
                                telemetryRecorder?.addData(loc.latitude, loc.longitude, loc.speed)
                            }
                        } catch (e: SecurityException) {}
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }, 1000)
    }

    private fun restartRecording() {
        recordingBack?.stop(); recordingFront?.stop()
        telemetryRecorder?.save()
        StorageManager.cleanupOldFiles(this, 5)
        handler.postDelayed({ if (_isRecording.value) startRecordingFiles() }, 1500)
    }

    private fun lockCurrentEvent() {
        serviceScope.launch {
            val recordings = recordingDao.getAll().take(4)
            recordings.forEach { rec ->
                StorageManager.lockFile(this@RecordingService, rec.fileName, rec.cameraType)
                recordingDao.update(rec.copy(isLocked = true, fileName = rec.fileName.replace(".mp4", "_LOCKED.mp4")))
            }
            withContext(Dispatchers.Main) { Toast.makeText(this@RecordingService, "EVENT LOCKED", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun takeStillPhoto() { Toast.makeText(this, "PHOTO CAPTURED", Toast.LENGTH_SHORT).show() }

    private fun setupSensors() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try { locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, this) } catch (e: SecurityException) {}
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.registerListener(this, sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
    }

    override fun onSensorChanged(e: SensorEvent?) {
        e?.let {
            val g = Math.sqrt((it.values[0] * it.values[0] + it.values[1] * it.values[1] + it.values[2] * it.values[2]).toDouble()) / 9.81
            _gForce.value = g.toFloat()
            if (g > 3.0) lockCurrentEvent()
        }
    }

    override fun onLocationChanged(l: Location) { _speedKmh.value = l.speed * 3.6f }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}

    private fun updateNotification(t: String, c: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(t, c)) }

    private fun createNotification(t: String, c: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)
        val lockPi = PendingIntent.getService(this, 2, Intent(this, RecordingService::class.java).apply { action = ACTION_LOCK }, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(t).setContentText(c).setSmallIcon(R.drawable.ic_rec)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_rec, "Stop", stopPi)
            .addAction(R.drawable.ic_lock, "Lock", lockPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sc = NotificationChannel(CHANNEL_ID, "Dashcam Service", NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                description = "DroidDashCam Pro recording engine"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(sc)
        }
    }

    private fun stopRecordingService() {
        _isRecording.value = false
        rtmpStream?.stopStream()
        telemetryRecorder?.save()
        handler.removeCallbacksAndMessages(null)
        recordingBack?.stop(); recordingFront?.stop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override fun onConnectionSuccess() { Log.d(TAG, "RTMP Success") }
    override fun onConnectionFailed(reason: String) { Log.e(TAG, "RTMP Failed: $reason") }
    override fun onConnectionStarted(url: String) {}
    override fun onDisconnect() {}
    override fun onNewBitrate(bitrate: Long) {}
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
}
