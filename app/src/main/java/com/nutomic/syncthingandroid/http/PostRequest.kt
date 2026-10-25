package com.nutomic.syncthingandroid.http

import android.content.Context
import java.net.URL

/**
 * Performs a POST request to the Syncthing API, with a JSON body or none.
 */
class PostRequest @JvmOverloads constructor(
    context: Context,
    url: URL,
    path: String,
    apiKey: String,
    params: Map<String, String>?,
    postBody: String?,
    listener: OnSuccessListener?,
    errorListener: OnErrorListener? = null,
) : ApiRequest(context, url, path, apiKey) {

    init {
        connect(ApiRequest.METHOD_POST, buildUri(params.orEmpty()), postBody, listener, errorListener)
    }

    companion object {
        const val URI_DB_IGNORES = "/rest/db/ignores"
        const val URI_DB_OVERRIDE = "/rest/db/override"
        const val URI_DB_REVERT = "/rest/db/revert"
        const val URI_DB_SCAN = "/rest/db/scan"
        const val URI_SYSTEM_CONFIG = "/rest/system/config"
    }
}
