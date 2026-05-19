package com.helge.droiddashcam.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
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
        binding.mapView.onCreate(savedInstanceState)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val videoUri = ReviewFragmentArgs.fromBundle(requireArguments()).videoUri
        setupPlayer(Uri.parse(videoUri))
        binding.mapView.getMapAsync(this)

        setupMockEvents()
    }

    private fun setupPlayer(uri: Uri) {
        player = ExoPlayer.Builder(requireContext()).build().also {
            binding.playerView.player = it
            val mediaItem = MediaItem.fromUri(uri)
            it.setMediaItem(mediaItem)
            it.prepare()
        }
    }

    private fun setupMockEvents() {
        // In a real app, these would come from a database associated with the video
        val events = listOf("00:05 - Hard Brake", "00:15 - Manual Save", "00:22 - Speed Alert")
        for (event in events) {
            val btn = Button(requireContext(), null, com.google.android.material.R.attr.materialButtonStyle).apply {
                text = event
                setOnClickListener {
                    val seconds = event.substring(3, 5).toLong()
                    player?.seekTo(seconds * 1000)
                }
            }
            binding.eventContainer.addView(btn)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        val mockLocation = LatLng(44.4268, 26.1025) // Bucharest
        map.addMarker(MarkerOptions().position(mockLocation).title("Incident Location"))
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(mockLocation, 15f))
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        _binding = null
    }
}
