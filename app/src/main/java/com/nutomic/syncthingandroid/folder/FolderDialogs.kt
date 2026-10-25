package com.nutomic.syncthingandroid.folder

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.FolderPickerActivity
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import java.util.concurrent.TimeUnit

/**
 * One option of a choice dialog: the value stored in the config, its label and what it does.
 */
internal class Choice(val value: String, @StringRes val label: Int, @StringRes val description: Int)

internal val folderTypeChoices = listOf(
    Choice(Constants.FOLDER_TYPE_SEND_RECEIVE, R.string.folder_type_sendreceive, R.string.folder_type_sendreceive_description),
    Choice(Constants.FOLDER_TYPE_SEND_ONLY, R.string.folder_type_sendonly, R.string.folder_type_sendonly_description),
    Choice(Constants.FOLDER_TYPE_RECEIVE_ONLY, R.string.folder_type_receiveonly, R.string.folder_type_receiveonly_description),
)

/**
 * Only set when accepting a folder shared encrypted: shown, never offered.
 */
internal val receiveEncryptedChoice = Choice(
    Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED,
    R.string.folder_type_receive_encrypted,
    R.string.folder_type_receive_encrypted_description,
)

internal val pullOrderChoices = listOf(
    Choice("random", R.string.pull_order_type_random, R.string.pull_order_type_random_description),
    Choice("alphabetic", R.string.pull_order_type_alphabetic, R.string.pull_order_type_alphabetic_description),
    Choice("smallestFirst", R.string.pull_order_type_smallestFirst, R.string.pull_order_type_smallestFirst_description),
    Choice("largestFirst", R.string.pull_order_type_largestFirst, R.string.pull_order_type_largestFirst_description),
    Choice("oldestFirst", R.string.pull_order_type_oldestFirst, R.string.pull_order_type_oldestFirst_description),
    Choice("newestFirst", R.string.pull_order_type_newestFirst, R.string.pull_order_type_newestFirst_description),
)

/**
 * Radio list of [choices], applied by OK.
 */
