package com.nutomic.syncthingandroid.model

class RemoteIgnoredDevice {
    @JvmField var time: String = ""
    @JvmField var deviceID: String = ""
    @JvmField var name: String = ""
    @JvmField var address: String = ""

    /**
     * The device name, or the first characters of the ID if the name is empty.
     */
    val displayName: String
        get() = if (name.isNullOrEmpty()) deviceID.take(7) else name
}
