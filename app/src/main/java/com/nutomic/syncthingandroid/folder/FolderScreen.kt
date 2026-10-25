package com.nutomic.syncthingandroid.folder

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.ui.SectionTitle
import com.nutomic.syncthingandroid.ui.SwitchItem

private enum class FolderDialog { TYPE, PULL_ORDER, VERSIONING, DELETE, DISCARD }

/**
 * Creates or edits a folder.
 *
 * @param devices remote devices the folder can be shared with, null while they are read.
 * @param onEdit changes what the screen tracks around the folder.
 * @param onEditFolder changes the folder itself.
 * @param onClose leaves without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    form: FolderForm,
    devices: List<Device>?,
    expertMode: Boolean,
    saving: Boolean,
    onEdit: ((FolderForm) -> FolderForm) -> Unit,
    onEditFolder: (Folder.() -> Unit) -> Unit,
    onPickDirectory: () -> Unit,
    onPickAdvancedDirectory: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenSyncConditions: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val folder = form.folder
    var dialog by rememberSaveable { mutableStateOf<FolderDialog?>(null) }

    val back: () -> Unit = {
        when {
            saving -> Unit
            form.needsUpdate -> dialog = FolderDialog.DISCARD
            else -> onClose()
        }
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (form.isCreate) R.string.create_folder else R.string.edit_folder)) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onSave, enabled = !saving) {
                        Icon(
                            Icons.Outlined.Done,
                            contentDescription = stringResource(if (form.isCreate) R.string.create else R.string.save_title),
                        )
                    }
                    if (!form.isCreate) {
                        IconButton(onClick = { dialog = FolderDialog.DELETE }, enabled = !saving) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_folder))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            FolderFields(
                form = form,
                devices = devices,
                expertMode = expertMode,
                onEdit = onEdit,
                onEditFolder = onEditFolder,
                onPickDirectory = onPickDirectory,
                onPickAdvancedDirectory = onPickAdvancedDirectory,
                onAddDevice = onAddDevice,
                onOpenSyncConditions = onOpenSyncConditions,
                onOpenDialog = { dialog = it },
            )
            if (saving) {
                SavingOverlay()
            }
        }
    }

    val dismiss: () -> Unit = { dialog = null }
    when (dialog) {
        FolderDialog.TYPE -> ChoiceDialog(
            title = R.string.folder_type,
            choices = if (form.isCreate) folderTypeChoices + receiveEncryptedChoice else folderTypeChoices,
            initial = folder.type,
            onConfirm = { type ->
                dialog = null
                onEditFolder { this.type = type }
            },
            onDismiss = dismiss,
        )
        FolderDialog.PULL_ORDER -> ChoiceDialog(
            title = R.string.pull_order,
            choices = pullOrderChoices,
            initial = pullOrderChoice(folder.order).value,
            onConfirm = { order ->
                dialog = null
                onEditFolder { this.order = order }
            },
            onDismiss = dismiss,
        )
        FolderDialog.VERSIONING -> VersioningDialog(
            versioning = folder.versioning,
            onConfirm = { build ->
                dialog = null
                onEditFolder { versioning = versioning.build() }
            },
            onDismiss = dismiss,
        )
        FolderDialog.DELETE -> AlertDialog(
            onDismissRequest = dismiss,
            text = { Text(stringResource(R.string.remove_folder_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialog = null
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.no)) } },
        )
        FolderDialog.DISCARD -> AlertDialog(
            onDismissRequest = dismiss,
            text = { Text(stringResource(R.string.dialog_discard_changes)) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun FolderFields(
    form: FolderForm,
    devices: List<Device>?,
    expertMode: Boolean,
    onEdit: ((FolderForm) -> FolderForm) -> Unit,
    onEditFolder: (Folder.() -> Unit) -> Unit,
    onPickDirectory: () -> Unit,
    onPickAdvancedDirectory: () -> Unit,
    onAddDevice: () -> Unit,
    onOpenSyncConditions: () -> Unit,
    onOpenDialog: (FolderDialog) -> Unit,
) {
    val context = LocalContext.current
    val folder = form.folder
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(bottom = 16.dp),
    ) {
        OutlinedTextField(
            value = folder.label.orEmpty(),
            onValueChange = { value -> onEditFolder { label = value } },
            label = { Text(stringResource(R.string.folder_label)) },
            leadingIcon = { Icon(Icons.Outlined.SortByAlpha, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = folder.id.orEmpty(),
            onValueChange = { value -> onEditFolder { id = value } },
            label = { Text(stringResource(R.string.folder_id)) },
            leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
            enabled = form.isCreate,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // The path is set when the folder is created, then fixed.
        ListItem(
            headlineContent = { Text(folder.path.takeUnless { it.isNullOrEmpty() } ?: stringResource(R.string.directory)) },
            supportingContent = if (folder.path.isNullOrEmpty()) {
                null
            } else {
                {
                    Text(stringResource(if (form.canWrite) R.string.folder_path_readwrite else R.string.folder_path_readonly))
                }
            },
            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            trailingContent = if (form.isCreate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                {
                    // Android 11 disallows picking "Download" and the storage root with the system picker.
                    IconButton(onClick = onPickAdvancedDirectory) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.advanced_directory_selection),
                        )
                    }
                }
            } else {
                null
            },
            modifier = Modifier.clickable(enabled = form.isCreate, onClick = onPickDirectory),
        )

        SectionTitle(R.string.devices, Icons.Outlined.Share)
        when {
            devices == null -> Unit
            devices.isEmpty() -> ListItem(
                headlineContent = { Text(stringResource(R.string.devices_list_empty)) },
                modifier = Modifier.clickable(onClick = onAddDevice),
            )
            else -> devices.forEach { device -> DeviceShare(device, folder, onEditFolder) }
        }

        val typeChoice = folderTypeChoice(folder.type)
        ListItem(
            headlineContent = { Text(stringResource(R.string.folder_type)) },
            supportingContent = { ChoiceSummary(typeChoice) },
            leadingContent = { Icon(folderTypeIcon(folder.type), contentDescription = null) },
            // An existing folder shared encrypted keeps its type: Syncthing refuses the change.
            modifier = Modifier.clickable(enabled = form.isCreate || folder.type != Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED) {
                when {
                    folder.path.isNullOrEmpty() ->
                        Toast.makeText(context, R.string.folder_path_required, Toast.LENGTH_LONG).show()
                    // Readonly access: the folder can only send changes, as explained under the path.
                    !form.canWrite ->
                        Toast.makeText(context, R.string.folder_path_readonly, Toast.LENGTH_LONG).show()
                    else -> onOpenDialog(FolderDialog.TYPE)
                }
            },
        )

        SwitchItem(
            icon = Icons.Outlined.Sync,
            title = R.string.folder_fileWatcher,
            description = R.string.folder_fileWatcherDescription,
            checked = folder.fsWatcherEnabled,
            onCheckedChange = { checked -> onEditFolder { fsWatcherEnabled = checked } },
        )
        SwitchItem(
            icon = Icons.Outlined.PauseCircle,
            title = R.string.folder_pause,
            checked = folder.paused,
            onCheckedChange = { checked -> onEditFolder { paused = checked } },
        )

        if (!form.isCreate) {
            SwitchItem(
                icon = Icons.Outlined.Tune,
                title = R.string.custom_sync_conditions_title,
                description = R.string.custom_sync_conditions_description,
                checked = form.customSyncConditions,
                onCheckedChange = { checked -> onEdit { it.copy(customSyncConditions = checked) } },
            )
            TextButton(
                onClick = onOpenSyncConditions,
                enabled = form.customSyncConditions,
                modifier = Modifier.padding(start = 56.dp),
            ) {
                Text(stringResource(R.string.custom_sync_conditions_dialog))
            }
        }

        // Send only folders pull nothing.
        if (expertMode && folder.type != Constants.FOLDER_TYPE_SEND_ONLY) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.pull_order)) },
                supportingContent = { ChoiceSummary(pullOrderChoice(folder.order)) },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
                modifier = Modifier.clickable { onOpenDialog(FolderDialog.PULL_ORDER) },
            )
        }

        val (versioningType, versioningInfo) = versioningSummary(folder.versioning)
        ListItem(
            headlineContent = { Text(stringResource(R.string.file_versioning)) },
            supportingContent = {
                Column {
                    Text(stringResource(R.string.file_versioning_generic_description))
                    Text(versioningType)
                    if (versioningInfo.isNotEmpty()) {
                        Text(versioningInfo)
                    }
                }
            },
            leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
            modifier = Modifier.clickable {
                if (isVersioningEditable(folder.versioning)) {
                    onOpenDialog(FolderDialog.VERSIONING)
                } else {
                    Toast.makeText(context, R.string.versioning_type_unsupported, Toast.LENGTH_LONG).show()
                }
            },
        )

        if (expertMode) {
            SwitchItem(
                icon = Icons.Outlined.DeleteSweep,
                title = R.string.folder_ignore_delete_caption,
                description = R.string.folder_ignore_delete_description,
                checked = folder.ignoreDelete,
                onCheckedChange = { checked -> onEditFolder { ignoreDelete = checked } },
            )
            SwitchItem(
                icon = Icons.Outlined.Code,
                title = R.string.folder_run_script_caption,
                description = R.string.folder_run_script_description,
                checked = form.runScript,
                onCheckedChange = { checked -> onEdit { it.copy(runScript = checked) } },
            )
        }

        if (!form.isCreate) {
            // Syncthing writes the patterns to a file in the folder: they need write access.
            OutlinedTextField(
                value = form.ignoreList.orEmpty(),
                onValueChange = { value -> onEdit { it.copy(ignoreList = value, ignoreListChanged = true) } },
                label = { Text(stringResource(R.string.ignore_patterns)) },
                enabled = form.canWrite && form.ignoreList != null,
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Whether the folder is shared with [device] and, if so, the password that encrypts its data there.
 */
