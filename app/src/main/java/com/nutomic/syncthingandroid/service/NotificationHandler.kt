package com.nutomic.syncthingandroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.activities.MainActivity
import com.nutomic.syncthingandroid.onboarding.OnboardingActivity
import com.nutomic.syncthingandroid.service.SyncthingService.State

/**
 * The persistent notification of the service, and the notifications of crashes, missing
 * permission and devices or folders asking to connect. Created once, by SyncthingApp.
 */
class NotificationHandler(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val persistentChannel = createChannel(
        CHANNEL_PERSISTENT, R.string.notifications_persistent_channel, NotificationManager.IMPORTANCE_MIN
    ) { hideFromLockscreen() }

    private val persistentChannelWaiting = createChannel(
        CHANNEL_PERSISTENT_WAITING, R.string.notification_persistent_waiting_channel, NotificationManager.IMPORTANCE_MIN
    ) { hideFromLockscreen() }

    private val infoChannel = createChannel(
        CHANNEL_INFO, R.string.notifications_other_channel, NotificationManager.IMPORTANCE_LOW
    ) { setShowBadge(true) }

    private var lastNotificationText: String? = null
    private var inForeground = false

    /**
     * Set by SyncthingService when the app starts and shuts down: the persistent notification
     * then leaves the foreground and disappears.
     */
    var appShutdownInProgress = false

    private fun createChannel(
        id: String,
        @StringRes name: Int,
        importance: Int,
        configure: NotificationChannel.() -> Unit,
    ) = NotificationChannel(id, context.getString(name), importance).apply {
        enableVibration(false)
        setSound(null, null)
        configure()
        notificationManager.createNotificationChannel(this)
    }

    private fun NotificationChannel.hideFromLockscreen() {
        enableLights(false)
        setShowBadge(false)
        lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
    }

    /**
     * Shows, updates or hides the notification, keeping the previous details.
     */
    fun updatePersistentNotification(service: SyncthingService) = updatePersistentNotification(service, true, 0, -1)

    fun updatePersistentNotification(
        service: SyncthingService,
        persistNotificationDetails: Boolean,
        onlineDeviceCount: Int,
        totalSyncCompletion: Int,
    ) {
        val currentState = service.currentState
        val syncthingRunning = currentState == State.ACTIVE || currentState == State.STARTING

        // The service stays in the foreground while the app runs: it is not killed, keeps
        // receiving the run condition broadcasts, which Android 8+ only delivers then, and
        // needs a notification for it. https://stackoverflow.com/a/44505719/1837158
        if (appShutdownInProgress && inForeground) {
            Log.v(TAG, "Stopping foreground service")
            service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        }

        if (currentState != State.ACTIVE) {
            // The next session starts without details, as its RestApi does.
            lastNotificationText = null
        }
        val text = when (currentState) {
            State.DISABLED -> context.getString(R.string.syncthing_disabled)
            State.STARTING -> context.getString(R.string.syncthing_starting)
            State.ACTIVE -> {
                if (lastNotificationText == null || !persistNotificationDetails) {
                    lastNotificationText = activeText(onlineDeviceCount, totalSyncCompletion)
                }
                lastNotificationText
            }
            else -> context.getString(R.string.syncthing_terminated)
        }

        // Two separate IDs: if one of the notification channels is hidden then the
        // startForeground() below won't update the notification but use the old one.
        val idToShow = if (syncthingRunning) ID_PERSISTENT else ID_PERSISTENT_WAITING
        val idToCancel = if (syncthingRunning) ID_PERSISTENT_WAITING else ID_PERSISTENT

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val exitIntent = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_EXIT)
        val channel = if (syncthingRunning) persistentChannel else persistentChannelWaiting
        val notification = NotificationCompat.Builder(context, channel.id)
            .setContentTitle(text)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags))
            .addAction(
                R.drawable.baseline_close_24,
                context.getString(R.string.exit),
                PendingIntent.getActivity(context, 0, exitIntent, flags),
            )
            .build()
        if (appShutdownInProgress) {
            notificationManager.cancel(idToShow)
        } else {
            Log.v(TAG, "Starting foreground service or updating notification")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(idToShow, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                service.startForeground(idToShow, notification)
            }
        }
        notificationManager.cancel(idToCancel)
        inForeground = !appShutdownInProgress
    }

    private fun activeText(onlineDeviceCount: Int, totalSyncCompletion: Int): String {
        val resources = context.resources
        return when (totalSyncCompletion) {
            -1 -> context.getString(
                R.string.syncthing_active_details,
                context.getString(R.string.no_remote_devices_connected),
            )
            100 -> context.getString(
                R.string.syncthing_active_details,
                resources.getQuantityString(R.plurals.device_online_up_to_date, onlineDeviceCount, onlineDeviceCount),
            )
            else -> resources.getQuantityString(
                R.plurals.syncthing_active_syncing_device_online,
                onlineDeviceCount,
                totalSyncCompletion,
                onlineDeviceCount,
            )
        }
    }

    fun showCrashedNotification(@StringRes title: Int, extraInfo: String?) {
        val notification = infoNotification()
            .setContentTitle(context.getString(title, extraInfo))
            .setContentText(context.getString(R.string.notification_crash_text, extraInfo))
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, LogActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .setAutoCancel(true)
            .build()
        notificationManager.notify(ID_CRASH, notification)
    }

    /**
     * Closes a notification. Required after the user hit an action button.
     */
    fun cancelConsentNotification(notificationId: Int) {
        if (notificationId == 0) {
            return
        }
        Log.v(TAG, "Cancelling notification with id $notificationId")
        notificationManager.cancel(notificationId)
    }

    fun showStoragePermissionRevokedNotification() {
        val notification = infoNotification()
            .setContentTitle(context.getString(R.string.syncthing_terminated))
            .setContentText(context.getString(R.string.toast_write_storage_permission_required))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(context, OnboardingActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(ID_MISSING_PERM, notification)
    }

    fun showDeviceConnectNotification(deviceId: String?, deviceName: String?, deviceAddress: String?) {
        if (deviceId == null) {
            Log.e(TAG, "showDeviceConnectNotification: deviceId == null")
            return
        }
        val title = context.getString(
            R.string.device_rejected,
            if (deviceName.isNullOrEmpty()) deviceId.take(7) else deviceName,
        )
        val notificationId = notificationIdFor(title)
        val accept = Intent(context, DeviceActivity::class.java)
            .putExtra(DeviceActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(DeviceActivity.EXTRA_IS_CREATE, true)
            .putExtra(DeviceActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(DeviceActivity.EXTRA_DEVICE_NAME, deviceName)
        val ignore = Intent(context, SyncthingService::class.java)
            .setAction(SyncthingService.ACTION_IGNORE_DEVICE)
            .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(SyncthingService.EXTRA_DEVICE_NAME, deviceName)
            .putExtra(SyncthingService.EXTRA_DEVICE_ADDRESS, deviceAddress)
        showConsentNotification(notificationId, title, accept, ignore)
    }

    fun showFolderShareNotification(
        deviceId: String?,
        deviceName: String?,
        folderId: String?,
        folderLabel: String?,
        receiveEncrypted: Boolean?,
        remoteEncrypted: Boolean?,
        isNewFolder: Boolean?,
    ) {
        if (deviceId == null) {
            Log.e(TAG, "showFolderShareNotification: deviceId == null")
            return
        }
        if (folderId == null) {
            Log.e(TAG, "showFolderShareNotification: folderId == null")
            return
        }
        val title = context.getString(
            R.string.folder_rejected,
            deviceName,
            if (folderLabel.isNullOrEmpty()) folderId else "$folderLabel ($folderId)",
        )
        val notificationId = notificationIdFor(title)
        val accept = Intent(context, FolderActivity::class.java)
            .putExtra(FolderActivity.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(FolderActivity.EXTRA_IS_CREATE, isNewFolder == true)
            .putExtra(FolderActivity.EXTRA_DEVICE_ID, deviceId)
            .putExtra(FolderActivity.EXTRA_FOLDER_ID, folderId)
            .putExtra(FolderActivity.EXTRA_FOLDER_LABEL, folderLabel)
            .putExtra(FolderActivity.EXTRA_RECEIVE_ENCRYPTED, receiveEncrypted == true)
            .putExtra(FolderActivity.EXTRA_REMOTE_ENCRYPTED, remoteEncrypted == true)
        val ignore = Intent(context, SyncthingService::class.java)
            .setAction(SyncthingService.ACTION_IGNORE_FOLDER)
            .putExtra(SyncthingService.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(SyncthingService.EXTRA_DEVICE_ID, deviceId)
            .putExtra(SyncthingService.EXTRA_FOLDER_ID, folderId)
            .putExtra(SyncthingService.EXTRA_FOLDER_LABEL, folderLabel)
        showConsentNotification(notificationId, title, accept, ignore)
    }

    /**
     * Replaces the notification with this id, which may be outdated, by one with "accept" and
     * "ignore" actions.
     */
    private fun showConsentNotification(notificationId: Int, text: String, accept: Intent, ignore: Intent) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val piAccept = PendingIntent.getActivity(context, notificationId, accept, flags)
        val piIgnore = PendingIntent.getService(context, notificationId, ignore, flags)
        notificationManager.cancel(notificationId)
        val notification = infoNotification()
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(piAccept)
            .addAction(R.drawable.ic_stat_notify, context.getString(R.string.accept), piAccept)
            .addAction(R.drawable.ic_stat_notify, context.getString(R.string.ignore), piIgnore)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    private fun infoNotification() =
        NotificationCompat.Builder(context, infoChannel.id).setSmallIcon(R.drawable.ic_stat_notify)

    private companion object {
        const val TAG = "NotificationHandler"
        const val ID_PERSISTENT = 1
        const val ID_PERSISTENT_WAITING = 4
        const val ID_CRASH = 9
        const val ID_MISSING_PERM = 10
        const val CHANNEL_PERSISTENT = "01_syncthing_persistent"
        const val CHANNEL_INFO = "02_syncthing_notifications"
        const val CHANNEL_PERSISTENT_WAITING = "03_syncthing_persistent_waiting"

        /**
         * A deterministic id between 1000 and 1999 for each device or folder consent notification:
         * a negative hash must not land on the fixed ids below 1000.
         */
        fun notificationIdFor(text: String) = 1000 + text.hashCode().mod(1000)
    }
}
