package com.nutomic.syncthingandroid.service

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.util.defaultPreferences

/**
 * Runs Syncthing for the duration of a scheduled window, now. Available when the time schedule
 * is on and nothing forces Syncthing to run or to stop.
 */
class QuickSettingsTileSchedule : TileService(), ServiceConnection, SyncthingService.OnServiceStateChangeListener {

    private var availableState = Tile.STATE_INACTIVE
    private var syncthingService: SyncthingService? = null

    private val preferences: SharedPreferences
        get() = applicationContext.defaultPreferences

    // Held here: SharedPreferences only keeps a weak reference to its listeners.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Constants.PREF_BTNSTATE_FORCE_START_STOP) {
            refreshTile()
        }
    }

    override fun onDestroy() {
        syncthingService?.unregisterOnServiceStateChangeListener(this)
        syncthingService = null
        preferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            applicationContext.unbindService(this)
        } catch (e: IllegalArgumentException) {
            // Not bound or already unbound.
        } catch (e: IllegalStateException) {
            // Not bound or already unbound.
        }
        super.onDestroy()
    }

    override fun onStartListening() {
        if (qsTile != null) {
            preferences.registerOnSharedPreferenceChangeListener(prefListener)
            try {
                // No BIND_AUTO_CREATE: opening the panel must not create the service. Without
                // onStartCommand, it would have no RunConditionMonitor, and survive "Exit".
                applicationContext.bindService(Intent(applicationContext, SyncthingService::class.java), this, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind to SyncthingService", e)
            }
            refreshTile()
        }
        super.onStartListening()
    }

    override fun onClick() {
        if (qsTile?.state == Tile.STATE_UNAVAILABLE) {
            return
        }
        RunConditionMonitor.fireSyncTrigger(true)
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        syncthingService = (service as SyncthingServiceBinder).service.also {
            it.registerOnServiceStateChangeListener(this)
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        syncthingService = null
    }

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        availableState = if (
            currentState == SyncthingService.State.STARTING || currentState == SyncthingService.State.ACTIVE
        ) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        refreshTile()
    }

    private fun refreshTile() {
        val schedulable = isSyncthingServiceRunning(this) &&
            preferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false) &&
            preferences.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP) ==
            Constants.BTNSTATE_NO_FORCE_START_STOP
        updateTile(if (schedulable) availableState else Tile.STATE_UNAVAILABLE)
    }

    private fun updateTile(newState: Int) {
        val tile = qsTile ?: return
        if (newState == tile.state) {
            return
        }
        tile.state = newState
        tile.label = if (newState == Tile.STATE_INACTIVE || newState == Tile.STATE_ACTIVE) {
            val minutes = preferences.getString(Constants.PREF_SYNC_DURATION_MINUTES, null)?.toIntOrNull() ?: 5
            getString(R.string.qs_schedule_label_minutes, minutes)
        } else {
            getString(R.string.qs_schedule_disabled)
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "QuickSettingsTileSchedule"
    }
}
