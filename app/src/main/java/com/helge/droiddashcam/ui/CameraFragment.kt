package com.helge.droiddashcam.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.helge.droiddashcam.R
import com.helge.droiddashcam.data.db.RecordingDao
import com.helge.droiddashcam.databinding.FragmentCameraBinding
import com.helge.droiddashcam.service.RecordingService
import com.helge.droiddashcam.utils.StorageManagerV2
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class CameraFragment : Fragment() {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var recordingDao: RecordingDao

    private lateinit var cameraExecutor: ExecutorService
    private var isRecording = false
    private var currentPin = "0000"

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        currentPin = prefs.getString("ui_pin", "0000") ?: "0000"

        if (allPermissionsGranted()) {
            startCameraPreview()
            startClock()
            updateStorageInfo()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setupButtons()
    }

    private fun setupButtons() {
        binding.btnRec.setOnClickListener { toggleRecording() }

        binding.btnLock.setOnClickListener {
            if (isRecording) {
                sendCommandToService(RecordingService.ACTION_LOCK)
            } else {
                binding.lockOverlay.visibility = View.VISIBLE
            }
        }

        binding.btnPhoto.setOnClickListener {
            sendCommandToService(RecordingService.ACTION_PHOTO)
        }

        binding.btnUnlock.setOnClickListener {
            val input = binding.pinInput.text.toString()
            if (input == currentPin) {
                binding.lockOverlay.visibility = View.GONE
                binding.pinInput.text.clear()
            } else {
                Toast.makeText(context, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendCommandToService(action: String) {
        val intent = Intent(requireContext(), RecordingService::class.java).apply {
            this.action = action
        }
        requireContext().startService(intent)
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (exc: Exception) {
                Log.e("CameraFragment", "Preview binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun toggleRecording() {
        if (isRecording) {
            sendCommandToService(RecordingService.ACTION_STOP)
            isRecording = false
            binding.btnRec.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red_rec))
            binding.recLayout.visibility = View.GONE
        } else {
            sendCommandToService(RecordingService.ACTION_START)
            isRecording = true
            binding.btnRec.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            binding.recLayout.visibility = View.VISIBLE
        }
    }

    private fun startClock() {
        handler.post(object : Runnable {
            override fun run() {
                _binding?.textTime?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun updateStorageInfo() {
        binding.textStorage.text = StorageManagerV2.getAvailableSpaceText(requireContext())
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}
