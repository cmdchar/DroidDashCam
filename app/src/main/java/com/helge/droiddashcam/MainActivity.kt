package com.helge.droiddashcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.helge.droiddashcam.databinding.ActivityMainBinding
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ConnectChecker {
    private var _viewBinding: ActivityMainBinding? = null
    private val viewBinding get() = _viewBinding!!

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var cameraExecutor: ExecutorService

    private var backStream: RtmpStream? = null
    private var frontStream: RtmpStream? = null

    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        viewBinding.videoCaptureButton.setOnClickListener { captureVideo() }
        viewBinding.streamButton.setOnClickListener { toggleStream() }
        viewBinding.viewRemoteButton.setOnClickListener {
            val intent = Intent(this, ViewerActivity::class.java)
            startActivity(intent)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun toggleStream() {
        if (!isStreaming) {
            val baseUrl = viewBinding.rtmpUrlInput.text.toString()
            if (baseUrl.isEmpty()) {
                Toast.makeText(this, "Please enter base RTMP URL", Toast.LENGTH_SHORT).show()
                return
            }

            startStreaming(baseUrl)
        } else {
            stopStreaming()
        }
    }

    private fun startStreaming(baseUrl: String) {
        val backUrl = if (baseUrl.endsWith("/")) "${baseUrl}back" else "$baseUrl/back"
        val frontUrl = if (baseUrl.endsWith("/")) "${baseUrl}front" else "$baseUrl/front"

        try {
            backStream = RtmpStream(this, this).apply {
                if (prepareVideo(1280, 720, 30, 2000 * 1000, 0, 2) &&
                    prepareAudio(44100, true, 128 * 1000, false, false)) {
                    startStream(backUrl)
                }
            }

            val isConcurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
            } else false

            if (isConcurrent) {
                frontStream = RtmpStream(this, object : ConnectChecker {
                    override fun onConnectionStarted(url: String) {}
                    override fun onConnectionSuccess() {}
                    override fun onConnectionFailed(reason: String) {}
                    override fun onNewBitrate(bitrate: Long) {}
                    override fun onDisconnect() {}
                    override fun onAuthError() {}
                    override fun onAuthSuccess() {}
                }).apply {
                    if (prepareVideo(640, 480, 30, 1000 * 1000, 0, 2) &&
                        prepareAudio(44100, true, 128 * 1000, false, false)) {
                        startStream(frontUrl)
                    }
                }
            }

            isStreaming = true
            viewBinding.streamButton.text = getString(R.string.stop_stream)
            Toast.makeText(this, "Streaming started", Toast.LENGTH_SHORT).show()

            // Re-bind camera to include streaming use cases
            startCamera()

        } catch (e: Exception) {
            Log.e(TAG, "Streaming failed to start", e)
            stopStreaming()
        }
    }

    private fun stopStreaming() {
        backStream?.stopStream()
        backStream?.release()
        backStream = null

        frontStream?.stopStream()
        frontStream?.release()
        frontStream = null

        isStreaming = false
        viewBinding.streamButton.text = getString(R.string.start_stream)
        Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()

        // Re-bind camera to remove streaming use cases
        startCamera()
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return

        viewBinding.videoCaptureButton.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(java.util.Date())
        val contentValues = ContentValues()
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DroidDashCam")
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .let {
                if (PermissionChecker.checkSelfPermission(this@MainActivity,
                        Manifest.permission.RECORD_AUDIO) ==
                    PermissionChecker.PERMISSION_GRANTED)
                {
                    it.withAudioEnabled()
                } else {
                    it
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent: VideoRecordEvent ->
                if (recordEvent is VideoRecordEvent.Start) {
                    viewBinding.videoCaptureButton.text = getString(R.string.stop_capture)
                    viewBinding.videoCaptureButton.isEnabled = true
                } else if (recordEvent is VideoRecordEvent.Finalize) {
                    if (!recordEvent.hasError()) {
                        val msg = "Video capture succeeded: " +
                                "${recordEvent.outputResults.outputUri}"
                        Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                        Log.d(TAG, msg)
                    } else {
                        recording?.close()
                        recording = null
                        Log.e(TAG, "Video capture ends with error: " +
                                "${recordEvent.error}")
                    }
                    viewBinding.videoCaptureButton.text = getString(R.string.start_capture)
                    viewBinding.videoCaptureButton.isEnabled = true
                }
            }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val isConcurrentSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
            } else false

            if (isConcurrentSupported) {
                bindConcurrentCamera(cameraProvider)
            } else {
                bindSingleCamera(cameraProvider)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindConcurrentCamera(cameraProvider: ProcessCameraProvider) {
        val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        val backPreview = Preview.Builder().build()
        backPreview.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val frontPreview = Preview.Builder().build()
        frontPreview.setSurfaceProvider(viewBinding.viewFinderSecondary.surfaceProvider)
        viewBinding.viewFinderSecondary.visibility = View.VISIBLE

        val backGroupBuilder = UseCaseGroup.Builder()
            .addUseCase(backPreview)
            .addUseCase(videoCapture!!)

        val frontGroupBuilder = UseCaseGroup.Builder()
            .addUseCase(frontPreview)

        // Add streaming use cases if active
        backStream?.let { stream ->
            val backStreamPreview = Preview.Builder().build()
            backStreamPreview.setSurfaceProvider { request ->
                val surface = stream.getGlInterface().surface
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {}
            }
            backGroupBuilder.addUseCase(backStreamPreview)
        }

        frontStream?.let { stream ->
            val frontStreamPreview = Preview.Builder().build()
            frontStreamPreview.setSurfaceProvider { request ->
                val surface = stream.getGlInterface().surface
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {}
            }
            frontGroupBuilder.addUseCase(frontStreamPreview)
        }

        val backConfig = ConcurrentCamera.SingleCameraConfig(
            backCameraSelector,
            backGroupBuilder.build(),
            this
        )

        val frontConfig = ConcurrentCamera.SingleCameraConfig(
            frontCameraSelector,
            frontGroupBuilder.build(),
            this
        )

        val configList = ArrayList<ConcurrentCamera.SingleCameraConfig>()
        configList.add(backConfig)
        configList.add(frontConfig)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(configList)
        } catch (exc: Exception) {
            Log.e(TAG, "Concurrent use case binding failed", exc)
            bindSingleCamera(cameraProvider)
        }
    }

    private fun bindSingleCamera(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val groupBuilder = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(videoCapture!!)

        backStream?.let { stream ->
            val streamPreview = Preview.Builder().build()
            streamPreview.setSurfaceProvider { request ->
                val surface = stream.getGlInterface().surface
                request.provideSurface(surface, ContextCompat.getMainExecutor(this)) {}
            }
            groupBuilder.addUseCase(streamPreview)
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, groupBuilder.build())
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(baseContext, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        backStream?.release()
        frontStream?.release()
        _viewBinding = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults:
        IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // ConnectChecker Implementation
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        runOnUiThread { Toast.makeText(this, "Stream connection success", Toast.LENGTH_SHORT).show() }
    }
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Stream connection failed: $reason", Toast.LENGTH_SHORT).show()
            if (!isStreaming) viewBinding.streamButton.text = getString(R.string.start_stream)
        }
    }
    override fun onNewBitrate(bitrate: Long) {}
    override fun onDisconnect() {
        runOnUiThread { Toast.makeText(this, "Stream disconnected", Toast.LENGTH_SHORT).show() }
    }
    override fun onAuthError() {
        runOnUiThread { Toast.makeText(this, "Stream auth error", Toast.LENGTH_SHORT).show() }
    }
    override fun onAuthSuccess() {
        runOnUiThread { Toast.makeText(this, "Stream auth success", Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private const val TAG = "DroidDashCam"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS: Array<String>
            get() {
                val permissions = ArrayList<String>()
                permissions.add(Manifest.permission.CAMERA)
                permissions.add(Manifest.permission.RECORD_AUDIO)
                permissions.add(Manifest.permission.INTERNET)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                return permissions.toArray(arrayOfNulls<String>(0))
            }
    }
}
