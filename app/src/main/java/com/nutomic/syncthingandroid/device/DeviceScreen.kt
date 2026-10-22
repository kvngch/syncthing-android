package com.nutomic.syncthingandroid.device

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Phonelink
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.ui.SectionTitle
import com.nutomic.syncthingandroid.ui.SwitchItem

private enum class DeviceDialog { COMPRESSION, DELETE, DISCARD }

/**
 * A device found on the local network, with the addresses it announced.
 */
class FoundDevice(val deviceId: String, val addresses: String)

/**
 * Devices found on the local network, for the create mode.
 *
 * @param localDiscoveryEnabled explains an empty list: nothing found, or nothing searched.
 */
class Discovery(val devices: List<FoundDevice>, val localDiscoveryEnabled: Boolean)

/**
 * Adds or edits a device.
 *
 * @param folders the folders the device can share, null while they are read.
 * @param discovery devices found on the local network, create mode only.
 * @param connection the current connection, edit mode only, null if the device was never seen.
 * @param onClose leaves without saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    form: DeviceForm,
    folders: List<Folder>?,
    discovery: Discovery?,
    connection: Connection?,
    expertMode: Boolean,
    canScanQrCode: Boolean,
    onEdit: ((DeviceForm) -> DeviceForm) -> Unit,
    onEditDevice: (Device.() -> Unit) -> Unit,
    onScanQrCode: () -> Unit,
    onSearchDevices: () -> Unit,
    onShareDeviceId: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenSyncConditions: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val device = form.device
    var dialog by rememberSaveable { mutableStateOf<DeviceDialog?>(null) }

    val back: () -> Unit = {
        if (form.needsUpdate) {
            dialog = DeviceDialog.DISCARD
        } else {
            onClose()
        }
    }
    BackHandler(onBack = back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (form.isCreate) R.string.add_device else R.string.edit_device)) },
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onSave) {
                        Icon(
                            Icons.Outlined.Done,
                            contentDescription = stringResource(if (form.isCreate) R.string.create else R.string.save_title),
                        )
                    }
                    if (!form.isCreate) {
                        IconButton(onClick = { dialog = DeviceDialog.DELETE }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.delete_device))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 16.dp),
        ) {
            OutlinedTextField(
                value = device.deviceID.orEmpty(),
                onValueChange = { value -> onEditDevice { deviceID = value } },
                label = { Text(stringResource(R.string.device_id)) },
                leadingIcon = { Icon(Icons.Outlined.Tag, contentDescription = null) },
                trailingIcon = {
                    when {
                        !form.isCreate -> IconButton(onClick = onShareDeviceId) {
                            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.share_device_id))
                        }
                        canScanQrCode -> IconButton(onClick = onScanQrCode) {
                            Icon(
                                Icons.Outlined.QrCodeScanner,
                                contentDescription = stringResource(R.string.scan_qr_code_description),
                            )
                        }
                    }
                },
                // The ID of a configured device cannot change.
                readOnly = !form.isCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Shown until an ID is entered, as picking one fills it.
            if (form.isCreate && discovery != null && device.deviceID.isNullOrEmpty()) {
                DiscoveredDevices(discovery, onSearchDevices) { deviceId -> onEditDevice { deviceID = deviceId } }
            }

            OutlinedTextField(
                value = device.name.orEmpty(),
                onValueChange = { value -> onEditDevice { name = value } },
                label = { Text(stringResource(R.string.name)) },
                leadingIcon = { Icon(Icons.Outlined.SortByAlpha, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = form.addresses,
                onValueChange = { value -> onEdit { it.copy(addresses = value) } },
                label = { Text(stringResource(R.string.addresses)) },
                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SectionTitle(R.string.folders, Icons.Outlined.Folder)
            when {
                folders == null -> Unit
                folders.isEmpty() -> ListItem(
                    headlineContent = { Text(stringResource(R.string.folders_list_empty)) },
                    modifier = Modifier.clickable(onClick = onAddFolder),
                )
                else -> folders.forEach { folder ->
                    FolderShareItem(folder, form.shares[folder.id]) { share ->
                        onEdit { it.copy(shares = it.shares + (folder.id to share)) }
                    }
                }
            }

            if (expertMode) {
                val compressionLabels = stringArrayResource(R.array.compress_entries)
                ListItem(
                    headlineContent = { Text(stringResource(R.string.compression)) },
                    supportingContent = { Text(compressionLabels[compressionIndex(device.compression)]) },
                    leadingContent = { Icon(Icons.Outlined.Compress, contentDescription = null) },
                    modifier = Modifier.clickable { dialog = DeviceDialog.COMPRESSION },
                )
            }

            SwitchItem(
                icon = Icons.Outlined.DeviceHub,
                title = R.string.introducer,
                checked = device.introducer,
                onCheckedChange = { checked -> onEditDevice { introducer = checked } },
            )
            SwitchItem(
                icon = Icons.Outlined.AutoFixHigh,
                title = R.string.autoAcceptFolders,
                checked = device.autoAcceptFolders,
                onCheckedChange = { checked -> onEditDevice { autoAcceptFolders = checked } },
            )
            SwitchItem(
                icon = Icons.Outlined.PauseCircle,
                title = R.string.pause_device,
                checked = device.paused,
                onCheckedChange = { checked -> onEditDevice { paused = checked } },
            )
            SwitchItem(
                icon = Icons.Outlined.Lock,
                title = R.string.untrusted_device,
                description = R.string.untrusted_device_explanation,
                checked = device.untrusted,
                onCheckedChange = { checked -> onEditDevice { untrusted = checked } },
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

            if (connection != null) {
                ListItem(
                    overlineContent = { Text(stringResource(R.string.current_address)) },
                    headlineContent = { Text(connection.address.orEmpty()) },
                    leadingContent = { Icon(Icons.Outlined.Phonelink, contentDescription = null) },
                )
                ListItem(
                    overlineContent = { Text(stringResource(R.string.syncthing_version_title)) },
                    headlineContent = { Text(connection.clientVersion.orEmpty()) },
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                )
            }
        }
    }

    val dismiss: () -> Unit = { dialog = null }
    when (dialog) {
        DeviceDialog.COMPRESSION -> CompressionDialog(
            current = compressionIndex(device.compression),
            onSelect = { value ->
                dialog = null
                if (value != device.compression) {
                    onEditDevice { compression = value }
                }
            },
            onDismiss = dismiss,
        )
        DeviceDialog.DELETE -> AlertDialog(
            onDismissRequest = dismiss,
            text = { Text(stringResource(R.string.remove_device_confirm)) },
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
        DeviceDialog.DISCARD -> AlertDialog(
            onDismissRequest = dismiss,
            text = { Text(stringResource(R.string.dialog_discard_changes)) },
            confirmButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
        null -> Unit
    }
}

/**
 * Position of a compression value in the compress arrays. Unknown values read as the first entry,
 * as the former screen did.
 */
