package com.nutomic.syncthingandroid.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
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

    /**
     * Opens the camera and closes the quick settings panel, which a plain startActivity leaves
     * open over it: since Android 12, an app cannot close the panel itself.
     */
    override fun onClick() {
        val intent = Intent(applicationContext, PhotoShootActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            // Throws for an app targeting Android 14 or later on Android 14 or later only.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
        super.onClick()
    }
}