@Composable
internal fun ChoiceDialog(
    @StringRes title: Int,
    choices: List<Choice>,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == choice.value,
                                onClick = { selected = choice.value },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = selected == choice.value, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(choice.label), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(choice.description), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private val versioningTypes = listOf(
    "none" to R.string.versioning_type_none,
    "trashcan" to R.string.versioning_type_trashcan,
    "simple" to R.string.versioning_type_simple,
    "staggered" to R.string.versioning_type_staggered,
    "external" to R.string.versioning_type_external,
)

/**
 * Whether [VersioningDialog] can show this versioning. Others, added by a newer Syncthing, would be
 * overwritten: they are left to the web GUI.
 */
internal fun isVersioningEditable(versioning: Folder.Versioning?): Boolean =
    versioning?.type.isNullOrEmpty() || versioningTypes.any { it.first == versioning?.type }

/**
 * Versioning type and its parameters. Numbers keep the ranges of the former number pickers.
 */
@Composable
internal fun VersioningDialog(
    versioning: Folder.Versioning?,
    onConfirm: (Folder.Versioning?.() -> Folder.Versioning) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val initial = versioning?.params.orEmpty()
    var type by rememberSaveable { mutableStateOf(versioning?.type.takeUnless { it.isNullOrEmpty() } ?: "none") }
    var keep by rememberSaveable { mutableStateOf(initial["keep"] ?: "5") }
    var cleanoutDays by rememberSaveable { mutableStateOf(initial["cleanoutDays"] ?: "0") }
    var maxAgeDays by rememberSaveable {
        mutableStateOf(TimeUnit.SECONDS.toDays(initial["maxAge"]?.toLongOrNull() ?: 0).toString())
    }
    var versionsPath by rememberSaveable { mutableStateOf(versioning?.fsPath.orEmpty()) }
    var command by rememberSaveable { mutableStateOf(initial["command"].orEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }

    val pickVersionsPath = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)?.let { versionsPath = it }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.file_versioning)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(versioningTypes.first { it.first == type }.second), Modifier.weight(1f))
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        versioningTypes.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                onClick = {
                                    type = value
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                when (type) {
                    "trashcan" -> {
                        Text(stringResource(R.string.trashcan_versioning_description))
                        NumberField(cleanoutDays, { cleanoutDays = it }, R.string.clean_out_after, R.string.cleanout_after_description)
                    }
                    "simple" -> {
                        Text(stringResource(R.string.simple_file_versioning_description))
                        NumberField(keep, { keep = it }, R.string.keep_versions, R.string.keep_versions_description)
                        NumberField(cleanoutDays, { cleanoutDays = it }, R.string.clean_out_after, R.string.cleanout_after_description)
                    }
                    "staggered" -> {
                        Text(stringResource(R.string.staggered_versioning_description))
                        NumberField(maxAgeDays, { maxAgeDays = it }, R.string.maximum_age, R.string.maximum_age_description)
                        Column {
                            Text(stringResource(R.string.versions_path), style = MaterialTheme.typography.labelLarge)
                            TextButton(
                                onClick = {
                                    pickVersionsPath.launch(FolderPickerActivity.createIntent(context, versionsPath, null))
                                },
                            ) {
                                Text(versionsPath.ifEmpty { stringResource(R.string.directory) })
                            }
                            Text(stringResource(R.string.versions_path_description), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    "external" -> {
                        Text(stringResource(R.string.external_versioning_description))
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text(stringResource(R.string.command)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> Text(stringResource(R.string.no_versioning_description))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm {
                        if (type == "none") {
                            Folder.Versioning().apply { this.type = "" }
                        } else {
                            val result = this ?: Folder.Versioning()
                            when (type) {
                                "trashcan" -> result.params["cleanoutDays"] = clamp(cleanoutDays, 0)
                                "simple" -> {
                                    result.params["keep"] = clamp(keep, 1, 100000)
                                    result.params["cleanoutDays"] = clamp(cleanoutDays, 0)
                                }
                                "staggered" -> {
                                    result.params["maxAge"] =
                                        TimeUnit.DAYS.toSeconds(clamp(maxAgeDays, 0).toLong()).toString()
                                    result.fsPath = versionsPath
                                }
                                "external" -> result.params["command"] = command
                            }
                            result.type = type
                            // Syncthing only reads versioning.fsPath since config v35: migrate the legacy param.
                            result.params.remove("versionsPath")?.let { result.fsPath = it }
                            if (result.fsPath == null) {
                                result.fsPath = ""
                            }
                            if (result.fsType.isNullOrEmpty()) {
                                result.fsType = "basic"
                            }
                            result
                        }
                    }
                },
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * No upper bound on the days kept: OK writes every field, changed or not, and Syncthing deletes
 * the versions older than what it gets. [NumberField] takes six digits at most.
 */
private fun clamp(value: String, min: Int, max: Int = Int.MAX_VALUE): String =
    (value.toIntOrNull() ?: min).coerceIn(min, max).toString()

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    @StringRes description: Int,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) onValueChange(it) },
        label = { Text(stringResource(label)) },
        supportingText = { Text(stringResource(description)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Type and parameters of [versioning], as shown on the folder screen.
 */
@Composable
internal fun versioningSummary(versioning: Folder.Versioning?): Pair<String, String> {
    val params = versioning?.params.orEmpty()
    return when (versioning?.type) {
        null, "" -> stringResource(R.string.none) to ""
        "simple" -> stringResource(R.string.type_simple) to
            stringResource(R.string.simple_versioning_info, params["keep"].orEmpty())
        "trashcan" -> stringResource(R.string.type_trashcan) to
            stringResource(R.string.trashcan_versioning_info, params["cleanoutDays"].orEmpty())
        "staggered" -> {
            val maxAgeDays = TimeUnit.SECONDS.toDays(params["maxAge"]?.toLongOrNull() ?: 0).toInt()
            stringResource(R.string.type_staggered) to
                stringResource(R.string.staggered_versioning_info, maxAgeDays, versioning?.fsPath.orEmpty())
        }
        "external" -> stringResource(R.string.type_external) to
            stringResource(R.string.external_versioning_info, params["command"].orEmpty())
        else -> versioning?.type.orEmpty() to ""
    }
}

/**
 * Label and description of a folder type, receive encrypted included.
 */
internal fun folderTypeChoice(type: String?): Choice =
    (folderTypeChoices + receiveEncryptedChoice).firstOrNull { it.value == type } ?: folderTypeChoices.first()

/**
 * Label and description of a pull order. Syncthing defaults to random when the order is empty.
 */
internal fun pullOrderChoice(order: String?): Choice =
    pullOrderChoices.firstOrNull { it.value == order } ?: pullOrderChoices.first()

@Composable
internal fun ChoiceSummary(choice: Choice) {
    Column {
        Text(stringResource(choice.label))
        Text(stringResource(choice.description), style = MaterialTheme.typography.bodySmall)
    }
}
