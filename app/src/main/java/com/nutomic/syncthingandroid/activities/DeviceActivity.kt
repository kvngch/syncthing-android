package com.nutomic.syncthingandroid.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.device.DeviceForm
import com.nutomic.syncthingandroid.device.DeviceScreen
import com.nutomic.syncthingandroid.device.DeviceViewModel
import com.nutomic.syncthingandroid.device.Discovery
import com.nutomic.syncthingandroid.device.FoundDevice
import com.nutomic.syncthingandroid.device.DYNAMIC_ADDRESS
import com.nutomic.syncthingandroid.device.parseAddresses
import com.nutomic.syncthingandroid.device.DeviceIdDialog
import com.nutomic.syncthingandroid.model.Connection
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.theme.ApplicationTheme
import com.nutomic.syncthingandroid.util.ConfigRouter
import com.nutomic.syncthingandroid.util.ConfigXml
import com.nutomic.syncthingandroid.util.Util
import com.nutomic.syncthingandroid.util.defaultPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows device details and allows changing them.
 */
class DeviceActivity : SyncthingActivity() {

    private val sharedPreferences: SharedPreferences by lazy { defaultPreferences }

    private val viewModel: DeviceViewModel by viewModels()
    private lateinit var config: ConfigRouter

    /**
     * Devices found on the local network, create mode only.
     */
    private var discovery by mutableStateOf<Discovery?>(null)

    /**
     * The current connection to the device, edit mode only.
     */
    private var connection by mutableStateOf<Connection?>(null)

