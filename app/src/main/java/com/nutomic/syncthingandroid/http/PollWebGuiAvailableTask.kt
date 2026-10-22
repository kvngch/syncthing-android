package com.nutomic.syncthingandroid.http

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Polls the web interface until it answers, then calls the listener once.
 */
class PollWebGuiAvailableTask(
    context: Context,
    url: URL,
    apiKey: String,
    listener: OnSuccessListener,
) : ApiRequest(context, url, "", apiKey) {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: OnSuccessListener? = listener

    private var logIncidence = 0

    init {
        Log.i(TAG, "Starting to poll for web gui availability")
        performRequest()
    }

    fun cancelRequestsAndCallback() {
        listener = null
    }

    private fun performRequest() {
        connect(ApiRequest.METHOD_GET, buildUri(emptyMap()), null, ::onSuccess, ::onError)
    }

    private fun onSuccess(result: String) {
        val current = listener
        if (current == null) {
            Log.v(TAG, "Cancelled callback and outstanding requests")
            return
        }
        current.onSuccess(result)
    }

    private fun onError(error: Exception) {
        if (listener == null) {
            Log.v(TAG, "Cancelled callback and outstanding requests")
            return
        }
        handler.postDelayed(::performRequest, WEB_GUI_POLL_INTERVAL)
        // Refused, timed out or not ready yet: expected while Syncthing starts.
        if (error is ConnectException || error is SocketTimeoutException || error is HttpException) {
            // Reduce lag caused by massively logging the same line while waiting.
            logIncidence++
            if (logIncidence == 1 || logIncidence % 10 == 0) {
                Log.v(TAG, "Polling web gui ... ($logIncidence)")
            }
        } else {
            Log.w(TAG, "Unexpected error while polling web gui", error)
        }
    }

    private companion object {
        const val TAG = "PollWebGuiAvailableTask"

        /**
         * Interval in ms, at which connections to the web gui are performed on first start
         * to find out if it's online.
         */
        const val WEB_GUI_POLL_INTERVAL = 150L
    }
}
