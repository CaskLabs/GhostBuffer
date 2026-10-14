package com.casklabs.ghostbuffer.preview;

import com.casklabs.ghostbuffer.time.GhostTime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Deterministic mock backend simulating Sony CameraSequence preview stream
 * for testing and emulation environments.
 */
public class FakePreviewBackend implements PreviewBackend {
    private static final byte[] JPEG_HEADER = new byte[] {
        (byte) 0xFF, (byte) 0xD8, // SOI
        (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, // APP0 marker
        0x4A, 0x46, 0x49, 0x46, 0x00, // 'JFIF'
        0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
    };
    private static final byte[] JPEG_TRAILER = new byte[] { (byte) 0xFF, (byte) 0xD9 }; // EOI

    private boolean opened = false;
    private boolean sequenceRunning = false;
    private long sequenceCounter = 0;
    private long simulatedClockMs = -1;
    private long frameIntervalMs = 100L; // 10 FPS = 100ms
    private boolean simulateNullFrames = false;
    private boolean simulateFailure = false;
    private int payloadPaddingBytes = 1024; // Generates ~1.1KB frames by default

    public FakePreviewBackend() {}

    public void setSimulatedClockMs(long clockMs) {
        this.simulatedClockMs = clockMs;
    }

    public void setFrameIntervalMs(long intervalMs) {
        this.frameIntervalMs = intervalMs;
    }

    public void setSimulateNullFrames(boolean simulateNullFrames) {
        this.simulateNullFrames = simulateNullFrames;
    }

    public void setSimulateFailure(boolean simulateFailure) {
        this.simulateFailure = simulateFailure;
    }

    public void setPayloadPaddingBytes(int bytes) {
        this.payloadPaddingBytes = bytes;
    }

    @Override
    public void open(Object cameraContext) throws Exception {
        opened = true;
    }

    @Override
    public void startPreviewSequence(int width, int height, int fps) throws Exception {
        if (!opened) {
            throw new IllegalStateException("Backend not open");
        }
        if (fps > 0) {
            this.frameIntervalMs = 1000L / fps;
        }
        sequenceRunning = true;
    }

    @Override
    public PreviewFrame readNextFrame() throws Exception {
        if (!sequenceRunning) {
            throw new IllegalStateException("Preview sequence is not running");
        }
        if (simulateFailure) {
            throw new IOException("Simulated hardware preview failure");
        }
        if (simulateNullFrames) {
            return null;
        }

        long startMs;
        long endMs;
        if (simulatedClockMs >= 0) {
            startMs = simulatedClockMs;
            simulatedClockMs += 10;
            endMs = simulatedClockMs;
            simulatedClockMs += Math.max(0, frameIntervalMs - 10);
        } else {
            long now = GhostTime.uptimeMillis();
            startMs = now - 10;
            endMs = now;
        }

        sequenceCounter++;
        byte[] jpeg = createFakeJpeg(sequenceCounter, endMs, payloadPaddingBytes);
        return new PreviewFrame(jpeg, jpeg.length, startMs, endMs);
    }

    @Override
    public void stopPreviewSequence() {
        sequenceRunning = false;
    }

    @Override
    public void close() {
        stopPreviewSequence();
        opened = false;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    public static byte[] createFakeJpeg(long sequence, long timestampMs, int paddingBytes) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(JPEG_HEADER);

            // COM marker (0xFF, 0xFE)
            String comment = "Seq:" + sequence + ";Time:" + timestampMs;
            byte[] commentBytes = comment.getBytes("UTF-8");
            int comLength = commentBytes.length + 2;
            baos.write(0xFF);
            baos.write(0xFE);
            baos.write((comLength >> 8) & 0xFF);
            baos.write(comLength & 0xFF);
            baos.write(commentBytes);

            // Dummy scan payload
            if (paddingBytes > 0) {
                byte[] pad = new byte[paddingBytes];
                for (int i = 0; i < paddingBytes; i++) {
                    pad[i] = (byte) ((i % 128) + 1);
                }
                baos.write(pad);
            }

            baos.write(JPEG_TRAILER);
        } catch (IOException ignored) {}
        return baos.toByteArray();
    }
}