@Composable
private fun compressionIndex(value: String?): Int =
    stringArrayResource(R.array.compress_values).indexOf(value).coerceAtLeast(0)

@Composable
private fun DiscoveredDevices(discovery: Discovery, onSearch: () -> Unit, onPick: (String) -> Unit) {
    // Tapping the title searches again.
    SectionTitle(R.string.discovered_devices_title, Icons.Outlined.DeviceHub, Modifier.clickable(onClick = onSearch))
    if (discovery.devices.isEmpty()) {
        ListItem(
            headlineContent = {
                Text(
                    if (discovery.localDiscoveryEnabled) {
                        stringResource(R.string.discovered_device_list_empty, stringResource(R.string.url_syncthing_homepage))
                    } else {
                        stringResource(R.string.local_discovery_disabled)
                    }
                )
            },
        )
        return
    }
    discovery.devices.forEach { found ->
        ListItem(
            headlineContent = { Text(found.deviceId) },
            supportingContent = if (found.addresses.isEmpty()) {
                null
            } else {
                { Text(found.addresses) }
            },
            modifier = Modifier.clickable { onPick(found.deviceId) },
        )
    }
}

/**
 * Whether [folder] is shared with the device and, if so, the password that encrypts its data there.
 */
@Composable
private fun FolderShareItem(folder: Folder, share: FolderShare?, onChange: (FolderShare) -> Unit) {
    val current = share ?: FolderShare(shared = false, password = "")
    ListItem(
        headlineContent = { Text(folder.toString()) },
        trailingContent = { Switch(checked = current.shared, onCheckedChange = null) },
        modifier = Modifier.toggleable(
            value = current.shared,
            role = Role.Switch,
            onValueChange = { checked -> onChange(current.copy(shared = checked)) },
        ),
    )
    if (current.shared) {
        OutlinedTextField(
            value = current.password,
            onValueChange = { value -> onChange(current.copy(password = value)) },
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

/**
 * Picks a compression mode; a choice applies at once.
 */
@Composable
private fun CompressionDialog(current: Int, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val labels = stringArrayResource(R.array.compress_entries)
    val values = stringArrayResource(R.array.compress_values)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compression)) },
        text = {
            Column(Modifier.selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { RadioButton(selected = index == current, onClick = null) },
                        modifier = Modifier.selectable(
                            selected = index == current,
                            role = Role.RadioButton,
                            onClick = { onSelect(values[index]) },
                        ),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
