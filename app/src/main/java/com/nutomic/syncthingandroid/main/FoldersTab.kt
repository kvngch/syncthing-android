package com.nutomic.syncthingandroid.main

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.FolderActivity
import com.nutomic.syncthingandroid.model.CachedFolderStatus
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderStatus
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.SuggestFileManagerDialog
import com.nutomic.syncthingandroid.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FoldersTab"

/**
 * A folder and, while Syncthing runs, its status from the REST API cache.
 */
internal class FolderRow(
    val folder: Folder,
    val status: FolderStatus?,
    val cached: CachedFolderStatus?,
)

private enum class FolderAction { OVERRIDE, REVERT }

private class PendingFolderAction(val folder: Folder, val action: FolderAction)

/**
 * Null if config.xml could not be read: the list keeps its last state.
 */
private suspend fun readFolders(context: Context, api: RestApi?): List<FolderRow>? {
    if (api != null && api.isConfigLoaded) {
        return api.folders.map { folder ->
            val entry = api.getFolderStatus(folder.id)
            FolderRow(folder, entry.key, entry.value)
        }
    }
    // Syncthing is not running: read config.xml, off the main thread.
    return withContext(Dispatchers.IO) {
        try {
            ConfigRouter(context).getFolders(api).map { FolderRow(it, null, null) }
        } catch (e: ConfigXml.OpenConfigException) {
            Log.e(TAG, "Failed to parse existing config. You will need support from here ...")
            null
        }
    }
}

@Composable
internal fun FoldersTab(api: RestApi?, serviceState: SyncthingService.State) {
    val context = LocalContext.current
    val rows = rememberPolled(api, serviceState, intervalMs = Constants.GUI_UPDATE_INTERVAL) {
        readFolders(context, api)
    }
    var pending by remember { mutableStateOf<PendingFolderAction?>(null) }

    when {
        rows == null -> LoadingList()
        rows.isEmpty() -> EmptyList(R.string.folder_list_empty)
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.folder.id.orEmpty() }) { row ->
                FolderItem(
                    row = row,
                    onClick = {
                        context.startActivity(
                            Intent(context, FolderActivity::class.java)
                                .putExtra(FolderActivity.EXTRA_IS_CREATE, false)
                                .putExtra(FolderActivity.EXTRA_FOLDER_ID, row.folder.id)
                        )
                    },
                    onAction = { action -> pending = PendingFolderAction(row.folder, action) },
                )
                HorizontalDivider()
            }
        }
    }

    pending?.let { request ->
        ConfirmFolderActionDialog(
            request = request,
            onConfirm = {
                // Sent through our service to the REST API.
                val intent = Intent(context, SyncthingService::class.java)
                    .putExtra(SyncthingService.EXTRA_FOLDER_ID, request.folder.id)
                    .setAction(
                        if (request.action == FolderAction.OVERRIDE) SyncthingService.ACTION_OVERRIDE_CHANGES
                        else SyncthingService.ACTION_REVERT_LOCAL_CHANGES
                    )
                context.startService(intent)
                pending = null
            },
            onDismiss = { pending = null },
        )
    }
}

