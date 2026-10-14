package com.casklabs.ghostbuffer.capture;

import com.casklabs.ghostbuffer.buffer.FrameRingBuffer;
import com.casklabs.ghostbuffer.preview.FakePreviewBackend;
import com.casklabs.ghostbuffer.storage.GhostStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class CaptureControllerTest {
    private File tempRootDir;
    private GhostStorage storage;
    private FrameRingBuffer ringBuffer;
    private FakePreviewBackend backend;
    private CaptureController controller;

    @Before
    public void setUp() {
        tempRootDir = new File(System.getProperty("java.io.tmpdir"), "ghostctrl_test_" + System.currentTimeMillis());
        tempRootDir.mkdirs();
        storage = new GhostStorage(tempRootDir);
        ringBuffer = new FrameRingBuffer(2000L, 20, 1024 * 1024L);
        backend = new FakePreviewBackend();
        backend.setPayloadPaddingBytes(200);
        controller = new CaptureController(backend, ringBuffer, storage, null);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.stop();
        }
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

    @Test
    public void testControllerLifecycleAndBuffering() throws Exception {
        assertEquals(CaptureController.State.IDLE, controller.getState());

        controller.start(null);
        assertEquals(CaptureController.State.BUFFERING, controller.getState());

        // Wait a few milliseconds for the frame reader to capture at least 2 frames
        Thread.sleep(250);

        assertTrue("Ring buffer should contain frames", ringBuffer.size() >= 2);

        controller.pause();
        assertEquals(CaptureController.State.PAUSED, controller.getState());
    }

    @Test
    public void testShutterTriggerAndRepeatSuppression() throws Exception {
        final CountDownLatch saveLatch = new CountDownLatch(1);
        final AtomicReference<CaptureController.State> finalState = new AtomicReference<CaptureController.State>();

        CaptureController.StateListener listener = new CaptureController.StateListener() {
            @Override
            public void onStateChanged(CaptureController.State newState, String message) {
                finalState.set(newState);
            }

            @Override
            public void onStatsUpdated(float fps, int frameCount, long coveredDurationMs) {}

            @Override
            public void onSaveProgress(int current, int total) {}

            @Override
            public void onSaveComplete(File sessionDir, CaptureManifest manifest) {
                saveLatch.countDown();
            }

            @Override
            public void onError(Throwable error) {
                saveLatch.countDown();
            }
        };

        controller = new CaptureController(backend, ringBuffer, storage, listener);
        controller.start(null);

        // Allow some frames to buffer
        Thread.sleep(250);
        int bufferedCount = ringBuffer.size();
        assertTrue("Buffer should have frames before shutter", bufferedCount > 0);

        // 1. First shutter key down -> MUST trigger capture
        boolean triggeredFirst = controller.onShutterKeyDown();
        assertTrue("First shutter press must trigger capture", triggeredFirst);

        // 2. Hardware repeat key down event (holding button down) -> MUST be ignored
        boolean triggeredRepeat = controller.onShutterKeyDown();
        assertFalse("Repeated shutter press while key is held down must be ignored", triggeredRepeat);

        // Wait for asynchronous saving to complete
        assertTrue("Capture save must finish within timeout", saveLatch.await(5, TimeUnit.SECONDS));
        assertEquals(CaptureController.State.REVIEW, controller.getState());
        assertNotNull(controller.getLastFrozenSnapshot());
        assertEquals(bufferedCount, controller.getLastFrozenSnapshot().size());

        // 3. Shutter key up -> resets key latch
        controller.onShutterKeyUp();

        // 4. Resume buffering
        controller.resumeBuffering();
        assertEquals(CaptureController.State.BUFFERING, controller.getState());
        assertEquals("Buffer should be reset on resume", 0, ringBuffer.size());
    }
}
