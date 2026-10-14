package com.casklabs.ghostbuffer.storage;

import com.casklabs.ghostbuffer.buffer.FrameSlot;
import com.casklabs.ghostbuffer.capture.CaptureSession;
import com.casklabs.ghostbuffer.capture.CaptureWriter;
import com.casklabs.ghostbuffer.preview.FakePreviewBackend;
import com.casklabs.ghostbuffer.probe.GhostProbeManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.security.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Rejects long/lowercase filenames at the actual file-I/O boundary, like Sony's SD bridge. */
public class SonyStorageCompatibilityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testCaptureCollisionProbeAndRecoveryOnShortNameFilesystem() throws Exception {
        final String mountPrefix = temp.getRoot().getAbsolutePath() + File.separator;
        final SecurityManager previous = System.getSecurityManager();
        final AtomicInteger temporaryWrites = new AtomicInteger();
        SecurityManager shortNamesOnly = new SecurityManager() {
            @Override public void checkPermission(Permission permission) {
                if (previous != null) previous.checkPermission(permission);
            }
            private void checkPath(String path) {
                if (!path.startsWith(mountPrefix)) return;
                String relative = path.substring(mountPrefix.length());
                for (String name : relative.split(File.separator)) {
                    if (!name.matches("[A-Z0-9_]{1,8}(\\.[A-Z0-9_]{1,3})?")) {
                        throw new SecurityException("Sony SD rejects filename: " + name);
                    }
                }
                if (relative.endsWith(".TMP")) temporaryWrites.incrementAndGet();
            }
            @Override public void checkWrite(String path) { checkPath(path); }
            @Override public void checkDelete(String path) { checkPath(path); }
        };
        GhostStorage storage = new GhostStorage(new File(temp.getRoot(), GhostStorage.ROOT_DIR_NAME), false);
        FakePreviewBackend backend = new FakePreviewBackend();
        backend.open(null);
        backend.startPreviewSequence(640, 480, 10);
        List<FrameSlot> frames = new ArrayList<FrameSlot>();
        byte[] jpeg = FakePreviewBackend.createFakeJpeg(1, 1000L, 200);
        frames.add(new FrameSlot(jpeg, jpeg.length, 1, 990L, 1000L));
        frames.add(new FrameSlot(jpeg, jpeg.length, 2, 1090L, 1100L));
        try {
            System.setSecurityManager(shortNamesOnly);
            CaptureWriter writer = new CaptureWriter(storage);
            File first = writer.writeSnapshot(frames, 1200L, 1700000000000L,
                2000L, 10.0, 10.0, 640, 480, "ILCE-5000", "test", null);
            File second = writer.writeSnapshot(frames, 1200L, 1700000000000L,
                2000L, 10.0, 10.0, 640, 480, "ILCE-5000", "test", null);
            assertNotEquals(first, second);
            assertTrue(new File(first, CaptureWriter.COMPLETE_MARKER_FILE).isFile());
            assertTrue(new File(second, CaptureWriter.COMPLETE_MARKER_FILE).isFile());
            assertEquals(2, CaptureSession.loadAllSessions(storage.getCapturesDir()).size());
            assertTrue(new File(second, CaptureWriter.MANIFEST_FILE).delete());
            assertTrue(new File(second, CaptureWriter.COMPLETE_MARKER_FILE).delete());
            List<CaptureSession> recovered = CaptureSession.loadAllSessions(storage.getCapturesDir());
            assertEquals(2, recovered.size());
            assertFalse(recovered.get(0).isComplete());
            assertEquals(2, recovered.get(0).getFrameCount());
            GhostProbeManager.ProbeResult probe = new GhostProbeManager(storage)
                .runSingleProbe(backend, "ILCE-5000", "1.00");
            assertTrue(probe.message, probe.success);
            assertTrue(probe.jpegFile.isFile());
            assertTrue(probe.reportFile.isFile());
            // A report left by an interrupted probe must not be overwritten either.
            long collisionTime = com.casklabs.ghostbuffer.time.GhostTime.currentTimeMillis();
            File reservedReport = new File(storage.getProbeDir(),
                GhostStorage.generateSessionId(collisionTime) + ".TXT");
            if (!reservedReport.exists()) assertTrue(reservedReport.createNewFile());
            GhostProbeManager.ProbeResult repeated = new GhostProbeManager(storage)
                .runSingleProbe(backend, "ILCE-5000", "1.00");
            assertTrue(repeated.message, repeated.success);
            assertNotEquals(probe.jpegFile, repeated.jpegFile);
            assertNotEquals(reservedReport, repeated.reportFile);
            assertTrue("The filename restriction must cover temporary files too", temporaryWrites.get() > 0);
        } finally {
            System.setSecurityManager(previous);
            backend.close();
        }
    }
}
