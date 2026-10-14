package com.casklabs.ghostbuffer.preview;

/** Requested JPEG sizes and conservative polling rates for the camera's small heap. */
public enum PreviewResolution {
    VGA(640, 480, 10, 256 * 1024),
    HD(1280, 720, 5, 1024 * 1024),
    FULL_HD(1920, 1080, 3, 2 * 1024 * 1024);

    public final int width;
    public final int height;
    public final int fps;
    public final int maxFrameBytes;

    PreviewResolution(int width, int height, int fps, int maxFrameBytes) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.maxFrameBytes = maxFrameBytes;
    }

    public String label() { return width + "x" + height; }

    public PreviewResolution step(int direction) {
        PreviewResolution[] choices = values();
        return choices[(ordinal() + (direction > 0 ? 1 : -1) + choices.length) % choices.length];
    }

    public static PreviewResolution fromPreference(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException e) { return VGA; }
        catch (NullPointerException e) { return VGA; }
    }

    public static PreviewResolution forDimensions(int width, int height) {
        for (PreviewResolution resolution : values()) {
            if (resolution.width == width && resolution.height == height) return resolution;
        }
        throw new IllegalArgumentException("Unsupported resolution choice: " + width + "x" + height);
    }

    /** Leave room for the independent frozen snapshot, UI, native buffers and other objects. */
    public static long memoryBudget(long maxHeapBytes) {
        return Math.max(3 * 1024 * 1024L, Math.min(8 * 1024 * 1024L, maxHeapBytes / 8));
    }
}
