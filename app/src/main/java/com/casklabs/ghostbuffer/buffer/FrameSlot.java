package com.casklabs.ghostbuffer.buffer;

import java.util.Arrays;

/**
 * Represents a single captured preview frame held in the ring buffer.
 */
public class FrameSlot {
    private final byte[] data;
    private final int length;
    private final long sequence;
    private final long readStartMs;
    private final long readEndMs;

    public FrameSlot(byte[] data, int length, long sequence, long readStartMs, long readEndMs) {
        this.data = data;
        this.length = length;
        this.sequence = sequence;
        this.readStartMs = readStartMs;
        this.readEndMs = readEndMs;
    }

    public byte[] getData() {
        return data;
    }

    public int getLength() {
        return length;
    }

    public long getSequence() {
        return sequence;
    }

    public long getReadStartMs() {
        return readStartMs;
    }

    public long getReadEndMs() {
        return readEndMs;
    }

    public long getReadDurationMs() {
        return Math.max(0, readEndMs - readStartMs);
    }

    /**
     * Checks if the data begins with JPEG SOI marker (0xFF, 0xD8).
     */
    public boolean isJpegValid() {
        if (data == null || length < 4) {
            return false;
        }
        return (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }

    /**
     * Creates an immutable copy with an independent byte array for frozen snapshots.
     */
    public FrameSlot copy() {
        byte[] copyData = Arrays.copyOf(data, length);
        return new FrameSlot(copyData, length, sequence, readStartMs, readEndMs);
    }
}
