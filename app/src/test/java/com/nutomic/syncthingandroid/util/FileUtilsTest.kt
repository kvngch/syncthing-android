package com.nutomic.syncthingandroid.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUtilsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun deleteDirectoryRecursivelyDoesNotFollowSymbolicLinks() {
        val outside = tmp.newFolder("outside")
        val kept = File(outside, "kept.txt").apply { writeText("data") }
        val staging = tmp.newFolder("staging")
        Files.createSymbolicLink(File(staging, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(staging, "broken").toPath(), File(tmp.root, "missing").toPath())

        assertTrue(FileUtils.deleteDirectoryRecursively(staging))

        assertFalse(staging.exists())
        assertTrue(kept.exists())
    }

    @Test
    fun writeBytesAtomicReplacesTarget() {
        val target = tmp.newFile("config.xml")
        target.writeText("old")

        FileUtils.writeBytesAtomic(target, "new".toByteArray())

        assertEquals("new", target.readText())
        assertFalse(File(target.parentFile, "config.xml.tmp").exists())
    }

    @Test
    fun writeBytesAtomicLeavesTargetUntouchedOnWriteFailure() {
        val target = tmp.newFile("config.xml")
        target.writeText("old")
        // A directory where the temporary file should go makes the write fail.
        assertTrue(File(target.parentFile, "config.xml.tmp").mkdir())

        assertThrows(IOException::class.java) { FileUtils.writeBytesAtomic(target, "new".toByteArray()) }

        // The target must keep its previous content.
        assertEquals("old", target.readText())
    }
}
