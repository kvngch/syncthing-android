package com.nutomic.syncthingandroid.service

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.http.ApiRequest
import com.nutomic.syncthingandroid.http.GetRequest
import com.nutomic.syncthingandroid.http.PostRequest
import com.nutomic.syncthingandroid.model.CachedFolderStatus
import com.nutomic.syncthingandroid.model.CompletionInfo
import com.nutomic.syncthingandroid.model.Config
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Connections
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.DeviceStat
import com.nutomic.syncthingandroid.model.DiscoveredDevice
import com.nutomic.syncthingandroid.model.DiskEvent
import com.nutomic.syncthingandroid.model.Event
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.IgnoredFolder
import com.nutomic.syncthingandroid.model.LocalCompletion
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.PendingDevice
import com.nutomic.syncthingandroid.model.PendingFolder
import com.nutomic.syncthingandroid.model.RemoteCompletion
import com.nutomic.syncthingandroid.model.RemoteCompletionInfo
import com.nutomic.syncthingandroid.model.RemoteIgnoredDevice
import com.nutomic.syncthingandroid.model.SyncthingJson
import com.nutomic.syncthingandroid.model.SystemStatus
import com.nutomic.syncthingandroid.model.deepCopy
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.Util
import com.nutomic.syncthingandroid.util.defaultPreferences
import java.io.File
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.util.Locale
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provides functions to interact with the syncthing REST API.
 */
