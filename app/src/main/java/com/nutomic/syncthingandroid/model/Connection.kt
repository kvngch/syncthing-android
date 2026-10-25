package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class Connection {
    @JvmField var address: String = ""
    @JvmField var at: String = "" // "0001-01-01T00:00:00Z"
    @JvmField var clientVersion: String = ""
    @JvmField var connected: Boolean = false
    @JvmField var inBytesTotal: Long = 0
    @JvmField var outBytesTotal: Long = 0
    @JvmField var paused: Boolean = false
    @JvmField var type: String = ""

    // These fields are not sent from Syncthing. They are populated by setTransferRate.
    @JvmField var inBits: Long = 0
    @JvmField var outBits: Long = 0

    fun setTransferRate(previous: Connection, msElapsed: Long) {
        inBits = maxOf(0, 8 * (inBytesTotal - previous.inBytesTotal) * 1000 / msElapsed)
        outBits = maxOf(0, 8 * (outBytesTotal - previous.outBytesTotal) * 1000 / msElapsed)
    }
}
