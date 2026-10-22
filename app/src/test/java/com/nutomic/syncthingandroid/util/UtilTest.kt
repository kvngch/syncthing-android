package com.nutomic.syncthingandroid.util

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.ZonedDateTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UtilTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(path: Path) {
        Files.createDirectories(path.parent)
        Files.createFile(path)
    }

    @Test
    fun getSyncConflictFilesMatchesFindSemantics() {
        val root = tmp.root.toPath()
        touch(root.resolve("a.$CONFLICT.txt"))
        touch(root.resolve("normal.txt"))
        touch(root.resolve("short.sync-conflict-2024-120000-ABCDEFG.txt"))
        // Shell metacharacters in a folder name stay literal.
        touch(root.resolve("Budget \$2024/b.$CONFLICT.pdf"))
        // Only the top level .stversions is excluded.
        touch(root.resolve(".stversions/c.$CONFLICT.txt"))
        touch(root.resolve("sub/.stversions/d.$CONFLICT.txt"))
        // Symlinks are not followed: no loop, no duplicate.
        Files.createSymbolicLink(root.resolve("sub/loop"), Paths.get(".."))
        Files.createSymbolicLink(root.resolve("link.$CONFLICT.txt"), root.resolve("a.$CONFLICT.txt"))

        assertEquals(
            setOf("a.$CONFLICT.txt", "Budget \$2024/b.$CONFLICT.pdf", "sub/.stversions/d.$CONFLICT.txt"),
            Util.getSyncConflictFiles(root.toString()).toSet(),
        )
    }

    @Test
    fun getSyncConflictFilesOnMissingFolderIsEmpty() {
        assertEquals(0, Util.getSyncConflictFiles("${tmp.root}/missing").size)
    }

    @Test
    fun isTcpPortListeningSeesLoopbackAndWildcardListeners() {
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { listener ->
            assertTrue(Util.isTcpPortListening(listener.localPort))
            listener.localPort
        }
        assertFalse(Util.isTcpPortListening(port))
        ServerSocket(port).use { assertTrue(Util.isTcpPortListening(port)) }
    }

    @Test
    fun getLocalZonedDateTimeIsCurrentUtcTime() {
        val now = Util.getLocalZonedDateTime()
        assertTrue(now, now.endsWith("Z"))
        val skewMillis = abs(ZonedDateTime.parse(now).toInstant().toEpochMilli() - System.currentTimeMillis())
        assertTrue("skew $skewMillis ms", skewMillis < 60_000)
    }

    @Test
    fun getPathEllipsisShortensEachPart() {
        assertEquals("/a/b.txt", Util.getPathEllipsis("/a/b.txt"))
        assertEquals("a/", Util.getPathEllipsis("a/"))
        assertEquals("very-long-direc\u22ef/f.txt", Util.getPathEllipsis("very-long-directory-name/f.txt"))
        // Long names keep their extension; a dotfile is cut as a whole.
        assertEquals("n".repeat(30) + "\u22ef.jpeg", Util.getPathEllipsis("n".repeat(40) + ".jpeg"))
        assertEquals("." + "h".repeat(29) + "\u22ef", Util.getPathEllipsis("." + "h".repeat(40)))
    }

    private companion object {
        const val CONFLICT = "sync-conflict-20240101-120000-ABCDEFG"
    }
}
