package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class PendingFolder {
    @JvmField var label: String = ""
    @JvmField var time: String = ""
    @JvmField var receiveEncrypted: Boolean? = false
    @JvmField var remoteEncrypted: Boolean? = false
}
