package com.nutomic.syncthingandroid.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.SystemStatus
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.Util
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@Composable
internal fun StatusTab(
    service: SyncthingService?,
    serviceState: SyncthingService.State,
    preferences: SharedPreferences,
) {
    val context = LocalContext.current
    val api = service?.api
    val lifecycleOwner = LocalLifecycleOwner.current
    var systemStatus by remember { mutableStateOf<SystemStatus?>(null) }
    var totalConnection by remember { mutableStateOf<Connection?>(null) }
    // Bumped by the polling loop, so that the run decision explanation is read again.
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(api, serviceState, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                if (serviceState == SyncthingService.State.ACTIVE && api != null) {
                    // Force a cache miss to query the status of all devices asynchronously.
                    api.getRemoteDeviceStatus("")
                    api.getSystemStatus { status ->
                        systemStatus = status
                        totalConnection = if (api.isConfigLoaded) api.totalConnectionStatistic else null
                    }
                }
                tick++
                delay(Constants.REST_UPDATE_INTERVAL)
            }
        }
    }

    val lines = remember(tick, service, serviceState, systemStatus, totalConnection) {
        statusLines(context, service, serviceState, systemStatus, totalConnection)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RunModeSelector(preferences)
        HorizontalDivider()
        lines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            HorizontalDivider()
        }
    }
}

private fun statusLines(
    context: Context,
    service: SyncthingService?,
    serviceState: SyncthingService.State,
    systemStatus: SystemStatus?,
    total: Connection?,
): List<String> = buildList {
    add(
        context.getString(
            when (serviceState) {
                SyncthingService.State.INIT, SyncthingService.State.STARTING -> R.string.syncthing_starting
                SyncthingService.State.ACTIVE -> R.string.syncthing_running
                SyncthingService.State.DISABLED -> R.string.syncthing_not_running
                SyncthingService.State.ERROR -> R.string.syncthing_has_crashed
            }
        )
    )
    // Why syncthing is (not) running.
    if (service != null &&
        (serviceState == SyncthingService.State.ACTIVE || serviceState == SyncthingService.State.DISABLED)
    ) {
        add(
            context.getString(R.string.reason) + "\n- " +
                service.runDecisionExplanation.trim().replace("\n", "\n- ")
        )
    }
    if (serviceState != SyncthingService.State.ACTIVE || systemStatus == null) {
        return@buildList
    }
    add(context.getString(R.string.uptime) + ": " + formatUptime(systemStatus.uptime))
    add(context.getString(R.string.ram_usage) + ": " + Util.readableFileSize(context, systemStatus.sys.toDouble()))
    val connection = total ?: Connection()
    add(context.getString(R.string.download_title) + ": " + transferText(context, connection.inBits, connection.inBytesTotal))
    add(context.getString(R.string.upload_title) + ": " + transferText(context, connection.outBits, connection.outBytesTotal))
    val announceTotal = systemStatus.discoveryMethods
    if (announceTotal != 0) {
        val announceConnected = announceTotal - (systemStatus.discoveryErrors?.size ?: 0)
        add(
            context.getString(R.string.announce_server) + ": " +
                String.format(Locale.getDefault(), "%1\$d/%2\$d", announceConnected, announceTotal)
        )
    }
}

private fun formatUptime(uptimeSeconds: Long): String {
    val days = TimeUnit.SECONDS.toDays(uptimeSeconds)
    val hours = TimeUnit.SECONDS.toHours(uptimeSeconds) - TimeUnit.DAYS.toHours(days)
    val minutes = TimeUnit.SECONDS.toMinutes(uptimeSeconds) - TimeUnit.HOURS.toMinutes(hours) - TimeUnit.DAYS.toMinutes(days)
    return when {
        days > 0 -> String.format(Locale.getDefault(), "%dd %02dh %02dm", days, hours, minutes)
        hours > 0 -> String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
        else -> String.format(Locale.getDefault(), "%dm", minutes)
    }
}

/**
 * Rates under 1 KiB/s are shown as zero: discovery and index exchange traffic is not worth a look.
 */
private fun transferText(context: Context, bits: Long, bytesTotal: Long): String {
    val rate = if (bits / 8 < 1024) "0 B/s" else Util.readableTransferRate(context, bits)
    return rate + " (" + Util.readableFileSize(context, bytesTotal.toDouble()) + ")"
}

/**
 * Follow the run conditions, or force Syncthing to run or to stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunModeSelector(preferences: SharedPreferences) {
    val context = LocalContext.current
    var selected by remember {
        mutableIntStateOf(
            preferences.getInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, Constants.BTNSTATE_NO_FORCE_START_STOP)
        )
    }
    // Follows changes made elsewhere: quick settings tile, broadcast from another app. The listener
    // is held here, SharedPreferences only keeps a weak reference to it.
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == Constants.PREF_BTNSTATE_FORCE_START_STOP) {
                selected = prefs.getInt(key, Constants.BTNSTATE_NO_FORCE_START_STOP)
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Index order matches the Constants.BTNSTATE_* values.
    val labels = listOf(
        R.string.button_follow_run_conditions,
        R.string.button_force_start,
        R.string.button_force_stop,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selected == index,
                onClick = {
                    selected = index
                    preferences.edit { putInt(Constants.PREF_BTNSTATE_FORCE_START_STOP, index) }
                    // Notify RunConditionMonitor that the button's state changed.
                    LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(Intent(RunConditionMonitor.ACTION_UPDATE_SHOULDRUN_DECISION))
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                label = {
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                },
            )
        }
    }
}
