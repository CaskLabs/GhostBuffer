package com.casklabs.ghostbuffer.ui;

/** Bounds JPEG decoding to the camera display instead of allocating full-resolution images. */
public final class GalleryImageSize {
    private GalleryImageSize() {}

    public static int sampleSize(int width, int height, int maxWidth, int maxHeight) {
        int sample = 1;
        maxWidth = Math.max(1, maxWidth);
        maxHeight = Math.max(1, maxHeight);
        while (sample < (1 << 30) && (width > (long) maxWidth * sample || height > (long) maxHeight * sample)) {
            sample *= 2;
        }
        return sample;
    }
}