@Composable
private fun DeviceShare(device: Device, folder: Folder, onEditFolder: (Folder.() -> Unit) -> Unit) {
    val shared = folder.getDevice(device.deviceID)
    ListItem(
        headlineContent = { Text(device.displayName) },
        trailingContent = { Switch(checked = shared != null, onCheckedChange = null) },
        modifier = Modifier.toggleable(
            value = shared != null,
            role = Role.Switch,
            onValueChange = { checked ->
                onEditFolder {
                    if (checked) {
                        addDevice(
                            SharedWithDevice().apply {
                                deviceID = device.deviceID
                                introducedBy = device.introducedBy
                            }
                        )
                    } else {
                        removeDevice(device.deviceID)
                    }
                }
            },
        ),
    )
    if (shared != null) {
        OutlinedTextField(
            value = shared.encryptionPassword.orEmpty(),
            onValueChange = { value -> onEditFolder { getDevice(device.deviceID)?.encryptionPassword = value } },
            label = { Text(stringResource(R.string.deviceEncryptionPasswordHint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun SavingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            // Swallows touches: nothing changes while the folder is written.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.state_saving))
        }
    }
}

private fun folderTypeIcon(type: String?): ImageVector = when (type) {
    Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> Icons.Outlined.Lock
    Constants.FOLDER_TYPE_RECEIVE_ONLY -> Icons.Outlined.Download
    Constants.FOLDER_TYPE_SEND_ONLY -> Icons.Outlined.Upload
    else -> Icons.Outlined.Folder
}
