package com.nutomic.syncthingandroid.service

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.edit
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.defaultPreferences

/**
 * Cycles through following the run conditions, forcing Syncthing to run and forcing it to stop.
 */
class QuickSettingsTileForce : TileService() {

    private val preferences: SharedPreferences
        get() = applicationContext.defaultPreferences

    override fun onStartListening() {
        val tile = qsTile
        if (tile != null) {
            // Disabled while the service does not run.
            if (!isSyncthingServiceRunning(this)) {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.updateTile()
                return
            }
            updateTileState(tile, forceState())
        }
        super.onStartListening()
    }

    override fun onClick() {
        val newState = when (forceState()) {
            Constants.BTNSTATE_FORCE_START -> Constants.BTNSTATE_FORCE_STOP
            Constants.BTNSTATE_NO_FORCE_START_STOP -> Constants.BTNSTATE_FORCE_START
            else -> Constants.BTNSTATE_NO_FORCE_START_STOP
        }
        preferences.edit { putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, newState) }
        RunConditionMonitor.requestUpdate()
        qsTile?.let { updateTileState(it, newState) }
    }

    private fun forceState() =
        preferences.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP)

    private fun updateTileState(tile: Tile, force: Int) {
        val (label, state, icon) = when (force) {
            Constants.BTNSTATE_FORCE_START ->
                Triple(R.string.qs_forced_to_run, Tile.STATE_ACTIVE, R.drawable.ic_qs_forced_to_run)
            Constants.BTNSTATE_FORCE_STOP ->
                Triple(R.string.qs_forced_to_stop, Tile.STATE_ACTIVE, R.drawable.ic_qs_forced_to_stop)
            else ->
                Triple(R.string.qs_following_run_conditions, Tile.STATE_INACTIVE, R.drawable.ic_qs_force)
        }
        tile.label = getString(label)
        tile.state = state
        tile.icon = Icon.createWithResource(this, icon)
        tile.updateTile()
    }
}

/**
 * Whether SyncthingService runs. Since Android 8, getRunningServices only lists the app's own
 * services, which is all the tiles ask about.
 */
@Suppress("DEPRECATION")
internal fun isSyncthingServiceRunning(context: Context): Boolean =
    context.getSystemService(ActivityManager::class.java)
        ?.getRunningServices(Int.MAX_VALUE)
        .orEmpty()
        .any { it.service.className == SyncthingService::class.java.name }
