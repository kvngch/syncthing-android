package com.nutomic.syncthingandroid.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.IgnoredFolder
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.model.SharedWithDevice
import com.nutomic.syncthingandroid.service.AppPrefs
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.SyncthingRunnable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.mindrot.jbcrypt.BCrypt
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/**
 * Provides direct access to the config.xml file in the file system.
 * This class should only be used if the syncthing API is not available (usually during startup).
 */
class ConfigXml(private val context: Context) {

    class OpenConfigException : RuntimeException()

    private val verboseLog = AppPrefs.getPrefVerboseLog(context)
    private val preferences = context.defaultPreferences
    private val configFile = Constants.getConfigFile(context)
    private lateinit var config: Document

    private val root: Element
        get() = config.documentElement

    private val guiElement: Element?
        get() = root.element("gui")

    fun loadConfig() {
        parseConfig()
        updateIfNeeded()
    }

    /**
     * Parses config.xml without updateIfNeeded, which may save it.
     * For reads while the binary may be running.
     */
    fun loadConfigReadOnly() = parseConfig()

    /**
     * Generates the keys and the config, then sets the app's defaults. Runs the binary twice: off
     * the main thread, as it can load the CPU for more than 30 seconds on older phones.
     */
    fun generateConfig() {
        // Create new secret keys and config.
        Log.i(TAG, "(Re)Generating keys and config.")
        SyncthingRunnable(context, SyncthingRunnable.Command.generate).run(true)
        parseConfig()
        var changed = false

        // Set local device name.
        Log.i(TAG, "Starting syncthing to retrieve local device id.")
        val localDeviceID = getLocalDeviceIDandStoreToPref()
        if (localDeviceID.isNotEmpty()) {
            changed = changeLocalDeviceName(localDeviceID)
        }

        // Change default folder section.
        root.element("defaults")?.element("folder")?.element("versioning")?.let { versioning ->
            versioning.setAttribute("type", "trashcan")
            versioning.appendChild(
                config.createElement("param").apply {
                    setAttribute("key", "cleanoutDays")
                    setAttribute("val", "14")
                }
            )
            changed = true
        }

        /* Section - GUI */
        val gui = guiElement ?: throw OpenConfigException()

        // Set user to "syncthing"
        changed = setConfigElement(gui, "user", "syncthing") || changed

        // Initialize password to the API key
        changed = setConfigElement(gui, "password", BCrypt.hashpw(apiKey, BCrypt.gensalt(4))) || changed
        preferences.edit { putString(Constants.PREF_WEBUI_PASSWORD, apiKey) }

        // Allow debug and release to run in parallel for testing purposes.
        if (Constants.isDebuggable(context)) {
            // Set alternative gui listen port.
            changed = setConfigElement(gui, "address", "127.0.0.1:8385") || changed

            // Set alternative data listen port.
            root.element("options")?.let {
                val addresses = listOf("tcp://:22001", "dynamic+https://relays.syncthing.net/endpoint")
                changed = replaceTextElements(it, "listenAddress", addresses) || changed
            }
        }

        // Save changes if we made any.
        if (changed) {
            saveChanges()
        }
    }

    private fun getLocalDeviceIDfromPref(): String {
        var localDeviceID = preferences.getString(Constants.PREF_LOCAL_DEVICE_ID, null).orEmpty()
        if (localDeviceID.isEmpty()) {
            Log.d(TAG, "getLocalDeviceIDfromPref: Local device ID unavailable, trying to retrieve it from syncthing ...")
            try {
                localDeviceID = getLocalDeviceIDandStoreToPref()
            } catch (e: SyncthingRunnable.ExecutableNotFoundException) {
                Log.e(TAG, "getLocalDeviceIDfromPref: Failed to execute syncthing core")
            }
            if (localDeviceID.isEmpty()) {
                Log.e(TAG, "getLocalDeviceIDfromPref: Local device ID unavailable")
            }
        }
        return localDeviceID
    }

    private fun getLocalDeviceIDandStoreToPref(): String {
        val logOutput = SyncthingRunnable(context, SyncthingRunnable.Command.deviceid).run(true)
        val localDeviceID = logOutput.replace("\n", "")

        // Verify that local device ID is correctly formatted.
        if (!Device().apply { deviceID = localDeviceID }.checkDeviceID()) {
            Log.w(TAG, "getLocalDeviceIDandStoreToPref: Syncthing core returned a bad formatted device ID \"$localDeviceID\"")
            return ""
        }

        // Store local device ID to pref. This saves us expensive calls to the syncthing binary if we need it later.
        preferences.edit { putString(Constants.PREF_LOCAL_DEVICE_ID, localDeviceID) }
        Log.d(TAG, "getLocalDeviceIDandStoreToPref: Cached local device ID \"$localDeviceID\"")
        return localDeviceID
    }

