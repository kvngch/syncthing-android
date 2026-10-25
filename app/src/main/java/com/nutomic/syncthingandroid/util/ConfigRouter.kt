package com.nutomic.syncthingandroid.util

import android.content.Context
import android.util.Log
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.model.FolderIgnoreList
import com.nutomic.syncthingandroid.model.Gui
import com.nutomic.syncthingandroid.model.Options
import com.nutomic.syncthingandroid.service.RestApi

/**
 * Accesses the config through the REST API while Syncthing runs, through config.xml otherwise.
 */
class ConfigRouter(context: Context) {

    private val configXml = ConfigXml(context)

    /**
     * Reads config.xml while the REST API has no config yet.
     */
    private fun <T> read(restApi: RestApi?, fromXml: ConfigXml.() -> T, fromApi: RestApi.() -> T): T {
        if (restApi == null || !restApi.isConfigLoaded) {
            configXml.loadConfigReadOnly()
            return configXml.fromXml()
        }
        return restApi.fromApi()
    }

    /**
     * Writes config.xml while Syncthing is not running. Otherwise Syncthing has read config.xml
     * already: writes through the REST API, which sends the config, once it has loaded it.
     */
    private fun write(restApi: RestApi?, toXml: ConfigXml.() -> Unit, toApi: RestApi.() -> Unit) {
        if (restApi == null) {
            writeXml(toXml)
            return
        }
        // Should Syncthing stop before serving its config, the change goes to config.xml.
        restApi.runWhenConfigLoaded(Runnable { writeXml(toXml) }) { restApi.toApi() }
    }

    private fun writeXml(toXml: ConfigXml.() -> Unit) {
        configXml.loadConfig()
        configXml.toXml()
        configXml.saveChanges()
    }

    fun getFolders(restApi: RestApi?): List<Folder> = read(restApi, { getFolders() }, { folders })

    fun addFolder(restApi: RestApi?, folder: Folder) = write(restApi, { addFolder(folder) }, { addFolder(folder) })

    fun updateFolder(restApi: RestApi?, folder: Folder) =
        write(restApi, { updateFolder(folder) }, { updateFolder(folder) })

    /**
     * Same as updateFolder for several folders, written at once: one config.xml save or one send.
     */
    fun updateFolders(restApi: RestApi?, folders: List<Folder>) {
        if (folders.isNotEmpty()) {
            write(restApi, { folders.forEach { updateFolder(it) } }, { updateFolders(folders) })
        }
    }

    fun removeFolder(restApi: RestApi?, folderId: String?) =
        write(restApi, { removeFolder(folderId) }, { removeFolder(folderId) })

    fun ignoreFolder(restApi: RestApi?, deviceId: String?, folderId: String?, folderLabel: String?) {
        if (restApi == null) {
            Log.e(TAG, "ignoreFolder failed, Syncthing is not running.")
            return
        }
        restApi.runWhenConfigLoaded { restApi.ignoreFolder(deviceId, folderId, folderLabel) }
    }

    /**
     * Gets ignore list for given folder.
     */
    fun getFolderIgnoreList(restApi: RestApi?, folder: Folder, listener: (FolderIgnoreList) -> Unit) =
        read(restApi, { listener(getFolderIgnoreList(folder)) }, { getFolderIgnoreList(folder.id) { listener(it) } })

    /**
     * Stores ignore list for given folder. The .stignore file is not part of config.xml.
     */
    fun postFolderIgnoreList(restApi: RestApi?, folder: Folder, ignore: Array<String>) {
        if (restApi == null) {
            configXml.loadConfig()
            configXml.postFolderIgnoreList(folder, ignore)
            return
        }
        restApi.runWhenConfigLoaded { restApi.postFolderIgnoreList(folder.id, ignore) }
    }

    fun getDevices(restApi: RestApi?, includeLocal: Boolean): List<Device> =
        read(restApi, { getDevices(includeLocal) }, { getDevices(includeLocal) })

    fun updateDevice(restApi: RestApi?, device: Device) =
        write(restApi, { updateDevice(device) }, { updateDevice(device) })

    fun removeDevice(restApi: RestApi?, deviceID: String?) =
        write(restApi, { removeDevice(deviceID) }, { removeDevice(deviceID) })

    fun ignoreDevice(restApi: RestApi?, deviceID: String?, deviceName: String?, deviceAddress: String?) {
        if (restApi == null) {
            Log.e(TAG, "ignoreDevice failed, Syncthing is not running.")
            return
        }
        restApi.runWhenConfigLoaded { restApi.ignoreDevice(deviceID, deviceName, deviceAddress) }
    }

    fun getGui(restApi: RestApi?): Gui = read(restApi, { getGui() }, { gui })

    fun updateGui(restApi: RestApi?, gui: Gui) = write(restApi, { updateGui(gui) }, { updateGui(gui) })

    fun getOptions(restApi: RestApi?): Options = read(restApi, { getOptions() }, { options })

    private companion object {
        const val TAG = "ConfigRouter"
    }
}
