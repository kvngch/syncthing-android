package com.nutomic.syncthingandroid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor

/**
 * Broadcast-receiver to control and configure Syncthing remotely, once allowed in the settings.
 */
class AppConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Exported receiver: any app can send anything, including an intent without action.
        val action = intent.action
        val packageName = context.packageName
        if (action == null || !action.startsWith(packageName)) {
            Log.w(TAG, "Ignored intent with action \"$action\"")
            return
        }
        val intentAction = action.substring(packageName.length)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (!preferences.getBoolean(Constants.PREF_BROADCAST_SERVICE_CONTROL, false)) {
            if (intentAction == ACTION_FOLLOW || intentAction == ACTION_START || intentAction == ACTION_STOP) {
                Log.w(
                    TAG,
                    "Ignored intent action \"$intentAction\". Enable Settings > Experimental > " +
                        "Service Control by Broadcast if you like to control syncthing remotely.",
                )
            }
            return
        }
        when (intentAction) {
            ACTION_FOLLOW -> {
                Log.d(TAG, "followRunConditions by intent")
                setForceStartStop(context, Constants.BTNSTATE_NO_FORCE_START_STOP)
                BootReceiver.startServiceCompat(context)
            }
            ACTION_START -> {
                Log.d(TAG, "forceStart by intent")
                setForceStartStop(context, Constants.BTNSTATE_FORCE_START)
                BootReceiver.startServiceCompat(context)
            }
            ACTION_STOP -> {
                Log.d(TAG, "forceStop by intent")
                setForceStartStop(context, Constants.BTNSTATE_FORCE_STOP)
            }
            else -> Log.w(TAG, "invalid intent action: $intentAction")
        }
    }

    /**
     * Stores the run mode and tells RunConditionMonitor, as the status tab does.
     */
    private fun setForceStartStop(context: Context, newState: Int) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, newState)
        }
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION))
    }

    private companion object {
        const val TAG = "AppConfigReceiver"

        /**
         * Let Syncthing-Service follow run conditions
         */
        const val ACTION_FOLLOW = ".action.FOLLOW"

        /**
         * Start the Syncthing-Service
         */
        const val ACTION_START = ".action.START"

        /**
         * Stop the Syncthing-Service
         */
        const val ACTION_STOP = ".action.STOP"
    }
}
