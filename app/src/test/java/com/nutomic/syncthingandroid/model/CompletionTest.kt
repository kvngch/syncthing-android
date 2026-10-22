package com.nutomic.syncthingandroid.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionTest {

    @Test
    fun remoteCompletionAveragesTheFoldersStillSyncingOnConnectedDevices() {
        val remote = RemoteCompletion(false)
        assertEquals(-1, remote.getTotalDeviceCompletion())
        remote.setDeviceStatus("A", Connection().apply { connected = true })
        listOf("f1" to 50.0, "f2" to 100.0, "f3" to 0.0, "f4" to 70.5).forEach { (folder, completion) ->
            remote.setCompletionInfo("A", folder, RemoteCompletionInfo().apply { this.completion = completion })
        }
        assertEquals(60, remote.getTotalDeviceCompletion())
        assertEquals(60, remote.getDeviceCompletion("A"))
        assertEquals(100, remote.getDeviceCompletion("B"))
        assertEquals(1, remote.getOnlineDeviceCount())
        // A disconnected device does not count, and a null connection is ignored.
        remote.setDeviceStatus("A", Connection())
        remote.setDeviceStatus("A", null)
        assertEquals(-1, remote.getTotalDeviceCompletion())
    }

    @Test
    fun localCompletionSkipsPausedAndCompleteFolders() {
        val local = LocalCompletion(false)
        local.setFolderStatus("a", false, FolderStatus().apply { state = "syncing"; globalBytes = 200; inSyncBytes = 50 })
        local.setFolderStatus("b", true, FolderStatus().apply { state = "syncing"; globalBytes = 200; inSyncBytes = 100 })
        local.setFolderStatus("c", false, FolderStatus())
        assertEquals(25, local.getTotalFolderCompletion())
        assertEquals(25.0, local.getFolderStatus("a").value.completion, 0.0)
        // The paused state survives a status without it.
        local.setFolderStatus("b", FolderStatus())
        assertEquals(true, local.getFolderStatus("b").value.paused)
        local.updateFromConfig(listOf(Folder().apply { id = "c" }))
        assertEquals(100, local.getTotalFolderCompletion())
    }
}
