package com.nutomic.syncthingandroid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.SyncthingService

/**
 * Starts Syncthing after a boot or an update of the app, if set to run in the background.
 *
 * For testing purposes: adb root & adb shell am broadcast -a android.intent.action.BOOT_COMPLETED
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (AppPrefs.getStartServiceOnBoot(context)) {
            startServiceCompat(context)
        }
    }

    companion object {
        /**
         * Starts the service in the foreground, as Android 8+ requires from the background.
         * See https://stackoverflow.com/a/44505719/1837158
         */
        @JvmStatic
        fun startServiceCompat(context: Context) {
            context.startForegroundService(Intent(context, SyncthingService::class.java))
        }
    }
}
