package com.casklabs.ghostbuffer.preview;

import com.casklabs.ghostbuffer.buffer.FrameRingBuffer;
import com.casklabs.ghostbuffer.buffer.FrameSlot;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class FrameReaderTest {
    private FakePreviewBackend backend;
    private FrameRingBuffer ringBuffer;
    private FrameReader reader;

    @Before
    public void setUp() throws Exception {
        backend = new FakePreviewBackend();
        backend.open(null);
        backend.startPreviewSequence(640, 480, 20); // 20 FPS (50ms interval)
        ringBuffer = new FrameRingBuffer(2000L, 30, 1024 * 1024L);
    }

    @After
    public void tearDown() {
        if (reader != null) {
            reader.stop();
        }
        backend.close();
    }

    @Test
    public void testFrameReaderPushesToRingBuffer() throws Exception {
        final CountDownLatch latch = new CountDownLatch(5);
        final AtomicInteger callbackCount = new AtomicInteger(0);

        reader = new FrameReader(backend, ringBuffer, 20, new FrameReader.Listener() {
            @Override
            public void onFrameRead(FrameSlot slot, float fps, int bufferSize, long coveredDurationMs) {
                callbackCount.incrementAndGet();
                latch.countDown();
            }

            @Override
            public void onReaderError(Throwable error) {}
        });

        reader.start();
        assertTrue("Reader should be running", reader.isRunning());

        assertTrue("Should read at least 5 frames within 2 seconds", latch.await(2, TimeUnit.SECONDS));
        assertTrue(ringBuffer.size() >= 5);
        assertTrue(reader.getSequenceCounter() >= 5);

        reader.stop();
        assertFalse("Reader should be stopped", reader.isRunning());
    }

    @Test
    public void testFrameReaderHandlesBackendErrorsGracefully() throws Exception {
        final CountDownLatch errorLatch = new CountDownLatch(1);

        backend.setSimulateFailure(true);

        reader = new FrameReader(backend, ringBuffer, 20, new FrameReader.Listener() {
            @Override
            public void onFrameRead(FrameSlot slot, float fps, int bufferSize, long coveredDurationMs) {}

            @Override
            public void onReaderError(Throwable error) {
                errorLatch.countDown();
            }
        });

        reader.start();
        assertTrue("Error listener must be triggered on simulated hardware failure", errorLatch.await(2, TimeUnit.SECONDS));
        reader.stop();
    }
}
