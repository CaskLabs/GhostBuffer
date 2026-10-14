package com.casklabs.ghostbuffer.buffer;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Thread-safe rolling ring buffer with time-window, slot capacity, and byte budget eviction.
 */
public class FrameRingBuffer {
    public static final long DEFAULT_DURATION_MS = 2000L;
    public static final int DEFAULT_MAX_SLOTS = 25;
    public static final long DEFAULT_MAX_BYTE_BUDGET = 3 * 1024 * 1024L; // 3 MiB

    private final long bufferDurationMs;
    private final int maxSlots;
    private final long maxByteBudget;

    private final LinkedList<FrameSlot> slots = new LinkedList<FrameSlot>();
    private long totalBytes = 0;

    public FrameRingBuffer() {
        this(DEFAULT_DURATION_MS, DEFAULT_MAX_SLOTS, DEFAULT_MAX_BYTE_BUDGET);
    }

    public FrameRingBuffer(long bufferDurationMs, int maxSlots, long maxByteBudget) {
        this.bufferDurationMs = bufferDurationMs;
        this.maxSlots = maxSlots;
        this.maxByteBudget = maxByteBudget;
    }

    public long getBufferDurationMs() {
        return bufferDurationMs;
    }

    public int getMaxSlots() {
        return maxSlots;
    }

    public long getMaxByteBudget() {
        return maxByteBudget;
    }

    /**
     * Pushes a new frame into the ring buffer. Evicts frames older than the time window,
     * or frames exceeding max capacity and byte budget.
     */
    public synchronized boolean push(byte[] data, int length, long sequence, long readStartMs, long readEndMs) {
        if (data == null || length <= 0 || length > data.length || length > maxByteBudget) {
            return false;
        }

        FrameSlot newSlot = new FrameSlot(data, length, sequence, readStartMs, readEndMs);
        if (!newSlot.isJpegValid()) {
            return false;
        }

        // 1. Evict frames older than current time window
        long cutoffTime = readEndMs - bufferDurationMs;
        while (!slots.isEmpty() && slots.getFirst().getReadEndMs() < cutoffTime) {
            FrameSlot evicted = slots.removeFirst();
            totalBytes -= evicted.getLength();
        }

        // 2. Enforce slot capacity
        while (slots.size() >= maxSlots) {
            FrameSlot evicted = slots.removeFirst();
            totalBytes -= evicted.getLength();
        }

        // 3. Enforce byte budget
        while (!slots.isEmpty() && (totalBytes + length > maxByteBudget)) {
            FrameSlot evicted = slots.removeFirst();
            totalBytes -= evicted.getLength();
        }

        if (totalBytes + length > maxByteBudget) {
            return false;
        }

        slots.addLast(newSlot);
        totalBytes += length;
        return true;
    }

    /**
     * Atomically creates an immutable snapshot of frames covering the trigger window.
     * Frames are strictly filtered: triggerMs - bufferDurationMs <= frame.readEndMs <= triggerMs.
     */
    public synchronized List<FrameSlot> freezeSnapshot(long triggerMs) {
        long minTime = triggerMs - bufferDurationMs;
        List<FrameSlot> snapshot = new ArrayList<FrameSlot>();
        for (FrameSlot slot : slots) {
            long frameTime = slot.getReadEndMs();
            if (frameTime >= minTime && frameTime <= triggerMs) {
                snapshot.add(slot.copy());
            }
        }
        return snapshot;
    }

    public synchronized void clear() {
        slots.clear();
        totalBytes = 0;
    }

    public synchronized int size() {
        return slots.size();
    }

    public synchronized long getTotalBytes() {
        return totalBytes;
    }

    public synchronized FrameSlot getNewest() {
        return slots.isEmpty() ? null : slots.getLast();
    }

    public synchronized FrameSlot getOldest() {
        return slots.isEmpty() ? null : slots.getFirst();
    }

    public synchronized long getCoveredDurationMs() {
        if (slots.isEmpty()) {
            return 0;
        }
        long oldest = slots.getFirst().getReadEndMs();
        long newest = slots.getLast().getReadEndMs();
        return Math.max(0, newest - oldest);
    }

    public synchronized List<FrameSlot> getSlotsReadOnly() {
        return new ArrayList<FrameSlot>(slots);
    }
}
