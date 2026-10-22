package com.nutomic.syncthingandroid.log

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LogsTest {

    @Test
    fun androidLogKeepsSyncthingLinesWithoutNoiseDateNorPid() {
        val output = listOf(
            "--------- beginning of main",
            "10-22 19:05:03.876 W/SyncthingRunnable( 2595): Syncthing stopped without request from service.",
            "10-22 19:05:03.900 I/Choreographer( 2595): Skipped 31 frames!",
            "10-22 19:05:04.026 I/SyncthingNativeCode( 3207): INFO: Web GUI has come online",
        ).joinToString("\n")

        assertEquals(
            listOf(
                "19:05:03W/SyncthingRunnable Syncthing stopped without request from service.",
                "19:05:04 INFO: Web GUI has come online",
            ).joinToString("\n"),
            cleanAndroidLog(output),
        )
    }

    @Test
    fun tailDropsTheLineCutBySeekAndKeepsTheLastLines() {
        val file = File.createTempFile("syncthing", ".log").apply { deleteOnExit() }
        file.writeText((1..10).joinToString("\n") { "line $it" })

        // The whole file fits: nothing is cut, the line limit applies.
        assertEquals("line 9\nline 10", readLogFileTail(file, maxBytes = 1024, maxLines = 2))
        // The read starts inside "line 8": that partial line is dropped.
        assertEquals("line 9\nline 10", readLogFileTail(file, maxBytes = 16, maxLines = 100))
    }

    @Test
    fun missingLogFileReadsEmpty() {
        assertEquals("", readLogFileTail(File("does-not-exist.log")))
    }
}
