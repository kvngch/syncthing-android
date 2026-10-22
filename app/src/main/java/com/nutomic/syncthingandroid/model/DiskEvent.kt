package com.nutomic.syncthingandroid.model

import kotlinx.serialization.Serializable

/**
 * REST API endpoint "/rest/events/disk"
 */
@Serializable
class DiskEvent {
    @JvmField var id: Long = 0
    @JvmField var globalID: Long = 0
    @JvmField var time: String = ""

    // type = {"LocalChangeDetected", "RemoteChangeDetected"}
    @JvmField var type: String = ""
    @JvmField var data: DiskEventData = DiskEventData()
}
