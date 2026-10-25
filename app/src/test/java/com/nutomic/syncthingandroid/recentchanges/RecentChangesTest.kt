package com.nutomic.syncthingandroid.recentchanges

import com.nutomic.syncthingandroid.model.DiskEvent
import com.nutomic.syncthingandroid.model.DiskEventData
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentChangesTest {

    private fun event(id: Long, folderId: String, path: String, action: String, type: String) =
        DiskEvent().apply {
            this.id = id
            data = DiskEventData().apply {
                folderID = folderId
                this.path = path
                this.action = action
                this.type = type
            }
        }

    @Test
    fun deletedDirectoryHidesItsEntriesInTheSameFolderOnly() {
        val events = listOf(
            event(1, "photos", "2024/a.jpg", "modified", "file"),
            event(2, "backup", "2024/a.jpg", "modified", "file"),
            event(3, "backup", "2024", "deleted", "dir"),
        )

        assertEquals(listOf(1L, 3L), events.withoutUselessEvents().map { it.id })
    }

    @Test
    fun deletedFileHidesItsEarlierChangesInTheSameFolderOnly() {
        // Syncthing v2 reports a creation as "modified".
        val events = listOf(
            event(1, "photos", "notes.txt", "modified", "file"),
            event(2, "backup", "notes.txt", "modified", "file"),
            event(3, "photos", "notes.txt", "deleted", "file"),
            event(4, "photos", "notes.txt", "modified", "file"),
        )

        assertEquals(listOf(2L, 3L, 4L), events.withoutUselessEvents().map { it.id })
    }
}
