package com.nutomic.syncthingandroid.activities

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.SyncthingApp
import com.nutomic.syncthingandroid.folder.FolderForm
import com.nutomic.syncthingandroid.folder.FolderScreen
import com.nutomic.syncthingandroid.folder.FolderViewModel
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.Util
import jakarta.inject.Inject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows folder details and allows changing them.
 */
class FolderActivity : SyncthingActivity() {

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    private val viewModel: FolderViewModel by viewModels()
    private lateinit var config: ConfigRouter

    /**
     * Bumped when a device was added from this screen, to read the devices again.
     */
    private var devicesVersion by mutableIntStateOf(0)

    private val pickTree = registerForActivityResult(
        object : ActivityResultContracts.OpenDocumentTree() {
            override fun createIntent(context: Context, input: Uri?): Intent =
                super.createIntent(context, input)
                    .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    .putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
    ) { uri -> if (uri != null) onTreePicked(uri) }

    private val pickDirectoryInApp = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = result.data?.getStringExtra(FolderPickerActivity.EXTRA_RESULT_DIRECTORY)
        if (result.resultCode == RESULT_OK && path != null) {
            viewModel.setPath(FileUtils.cutTrailingSlash(path).orEmpty(), null)
        }
    }

    private val addDevice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            devicesVersion++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        (application as SyncthingApp).component().inject(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        config = ConfigRouter(this)

        if (viewModel.form.value == null) {
            val form = initialForm()
            if (form == null) {
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            viewModel.start(form)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.result.filterNotNull().collect { resultCode ->
                    setResult(resultCode)
                    finish()
                }
            }
        }

        val expertMode = sharedPreferences.getBoolean(Constants.PREF_EXPERT_MODE, false)
        setContent {
            ApplicationTheme {
                val form by viewModel.form.collectAsStateWithLifecycle()
                val saving by viewModel.saving.collectAsStateWithLifecycle()
                val devices by produceState<List<Device>?>(null, devicesVersion) { value = readDevices() }
                form?.let { current ->
                    FolderScreen(
                        form = current,
                        devices = devices,
                        expertMode = expertMode,
                        saving = saving,
                        onEdit = viewModel::edit,
                        onEditFolder = viewModel::editFolder,
                        onPickDirectory = ::pickDirectory,
                        onPickAdvancedDirectory = {
                            pickDirectoryInApp.launch(FolderPickerActivity.createIntent(this, current.folder.path, null))
                        },
                        onAddDevice = { addDevice.launch(DeviceActivity.createIntent(this)) },
                        onOpenSyncConditions = {
                            startActivity(
                                SyncConditionsActivity.createIntent(
                                    this, Constants.PREF_OBJECT_PREFIX_FOLDER + current.folder.id, current.folder.label
                                )
                            )
                        },
                        onSave = ::save,
                        onDelete = ::delete,
                        onClose = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        service?.notificationHandler?.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    /**
     * The folder to create, or the one to edit read from config.xml: the service is not bound yet.
     * Null if it cannot be read.
     */
    private fun initialForm(): FolderForm? {
        val isCreate = intent.getBooleanExtra(EXTRA_IS_CREATE, false)
        val folder = if (isCreate) {
            newFolder()
        } else {
            val id = intent.getStringExtra(EXTRA_FOLDER_ID)
            val folders = try {
                config.getFolders(null)
            } catch (e: ConfigXml.OpenConfigException) {
                Log.e(TAG, "Failed to read the config", e)
                Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_LONG).show()
                return null
            }
            folders.firstOrNull { it.id == id } ?: run {
                Log.w(TAG, "Folder not found in API update, maybe it was deleted?")
                return null
            }
        }
        // Accepting a folder a device offered: share it back with that device.
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        if (deviceId != null) {
            folder.addDevice(SharedWithDevice().apply { deviceID = deviceId })
        }
        return FolderForm(
            folder = folder,
            isCreate = isCreate,
            needsUpdate = isCreate || deviceId != null,
            customSyncConditions = !isCreate && sharedPreferences.getBoolean(
                Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id), false
            ),
            runScript = sharedPreferences.getBoolean(Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(folder.id), false),
        )
    }

    private fun newFolder() = Folder().apply {
        id = intent.getStringExtra(EXTRA_FOLDER_ID) ?: randomFolderId()
        label = intent.getStringExtra(EXTRA_FOLDER_LABEL)?.trim().orEmpty()
        paused = false
        type = if (intent.getBooleanExtra(EXTRA_RECEIVE_ENCRYPTED, false)) {
            Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED
        } else {
            Constants.FOLDER_TYPE_SEND_RECEIVE
        }
        minDiskFree = Folder.MinDiskFree()
        versioning = Folder.Versioning().apply {
            type = "trashcan"
            params["cleanoutDays"] = "14"
            cleanupIntervalS = 0
            fsPath = ""
            fsType = "basic"
        }
    }

    /**
     * Devices the folder can be shared with. config.xml is read off the main thread.
     */
    private suspend fun readDevices(): List<Device> {
        val api = api
        if (api != null && api.isConfigLoaded) {
            return config.getDevices(api, false)
        }
        return withContext(Dispatchers.IO) {
            try {
                config.getDevices(null, false)
            } catch (e: ConfigXml.OpenConfigException) {
                Log.e(TAG, "Failed to read the devices", e)
                emptyList()
            }
        }
    }

    private fun pickDirectory() {
        val initialUri = FileUtils.getExternalFilesDirUri(this, FileUtils.ExternalStorageDirType.INT_MEDIA)
            ?.takeIf { FileUtils.directoryUriExists(this, it) }
            ?: FileUtils.getInternalStorageRootUri()?.takeIf { FileUtils.directoryUriExists(this, it) }
        try {
            pickTree.launch(initialUri)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No document tree picker, falling back to the built-in FolderPickerActivity.", e)
            pickDirectoryInApp.launch(FolderPickerActivity.createIntent(this, viewModel.form.value?.folder?.path, null))
        }
    }

    private fun onTreePicked(uri: Uri) {
        // The folder path, unix style, e.g. "/storage/0000-0000/DCIM".
        val path = FileUtils.getAbsolutePathFromSAFUri(this, uri)?.let(Util::formatPath)
        if (path.isNullOrEmpty() || path == File.separator) {
            viewModel.setPath("", null)
            Toast.makeText(this, R.string.toast_invalid_folder_selected, Toast.LENGTH_LONG).show()
            return
        }
        viewModel.setPath(FileUtils.cutTrailingSlash(path).orEmpty(), uri)
    }

    private fun save() {
        val form = viewModel.form.value ?: return
        if (viewModel.saving.value) {
            return
        }
        val folder = form.folder
        folder.label = folder.label.trim()
        val error = when {
            folder.id.isNullOrEmpty() -> R.string.folder_id_required
            folder.label.isNullOrEmpty() -> R.string.folder_label_required
            folder.path.isNullOrEmpty() -> R.string.folder_path_required
            else -> null
        }
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        if (form.isCreate) {
            val exists = try {
                config.getFolders(api).any { it.id == folder.id }
            } catch (e: ConfigXml.OpenConfigException) {
                Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_LONG).show()
                return
            }
            if (exists) {
                Toast.makeText(this, R.string.folder_id_exists, Toast.LENGTH_LONG).show()
                return
            }
        }

        sharedPreferences.edit { putBoolean(Constants.DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(folder.id), form.runScript) }
        if (form.isCreate) {
            // Finishes through viewModel.result once the folder is written.
            viewModel.create(api)
            return
        }
        if (!form.needsUpdate) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        sharedPreferences.edit {
            putBoolean(
                Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_FOLDER + folder.id),
                form.customSyncConditions,
            )
        }
        val api = api
        if (form.ignoreListChanged) {
            config.postFolderIgnoreList(api, folder, form.ignoreList.orEmpty().split("\n").toTypedArray())
        }
        config.updateFolder(api, folder)
        setResult(RESULT_OK)
        finish()
    }

    private fun delete() {
        val folder = viewModel.form.value?.folder ?: return
        config.removeFolder(api, folder.id)
        if (folder.id == Constants.syncthingCameraFolderId) {
            // Remove the consent to the "Syncthing Camera" feature.
            sharedPreferences.edit { putBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false) }
        }
        finish()
    }

    companion object {
        private const val TAG = "FolderActivity"

        const val EXTRA_DEVICE_ID = ".activities.FolderActivity.DEVICE_ID"
        const val EXTRA_FOLDER_ID = ".activities.FolderActivity.FOLDER_ID"
        const val EXTRA_FOLDER_LABEL = ".activities.FolderActivity.FOLDER_LABEL"
        const val EXTRA_IS_CREATE = ".activities.FolderActivity.IS_CREATE"
        const val EXTRA_NOTIFICATION_ID = ".activities.FolderActivity.NOTIFICATION_ID"
        const val EXTRA_RECEIVE_ENCRYPTED = ".activities.FolderActivity.RECEIVE_ENCRYPTED"
        const val EXTRA_REMOTE_ENCRYPTED = ".activities.FolderActivity.REMOTE_ENCRYPTED"

        const val FOLDER_ADD_CODE = 402

        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, FolderActivity::class.java).putExtra(EXTRA_IS_CREATE, true)

        /**
         * Ten random lowercase letters and digits, as "xxxxx-xxxxx".
         */
        private fun randomFolderId(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val id = (1..10).map { chars.random() }.joinToString("")
            return id.take(5) + "-" + id.drop(5)
        }
    }
}
