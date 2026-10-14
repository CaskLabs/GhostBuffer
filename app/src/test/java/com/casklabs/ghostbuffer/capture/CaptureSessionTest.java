package com.casklabs.ghostbuffer.capture;

import com.casklabs.ghostbuffer.buffer.FrameSlot;
import com.casklabs.ghostbuffer.preview.FakePreviewBackend;
import com.casklabs.ghostbuffer.storage.GhostStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CaptureSessionTest {
    private File tempRootDir;
    private GhostStorage storage;
    private CaptureWriter writer;

    @Before
    public void setUp() {
        tempRootDir = new File(System.getProperty("java.io.tmpdir"), "ghostsession_test_" + System.currentTimeMillis());
        tempRootDir.mkdirs();
        storage = new GhostStorage(tempRootDir);
        writer = new CaptureWriter(storage);
    }

    @After
    public void tearDown() {
        deleteRecursive(tempRootDir);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }

    private List<FrameSlot> createSampleSnapshot(int count) {
        List<FrameSlot> list = new ArrayList<FrameSlot>();
        for (int i = 1; i <= count; i++) {
            byte[] jpeg = FakePreviewBackend.createFakeJpeg(i, 1000L + i * 100, 200);
            list.add(new FrameSlot(jpeg, jpeg.length, i, 990L + i * 100, 1000L + i * 100));
        }
        return list;
    }

    @Test
    public void testLoadAllSessions() throws Exception {
        // Write two sessions at different wall clock times
        long time1 = 1700000000000L;
        long time2 = 1700000010000L;

        writer.writeSnapshot(createSampleSnapshot(2), 1000L, time1, 2000L, 10.0, 10.0, 640, 480, "ILCE-5000", "1.0.0", null);
        writer.writeSnapshot(createSampleSnapshot(3), 2000L, time2, 2000L, 10.0, 10.0, 640, 480, "ILCE-5000", "1.0.0", null);

        List<CaptureSession> loaded = CaptureSession.loadAllSessions(storage.getCapturesDir());
        assertEquals("Should find exactly 2 sessions", 2, loaded.size());

        // Check reverse chronological order (newest time2 first)
        CaptureSession first = loaded.get(0);
        CaptureSession second = loaded.get(1);

        assertTrue("First session directory name should be greater than second (newer)",
            first.getSessionId().compareTo(second.getSessionId()) > 0);

        assertTrue("Session must be complete", first.isComplete());
        assertEquals(3, first.getFrameCount());
        assertNotNull(first.getFrameFile(0));
        assertTrue(first.getFrameFile(0).exists());
    }

    @Test
    public void testIncompleteSessionDetection() throws Exception {
        long time = 1700000020000L;
        File sessionDir = writer.writeSnapshot(createSampleSnapshot(2), 1000L, time, 2000L, 10.0, 10.0, 640, 480, "ILCE-5000", "1.0.0", null);

        // Delete COMPLETE marker file to simulate interrupted capture
        File completeMarker = new File(sessionDir, CaptureWriter.COMPLETE_MARKER_FILE);
        assertTrue(completeMarker.delete());

        List<CaptureSession> loaded = CaptureSession.loadAllSessions(storage.getCapturesDir());
        assertEquals(1, loaded.size());
        assertFalse("Session without COMPLETE marker must be marked incomplete", loaded.get(0).isComplete());
    }
}
