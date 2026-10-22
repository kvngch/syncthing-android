package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.Util
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Copies the files another app shares into a Syncthing folder.
 */
class ShareActivity : SyncthingActivity() {

    private val sharedPreferences: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private lateinit var config: ConfigRouter

    /**
     * Shared content and the file name it gets in the folder, in the order it was shared.
     */
    private lateinit var files: List<Pair<Uri, String>>

    private var folders by mutableStateOf<List<Folder>?>(null)
    private var selectedFolderId by mutableStateOf<String?>(null)
    private var copying by mutableStateOf(false)

    /**
     * Bumped when a sub directory was picked, to read it again from the preferences.
     */
    private var subDirectoryVersion by mutableIntStateOf(0)

    private val pickSubDirectory = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folder = selectedFolder() ?: return@registerForActivityResult
        val picked = result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)
        if (result.resultCode == RESULT_OK && picked != null) {
            // Only the part under the folder is shown and stored.
            val subDirectory = picked.replace(Util.formatPath(folder.path.orEmpty()), "")
            sharedPreferences.edit { putString(PREF_FOLDER_SAVED_SUBDIRECTORY + folder.id, subDirectory) }
            subDirectoryVersion++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        config = ConfigRouter(this)

        val shared = sharedUris()
        if (shared.isEmpty()) {
            Toast.makeText(this, R.string.nothing_share, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        files = shared.map { uri -> uri to (displayName(uri) ?: newFileName()) }
        selectedFolderId = savedInstanceState?.getString(KEY_SELECTED_FOLDER)
            ?: sharedPreferences.getString(PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, null)

        setContent {
            ApplicationTheme {
                ShareScreen()
            }
        }
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        if (folders != null) {
            return
        }
        val api = api
        lifecycleScope.launch {
            val read = try {
                if (api != null && api.isConfigLoaded) {
                    config.getFolders(api)
                } else {
                    withContext(Dispatchers.IO) { config.getFolders(null) }
                }
            } catch (e: ConfigXml.OpenConfigException) {
                Toast.makeText(this@ShareActivity, R.string.complete_welcome_wizard_first, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            folders = read
            if (read.none { it.id == selectedFolderId }) {
                selectedFolderId = read.firstOrNull()?.id
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_FOLDER, selectedFolderId)
    }

    override fun onPause() {
        super.onPause()
        selectedFolderId?.let { id ->
            sharedPreferences.edit { putString(PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER, id) }
        }
    }

    private fun selectedFolder(): Folder? = folders?.firstOrNull { it.id == selectedFolderId }

    private fun savedSubDirectory(folder: Folder?): String =
        folder?.let { sharedPreferences.getString(PREF_FOLDER_SAVED_SUBDIRECTORY + it.id, "") }.orEmpty()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ShareScreen() {
        // A single file can be renamed; several keep their names.
        var name by rememberSaveable { mutableStateOf(files.joinToString("\n") { it.second }) }
        val folder = selectedFolder()
        val subDirectory = remember(folder?.id, subDirectoryVersion) { savedSubDirectory(folder) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.share_activity_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel_title))
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(pluralStringResource(R.plurals.file_name_title, if (files.size > 1) 2 else 1)) },
                    readOnly = files.size > 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                FolderChoice(folders.orEmpty(), folder) { selectedFolderId = it.id }
                ListItem(
                    overlineContent = { Text(stringResource(R.string.sub_folder)) },
                    headlineContent = { Text(subDirectory.ifEmpty { stringResource(R.string.no_sub_folder_is_selected) }) },
                    trailingContent = {
                        OutlinedButton(
                            enabled = folder != null,
                            onClick = {
                                val selected = folder ?: return@OutlinedButton
                                pickSubDirectory.launch(
                                    FolderPickerActivity.createIntent(
                                        applicationContext,
                                        File(selected.path, subDirectory).absolutePath,
                                        selected.path,
                                    )
                                )
                            },
                        ) {
                            Text(stringResource(R.string.browse))
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    OutlinedButton(onClick = ::finish) { Text(stringResource(R.string.cancel_title)) }
                    Button(
                        enabled = folder != null && !copying,
                        onClick = {
                            val selected = folder ?: return@Button
                            copy(if (files.size == 1) listOf(files[0].first to name) else files, selected, subDirectory)
                        },
                    ) {
                        Text(stringResource(R.string.save_title))
                    }
                }
            }
        }

        if (copying) {
            AlertDialog(
                onDismissRequest = {},
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.copy_progress))
                    }
                },
                confirmButton = {},
            )
        }
    }

    @Composable
    private fun FolderChoice(folders: List<Folder>, selected: Folder?, onSelect: (Folder) -> Unit) {
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            ListItem(
                overlineContent = { Text(stringResource(R.string.folder_title)) },
                headlineContent = { Text(selected?.toString().orEmpty()) },
                trailingContent = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.clickable(enabled = folders.isNotEmpty()) { menuOpen = true },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                folders.forEach { folder ->
                    DropdownMenuItem(
                        text = { Text(folder.toString()) },
                        onClick = {
                            menuOpen = false
                            onSelect(folder)
                        },
                    )
                }
            }
        }
    }

    /**
     * Copies the files off the main thread, reports how it went, then leaves.
     */
    private fun copy(toCopy: List<Pair<Uri, String>>, folder: Folder, subDirectory: String) {
        val directory = File(folder.path, subDirectory)
        val allowOverwrite = sharedPreferences.getBoolean(Constants.PREF_ALLOW_OVERWRITE_FILES, false)
        copying = true
        lifecycleScope.launch {
            var copied = 0
            var ignored = 0
            var failed = false
            withContext(Dispatchers.IO) {
                toCopy.forEach { (uri, name) ->
                    val target = File(directory, name)
                    if (target.isFile && !allowOverwrite) {
                        ignored++
                        return@forEach
                    }
                    try {
                        val input = contentResolver.openInputStream(uri) ?: throw IOException("No stream for $uri")
                        input.use { source -> target.outputStream().use { source.copyTo(it) } }
                        copied++
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to copy \"$uri\" to \"$target\"", e)
                        failed = true
                    }
                }
            }
            copying = false
            if (failed) {
                Toast.makeText(this@ShareActivity, R.string.copy_exception, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val message = if (ignored > 0) {
                resources.getQuantityString(R.plurals.copy_success_partially, copied, copied, folder.label, ignored)
            } else {
                resources.getQuantityString(R.plurals.copy_success, copied, copied, folder.label)
            }
            Toast.makeText(this@ShareActivity, message, Toast.LENGTH_LONG).show()
            if (sharedPreferences.getBoolean(Constants.PREF_RUN_ON_TIME_SCHEDULE, false)) {
                // Syncthing waits for its next time window: open one now.
                LocalBroadcastManager.getInstance(this@ShareActivity).sendBroadcast(
                    Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
                        .putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
                )
            }
            finish()
        }
    }

    private fun sharedUris(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    /**
     * The name the content was shared under, given the extension of its type if it lacks one.
     * Path separators become dashes.
     */
    private fun displayName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return uri.lastPathSegment?.replace("/", "-")
        }
        val queried = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not retrieve display name for $uri", e)
            null
        }
        var name = queried ?: uri.lastPathSegment?.replace(Regex("\\s"), "") ?: return null
        val extensionKnown = name.substringAfterLast('.', "").let {
            it.isNotEmpty() && MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) != null
        }
        if (!extensionKnown) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri))?.let { name += ".$it" }
        }
        return name.replace("/", "-")
    }

    private fun newFileName(): String =
        getString(R.string.file_name_template, DateFormat.getDateTimeInstance().format(Date()))

    companion object {
        private const val TAG = "ShareActivity"
        private const val KEY_SELECTED_FOLDER = "selectedFolder"
        private const val PREF_PREVIOUSLY_SELECTED_SYNCTHING_FOLDER = "previously_selected_syncthing_folder"

        const val PREF_FOLDER_SAVED_SUBDIRECTORY = "saved_sub_directory_"
    }
}
