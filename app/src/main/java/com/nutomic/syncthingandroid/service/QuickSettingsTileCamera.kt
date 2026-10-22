package com.nutomic.syncthingandroid.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nutomic.syncthingandroid.activities.PhotoShootActivity

/**
 * Opens the Syncthing camera.
 */
class QuickSettingsTileCamera : TileService() {

    override fun onStartListening() {
        qsTile?.let {
            it.state = Tile.STATE_ACTIVE
            it.updateTile()
        }
        super.onStartListening()
    }

    override fun onClick() {
        startActivity(Intent(applicationContext, PhotoShootActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        super.onClick()
    }
}
