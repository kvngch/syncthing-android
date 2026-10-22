package com.nutomic.syncthingandroid.folder

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.RunConditionMonitor
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.FileUtils
import com.nutomic.syncthingandroid.util.Util
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "FolderViewModel"
private const val KEY_FORM = "form"

/**
 * The folder being edited and what the screen tracks around it.
 *
 * @param ignoreList ignore patterns, one per line, null until read (edit mode only).
 * @param folderUri storage access framework tree picked by the user, used to create the folder markers.
 * @param canWrite whether the native binary can write to the folder path. If not, the folder can only
 * send changes.
 */
data class FolderForm(
    val folder: Folder,
    val isCreate: Boolean,
    val needsUpdate: Boolean = false,
    val ignoreList: String? = null,
    val ignoreListChanged: Boolean = false,
    val folderUri: Uri? = null,
    val canWrite: Boolean = false,
    val customSyncConditions: Boolean = false,
    val runScript: Boolean = false,
)

/**
 * Holds the edited folder across configuration changes and process death.
 */
class FolderViewModel(application: Application, private val savedState: SavedStateHandle) :
    AndroidViewModel(application) {

    private val gson = Gson()
    private val app get() = getApplication<Application>()
    private val _form = MutableStateFlow(savedState.get<Bundle>(KEY_FORM)?.let(::fromBundle))
    val form: StateFlow<FolderForm?> = _form.asStateFlow()

    /**
     * Result code to finish the screen with, once a new folder has been written.
     */
    private val _result = MutableStateFlow<Int?>(null)
    val result: StateFlow<Int?> = _result.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        savedState.setSavedStateProvider(KEY_FORM) { _form.value?.let(::toBundle) ?: Bundle() }
    }

    /**
     * Sets the initial form, unless one was restored.
     */
    fun start(form: FolderForm) {
        if (_form.value != null) {
            return
        }
        _form.value = form
        checkWriteAccess(form.folder.path)
        if (!form.isCreate) {
            readIgnoreList(form.folder)
        }
    }

    /**
     * Applies [change] and marks the folder as modified.
     */
    fun edit(change: (FolderForm) -> FolderForm) {
        _form.update { form -> form?.let(change)?.copy(needsUpdate = true) }
    }

    /**
     * Applies [change] to a copy of the folder: the previous state keeps its own instance.
     */
    fun editFolder(change: Folder.() -> Unit) {
        edit { it.copy(folder = gson.fromJson(gson.toJson(it.folder), Folder::class.java).apply(change)) }
    }

    fun setPath(path: String, uri: Uri?) {
        edit { it.copy(folderUri = uri) }
        editFolder { this.path = path }
        checkWriteAccess(path)
    }

    /**
     * Without write access, Syncthing can only send changes from the folder.
     */
    private fun checkWriteAccess(path: String?) {
        if (path.isNullOrEmpty()) {
            return
        }
        viewModelScope.launch {
            val canWrite = withContext(Dispatchers.IO) { Util.nativeBinaryCanWriteToPath(app, path) }
            _form.update { it?.copy(canWrite = canWrite) }
            if (!canWrite && _form.value?.folder?.type != Constants.FOLDER_TYPE_SEND_ONLY) {
                editFolder { type = Constants.FOLDER_TYPE_SEND_ONLY }
            }
        }
    }

    private fun readIgnoreList(folder: Folder) {
        viewModelScope.launch(Dispatchers.IO) {
            // Syncthing may not run yet: read the file next to the folder.
            ConfigRouter(app).getFolderIgnoreList(null, folder) { ignoreList ->
                _form.update { it?.copy(ignoreList = ignoreList.ignore?.joinToString("\n").orEmpty()) }
            }
        }
    }

    /**
     * Writes the folder markers, adds the folder, starts syncing it, then sets [result].
     */
    fun create(api: RestApi?) {
        val form = _form.value ?: return
        _saving.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                preCreateFolderStruct(form.folderUri, form.folder.path)
                ConfigRouter(app).addFolder(api, form.folder)
            }
            LocalBroadcastManager.getInstance(app).sendBroadcast(
                Intent(RunConditionMonitor.ACTION_SYNC_TRIGGER_FIRED)
                    .putExtra(RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, true)
            )
            _result.value = Activity.RESULT_OK
        }
    }

    /**
     * Syncthing creates the ".stfolder" marker itself, but cannot with readonly access to a send
     * only folder: create it here, with a file in it as some cleaners wipe empty directories.
     * ".stversions" gets a ".nomedia" to keep versions out of the gallery.
     */
    private fun preCreateFolderStruct(folderUri: Uri?, absolutePath: String) {
        val markerName = Folder().markerName
        val doNotDelete = "DO_NOT_DELETE"
        if (folderUri == null) {
            // The built-in folder picker only returns an absolute path.
            Log.w(TAG, "preCreateFolderStruct: no tree uri, using the absolute path.")
            try {
                val marker = File(absolutePath, markerName)
                marker.mkdirs()
                val doNotDeleteFile = File(marker, doNotDelete)
                if (doNotDeleteFile.createNewFile()) {
                    doNotDeleteFile.writeText(doNotDelete)
                }
                val versions = File(absolutePath, Constants.FOLDER_NAME_STVERSIONS)
                versions.mkdirs()
                File(versions, ".nomedia").createNewFile()
            } catch (e: Exception) {
                Log.e(TAG, "preCreateFolderStruct: Failed to create using absolute path.", e)
            }
            return
        }
        val folder = DocumentFile.fromTreeUri(app, folderUri) ?: return
        FileUtils.safCreateDirectory(folder, markerName)?.let {
            FileUtils.safCreateFile(app, it, "$doNotDelete.txt", doNotDelete)
        }
        FileUtils.safCreateDirectory(folder, Constants.FOLDER_NAME_STVERSIONS)?.let {
            FileUtils.safCreateFile(app, it, ".nomedia", "")
        }
    }

    private fun toBundle(form: FolderForm) = bundleOf(
        "folder" to gson.toJson(form.folder),
        "isCreate" to form.isCreate,
        "needsUpdate" to form.needsUpdate,
        "ignoreList" to form.ignoreList,
        "ignoreListChanged" to form.ignoreListChanged,
        "folderUri" to form.folderUri,
        "canWrite" to form.canWrite,
        "customSyncConditions" to form.customSyncConditions,
        "runScript" to form.runScript,
    )

    private fun fromBundle(bundle: Bundle): FolderForm? {
        val folder = bundle.getString("folder") ?: return null
        return FolderForm(
            folder = gson.fromJson(folder, Folder::class.java),
            isCreate = bundle.getBoolean("isCreate"),
            needsUpdate = bundle.getBoolean("needsUpdate"),
            ignoreList = bundle.getString("ignoreList"),
            ignoreListChanged = bundle.getBoolean("ignoreListChanged"),
            folderUri = BundleCompat.getParcelable(bundle, "folderUri", Uri::class.java),
            canWrite = bundle.getBoolean("canWrite"),
            customSyncConditions = bundle.getBoolean("customSyncConditions"),
            runScript = bundle.getBoolean("runScript"),
        )
    }
}
