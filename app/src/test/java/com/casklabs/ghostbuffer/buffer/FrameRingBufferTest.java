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
        // 2000 ms duration, 10 slots, 100 KB budget
        ringBuffer = new FrameRingBuffer(2000L, 10, 100 * 1024L);
    }

    @Test
    public void testEmptyBufferState() {
        assertEquals(0, ringBuffer.size());
        assertEquals(0, ringBuffer.getTotalBytes());
        assertEquals(0, ringBuffer.getCoveredDurationMs());
        assertNull(ringBuffer.getNewest());
        assertNull(ringBuffer.getOldest());
    }

    @Test
    public void testPushSingleFrame() {
        byte[] fakeJpeg = FakePreviewBackend.createFakeJpeg(1, 1000L, 512);
        boolean accepted = ringBuffer.push(fakeJpeg, fakeJpeg.length, 1, 990L, 1000L);

        assertTrue("Valid JPEG frame must be accepted", accepted);
        assertEquals(1, ringBuffer.size());
        assertEquals(fakeJpeg.length, ringBuffer.getTotalBytes());
        assertNotNull(ringBuffer.getNewest());
        assertEquals(1, ringBuffer.getNewest().getSequence());
    }

    @Test
    public void testRejectInvalidJpeg() {
        byte[] invalidData = new byte[] { 0x00, 0x01, 0x02, 0x03 };
        boolean accepted = ringBuffer.push(invalidData, invalidData.length, 1, 0L, 10L);

        assertFalse("Non-JPEG data must be rejected", accepted);
        assertEquals(0, ringBuffer.size());
    }

    @Test
    public void testTimeWindowEviction() {
        // Time window = 2000ms.
        // Push frame at t = 1000ms
        byte[] f1 = FakePreviewBackend.createFakeJpeg(1, 1000L, 256);
        ringBuffer.push(f1, f1.length, 1, 990L, 1000L);

        // Push frame at t = 2000ms
        byte[] f2 = FakePreviewBackend.createFakeJpeg(2, 2000L, 256);
        ringBuffer.push(f2, f2.length, 2, 1990L, 2000L);

        assertEquals(2, ringBuffer.size());

        // Push frame at t = 3500ms (f1 at 1000ms is now older than 3500 - 2000 = 1500ms)
        byte[] f3 = FakePreviewBackend.createFakeJpeg(3, 3500L, 256);
        ringBuffer.push(f3, f3.length, 3, 3490L, 3500L);

        assertEquals("Frame 1 should have been evicted by time window", 2, ringBuffer.size());
        assertEquals(2, ringBuffer.getOldest().getSequence());
        assertEquals(3, ringBuffer.getNewest().getSequence());
    }

    @Test
    public void testSlotCapacityEviction() {
        // Capacity = 10 slots
        for (int i = 1; i <= 15; i++) {
            long time = 1000L + (i * 50); // within 2000ms window
            byte[] f = FakePreviewBackend.createFakeJpeg(i, time, 128);
            ringBuffer.push(f, f.length, i, time - 10, time);
        }

        assertEquals("Slot count must be capped at maxSlots (10)", 10, ringBuffer.size());
        assertEquals("Oldest frame should be sequence 6", 6, ringBuffer.getOldest().getSequence());
        assertEquals("Newest frame should be sequence 15", 15, ringBuffer.getNewest().getSequence());
    }

    @Test
    public void testByteBudgetEvictionAndOversizedRejection() {
        // Create small ring buffer: budget = 3500 bytes (each frame with 1000B pad is ~1.44 KB)
        FrameRingBuffer smallBuffer = new FrameRingBuffer(5000L, 50, 3500L);

        byte[] frame1 = FakePreviewBackend.createFakeJpeg(1, 100L, 1000);
        byte[] frame2 = FakePreviewBackend.createFakeJpeg(2, 200L, 1000);
        byte[] frame3 = FakePreviewBackend.createFakeJpeg(3, 300L, 1000);

        smallBuffer.push(frame1, frame1.length, 1, 90L, 100L);
        smallBuffer.push(frame2, frame2.length, 2, 190L, 200L);
        // Total ~2.88 KB <= 3.5 KB
        assertEquals(2, smallBuffer.size());

        // Pushing frame3 will exceed 3.5 KB, so frame1 must be evicted
        smallBuffer.push(frame3, frame3.length, 3, 290L, 300L);

        assertTrue("Total bytes must remain <= budget", smallBuffer.getTotalBytes() <= 3500L);
        assertEquals(2, smallBuffer.size());
        assertEquals(2, smallBuffer.getOldest().getSequence());
        assertEquals(3, smallBuffer.getNewest().getSequence());

        // Oversized single frame exceeding total budget (3900 bytes > 3500 bytes)
        byte[] hugeFrame = FakePreviewBackend.createFakeJpeg(4, 400L, 3500);
        boolean accepted = smallBuffer.push(hugeFrame, hugeFrame.length, 4, 390L, 400L);
        assertFalse("Frame exceeding entire byte budget must be rejected immediately", accepted);
        assertEquals("Buffer slots must not be evicted by rejected oversized frame", 2, smallBuffer.size());
        assertEquals(2, smallBuffer.getOldest().getSequence());
        assertEquals(3, smallBuffer.getNewest().getSequence());
    }

    @Test
    public void testFreezeSnapshotIntegrity() {
        // Push 5 frames at t = 1000, 1200, 1400, 1600, 1800
        for (int i = 1; i <= 5; i++) {
            long time = 1000L + (i - 1) * 200L;
            byte[] f = FakePreviewBackend.createFakeJpeg(i, time, 256);
            ringBuffer.push(f, f.length, i, time - 20, time);
        }

        // Trigger at t = 1700, window = 2000ms (minTime = -300ms)
        // Frames with readEndMs <= 1700 are frames 1, 2, 3, 4 (readEndMs = 1000, 1200, 1400, 1600)
        List<FrameSlot> snapshot = ringBuffer.freezeSnapshot(1700L);

        assertEquals("Snapshot must contain exactly 4 frames <= triggerMs", 4, snapshot.size());
        assertEquals(1, snapshot.get(0).getSequence());
        assertEquals(4, snapshot.get(3).getSequence());

        // Modifying the ring buffer after freeze must NOT affect the frozen snapshot
        ringBuffer.clear();
        assertEquals(0, ringBuffer.size());
        assertEquals(4, snapshot.size());
    }
}
