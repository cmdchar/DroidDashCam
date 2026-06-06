package com.helge.droiddashcam.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.helge.droiddashcam.databinding.FragmentViewerBinding

class ViewerFragment : Fragment() {
    private var _binding: FragmentViewerBinding? = null
    private val binding get() = _binding!!
    private var player: ExoPlayer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.playButton.setOnClickListener {
            val url = binding.rtspUrlInput.text.toString()
            if (url.isNotEmpty()) {
                startPlaying(url)
            } else {
                Toast.makeText(context, "Please enter stream URL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlaying(url: String) {
        player?.release()
        val exoPlayer = ExoPlayer.Builder(requireContext()).build()
        player = exoPlayer
        binding.videoView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val status = when(state) {
                    Player.STATE_IDLE -> "Status: Idle"
                    Player.STATE_BUFFERING -> "Status: Buffering..."
                    Player.STATE_READY -> "Status: Live"
                    Player.STATE_ENDED -> "Status: Ended"
                    else -> "Status: Unknown"
                }
                binding.connectionStatus.text = status
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.connectionStatus.text = "Status: Error - ${error.message}"
            }
        })

        val mediaItem = MediaItem.fromUri(url)
        val mediaSource = if (url.startsWith("rtsp://")) {
            RtspMediaSource.Factory().createMediaSource(mediaItem)
        } else if (url.startsWith("rtmp://")) {
            ProgressiveMediaSource.Factory(RtmpDataSource.Factory())
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(requireContext()))
                .createMediaSource(mediaItem)
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }
}