@Composable
private fun FolderItem(row: FolderRow, onClick: () -> Unit, onAction: (FolderAction) -> Unit) {
    val context = LocalContext.current
    val folder = row.folder
    val status = row.status
    val cached = row.cached
    var suggestFileManager by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = folder.toString(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null && cached != null && status.state == "syncing") {
                LinearProgressIndicator(
                    progress = { (cached.completion / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
            Text(shortPathForUi(context, folder.path.orEmpty()), style = MaterialTheme.typography.bodySmall)

            if (status != null && cached != null) {
                FolderStatusDetails(folder, status, cached, onAction)
            }
            val invalid = if (status != null) status.invalid else folder.invalid
            if (!invalid.isNullOrEmpty()) {
                Text(invalid, color = colorResource(R.color.text_red), style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (status != null && cached != null) {
                val (text, color) = folderStateText(folder, status, cached)
                Text(text, color = color, style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = { suggestFileManager = !FileUtils.openFolder(context, folder.path) }) {
                Icon(folderTypeIcon(folder.type), contentDescription = stringResource(R.string.open_file_manager))
            }
        }
    }
    if (suggestFileManager) {
        SuggestFileManagerDialog(onDismiss = { suggestFileManager = false })
    }
}

@Composable
private fun FolderStatusDetails(
    folder: Folder,
    status: FolderStatus,
    cached: CachedFolderStatus,
    onAction: (FolderAction) -> Unit,
) {
    val context = LocalContext.current
    val red = colorResource(R.color.text_red)

    if (folder.type == Constants.FOLDER_TYPE_SEND_ONLY && isOutOfSync(status)) {
        OutlinedButton(onClick = { onAction(FolderAction.OVERRIDE) }) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = red, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.override_changes), Modifier.padding(start = 8.dp))
        }
    }
    if (isRevertVisible(folder, status)) {
        OutlinedButton(onClick = { onAction(FolderAction.REVERT) }) {
            Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = red, modifier = Modifier.size(18.dp))
            Text(
                stringResource(
                    if (folder.type == Constants.FOLDER_TYPE_RECEIVE_ONLY) R.string.revert_local_changes
                    else R.string.delete_unexpected_items
                ),
                Modifier.padding(start = 8.dp),
            )
        }
    }

    lastItemFinishedText(cached)?.let { (item, time) ->
        Text(item, style = MaterialTheme.typography.bodySmall)
        Text(time, style = MaterialTheme.typography.bodySmall)
    }

    if (!folder.paused) {
        val files = pluralStringResource(
            R.plurals.files, status.inSyncFiles.toInt(), status.inSyncFiles, status.globalFiles
        )
        val sizes = stringResource(
            R.string.folder_size_format,
            Util.readableFileSize(context, status.inSyncBytes.toDouble()),
            Util.readableFileSize(context, status.globalBytes.toDouble()),
        )
        Text("∑ $files • $sizes", style = MaterialTheme.typography.bodySmall)
    }

    val conflicts = cached.discoveredConflictFiles
    if (conflicts.isNotEmpty()) {
        val count = pluralStringResource(R.plurals.conflicts, conflicts.size, conflicts.size)
        val more = if (conflicts.size > 1) "\n…" else ""
        Text(
            "⚠ $count\n⤮ ${conflicts[0]}$more",
            color = colorResource(R.color.text_orange),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun isOutOfSync(status: FolderStatus): Boolean {
    val neededItems = status.needFiles + status.needDirectories + status.needSymlinks + status.needDeletes
    return status.state == "idle" && neededItems > 0
}

private fun isRevertVisible(folder: Folder, status: FolderStatus): Boolean = when (folder.type) {
    Constants.FOLDER_TYPE_RECEIVE_ONLY -> status.receiveOnlyTotalItems > 0
    Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED ->
        status.receiveOnlyTotalItems - status.receiveOnlyChangedDeletes > 0
    else -> false
}

@Composable
private fun folderStateText(folder: Folder, status: FolderStatus, cached: CachedFolderStatus): Pair<String, Color> {
    val red = colorResource(R.color.text_red)
    val green = colorResource(R.color.text_green)
    val blue = colorResource(R.color.text_blue)
    val orange = colorResource(R.color.text_orange)
    return when {
        isOutOfSync(status) -> stringResource(R.string.status_outofsync) to red
        status.errors > 0 -> stringResource(R.string.state_failed_items, status.errors) to red
        folder.paused -> stringResource(R.string.state_paused) to colorResource(R.color.text_purple)
        else -> when (status.state) {
            "clean-waiting" -> stringResource(R.string.state_clean_waiting) to orange
            "cleaning" -> stringResource(R.string.state_cleaning) to blue
            "idle" -> when {
                // The folder is idle and shared with no other device.
                folder.deviceCount <= 1 -> stringResource(R.string.state_unshared) to orange
                isRevertVisible(folder, status) -> stringResource(R.string.state_local_additions) to green
                else -> stringResource(R.string.state_up_to_date) to green
            }
            "scan-waiting" -> stringResource(R.string.state_scan_waiting) to orange
            "scanning" -> stringResource(R.string.state_scanning) to blue
            "sync-waiting" -> stringResource(R.string.state_sync_waiting) to orange
            "syncing" -> stringResource(R.string.state_syncing, cached.completion.toInt()) to blue
            "sync-preparing" -> stringResource(R.string.state_sync_preparing) to blue
            "error" ->
                if (status.error.isNullOrEmpty()) stringResource(R.string.state_error) to red
                else stringResource(R.string.state_error_message, status.error) to red
            "unknown" -> stringResource(R.string.state_unknown) to red
            else -> status.state.orEmpty() to red
        }
    }
}

/**
 * The last finished item and its time, or null if Syncthing reported none.
 */
private fun lastItemFinishedText(cached: CachedFolderStatus): Pair<String, String>? {
    if (cached.lastItemFinishedAction.isNullOrEmpty() ||
        cached.lastItemFinishedItem.isNullOrEmpty() ||
        cached.lastItemFinishedTime.isNullOrEmpty()
    ) {
        return null
    }
    val action = when (cached.lastItemFinishedAction) {
        "delete" -> "⊗"
        "update" -> "⊛"
        else -> "⁉"
    }
    val item = "⇌ $action ${Util.getPathEllipsis(cached.lastItemFinishedItem)}"
    val time = "⇌⌚" + Util.formatTime(cached.lastItemFinishedTime)
    return item to time
}

private fun shortPathForUi(context: Context, path: String): String {
    val shortened = path
        .replaceFirst("/storage/emulated/0", "[int]")
        .replaceFirst(Regex("/storage/[a-zA-Z0-9]{4}-[a-zA-Z0-9]{4}"), "[ext]")
        .replaceFirst("/" + context.packageName, "/[app]")
    return "❖ " + Util.getPathEllipsis(shortened)
}

private fun folderTypeIcon(type: String?): ImageVector = when (type) {
    Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> Icons.Outlined.Lock
    Constants.FOLDER_TYPE_RECEIVE_ONLY -> Icons.Outlined.Download
    Constants.FOLDER_TYPE_SEND_ONLY -> Icons.Outlined.Upload
    else -> Icons.Outlined.Folder
}

@Composable
private fun ConfirmFolderActionDialog(
    request: PendingFolderAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Same revert action, two meanings: receive encrypted folders delete the unexpected items.
    val receiveEncrypted = request.folder.type == Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED
    val (title, message) = when {
        request.action == FolderAction.OVERRIDE ->
            R.string.override_changes to R.string.override_changes_question
        receiveEncrypted -> R.string.delete_unexpected_items to R.string.delete_unexpected_items_question
        else -> R.string.revert_local_changes to R.string.revert_local_changes_question
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.no)) } },
    )
}
