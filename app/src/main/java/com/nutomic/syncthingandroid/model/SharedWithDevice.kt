package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class SharedWithDevice {
    @JvmField var deviceID: String? = null
    @JvmField var introducedBy: String = ""

    // Since v1.12.0
    // See https://github.com/syncthing/syncthing/pull/7055
    @JvmField var encryptionPassword: String = ""

    /**
     * The first characters of the device ID.
     */
    val displayName: String
        get() = deviceID.orEmpty().take(7)
}
