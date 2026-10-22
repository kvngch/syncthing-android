package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
class Event {
    @JvmField var id: Int = 0
    @JvmField var globalID: Int = 0
    @JvmField var type: String? = null
    @JvmField var time: String? = null
    @JvmField var data: JsonObject? = null
}
