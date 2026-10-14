package com.casklabs.ghostbuffer.capture;

import com.casklabs.ghostbuffer.buffer.FrameRingBuffer;
import com.casklabs.ghostbuffer.buffer.FrameSlot;
import com.casklabs.ghostbuffer.preview.FakePreviewBackend;
import com.casklabs.ghostbuffer.preview.JpegDimensions;
import com.casklabs.ghostbuffer.preview.PreviewFrame;
import com.casklabs.ghostbuffer.preview.PreviewResolution;
import com.casklabs.ghostbuffer.preview.ResolutionPreviewBackend;
import com.casklabs.ghostbuffer.preview.TestJpeg;
import com.casklabs.ghostbuffer.storage.GhostStorage;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class CaptureControllerResolutionTest {
    private File directory;
    private FrameRingBuffer buffer;
    private CaptureController controller;
    private Camera camera;
    private ResolutionPreviewBackend backend;
    private volatile CaptureManifest saved;
    private CountDownLatch saveCompleted;

    private static class Camera extends FakePreviewBackend {
        int maxWidth = 1920;
        byte[] jpeg;
        @Override public void startPreviewSequence(int width, int height, int fps) throws Exception {
            if (width > maxWidth) throw new IOException("Unsupported size");
            super.startPreviewSequence(width, height, fps);
            jpeg = TestJpeg.create(width, height, false);
        }
        @Override public PreviewFrame readNextFrame() throws Exception {
            PreviewFrame clock = super.readNextFrame();
            return new PreviewFrame(jpeg, jpeg.length, clock.getReadStartMs(), clock.getReadEndMs());
        }
    }

    @Before public void setUp() throws Exception {
        directory = File.createTempFile("ghost-resolution-", "tests");
        assertTrue(directory.delete());
        assertTrue(directory.mkdir());
        buffer = new FrameRingBuffer();
        camera = new Camera();
        backend = new ResolutionPreviewBackend(camera, null);
        saveCompleted = new CountDownLatch(1);
        controller = new CaptureController(backend, buffer, new GhostStorage(directory),
            new CaptureController.StateListener() {
                @Override public void onStateChanged(CaptureController.State state, String message) {}
                @Override public void onStatsUpdated(float fps, int count, long duration) {}
                @Override public void onSaveProgress(int current, int total) {}
                @Override public void onSaveComplete(File session, CaptureManifest manifest) {
                    saved = manifest; saveCompleted.countDown();
                }
                @Override public void onError(Throwable error) {}
            });
    }

    @After public void tearDown() {
        controller.stop();
        delete(directory);
    }

    private void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private void awaitFrames(int count) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (buffer.size() < count && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertTrue("Expected a working live buffer", buffer.size() >= count);
    }

    @Test public void resolutionSwitchClearsOldSizeFramesAndSavesActualSizeAndFps() throws Exception {
        controller.start(null);
        awaitFrames(3);
        controller.changePreviewResolution(PreviewResolution.HD);
        awaitFrames(3);
        for (FrameSlot frame : buffer.getSlotsReadOnly()) {
            assertArrayEquals(new int[] { 1280, 720 }, JpegDimensions.read(frame.getData(), frame.getLength()));
        }
        assertTrue(controller.onShutterKeyDown());
        assertTrue(saveCompleted.await(3, TimeUnit.SECONDS));
        assertEquals(1280, saved.getFrameWidth());
        assertEquals(720, saved.getFrameHeight());
        assertEquals(5.0, saved.getTargetFps(), 0.001);
        assertTrue("Measured FPS must describe the slower stream", saved.getMeasuredFps() > 3 && saved.getMeasuredFps() < 6);
    }

    @Test public void fallbackAndSavedMetadataUse720pInsteadOfRequested1080p() throws Exception {
        camera.maxWidth = 1280;
        controller.setPreviewDimensions(1920, 1080, 3);
        controller.start(null);
        awaitFrames(3);
        assertEquals(PreviewResolution.FULL_HD, backend.getSelection().requested);
        assertEquals(PreviewResolution.HD, backend.getSelection().active);
        assertTrue(controller.onShutterKeyDown());
        assertTrue(saveCompleted.await(3, TimeUnit.SECONDS));
        assertEquals(1280, saved.getFrameWidth());
        assertEquals(720, saved.getFrameHeight());
        assertEquals(5.0, saved.getTargetFps(), 0.001);
    }

    @Test public void cannotChangeResolutionAfterCameraPause() throws Exception {
        controller.start(null);
        controller.pause();
        try {
            controller.changePreviewResolution(PreviewResolution.FULL_HD);
            fail("A released camera must not receive settings");
        } catch (IllegalStateException expected) {}
        assertEquals(CaptureController.State.PAUSED, controller.getState());
    }

    @Test public void failedSwitchLeavesNoOldFramesAndCanRecoverToThePreviousChoice() throws Exception {
        controller.start(null);
        awaitFrames(2);
        camera.maxWidth = 0;
        try {
            controller.changePreviewResolution(PreviewResolution.FULL_HD);
            fail("All rejected sizes must report an error");
        } catch (IOException expected) {}
        assertEquals(CaptureController.State.ERROR, controller.getState());
        assertEquals(0, buffer.size());
        assertNull(controller.getLastFrozenSnapshot());
        camera.maxWidth = 640;
        controller.discardSnapshotAndResume();
        assertEquals(CaptureController.State.BUFFERING, controller.getState());
        awaitFrames(2);
        assertEquals("640x480", backend.getSelection().actualLabel());
    }

    @Test public void recoveryRestartsAndRevalidatesAStreamThatChangedDimensions() throws Exception {
        controller.start(null);
        awaitFrames(1);
        camera.jpeg = TestJpeg.create(64, 48, false);
        long deadline = System.currentTimeMillis() + 3000;
        while (controller.getState() != CaptureController.State.ERROR && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(CaptureController.State.ERROR, controller.getState());
        controller.discardSnapshotAndResume();
        assertEquals(CaptureController.State.BUFFERING, controller.getState());
        awaitFrames(2);
        for (FrameSlot frame : buffer.getSlotsReadOnly()) {
            assertArrayEquals(new int[] { 640, 480 }, JpegDimensions.read(frame.getData(), frame.getLength()));
        }
    }
}
