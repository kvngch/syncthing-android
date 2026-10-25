package com.nutomic.syncthingandroid.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.activities.LogActivity
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.util.LocalActivityScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.LocalPreferenceTheme
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.PreferenceCategory
import me.zhanghai.compose.preference.TextFieldPreference
import me.zhanghai.compose.preference.TwoTargetPreference
import me.zhanghai.compose.preference.rememberPreferenceState


fun EntryProviderScope<SettingsRoute>.settingsImportExportEntry() {
    entry<SettingsRoute.ImportExport> {
        SettingsImportExportScreen()
    }
}


@Composable
fun SettingsImportExportScreen() {
    val defaultBackupPath = "backups/syncthing/config.zip"
    val backupPath = rememberPreferenceState(Constants.PREF_BACKUP_REL_PATH_TO_ZIP,defaultBackupPath)
    // One import or export at a time: both entries wait for the end of the current one.
    var transferRunning by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.category_backup),
    ) {
        item {
            PreferenceCategory(
                title = { Text(stringResource(R.string.preference_category_explanation)) },
            )
        }
        item {
            Preference(
                title = { Text(stringResource(R.string.backup_password_summary)) },
            )
        }

        item {
            PreferenceCategory(
                title = { Text(stringResource(R.string.preference_category_prepare)) },
            )
        }
        item {
            TextFieldPreference(
                title = { Text(stringResource(R.string.backup_rel_path_to_zip)) },
                summary = { Text(backupPath.value.ifBlank { defaultBackupPath }) },
                state = backupPath,
                textToValue = { it.ifBlank { defaultBackupPath } },
            )
        }
        item {
            PasswordPreference()
        }

        item {
            PreferenceCategory(
                title = { Text(stringResource(R.string.preference_category_actions)) },
            )
        }
        item {
            ExportConfigPreference(transferRunning) { transferRunning = it }
        }
        item {
            ImportConfigPreference(transferRunning) { transferRunning = it }
        }
    }
}

@Composable
private fun PasswordPreference() {
    val preferenceTheme = LocalPreferenceTheme.current

    val password = rememberPreferenceState(Constants.PREF_BACKUP_PASSWORD, "")
    val isPasswordSet = password.value.isNotBlank()
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showAlert by rememberSaveable { mutableStateOf(false) }

    val passwordSummary = if (!isPasswordSet)
        stringResource(R.string.backup_password_not_set)
    else if (!showPassword)
        stringResource(R.string.backup_password_set_masked)
    else
        stringResource(R.string.backup_password_set, password.value)

    TwoTargetPreference(
        title = { Text(stringResource(R.string.backup_password_title)) },
        summary = { Text(passwordSummary) },
        modifier = Modifier.semantics {
            password()
            isSensitiveData = true
        },
        secondTarget = {
            if (isPasswordSet) {
                Box(
                    Modifier.defaultMinSize(minWidth = preferenceTheme.iconContainerMinWidth),
                ) {
                    IconButton(
                        onClick = { showPassword = !showPassword},
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = preferenceTheme.iconColor,
                        ),
                    ) {
                        Icon(
                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            stringResource(R.string.toggle_password_visibility)
                        )
                    }
                }
            }
        },
        onClick = { showAlert = true },
    )
    if (showAlert) {
        var dialogText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            val text = password.value
            mutableStateOf(TextFieldValue(text, TextRange(text.length)))
        }
        val onOk = {
            val dialogValue = dialogText.text
            password.value = dialogValue
            showAlert = false
        }
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = {
                Text(stringResource(R.string.backup_password_title))
            },
            text = {
                val focusRequester = remember { FocusRequester() }
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .focusRequester(focusRequester)
                ) {
                    PasswordTextField(dialogText, { dialogText = it }) { onOk() }
                }
                LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
            },
            confirmButton = {
                TextButton(onClick = onOk) {
                    Text(text = stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAlert = false }) {
                    Text(text = stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ExportConfigPreference(transferRunning: Boolean, onTransferRunning: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = LocalActivityScope.current
    val navigator = LocalSettingsNavigator.current
    val stService = LocalSyncthingService.current

    var showAlert by rememberSaveable { mutableStateOf(false) }

    Preference(
        enabled = stService != null && !transferRunning,
        title = { Text(stringResource(R.string.export_config)) },
        onClick = { showAlert = true }
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.export_config)) },
            text = { Text(stringResource(R.string.dialog_confirm_export)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stService?.let { service ->
                            showAlert = false
                            onTransferRunning(true)
                            scope.launch(Dispatchers.IO) {
                                service.exportConfig().also { success ->
                                    withContext(Dispatchers.Main) {
                                        onTransferRunning(false)
                                        if (success) {
                                            Toast.makeText(
                                                context,
                                                R.string.config_export_successful_no_path,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            navigator.navigateUp()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                R.string.config_export_failed,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            val intent = Intent(context, LogActivity::class.java)
                                            context.startActivity(intent)
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlert = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ImportConfigPreference(transferRunning: Boolean, onTransferRunning: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = LocalActivityScope.current
    val navigator = LocalSettingsNavigator.current
    val stService = LocalSyncthingService.current

    var showAlert by rememberSaveable { mutableStateOf(false) }

    Preference(
        enabled = stService != null && !transferRunning,
        title = { Text(stringResource(R.string.import_config)) },
        onClick = { showAlert = true }
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.import_config)) },
            text = { Text(stringResource(R.string.dialog_confirm_import)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stService?.let { service ->
                            showAlert = false
                            onTransferRunning(true)
                            scope.launch(Dispatchers.IO) {
                                service.importConfig().also { success ->
                                    withContext(Dispatchers.Main) {
                                        onTransferRunning(false)
                                        if (success) {
                                            Toast.makeText(
                                                context,
                                                R.string.config_imported_successful,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            navigator.navigateUp()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                R.string.config_import_failed_no_path,
                                                Toast.LENGTH_LONG
                                            ).show()
                                            val intent = Intent(context, LogActivity::class.java)
                                            context.startActivity(intent)
                                        }
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlert = false }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
