package com.helge.droiddashcam.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.helge.droiddashcam.databinding.FragmentReviewBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions

class ReviewFragment : Fragment(), OnMapReadyCallback {
    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null
    private var googleMap: GoogleMap? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        try {
            binding.mapView.onCreate(savedInstanceState)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val videoUriString = try {
            ReviewFragmentArgs.fromBundle(requireArguments()).videoUri
        } catch (e: Exception) {
            null
        }

        if (videoUriString != null) {
            setupPlayer(Uri.parse(videoUriString))
        } else {
            Toast.makeText(context, "Error loading video", Toast.LENGTH_SHORT).show()
        }

        try {
            binding.mapView.getMapAsync(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setupMockEvents()
    }

    private fun setupPlayer(uri: Uri) {
        try {
            player = ExoPlayer.Builder(requireContext()).build().also {
                binding.playerView.player = it
                val mediaItem = MediaItem.fromUri(uri)
                it.setMediaItem(mediaItem)
                it.prepare()

                it.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Toast.makeText(context, "Playback Error: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to initialize player", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMockEvents() {
        val events = listOf("00:05 - Hard Brake", "00:15 - Manual Save", "00:22 - Speed Alert")
        for (event in events) {
            val btn = Button(requireContext()).apply {
                text = event
                textSize = 10f
                setPadding(16, 4, 16, 4)
                setOnClickListener {
                    val timeParts = event.split(" ")[0].split(":")
                    if (timeParts.size >= 2) {
                        val seconds = timeParts[0].toLong() * 60 + timeParts[1].toLong()
                        player?.seekTo(seconds * 1000)
                        player?.play()
                    }
                }
            }
            binding.eventContainer.addView(btn)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        val mockLocation = LatLng(44.4268, 26.1025)
        map.addMarker(MarkerOptions().position(mockLocation).title("Incident Location"))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(mockLocation, 15f))
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
        player?.pause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.mapView.onDestroy()
        player?.release()
        player = null
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.mapView?.onSaveInstanceState(outState)
    }
}
