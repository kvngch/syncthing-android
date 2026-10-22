package com.nutomic.syncthingandroid.model

import android.util.Log
import com.google.gson.Gson
import kotlin.math.floor

/**
 * Caches the remote completion of [RemoteCompletionInfo] per device and folder, from Syncthing's
 * "/rest/db/completion" results, with each device's connection. Completion model of Syncthing's
 * web UI is completion[deviceId][folderId].
 */
class RemoteCompletion(private val verboseLog: Boolean) {

    private class DeviceCompletion(var connection: Connection = Connection()) {
        val folders = HashMap<String, RemoteCompletionInfo>()
    }

    private val gson = Gson()
    private val devices = HashMap<String, DeviceCompletion>()

    /**
     * Drops the devices and folders removed from the config, adds the new ones.
     */
    @Synchronized
    fun updateFromConfig(newDevices: List<Device>, newFolders: List<Folder>) {
        val deviceIds = newDevices.mapNotNull { it.deviceID }
        devices.keys.filter { it !in deviceIds }.forEach {
            logV("updateFromConfig: Remove device '${shortened(it)}' from cache model")
            devices.remove(it)
        }
        deviceIds.filter { it !in devices }.forEach {
            logV("updateFromConfig: Add device '${shortened(it)}' to cache model")
            devices[it] = DeviceCompletion()
        }
        val folderIds = newFolders.mapNotNull { it.id }
        devices.values.forEach { device ->
            device.folders.keys.filter { it !in folderIds }.forEach {
                logV("updateFromConfig: Remove folder '$it' from cache model")
                device.folders.remove(it)
            }
        }
        for (folder in newFolders) {
            val folderId = folder.id ?: continue
            for (deviceId in deviceIds) {
                val folders = devices[deviceId]?.folders ?: continue
                if (folder.getDevice(deviceId) != null && folderId !in folders) {
                    logV("updateFromConfig: Add folder '$folderId' shared with device '${shortened(deviceId)}' to cache model.")
                    folders[folderId] = RemoteCompletionInfo()
                }
            }
        }
    }

    /**
     * Remote completion across the connected devices, -1 if none is connected.
     */
    @Synchronized
    fun getTotalDeviceCompletion(): Int {
        val connected = devices.values.filter { it.connection.connected }
        if (connected.isEmpty()) {
            return -1
        }
        return averageCompletion(connected.flatMap { it.folders.values })
    }

    /**
     * Remote completion across the folders shared with the device.
     */
    @Synchronized
    fun getDeviceCompletion(deviceId: String?): Int {
        val device = deviceId?.let(devices::get)
        if (device == null) {
            logV("getDeviceCompletion: Cache miss for deviceId=[$deviceId]")
            return 100
        }
        return averageCompletion(device.folders.values)
    }

    @Synchronized
    fun getDeviceNeedBytes(deviceId: String?): Double {
        val device = deviceId?.let(devices::get)
        if (device == null) {
            logV("getDeviceNeedBytes: Cache miss for deviceId=[$deviceId]")
            return 0.0
        }
        return device.folders.values.sumOf { it.needBytes }
    }

    /**
     * Sets completionInfo within the completion[deviceId][folderId] model.
     */
    @Synchronized
    fun setCompletionInfo(deviceId: String?, folderId: String?, completionInfo: RemoteCompletionInfo) {
        if (deviceId == null || folderId == null) {
            return
        }
        logV(
            "setCompletionInfo: Storing ${completionInfo.completion}% for folder \"$folderId\" " +
                "at device \"${shortened(deviceId)}\"."
        )
        devices.getOrPut(deviceId) { DeviceCompletion() }.folders[folderId] = completionInfo
    }

    /**
     * A copy of the device's connection, an empty one on a cache miss.
     */
    @Synchronized
    fun getDeviceStatus(deviceId: String?): Connection =
        deviceId?.let(devices::get)?.connection?.let(::copyOf) ?: Connection()

    @Synchronized
    fun getOnlineDeviceCount(): Int = devices.values.count { it.connection.connected }

    /**
     * Stores a copy of the device's connection for the UI.
     */
    @Synchronized
    fun setDeviceStatus(deviceId: String?, connection: Connection?) {
        if (deviceId == null || connection == null) {
            return
        }
        devices.getOrPut(deviceId) { DeviceCompletion() }.connection = copyOf(connection)
    }

    /**
     * The average of the folders still syncing, 100 if none is. Syncthing's web UI considers
     * remote folders at 0% or 100% up to date.
     */
    private fun averageCompletion(folders: Collection<RemoteCompletionInfo>): Int {
        val syncing = folders.map { it.completion.coerceIn(0.0, 100.0) }.filter { it != 0.0 && it != 100.0 }
        if (syncing.isEmpty()) {
            return 100
        }
        return floor(syncing.sum() / syncing.size).toInt().coerceIn(0, 100)
    }

    private fun copyOf(connection: Connection): Connection =
        gson.fromJson(gson.toJson(connection), Connection::class.java)

    /**
     * The first characters of the device ID, for logging.
     */
    private fun shortened(deviceId: String) = deviceId.take(7)

    private fun logV(message: String) {
        if (verboseLog) {
            Log.v(TAG, message)
        }
    }

    private companion object {
        const val TAG = "RemoteCompletion"
    }
}
