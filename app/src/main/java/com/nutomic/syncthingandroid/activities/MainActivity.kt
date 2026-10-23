package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.device.DeviceIdDialog
import com.nutomic.syncthingandroid.main.MainScreen
import com.nutomic.syncthingandroid.onboarding.OnboardingActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.PermissionUtil
import com.nutomic.syncthingandroid.util.defaultPreferences
import kotlinx.coroutines.channels.Channel

/**
 * Hosts [MainScreen]: folders, devices and status tabs, and the navigation drawer.
 */
class MainActivity : SyncthingActivity(), SyncthingService.OnServiceStateChangeListener {

    private val sharedPreferences: SharedPreferences by lazy { defaultPreferences }

    private var boundService by mutableStateOf<SyncthingService?>(null)
    private var serviceState by mutableStateOf(SyncthingService.State.INIT)
    private var runAsService by mutableStateOf(false)
    private val menuKeyPresses = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The user can come back to this activity from the recent apps list, service stopped.
        startForegroundService(Intent(this, SyncthingService::class.java))

        onBackPressedDispatcher.addCallback(this) {
            // Leave the activity as the home button does: no loading spinner when coming back.
            moveTaskToBack(true)
        }

        setContent {
            ApplicationTheme {
                // Name and ID of the device shown, kept across a rotation.
                var deviceIdDialog by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
                MainScreen(
                    service = boundService,
                    serviceState = serviceState,
                    preferences = sharedPreferences,
                    runAsService = runAsService,
                    menuKeyPresses = menuKeyPresses,
                    onShowDeviceId = { localDeviceToShow()?.let { deviceIdDialog = it } },
                    onExit = ::doExit,
                )
                deviceIdDialog?.let { (name, id) ->
                    DeviceIdDialog(name, id, isCurrentDevice = true) { deviceIdDialog = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // The storage permission can be revoked while the app is in the background.
        if (!PermissionUtil.haveStoragePermission(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Picks up changes made to the metered wifi flags.
        service?.evaluateRunConditions()
        runAsService = sharedPreferences.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false)

        if (intent?.action == ACTION_EXIT) {
            Log.i(TAG, "Exit app requested by notification action")
            doExit()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        service?.unregisterOnServiceStateChangeListener(this)
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        boundService = service
        // Listeners are kept in a set: binding again on each resume adds no duplicate.
        service?.registerOnServiceStateChangeListener(this)
    }

    override fun onServiceStateChange(currentState: SyncthingService.State) {
        serviceState = currentState
        if (currentState == SyncthingService.State.ERROR) {
            finish()
        }
    }

    /**
     * Toggles the drawer on menu button press.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            menuKeyPresses.trySend(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Name and ID of this device, null after telling the user it is not known yet.
     */
    private fun localDeviceToShow(): Pair<String, String>? {
        val deviceId = sharedPreferences.getString(Constants.PREF_LOCAL_DEVICE_ID, "")
        if (deviceId.isNullOrEmpty()) {
            Toast.makeText(this, R.string.could_not_access_deviceid, Toast.LENGTH_SHORT).show()
            return null
        }
        val deviceName = ConfigRouter(this).getDevices(api, true)
            .firstOrNull { it.deviceID == deviceId }
            ?.displayName
            .orEmpty()
        return deviceName.trim() to deviceId
    }

    /**
     * Stops the service and leaves. The exit button only shows when this needs no confirmation.
     */
    private fun doExit() {
        if (isFinishing) {
            return
        }
        Log.i(TAG, "Exiting app on user request")
        stopService(Intent(this, SyncthingService::class.java))
        finishAndRemoveTask()
    }

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Intent action to exit app.
         */
        const val ACTION_EXIT = ".MainActivity.EXIT"
    }
}
