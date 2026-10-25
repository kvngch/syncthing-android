package com.nutomic.syncthingandroid.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.DeviceActivity
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.activities.WebViewActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.settings.SettingsActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

private const val TAG = "MainScreen"

/**
 * Time after the first install before Syncthing asks whether to send usage reports.
 */
private val USAGE_REPORTING_DIALOG_DELAY = TimeUnit.DAYS.toMillis(3)

private val tabTitles = listOf(
    R.string.folders_fragment_title,
    R.string.devices_fragment_title,
    R.string.status_fragment_title,
)

/**
 * Folders, devices and status tabs under a top bar, and the navigation drawer.
 *
 * @param runAsService hides the exit button: exiting then goes through the drawer, which asks
 * for a confirmation.
 * @param menuKeyPresses one element per press of the hardware menu key, which toggles the drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    service: SyncthingService?,
    serviceState: SyncthingService.State,
    preferences: SharedPreferences,
    runAsService: Boolean,
    menuKeyPresses: ReceiveChannel<Unit>,
    onShowDeviceId: () -> Unit,
    onExit: () -> Unit,
) {
    val api = service?.api
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val pagerState = rememberPagerState { tabTitles.size }

    LaunchedEffect(menuKeyPresses) {
        while (true) {
            menuKeyPresses.receive()
            // In a child coroutine: a drag that interrupts the animation cancels it, not this loop.
            launch { if (drawerState.isOpen) drawerState.close() else drawerState.open() }
        }
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                serviceRunning = serviceState == SyncthingService.State.ACTIVE,
                drawerOpen = drawerState.isOpen,
                runAsService = runAsService,
                onCloseDrawer = { scope.launch { drawerState.close() } },
                onShowDeviceId = onShowDeviceId,
                onExit = onExit,
            )
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { MainTitle(api, serviceState) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_main_menu))
                            }
                        },
                        actions = { TabActions(pagerState.currentPage, api) },
                    )
                    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                        tabTitles.forEachIndexed { index, title ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(stringResource(title)) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!runAsService) {
                    FloatingActionButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = stringResource(R.string.exit))
                    }
                }
            },
        ) { padding ->
            HorizontalPager(pagerState, Modifier.padding(padding).fillMaxSize()) { page ->
                when (page) {
                    0 -> FoldersTab(api, serviceState)
                    1 -> DevicesTab(api, serviceState, preferences)
                    else -> StatusTab(service, serviceState, preferences)
                }
            }
        }
    }

    UsageReportingDialog(api, serviceState)
}

/**
 * App name, a light showing whether Syncthing runs and, while it syncs, the total progress.
 */
@Composable
private fun MainTitle(api: RestApi?, serviceState: SyncthingService.State) {
    val (lightColor, lightDescription) = when (serviceState) {
        SyncthingService.State.ACTIVE -> Color(0xFF76FF03) to R.string.syncthing_running
        SyncthingService.State.STARTING -> Color(0xFFFFF103) to R.string.syncthing_starting
        else -> Color(0xFFFFAE03) to R.string.syncthing_not_running
    }
    val totalCompletion = rememberPolled(api, serviceState, intervalMs = Constants.GUI_UPDATE_INTERVAL) {
        if (serviceState == SyncthingService.State.ACTIVE && api != null && api.isConfigLoaded) {
            api.totalSyncCompletion
        } else {
            -1
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name))
        val description = stringResource(lightDescription)
        Box(
            Modifier
                .size(12.dp)
                .background(lightColor, CircleShape)
                .semantics { contentDescription = description }
        )
        if (totalCompletion != null && totalCompletion != -1) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { totalCompletion / 100f },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 3.dp,
                )
                Text("$totalCompletion", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TabActions(page: Int, api: RestApi?) {
    val context = LocalContext.current
    when (page) {
        0 -> {
            IconButton(
                onClick = {
                    if (api == null || !api.isConfigLoaded) {
                        Log.e(TAG, "rescanAll skipped because Syncthing is not running.")
                    } else {
                        api.rescanAll()
                    }
                }
            ) {
                Icon(
                    painterResource(R.drawable.outline_autorenew_24),
                    contentDescription = stringResource(R.string.activity_main_bottom_navigation_rescan_all),
                )
            }
            IconButton(
                onClick = {
                    context.startActivity(
                        Intent(context, FolderActivity::class.java).putExtra(FolderActivity.EXTRA_IS_CREATE, true)
                    )
                }
            ) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.add_folder))
            }
        }
        1 -> IconButton(
            onClick = {
                context.startActivity(
                    Intent(context, DeviceActivity::class.java).putExtra(DeviceActivity.EXTRA_IS_CREATE, true)
                )
            }
        ) {
            Icon(painterResource(R.drawable.ic_device_add_black_24), contentDescription = stringResource(R.string.add_device))
        }
        else -> IconButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
        }
    }
}

@Composable
internal fun LoadingList() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun EmptyList(@StringRes text: Int) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(text), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Asks whether to send anonymous usage reports, once Syncthing runs and some days after the
 * install, until the user answers.
 */
@Composable
private fun UsageReportingDialog(api: RestApi?, serviceState: SyncthingService.State) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api, serviceState) {
        if (serviceState == SyncthingService.State.ACTIVE && api != null && api.isConfigLoaded &&
            !api.isUsageReportingDecided && usageReportingDelayPassed(context)
        ) {
            api.getUsageReport { report = it }
        }
    }

    val shownReport = report ?: return
    val usageStatsUrl = stringResource(R.string.syncthing_usage_stats_url)
    fun answer(accept: Boolean) {
        // The dialog outlives a restart of Syncthing: the answer then waits for its config.
        api?.let { restApi ->
            restApi.runWhenConfigLoaded {
                restApi.setUsageReporting(accept)
                restApi.sendConfig()
            }
        }
        report = null
    }
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text(stringResource(R.string.usage_reporting_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.usage_reporting_dialog_description))
                Text(
                    shownReport,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { answer(true) }) { Text(stringResource(R.string.yes)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(context, WebViewActivity::class.java)
                                .putExtra(WebViewActivity.EXTRA_WEB_URL, usageStatsUrl)
                        )
                        report = null
                    }
                ) {
                    Text(stringResource(R.string.open_website))
                }
                TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.no)) }
            }
        },
    )
}

private fun usageReportingDelayPassed(context: Context): Boolean {
    val firstInstallTime = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    return System.currentTimeMillis() > firstInstallTime + USAGE_REPORTING_DIALOG_DELAY
}
