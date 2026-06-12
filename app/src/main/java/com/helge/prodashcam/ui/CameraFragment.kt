package com.helge.prodashcam.ui

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
import com.helge.prodashcam.R
import com.helge.prodashcam.databinding.FragmentCameraBinding
import com.helge.prodashcam.service.RecordingService
import com.helge.prodashcam.utils.StorageManager
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
            attachPreviews()
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

        setupGauges()
        if (allPermissionsGranted()) {
            startClock()
            updateStorageInfo()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setupButtons()
        bindRecordingService()
    }

    private fun setupGauges() {
        binding.gaugeSpeed.apply { setMaxValue(240f); setUnit("km/h"); setLabel("SPEED") }
        binding.gaugeGforce.apply { setMaxValue(4f); setUnit("G"); setLabel("G-FORCE"); setProgressColor(android.graphics.Color.parseColor("#FF6D00")) }
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
        viewLifecycleOwner.lifecycleScope.launch {
            recordingService?.speedKmh?.collectLatest { speed ->
                binding.gaugeSpeed.setValue(speed)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            recordingService?.gForce?.collectLatest { g ->
                binding.gaugeGforce.setValue(g)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            recordingService?.isConcurrent?.collectLatest { concurrent ->
                binding.viewFinderSecondary.visibility = if (concurrent) View.VISIBLE else View.GONE
            }
        }
    }

    private fun attachPreviews() {
        recordingService?.let { service ->
            service.backPreview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            service.frontPreview?.setSurfaceProvider(binding.viewFinderSecondary.surfaceProvider)
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

    private fun updateStorageInfo() { binding.textStorage.text = StorageManager.getAvailableSpaceText(requireContext()) }

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
