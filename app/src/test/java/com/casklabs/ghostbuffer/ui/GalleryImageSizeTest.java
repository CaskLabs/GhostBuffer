package com.casklabs.ghostbuffer.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class GalleryImageSizeTest {
    @Test
    public void testPreviewFitsWithoutDownsampling() {
        assertEquals(1, GalleryImageSize.sampleSize(640, 480, 640, 480));
        assertEquals(1, GalleryImageSize.sampleSize(64, 48, 640, 480));
    }

    @Test
    public void testFullResolutionPhotoIsBoundedForCameraMemory() {
        int sample = GalleryImageSize.sampleSize(5456, 3632, 640, 480);
        assertTrue(5456 / sample <= 640);
        assertTrue(3632 / sample <= 480);
        assertEquals(0, sample & (sample - 1));
    }

    @Test
    public void testPortraitImageIsAlsoBoundedByHeight() {
        int sample = GalleryImageSize.sampleSize(480, 1920, 640, 480);
        assertEquals(4, sample);
    }

    @Test
    public void testUnmeasuredViewDoesNotCauseDivisionByZero() {
        assertTrue(GalleryImageSize.sampleSize(640, 480, 0, 0) >= 640);
    }
}