class RestApi(
    private val context: Context,
    url: URL,
    /**
     * The API key of the local Syncthing instance.
     */
    val apiKey: String,
    private val onApiAvailableListener: OnApiAvailableListener,
    private val onConfigChangedListener: OnConfigChangedListener,
) {
    fun interface OnConfigChangedListener {
        fun onConfigChanged()
    }

    fun interface OnApiAvailableListener {
        fun onApiAvailable()
    }

    fun interface OnResultListener1<T> {
        fun onResult(t: T)
    }

    /**
     * Listener for [getEvents].
     */
    interface OnReceiveEventListener {
        fun onError()

        /**
         * Called for each event.
         */
        fun onEvent(event: Event, json: JsonElement)

        /**
         * Called after all available events have been processed.
         * @param lastId The id of the last event processed. Should be used as a starting point for
         *               the next round of event processing.
         */
        fun onDone(lastId: Long)
    }

    /**
     * The URL of the web GUI, updated when a config change moves it.
     */
    var url: URL = url
        private set

    /**
     * The version name of the Syncthing binary, once read.
     */
    var version: String? = null
        private set

    private val notificationHandler = (context.applicationContext as SyncthingApp).notificationHandler
    private val preferences: SharedPreferences = context.defaultPreferences
    private val verboseLog = AppPrefs.getPrefVerboseLog(context)

    /**
     * Object that must be locked upon accessing config, rawConfig and pendingUntilConfigLoaded.
     */
    private val configLock = Any()
    private var config: Config? = null

    /**
     * Config as last received from Syncthing, including the fields the model does not know.
     * [sendConfig] merges [config] onto it.
     */
    private var rawConfig: JsonObject? = null

    /**
     * Config writes requested while Syncthing is starting, before its config is loaded.
     * Run by [onReloadConfigComplete].
     */
    private val pendingUntilConfigLoaded = mutableListOf<Runnable>()

    /**
     * Results cached from the system status.
     */
    private var localDeviceId: String? = null
    private var urVersionMax: Int? = null

    /**
     * The last successful result of [GetRequest.URI_CONNECTIONS], and when it came.
     */
    private var previousConnections: Connections? = null
    private var previousConnectionTime = 0L

    /**
     * In the last-finishing [readConfigFromRestApi] callback, we have to call
     * SyncthingService.onApiAvailable to indicate that the RestApi class is fully initialized.
     * We do this to avoid getting stuck with our main thread due to synchronous REST queries.
     * The settings need the cached config and system information, e.g. the local device ID.
     * Guarded by asyncQueryCompleteLock.
     */
    private val asyncQueryCompleteLock = Any()
    private var asyncQueryConfigComplete = false
    private var asyncQueryVersionComplete = false
    private var asyncQuerySystemStatusComplete = false

    /**
     * Stores the latest result of device and folder completion events.
     */
    private val localCompletion = LocalCompletion(verboseLog)
    private val remoteCompletion = RemoteCompletion(verboseLog)
    private var lastOnlineDeviceCount = 0
    private var lastTotalSyncCompletion = -1

    @Volatile
    private var hasShutdown = false

    /**
     * Cancelled at shutdown. Conflict files are searched for one folder at a time, in order.
     */
    private val scope = MainScope()
    private val conflictSearch = Dispatchers.IO.limitedParallelism(1)

    private val loadedConfig: Config
        get() = config ?: throw IllegalStateException("The config is not loaded yet")

    /**
     * Gets local device ID, syncthing version and config, then calls the OnApiAvailableListener.
     */
    fun readConfigFromRestApi() {
        logV("Querying config from REST ...")
        synchronized(asyncQueryCompleteLock) {
            asyncQueryVersionComplete = false
            asyncQueryConfigComplete = false
            asyncQuerySystemStatusComplete = false
        }
        get(GetRequest.URI_VERSION) { result ->
            version = SyncthingJson.parseToJsonElement(result).jsonObject["version"]?.jsonPrimitive?.contentOrNull
            updateDebugFacilitiesCache()
            synchronized(asyncQueryCompleteLock) {
                asyncQueryVersionComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
        get(GetRequest.URI_CONFIG) { result ->
            onReloadConfigComplete(result)
            synchronized(asyncQueryCompleteLock) {
                asyncQueryConfigComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
        getSystemStatus { info ->
            localDeviceId = info.myID
            urVersionMax = info.urVersionMax
            synchronized(asyncQueryCompleteLock) {
                asyncQuerySystemStatusComplete = true
                checkReadConfigFromRestApiCompleted()
            }
        }
    }

    private fun checkReadConfigFromRestApiCompleted() {
        if (asyncQueryVersionComplete && asyncQueryConfigComplete && asyncQuerySystemStatusComplete) {
            logV("Reading config from REST completed. Syncthing version is $version")
            // Tell SyncthingService it can transition to State.ACTIVE.
            onApiAvailableListener.onApiAvailable()
            triggerVersioningCleanupIfNecessary()
        }
    }

    /**
     * Every 10th start, lowers the versioning cleanup interval of every folder for 10 seconds, to
     * force a cleanup after startup.
     */
    private fun triggerVersioningCleanupIfNecessary() {
        // A previous run stopped before restoring the intervals it lowered: restore them now.
        preferences.getString(Constants.PREF_VERSIONING_CLEANUP_RESTORE, null)?.let {
            restoreVersioningCleanupIntervals(it)
        }

        var startupCounter = preferences.getInt(Constants.PREF_APP_START_COUNTER, 0) + 1
        if (startupCounter == Int.MAX_VALUE) {
            startupCounter = 1
        }
        preferences.edit { putInt(Constants.PREF_APP_START_COUNTER, startupCounter) }
        if (startupCounter % 10 != 0) {
            logV("Skipping versioning cleanup because it is only triggered every 10th startup to save resources.")
            return
        }

        // The current values are saved first, so they are restored even if we stop meanwhile.
        val originalIntervals = synchronized(configLock) {
            loadedConfig.folders.orEmpty()
                .mapNotNull { folder -> folder.versioning?.let { folder.id.orEmpty() to it.cleanupIntervalS } }
                .toMap()
        }
        val originalIntervalsJson = SyncthingJson.encodeToString(originalIntervals)
        // commit(), not apply(): the original intervals must be on disk before the lowered ones are sent.
        preferences.edit(commit = true) { putString(Constants.PREF_VERSIONING_CLEANUP_RESTORE, originalIntervalsJson) }
        setVersioningCleanupIntervals(originalIntervals.mapValues { 2 })
        // Cancelled by shutdown: the intervals are then restored at the next start.
        scope.launch {
            delay(10_000)
            restoreVersioningCleanupIntervals(originalIntervalsJson)
        }
    }

    private fun restoreVersioningCleanupIntervals(intervalsJson: String) {
        setVersioningCleanupIntervals(SyncthingJson.decodeFromString<Map<String, Int>>(intervalsJson))
        preferences.edit { remove(Constants.PREF_VERSIONING_CLEANUP_RESTORE) }
    }

    fun reloadConfig() = get(GetRequest.URI_CONFIG, onSuccess = ::onReloadConfigComplete)

    private fun onReloadConfigComplete(configResult: String) {
        val raw = try {
            SyncthingJson.parseToJsonElement(configResult).jsonObject
        } catch (e: IllegalArgumentException) {
            throw RuntimeException("config could not be parsed: $configResult", e)
        }
        synchronized(configLock) {
            config = SyncthingJson.decodeFromJsonElement<Config>(raw)
            rawConfig = raw
        }
        Log.d(TAG, "onReloadConfigComplete: Successfully parsed configuration.")

        val pendingActions = synchronized(configLock) {
            pendingUntilConfigLoaded.toList().also { pendingUntilConfigLoaded.clear() }
        }
        pendingActions.forEach { it.run() }

        if (verboseLog) {
            synchronized(configLock) {
                val remoteIgnoredDevices = SyncthingJson.encodeToString(loadedConfig.remoteIgnoredDevices.orEmpty())
                if (remoteIgnoredDevices != "[]") {
                    logV("ORCC: remoteIgnoredDevices = $remoteIgnoredDevices")
                }
                // Loop through devices to get ignoredFolders per device.
                for (device in getDevices(false)) {
                    val ignoredFolders = SyncthingJson.encodeToString(device.ignoredFolders.orEmpty())
                    if (ignoredFolders != "[]") {
                        logV("ORCC: device[${device.displayName}].ignoredFolders = $ignoredFolders")
                    }
                }
            }
        }

        get(GetRequest.URI_PENDING_DEVICES) { result ->
            for ((deviceId, value) in SyncthingJson.parseToJsonElement(result).jsonObject) {
                val pendingDevice = SyncthingJson.decodeFromJsonElement<PendingDevice>(value)
                if (pendingDevice.time.isNullOrEmpty()) {
                    continue
                }
                Log.d(TAG, "ORCC: resultDeviceId = $deviceId('${pendingDevice.name}')")
                notificationHandler.showDeviceConnectNotification(deviceId, pendingDevice.name, pendingDevice.address)
            }
        }
        get(GetRequest.URI_PENDING_FOLDERS) { result ->
            for ((folderId, value) in SyncthingJson.parseToJsonElement(result).jsonObject) {
                val offeredBy = (value as? JsonObject)?.get("offeredBy") as? JsonObject ?: continue
                for ((offeredByDeviceId, offer) in offeredBy) {
                    val pendingFolder = SyncthingJson.decodeFromJsonElement<PendingFolder>(offer)
                    Log.d(TAG, "ORCC: resultFolderId = $folderId('${pendingFolder.label}')")
                    // The offering device may have been removed from the config meanwhile.
                    val matchingDevice = getDevices(false).firstOrNull { it.deviceID == offeredByDeviceId } ?: continue
                    notificationHandler.showFolderShareNotification(
                        offeredByDeviceId,
                        matchingDevice.displayName,
                        folderId,
                        pendingFolder.label,
                        pendingFolder.receiveEncrypted,
                        pendingFolder.remoteEncrypted,
                        folders.none { it.id == folderId },
                    )
                }
            }
        }

        // Update cached device and folder information.
        val folders = folders
        localCompletion.updateFromConfig(folders)
        remoteCompletion.updateFromConfig(getDevices(true), folders)

        // Perform first query for remote device status by forcing a cache miss.
        getRemoteDeviceStatus("")

        for (folder in folders) {
            for (device in folder.sharedWithDevices) {
                val params = mapOf("device" to device.deviceID.orEmpty(), "folder" to folder.id.orEmpty())
                get(GetRequest.URI_DB_COMPLETION, params) { result ->
                    val completionInfo = parseOrNull<CompletionInfo>(result, "completion") ?: return@get
                    logV(
                        "ORCC: /rest/db/completion: folder=${folder.id}, device=${device.displayName}" +
                            ", completion=${completionInfo.completion}" +
                            ", needBytes=" + String.format(Locale.getDefault(), "%.0f", completionInfo.needBytes) +
                            ", remoteState=${completionInfo.remoteState}"
                    )
                    val remoteCompletionInfo = RemoteCompletionInfo().apply {
                        completion = completionInfo.completion
                        needBytes = completionInfo.needBytes
                    }
                    remoteCompletion.setCompletionInfo(device.deviceID, folder.id, remoteCompletionInfo)
                }
            }
        }
    }

    /**
     * Queries debug facilities available from the currently running syncthing binary
     * if the syncthing binary version changed. First launch of the binary is also
     * considered as a version change.
     * Precondition: [version] read from REST
     */
    private fun updateDebugFacilitiesCache() {
        val currentVersion = version
        if (currentVersion == preferences.getString(Constants.PREF_LAST_BINARY_VERSION, "")) {
            return
        }
        // First binary launch or binary upgraded case.
        get(GetRequest.URI_SYSTEM_LOGLEVELS) { result ->
            try {
                val facilities = SyncthingJson.parseToJsonElement(result).jsonObject.getValue("packages").jsonObject.keys
                // The binary version is stored so that this is only read again after an update.
                preferences.edit {
                    putStringSet(Constants.PREF_DEBUG_FACILITIES_AVAILABLE, facilities.toSet())
                    putString(Constants.PREF_LAST_BINARY_VERSION, currentVersion)
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateDebugFacilitiesCache: Failed to get debug facilities. result=$result")
            }
        }
    }

    /**
     * Permanently ignore a device when it tries to connect.
     * Ignored devices will not trigger the "DeviceRejected" event in EventProcessor.
     */
    fun ignoreDevice(deviceId: String?, deviceName: String?, deviceAddress: String?) {
        synchronized(configLock) {
            val config = loadedConfig
            if (config.remoteIgnoredDevices.orEmpty().any { it.deviceID == deviceId }) {
                Log.d(TAG, "Device already ignored [$deviceId]")
                return
            }
            // Ignore device by moving its corresponding "pendingDevice" entry to
            // a newly created "remoteIgnoredDevice" entry.
            val remoteIgnoredDevice = RemoteIgnoredDevice().apply {
                deviceID = deviceId.orEmpty()
                address = deviceAddress.orEmpty()
                name = deviceName.orEmpty()
                time = Util.getLocalZonedDateTime()
            }
            config.remoteIgnoredDevices = (config.remoteIgnoredDevices ?: mutableListOf()).apply { add(remoteIgnoredDevice) }
            sendConfig()
            Log.d(TAG, "Ignored device [$deviceId]")
        }
    }

    /**
     * Permanently ignore a folder share request.
     * Ignored folders will not trigger the "FolderRejected" event in EventProcessor.
     */
    fun ignoreFolder(deviceId: String?, folderId: String?, folderLabel: String?) {
        synchronized(configLock) {
            val device = loadedConfig.devices.orEmpty().firstOrNull { it.deviceID == deviceId } ?: return
            if (device.ignoredFolders.orEmpty().any { it.id == folderId }) {
                Log.d(TAG, "ignoreFolder: Folder [$folderId] already ignored on device [$deviceId]")
                return
            }
            // Ignore folder by moving its corresponding "pendingFolder" entry to
            // a newly created "ignoredFolder" entry.
            val ignoredFolder = IgnoredFolder().apply {
                id = folderId.orEmpty()
                label = folderLabel.orEmpty()
                time = Util.getLocalZonedDateTime()
            }
            device.ignoredFolders = (device.ignoredFolders ?: mutableListOf()).apply { add(ignoredFolder) }
            logV("ignoreFolder: device.ignoredFolders = " + SyncthingJson.encodeToString(device.ignoredFolders.orEmpty()))
            sendConfig()
            Log.d(TAG, "Ignored folder [$folderId] announced by device [$deviceId]")
        }
    }

    /**
     * Undo ignoring devices and folders.
     */
    fun undoIgnoredDevicesAndFolders() {
        Log.d(TAG, "Undo ignoring devices and folders ...")
        synchronized(configLock) {
            loadedConfig.remoteIgnoredDevices?.clear()
            loadedConfig.devices.orEmpty().forEach { it.ignoredFolders?.clear() }
        }
    }

    /**
     * Override folder changes. This is the same as hitting
     * the "override changes" button from the web UI.
     */
    fun overrideChanges(folderId: String?) {
        Log.d(TAG, "overrideChanges '$folderId'")
        post(PostRequest.URI_DB_OVERRIDE, mapOf("folder" to folderId.orEmpty()))
    }

    /**
     * Rescan all folders
     */
    fun rescanAll() {
        Log.d(TAG, "rescanAll")
        post(PostRequest.URI_DB_SCAN)
    }

    /**
     * Revert local folder changes. This is the same as hitting
     * the "Revert local changes" button from the web UI.
     */
    fun revertLocalChanges(folderId: String?) {
        Log.d(TAG, "revertLocalChanges '$folderId'")
        post(PostRequest.URI_DB_REVERT, mapOf("folder" to folderId.orEmpty()))
    }

    val webGuiUrl: URL
        get() = synchronized(configLock) {
            val address = loadedConfig.gui?.address
            try {
                if (address == null) {
                    Log.e(TAG, "getWebGuiUrl: mConfig.gui.address == null, returning 127.0.0.1:8384")
                    URL("https://127.0.0.1:8384")
                } else {
                    URL("https://$address")
                }
            } catch (e: MalformedURLException) {
                throw RuntimeException("Failed to parse web interface URL", e)
            }
        }

    /**
     * Sends current config to Syncthing.
     * Will result in a "ConfigSaved" event.
     * EventProcessor will trigger this.reloadConfig().
     */
    fun sendConfig() {
        val jsonConfig = synchronized(configLock) {
            // POST /rest/system/config replaces the whole configuration, resetting any field
            // absent from the body. Merging onto the config read from Syncthing keeps the fields
            // the model does not know (ldap, stunServers, gui authMode, ...).
            mergeConfigJson(rawConfig, SyncthingJson.encodeToJsonElement(loadedConfig).jsonObject).also { rawConfig = it }.toString()
        }
        post(PostRequest.URI_SYSTEM_CONFIG, body = jsonConfig) { error ->
            // Syncthing rejected the config, e.g. a duplicate folder id: resync the model with it.
            Log.e(TAG, "sendConfig: Syncthing rejected the config, reloading it", error)
            reloadConfig()
        }
        url = webGuiUrl
        onConfigChangedListener.onConfigChanged()
    }

    /**
     * Posts shutdown request.
     * This will cause SyncthingNative to exit and not restart.
     */
    fun shutdown() {
        hasShutdown = true
        scope.cancel()
        post(PostRequest.URI_SYSTEM_SHUTDOWN)
    }

    /**
     * The folders, sorted by label, a copy of the config's.
     */
    val folders: List<Folder>
        get() {
            val folders: List<Folder> = synchronized(configLock) { loadedConfig.folders.orEmpty().deepCopy() }
            for (folder in folders) {
                val path = folder.path
                if (path != null && path.startsWith("~/")) {
                    folder.path = path.replaceFirst("~", FileUtils.getSyncthingTildeAbsolutePath())
                }
            }
            return folders.sortedWith(FOLDERS_COMPARATOR)
        }

    fun getFolderByID(folderId: String?): Folder? = folders.firstOrNull { it.id == folderId }

    /**
     * This is only used for new folder creation, see FolderActivity.
     */
    fun addFolder(folder: Folder) {
        synchronized(configLock) {
            loadedConfig.folders = (loadedConfig.folders ?: mutableListOf()).apply { add(folder) }
            // Send model changes to syncthing, does not require a restart.
            sendConfig()
        }
    }

    fun updateFolder(newFolder: Folder) = updateFolders(listOf(newFolder))

    /**
     * Replaces several folders, then sends the config once.
     */
    fun updateFolders(newFolders: List<Folder>) {
        synchronized(configLock) {
            for (newFolder in newFolders) {
                // The screens do not edit the cleanup interval, and may hold the one that
                // triggerVersioningCleanupIfNecessary lowers for ten seconds: keep the current one.
                loadedConfig.folders?.firstOrNull { it.id == newFolder.id }?.versioning?.let { current ->
                    newFolder.versioning?.cleanupIntervalS = current.cleanupIntervalS
                }
                removeFolderInternal(newFolder.id)
                loadedConfig.folders = (loadedConfig.folders ?: mutableListOf()).apply { add(newFolder) }
            }
            sendConfig()
        }
    }

    fun removeFolder(id: String?) {
        synchronized(configLock) {
            removeFolderInternal(id)
            // localCompletion and remoteCompletion will be updated after the ConfigSaved event.
            sendConfig()
        }
    }

    private fun removeFolderInternal(id: String?) {
        synchronized(configLock) {
            loadedConfig.folders?.let { folders ->
                folders.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { folders.removeAt(it) }
            }
        }
    }

    /**
     * Returns a list of all existing devices.
     *
     * @param includeLocal True if the local device should be included in the result.
     */
    fun getDevices(includeLocal: Boolean): List<Device> {
        val devices: List<Device> = synchronized(configLock) { loadedConfig.devices.orEmpty().deepCopy() }
        return if (includeLocal) devices else devices.filter { it.deviceID != localDeviceId }
    }

    val localDevice: Device
        get() {
            val devices = getDevices(true)
            if (devices.isEmpty()) {
                throw RuntimeException("RestApi.getLocalDevice: devices is empty.")
            }
            logV("getLocalDevice: Looking for local device ID $localDeviceId")
            return devices.firstOrNull { it.deviceID == localDeviceId }
                ?: throw RuntimeException("RestApi.getLocalDevice: Failed to get the local device crucial to continuing execution.")
        }

    /**
     * Adds or updates a device identified by its device ID.
     */
    fun updateDevice(newDevice: Device) {
        synchronized(configLock) {
            removeDeviceInternal(newDevice.deviceID)
            loadedConfig.devices = (loadedConfig.devices ?: mutableListOf()).apply { add(newDevice) }
            sendConfig()
        }
    }

    fun removeDevice(deviceId: String?) {
        synchronized(configLock) {
            removeDeviceInternal(deviceId)
            // remoteCompletion will be updated after the ConfigSaved event.
            sendConfig()
        }
    }

    private fun removeDeviceInternal(deviceId: String?) {
        synchronized(configLock) {
            loadedConfig.devices?.let { devices ->
                devices.indexOfFirst { it.deviceID == deviceId }.takeIf { it >= 0 }?.let { devices.removeAt(it) }
            }
        }
    }

    val options: Options
        get() = synchronized(configLock) { checkNotNull(loadedConfig.options).deepCopy() }

    val gui: Gui
        get() = synchronized(configLock) { checkNotNull(loadedConfig.gui).deepCopy() }

    fun editSettings(newGui: Gui, newOptions: Options) {
        synchronized(configLock) {
            loadedConfig.gui = newGui
            loadedConfig.options = newOptions
        }
    }

    fun updateGui(newGui: Gui) {
        synchronized(configLock) {
            loadedConfig.gui = newGui
            sendConfig()
        }
    }

    /**
     * Requests and parses information about current system status and resource usage.
     */
    fun getSystemStatus(listener: OnResultListener1<SystemStatus>) {
        get(GetRequest.URI_SYSTEM_STATUS) { result ->
            try {
                listener.onResult(SyncthingJson.decodeFromString<SystemStatus>(result))
            } catch (e: Exception) {
                Log.e(TAG, "getSystemStatus: Parsing REST API result failed. result=$result")
            }
        }
    }

    /**
     * Runs action now if the config is loaded. Otherwise Syncthing is starting: it has read
     * config.xml already, so the action waits for the config to be loaded through the API.
     */
    fun runWhenConfigLoaded(action: Runnable) {
        synchronized(configLock) {
            if (config == null) {
                logV("runWhenConfigLoaded: Config not loaded yet, deferring a config change.")
                pendingUntilConfigLoaded.add(action)
                return
            }
        }
        action.run()
    }

    /**
     * Parses a REST response, or returns null (logged) when Syncthing sent something unexpected,
     * e.g. while restarting. Callers then drop the result, as they do on a failed request.
     */
    private inline fun <reified T> parseOrNull(json: String, caller: String): T? =
        try {
            SyncthingJson.decodeFromString<T>(json)
        } catch (e: IllegalArgumentException) {
            // SerializationException included.
            Log.w(TAG, "$caller: Failed to parse the response", e)
            null
        }

    val isConfigLoaded: Boolean
        get() = synchronized(configLock) { config != null }

    /**
     * Requests locally discovered devices.
     */
    fun getDiscoveredDevices(listener: OnResultListener1<Map<String, DiscoveredDevice>>) {
        get(GetRequest.URI_SYSTEM_DISCOVERY) { result ->
            val discoveredDevices: Map<String, DiscoveredDevice> =
                parseOrNull(result, "getDiscoveredDevices") ?: return@get
            listener.onResult(discoveredDevices)
        }
    }

    /**
     * Requests ignore list for given folder.
     */
    fun getFolderIgnoreList(folderId: String?, listener: OnResultListener1<FolderIgnoreList>) {
        get(GetRequest.URI_DB_IGNORES, mapOf("folder" to folderId.orEmpty())) { result ->
            val folderIgnoreList = parseOrNull<FolderIgnoreList>(result, "getFolderIgnoreList") ?: return@get
            listener.onResult(folderIgnoreList)
        }
    }

    /**
     * Posts ignore list for given folder.
     */
    fun postFolderIgnoreList(folderId: String?, ignore: Array<String>) {
        val body = SyncthingJson.encodeToString(FolderIgnoreList().apply { this.ignore = ignore })
        post(PostRequest.URI_DB_IGNORES, mapOf("folder" to folderId.orEmpty()), body)
    }

    /**
     * Returns status information about the device with the given id from cache.
     * Set deviceId to "" to query status for an initially empty cache.
     */
    fun getRemoteDeviceStatus(deviceId: String?): Connection {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId)
        if (cacheEntry.at.isNotEmpty()) {
            return cacheEntry
        }
        // Cache miss: query the required information so it will be available on a future call.
        if (!deviceId.isNullOrEmpty()) {
            logV("getRemoteDeviceStatus: Cache miss, deviceId=\"$deviceId\". Performing query.")
        }
        get(GetRequest.URI_CONNECTIONS) { result ->
            // We got connection status information for ALL devices instead of one.
            // It does not hurt storing all of them.
            val connections = parseOrNull<Connections>(result, "getRemoteDeviceStatus") ?: return@get
            val byDevice = connections.connections ?: return@get
            calculateConnectionStats(connections)
            byDevice.forEach { (id, connection) -> remoteCompletion.setDeviceStatus(id, connection) }
        }
        get(GetRequest.URI_STATS_DEVICE) { result ->
            // We got the last seen timestamp for ALL devices - including the local device - instead of one.
            // It does not hurt storing all of them.
            preferences.edit {
                for ((id, value) in SyncthingJson.parseToJsonElement(result).jsonObject) {
                    val deviceStat = SyncthingJson.decodeFromJsonElement<DeviceStat>(value)
                    putString(Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + id, deviceStat.lastSeen)
                }
            }
        }
        return cacheEntry
    }

    fun getRemoteDeviceCompletion(deviceId: String?): Int = remoteCompletion.getDeviceCompletion(deviceId)

    fun getRemoteDeviceNeedBytes(deviceId: String?): Double = remoteCompletion.getDeviceNeedBytes(deviceId)

    val totalConnectionStatistic: Connection
        get() = previousConnections?.total?.deepCopy() ?: Connection()

    /**
     * Calculate transfer rates for each remote device connection and the "total device" stats.
     * Within REST_UPDATE_INTERVAL of the previous result, the previous rates are kept.
     */
    private fun calculateConnectionStats(connections: Connections) {
        val now = System.currentTimeMillis()
        val msElapsed = now - previousConnectionTime
        if (msElapsed < Constants.REST_UPDATE_INTERVAL) {
            return
        }
        previousConnectionTime = now
        val previous = previousConnections
        connections.connections.orEmpty().forEach { (id, connection) ->
            connection.setTransferRate(previous?.connections?.get(id) ?: Connection(), msElapsed)
        }
        connections.total?.setTransferRate(previous?.total ?: Connection(), msElapsed)
        previousConnections = connections
    }

    /**
     * Returns overall sync completion percentage representing all
     * currently running folder and device transfers.
     * Folder percentage means we are currently pulling changes from remotes.
     * Device percentage means remotes currently pull changes from us.
     * Uses cached stats instead of performing REST queries.
     */
    val totalSyncCompletion: Int
        get() {
            val totalDeviceCompletion = remoteCompletion.getTotalDeviceCompletion()
            if (totalDeviceCompletion == -1) {
                // Total sync completion is not applicable because there are no devices or no devices are connected.
                return -1
            }
            val totalFolderCompletion = localCompletion.getTotalFolderCompletion()
            val totalSyncCompletion = if (totalFolderCompletion == 100) {
                totalDeviceCompletion
            } else {
                floor((totalFolderCompletion + totalDeviceCompletion) / 2.0).toInt()
            }
            return totalSyncCompletion.coerceIn(0, 100)
        }

    /**
     * Requests and parses information about recent changes.
     * Fetches the most recent disk events, the latest first.
     *
     * "timeout=1" is important. /rest/events/disk is a long-poll endpoint: when no disk events are
     * buffered it blocks for the server default of 60s before returning an empty array, which made
     * the Recent changes screen appear to hang on open. The web GUI gets away without this because it
     * only ever calls the endpoint in the background and renders cached results, never blocking the
     * user. We fetch on screen open, so we want a query rather than a subscription.
     *
     * As a bonus this keeps each request well inside ApiRequest's 5s socket timeout, so it is not
     * retried (see the "should not be retried" note in the server's getEvents).
     */
    fun getDiskEvents(limit: Int, listener: OnResultListener1<List<DiskEvent>>) {
        get(GetRequest.URI_EVENTS_DISK, mapOf("limit" to limit.toString(), "timeout" to "1")) { result ->
            try {
                listener.onResult(
                    SyncthingJson.parseToJsonElement(result).jsonArray.reversed()
                        .map { SyncthingJson.decodeFromJsonElement<DiskEvent>(it) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "getDiskEvents: Parsing REST API result failed. result=$result")
            }
        }
    }

    /**
     * Retrieves the events that have accumulated since the given event id.
     *
     * The OnReceiveEventListeners onEvent method is called for each event.
     */
    fun getEvents(sinceId: Long, limit: Long, listener: OnReceiveEventListener) {
        val params = mapOf("since" to sinceId.toString(), "limit" to limit.toString())
        get(GetRequest.URI_EVENTS, params, onError = { listener.onError() }) { result ->
            val jsonEvents: JsonArray = try {
                SyncthingJson.parseToJsonElement(result).jsonArray
            } catch (e: IllegalArgumentException) {
                // Not JSON, or not an array. Same as a failed request: the event processor
                // schedules the next poll.
                Log.w(TAG, "getEvents: Failed to parse the response", e)
                listener.onError()
                return@get
            }
            var lastId = 0L
            for (json in jsonEvents) {
                try {
                    val event = SyncthingJson.decodeFromJsonElement<Event>(json)
                    lastId = maxOf(lastId, event.id.toLong())
                    listener.onEvent(event, json)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "getEvents: Skipping event due to JsonSyntaxException, raw=[$json]")
                }
            }
            listener.onDone(lastId)
        }
    }

    /**
     * Returns status information about the folder with the given id from cache.
     */
    fun getFolderStatus(folderId: String?): Map.Entry<FolderStatus, CachedFolderStatus> {
        val cacheEntry = localCompletion.getFolderStatus(folderId)
        if (cacheEntry.key.stateChanged.isNullOrEmpty()) {
            // Cache miss because we haven't received a "FolderSummary" event yet.
            // Query the required information so it will be available on a future call to this function.
            logV("getFolderStatus: Cache miss, folderId=\"$folderId\". Performing query.")
            get(GetRequest.URI_DB_STATUS, mapOf("folder" to folderId.orEmpty())) { result ->
                val folder = getFolderByID(folderId)
                if (folder == null) {
                    Log.e(TAG, "getFolderStatus#GetRequest#onResult: folderId == null")
                    return@get
                }
                val folderStatus = parseOrNull<FolderStatus>(result, "getFolderStatus") ?: return@get
                localCompletion.setFolderStatus(folderId, folder.paused, folderStatus)
            }
        }
        return cacheEntry
    }

    /**
     * Tells the third-party apps that subscribed, see
     * wiki/developers/Integrate-your-app-with-Syncthing-receive-folder-sync-status.md.
     */
    private fun sendBroadcastFolderSyncComplete(deviceId: String?, folder: Folder, folderState: String) {
        val intent = Intent(context.packageName + ACTION_NOTIFY_FOLDER_SYNC_COMPLETE)
            .putExtra("deviceId", deviceId)
            .putExtra("folderId", folder.id)
            .putExtra("folderLabel", folder.label)
            .putExtra("folderPath", folder.path)
            .putExtra("folderState", folderState)
        for (packageId in SUBSCRIBED_PACKAGES) {
            intent.setPackage(packageId)
            context.applicationContext.sendBroadcast(intent, context.packageName + PERMISSION_RECEIVE_SYNC_STATUS)
        }
    }

    /**
     * Updates cached folder and device completion info according to event data.
     */
    fun setLocalFolderStatus(folderId: String?, folderStatus: FolderStatus) {
        localCompletion.setFolderStatus(folderId, folderStatus)
        onTotalSyncCompletionChange()
    }

    /**
     * Stores the last synced item for the UI. Syncthing reports "update" for a file changed or
     * deleted: a file that no longer exists is shown as deleted.
     */
    fun setLocalFolderLastItemFinished(folderId: String?, action: String?, item: String?, time: String?) {
        var realAction = action
        if (action == "update") {
            val path = getFolderByID(folderId)?.path
            if (path != null && !File("$path/$item").exists()) {
                realAction = "delete"
            }
        }
        localCompletion.setLastItemFinished(folderId, realAction, item, time)
    }

    fun setRemoteCompletionInfo(deviceId: String?, folderId: String?, needBytes: Double?, completion: Double?) {
        val folder = getFolderByID(folderId)
        if (folder == null) {
            Log.e(TAG, "setRemoteCompletionInfo: folderId == null")
            return
        }
        val remoteCompletionInfo = RemoteCompletionInfo()
        if (folder.paused) {
            // A paused folder is reported 0% complete: on the remote device the web UI shows 100%
            // and ours would show 50% with another folder syncing. Paused folders count as 100%.
            logV("setRemoteCompletionInfo: Paused folder \"$folderId\" - got $completion%, passing on 100%")
            remoteCompletionInfo.completion = 100.0
            remoteCompletionInfo.needBytes = 0.0
        } else {
            remoteCompletionInfo.completion = completion ?: return
            remoteCompletionInfo.needBytes = needBytes ?: 0.0
        }
        remoteCompletion.setCompletionInfo(deviceId, folderId, remoteCompletionInfo)
        onTotalSyncCompletionChange()

        // Check if a folder completed synchronization on the local or a remote device, then plan
        // the work to do after completion, on a separate thread.
        if (remoteCompletionInfo.completion != 100.0) {
            return
        }
        val (folderStatus, cachedFolderStatus) = localCompletion.getFolderStatus(folderId)
        if (folderStatus.state.orEmpty().contains("sync")) {
            return
        }
        val planOnFolderSyncCompleted = cachedFolderStatus.remoteIndexUpdated
        if (planOnFolderSyncCompleted) {
            localCompletion.setRemoteIndexUpdated(folderId, false)
        }
        if (hasShutdown) {
            // We are on the way to shutdown SyncthingNative.
            return
        }
        scope.launch(conflictSearch) {
            if (hasShutdown) {
                return@launch
            }
            // Check for ".sync-conflict-YYYYMMDD-HHMMSS-DEVICEI*" files. Shutdown interrupts the search.
            val conflictFiles = runInterruptible { Util.getSyncConflictFiles(folder.path.orEmpty()) }
            localCompletion.setDiscoveredConflictFiles(folderId, conflictFiles)
            if (planOnFolderSyncCompleted) {
                onFolderSyncCompleted(folder, folderStatus.state.orEmpty(), deviceId)
            }
        }
    }

    private fun onFolderSyncCompleted(folder: Folder, folderState: String, deviceId: String?) {
        Log.d(TAG, "onFolderSyncCompleted: Completed folder=[${folder.id}]")

        // Run folder script set if enabled by user pref.
        if (preferences.getBoolean(Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(folder.id), false)) {
            Util.runScriptSet(folder.path + "/" + Constants.FILENAME_STFOLDER, arrayOf("sync_complete"))
        }

        // Notify listening third-party apps.
        sendBroadcastFolderSyncComplete(deviceId, folder, folderState)
    }

    @Suppress("UNUSED_PARAMETER")
    fun setRemoteIndexUpdated(deviceId: String?, folderId: String?, remoteIndexUpdated: Boolean) {
        localCompletion.setRemoteIndexUpdated(folderId, remoteIndexUpdated)
    }

    fun updateLocalFolderPause(folderId: String?, newPaused: Boolean) {
        // Clear status cache when pausing or resuming the folder.
        localCompletion.setFolderStatus(folderId, newPaused, FolderStatus())
    }

    fun updateLocalFolderState(folderId: String?, newState: String?) {
        val folderStatus = localCompletion.getFolderStatus(folderId).key
        folderStatus.state = newState.orEmpty()
        localCompletion.setFolderStatus(folderId, folderStatus)
    }

    fun updateRemoteDeviceConnected(deviceId: String?, newConnected: Boolean) {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId)
        cacheEntry.connected = newConnected
        remoteCompletion.setDeviceStatus(deviceId, cacheEntry)
        onTotalSyncCompletionChange()
    }

    fun updateRemoteDevicePaused(deviceId: String?, newPaused: Boolean) {
        val cacheEntry = remoteCompletion.getDeviceStatus(deviceId)
        cacheEntry.connected = false
        cacheEntry.paused = newPaused
        remoteCompletion.setDeviceStatus(deviceId, cacheEntry)
        onTotalSyncCompletionChange()
    }

    /**
     * Returns prettyfied usage report.
     */
    fun getUsageReport(listener: OnResultListener1<String>) {
        get(GetRequest.URI_REPORT) { result ->
            listener.onResult(PRETTY_JSON.encodeToString(SyncthingJson.parseToJsonElement(result)))
        }
    }

    val isUsageReportingAccepted: Boolean
        get() = urVersionMax?.let { options.isUsageReportingAccepted(it) } ?: false

    val isUsageReportingDecided: Boolean
        get() = urVersionMax?.let { options.isUsageReportingDecided(it) } ?: true

    fun setUsageReporting(acceptUsageReporting: Boolean) {
        val versionMax = urVersionMax
        if (versionMax == null) {
            Log.e(TAG, "setUsageReporting called before the system status was read")
            return
        }
        val options = options
        options.urAccepted = if (acceptUsageReporting) versionMax else Options.USAGE_REPORTING_DENIED
        synchronized(configLock) {
            loadedConfig.options = options
        }
    }

    fun downloadSupportBundle(targetFile: File, listener: OnResultListener1<Boolean>?) {
        get(GetRequest.URI_DEBUG_SUPPORT) { result ->
            logV("downloadSupportBundle: Writing '${targetFile.path}' ...")
            val success = try {
                // ISO-8859-1, as ApiRequest read the response: UTF-8 would corrupt the ZIP.
                targetFile.writeBytes(result.toByteArray(Charsets.ISO_8859_1))
                true
            } catch (e: IOException) {
                Log.w(TAG, "downloadSupportBundle: Failed to write '${targetFile.path}'", e)
                false
            }
            listener?.onResult(success)
        }
    }

    /**
     * Pauses or resumes the folders and devices with their own run conditions, from
     * RunConditionMonitor through SyncthingService.
     */
    fun applyCustomRunConditions(runConditionMonitor: RunConditionMonitor) {
        synchronized(configLock) {
            val config = config
            if (config == null) {
                Log.w(TAG, "applyCustomRunConditions: mConfig is not ready yet.")
                return
            }
            val folders = config.folders
            val devices = config.devices
            if (folders == null || devices == null) {
                Log.d(TAG, "applyCustomRunConditions: folders or devices are not ready yet.")
                return
            }
            var configChanged = false
            for (folder in folders) {
                val prefix = Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id
                if (!preferences.getBoolean(Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(prefix), false)) {
                    continue
                }
                val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(prefix)
                logV("applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) "1" else "0"}")
                if (folder.paused == syncConditionsMet) {
                    folder.paused = !syncConditionsMet
                    Log.d(TAG, "applyCustomRunConditions: f(${folder.label})=${if (syncConditionsMet) ">1" else ">0"}")
                    configChanged = true
                }
            }
            for (device in devices) {
                val prefix = Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID
                if (!preferences.getBoolean(Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(prefix), false)) {
                    continue
                }
                val syncConditionsMet = runConditionMonitor.checkObjectSyncConditions(prefix)
                logV("applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) "1" else "0"}")
                if (device.paused == syncConditionsMet) {
                    device.paused = !syncConditionsMet
                    Log.d(TAG, "applyCustomRunConditions: d(${device.name})=${if (syncConditionsMet) ">1" else ">0"}")
                    configChanged = true
                }
            }
            if (configChanged) {
                logV("applyCustomRunConditions: Sending changed config ...")
                sendConfig()
            } else {
                logV("applyCustomRunConditions: No action was necessary.")
            }
        }
    }

    /**
     * Sets the versioning cleanupIntervalS of the folders listed in intervals (by folder id),
     * then sends the config. Folders no longer in the config are skipped.
     */
    private fun setVersioningCleanupIntervals(intervals: Map<String, Int>) {
        synchronized(configLock) {
            for (folder in loadedConfig.folders.orEmpty()) {
                val interval = intervals[folder.id ?: continue] ?: continue
                folder.versioning?.cleanupIntervalS = interval
            }
            logV("Set versioning cleanup intervals $intervals")
            sendConfig()
        }
    }

    private fun onTotalSyncCompletionChange() {
        val onlineDeviceCount = remoteCompletion.getOnlineDeviceCount()
        val totalSyncCompletion = totalSyncCompletion
        if (onlineDeviceCount == lastOnlineDeviceCount && totalSyncCompletion == lastTotalSyncCompletion) {
            return
        }
        // Do not persist previous notification text.
        notificationHandler.updatePersistentNotification(
            context as SyncthingService, false, onlineDeviceCount, totalSyncCompletion
        )
        lastOnlineDeviceCount = onlineDeviceCount
        lastTotalSyncCompletion = totalSyncCompletion
    }

    /**
     * Sends a GET request. Failures are dropped, as when Syncthing restarts.
     */
    private fun get(
        path: String,
        params: Map<String, String>? = null,
        onError: () -> Unit = {},
        onSuccess: (String) -> Unit,
    ) {
        GetRequest(context, url, path, apiKey, params, { onSuccess(it) }, { onError() })
    }

    /**
     * Sends a POST request. Without [onError], failures are logged.
     */
    private fun post(
        path: String,
        params: Map<String, String>? = null,
        body: String? = null,
        onError: ApiRequest.OnErrorListener? = null,
    ) {
        PostRequest(context, url, path, apiKey, params, body, null, onError)
    }

    private fun logV(message: String) {
        if (verboseLog) {
            Log.v(TAG, message)
        }
    }

    companion object {
        private const val TAG = "RestApi"

        /**
         * Intents we sent to to other apps that subscribed to us.
         * Suffix of the action: the full name is prefixed with the application id, see
         * wiki/developers/Integrate-your-app-with-Syncthing-receive-folder-sync-status.md.
         */
        private const val ACTION_NOTIFY_FOLDER_SYNC_COMPLETE = ".ACTION_NOTIFY_FOLDER_SYNC_COMPLETE"

        /**
         * Permission for apps receiving our broadcast intents.
         * Suffix of the permission declared in AndroidManifest.xml as ${applicationId}.permission...
         */
        private const val PERMISSION_RECEIVE_SYNC_STATUS = ".permission.RECEIVE_SYNC_STATUS"

        /**
         * The apps told when a folder completed.
         */
        private val SUBSCRIBED_PACKAGES = listOf("org.decsync.cc")

        private val PRETTY_JSON = Json { prettyPrint = true }

        /**
         * Compares folders by labels, uses the folder ID as fallback if the label is empty
         */
        private val FOLDERS_COMPARATOR = compareBy<Folder> { it.label.orEmpty().ifEmpty { it.id.orEmpty() } }

        /**
         * Returns [update] copied onto [base], recursively: fields only present in base are kept.
         * Arrays of objects identified by "id" or "deviceID" (folders, devices, shared devices,
         * ignored entries) are merged element by element, in the order of update; elements missing
         * from update are dropped. Other arrays and values are taken from update.
         */
        @JvmStatic
        fun mergeConfigJson(base: JsonObject?, update: JsonObject): JsonObject = mergeJson(base, update) as JsonObject

        private fun mergeJson(base: JsonElement?, update: JsonElement): JsonElement {
            if (base is JsonObject && update is JsonObject) {
                // A key of base keeps its place, a new one comes last.
                return JsonObject(base + update.mapValues { (key, value) -> mergeJson(base[key], value) })
            }
            if (base is JsonArray && update is JsonArray) {
                val idKey = getArrayIdKey(update)
                if (idKey != null) {
                    val baseById = base.filterIsInstance<JsonObject>()
                        .filter { idKey in it }
                        .associateBy { it.getValue(idKey).jsonPrimitive.content }
                    return JsonArray(
                        update.map { element -> mergeJson(baseById[element.jsonObject.getValue(idKey).jsonPrimitive.content], element) }
                    )
                }
            }
            return update
        }

        /**
         * Returns "id" or "deviceID" if every element of the array is an object carrying that key.
         */
        private fun getArrayIdKey(array: JsonArray): String? =
            listOf("id", "deviceID").firstOrNull { key ->
                array.isNotEmpty() && array.all { element ->
                    (element as? JsonObject)?.get(key).let { it is JsonPrimitive && it !is JsonNull }
                }
            }
    }
}
