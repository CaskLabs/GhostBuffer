package com.casklabs.ghostbuffer.capture;

import com.casklabs.ghostbuffer.time.GhostTime;
import com.casklabs.ghostbuffer.buffer.FrameRingBuffer;
import com.casklabs.ghostbuffer.buffer.FrameSlot;
import com.casklabs.ghostbuffer.preview.FrameReader;
import com.casklabs.ghostbuffer.preview.PreviewBackend;
import com.casklabs.ghostbuffer.storage.GhostStorage;

import java.io.File;
import java.util.List;

/**
 * State machine controller coordinating preview, ring buffering, shutter triggers, and saving.
 */
public class CaptureController implements FrameReader.Listener, CaptureWriter.Listener {
    public enum State {
        IDLE,
        INITIALIZING,
        BUFFERING,
        FROZEN,
        SAVING,
        REVIEW,
        PAUSED,
        ERROR
    }

    public interface StateListener {
        void onStateChanged(State newState, String message);
        void onStatsUpdated(float fps, int frameCount, long coveredDurationMs);
        void onSaveProgress(int current, int total);
        void onSaveComplete(File sessionDir, CaptureManifest manifest);
        void onError(Throwable error);
    }

    private final PreviewBackend backend;
    private final FrameRingBuffer ringBuffer;
    private final GhostStorage storage;
    private final CaptureWriter writer;
    private final StateListener listener;

    private State state = State.IDLE;
    private FrameReader frameReader;
    private boolean shutterPressed = false;
    private List<FrameSlot> lastFrozenSnapshot = null;
    private long lastTriggerUptimeMs = 0;
    private long lastTriggerWallClockMs = 0;

    private int previewWidth = 640;
    private int previewHeight = 480;
    private int targetFps = 10;
    private String cameraModel = "ILCE-5000";
    private String appVersion = "1.0.0";

    public CaptureController(
        PreviewBackend backend,
        FrameRingBuffer ringBuffer,
        GhostStorage storage,
        StateListener listener
    ) {
        this.backend = backend;
        this.ringBuffer = ringBuffer;
        this.storage = storage;
        this.writer = new CaptureWriter(storage);
        this.listener = listener;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized void setCameraModel(String model) {
        this.cameraModel = model;
    }

    public synchronized void setAppVersion(String version) {
        this.appVersion = version;
    }

    public synchronized void setPreviewDimensions(int width, int height, int fps) {
        this.previewWidth = width;
        this.previewHeight = height;
        this.targetFps = fps;
    }

    private void changeState(State newState, String message) {
        this.state = newState;
        if (listener != null) {
            listener.onStateChanged(newState, message);
        }
    }

    public synchronized void start(Object cameraContext) {
        if (state == State.BUFFERING || state == State.SAVING) {
            return;
        }

        changeState(State.INITIALIZING, "Initializing camera...");
        try {
            backend.open(cameraContext);
            backend.startPreviewSequence(previewWidth, previewHeight, targetFps);

            frameReader = new FrameReader(backend, ringBuffer, targetFps, this);
            frameReader.start();

            changeState(State.BUFFERING, "Buffer active");
        } catch (Throwable t) {
            changeState(State.ERROR, "Startup error: " + t.getMessage());
            if (listener != null) {
                listener.onError(t);
            }
        }
    }

    public synchronized void stop() {
        if (frameReader != null) {
            frameReader.stop();
            frameReader = null;
        }
        backend.stopPreviewSequence();
        backend.close();
        ringBuffer.clear();
        changeState(State.IDLE, "Inactive");
    }

    public synchronized void pause() {
        if (frameReader != null) {
            frameReader.stop();
            frameReader = null;
        }
        backend.stopPreviewSequence();
        backend.close();
        if (state != State.SAVING) {
            changeState(State.PAUSED, "Paused");
        }
    }

    /**
     * Handles hardware shutter key down event. Guarantees exactly one capture per press.
     * Ignores repeated key down events caused by holding the button.
     */
    public synchronized boolean onShutterKeyDown() {
        if (shutterPressed) {
            // Ignore repeat
            return false;
        }
        shutterPressed = true;

        if (state != State.BUFFERING) {
            return false;
        }

        lastTriggerUptimeMs = GhostTime.uptimeMillis();
        lastTriggerWallClockMs = System.currentTimeMillis();

        // Atomically freeze buffer snapshot
        lastFrozenSnapshot = ringBuffer.freezeSnapshot(lastTriggerUptimeMs);

        if (lastFrozenSnapshot.isEmpty()) {
            return false;
        }

        changeState(State.FROZEN, "Buffer frozen (" + lastFrozenSnapshot.size() + " frames)");
        changeState(State.SAVING, "Saving...");

        // Pause frame reader while saving
        if (frameReader != null) {
            frameReader.stop();
        }

        writer.writeSnapshotAsync(
            lastFrozenSnapshot,
            lastTriggerUptimeMs,
            lastTriggerWallClockMs,
            ringBuffer.getBufferDurationMs(),
            targetFps,
            frameReader != null ? frameReader.getCurrentFps() : 0.0,
            previewWidth,
            previewHeight,
            cameraModel,
            appVersion,
            this
        );

        return true;
    }

    /**
     * Handles hardware shutter key release. Resets key latch.
     */
    public synchronized boolean onShutterKeyUp() {
        shutterPressed = false;
        return true;
    }

    public synchronized void retrySave() {
        if (lastFrozenSnapshot == null || lastFrozenSnapshot.isEmpty()) {
            return;
        }

        changeState(State.SAVING, "Retrying save...");
        writer.writeSnapshotAsync(
            lastFrozenSnapshot,
            lastTriggerUptimeMs,
            lastTriggerWallClockMs,
            ringBuffer.getBufferDurationMs(),
            targetFps,
            frameReader != null ? frameReader.getCurrentFps() : 0.0,
            previewWidth,
            previewHeight,
            cameraModel,
            appVersion,
            this
        );
    }

    public synchronized void resumeBuffering() {
        lastFrozenSnapshot = null;
        ringBuffer.clear();
        if (frameReader != null && !frameReader.isRunning()) {
            frameReader.start();
        }
        changeState(State.BUFFERING, "Buffer active");
    }

    @Override
    public void onFrameRead(FrameSlot slot, float fps, int bufferSize, long coveredDurationMs) {
        if (state == State.BUFFERING && listener != null) {
            listener.onStatsUpdated(fps, bufferSize, coveredDurationMs);
        }
    }

    @Override
    public void onReaderError(Throwable error) {
        if (state == State.BUFFERING) {
            changeState(State.ERROR, "Read error: " + error.getMessage());
            if (listener != null) {
                listener.onError(error);
            }
        }
    }

    @Override
    public synchronized void onSaveProgress(int current, int total) {
        if (listener != null) {
            listener.onSaveProgress(current, total);
        }
    }

    @Override
    public synchronized void onSaveComplete(File sessionDir, CaptureManifest manifest) {
        changeState(State.REVIEW, "Saved: " + manifest.getFrameCount() + " frames");
        if (listener != null) {
            listener.onSaveComplete(sessionDir, manifest);
        }
    }

    @Override
    public synchronized void onSaveError(Throwable error) {
        changeState(State.ERROR, "Save error: " + error.getMessage());
        if (listener != null) {
            listener.onError(error);
        }
    }

    public List<FrameSlot> getLastFrozenSnapshot() {
        return lastFrozenSnapshot;
    }
}
