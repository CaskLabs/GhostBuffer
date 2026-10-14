package com.casklabs.ghostbuffer.preview;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class JpegDimensionsTest {
    @Test public void readsActualSizeFromBaselineJpeg() {
        byte[] jpeg = FakePreviewBackend.createFakeJpeg(1, 0, 100);
        assertArrayEquals(new int[] { 64, 48 }, JpegDimensions.read(jpeg, jpeg.length));
    }

    @Test public void skipsSofLookingBytesInsideAppData() {
        byte[] jpeg = FakePreviewBackend.createFakeJpeg(1, 0, 0);
        byte[] app = { (byte)255, (byte)225, 0, 8, (byte)255, (byte)192, 0, 0, 0, 0 };
        byte[] combined = new byte[jpeg.length + app.length];
        System.arraycopy(jpeg, 0, combined, 0, 2);
        System.arraycopy(app, 0, combined, 2, app.length);
        System.arraycopy(jpeg, 2, combined, 2 + app.length, jpeg.length - 2);
        assertArrayEquals(new int[] { 64, 48 }, JpegDimensions.read(combined, combined.length));
    }

    @Test public void supportsProgressiveSofHeaders() {
        byte[] jpeg = FakePreviewBackend.createFakeJpeg(1, 0, 0);
        for (int i = 0; i < jpeg.length - 1; i++) {
            if ((jpeg[i] & 255) == 255 && (jpeg[i + 1] & 255) == 192) {
                jpeg[i + 1] = (byte)194;
                break;
            }
        }
        assertArrayEquals(new int[] { 64, 48 }, JpegDimensions.read(jpeg, jpeg.length));
    }

    @Test public void rejectsTruncatedAndMalformedSegmentsWithoutReadingPastBounds() {
        byte[] jpeg = FakePreviewBackend.createFakeJpeg(1, 0, 0);
        assertNull(JpegDimensions.read(null, 0));
        assertNull(JpegDimensions.read(jpeg, jpeg.length + 1));
        assertNull(JpegDimensions.read(jpeg, 8));
        assertNull(JpegDimensions.read(Arrays.copyOf(jpeg, 8), 8));
        jpeg[4] = (byte)255;
        jpeg[5] = (byte)255;
        assertNull(JpegDimensions.read(jpeg, jpeg.length));
    }

    @Test public void rejectsScanDataWithoutASizeHeader() {
        byte[] jpeg = { (byte)255, (byte)216, (byte)255, (byte)218, 0, 2, (byte)255, (byte)217 };
        assertNull(JpegDimensions.read(jpeg, jpeg.length));
    }

    @Test public void memoryBudgetLeavesSpaceForSnapshotAndHasAnUpperBound() {
        assertEquals(3 * 1024 * 1024L, PreviewResolution.memoryBudget(16 * 1024 * 1024L));
        assertEquals(4 * 1024 * 1024L, PreviewResolution.memoryBudget(32 * 1024 * 1024L));
        assertEquals(8 * 1024 * 1024L, PreviewResolution.memoryBudget(256 * 1024 * 1024L));
        assertEquals(PreviewResolution.VGA, PreviewResolution.fromPreference("deleted-choice"));
    }
}
