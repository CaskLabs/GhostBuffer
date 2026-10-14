package com.casklabs.ghostbuffer.storage;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class GhostStorageTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testFallbackWhenPrimaryMountCannotCreateAppDirectory() throws Exception {
        File primary = temp.newFolder("primary");
        // A file occupying the directory path makes creation fail on every host OS.
        assertTrue(new File(primary, GhostStorage.ROOT_DIR_NAME).createNewFile());
        File sdCard = temp.newFolder("sdcard0");
        GhostStorage storage = new GhostStorage(new File[] { primary, sdCard });

        File captures = storage.prepareCapturesDir();
        assertEquals(new File(sdCard, "GHOSTBUF/CAPTURES"), captures);
        assertTrue(captures.isDirectory());
        assertEquals(new File(sdCard, "GHOSTBUF/PROBE"), storage.prepareProbeDir());
        assertEquals(0, captures.list().length);
        assertEquals(2, storage.getBaseDir().list().length);
        // A fresh gallery instance must resolve the same root as the capture writer.
        assertEquals(captures, new GhostStorage(new File[] { primary, sdCard }).getCapturesDir());
    }

    @Test
    public void testMissingMountPointIsNeverCreatedAndCanBeRetried() throws Exception {
        File missing = new File(temp.getRoot(), "unmounted");
        GhostStorage storage = new GhostStorage(new File[] { missing });
        assertFalse(storage.isStorageWritable());
        assertFalse(missing.exists());

        assertTrue(missing.mkdir());
        assertTrue(storage.isStorageWritable());
        assertEquals(new File(missing, "GHOSTBUF/CAPTURES"), storage.getCapturesDir());
    }

    @Test
    public void testBlockedCapturesDirectoryReportsStorageError() throws Exception {
        File base = temp.newFolder("blocked");
        assertTrue(new File(base, "CAPTURES").createNewFile());
        GhostStorage storage = new GhostStorage(base);
        try {
            storage.prepareCapturesDir();
            fail("Saving to a regular file must fail before creating a session");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("SD card unavailable or not writable"));
            assertTrue(e.getMessage().contains(base.getAbsolutePath()));
        }
    }

    @Test
    public void testExistingCaptureDirectoryAndNestedParentsAreSupported() throws Exception {
        GhostStorage storage = new GhostStorage(new File(temp.getRoot(), "nested/GhostBuffer"));
        File first = storage.prepareCapturesDir();
        assertTrue(first.isDirectory());
        assertEquals(first, storage.prepareCapturesDir());
        assertEquals(0, first.list().length);
    }

    @Test
    public void testSonySyncIsSkippedOnUnsupportedFilesystem() throws Exception {
        GhostStorage storage = new GhostStorage(temp.newFolder("sony"), false);
        // An invalid descriptor would throw if sync were attempted.
        storage.sync(new FileDescriptor());
    }

    @Test(expected = IOException.class)
    public void testRealSyncErrorsAreNotHiddenOnOtherFilesystems() throws Exception {
        new GhostStorage(temp.newFolder("host")).sync(new FileDescriptor());
    }

    @Test
    public void testIso8601FormattingAlwaysInUtc() {
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            // Test in GMT+02:00
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+02:00"));
            String isoEpochGmtPlus2 = GhostStorage.formatIso8601(0L);
            assertEquals("1970-01-01T00:00:00.000Z", isoEpochGmtPlus2);

            // Test in GMT-08:00
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-08:00"));
            String isoEpochGmtMinus8 = GhostStorage.formatIso8601(0L);
            assertEquals("1970-01-01T00:00:00.000Z", isoEpochGmtMinus8);

            // Test specific known timestamp: 1700000000000 ms -> 2023-11-14T22:13:20.000Z
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"));
            String isoKnown = GhostStorage.formatIso8601(1700000000000L);
            assertEquals("2023-11-14T22:13:20.000Z", isoKnown);
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }

    @Test
    public void testGenerateSessionId() {
        TimeZone originalDefault = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+09:00"));
            String sessionId = GhostStorage.generateSessionId(1700000000000L);
            // In UTC, 1700000000000 is 2023-11-14 22:13:20
            assertEquals("6553F100", sessionId);
        } finally {
            TimeZone.setDefault(originalDefault);
        }
    }
}
