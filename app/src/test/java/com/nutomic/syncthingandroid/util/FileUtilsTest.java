package com.nutomic.syncthingandroid.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FileUtilsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void writeBytesAtomicReplacesTarget() throws IOException {
        File target = tmp.newFile("config.xml");
        Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));

        FileUtils.writeBytesAtomic(target, "new".getBytes(StandardCharsets.UTF_8));

        assertEquals("new", read(target));
        assertFalse(new File(target.getParentFile(), "config.xml.tmp").exists());
    }

    @Test
    public void writeBytesAtomicLeavesTargetUntouchedOnWriteFailure() throws IOException {
        File target = tmp.newFile("config.xml");
        Files.write(target.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        // A directory where the temporary file should go makes the write fail.
        assertTrue(new File(target.getParentFile(), "config.xml.tmp").mkdir());

        try {
            FileUtils.writeBytesAtomic(target, "new".getBytes(StandardCharsets.UTF_8));
            fail("IOException expected");
        } catch (IOException expected) {
            // The target must keep its previous content.
        }

        assertEquals("old", read(target));
    }
}
