package com.nutomic.syncthingandroid.model

import android.util.Log
import java.util.AbstractMap.SimpleEntry
import kotlin.math.floor

/**
 * Caches the local folder completion of [CachedFolderStatus], from Syncthing's "FolderSummary"
 * events. Completion model of Syncthing's web UI is completion[folderId].
 */
class LocalCompletion(private val verboseLog: Boolean) {

    private val folderMap = HashMap<String, Map.Entry<FolderStatus, CachedFolderStatus>>()

    /**
     * Drops the folders removed from the config and adds the new ones.
     */
    @Synchronized
    fun updateFromConfig(newFolders: List<Folder>) {
        val folderIds = newFolders.mapNotNull { it.id }
        folderMap.keys.filter { it !in folderIds }.forEach {
            logV("updateFromConfig: Remove folder '$it' from cache model")
            folderMap.remove(it)
        }
        folderIds.filter { it !in folderMap }.forEach {
            logV("updateFromConfig: Add folder '$it' to cache model.")
            folderMap[it] = SimpleEntry(FolderStatus(), CachedFolderStatus())
        }
    }

    /**
     * Local completion across the folders that sync and are not complete, 100 if none.
     */
    @Synchronized
    fun getTotalFolderCompletion(): Int {
        var folderCount = 0
        var sumCompletion = 0.0
        for (cached in folderMap.values.map { it.value }) {
            // Filter invalid percentage values we may have got from the REST API.
            cached.completion = cached.completion.coerceIn(0.0, 100.0)
            if (!cached.paused && cached.completion != 100.0) {
                sumCompletion += cached.completion
                folderCount++
            }
        }
        if (folderCount == 0) {
            return 100
        }
        return floor(sumCompletion / folderCount).toInt().coerceIn(0, 100)
    }

    /**
     * A copy of the folder's status and completion, empty ones on a cache miss.
     */
    @Synchronized
    fun getFolderStatus(folderId: String?): Map.Entry<FolderStatus, CachedFolderStatus> {
        val entry = folderId?.let(folderMap::get) ?: return SimpleEntry(FolderStatus(), CachedFolderStatus())
        return SimpleEntry(entry.key.deepCopy(), entry.value.deepCopy())
    }

    /**
     * Stores the folder status for the UI, with the completion derived from it.
     */
    @Synchronized
    fun setFolderStatus(folderId: String?, folderPaused: Boolean, folderStatus: FolderStatus) {
        folderId ?: return
        val cached = getFolderStatus(folderId).value
        cached.paused = folderPaused
        cached.completion = when {
            folderStatus.state == "idle" -> 100.0
            folderStatus.globalBytes == 0L || folderStatus.inSyncBytes > folderStatus.globalBytes -> 100.0
            else -> floor(folderStatus.inSyncBytes.toDouble() / folderStatus.globalBytes * 100)
        }
        logV(
            "setFolderStatus: folderId=\"$folderId\", state=\"${folderStatus.state}\", " +
                "paused=${cached.paused}, completion=${cached.completion.toInt()}%"
        )
        folderMap[folderId] = SimpleEntry(folderStatus, cached)
    }

    /**
     * Same, keeping the paused state of the previous entry.
     */
    @Synchronized
    fun setFolderStatus(folderId: String?, folderStatus: FolderStatus) =
        setFolderStatus(folderId, getFolderStatus(folderId).value.paused, folderStatus)

    /**
     * "ItemFinished" event details, from EventProcessor through RestApi.
     */
    @Synchronized
    fun setLastItemFinished(folderId: String?, action: String?, item: String?, time: String?) =
        updateCached(folderId) {
            lastItemFinishedAction = action.orEmpty()
            lastItemFinishedItem = item.orEmpty()
            lastItemFinishedTime = time.orEmpty()
        }

    @Synchronized
    fun setRemoteIndexUpdated(folderId: String?, remoteIndexUpdated: Boolean) =
        updateCached(folderId) { this.remoteIndexUpdated = remoteIndexUpdated }

    @Synchronized
    fun setDiscoveredConflictFiles(folderId: String?, discoveredConflictFiles: Array<String>) =
        updateCached(folderId) { this.discoveredConflictFiles = discoveredConflictFiles }

    private fun updateCached(folderId: String?, update: CachedFolderStatus.() -> Unit) {
        folderId ?: return
        val entry = getFolderStatus(folderId)
        entry.value.update()
        folderMap[folderId] = SimpleEntry(entry.key, entry.value)
    }

    private fun logV(message: String) {
        if (verboseLog) {
            Log.v(TAG, message)
        }
    }

    private companion object {
        const val TAG = "LocalCompletion"
    }
}
