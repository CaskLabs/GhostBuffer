package com.casklabs.ghostbuffer.preview;

import com.casklabs.ghostbuffer.time.GhostTime;
import java.io.IOException;

/** Validates real JPEG sizes and restarts a fresh native sequence for each smaller fallback. */
public final class ResolutionPreviewBackend implements PreviewBackend {
    public interface Cancellation { boolean isCancelled(); }

    public static final class Selection {
        public final PreviewResolution requested;
        public final PreviewResolution active;
        public final int width;
        public final int height;

        Selection(PreviewResolution requested, PreviewResolution active, int width, int height) {
            this.requested = requested;
            this.active = active;
            this.width = width;
            this.height = height;
        }

        public String actualLabel() { return width + "x" + height; }
        public boolean fellBack() { return requested.width != width || requested.height != height; }
    }

    private final PreviewBackend delegate;
    private final Cancellation cancellation;
    private final long frameTimeoutMs;
    private volatile int cancellationGeneration;
    private volatile Selection selection;
    private Object cameraContext;
    private PreviewFrame firstFrame;
    private boolean running;

    public ResolutionPreviewBackend(PreviewBackend delegate, Cancellation cancellation) {
        this(delegate, cancellation, 2000L);
    }

    ResolutionPreviewBackend(PreviewBackend delegate, Cancellation cancellation, long frameTimeoutMs) {
        this.delegate = delegate;
        this.cancellation = cancellation;
        this.frameTimeoutMs = frameTimeoutMs;
    }

    public Selection getSelection() { return selection; }

    // Deliberately does not take the native monitor: lifecycle shutdown can cancel a pending check.
    public void cancelPending() { cancellationGeneration++; }

    @Override public boolean isAvailable() { return delegate.isAvailable(); }

    @Override public synchronized void open(Object cameraContext) throws Exception {
        this.cameraContext = cameraContext;
        delegate.open(cameraContext);
    }

    @Override public synchronized void startPreviewSequence(int width, int height, int fps) throws Exception {
        PreviewResolution requested = PreviewResolution.forDimensions(width, height);
        if (running && selection != null && selection.requested == requested) return;
        int generation = cancellationGeneration;
        Selection previous = selection;
        selection = null;
        firstFrame = null;
        running = false;
        // Reuse an already validated fallback when returning from the gallery in the same process.
        int candidateIndex = previous != null && previous.requested == requested
            ? previous.active.ordinal() : requested.ordinal();
        Exception lastError = null;
        for (int i = candidateIndex; i >= 0; i--) {
            PreviewResolution candidate = PreviewResolution.values()[i];
            try {
                checkCancelled(generation);
                delegate.close();
                checkCancelled(generation);
                delegate.open(cameraContext);
                delegate.startPreviewSequence(candidate.width, candidate.height, candidate.fps);
                PreviewFrame frame = awaitFrame(generation, candidate);
                int[] dimensions = JpegDimensions.read(frame.getData(), frame.getLength());
                checkCancelled(generation);
                selection = new Selection(requested, candidate, dimensions[0], dimensions[1]);
                firstFrame = frame;
                running = true;
                return;
            } catch (InterruptedException e) {
                delegate.close();
                throw e;
            } catch (Exception e) {
                lastError = e;
            } catch (LinkageError e) {
                lastError = new IOException("Preview setting unavailable: " + e.getMessage());
            } catch (OutOfMemoryError e) {
                lastError = new IOException("Not enough memory for " + candidate.label());
            }
        }
        delegate.close();
        throw new IOException("No usable preview resolution: "
            + (lastError == null ? "no JPEG frames" : lastError.getMessage()));
    }

    private PreviewFrame awaitFrame(int generation, PreviewResolution candidate) throws Exception {
        long deadline = GhostTime.uptimeMillis() + frameTimeoutMs;
        String reason = "Camera did not deliver a JPEG frame";
        do {
            checkCancelled(generation);
            PreviewFrame frame = delegate.readNextFrame();
            if (frame != null) {
                int[] dimensions = JpegDimensions.read(frame.getData(), frame.getLength());
                if (dimensions != null) {
                    boolean exact = dimensions[0] == candidate.width && dimensions[1] == candidate.height;
                    // VGA can be smaller or aspect-specific on some camera models.
                    boolean safeVga = candidate == PreviewResolution.VGA
                        && dimensions[0] <= candidate.width && dimensions[1] <= candidate.height;
                    if ((exact || safeVga) && frame.getLength() <= candidate.maxFrameBytes) return frame;
                    reason = "Requested " + candidate.label() + " but received "
                        + dimensions[0] + "x" + dimensions[1] + " (" + frame.getLength() + " bytes)";
                } else reason = "Camera returned an invalid JPEG header";
            }
            Thread.sleep(20);
        } while (GhostTime.uptimeMillis() < deadline);
        throw new IOException(reason);
    }

    private void checkCancelled(int generation) throws InterruptedException {
        if (generation != cancellationGeneration || Thread.currentThread().isInterrupted()
            || (cancellation != null && cancellation.isCancelled())) {
            throw new InterruptedException("Preview check cancelled");
        }
    }

    @Override public synchronized PreviewFrame readNextFrame() throws Exception {
        if (!running) return null;
        PreviewFrame frame = firstFrame;
        firstFrame = null;
        if (frame == null) frame = delegate.readNextFrame();
        if (frame != null) {
            int[] dimensions = JpegDimensions.read(frame.getData(), frame.getLength());
            if (dimensions == null || dimensions[0] != selection.width || dimensions[1] != selection.height
                || frame.getLength() > selection.active.maxFrameBytes) {
                throw new IOException("Camera preview changed size; reopen the camera settings");
            }
        }
        return frame;
    }

    @Override public synchronized void stopPreviewSequence() {
        running = false;
        firstFrame = null;
        delegate.stopPreviewSequence();
    }

    @Override public synchronized void close() {
        stopPreviewSequence();
        delegate.close();
        cameraContext = null;
    }
}
