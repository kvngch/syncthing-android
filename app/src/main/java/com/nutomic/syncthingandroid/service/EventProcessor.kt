package com.nutomic.syncthingandroid.service

import android.content.AsyncQueryHandler
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.FolderStatus
import java.io.File

/**
 * Run by the syncthing service to forward Syncthing's events to the REST API's caches, the
 * notifications and Android's media store.
 *
 * It uses [RestApi.getEvents] to read the pending events and wait for new events.
 */
class EventProcessor(private val context: Context, private val restApi: RestApi) :
    Runnable, RestApi.OnReceiveEventListener {

    /**
     * Use the MainThread for all callbacks and message handling
     * or we have to track down nasty threading problems.
     */
    private val mainThreadHandler = Handler(Looper.getMainLooper())

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val notificationHandler = (context.applicationContext as SyncthingApp).notificationHandler
    private val verboseLog = AppPrefs.getPrefVerboseLog(preferences)
    private val gson = Gson()

    @Volatile
    private var lastEventId = 0L

    @Volatile
    private var shutdown = true

    override fun run() {
        // Restore the last event id if the event processor may have been restarted.
        if (lastEventId == 0L) {
            lastEventId = preferences.getLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, 0)
        }

        // First check if the event number ran backwards.
        // If that's the case we've to start at zero because syncthing was restarted.
        restApi.getEvents(0, 1, object : RestApi.OnReceiveEventListener {
            override fun onError() {}

            override fun onEvent(event: Event, json: JsonElement) {}

            override fun onDone(lastId: Long) {
                if (lastId < lastEventId) {
                    lastEventId = 0
                }
                logV("Reading events starting with id $lastEventId")
                restApi.getEvents(lastEventId, 0, this@EventProcessor)
            }
        })
    }

    /**
     * Performs the actual event handling.
     */
    override fun onEvent(event: Event, json: JsonElement) {
        val data: Map<String, Any?> = event.data.orEmpty()
        fun string(key: String) = data[key] as? String
        when (event.type) {
            "ConfigSaved" -> {
                logV("Forwarding ConfigSaved event to RestApi to get the updated config.")
                restApi.reloadConfig()
            }
            "DeviceConnected" -> restApi.updateRemoteDeviceConnected(string("id"), true)
            "DeviceDisconnected" -> restApi.updateRemoteDeviceConnected(string("id"), false)
            "DevicePaused" -> restApi.updateRemoteDevicePaused(string("device"), true)
            "DeviceResumed" -> restApi.updateRemoteDevicePaused(string("device"), false)
            "FolderCompletion" -> restApi.setRemoteCompletionInfo(
                string("device"),
                string("folder"),
                data["needBytes"] as? Double,
                data["completion"] as? Double,
            )
            "FolderErrors" -> {
                logV("Event ${event.type}, data ${event.data}")
                onFolderErrors(json)
            }
            "FolderPaused" -> restApi.updateLocalFolderPause(string("id"), true)
            "FolderResumed" -> restApi.updateLocalFolderPause(string("id"), false)
            "FolderSummary" -> onFolderSummary(json, string("folder"))
            "ItemFinished" -> onItemFinished(
                string("action"), string("error"), string("folder"), string("item"), event.time
            )
            "LocalIndexUpdated" -> {
                logV("Event ${event.type}, data ${event.data}")
                onLocalIndexUpdated(json, string("folder"), event.time)
            }
            "PendingDevicesChanged" -> added(data).forEach(::onPendingDeviceAdded)
            "PendingFoldersChanged" -> added(data).forEach(::onPendingFolderAdded)
            "RemoteIndexUpdated" -> {
                val deviceId = string("device")
                val folderId = string("folder")
                if (deviceId != null && folderId != null && ((data["items"] as? Double) ?: 0.0) > 0) {
                    restApi.setRemoteIndexUpdated(deviceId, folderId, true)
                }
            }
            "StateChanged" -> restApi.updateLocalFolderState(string("folder"), string("to"))
            "Ping", "DeviceDiscovered", "DownloadProgress", "FolderScanProgress", "FolderWatchStateChanged",
            "ItemStarted", "ListenAddressesChanged", "LoginAttempt", "RemoteDownloadProgress" -> {
                // Ignored.
            }
            "Starting", "StartupComplete" -> logV("Ignored event ${event.type}, data ${event.data}")
            else -> Log.d(TAG, "Unhandled event " + event.type)
        }
    }

    override fun onDone(lastId: Long) {
        if (lastEventId < lastId) {
            lastEventId = lastId

            // Store the last EventId in case we get killed
            preferences.edit { putLong(Constants.PREF_EVENT_PROCESSOR_LAST_SYNC_ID, lastId) }
        }
        scheduleNextRun()
    }

    override fun onError() {
        if (!shutdown) {
            Log.d(TAG, "Event sink aborted, will retry in $EVENT_UPDATE_INTERVAL ms")
        }
        scheduleNextRun()
    }

    private fun scheduleNextRun() {
        synchronized(mainThreadHandler) {
            if (!shutdown) {
                mainThreadHandler.removeCallbacks(this)
                mainThreadHandler.postDelayed(this, EVENT_UPDATE_INTERVAL)
            }
        }
    }

    fun start() {
        Log.d(TAG, "Starting event processor.")

        // Remove all pending callbacks and add a new one. This makes sure that only one
        // event poller is running at any given time.
        synchronized(mainThreadHandler) {
            shutdown = false
            mainThreadHandler.removeCallbacks(this)
            mainThreadHandler.postDelayed(this, EVENT_UPDATE_INTERVAL)
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping event processor.")
        synchronized(mainThreadHandler) {
            shutdown = true
            mainThreadHandler.removeCallbacks(this)
        }
    }

    /**
     * The entries "added" by a PendingDevicesChanged or PendingFoldersChanged event.
     */
    private fun added(data: Map<String, Any?>): List<Map<*, *>> =
        (data["added"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()

    private fun onPendingDeviceAdded(added: Map<*, *>) {
        val deviceId = added["deviceID"] as? String ?: return
        val deviceName = added["name"] as? String
        Log.d(TAG, "Unknown device '$deviceName' ($deviceId) wants to connect")
        // Show device approve/ignore notification.
        notificationHandler.showDeviceConnectNotification(deviceId, deviceName, added["address"] as? String)
    }

    private fun onPendingFolderAdded(added: Map<*, *>) {
        val deviceId = added["deviceID"]?.toString() ?: return
        val folderId = added["folderID"]?.toString() ?: return
        val folderLabel = added["folderLabel"]?.toString().orEmpty()
        Log.d(TAG, "Device '$deviceId' wants to share folder '$folderLabel' ($folderId)")
        val deviceName = restApi.getDevices(false).firstOrNull { it.deviceID == deviceId }?.displayName
        // Show folder approve/ignore notification.
        notificationHandler.showFolderShareNotification(
            deviceId,
            deviceName,
            folderId,
            folderLabel,
            added["receiveEncrypted"] as? Boolean,
            added["remoteEncrypted"] as? Boolean,
            restApi.folders.none { it.id == folderId },
        )
    }

    private fun onFolderErrors(json: JsonElement) {
        val errors = eventData(json)?.get("errors") as? JsonArray
        if (errors == null) {
            Log.e(TAG, "onFolderErrors: errors == null")
            return
        }
        for (error in errors.filterIsInstance<JsonObject>()) {
            val path = error.string("path")
            if (!path.isNullOrEmpty() && error.string("error")?.contains("insufficient space in basic") == true) {
                notificationHandler.showCrashedNotification(R.string.notification_out_of_disk_space, shortened(path))
            }
        }
    }

    private fun onFolderSummary(json: JsonElement, folderId: String?) {
        val summary = eventData(json)?.get("summary")
        if (summary == null) {
            Log.e(TAG, "onFolderSummary: summary == null")
            return
        }
        val folderStatus = try {
            gson.fromJson(summary, FolderStatus::class.java)
        } catch (e: JsonParseException) {
            Log.e(TAG, "onFolderSummary: gson.fromJson failed", e)
            return
        }
        restApi.setLocalFolderStatus(folderId, folderStatus)
    }

    private fun onItemFinished(action: String?, error: String?, folderId: String?, relativeFilePath: String?, time: String?) {
        val folder = restApi.folders.firstOrNull { it.id == folderId }
        if (action.isNullOrEmpty() || folderId.isNullOrEmpty() || relativeFilePath.isNullOrEmpty() || folder == null) {
            Log.w(TAG, "ItemFinished: Failed to determine folder.path for folder.id=\"${folderId.orEmpty()}\"")
            return
        }
        if (error.isNullOrEmpty()) {
            // We don't intend to show errors as the last synced item on the UI.
            restApi.setLocalFolderLastItemFinished(folderId, action, relativeFilePath, time)
        }
        val fullFilePath = folder.path + File.separator + relativeFilePath
        if (!error.isNullOrEmpty()) {
            Log.e(TAG, "onItemFinished: Error \"$error\" reported on file: $fullFilePath")
            if ("no space left on device" in error) {
                notificationHandler.showCrashedNotification(R.string.notification_out_of_disk_space, shortened(fullFilePath))
            }
            return
        }
        if (folder.type == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) {
            // Skip notifying Android's MediaStore, MediaScanner.
            return
        }
        updateMediaStore(action, fullFilePath)
    }

    @Suppress("DEPRECATION")
    private fun updateMediaStore(action: String, fullFilePath: String) {
        when (action) {
            // File deleted.
            "delete" -> {
                if (File(fullFilePath).exists()) {
                    Log.i(TAG, "onItemFinished: MediaStore, Skip file deletion because file exists: $fullFilePath")
                    return
                }
                Log.i(TAG, "onItemFinished: MediaStore, Deleting file: $fullFilePath")
                AsyncQueryHandler(context.contentResolver).startDelete(
                    0,
                    null,
                    MediaStore.Files.getContentUri("external"),
                    MediaStore.Images.ImageColumns.DATA + " = ?",
                    arrayOf(fullFilePath),
                )
            }
            // File contents changed.
            "update" -> {
                Log.i(TAG, "onItemFinished: MediaScanner, Rescanning file: $fullFilePath")
                MediaScannerConnection.scanFile(context, arrayOf(fullFilePath), null, null)
            }
            // File metadata changed but not contents.
            "metadata" -> Log.i(TAG, "onItemFinished: MediaScanner, Skipping file: $fullFilePath")
            else -> Log.w(TAG, "onItemFinished: Unhandled action \"$action\"")
        }
    }

    private fun onLocalIndexUpdated(json: JsonElement, folderId: String?, dateTimeStamp: String?) {
        val filenames = eventData(json)?.get("filenames") as? JsonArray
        if (filenames == null) {
            Log.e(TAG, "onLocalIndexUpdated: filenames == null")
            return
        }
        // Send the last (latest) local change to the UI.
        val filename = (filenames.lastOrNull() as? JsonPrimitive)?.asString
        logV("onLocalIndexUpdated: ${filenames.size()} files, last=[$filename], time=[$dateTimeStamp]")
        if (!filename.isNullOrEmpty()) {
            restApi.setLocalFolderLastItemFinished(folderId, "update", filename, dateTimeStamp)
        }
    }

    private fun eventData(json: JsonElement) = (json as? JsonObject)?.get("data") as? JsonObject

    private fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.asString

    /**
     * The file and its folder, e.g. "Camera/IMG_1.jpg", for a notification.
     */
    private fun shortened(path: String): String {
        val segments = path.split(File.separator).dropLastWhile { it.isEmpty() }
        return if (segments.size < 2) path else segments[segments.size - 2] + File.separator + segments.last()
    }

    private fun logV(message: String) {
        if (verboseLog) {
            Log.v(TAG, message)
        }
    }

    private companion object {
        const val TAG = "EventProcessor"

        /**
         * Minimum interval in ms at which the events are polled from syncthing and processed.
         * This interval will not wake up the device to save battery power.
         */
        const val EVENT_UPDATE_INTERVAL = 5_000L
    }
}
