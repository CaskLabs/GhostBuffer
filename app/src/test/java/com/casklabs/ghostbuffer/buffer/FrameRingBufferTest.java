package com.casklabs.ghostbuffer.buffer;

import com.casklabs.ghostbuffer.preview.FakePreviewBackend;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class FrameRingBufferTest {
    private FrameRingBuffer ringBuffer;

    @Before
    public void setUp() {
        // 2000 ms duration, 10 slots capacity, 50 KB byte budget
        ringBuffer = new FrameRingBuffer(2000L, 10, 50 * 1024L);
    }

    private byte[] makeSampleJpeg(int sequence, long timeMs) {
        return FakePreviewBackend.createFakeJpeg(sequence, timeMs, 100);
    }

    @Test
    public void testPushValidFrame() {
        byte[] jpeg = makeSampleJpeg(1, 1000L);
        boolean pushed = ringBuffer.push(jpeg, jpeg.length, 1, 990L, 1000L);

        assertTrue("Valid JPEG should be accepted", pushed);
        assertEquals(1, ringBuffer.size());
        assertEquals(jpeg.length, ringBuffer.getTotalBytes());
        assertEquals(1, ringBuffer.getNewest().getSequence());
    }

    @Test
    public void testRejectInvalidData() {
        // Null data
        assertFalse(ringBuffer.push(null, 10, 1, 0, 10));

        // Negative or zero length
        assertFalse(ringBuffer.push(new byte[10], 0, 1, 0, 10));

        // Non-JPEG bytes (does not start with 0xFF 0xD8)
        byte[] notJpeg = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04 };
        assertFalse(ringBuffer.push(notJpeg, notJpeg.length, 1, 0, 10));

        assertEquals(0, ringBuffer.size());
    }

    @Test
    public void testTimeWindowEviction() {
        // Push frame at t = 1000ms
        byte[] f1 = makeSampleJpeg(1, 1000L);
        ringBuffer.push(f1, f1.length, 1, 990L, 1000L);

        // Push frame at t = 2000ms
        byte[] f2 = makeSampleJpeg(2, 2000L);
        ringBuffer.push(f2, f2.length, 2, 1990L, 2000L);

        // Push frame at t = 3000ms (window: 3000 - 2000 = 1000, f1 at 1000 is still in or at boundary)
        byte[] f3 = makeSampleJpeg(3, 3000L);
        ringBuffer.push(f3, f3.length, 3, 2990L, 3000L);

        // Push frame at t = 3100ms (cutoff is 1100ms; f1 at 1000ms MUST be evicted)
        byte[] f4 = makeSampleJpeg(4, 3100L);
        ringBuffer.push(f4, f4.length, 4, 3090L, 3100L);

        assertEquals(3, ringBuffer.size());
        assertEquals(2, ringBuffer.getOldest().getSequence());
        assertEquals(4, ringBuffer.getNewest().getSequence());
    }

    @Test
    public void testSlotCapacityEviction() {
        // Ring buffer configured for max 10 slots
        for (int i = 1; i <= 15; i++) {
            long time = 1000L + (i * 50); // 50ms interval, total span 750ms (< 2000ms window)
            byte[] frame = makeSampleJpeg(i, time);
            ringBuffer.push(frame, frame.length, i, time - 10, time);
        }

        assertEquals("Slot count must be capped at maxSlots (10)", 10, ringBuffer.size());
        assertEquals("Oldest frame should be sequence 6", 6, ringBuffer.getOldest().getSequence());
        assertEquals("Newest frame should be sequence 15", 15, ringBuffer.getNewest().getSequence());
    }

    @Test
    public void testByteBudgetEviction() {
        // Create small ring buffer: budget = 4000 bytes
        FrameRingBuffer smallBuffer = new FrameRingBuffer(5000L, 50, 4000L);

        byte[] frame1 = FakePreviewBackend.createFakeJpeg(1, 100L, 1500); // ~1.6 KB
        byte[] frame2 = FakePreviewBackend.createFakeJpeg(2, 200L, 1500); // ~1.6 KB
        byte[] frame3 = FakePreviewBackend.createFakeJpeg(3, 300L, 1500); // ~1.6 KB

        smallBuffer.push(frame1, frame1.length, 1, 90L, 100L);
        smallBuffer.push(frame2, frame2.length, 2, 190L, 200L);
        // Total ~3.2 KB <= 4.0 KB

        assertEquals(2, smallBuffer.size());

        // Pushing frame3 will exceed 4.0 KB, so frame1 must be evicted
        smallBuffer.push(frame3, frame3.length, 3, 290L, 300L);

        assertTrue("Total bytes must remain <= budget", smallBuffer.getTotalBytes() <= 4000L);
        assertEquals(2, smallBuffer.size());
        assertEquals(2, smallBuffer.getOldest().getSequence());
        assertEquals(3, smallBuffer.getNewest().getSequence());
    }

    @Test
    public void testFreezeSnapshotIntegrity() {
        // Push 5 frames at t = 1000, 1200, 1400, 1600, 1800
        for (int i = 1; i <= 5; i++) {
            long time = 1000L + (i - 1) * 200L;
            byte[] frame = makeSampleJpeg(i, time);
            ringBuffer.push(frame, frame.length, i, time - 10, time);
        }

        // Trigger at t = 1800 (window: 1800 - 2000 = -200 to 1800) -> all 5 frames
        List<FrameSlot> snapshot = ringBuffer.freezeSnapshot(1800L);
        assertEquals(5, snapshot.size());
        assertEquals(1, snapshot.get(0).getSequence());
        assertEquals(5, snapshot.get(4).getSequence());

        // Verify deep copy: pushing more frames does not alter the frozen snapshot
        byte[] frame6 = makeSampleJpeg(6, 2000L);
        ringBuffer.push(frame6, frame6.length, 6, 1990L, 2000L);

        assertEquals("Live buffer has 6 frames", 6, ringBuffer.size());
        assertEquals("Frozen snapshot remains untouched with 5 frames", 5, snapshot.size());

        // Clear live buffer: frozen snapshot still has all data
        ringBuffer.clear();
        assertEquals(0, ringBuffer.size());
        assertEquals(5, snapshot.size());
        assertTrue(snapshot.get(0).isJpegValid());
    }

    @Test
    public void testFreezeSnapshotWindowFiltering() {
        // Buffer duration is 2000ms
        // Frames at t = 1000, 2000, 3000, 4000
        byte[] f1 = makeSampleJpeg(1, 1000L);
        byte[] f2 = makeSampleJpeg(2, 2000L);
        byte[] f3 = makeSampleJpeg(3, 3000L);
        byte[] f4 = makeSampleJpeg(4, 4000L);

        ringBuffer.push(f1, f1.length, 1, 990L, 1000L);
        ringBuffer.push(f2, f2.length, 2, 1990L, 2000L);
        ringBuffer.push(f3, f3.length, 3, 2990L, 3000L);
        // Note: f1 was evicted when f3 (3000 - 2000 = 1000) or f4 was pushed

        // Freeze at t = 3500ms (window: 1500ms to 3500ms)
        List<FrameSlot> snapshot = ringBuffer.freezeSnapshot(3500L);
        for (FrameSlot slot : snapshot) {
            assertTrue(slot.getReadEndMs() >= 1500L);
            assertTrue(slot.getReadEndMs() <= 3500L);
        }
    }

    @Test
    public void testCoveredDurationCalculation() {
        assertEquals(0, ringBuffer.getCoveredDurationMs());

        byte[] f1 = makeSampleJpeg(1, 1000L);
        ringBuffer.push(f1, f1.length, 1, 990L, 1000L);
        assertEquals(0, ringBuffer.getCoveredDurationMs());

        byte[] f2 = makeSampleJpeg(2, 1750L);
        ringBuffer.push(f2, f2.length, 2, 1740L, 1750L);
        assertEquals(750L, ringBuffer.getCoveredDurationMs());
    }
}
