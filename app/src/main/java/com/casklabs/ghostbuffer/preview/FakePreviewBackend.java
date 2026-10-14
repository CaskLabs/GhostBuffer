package com.casklabs.ghostbuffer.preview;

import com.casklabs.ghostbuffer.time.GhostTime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Deterministic preview backend for unit tests, emulators, and offline verification.
 * Generates genuine baseline decodable JPEG images with embedded sequence/timestamp metadata
 * stored within JPEG COM (0xFFFE) comment markers.
 */
public class FakePreviewBackend implements PreviewBackend {
    private boolean opened = false;
    private boolean sequenceRunning = false;
    private long frameIntervalMs = 50L;
    private long sequenceCounter = 0;
    private long simulatedClockMs = -1;
    private boolean simulateNullFrames = false;
    private boolean simulateFailure = false;
    private boolean simulateCorruptedBytes = false;
    private int payloadPaddingBytes = 1024;

    // Minimal valid 64x48 grayscale baseline decodable JPEG
    private static final byte[] BASELINE_JPEG_HEADER = new byte[] {
        (byte)0xFF, (byte)0xD8, // SOI
        (byte)0xFF, (byte)0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // JFIF APP0
        (byte)0xFF, (byte)0xDB, 0x00, 0x43, 0x00, // DQT (64 bytes table 0)
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
        (byte)0xFF, (byte)0xC0, 0x00, 0x0B, 0x08, 0x00, 0x30, 0x00, 0x40, 0x01, 0x01, 0x11, 0x00, // SOF0: 64x48, 1 component
        (byte)0xFF, (byte)0xC4, 0x00, 0x1F, 0x00, // DHT DC
        0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        (byte)0xFF, (byte)0xC4, 0x00, (byte)0xB5, 0x10, // DHT AC
        0x00, 0x02, 0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D,
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
        0x22, 0x71, 0x14, 0x32, (byte)0x81, (byte)0x91, (byte)0xA1, 0x08, 0x23, 0x42, (byte)0xB1, (byte)0xC1, 0x15, 0x52, (byte)0xD1, (byte)0xF0,
        0x24, 0x33, 0x62, 0x72, (byte)0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
        0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
        0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
        0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, (byte)0x83, (byte)0x84, (byte)0x85, (byte)0x86, (byte)0x87, (byte)0x88, (byte)0x89,
        (byte)0x8A, (byte)0x92, (byte)0x93, (byte)0x94, (byte)0x95, (byte)0x96, (byte)0x97, (byte)0x98, (byte)0x99, (byte)0x9A, (byte)0xA2, (byte)0xA3,
        (byte)0xA4, (byte)0xA5, (byte)0xA6, (byte)0xA7, (byte)0xA8, (byte)0xA9, (byte)0xAA, (byte)0xB2, (byte)0xB3, (byte)0xB4, (byte)0xB5, (byte)0xB6,
        (byte)0xB7, (byte)0xB8, (byte)0xB9, (byte)0xBA, (byte)0xC2, (byte)0xC3, (byte)0xC4, (byte)0xC5, (byte)0xC6, (byte)0xC7, (byte)0xC8, (byte)0xC9,
        (byte)0xCA, (byte)0xD2, (byte)0xD3, (byte)0xD4, (byte)0xD5, (byte)0xD6, (byte)0xD7, (byte)0xD8, (byte)0xD9, (byte)0xDA, (byte)0xE1, (byte)0xE2,
        (byte)0xE3, (byte)0xE4, (byte)0xE5, (byte)0xE6, (byte)0xE7, (byte)0xE8, (byte)0xE9, (byte)0xEA, (byte)0xF1, (byte)0xF2, (byte)0xF3, (byte)0xF4,
        (byte)0xF5, (byte)0xF6, (byte)0xF7, (byte)0xF8, (byte)0xF9, (byte)0xFA,
        (byte)0xFF, (byte)0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00 // SOS: 1 component
    };

    // Minimal scan data producing a solid 64x48 image block
    private static final byte[] BASELINE_SCAN_DATA = new byte[] {
        (byte)0xF8, (byte)0x8C, (byte)0x28, (byte)0xA2, (byte)0x80, 0x0A, 0x28, (byte)0xA2,
        (byte)0x80, 0x0A, 0x28, (byte)0xA2, (byte)0x80, 0x0A, 0x28, (byte)0xA2,
        (byte)0x80, 0x0A, 0x28, (byte)0xA2, (byte)0x80, 0x0A, 0x28, (byte)0xA2,
        (byte)0x80, 0x0A, 0x28, (byte)0xA2, (byte)0x80, 0x0A, 0x28, (byte)0xA2,
        (byte)0x80, 0x0A, 0x28, (byte)0xA2, (byte)0x80, 0x0A, 0x28, (byte)0xA2,
        (byte)0x80, 0x0A, 0x28, (byte)0xA2, (byte)0x80, 0x0A, 0x28, (byte)0xA2,
        (byte)0xFF, (byte)0xD9 // EOI
    };

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

    public void setSimulateCorruptedBytes(boolean simulateCorruptedBytes) {
        this.simulateCorruptedBytes = simulateCorruptedBytes;
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
        byte[] jpeg;
        if (simulateCorruptedBytes) {
            jpeg = new byte[] { (byte)0xFF, (byte)0xD8, 0x00, 0x00, 0x00, 0x00, (byte)0xFF, (byte)0xD9 };
        } else {
            jpeg = createFakeJpeg(sequenceCounter, endMs, payloadPaddingBytes);
        }
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
            // Write standard baseline headers
            baos.write(BASELINE_JPEG_HEADER);

            // Write metadata as a standard JPEG COM marker (0xFF, 0xFE, 2-byte-length, payload)
            String meta = "GhostBuffer|seq=" + sequence + "|ts=" + timestampMs;
            byte[] metaBytes = meta.getBytes("UTF-8");
            int comLength = metaBytes.length + 2;
            baos.write(0xFF);
            baos.write(0xFE);
            baos.write((comLength >> 8) & 0xFF);
            baos.write(comLength & 0xFF);
            baos.write(metaBytes);

            // Write optional padding COM marker for byte budget testing
            if (paddingBytes > 0) {
                int padComLen = paddingBytes + 2;
                if (padComLen > 65535) padComLen = 65535;
                baos.write(0xFF);
                baos.write(0xFE);
                baos.write((padComLen >> 8) & 0xFF);
                baos.write(padComLen & 0xFF);
                baos.write(new byte[padComLen - 2]);
            }

            // Write scan data and EOI
            baos.write(BASELINE_SCAN_DATA);
        } catch (IOException ignored) {}

        return baos.toByteArray();
    }
}
