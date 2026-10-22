package com.nutomic.syncthingandroid.model

/**
 * REST API endpoint "/rest/system/status"
 * Part of JSON answer in field [SystemStatus.connectionServiceStatus]
 */
class SystemStatusConnectionServiceStatusElement {
    @JvmField var error: String? = null
    @JvmField var lanAddresses: MutableList<String>? = null
    @JvmField var wanAddresses: MutableList<String>? = null
}
