package com.nutomic.syncthingandroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Keeps the receivers registered at runtime, to unregister them all at once.
 */
object ReceiverManager {
    private const val TAG = "ReceiverManager"
    private val receivers = mutableListOf<BroadcastReceiver>()

    @JvmStatic
    @Synchronized
    fun registerReceiver(context: Context, receiver: BroadcastReceiver, intentFilter: IntentFilter) {
        receivers.add(receiver)
        ContextCompat.registerReceiver(context, receiver, intentFilter, ContextCompat.RECEIVER_EXPORTED)
    }

    @JvmStatic
    @Synchronized
    fun unregisterAllReceivers(context: Context) {
        receivers.forEach { receiver ->
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // A registration may still be pending in Android, see https://stackoverflow.com/a/3568906
                Log.w(TAG, "unregisterReceiver($receiver) threw IllegalArgumentException")
            }
        }
        receivers.clear()
    }
}
