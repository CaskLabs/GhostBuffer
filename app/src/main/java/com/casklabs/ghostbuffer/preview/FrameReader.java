package com.casklabs.ghostbuffer.preview;

import com.casklabs.ghostbuffer.time.GhostTime;
import com.casklabs.ghostbuffer.buffer.FrameRingBuffer;
import com.casklabs.ghostbuffer.buffer.FrameSlot;

/**
 * Background worker thread polling the preview backend at a scheduled frequency
 * and feeding valid frames into the ring buffer.
 */
public class FrameReader implements Runnable {
    public interface Listener {
        void onFrameRead(FrameSlot slot, float fps, int bufferSize, long coveredDurationMs);
        void onReaderError(Throwable error);
    }

    private final PreviewBackend backend;
    private final FrameRingBuffer ringBuffer;
    private final Listener listener;
    private final long targetIntervalMs;

    private volatile boolean running = false;
    private Thread workerThread;
    private long sequenceCounter = 0;

    // Rolling FPS tracking
    private static final int FPS_WINDOW_SIZE = 10;
    private final long[] frameTimestamps = new long[FPS_WINDOW_SIZE];
    private int fpsIndex = 0;
    private int fpsCount = 0;
    private float currentFps = 0.0f;

    public FrameReader(PreviewBackend backend, FrameRingBuffer ringBuffer, int targetFps, Listener listener) {
        this.backend = backend;
        this.ringBuffer = ringBuffer;
        this.listener = listener;
        this.targetIntervalMs = targetFps > 0 ? (1000L / targetFps) : 100L;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this, "GhostBuffer-FrameReader");
        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(500);
            } catch (InterruptedException ignored) {}
            workerThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public float getCurrentFps() {
        return currentFps;
    }

    public long getSequenceCounter() {
        return sequenceCounter;
    }

    @Override
    public void run() {
        while (running) {
            long cycleStart = GhostTime.uptimeMillis();
            try {
                PreviewFrame frame = backend.readNextFrame();
                if (frame != null && running) {
                    sequenceCounter++;
                    boolean accepted = ringBuffer.push(
                        frame.getData(),
                        frame.getLength(),
                        sequenceCounter,
                        frame.getReadStartMs(),
                        frame.getReadEndMs()
                    );

                    if (accepted) {
                        updateFps(frame.getReadEndMs());
                        if (listener != null) {
                            FrameSlot newest = ringBuffer.getNewest();
                            listener.onFrameRead(newest, currentFps, ringBuffer.size(), ringBuffer.getCoveredDurationMs());
                        }
                    }
                }
            } catch (Throwable t) {
                if (running) {
                    if (listener != null) {
                        listener.onReaderError(t);
                    }
                }
            }

            long cycleElapsed = GhostTime.uptimeMillis() - cycleStart;
            long sleepTime = targetIntervalMs - cycleElapsed;
            if (sleepTime > 0 && running) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private void updateFps(long nowMs) {
        frameTimestamps[fpsIndex] = nowMs;
        fpsIndex = (fpsIndex + 1) % FPS_WINDOW_SIZE;
        if (fpsCount < FPS_WINDOW_SIZE) {
            fpsCount++;
        }

        if (fpsCount >= 2) {
            int oldestIndex = (fpsIndex - fpsCount + FPS_WINDOW_SIZE) % FPS_WINDOW_SIZE;
            long duration = nowMs - frameTimestamps[oldestIndex];
            if (duration > 0) {
                currentFps = (float) ((fpsCount - 1) * 1000.0 / duration);
            }
        }
    }
}