    private fun parseConfig() {
        if (!configFile.canRead()) {
            Log.w(TAG, "Failed to open config file '$configFile'")
            throw OpenConfigException()
        }
        try {
            config = configFile.inputStream().use { input ->
                val source = InputSource(input.reader(Charsets.UTF_8)).apply { encoding = "UTF-8" }
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source)
            }
        } catch (e: SAXException) {
            throw parseFailure(e)
        } catch (e: ParserConfigurationException) {
            throw parseFailure(e)
        } catch (e: IOException) {
            throw parseFailure(e)
        }
    }

    private fun parseFailure(e: Exception): OpenConfigException {
        Log.w(TAG, "Failed to parse config file '$configFile'", e)
        return OpenConfigException()
    }

    val webGuiUrl: URL
        get() {
            val address = guiElement?.text("address") ?: throw OpenConfigException()
            return try {
                URL("https://$address")
            } catch (e: MalformedURLException) {
                throw RuntimeException("Failed to parse web interface URL", e)
            }
        }

    val webGuiBindPort: Int
        get() = Gui().apply { address = guiElement?.text("address") }.bindPort.toIntOrNull()
            ?: Constants.DEFAULT_WEBGUI_TCP_PORT.also { Log.w(TAG, "getWebGuiBindPort: No port in the GUI address") }

    val apiKey: String
        get() = guiElement?.text("apikey").orEmpty()

    val webUIUsername: String
        get() = guiElement?.text("user").orEmpty()

    val webUIPassword: String
        get() = preferences.getString(Constants.PREF_WEBUI_PASSWORD, null).orEmpty()

    /**
     * Updates the config file.
     * Sets ignorePerms flag to true on every folder, force enables TLS, sets the
     * username/password.
     */
    private fun updateIfNeeded() {
        /* Perform one-time migration tasks on syncthing's config file when coming from an older config version. */
        var changed = migrateSyncthingOptions()

        /* Section - folders */
        for (r in root.elements("folder")) {
            // Set ignorePerms attribute.
            if (r.attr("ignorePerms")?.toBoolean() != true) {
                Log.i(TAG, "Set 'ignorePerms' on folder " + r.getAttribute("id"))
                r.setAttribute("ignorePerms", "true")
                changed = true
            }

            // Set 'hashers' on the given folder.
            changed = setConfigElement(r, "hashers", "1") || changed
        }

        /* Section - GUI */
        val gui = guiElement ?: throw OpenConfigException()

        // Force REST API and Web UI access to use TLS.
        if (gui.attr("tls")?.toBoolean() != true) {
            gui.setAttribute("tls", "true")
            changed = true
        }

        /* Section - options */
        val options = root.element("options") ?: throw OpenConfigException()

        /* Dismiss "fsWatcherNotification" according to https://github.com/syncthing/syncthing-android/pull/1051 */
        for (node in options.childNodes.toList()) {
            if (node.nodeName == "unackedNotificationID" && node.textContent in DISMISSED_NOTIFICATIONS) {
                Log.i(TAG, "Remove found unackedNotificationID '${node.textContent}'.")
                options.removeChild(node)
                changed = true
            }
        }

        // Disable "startBrowser" because it applies to desktop environments and cannot start a mobile browser app.
        changed = setConfigElement(options, "startBrowser", Options().startBrowser) || changed

        // Disable Syncthing's NAT feature because it causes kernel oops on some buggy kernels.
        if (Constants.osHasKernelBugIssue505() && setConfigElement(options, "natEnabled", false)) {
            Log.d(TAG, "Disabling NAT option because a buggy kernel was detected.")
            changed = true
        }

        // Add the "Syncthing Camera" folder if the user consented to use the feature.
        if (preferences.getBoolean(Constants.PREF_ENABLE_SYNCTHING_CAMERA, false)) {
            changed = addSyncthingCameraFolder() || changed
        }

        // Save changes if we made any.
        if (changed) {
            saveChanges()
        }
    }

    /**
     * Migrates a config of version 27 to 28: enables the filesystem watcher on every folder.
     * Returns if changes to the config have been made.
     */
    private fun migrateSyncthingOptions(): Boolean {
        if (root.attr("version")?.toIntOrNull() != 27) {
            return false
        }
        Log.i(TAG, "Migrating config version 27 to 28 ...")
        val defaultFolder = Folder()
        for (r in root.elements("folder")) {
            // Enable "fsWatcherEnabled" attribute and set default delay.
            Log.i(TAG, "Set 'fsWatcherEnabled', 'fsWatcherDelayS' on folder " + r.getAttribute("id"))
            r.setAttribute("fsWatcherEnabled", defaultFolder.fsWatcherEnabled.toString())
            r.setAttribute("fsWatcherDelayS", defaultFolder.fsWatcherDelayS.toString())
        }
        // Version 28 prevents "unackedNotificationID" getting populated with the fsWatcher GUI notification.
        root.setAttribute("version", "28")
        Log.i(TAG, "Old config version was 27, new config version is 28")
        return true
    }

    fun getFolders(): List<Folder> {
        val localDeviceID = getLocalDeviceIDfromPref()
        // Direct children only: the "<defaults>" node holds a "<folder>" too.
        return root.children("folder").map { readFolder(it, localDeviceID) }.sortedWith(FOLDERS_COMPARATOR)
    }

    private fun readFolder(r: Element, localDeviceID: String): Folder {
        val folder = Folder()
        folder.id = r.attr("id").orEmpty()
        folder.group = r.attr("group") ?: folder.group
        folder.label = r.attr("label") ?: folder.label
        folder.path = r.attr("path").orEmpty().let {
            if (it.startsWith("~/")) it.replaceFirst("~", FileUtils.getSyncthingTildeAbsolutePath()) else it
        }
        folder.type = r.attr("type") ?: Constants.FOLDER_TYPE_SEND_RECEIVE
        folder.autoNormalize = r.attr("autoNormalize")?.toBoolean() ?: folder.autoNormalize
        folder.fsWatcherDelayS = r.attr("fsWatcherDelayS")?.toFloatOrNull() ?: folder.fsWatcherDelayS
        folder.fsWatcherEnabled = r.attr("fsWatcherEnabled")?.toBoolean() ?: folder.fsWatcherEnabled
        folder.ignorePerms = r.attr("ignorePerms")?.toBoolean() ?: folder.ignorePerms
        folder.rescanIntervalS = r.attr("rescanIntervalS")?.toIntOrNull() ?: folder.rescanIntervalS

        folder.copiers = r.int("copiers") ?: folder.copiers
        folder.hashers = r.int("hashers") ?: folder.hashers
        folder.order = r.text("order") ?: folder.order
        folder.paused = r.bool("paused") ?: folder.paused
        folder.ignoreDelete = r.bool("ignoreDelete") ?: folder.ignoreDelete
        folder.copyOwnershipFromParent = r.bool("copyOwnershipFromParent") ?: folder.copyOwnershipFromParent
        folder.modTimeWindowS = r.int("modTimeWindowS") ?: folder.modTimeWindowS
        folder.blockPullOrder = r.text("blockPullOrder") ?: folder.blockPullOrder
        folder.disableFsync = r.bool("disableFsync") ?: folder.disableFsync
        folder.maxConcurrentWrites = r.int("maxConcurrentWrites") ?: folder.maxConcurrentWrites
        folder.maxConflicts = r.int("maxConflicts") ?: folder.maxConflicts
        folder.copyRangeMethod = r.text("copyRangeMethod") ?: folder.copyRangeMethod
        folder.caseSensitiveFS = r.bool("caseSensitiveFS") ?: folder.caseSensitiveFS
        folder.syncOwnership = r.bool("syncOwnership") ?: folder.syncOwnership
        folder.sendOwnership = r.bool("sendOwnership") ?: folder.sendOwnership
        folder.syncXattrs = r.bool("syncXattrs") ?: folder.syncXattrs
        folder.sendXattrs = r.bool("sendXattrs") ?: folder.sendXattrs
        folder.blockIndexing = r.bool("blockIndexing") ?: folder.blockIndexing
        folder.filesystemType = r.text("filesystemType") ?: folder.filesystemType
        folder.markerName = r.text("markerName") ?: folder.markerName
        folder.disableSparseFiles = r.bool("disableSparseFiles") ?: folder.disableSparseFiles
        folder.pullerMaxPendingKiB = r.int("pullerMaxPendingKiB") ?: folder.pullerMaxPendingKiB
        folder.scanProgressIntervalS = r.int("scanProgressIntervalS") ?: folder.scanProgressIntervalS
        folder.pullerPauseS = r.int("pullerPauseS") ?: folder.pullerPauseS

        // <device id="[DEVICE_ID]" introducedBy=""/>, the local device excluded.
        for (elementDevice in r.elements("device")) {
            val device = SharedWithDevice()
            device.deviceID = elementDevice.attr("id").orEmpty()
            if (device.deviceID.isNullOrEmpty() || device.deviceID == localDeviceID) {
                continue
            }
            device.introducedBy = elementDevice.attr("introducedBy") ?: device.introducedBy
            device.encryptionPassword = elementDevice.text("encryptionPassword") ?: device.encryptionPassword
            folder.addDevice(device)
        }

        // <minDiskFree unit="MB">5</minDiskFree>
        folder.minDiskFree = Folder.MinDiskFree().apply {
            r.element("minDiskFree")?.let {
                unit = it.attr("unit") ?: unit
                value = it.textContent?.toFloatOrNull() ?: value
            }
        }

        /*
        <versioning></versioning>
        <versioning type="trashcan">
            <param key="cleanoutDays" val="90"></param>
            <cleanupIntervalS>3600</cleanupIntervalS>
            <fsPath></fsPath>
            <fsType>basic</fsType>
        </versioning>
        */
        folder.versioning = Folder.Versioning().apply {
            r.element("versioning")?.let { element ->
                type = element.attr("type").orEmpty()
                cleanupIntervalS = element.int("cleanupIntervalS") ?: 3600
                fsPath = element.text("fsPath").orEmpty()
                fsType = element.text("fsType") ?: "basic"
                for (param in element.elements("param")) {
                    params[param.attr("key").orEmpty()] = param.attr("val").orEmpty()
                }
            }
        }
        return folder
    }

    fun addFolder(folder: Folder) {
        Log.d(TAG, "addFolder: folder.id=" + folder.id)
        root.appendChild(config.createElement("folder").apply { setAttribute("id", folder.id) })
        updateFolder(folder)
    }

    fun updateFolder(folder: Folder) {
        val localDeviceID = getLocalDeviceIDfromPref()
        val r = root.elements("folder").firstOrNull { it.attr("id").orEmpty() == folder.id } ?: return
        r.setAttribute("group", folder.group)
        r.setAttribute("label", folder.label)
        r.setAttribute("path", folder.path)
        r.setAttribute("type", folder.type)
        r.setAttribute("autoNormalize", folder.autoNormalize.toString())
        r.setAttribute("fsWatcherDelayS", folder.fsWatcherDelayS.toString())
        r.setAttribute("fsWatcherEnabled", folder.fsWatcherEnabled.toString())
        r.setAttribute("ignorePerms", folder.ignorePerms.toString())
        r.setAttribute("rescanIntervalS", folder.rescanIntervalS.toString())

        setConfigElement(r, "copiers", folder.copiers.toString())
        setConfigElement(r, "hashers", folder.hashers.toString())
        setConfigElement(r, "order", folder.order)
        setConfigElement(r, "paused", folder.paused)
        setConfigElement(r, "ignoreDelete", folder.ignoreDelete)
        setConfigElement(r, "copyOwnershipFromParent", folder.copyOwnershipFromParent)
        setConfigElement(r, "modTimeWindowS", folder.modTimeWindowS.toString())
        setConfigElement(r, "blockPullOrder", folder.blockPullOrder)
        setConfigElement(r, "disableFsync", folder.disableFsync)
        setConfigElement(r, "maxConcurrentWrites", folder.maxConcurrentWrites.toString())
        setConfigElement(r, "maxConflicts", folder.maxConflicts.toString())
        setConfigElement(r, "copyRangeMethod", folder.copyRangeMethod)
        setConfigElement(r, "caseSensitiveFS", folder.caseSensitiveFS)
        setConfigElement(r, "syncOwnership", folder.syncOwnership)
        setConfigElement(r, "sendOwnership", folder.sendOwnership)
        setConfigElement(r, "syncXattrs", folder.syncXattrs)
        setConfigElement(r, "sendXattrs", folder.sendXattrs)
        setConfigElement(r, "blockIndexing", folder.blockIndexing)
        setConfigElement(r, "filesystemType", folder.filesystemType)

        // Devices sharing the folder: all but the local device are replaced by the model's.
        for (elementDevice in r.elements("device")) {
            if (elementDevice.attr("id").orEmpty() != localDeviceID) {
                Log.d(TAG, "updateFolder: nodeDevices: Removing deviceID=" + elementDevice.attr("id").orEmpty())
                removeChildElementFromTextNode(r, elementDevice)
            }
        }
        for (device in folder.sharedWithDevices) {
            Log.d(TAG, "updateFolder: nodeDevices: Adding deviceID=" + device.deviceID)
            val elementDevice = config.createElement("device")
            r.appendChild(elementDevice)
            elementDevice.setAttribute("id", device.deviceID)
            elementDevice.setAttribute("introducedBy", device.introducedBy)
            setConfigElement(elementDevice, "encryptionPassword", device.encryptionPassword)
        }

        // minDiskFree, replaced by the model's.
        folder.minDiskFree?.let { minDiskFree ->
            r.element("minDiskFree")?.let {
                Log.d(TAG, "updateFolder: nodeMinDiskFree: Removing minDiskFree node")
                removeChildElementFromTextNode(r, it)
            }
            r.appendChild(config.createElement("minDiskFree").apply { setAttribute("unit", minDiskFree.unit) })
            setConfigElement(r, "minDiskFree", minDiskFree.value.toString())
        }

        // Versioning, replaced by the model's.
        r.element("versioning")?.let {
            Log.d(TAG, "updateFolder: nodeVersioning: Removing versioning node")
            removeChildElementFromTextNode(r, it)
        }
        val elementVersioning = config.createElement("versioning")
        r.appendChild(elementVersioning)
        val versioning = folder.versioning
        if (versioning != null && !versioning.type.isNullOrEmpty()) {
            elementVersioning.setAttribute("type", versioning.type)
            setConfigElement(elementVersioning, "cleanupIntervalS", versioning.cleanupIntervalS.toString())
            setConfigElement(elementVersioning, "fsPath", versioning.fsPath)
            setConfigElement(elementVersioning, "fsType", versioning.fsType)
            for ((key, value) in versioning.params) {
                Log.d(TAG, "updateFolder: nodeVersioning: Adding param key=$key, val=$value")
                elementVersioning.appendChild(
                    config.createElement("param").apply {
                        setAttribute("key", key)
                        setAttribute("val", value)
                    }
                )
            }
        }
    }

    fun removeFolder(folderId: String?) {
        val r = root.elements("folder").lastOrNull { it.attr("id").orEmpty() == folderId } ?: return
        Log.d(TAG, "removeFolder: Removing folder node, folderId=$folderId")
        removeChildElementFromTextNode(r.parentNode as Element, r)
    }

    fun setFolderPause(folderId: String?, paused: Boolean) {
        root.elements("folder").firstOrNull { it.attr("id").orEmpty() == folderId }?.let {
            setConfigElement(it, "paused", paused)
        }
    }

    /**
     * The folder's ignore list, from its .stignore file. Empty if the file is missing, as it is
     * when no ignore was ever set on the folder.
     */
    fun getFolderIgnoreList(folder: Folder): FolderIgnoreList {
        val folderIgnoreList = FolderIgnoreList()
        val file = File(folder.path, Constants.FILENAME_STIGNORE)
        if (!file.exists()) {
            Log.w(TAG, "getFolderIgnoreList: File missing $file")
            return folderIgnoreList
        }
        try {
            folderIgnoreList.ignore = file.readText().split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
        } catch (e: IOException) {
            Log.e(TAG, "getFolderIgnoreList: Failed to read '$file'", e)
        }
        return folderIgnoreList
    }

    /**
     * Stores ignore list for given folder.
     */
    fun postFolderIgnoreList(folder: Folder, ignore: Array<String>) {
        val file = File(folder.path, Constants.FILENAME_STIGNORE)
        try {
            file.writeText(ignore.joinToString("\n"))
        } catch (e: IOException) {
            // This will happen on external storage folders which exist outside the
            // "/Android/data/[package_name]/files" folder.
            Log.w(TAG, "postFolderIgnoreList: Failed to write '$file'", e)
        }
    }

    fun getDevices(includeLocal: Boolean): List<Device> {
        val localDeviceID = getLocalDeviceIDfromPref()
        // Direct children only: "<defaults>" and each "<folder>" hold "<device>" nodes too.
        return root.children("device")
            .map { readDevice(it) }
            .filter { includeLocal || it.deviceID.isNullOrEmpty() || it.deviceID != localDeviceID }
            .sortedWith(DEVICES_COMPARATOR)
    }

    private fun readDevice(r: Element) = Device().apply {
        compression = r.attr("compression") ?: compression
        deviceID = r.attr("id").orEmpty()
        introducedBy = r.attr("introducedBy") ?: introducedBy
        introducer = r.attr("introducer")?.toBoolean() ?: introducer
        name = r.attr("name") ?: name
        autoAcceptFolders = r.bool("autoAcceptFolders") ?: autoAcceptFolders
        maxRecvKbps = r.int("maxRecvKbps") ?: maxRecvKbps
        maxSendKbps = r.int("maxSendKbps") ?: maxSendKbps
        paused = r.bool("paused") ?: paused
        untrusted = r.bool("untrusted") ?: untrusted
        numConnections = r.int("numConnections") ?: numConnections
        // <address>dynamic</address>, <address>tcp4://192.168.1.67:2222</address>
        addresses = r.elements("address").map { it.textContent.orEmpty() }
        // <allowedNetwork>192.168.0.0/24</allowedNetwork>
        allowedNetworks = r.elements("allowedNetwork").map { it.textContent.orEmpty() }
        ignoredFolders = r.elements("ignoredFolder").map { element ->
            IgnoredFolder().apply {
                id = element.attr("id") ?: id
                label = element.attr("label") ?: label
                time = element.attr("time") ?: time
            }
        }.toMutableList()
    }

    /**
     * Adds or updates a device identified by its device ID.
     */
    fun updateDevice(device: Device) {
        val deviceID = device.deviceID ?: return
        // Direct children only: each "<folder>" holds "<device>" nodes too.
        val r = root.children("device").firstOrNull { it.attr("id").orEmpty() == deviceID }
            ?: config.createElement("device").also {
                Log.d(TAG, "updateDevice: [addDevice] Adding deviceID='$deviceID' to config ...")
                root.appendChild(it)
                it.setAttribute("id", deviceID)
            }
        r.setAttribute("compression", device.compression)
        r.setAttribute("introducedBy", device.introducedBy)
        r.setAttribute("introducer", device.introducer.toString())
        r.setAttribute("name", device.name)

        setConfigElement(r, "autoAcceptFolders", device.autoAcceptFolders)
        setConfigElement(r, "paused", device.paused)
        setConfigElement(r, "untrusted", device.untrusted)
        setConfigElement(r, "numConnections", device.numConnections?.toString())

        replaceTextElements(r, "address", device.addresses.orEmpty())
        replaceTextElements(r, "allowedNetwork", device.allowedNetworks.orEmpty())
    }

    fun removeDevice(deviceID: String?) {
        // Direct children only: each "<folder>" holds "<device>" nodes too.
        val r = root.children("device").firstOrNull { it.attr("id").orEmpty() == deviceID } ?: return
        Log.d(TAG, "removeDevice: Removing device node, deviceID=$deviceID")
        removeChildElementFromTextNode(root, r)
    }

    fun getGui(): Gui {
        val gui = Gui()
        val elementGui = guiElement
        if (elementGui == null) {
            Log.e(TAG, "getGui: elementGui == null. Returning defaults.")
            return gui
        }
        gui.enabled = elementGui.attr("enabled")?.toBoolean() ?: gui.enabled
        gui.useTLS = elementGui.attr("tls")?.toBoolean() ?: gui.useTLS

        gui.address = elementGui.text("address") ?: gui.address
        gui.user = elementGui.text("user") ?: gui.user
        gui.password = elementGui.text("password").orEmpty()
        gui.apiKey = elementGui.text("apikey").orEmpty()
        gui.theme = elementGui.text("theme") ?: gui.theme
        gui.insecureAdminAccess = elementGui.bool("insecureAdminAccess") ?: gui.insecureAdminAccess
        gui.insecureAllowFrameLoading = elementGui.bool("insecureAllowFrameLoading") ?: gui.insecureAllowFrameLoading
        gui.insecureSkipHostCheck = elementGui.bool("insecureSkipHostcheck") ?: gui.insecureSkipHostCheck
        return gui
    }

    fun updateGui(gui: Gui) {
        val elementGui = guiElement
        if (elementGui == null) {
            Log.e(TAG, "updateGui: elementGui == null")
            return
        }
        elementGui.setAttribute("enabled", gui.enabled.toString())
        elementGui.setAttribute("tls", gui.useTLS.toString())

        setConfigElement(elementGui, "address", gui.address)
        setConfigElement(elementGui, "user", gui.user)
        setConfigElement(elementGui, "password", gui.password)
        setConfigElement(elementGui, "apikey", gui.apiKey)
        setConfigElement(elementGui, "theme", gui.theme)
        setConfigElement(elementGui, "insecureAdminAccess", gui.insecureAdminAccess)
        setConfigElement(elementGui, "insecureAllowFrameLoading", gui.insecureAllowFrameLoading)
        setConfigElement(elementGui, "insecureSkipHostcheck", gui.insecureSkipHostCheck)
    }

    fun getOptions(): Options {
        val options = Options()
        val e = root.element("options")
        if (e == null) {
            Log.e(TAG, "getOptions: elementOptions == null. Returning defaults.")
            return options
        }
        return options.apply {
            listenAddresses = e.elements("listenAddress").map { it.textContent.trim() }.filter { it.isNotEmpty() }.toTypedArray()
            globalAnnounceEnabled = e.bool("globalAnnounceEnabled") ?: globalAnnounceEnabled
            localAnnounceEnabled = e.bool("localAnnounceEnabled") ?: localAnnounceEnabled
            localAnnouncePort = e.int("localAnnouncePort") ?: localAnnouncePort
            localAnnounceMCAddr = e.text("localAnnounceMCAddr").orEmpty()
            maxSendKbps = e.int("maxSendKbps") ?: maxSendKbps
            maxRecvKbps = e.int("maxRecvKbps") ?: maxRecvKbps
            reconnectionIntervalS = e.int("reconnectionIntervalS") ?: reconnectionIntervalS
            relaysEnabled = e.bool("relaysEnabled") ?: relaysEnabled
            relayReconnectIntervalM = e.int("relayReconnectIntervalM") ?: relayReconnectIntervalM
            startBrowser = e.bool("startBrowser") ?: startBrowser
            natEnabled = e.bool("natEnabled") ?: natEnabled
            natLeaseMinutes = e.int("natLeaseMinutes") ?: natLeaseMinutes
            natRenewalMinutes = e.int("natRenewalMinutes") ?: natRenewalMinutes
            natTimeoutSeconds = e.int("natTimeoutSeconds") ?: natTimeoutSeconds
            urAccepted = e.int("urAccepted") ?: urAccepted
            urUniqueId = e.text("urUniqueId").orEmpty()
            urURL = e.text("urURL") ?: urURL
            urPostInsecurely = e.bool("urPostInsecurely") ?: urPostInsecurely
            urInitialDelayS = e.int("urInitialDelayS") ?: urInitialDelayS
            autoUpgradeIntervalH = e.int("autoUpgradeIntervalH") ?: autoUpgradeIntervalH
            upgradeToPreReleases = e.bool("upgradeToPreReleases") ?: upgradeToPreReleases
            keepTemporariesH = e.int("keepTemporariesH") ?: keepTemporariesH
            cacheIgnoredFiles = e.bool("cacheIgnoredFiles") ?: cacheIgnoredFiles
            progressUpdateIntervalS = e.int("progressUpdateIntervalS") ?: progressUpdateIntervalS
            limitBandwidthInLan = e.bool("limitBandwidthInLan") ?: limitBandwidthInLan
            releasesURL = e.text("releasesURL") ?: releasesURL
            overwriteRemoteDeviceNamesOnConnect = e.bool("overwriteRemoteDeviceNamesOnConnect") ?: overwriteRemoteDeviceNamesOnConnect
            tempIndexMinBlocks = e.int("tempIndexMinBlocks") ?: tempIndexMinBlocks
            setLowPriority = e.bool("setLowPriority") ?: setLowPriority
            maxFolderConcurrency = e.int("maxFolderConcurrency") ?: maxFolderConcurrency
            unackedNotificationID = e.text("unackedNotificationID") ?: unackedNotificationID
            crURL = e.text("crashReportingURL") ?: crURL
            crashReportingEnabled = e.bool("crashReportingEnabled") ?: crashReportingEnabled
            stunKeepaliveStartS = e.int("stunKeepaliveStartS") ?: stunKeepaliveStartS
            stunKeepaliveMinS = e.int("stunKeepaliveMinS") ?: stunKeepaliveMinS
            stunServer = e.text("stunServer") ?: stunServer
            maxConcurrentIncomingRequestKiB = e.int("maxConcurrentIncomingRequestKiB") ?: maxConcurrentIncomingRequestKiB
            announceLANAddresses = e.bool("announceLANAddresses") ?: announceLANAddresses
            sendFullIndexOnUpgrade = e.bool("sendFullIndexOnUpgrade") ?: sendFullIndexOnUpgrade
            featureFlag = e.text("featureFlag") ?: featureFlag
            connectionLimitEnough = e.int("connectionLimitEnough") ?: connectionLimitEnough
            connectionLimitMax = e.int("connectionLimitMax") ?: connectionLimitMax
        }
    }

    fun setDevicePause(deviceId: String?, paused: Boolean) {
        // Direct children only: each "<folder>" holds "<device>" nodes too.
        root.children("device").firstOrNull { it.attr("id").orEmpty() == deviceId }?.let {
            setConfigElement(it, "paused", paused)
        }
    }

    /**
     * Removes the element with the whitespace before it, which Element.removeChild leaves.
     * See https://stackoverflow.com/questions/14255064/removechild-how-to-remove-indent-too
     */
    private fun removeChildElementFromTextNode(parentElement: Element, childElement: Element) {
        val prev = childElement.previousSibling
        if (prev != null && prev.nodeType == Node.TEXT_NODE && prev.nodeValue.isBlank()) {
            parentElement.removeChild(prev)
        }
        parentElement.removeChild(childElement)
    }

    /**
     * Sets the text of the first [tagName] element below [parent], created if missing. A null value
     * leaves the config as is. Returns if the config changed.
     */
    private fun setConfigElement(parent: Element, tagName: String, textContent: String?): Boolean {
        textContent ?: return false
        val element = parent.getElementsByTagName(tagName).item(0)
            ?: config.createElement(tagName).also { parent.appendChild(it) }
        if (textContent == element.textContent) {
            return false
        }
        element.textContent = textContent
        return true
    }

    private fun setConfigElement(parent: Element, tagName: String, value: Boolean?): Boolean =
        setConfigElement(parent, tagName, value?.toString())

    /**
     * Replaces the [tagName] children of [parent] by one element per value. Returns if the config
     * changed.
     */
    private fun replaceTextElements(parent: Element, tagName: String, values: List<String>): Boolean {
        val existing = parent.children(tagName)
        existing.forEach { removeChildElementFromTextNode(parent, it) }
        values.forEach { value -> parent.appendChild(config.createElement(tagName).apply { textContent = value }) }
        return existing.isNotEmpty() || values.isNotEmpty()
    }

    /**
     * Sets the device model as the local device's name. Returns if changes to the config have
     * been made.
     */
    private fun changeLocalDeviceName(localDeviceID: String): Boolean {
        // Direct children only: each "<folder>" holds "<device>" nodes too.
        val device = root.children("device").firstOrNull { it.getAttribute("id") == localDeviceID } ?: return false
        Log.i(TAG, "changeLocalDeviceName: Rename device ID $localDeviceID to ${Build.MODEL}")
        device.setAttribute("name", Build.MODEL)
        return true
    }

    /**
     * Adds a new folder pointing to the app-specific "Syncthing Camera"
     * directory if it hasn't been added to the config yet.
     * Returns if changes to the config have been made.
     */
    private fun addSyncthingCameraFolder(): Boolean {
        if (root.elements("folder").any { it.attr("id") == Constants.syncthingCameraFolderId }) {
            return false
        }
        // App specific directory, e.g. "/storage/emulated/0/Android/media/[PACKAGE_NAME]/Pictures".
        val storageDir = FileUtils.getExternalFilesDir(
            context, FileUtils.ExternalStorageDirType.INT_MEDIA, Environment.DIRECTORY_PICTURES
        )
        if (storageDir == null) {
            Log.e(TAG, "addSyncthingCameraFolder: storageDir == null")
            return false
        }
        val folder = Folder().apply {
            minDiskFree = Folder.MinDiskFree()
            id = Constants.syncthingCameraFolderId
            label = context.getString(R.string.default_syncthing_camera_folder_label)
            path = storageDir.absolutePath
            versioning = Folder.Versioning().apply {
                type = "trashcan"
                params["cleanoutDays"] = "14"
                cleanupIntervalS = 3600
                fsPath = ""
                fsType = "basic"
            }
        }
        logV("addSyncthingCameraFolder: Adding folder to config [${folder.path}]")
        addFolder(folder)
        return true
    }

    /**
     * Writes the config back to config.xml.
     */
    fun saveChanges() {
        if (!configFile.canWrite()) {
            Log.w(TAG, "Failed to save updated config. Cannot change the owner of the config file.")
            return
        }
        Log.i(TAG, "Saving config file")
        try {
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.METHOD, "xml")
                setOutputProperty(OutputKeys.ENCODING, "UTF-16")
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
            }
            // XML header, then body. The whole file is built in memory before touching the disk.
            val output = ByteArrayOutputStream()
            output.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>".toByteArray(Charsets.UTF_8))
            val writer = output.writer(Charsets.UTF_8)
            transformer.transform(DOMSource(config), StreamResult(writer))
            writer.flush()

            // Replaces config.xml only if the temporary file was fully written.
            FileUtils.writeBytesAtomic(configFile, output.toByteArray())
        } catch (e: TransformerException) {
            Log.w(TAG, "Failed to save config file, config.xml left untouched", e)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to save config file, config.xml left untouched", e)
        }
    }

    private fun logV(message: String) {
        if (verboseLog) {
            Log.v(TAG, message)
        }
    }

    private companion object {
        const val TAG = "ConfigXml"

        /**
         * Notifications Syncthing shows in its web GUI that do not apply to the app.
         */
        val DISMISSED_NOTIFICATIONS = setOf(
            "authenticationUserAndPassword", "crAutoEnabled", "crAutoDisabled", "fsWatcherNotification"
        )

        /**
         * Compares devices by name, uses the device ID as fallback if the name is empty
         */
        val DEVICES_COMPARATOR = compareBy<Device> { it.name.ifEmpty { it.deviceID.orEmpty() } }

        /**
         * Compares folders by labels, uses the folder ID as fallback if the label is empty
         */
        val FOLDERS_COMPARATOR = compareBy<Folder> { it.label.ifEmpty { it.id.orEmpty() } }
    }
}

private fun Element.attr(name: String): String? = if (hasAttribute(name)) getAttribute(name) else null

private fun Element.element(tagName: String): Element? = getElementsByTagName(tagName).item(0) as Element?

/**
 * The [tagName] elements at any depth below this one.
 */
private fun Element.elements(tagName: String): List<Element> {
    val nodes = getElementsByTagName(tagName)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

/**
 * The [tagName] elements directly below this one.
 */
private fun Element.children(tagName: String): List<Element> =
    childNodes.toList().filter { it.nodeName == tagName }.map { it as Element }

private fun NodeList.toList(): List<Node> = (0 until length).map { item(it) }

/**
 * The text of the first [tagName] element below this one, null if there is none.
 */
private fun Element.text(tagName: String): String? = getElementsByTagName(tagName).item(0)?.textContent

private fun Element.bool(tagName: String): Boolean? = text(tagName)?.toBoolean()

private fun Element.int(tagName: String): Int? = text(tagName)?.toIntOrNull()
