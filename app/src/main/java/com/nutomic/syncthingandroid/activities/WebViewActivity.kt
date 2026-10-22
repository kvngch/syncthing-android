package com.nutomic.syncthingandroid.activities

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.Util
import com.nutomic.syncthingandroid.webgui.WebGuiScreen

/**
 * Shows a web page inside the app, e.g. Syncthing's usage statistics.
 */
class WebViewActivity : ThemedAppCompatActivity() {

    /**
     * A certificate error waiting for the user's answer, with the host it concerns.
     */
    private class PendingSslError(val handler: SslErrorHandler, val host: String?)

    private lateinit var url: String
    private var webView: WebView? = null
    private var loading by mutableStateOf(true)
    private var pendingSslError by mutableStateOf<PendingSslError?>(null)

    /**
     * Host whose certificate error the user accepted; errors for any other host ask again.
     */
    private var acceptedSslHost: String? = null

    private val webViewClient = object : WebViewClient() {
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val host = error.url.toUri().host
            if (host != null && host == acceptedSslHost) {
                handler.proceed()
                return
            }
            pendingSslError = PendingSslError(handler, host)
        }

        override fun onPageFinished(view: WebView, url: String) {
            loading = false
        }

        // Without this override, a renderer killed by the system takes the whole process down,
        // SyncthingService included. Leave the screen instead; onDestroy destroys the WebView.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "WebView renderer gone, crashed=${detail.didCrash()}")
            finish()
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        url = intent.getStringExtra(EXTRA_WEB_URL) ?: run {
            Log.e(TAG, "EXTRA_WEB_URL not set.")
            finish()
            return
        }
        val isTelevision = Util.isRunningOnTV(this)
        onBackPressedDispatcher.addCallback(this) {
            val current = webView
            if (current != null && current.canGoBack()) current.goBack() else finish()
        }

        setContent {
            ApplicationTheme {
                WebGuiScreen(
                    title = url.toUri().host ?: url,
                    loadingText = stringResource(R.string.web_page_loading, url),
                    loading = loading,
                    error = null,
                    onNavigateBack = ::finish,
                    webViewFactory = { context -> createWebView(context).also { webView = it } },
                    actions = {
                        // A TV has no browser to hand the page to.
                        if (!isTelevision) {
                            IconButton(onClick = ::openInBrowser) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.open_in_browser),
                                )
                            }
                        }
                    },
                )
                pendingSslError?.let { pending ->
                    AlertDialog(
                        // Back or a tap outside: the request must not stay suspended.
                        onDismissRequest = { answerSslError(pending, accept = false, isTelevision) },
                        title = { Text(stringResource(R.string.security_notice)) },
                        text = { Text(stringResource(R.string.ssl_cert_invalid_text, url)) },
                        confirmButton = {
                            TextButton(onClick = { answerSslError(pending, accept = true, isTelevision) }) {
                                Text(stringResource(R.string.cont))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { answerSslError(pending, accept = false, isTelevision) }) {
                                Text(stringResource(R.string.cancel_title))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        // The request would stay suspended with its dialog gone.
        pendingSslError?.handler?.cancel()
        pendingSslError = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // A web page has no business with local files, as in WebGuiActivity.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = this@WebViewActivity.webViewClient
        clearCache(true)
        // Inside apply, a bare url would be WebView.getUrl().
        loadUrl(this@WebViewActivity.url)
    }

    private fun answerSslError(pending: PendingSslError, accept: Boolean, isTelevision: Boolean) {
        pendingSslError = null
        if (accept) {
            acceptedSslHost = pending.host
            pending.handler.proceed()
            return
        }
        pending.handler.cancel()
        if (isTelevision) {
            // There is no other way to display the website.
            finish()
        }
    }

    private fun openInBrowser() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open_link, Toast.LENGTH_SHORT).show()
            return
        }
        finish()
    }

    companion object {
        private const val TAG = "WebViewActivity"

        const val EXTRA_WEB_URL = ".activities.WebViewActivity.WEB_URL"
    }
}
