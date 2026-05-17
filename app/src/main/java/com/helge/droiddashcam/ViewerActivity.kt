package com.helge.droiddashcam

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.helge.droiddashcam.databinding.ActivityViewerBinding

class ViewerActivity : AppCompatActivity() {
    private var _viewBinding: ActivityViewerBinding? = null
    private val viewBinding get() = _viewBinding!!
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _viewBinding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        viewBinding.playButton.setOnClickListener {
            val url = viewBinding.rtspUrlInput.text.toString()
            if (url.isNotEmpty()) {
                startPlaying(url)
            } else {
                Toast.makeText(this, "Please enter stream URL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlaying(url: String) {
        player?.release()
        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        viewBinding.videoView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val status = when(state) {
                    Player.STATE_IDLE -> "Status: Idle"
                    Player.STATE_BUFFERING -> "Status: Buffering..."
                    Player.STATE_READY -> "Status: Playing (PIP Mixed Stream)"
                    Player.STATE_ENDED -> "Status: Ended"
                    else -> "Status: Unknown"
                }
                viewBinding.connectionStatus.text = status
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                viewBinding.connectionStatus.text = "Status: Error - ${error.message}"
            }
        })

        val mediaItem = MediaItem.fromUri(url)
        val mediaSource = if (url.startsWith("rtsp://")) {
            RtspMediaSource.Factory().createMediaSource(mediaItem)
        } else if (url.startsWith("rtmp://")) {
            ProgressiveMediaSource.Factory(RtmpDataSource.Factory())
                .createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(this))
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

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        _viewBinding = null
    }
}
