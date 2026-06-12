package com.helge.prodashcam.ui

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.helge.prodashcam.databinding.FragmentReviewBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.provider.MediaStore

class ReviewFragment : Fragment(), OnMapReadyCallback {
    private var _binding: FragmentReviewBinding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null
    private var googleMap: GoogleMap? = null
    private var currentMarker: Marker? = null

    private val telemetryEntries = mutableListOf<TelemetryPoint>()

    data class TelemetryPoint(val timeOffsetMs: Long, val lat: Double, val lng: Double)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReviewBinding.inflate(inflater, container, false)
        try {
            binding.mapView.onCreate(savedInstanceState)
        } catch (e: Exception) {}
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
            val uri = Uri.parse(videoUriString)
            loadTelemetry(uri)
            setupPlayer(uri)
        }

        binding.mapView.getMapAsync(this)
        setupMockEvents()
    }

    private fun loadTelemetry(videoUri: Uri) {
        try {
            val displayName = getDisplayNameFromUri(videoUri) ?: return
            val dir = requireContext().getExternalFilesDir("telemetry") ?: return
            val metaFile = File(dir, "$displayName.json")

            if (metaFile.exists()) {
                val jsonArray = JSONArray(metaFile.readText())
                if (jsonArray.length() > 0) {
                    val startTime = jsonArray.getJSONObject(0).getLong("timestamp")
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        telemetryEntries.add(TelemetryPoint(
                            obj.getLong("timestamp") - startTime,
                            obj.getDouble("lat"),
                            obj.getDouble("lon")
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ReviewFragment", "Failed to load telemetry", e)
        }
    }

    private fun getDisplayNameFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Video.Media.DISPLAY_NAME)
        requireContext().contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    private fun setupPlayer(uri: Uri) {
        player = ExoPlayer.Builder(requireContext()).build().also {
            binding.playerView.player = it
            it.setMediaItem(MediaItem.fromUri(uri))
            it.prepare()

            it.addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    updateMapPosition(newPosition.positionMs)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                        startMapSync()
                    }
                }
            })
        }
    }

    private fun startMapSync() {
        val syncRunnable = object : Runnable {
            override fun run() {
                player?.let {
                    if (it.isPlaying) {
                        updateMapPosition(it.currentPosition)
                        binding.root.postDelayed(this, 1000)
                    }
                }
            }
        }
        binding.root.post(syncRunnable)
    }

    private fun updateMapPosition(positionMs: Long) {
        val point = telemetryEntries.minByOrNull { Math.abs(it.timeOffsetMs - positionMs) } ?: return
        val latLng = LatLng(point.lat, point.lng)

        activity?.runOnUiThread {
            currentMarker?.remove()
            currentMarker = googleMap?.addMarker(MarkerOptions().position(latLng).title("Vehicle Position"))
            googleMap?.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        }
    }

    private fun setupMockEvents() {
        val events = listOf("00:05 - Hard Brake", "00:15 - Manual Save")
        for (event in events) {
            val btn = Button(requireContext()).apply {
                text = event
                textSize = 10f
                setOnClickListener {
                    val timeParts = event.split(" ")[0].split(":")
                    val seconds = timeParts[0].toLong() * 60 + timeParts[1].toLong()
                    player?.seekTo(seconds * 1000)
                    player?.play()
                }
            }
            binding.eventContainer.addView(btn)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        if (telemetryEntries.isNotEmpty()) {
            val first = telemetryEntries.first()
            updateMapPosition(0)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.lat, first.lng), 15f))
        }
    }

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onStop() { super.onStop(); binding.mapView.onStop(); player?.pause() }
    override fun onDestroyView() { super.onDestroyView(); binding.mapView.onDestroy(); player?.release(); _binding = null }
}
