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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.helge.droiddashcam.R
import com.helge.droiddashcam.databinding.FragmentCameraBinding
import com.helge.droiddashcam.service.RecordingService
import com.helge.droiddashcam.utils.StorageManagerV2
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class CameraFragment : Fragment() {
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var recordingService: RecordingService? = null
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())
    private var currentPin = "0000"

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.ServiceBinder
            recordingService = binder.getService()
            observeRecordingState()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
        bindRecordingService()
    }

    private fun bindRecordingService() {
        val intent = Intent(requireContext(), RecordingService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeRecordingState() {
        viewLifecycleOwner.lifecycleScope.launch {
            recordingService?.isRecording?.collectLatest { recording ->
                isRecording = recording
                updateUiState()
            }
        }
    }

    private fun updateUiState() {
        if (isRecording) {
            binding.btnRec.setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            binding.recLayout.visibility = View.VISIBLE
        } else {
            binding.btnRec.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.red_rec))
            binding.recLayout.visibility = View.GONE
        }
    }

    private fun setupButtons() {
        binding.btnGallery.setOnClickListener { findNavController().navigate(R.id.action_camera_to_gallery) }
        binding.btnSettings.setOnClickListener { findNavController().navigate(R.id.action_camera_to_settings) }
        binding.btnRec.setOnClickListener { toggleRecording() }

        binding.btnLock.setOnClickListener {
            if (isRecording) {
                sendCommandToService(RecordingService.ACTION_LOCK)
            } else {
                binding.lockOverlay.visibility = View.VISIBLE
            }
        }

        binding.btnPhoto.setOnClickListener { sendCommandToService(RecordingService.ACTION_PHOTO) }

        binding.btnUnlock.setOnClickListener {
            if (binding.pinInput.text.toString() == currentPin) {
                binding.lockOverlay.visibility = View.GONE
                binding.pinInput.text.clear()
            } else {
                Toast.makeText(context, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendCommandToService(action: String) {
        val intent = Intent(requireContext(), RecordingService::class.java).apply { this.action = action }
        requireContext().startService(intent)
    }

    private fun startCameraPreview() {
        ProcessCameraProvider.getInstance(requireContext()).addListener({
            try {
                val provider = ProcessCameraProvider.getInstance(requireContext()).get()
                val isConcurrent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_CONCURRENT)
                } else false

                provider.unbindAll()
                if (isConcurrent) {
                    val backPreview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
                    val frontPreview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinderSecondary.surfaceProvider) }

                    val backConfig = ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, UseCaseGroup.Builder().addUseCase(backPreview).build(), viewLifecycleOwner)
                    val frontConfig = ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, UseCaseGroup.Builder().addUseCase(frontPreview).build(), viewLifecycleOwner)

                    provider.bindToLifecycle(listOf(backConfig, frontConfig))
                    binding.viewFinderSecondary.visibility = View.VISIBLE
                } else {
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
                    provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    binding.viewFinderSecondary.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("CameraFragment", "Preview failed", e)
                // Fallback to single camera if concurrent binding fails
                startSingleCameraPreview()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun startSingleCameraPreview() {
        ProcessCameraProvider.getInstance(requireContext()).addListener({
            try {
                val provider = ProcessCameraProvider.getInstance(requireContext()).get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                binding.viewFinderSecondary.visibility = View.GONE
            } catch (e: Exception) { Log.e("CameraFragment", "Single preview fallback failed", e) }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun toggleRecording() {
        sendCommandToService(if (isRecording) RecordingService.ACTION_STOP else RecordingService.ACTION_START)
    }

    private fun startClock() {
        handler.post(object : Runnable {
            override fun run() {
                _binding?.textTime?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun updateStorageInfo() { binding.textStorage.text = StorageManagerV2.getAvailableSpaceText(requireContext()) }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }

    override fun onDestroyView() {
        super.onDestroyView()
        try { requireContext().unbindService(serviceConnection) } catch (e: Exception) {}
        handler.removeCallbacksAndMessages(null)
        _binding = null
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
