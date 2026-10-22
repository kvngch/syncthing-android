package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class PendingDevice {
    @JvmField var time: String = ""
    @JvmField var name: String = ""
    @JvmField var address: String = ""
}
