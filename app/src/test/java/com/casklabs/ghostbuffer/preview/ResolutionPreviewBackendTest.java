package com.casklabs.ghostbuffer.preview;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ResolutionPreviewBackendTest {
    private static byte[] jpeg(int width, int height, boolean texture) throws Exception {
        return TestJpeg.create(width, height, texture);
    }

    private static class Camera implements PreviewBackend {
        final List<Integer> requestedWidths = new ArrayList<Integer>();
        int maxWidth = 1920;
        boolean ignoresSize, nullAboveLimit, warmup, opened;
        int width, height, reads, closes;
        byte[] frame;

        @Override public void open(Object context) { opened = true; }
        @Override public boolean isAvailable() { return true; }
        @Override public void startPreviewSequence(int width, int height, int fps) throws Exception {
            assertTrue(opened);
            requestedWidths.add(width);
            this.width = width; this.height = height;
            if (width > maxWidth && !nullAboveLimit) throw new IOException("Size rejected");
            frame = ignoresSize ? jpeg(640, 480, false) : jpeg(width, height, false);
        }
        @Override public PreviewFrame readNextFrame() throws Exception {
            reads++;
            if (nullAboveLimit && width > maxWidth) return null;
            byte[] data = warmup && reads == 1 ? jpeg(640, 480, false) : frame;
            return new PreviewFrame(data, data.length, 10, 20);
        }
        @Override public void stopPreviewSequence() {}
        @Override public void close() { closes++; opened = false; }
    }

    private ResolutionPreviewBackend start(Camera camera, PreviewResolution desired) throws Exception {
        ResolutionPreviewBackend backend = new ResolutionPreviewBackend(camera, null, 60L);
        backend.open(null);
        backend.startPreviewSequence(desired.width, desired.height, desired.fps);
        return backend;
    }

    @Test public void fullHdIsVerifiedAndFirstFrameIsKeptForTheReader() throws Exception {
        Camera camera = new Camera();
        ResolutionPreviewBackend backend = start(camera, PreviewResolution.FULL_HD);
        assertEquals("1920x1080", backend.getSelection().actualLabel());
        assertFalse(backend.getSelection().fellBack());
        assertArrayEquals(new int[] { 1920, 1080 }, JpegDimensions.read(backend.readNextFrame().getData(), camera.frame.length));
        assertEquals("Validation must not discard the first captured image", 1, camera.reads);
        backend.close();
    }

    @Test public void rejectedFullHdFallsBackTo720p() throws Exception {
        Camera camera = new Camera(); camera.maxWidth = 1280;
        ResolutionPreviewBackend backend = start(camera, PreviewResolution.FULL_HD);
        assertEquals(Arrays.asList(1920, 1280), camera.requestedWidths);
        assertEquals(PreviewResolution.HD, backend.getSelection().active);
        assertEquals(PreviewResolution.FULL_HD, backend.getSelection().requested);
        assertTrue(backend.getSelection().fellBack());
        backend.close();
    }

    @Test public void silentlyIgnoredSettingsNeverClaimFullHd() throws Exception {
        Camera camera = new Camera(); camera.ignoresSize = true;
        ResolutionPreviewBackend backend = start(camera, PreviewResolution.FULL_HD);
        assertEquals(Arrays.asList(1920, 1280, 640), camera.requestedWidths);
        assertEquals("640x480", backend.getSelection().actualLabel());
        assertTrue(backend.getSelection().fellBack());
        backend.close();
    }

    @Test public void missingFramesFallBackAndGalleryResumeReusesTheKnownWorkingSize() throws Exception {
        Camera camera = new Camera(); camera.maxWidth = 640; camera.nullAboveLimit = true;
        ResolutionPreviewBackend backend = start(camera, PreviewResolution.FULL_HD);
        assertEquals(PreviewResolution.VGA, backend.getSelection().active);
        backend.close();
        camera.requestedWidths.clear();
        backend.open(null);
        backend.startPreviewSequence(1920, 1080, 3);
        assertEquals(Arrays.asList(640), camera.requestedWidths);
        assertEquals(PreviewResolution.FULL_HD, backend.getSelection().requested);
        backend.close();
    }

    @Test public void waitsForResolutionToSettleAfterAnInitialOldFrame() throws Exception {
        Camera camera = new Camera(); camera.warmup = true;
        ResolutionPreviewBackend backend = start(camera, PreviewResolution.FULL_HD);
        assertEquals(Arrays.asList(1920), camera.requestedWidths);
        assertEquals("1920x1080", backend.getSelection().actualLabel());
        backend.close();
    }

    @Test public void acceptsGenuineFullHdJpegsLargerThanTheOld256KbLimit() throws Exception {
        final byte[] detailed = jpeg(1920, 1080, true);
        assertTrue(detailed.length > 256 * 1024);
        assertTrue(detailed.length < PreviewResolution.FULL_HD.maxFrameBytes);
        Camera camera = new Camera() {
            @Override public void startPreviewSequence(int width, int height, int fps) throws Exception {
                super.startPreviewSequence(width, height, fps);
                frame = detailed;
            }
        };
        ResolutionPreviewBackend backend = start(camera, PreviewResolution.FULL_HD);
        assertEquals(detailed.length, backend.readNextFrame().getLength());
        backend.close();
    }

    @Test public void cancellationStopsNegotiationWithoutTryingMoreSizes() throws Exception {
        final boolean[] cancelled = { false };
        Camera camera = new Camera() {
            @Override public PreviewFrame readNextFrame() { cancelled[0] = true; return null; }
        };
        ResolutionPreviewBackend backend = new ResolutionPreviewBackend(camera,
            new ResolutionPreviewBackend.Cancellation() {
                @Override public boolean isCancelled() { return cancelled[0]; }
            }, 1000L);
        backend.open(null);
        try {
            backend.startPreviewSequence(1920, 1080, 3);
            fail("A paused camera must not receive further fallback requests");
        } catch (InterruptedException expected) {}
        assertEquals(Arrays.asList(1920), camera.requestedWidths);
        assertNull(backend.getSelection());
        assertFalse(camera.opened);
    }

    @Test public void allSizesFailCleanlyWithoutAUsableStream() throws Exception {
        Camera camera = new Camera(); camera.maxWidth = 0;
        ResolutionPreviewBackend backend = new ResolutionPreviewBackend(camera, null, 20L);
        backend.open(null);
        try {
            backend.startPreviewSequence(1920, 1080, 3);
            fail("Unusable stream must report an error");
        } catch (IOException expected) {}
        assertNull(backend.getSelection());
        assertFalse(camera.opened);
        assertNull(backend.readNextFrame());
    }

    @Test public void insufficientMemoryFor1080pFallsBackTo720p() throws Exception {
        Camera camera = new Camera() {
            @Override public void startPreviewSequence(int width, int height, int fps) throws Exception {
                super.startPreviewSequence(width, height, fps);
                if (width == 1920) throw new OutOfMemoryError("Frame allocation failed");
            }
        };
        ResolutionPreviewBackend backend = start(camera, PreviewResolution.FULL_HD);
        assertEquals(Arrays.asList(1920, 1280), camera.requestedWidths);
        assertEquals(PreviewResolution.HD, backend.getSelection().active);
        backend.close();
    }
}
