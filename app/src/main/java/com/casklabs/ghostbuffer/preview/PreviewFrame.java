package com.casklabs.ghostbuffer.preview;

/**
 * Container holding raw frame bytes and read timestamps.
 */
public class PreviewFrame {
    private final byte[] data;
    private final int length;
    private final long readStartMs;
    private final long readEndMs;

    public PreviewFrame(byte[] data, int length, long readStartMs, long readEndMs) {
        this.data = data;
        this.length = length;
        this.readStartMs = readStartMs;
        this.readEndMs = readEndMs;
    }

    public byte[] getData() {
        return data;
    }

    public int getLength() {
        return length;
    }

    public long getReadStartMs() {
        return readStartMs;
    }

    public long getReadEndMs() {
        return readEndMs;
    }
}
