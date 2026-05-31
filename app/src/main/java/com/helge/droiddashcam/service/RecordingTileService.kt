package com.helge.droiddashcam.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class RecordingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Here we would ideally check if RecordingService is actually running
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, RecordingService::class.java)
        if (qsTile.state == Tile.STATE_INACTIVE) {
            intent.action = RecordingService.ACTION_START
            qsTile.state = Tile.STATE_ACTIVE
        } else {
            intent.action = RecordingService.ACTION_STOP
            qsTile.state = Tile.STATE_INACTIVE
        }
        startService(intent)
        qsTile.updateTile()
    }
}
