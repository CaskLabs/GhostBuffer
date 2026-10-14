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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class CaptureWriterTest {
    private File tempRootDir;
    private GhostStorage storage;
    private CaptureWriter writer;

    @Before
    public void setUp() {
        tempRootDir = new File(System.getProperty("java.io.tmpdir"), "ghostbuffer_test_" + System.currentTimeMillis());
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

    private List<FrameSlot> createSampleSnapshot(int count, long triggerTimeMs) {
        List<FrameSlot> list = new ArrayList<FrameSlot>();
        for (int i = 1; i <= count; i++) {
            long endMs = triggerTimeMs - ((count - i) * 100L);
            long startMs = endMs - 10L;
            byte[] jpeg = FakePreviewBackend.createFakeJpeg(i, endMs, 500);
            list.add(new FrameSlot(jpeg, jpeg.length, i, startMs, endMs));
        }
        return list;
    }

    @Test
    public void testSuccessfulSnapshotWrite() throws Exception {
        long triggerUptime = 20000L;
        long triggerWallClock = 1700000000000L;
        List<FrameSlot> snapshot = createSampleSnapshot(5, triggerUptime);

        File sessionDir = writer.writeSnapshot(
            snapshot,
            triggerUptime,
            triggerWallClock,
            2000L,
            10.0,
            9.9,
            640,
            480,
            "ILCE-5000",
            "1.0.0",
            null
        );

        assertNotNull(sessionDir);
        assertTrue(sessionDir.exists());
        assertTrue(sessionDir.isDirectory());

        // Check frame files
        for (int i = 1; i <= 5; i++) {
            File frameFile = new File(sessionDir, String.format("frame_%06d.jpg", i));
            assertTrue("Frame file must exist: " + frameFile.getName(), frameFile.exists());
            assertTrue(frameFile.length() > 0);

            // Ensure no leftover temp files
            File tmpFile = new File(sessionDir, String.format("frame_%06d.jpg.tmp", i));
            assertFalse("Temp file must be cleaned up", tmpFile.exists());
        }

        // Check manifest.json
        File manifestFile = new File(sessionDir, CaptureWriter.MANIFEST_FILE);
        assertTrue("manifest.json must exist", manifestFile.exists());

        // Check COMPLETE marker
        File completeFile = new File(sessionDir, CaptureWriter.COMPLETE_MARKER_FILE);
        assertTrue("COMPLETE marker file must exist", completeFile.exists());
    }

    @Test
    public void testUniqueDirectoryCreationOnCollision() throws Exception {
        long triggerUptime = 30000L;
        long triggerWallClock = 1700000000000L;
        List<FrameSlot> snapshot = createSampleSnapshot(2, triggerUptime);

        File session1 = writer.writeSnapshot(snapshot, triggerUptime, triggerWallClock, 2000L, 10.0, 10.0, 640, 480, "ILCE-5000", "1.0.0", null);
        File session2 = writer.writeSnapshot(snapshot, triggerUptime, triggerWallClock, 2000L, 10.0, 10.0, 640, 480, "ILCE-5000", "1.0.0", null);

        assertNotEquals("Directory collision must generate distinct folder names", session1.getAbsolutePath(), session2.getAbsolutePath());
        assertTrue(session1.exists());
        assertTrue(session2.exists());
    }

    @Test
    public void testAsyncWriteAndCallbacks() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean completeCalled = new AtomicBoolean(false);

        List<FrameSlot> snapshot = createSampleSnapshot(3, 10000L);

        writer.writeSnapshotAsync(
            snapshot,
            10000L,
            System.currentTimeMillis(),
            2000L,
            10.0,
            9.5,
            640,
            480,
            "ILCE-5000",
            "1.0.0",
            new CaptureWriter.Listener() {
                @Override
                public void onSaveProgress(int current, int total) {}

                @Override
                public void onSaveComplete(File sessionDir, CaptureManifest manifest) {
                    completeCalled.set(true);
                    latch.countDown();
                }

                @Override
                public void onSaveError(Throwable error) {
                    latch.countDown();
                }
            }
        );

        assertTrue("Async writer must complete within timeout", latch.await(5, TimeUnit.SECONDS));
        assertTrue("onSaveComplete must be invoked", completeCalled.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testEmptySnapshotThrows() throws Exception {
        writer.writeSnapshot(new ArrayList<FrameSlot>(), 0L, 0L, 2000L, 10.0, 10.0, 640, 480, "ILCE-5000", "1.0.0", null);
    }
}
