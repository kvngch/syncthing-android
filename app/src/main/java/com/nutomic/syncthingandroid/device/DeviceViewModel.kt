package com.nutomic.syncthingandroid.device

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val KEY_FORM = "form"

/**
 * Lets Syncthing find the device's addresses by itself.
 */
internal val DYNAMIC_ADDRESS = listOf("dynamic")

/**
 * Addresses typed by the user, separated by commas, semicolons or spaces, e.g.
 * "tcp4://192.168.1.67:2222, dynamic". Empty means dynamic.
 */
internal fun parseAddresses(text: String): List<String> =
    text.split(',', ';', ' ', '\t', '\n').filter { it.isNotBlank() }.ifEmpty { DYNAMIC_ADDRESS }

/**
 * Whether a folder is shared with the device, and the password that encrypts its data there.
 */
data class FolderShare(val shared: Boolean, val password: String)

/**
 * The device being edited and what the screen tracks around it.
 *
 * @param addresses as typed, comma separated: parsed into [Device.addresses] on save, so that typing
 * a separator does not reformat the field.
 * @param shares by folder ID, filled as the folders are read: edits survive a reload.
 */
data class DeviceForm(
    val device: Device,
    val isCreate: Boolean,
    val addresses: String,
    val needsUpdate: Boolean = false,
    val shares: Map<String, FolderShare> = emptyMap(),
    val customSyncConditions: Boolean = false,
)

/**
 * Holds the edited device across configuration changes and process death.
 */
class DeviceViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    private val gson = Gson()
    private val _form = MutableStateFlow(savedState.get<Bundle>(KEY_FORM)?.let(::fromBundle))
    val form: StateFlow<DeviceForm?> = _form.asStateFlow()

    /**
     * The folders the device can share, null until read.
     */
    private val _folders = MutableStateFlow<List<Folder>?>(null)
    val folders: StateFlow<List<Folder>?> = _folders.asStateFlow()

    init {
        savedState.setSavedStateProvider(KEY_FORM) { _form.value?.let(::toBundle) ?: Bundle() }
    }

    /**
     * Sets the initial form, unless one was restored.
     */
    fun start(form: DeviceForm) {
        if (_form.value == null) {
            _form.value = form
        }
    }

    /**
     * Applies [change] and marks the device as modified.
     */
    fun edit(change: (DeviceForm) -> DeviceForm) {
        _form.update { form -> form?.let(change)?.copy(needsUpdate = true) }
    }

    /**
     * Applies [change] to a copy of the device: the previous state keeps its own instance.
     */
    fun editDevice(change: Device.() -> Unit) {
        edit { it.copy(device = gson.fromJson(gson.toJson(it.device), Device::class.java).apply(change)) }
    }

    /**
     * Reads the folders again. Folders not seen before take their sharing from the config.
     */
    fun loadFolders(read: suspend () -> List<Folder>) {
        viewModelScope.launch {
            val folders = read()
            _folders.value = folders
            _form.update { form ->
                form?.copy(
                    shares = folders.associate { folder ->
                        val id = folder.id.orEmpty()
                        val share = form.shares[id] ?: folder.getDevice(form.device.deviceID).let {
                            FolderShare(shared = it != null, password = it?.encryptionPassword.orEmpty())
                        }
                        id to share
                    }
                )
            }
        }
    }

    private fun toBundle(form: DeviceForm) = bundleOf(
        "device" to gson.toJson(form.device),
        "isCreate" to form.isCreate,
        "addresses" to form.addresses,
        "needsUpdate" to form.needsUpdate,
        "shares" to bundleOf(
            *form.shares.map { (id, share) ->
                id to bundleOf("shared" to share.shared, "password" to share.password)
            }.toTypedArray()
        ),
        "customSyncConditions" to form.customSyncConditions,
    )

    private fun fromBundle(bundle: Bundle): DeviceForm? {
        val device = bundle.getString("device") ?: return null
        val shares = bundle.getBundle("shares")
        return DeviceForm(
            device = gson.fromJson(device, Device::class.java),
            isCreate = bundle.getBoolean("isCreate"),
            addresses = bundle.getString("addresses").orEmpty(),
            needsUpdate = bundle.getBoolean("needsUpdate"),
            shares = shares?.keySet().orEmpty().associateWith { id ->
                val share = shares?.getBundle(id)
                FolderShare(share?.getBoolean("shared") ?: false, share?.getString("password").orEmpty())
            },
            customSyncConditions = bundle.getBoolean("customSyncConditions"),
        )
    }
}
