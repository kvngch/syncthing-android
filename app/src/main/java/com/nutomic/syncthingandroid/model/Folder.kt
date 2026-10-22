package com.nutomic.syncthingandroid.model

import com.nutomic.syncthingandroid.service.Constants
import kotlinx.serialization.Serializable

/**
 * Sources:
 * - https://github.com/syncthing/syncthing/tree/master/lib/config
 * - https://github.com/syncthing/syncthing/blob/master/lib/config/folderconfiguration.go
 */
@Serializable
class Folder {
    // Folder Configuration
    @JvmField var group: String = ""
    @JvmField var id: String? = null
    @JvmField var label: String = ""
    @JvmField var filesystemType: String = "basic"
    @JvmField var path: String? = null
    @JvmField var type: String = Constants.FOLDER_TYPE_SEND_RECEIVE
    @JvmField var fsWatcherEnabled: Boolean = true
    @JvmField var fsWatcherDelayS: Float = 10f

    // Null when Syncthing sends an empty list.
    private var devices: MutableList<SharedWithDevice>? = ArrayList()

    /**
     * Folder rescan interval defaults to 3600s as it is the default in
     * syncthing when the file watcher is enabled and a new folder is created.
     */
    @JvmField var rescanIntervalS: Int = 3600
    @JvmField var ignorePerms: Boolean = true
    @JvmField var autoNormalize: Boolean = true
    @JvmField var minDiskFree: MinDiskFree? = null
    @JvmField var versioning: Versioning? = null
    @JvmField var copiers: Int = 0
    @JvmField var pullerMaxPendingKiB: Int = 0
    @JvmField var hashers: Int = 0
    @JvmField var order: String = "random"
    @JvmField var ignoreDelete: Boolean = false
    @JvmField var scanProgressIntervalS: Int = 0
    @JvmField var pullerPauseS: Int = 0
    @JvmField var maxConflicts: Int = 10
    @JvmField var disableSparseFiles: Boolean = false
    @JvmField var paused: Boolean = false
    @JvmField var markerName: String = Constants.FILENAME_STFOLDER

    // Since v1.1.0
    @JvmField var copyOwnershipFromParent: Boolean? = false

    // Since v1.2.1, see PR #5852
    @JvmField var modTimeWindowS: Int = 0

    // Since v1.6.0
    // see PR #6587: "inorder", "random", "standard"
    @JvmField var blockPullOrder: String = "standard"

    // see PR #6588
    @JvmField var disableFsync: Boolean? = false

    // see PR #6573, #10200
    @JvmField var maxConcurrentWrites: Int = 0

    // Since v1.8.0
    // see PR #6746: "all", "copy_file_range", "duplicate_extents", "ioctl", "sendfile", "standard"
    @JvmField var copyRangeMethod: String = "standard"

    // Since v1.9.0
    // see https://github.com/syncthing/syncthing/commit/932d8c69de9e34824ecc4d5de0a482dfdb71936e
    @JvmField var caseSensitiveFS: Boolean? = false

    // Since v1.21.0
    @JvmField var syncOwnership: Boolean? = false
    @JvmField var sendOwnership: Boolean? = false

    // Since v1.22.0
    @JvmField var syncXattrs: Boolean? = false
    @JvmField var sendXattrs: Boolean? = false

    // Since v2.1.0
    @JvmField var blockIndexing: Boolean? = true

    // Folder Status
    @JvmField var invalid: String? = null

    @Serializable
    class Versioning {
        @JvmField var type: String? = null
        @JvmField var cleanupIntervalS: Int = 0
        @JvmField var params: MutableMap<String, String> = HashMap()

        // Since v1.14.0
        @JvmField var fsPath: String? = null
        @JvmField var fsType: String? = null // default: "basic"
    }

    @Serializable
    class MinDiskFree {
        @JvmField var value: Float = 1f
        @JvmField var unit: String = "%"
    }

    fun addDevice(device: Device) {
        // Avoid ConfigXml.updateDevice creating two list entries with the same device ID.
        removeDevice(device.deviceID)
        sharedDevices().add(
            SharedWithDevice().apply {
                deviceID = device.deviceID
                introducedBy = device.introducedBy
            }
        )
    }

    fun addDevice(sharedWithDevice: SharedWithDevice) {
        // Avoid ConfigXml.updateDevice creating two list entries with the same device ID.
        removeDevice(sharedWithDevice.deviceID)
        sharedDevices().add(
            SharedWithDevice().apply {
                deviceID = sharedWithDevice.deviceID
                encryptionPassword = sharedWithDevice.encryptionPassword
                introducedBy = sharedWithDevice.introducedBy
            }
        )
    }

    val sharedWithDevices: List<SharedWithDevice>
        get() = devices.orEmpty()

    /**
     * Note: This is expected to return "1" if a folder is not shared with any
     * other device. Syncthing's config will list ourself as the only device
     * sub node which is associated with the folder. This will return >= 2
     * if the folder is shared with other devices.
     */
    val deviceCount: Int
        get() = devices?.size ?: 1

    fun getDevice(deviceId: String?): SharedWithDevice? = devices?.firstOrNull { it.deviceID == deviceId }

    fun removeDevice(deviceId: String?) {
        devices?.removeAll { it.deviceID == deviceId }
    }

    private fun sharedDevices(): MutableList<SharedWithDevice> =
        devices ?: ArrayList<SharedWithDevice>().also { devices = it }

    override fun toString(): String = if (label.isNullOrEmpty()) id.orEmpty() else label
}
