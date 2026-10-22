package com.nutomic.syncthingandroid.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Between the model init assignment and a finished sync, completion reads 100 % in both cases:
 * above this rate, the device is shown as syncing rather than up to date.
 */
private const val ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD = 50L * 1024 * 8

private const val TIMESTAMP_NEVER_SEEN = "1970-01-01T00:00:00Z"

private const val TAG = "DevicesTab"

/**
 * A device, the folders shared with it and, while Syncthing runs, its connection.
 */
internal class DeviceRow(
    val device: Device,
    val lastSeen: String?,
    val sharedFolders: List<Folder>,
    val connection: Connection?,
    val completion: Int,
    val needBytes: Double,
)

/**
 * Null if config.xml could not be read: the list keeps its last state.
 */
private suspend fun readDevices(
    context: Context,
    api: RestApi?,
    serviceState: SyncthingService.State,
    preferences: SharedPreferences,
): List<DeviceRow>? {
    val loaded = api != null && api.isConfigLoaded
    val read = {
        val router = ConfigRouter(context)
        val folders = router.getFolders(api)
        if (loaded && serviceState == SyncthingService.State.ACTIVE) {
            // Force a cache miss to query the status of all devices asynchronously.
            api!!.getRemoteDeviceStatus("")
        }
        router.getDevices(api, false)
            .sortedBy { if (it.name.isNullOrEmpty()) it.deviceID else it.name }
            .map { device ->
                DeviceRow(
                    device = device,
                    lastSeen = preferences.getString(Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + device.deviceID, ""),
                    sharedFolders = folders.filter { it.getDevice(device.deviceID) != null },
                    connection = if (loaded) api!!.getRemoteDeviceStatus(device.deviceID) else null,
                    completion = if (loaded) api!!.getRemoteDeviceCompletion(device.deviceID) else 0,
                    needBytes = if (loaded) api!!.getRemoteDeviceNeedBytes(device.deviceID) else 0.0,
                )
            }
    }
    if (loaded) {
        return read()
    }
    // Syncthing is not running: read config.xml, off the main thread.
    return withContext(Dispatchers.IO) {
        try {
            read()
        } catch (e: ConfigXml.OpenConfigException) {
            Log.e(TAG, "Failed to parse existing config. You will need support from here ...")
            null
        }
    }
}

@Composable
internal fun DevicesTab(api: RestApi?, serviceState: SyncthingService.State, preferences: SharedPreferences) {
    val context = LocalContext.current
    val rows = rememberPolled(api, serviceState, intervalMs = Constants.GUI_UPDATE_INTERVAL) {
        readDevices(context, api, serviceState, preferences)
    }
    when {
        rows == null -> LoadingList()
        rows.isEmpty() -> EmptyList(R.string.no_devices_configured)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.device.deviceID }) { row ->
                DeviceItem(row, api) {
                    context.startActivity(
                        Intent(context, DeviceActivity::class.java)
                            .putExtra(DeviceActivity.EXTRA_IS_CREATE, false)
                            .putExtra(DeviceActivity.EXTRA_DEVICE_ID, row.device.deviceID)
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun DeviceItem(row: DeviceRow, api: RestApi?, onClick: () -> Unit) {
    val context = LocalContext.current
    val device = row.device
    val connection = row.connection
    val (statusText, statusColor) = deviceStatusText(row, api)
    val syncing = !device.paused && connection != null && connection.connected && row.completion != 100

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Devices, contentDescription = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(statusText, color = statusColor, style = MaterialTheme.typography.labelLarge)
            }
            if (syncing) {
                LinearProgressIndicator(
                    progress = { row.completion / 100f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
            Text(
                stringResource(R.string.device_last_seen, lastSeenText(row.lastSeen)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.sharedFolders.isEmpty()) {
                Text(stringResource(R.string.device_state_unused), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    stringResource(R.string.shared_folders_title_colon) +
                        "\n• " + row.sharedFolders.joinToString("\n• ") { it.toString() },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!device.paused && connection != null && connection.connected) {
                Text(
                    "⇵ " + stringResource(R.string.download_title) +
                        " ˅ " + Util.readableTransferRate(context, connection.inBits) +
                        " • " + stringResource(R.string.upload_title) +
                        " ˄ " + Util.readableTransferRate(context, connection.outBits),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun lastSeenText(lastSeen: String?): String {
    if (lastSeen.isNullOrEmpty() || lastSeen == TIMESTAMP_NEVER_SEEN) {
        return stringResource(R.string.device_last_seen_never)
    }
    return try {
        Util.formatDateTime(lastSeen)
    } catch (e: RuntimeException) {
        // Not a timestamp java.time can read: show it as stored.
        lastSeen
    }
}

@Composable
private fun deviceStatusText(row: DeviceRow, api: RestApi?): Pair<String, Color> {
    val context = LocalContext.current
    val red = colorResource(R.color.text_red)
    val blue = colorResource(R.color.text_blue)
    val connection = row.connection
    return when {
        row.device.paused -> stringResource(R.string.device_paused) to colorResource(R.color.text_purple)
        // Syncthing is not running.
        api == null || !api.isConfigLoaded || connection == null ->
            stringResource(R.string.device_disconnected) to red
        connection.connected && row.completion == 100 ->
            if (connection.inBits + connection.outBits >= ACTIVE_SYNC_BITS_PER_SECOND_THRESHOLD) {
                stringResource(R.string.state_syncing_general) to blue
            } else {
                stringResource(R.string.device_up_to_date) to colorResource(R.color.text_green)
            }
        connection.connected -> stringResource(
            R.string.device_syncing_percent_bytes,
            row.completion,
            Util.readableFileSize(context, row.needBytes),
        ) to blue
        row.needBytes == 0.0 -> stringResource(R.string.device_disconnected) to red
        else -> stringResource(
            R.string.device_disconnected_not_synced,
            Util.readableFileSize(context, row.needBytes),
        ) to red
    }
}
