package com.nutomic.syncthingandroid.http

import android.content.Context
import java.net.URL

/**
 * Performs a GET request to the Syncthing API.
 */
class GetRequest(
    context: Context,
    url: URL,
    path: String,
    apiKey: String,
    params: Map<String, String>?,
    onSuccess: OnSuccessListener?,
    onError: OnErrorListener?,
) : ApiRequest(context, url, path, apiKey) {

    init {
        connect(ApiRequest.METHOD_GET, buildUri(params.orEmpty()), null, onSuccess, onError)
    }

    companion object {
        const val URI_CONFIG = "/rest/system/config"
        const val URI_SYSTEM_DISCOVERY = "/rest/system/discovery"
        const val URI_SYSTEM_LOGLEVELS = "/rest/system/loglevels"
        const val URI_VERSION = "/rest/system/version"
        const val URI_SYSTEM_STATUS = "/rest/system/status"
        const val URI_CONNECTIONS = "/rest/system/connections"
        const val URI_PENDING_DEVICES = "/rest/cluster/pending/devices"
        const val URI_PENDING_FOLDERS = "/rest/cluster/pending/folders"
        const val URI_DEBUG_SUPPORT = "/rest/debug/support"
        const val URI_DB_COMPLETION = "/rest/db/completion"
        const val URI_DB_IGNORES = "/rest/db/ignores"
        const val URI_DB_STATUS = "/rest/db/status"
        const val URI_REPORT = "/rest/svc/report"
        const val URI_EVENTS = "/rest/events"
        const val URI_EVENTS_DISK = "/rest/events/disk"
        const val URI_STATS_DEVICE = "/rest/stats/device"
    }
}
