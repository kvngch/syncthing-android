package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

@Serializable
class Connections {
    @JvmField var total: Connection? = null
    @JvmField var connections: MutableMap<String, Connection>? = null
}
