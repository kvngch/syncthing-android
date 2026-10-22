package com.nutomic.syncthingandroid.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.Util
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Allows selecting a directory in the local file system, when the system picker cannot.
 */
class FolderPickerActivity : ThemedAppCompatActivity() {

    /**
     * A directory entry, typed off the main thread.
     */
    private class Entry(val file: File, val isDirectory: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val roots = roots()
        // With a single root, its content is shown instead of the list of roots.
        val initial = intent.getStringExtra(EXTRA_INITIAL_DIRECTORY) ?: roots.singleOrNull()?.path
        setContent {
            ApplicationTheme {
                FolderPickerScreen(
                    roots = roots,
                    initial = initial,
                    onSelect = { directory ->
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(EXTRA_RESULT_DIRECTORY, Util.formatPath(directory.absolutePath)),
                        )
                        finish()
                    },
                    onCancel = ::finish,
                )
            }
        }
    }

    /**
     * The root directory requested by [createIntent], or else every storage and public directory
     * that exists. The legacy storage paths stay listed: this picker is the fallback for what the
     * system picker hides.
     */
    @Suppress("DEPRECATION")
    private fun roots(): List<File> {
        val rootDirectory = intent.getStringExtra(EXTRA_ROOT_DIRECTORY)
        val candidates = if (!rootDirectory.isNullOrEmpty()) {
            listOf(File(rootDirectory))
        } else {
            buildList {
                // getExternalFilesDirs may return null for an ejected SD card.
                val appFiles = getExternalFilesDir(null)
                addAll(getExternalFilesDirs(null).filterNotNull().filter { it != appFiles })
                add(Environment.getExternalStorageDirectory())
                listOf(
                    Environment.DIRECTORY_MOVIES,
                    Environment.DIRECTORY_MUSIC,
                    Environment.DIRECTORY_PICTURES,
                    Environment.DIRECTORY_DOWNLOADS,
                    Environment.DIRECTORY_DCIM,
                    Environment.DIRECTORY_DOCUMENTS,
                ).forEach { add(Environment.getExternalStoragePublicDirectory(it)) }
                // Paths where we might have read-only access.
                FileUtils.getMountedStoragePathsAsFileArray()?.let { addAll(it) }
                add(File("/"))
            }
        }
        return candidates.filter { it.isDirectory }.toSortedSet().toList()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FolderPickerScreen(
        roots: List<File>,
        initial: String?,
        onSelect: (File) -> Unit,
        onCancel: () -> Unit,
    ) {
        val context = LocalContext.current
        // Null shows the list of roots.
        var path by rememberSaveable { mutableStateOf(initial) }
        var creating by rememberSaveable { mutableStateOf(false) }
        val location = path?.let(::File)
        val entries by produceState<List<Entry>?>(null, path) {
            value = if (location == null) {
                roots.map { Entry(it, true) }
            } else {
                withContext(Dispatchers.IO) { list(location) }
            }
        }
        // Up from a root leads to the list of roots, when there are several.
        val canGoUp = location != null && (location !in roots || roots.size > 1)

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.folder_picker_title)) },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        if (location != null) {
                            IconButton(onClick = { creating = true }) {
                                Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.create_fs_folder))
                            }
                            IconButton(
                                onClick = { path = if (location in roots) null else location.parent },
                                enabled = canGoUp,
                            ) {
                                Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.folder_go_up))
                            }
                            IconButton(onClick = { onSelect(location) }) {
                                Icon(Icons.Outlined.Done, contentDescription = stringResource(R.string.select_folder))
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    text = if (location == null) {
                        stringResource(R.string.advanced_storage_path_overview)
                    } else {
                        stringResource(R.string.current_path, location.absolutePath)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                HorizontalDivider()
                val current = entries
                when {
                    current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    current.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.directory_empty))
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(current, key = { it.file.path }) { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        if (location == null) entry.file.absolutePath else entry.file.name,
                                        fontStyle = if (entry.isDirectory) FontStyle.Normal else FontStyle.Italic,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        if (entry.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
                                        contentDescription = null,
                                    )
                                },
                                modifier = if (entry.isDirectory) {
                                    Modifier.clickable { path = entry.file.path }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
            }
        }

        if (creating && location != null) {
            CreateFolderDialog(
                onConfirm = { name ->
                    creating = false
                    val folder = File(location, name)
                    if (folder.mkdir()) {
                        path = folder.path
                    } else {
                        Toast.makeText(context, R.string.create_folder_failed, Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { creating = false },
            )
        }
    }

    @Composable
    private fun CreateFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.create_folder)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(name) }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    companion object {
        private const val EXTRA_INITIAL_DIRECTORY = ".activities.FolderPickerActivity.INITIAL_DIRECTORY"

        /**
         * If requested by [createIntent], the user stays within that single root.
         */
        private const val EXTRA_ROOT_DIRECTORY = ".activities.FolderPickerActivity.ROOT_DIRECTORY"

        const val EXTRA_RESULT_DIRECTORY = ".activities.FolderPickerActivity.RESULT_DIRECTORY"

        const val DIRECTORY_REQUEST_CODE = 234

        @JvmStatic
        fun createIntent(context: Context, initialDirectory: String?, rootDirectory: String?): Intent {
            val intent = Intent(context, FolderPickerActivity::class.java)
            if (!initialDirectory.isNullOrEmpty()) {
                intent.putExtra(EXTRA_INITIAL_DIRECTORY, initialDirectory)
            }
            if (!rootDirectory.isNullOrEmpty()) {
                intent.putExtra(EXTRA_ROOT_DIRECTORY, rootDirectory)
            }
            return intent
        }

        /**
         * Directories first, then files, by name regardless of case. A directory that cannot be read
         * lists nothing.
         */
        private fun list(directory: File): List<Entry> =
            directory.listFiles().orEmpty()
                .map { Entry(it, it.isDirectory) }
                .sortedWith(compareBy<Entry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name })
    }
}
