package com.nutomic.syncthingandroid.model

/**
 * Caches information frequently needed by the wrapper
 * to save expensive calls to Syncthing's REST API.
 * Vars in class do not correspond to JSON results.
 */
class CachedFolderStatus {
    /**
     * Calculated
     */
    @JvmField var completion: Double = 100.0

    // Example: "Test1.sync-conflict-20250514-140912-XF3FVSV", "subfolder/Test2.sync-conflict-20250514-140912-XF3FVSV"
    @JvmField var discoveredConflictFiles: Array<String> = arrayOf()

    // Example: "update"
    @JvmField var lastItemFinishedAction: String = ""

    // Example: "test.doc"
    @JvmField var lastItemFinishedItem: String = ""

    // Example: "2019-11-19T23:28:55.7479276+01:00"
    @JvmField var lastItemFinishedTime: String = ""

    // Indicates if a folder - device pair has received updates from the remote side.
    @JvmField var remoteIndexUpdated: Boolean = false
    @JvmField var paused: Boolean = false
}
