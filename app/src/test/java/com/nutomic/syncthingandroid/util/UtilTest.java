package com.nutomic.syncthingandroid.util;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
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
}