    private val scanQrCode = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val deviceId = result.data?.getStringExtra(QRScannerActivity.QR_RESULT_ARG)
        if (result.resultCode == RESULT_OK && deviceId != null) {
            viewModel.editDevice { deviceID = deviceId }
        }
    }

    private val addFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            loadFolders()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (viewModel.folders.value == null) {
            loadFolders()
        }

        val expertMode = sharedPreferences.getBoolean(Constants.PREF_EXPERT_MODE, false)
        val canScanQrCode = !Util.isRunningOnTV(this)
        setContent {
            ApplicationTheme {
                val form by viewModel.form.collectAsStateWithLifecycle()
                val folders by viewModel.folders.collectAsStateWithLifecycle()
                // Shown with the device's ID, kept across a rotation.
                var showDeviceId by rememberSaveable { mutableStateOf(false) }
                form?.let { current ->
                    DeviceScreen(
                        form = current,
                        folders = folders,
                        discovery = discovery,
                        connection = connection,
                        expertMode = expertMode,
                        canScanQrCode = canScanQrCode,
                        onEdit = viewModel::edit,
                        onEditDevice = viewModel::editDevice,
                        onScanQrCode = { scanQrCode.launch(QRScannerActivity.intent(this)) },
                        onSearchDevices = ::searchDevices,
                        onShareDeviceId = { showDeviceId = true },
                        onAddFolder = { addFolder.launch(FolderActivity.createIntent(this)) },
                        onOpenSyncConditions = {
                            startActivity(
                                SyncConditionsActivity.createIntent(
                                    this,
                                    Constants.PREF_OBJECT_PREFIX_DEVICE + current.device.deviceID,
                                    current.device.name,
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
                    if (showDeviceId) {
                        DeviceIdDialog(
                            current.device.displayName.trim(),
                            current.device.deviceID.orEmpty(),
                            isCurrentDevice = false,
                        ) { showDeviceId = false }
                    }
                }
            }
        }
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        super.onServiceConnected(componentName, iBinder)
        service?.notificationHandler?.cancelConsentNotification(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        val api = api ?: return
        val form = viewModel.form.value ?: return
        if (form.isCreate) {
            searchDevices()
        } else {
            // From the cache: empty until the device was seen.
            connection = api.getRemoteDeviceStatus(form.device.deviceID).takeIf { it.at.isNotEmpty() }
        }
    }

    /**
     * The device to add, or the one to edit read from config.xml: the service is not bound yet.
     * Null if it cannot be read.
     */
    private fun initialForm(): DeviceForm? {
        val isCreate = intent.getBooleanExtra(EXTRA_IS_CREATE, false)
        val device = if (isCreate) {
            Device().apply {
                // Set when accepting a device that asked to connect.
                name = intent.getStringExtra(EXTRA_DEVICE_NAME).orEmpty()
                deviceID = intent.getStringExtra(EXTRA_DEVICE_ID)
                addresses = DYNAMIC_ADDRESS
            }
        } else {
            val id = intent.getStringExtra(EXTRA_DEVICE_ID)
            val devices = try {
                config.getDevices(null, false)
            } catch (e: ConfigXml.OpenConfigException) {
                Log.e(TAG, "Failed to read the config", e)
                Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_LONG).show()
                return null
            }
            devices.firstOrNull { it.deviceID == id } ?: run {
                Log.w(TAG, "Device not found in API update, maybe it was deleted?")
                return null
            }
        }
        return DeviceForm(
            device = device,
            isCreate = isCreate,
            addresses = device.addresses.orEmpty().joinToString(", "),
            needsUpdate = isCreate,
            customSyncConditions = !isCreate && sharedPreferences.getBoolean(
                Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID),
                false,
            ),
        )
    }

    /**
     * Folders the device can share. config.xml is read off the main thread.
     */
    private fun loadFolders() {
        viewModel.loadFolders {
            val api = api
            if (api != null && api.isConfigLoaded) {
                config.getFolders(api)
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        config.getFolders(null)
                    } catch (e: ConfigXml.OpenConfigException) {
                        Log.e(TAG, "Failed to read the folders", e)
                        emptyList()
                    }
                }
            }
        }
    }

    /**
     * Asks Syncthing which devices announced themselves on the local network.
     */
    private fun searchDevices() {
        val api = api
        if (api == null || !api.isConfigLoaded) {
            return
        }
        api.getDiscoveredDevices { discovered ->
            if (discovered == null) {
                Log.e(TAG, "onReceiveDiscoveredDevices: discoveredDevices == null")
                return@getDiscoveredDevices
            }
            discovery = Discovery(
                devices = discovered.mapNotNull { (deviceId, found) ->
                    deviceId?.let { FoundDevice(it, found?.addresses?.joinToString(", ").orEmpty()) }
                },
                localDiscoveryEnabled = config.getOptions(null).localAnnounceEnabled,
            )
        }
    }

    private fun save() {
        val form = viewModel.form.value ?: return
        val device = form.device
        device.addresses = parseAddresses(form.addresses)
        val error = when {
            device.deviceID.isNullOrEmpty() -> R.string.device_id_required
            !device.checkDeviceID() -> R.string.device_id_invalid
            !device.checkDeviceAddresses() -> R.string.device_addresses_invalid
            else -> null
        }
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        val api = api
        if (form.isCreate) {
            // Adding a device already in the config would silently overwrite it.
            val exists = try {
                config.getDevices(api, true).any { it.deviceID == device.deviceID }
            } catch (e: ConfigXml.OpenConfigException) {
                Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_LONG).show()
                return
            }
            if (exists) {
                Toast.makeText(this, R.string.device_id_exists, Toast.LENGTH_LONG).show()
                return
            }
        }

        // The folders as they are now, not as the screen read them: only those whose sharing with
        // this device the form changes are written, in one config send.
        val folders = try {
            config.getFolders(api)
        } catch (e: ConfigXml.OpenConfigException) {
            Toast.makeText(this, R.string.config_read_failed, Toast.LENGTH_LONG).show()
            return
        }
        val changed = folders.filter { folder -> applyShare(folder, device, form) }
        if (changed.isNotEmpty()) {
            config.updateFolders(api, changed)
        }

        if (form.isCreate) {
            config.updateDevice(api, device)
            setResult(RESULT_OK)
            finish()
            return
        }
        if (!form.needsUpdate) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        sharedPreferences.edit {
            putBoolean(
                Constants.DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(Constants.PREF_OBJECT_PREFIX_DEVICE + device.deviceID),
                form.customSyncConditions,
            )
        }
        config.updateDevice(api, device)
        setResult(RESULT_OK)
        finish()
    }

    /**
     * Shares [folder] with [device] or not, as the form says. True if that changed the folder; a
     * folder the screen has not shown is left as it is.
     */
    private fun applyShare(folder: Folder, device: Device, form: DeviceForm): Boolean {
        val share = form.shares[folder.id.orEmpty()] ?: return false
        val current = folder.getDevice(device.deviceID)
        if (!share.shared) {
            folder.removeDevice(device.deviceID)
            return current != null
        }
        if (current != null && current.encryptionPassword.orEmpty() == share.password) {
            return false
        }
        folder.addDevice(device)
        folder.getDevice(device.deviceID)?.encryptionPassword = share.password
        return true
    }

    private fun delete() {
        val device = viewModel.form.value?.device ?: return
        config.removeDevice(api, device.deviceID)
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val TAG = "DeviceActivity"

        const val EXTRA_NOTIFICATION_ID = ".activities.DeviceActivity.NOTIFICATION_ID"
        const val EXTRA_DEVICE_ID = ".activities.DeviceActivity.DEVICE_ID"
        const val EXTRA_DEVICE_NAME = ".activities.DeviceActivity.DEVICE_NAME"
        const val EXTRA_IS_CREATE = ".activities.DeviceActivity.IS_CREATE"

        const val DEVICE_ADD_CODE = 401

        @JvmStatic
        fun createIntent(context: Context): Intent =
            Intent(context, DeviceActivity::class.java).putExtra(EXTRA_IS_CREATE, true)
    }
}
