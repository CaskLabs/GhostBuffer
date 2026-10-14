package com.casklabs.ghostbuffer.capture;

import com.casklabs.ghostbuffer.time.GhostTime;
import com.casklabs.ghostbuffer.buffer.FrameRingBuffer;
import com.casklabs.ghostbuffer.buffer.FrameSlot;
import com.casklabs.ghostbuffer.preview.FrameReader;
import com.casklabs.ghostbuffer.preview.PreviewBackend;
import com.casklabs.ghostbuffer.probe.GhostProbeManager;
import com.casklabs.ghostbuffer.storage.GhostStorage;

import java.io.File;
import java.util.List;

/**
 * State machine controller coordinating preview, ring buffering, shutter triggers, and saving.
 * Safely coordinates thread access, prevents stale callbacks when paused, and provides error recovery.
 */
public class CaptureController implements FrameReader.Listener, CaptureWriter.Listener {
    public interface CameraAdjustment {
        void run();
    }
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
    private boolean isForeground = false;
    private boolean pausedDuringSave = false;
    private boolean shutterPressed = false;
    private List<FrameSlot> lastFrozenSnapshot = null;
    private long lastTriggerUptimeMs = 0;
    private long lastTriggerWallClockMs = 0;

    private int previewWidth = 640;
    private int previewHeight = 480;
    private int targetFps = 10;
    private String cameraModel = "ILCE-5000";
    private String cameraFirmware = "1.00";
    private String appVersion = "1.0.0";
    private Object currentCameraContext = null;

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

    public synchronized boolean isForeground() {
        return isForeground;
    }

    public synchronized void setCameraModel(String model) {
        this.cameraModel = model;
    }

    public synchronized void setCameraFirmware(String firmware) {
        this.cameraFirmware = firmware;
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
        this.isForeground = true;
        this.currentCameraContext = cameraContext;

        if (state == State.BUFFERING && frameReader != null && frameReader.isRunning()) return;

        if (state == State.SAVING) {
            pausedDuringSave = false;
            return;
        }

        changeState(State.INITIALIZING, "Initializing camera...");
        try {
            backend.open(cameraContext);
            backend.startPreviewSequence(previewWidth, previewHeight, targetFps);

            if (frameReader != null) {
                frameReader.stop();
            }
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
        isForeground = false;
        if (frameReader != null) {
            frameReader.stop();
            frameReader = null;
        }
        backend.stopPreviewSequence();
        backend.close();
        ringBuffer.clear();
        lastFrozenSnapshot = null;
        changeState(State.IDLE, "Inactive");
    }

    public synchronized void pause() {
        isForeground = false;
        if (frameReader != null) {
            frameReader.stop();
            frameReader = null;
        }
        backend.stopPreviewSequence();
        backend.close();

        if (state == State.SAVING) {
            pausedDuringSave = true;
        } else {
            changeState(State.PAUSED, "Paused");
        }
    }

    /**
     * Executes single probe safely serialized with FrameReader.
     */
    public synchronized GhostProbeManager.ProbeResult runProbe(GhostProbeManager probeManager) {
        boolean wasRunning = (frameReader != null && frameReader.isRunning());
        if (wasRunning) {
            frameReader.stop();
        }

        try {
            return probeManager.runSingleProbe(backend, cameraModel, cameraFirmware);
        } finally {
            if (wasRunning && isForeground) {
                frameReader = new FrameReader(backend, ringBuffer, targetFps, this);
                frameReader.start();
            }
        }
    }

    /** Serialize camera settings with preview reads and lifecycle shutdown. */
    public synchronized void runCameraAdjustment(CameraAdjustment adjustment, boolean resetBuffer) {
        if (!isForeground || state != State.BUFFERING) {
            throw new IllegalStateException("Wait until the live buffer is active");
        }
        if (resetBuffer && frameReader != null) frameReader.stop();
        try {
            // A native frame read may outlive the reader's bounded join timeout.
            synchronized (backend) { adjustment.run(); }
        } finally {
            // Even a partially applied setting must not mix old and new exposure frames.
            if (resetBuffer) ringBuffer.clear();
            if (resetBuffer && isForeground && state == State.BUFFERING) {
                frameReader = new FrameReader(backend, ringBuffer, targetFps, this);
                frameReader.start();
            }
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
        lastTriggerWallClockMs = GhostTime.currentTimeMillis();

        // Atomically freeze buffer snapshot
        lastFrozenSnapshot = ringBuffer.freezeSnapshot(lastTriggerUptimeMs);

        if (lastFrozenSnapshot == null || lastFrozenSnapshot.isEmpty()) {
            return false;
        }

        changeState(State.FROZEN, "Buffer frozen (" + lastFrozenSnapshot.size() + " frames)");
        changeState(State.SAVING, "Saving...");

        // Pause frame reader while saving
        if (frameReader != null) {
            frameReader.stop();
            frameReader = null;
        }

        writer.writeSnapshotAsync(
            lastFrozenSnapshot,
            lastTriggerUptimeMs,
            lastTriggerWallClockMs,
            ringBuffer.getBufferDurationMs(),
            targetFps,
            10.0,
            previewWidth,
            previewHeight,
            cameraModel,
            cameraFirmware,
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
        if (state != State.ERROR || lastFrozenSnapshot == null || lastFrozenSnapshot.isEmpty()) {
            return;
        }

        changeState(State.SAVING, "Retrying save...");
        writer.writeSnapshotAsync(
            lastFrozenSnapshot,
            lastTriggerUptimeMs,
            lastTriggerWallClockMs,
            ringBuffer.getBufferDurationMs(),
            targetFps,
            10.0,
            previewWidth,
            previewHeight,
            cameraModel,
            cameraFirmware,
            appVersion,
            this
        );
    }

    public synchronized void discardSnapshotAndResume() {
        lastFrozenSnapshot = null;
        ringBuffer.clear();

        if (isForeground) {
            try {
                backend.open(currentCameraContext);
                backend.startPreviewSequence(previewWidth, previewHeight, targetFps);
                if (frameReader != null) {
                    frameReader.stop();
                }
                frameReader = new FrameReader(backend, ringBuffer, targetFps, this);
                frameReader.start();
                changeState(State.BUFFERING, "Buffer active");
            } catch (Throwable t) {
                changeState(State.ERROR, "Resume failed: " + t.getMessage());
            }
        } else {
            changeState(State.PAUSED, "Paused");
        }
    }

    public synchronized void resumeBuffering() {
        if (!isForeground) {
            return;
        }

        lastFrozenSnapshot = null;
        ringBuffer.clear();

        try {
            if (frameReader == null || !frameReader.isRunning()) {
                backend.open(currentCameraContext);
                backend.startPreviewSequence(previewWidth, previewHeight, targetFps);
                frameReader = new FrameReader(backend, ringBuffer, targetFps, this);
                frameReader.start();
            }
            changeState(State.BUFFERING, "Buffer active");
        } catch (Throwable t) {
            changeState(State.ERROR, "Resume failed: " + t.getMessage());
        }
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
    public void onReaderStale() {
        if (state == State.BUFFERING) {
            changeState(State.BUFFERING, "Buffer active (waiting for frames...)");
            if (listener != null) {
                listener.onStatsUpdated(0.0f, ringBuffer.size(), ringBuffer.getCoveredDurationMs());
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
        if (!isForeground) {
            changeState(State.PAUSED, "Saved while paused (" + manifest.getFrameCount() + " frames)");
            pausedDuringSave = false;
            return;
        }

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
