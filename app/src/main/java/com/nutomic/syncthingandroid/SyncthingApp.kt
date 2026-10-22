package com.nutomic.syncthingandroid

import android.app.Application
import android.os.StrictMode
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.NotificationHandler

class SyncthingApp : Application() {

    /**
     * Shared by the service, its helpers and the settings, created on first use.
     */
    val notificationHandler by lazy { NotificationHandler(this) }

    override fun onCreate() {
        super.onCreate()
        // Replacing the default VM policy lifts the crash Android sets on file:// URI exposure,
        // which FileUtils.openFolder relies on to hand a folder to a file manager. Only debug
        // builds detect and log every other violation.
        val vmPolicy = StrictMode.VmPolicy.Builder()
        if (Constants.isDebuggable(this)) {
            vmPolicy.detectAll().penaltyLog()
        }
        StrictMode.setVmPolicy(vmPolicy.build())
    }
}
