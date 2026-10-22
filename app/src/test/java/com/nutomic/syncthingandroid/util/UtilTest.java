package com.nutomic.syncthingandroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;

public class UtilTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String CONFLICT = "sync-conflict-20240101-120000-ABCDEFG";

    private static void touch(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.createFile(path);
    }

    @Test
    public void getSyncConflictFilesMatchesFindSemantics() throws IOException {
        Path root = tmp.getRoot().toPath();
        touch(root.resolve("a." + CONFLICT + ".txt"));
        touch(root.resolve("normal.txt"));
        touch(root.resolve("short.sync-conflict-2024-120000-ABCDEFG.txt"));
        // Shell metacharacters in a folder name stay literal.
        touch(root.resolve("Budget $2024/b." + CONFLICT + ".pdf"));
        // Only the top level .stversions is excluded.
        touch(root.resolve(".stversions/c." + CONFLICT + ".txt"));
        touch(root.resolve("sub/.stversions/d." + CONFLICT + ".txt"));
        // Symlinks are not followed: no loop, no duplicate.
        Files.createSymbolicLink(root.resolve("sub/loop"), Paths.get(".."));
        Files.createSymbolicLink(root.resolve("link." + CONFLICT + ".txt"), root.resolve("a." + CONFLICT + ".txt"));

        Set<String> found = new TreeSet<>(Arrays.asList(Util.getSyncConflictFiles(root.toString())));

        Set<String> expected = new TreeSet<>(Arrays.asList(
                "a." + CONFLICT + ".txt",
                "Budget $2024/b." + CONFLICT + ".pdf",
                "sub/.stversions/d." + CONFLICT + ".txt"
        ));
        assertEquals(expected, found);
    }

    @Test
    public void getSyncConflictFilesOnMissingFolderIsEmpty() {
        assertEquals(0, Util.getSyncConflictFiles(tmp.getRoot() + "/missing").length);
    }

    @Test
    public void isTcpPortListeningSeesLoopbackAndWildcardListeners() throws IOException {
        int port;
        try (ServerSocket listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            port = listener.getLocalPort();
            assertTrue(Util.isTcpPortListening(port));
        }
        assertFalse(Util.isTcpPortListening(port));
        try (ServerSocket listener = new ServerSocket(port)) {
            assertTrue(Util.isTcpPortListening(port));
        }
    }

    @Test
    public void getLocalZonedDateTimeIsCurrentUtcTime() throws ParseException {
        String now = Util.getLocalZonedDateTime();
        // Unit tests run with Build.VERSION.SDK_INT == 0: the pre-API 26 format.
        assertTrue(now, now.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        long skewMillis = Math.abs(parser.parse(now).getTime() - System.currentTimeMillis());
        assertTrue("skew " + skewMillis + " ms", skewMillis < 60_000);
    }
}
