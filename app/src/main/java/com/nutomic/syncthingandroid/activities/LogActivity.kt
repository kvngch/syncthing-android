package com.nutomic.syncthingandroid.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.log.cleanAndroidLog
import com.nutomic.syncthingandroid.log.readAndroidLog
import com.nutomic.syncthingandroid.log.readLogFileTail
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.LogMask
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the Android log or the Syncthing log, and shares a masked copy of either.
 */
class LogActivity : ThemedAppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ApplicationTheme {
                LogScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LogScreen() {
        // The Android log by default.
        var showSyncthingLog by rememberSaveable { mutableStateOf(false) }
        val log by produceState<String?>(null, showSyncthingLog) {
            value = null
            value = withContext(Dispatchers.IO) { readLog(showSyncthingLog) }
        }
        val scroll = rememberScrollState()
        // The end of the log is the most recent: show it once laid out.
        LaunchedEffect(scroll) {
            snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(stringResource(if (showSyncthingLog) R.string.syncthing_log_title else R.string.android_log_title))
                    },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSyncthingLog = !showSyncthingLog }) {
                            Icon(
                                Icons.Outlined.SwapHoriz,
                                contentDescription = stringResource(
                                    if (showSyncthingLog) R.string.view_android_log else R.string.view_syncthing_log
                                ),
                            )
                        }
                        IconButton(
                            onClick = {
                                share(
                                    if (showSyncthingLog) {
                                        Constants.getSyncthingLogFile(this@LogActivity)
                                    } else {
                                        Constants.getAndroidLogFile(this@LogActivity)
                                    }
                                )
                            },
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share_log_file))
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(scroll)
            ) {
                SelectionContainer {
                    Text(
                        log ?: stringResource(R.string.retrieving_logs),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }

    /**
     * The Android log is also written to a file, for sharing.
     */
    private fun readLog(syncthing: Boolean): String {
        if (syncthing) {
            return try {
                readLogFileTail(Constants.getSyncthingLogFile(this))
            } catch (e: IOException) {
                Log.e(TAG, "Failed to read the Syncthing log", e)
                ""
            }
        }
        val log = try {
            cleanAndroidLog(readAndroidLog())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read the Android log", e)
            return ""
        }
        try {
            Constants.getAndroidLogFile(this).writeText(log)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write the Android log file", e)
        }
        return log
    }

    /**
     * Shared logs often end up in public issues: shares a copy with peer addresses and full
     * device IDs masked, written off the main thread as the log can be large.
     */
    private fun share(logFile: File) {
        if (!logFile.exists()) {
            Toast.makeText(this, R.string.share_log_file_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val maskedLog = File(cacheDir, logFile.name)
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) {
                try {
                    LogMask.maskFile(logFile, maskedLog)
                    true
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to write '$maskedLog'", e)
                    false
                }
            }
            if (!written) {
                Toast.makeText(this@LogActivity, R.string.share_log_file_missing, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@LogActivity, "$packageName.provider", maskedLog)
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.share_log_file)))
        }
    }

    companion object {
        private const val TAG = "LogActivity"
    }
}
