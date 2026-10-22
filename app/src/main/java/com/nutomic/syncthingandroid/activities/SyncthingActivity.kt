package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.service.SyncthingServiceBinder

/**
 * Binds to [SyncthingService] while the activity is resumed.
 */
abstract class SyncthingActivity : ThemedAppCompatActivity(), ServiceConnection {

    /**
     * The service, or null while it is not bound.
     */
    var service: SyncthingService? = null
        private set

    /**
     * The REST API, or null while the service is not bound.
     */
    val api: RestApi?
        get() = service?.api

    override fun onResume() {
        super.onResume()
        bindService(Intent(this, SyncthingService::class.java), this, BIND_AUTO_CREATE)
    }

    override fun onPause() {
        unbindService(this)
        super.onPause()
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        service = (iBinder as SyncthingServiceBinder).service
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        service = null
    }
}
