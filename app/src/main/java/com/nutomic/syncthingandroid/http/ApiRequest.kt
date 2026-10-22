package com.nutomic.syncthingandroid.http

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.nutomic.syncthingandroid.service.Constants
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ApiRequest"
private const val LOOPBACK_HOST = "127.0.0.1"

/**
 * Syncthing answered with an error status.
 */
class HttpException(val statusCode: Int, message: String) : IOException(message)

/**
 * A request to the REST API of the local Syncthing instance. It is sent on a network thread;
 * the listeners run on the main thread.
 */
abstract class ApiRequest internal constructor(
    context: Context,
    url: URL,
    private val path: String,
    private val apiKey: String,
) {
    fun interface OnSuccessListener {
        fun onSuccess(result: String)
    }

    fun interface OnErrorListener {
        fun onError(error: Exception)
    }

    private val httpsCertFile: File = Constants.getHttpsCertFile(context)

    /**
     * The app only ever talks to the local syncthing instance: see [forceLoopbackHost].
     */
    private val url: URL = forceLoopbackHost(url)

    internal fun buildUri(params: Map<String, String>): Uri {
        val builder = url.toString().toUri().buildUpon().path(path)
        params.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build()
    }

    /**
     * Sends the request; [listener] gets the response body, [errorListener] the failure. Without
     * an error listener, failures are logged.
     */
    internal fun connect(
        method: String,
        uri: Uri,
        body: String?,
        listener: OnSuccessListener?,
        errorListener: OnErrorListener?,
    ) {
        scope.launch {
            val result = try {
                withContext(network) { send(method, URL(uri.toString()), body) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (errorListener != null) {
                    errorListener.onError(e)
                } else {
                    Log.w(TAG, "Request to $uri failed, code=${(e as? HttpException)?.statusCode ?: 0}, msg=${e.message}")
                }
                return@launch
            }
            listener?.onSuccess(result)
        }
    }

    /**
     * Some requests are slow, long polls first: a timeout is tried again with twice the time,
     * as Volley's DefaultRetryPolicy did.
     */
    private fun send(method: String, target: URL, body: String?): String {
        var timeout = INITIAL_TIMEOUT_MS
        var retries = 0
        while (true) {
            try {
                return sendOnce(method, target, body, timeout)
            } catch (e: SocketTimeoutException) {
                if (retries == MAX_RETRIES) {
                    throw e
                }
                retries++
                timeout *= 2
            }
        }
    }

    private fun sendOnce(method: String, target: URL, body: String?, timeout: Int): String {
        val connection = target.openConnection() as HttpURLConnection
        try {
            if (connection is HttpsURLConnection) {
                sslSocketFactory(httpsCertFile)?.let { connection.sslSocketFactory = it }
                // Hostname verification only checks the loopback host: the connection is pinned to
                // it (see forceLoopbackHost), so there is no network MITM surface, and the
                // certificate's SAN/CN need not match 127.0.0.1 (a user-supplied CA cert is
                // typically issued for a real hostname). Trust is still enforced by
                // SyncthingTrustManager: the self-signed pin first, then the OS trust store.
                connection.hostnameVerifier = HostnameVerifier { hostname, _ -> hostname == LOOPBACK_HOST }
            }
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.useCaches = false
            connection.requestMethod = method
            connection.setRequestProperty(HEADER_API_KEY, apiKey)
            if (method == METHOD_POST) {
                val bytes = body.orEmpty().toByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val success = status in 200..299
            val stream = if (success) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readBytes(), charsetOf(connection.contentType)) }.orEmpty()
            if (!success) {
                throw HttpException(status, "HTTP $status: $text")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /**
         * The name of the HTTP header used for the syncthing API key.
         */
        private const val HEADER_API_KEY = "X-API-Key"

        private const val INITIAL_TIMEOUT_MS = 5000
        private const val MAX_RETRIES = 5

        internal const val METHOD_GET = "GET"
        internal const val METHOD_POST = "POST"

        /**
         * As many requests at once as Volley had network threads: a burst of requests, e.g. one
         * completion query per folder and device, waits its turn.
         */
        private val network = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        @Volatile
        private var socketFactory: SSLSocketFactory? = null

        /**
         * Built once: SyncthingTrustManager reads the pinned certificate at each handshake.
         */
        private fun sslSocketFactory(httpsCertFile: File): SSLSocketFactory? {
            socketFactory?.let { return it }
            return try {
                SSLContext.getInstance("TLS")
                    .apply { init(null, arrayOf(SyncthingTrustManager(httpsCertFile)), SecureRandom()) }
                    .socketFactory
                    .also { socketFactory = it }
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, e)
                null
            }
        }
    }
}

/**
 * Rewrites the host of the given URL to 127.0.0.1, preserving the scheme and port. The port
 * comes from the configured GUI listen address; falls back to the default web GUI port.
 *
 * Forcing loopback is intentional and security-relevant, not merely a convenience:
 * - The app only ever sets the GUI address to 127.0.0.1 or 0.0.0.0 (the "listen on all
 *   interfaces" setting). 0.0.0.0 always includes loopback, so the local instance is
 *   reachable on 127.0.0.1 in every app-managed config.
 * - It keeps the API key and configuration off any routable interface.
 * - It is the precondition that makes the two TLS relaxations safe: the hostname check
 *   limited to the loopback host and the fallback to the OS trust store / user-installed
 *   CAs (SyncthingTrustManager). On loopback there is no network position for a MITM to
 *   occupy, so neither relaxation can be abused.
 *
 * Do not "simplify" this by connecting to the configured address directly: 0.0.0.0 is not
 * a valid destination (and modern WebView blocks it), and targeting a routable address
 * would break the trust model above.
 */
internal fun forceLoopbackHost(url: URL): URL = try {
    val port = if (url.port != -1) url.port else Constants.DEFAULT_WEBGUI_TCP_PORT
    URL(url.protocol, LOOPBACK_HOST, port, url.file)
} catch (e: MalformedURLException) {
    Log.w(TAG, "forceLoopbackHost: Failed to rewrite host, using original URL", e)
    url
}

/**
 * The charset of the Content-Type, else UTF-8 for JSON and ISO-8859-1 otherwise, as
 * Volley read responses.
 */
internal fun charsetOf(contentType: String?): Charset {
    val declared = contentType.orEmpty()
        .split(';')
        .drop(1)
        .map { it.trim() }
        .firstOrNull { it.startsWith("charset=") }
        ?.substringAfter('=')
    if (declared != null) {
        return try {
            Charset.forName(declared)
        } catch (e: IllegalArgumentException) {
            Charsets.ISO_8859_1
        }
    }
    val isJson = contentType?.lowercase(Locale.US)?.startsWith("application/json") == true
    return if (isJson) Charsets.UTF_8 else Charsets.ISO_8859_1
}
