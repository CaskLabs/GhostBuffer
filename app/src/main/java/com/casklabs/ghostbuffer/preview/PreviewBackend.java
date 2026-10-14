package com.casklabs.ghostbuffer.preview;

/**
 * Abstraction for preview frame sources (Sony hardware CameraSequence or Fake simulation).
 */
public interface PreviewBackend {
    /**
     * Initializes the backend with camera context (e.g. CameraEx).
     */
    void open(Object cameraContext) throws Exception;

    /**
     * Configures options and starts the preview sequence stream.
     */
    void startPreviewSequence(int width, int height, int fps) throws Exception;

    /**
     * Polls the next preview frame. Returns null if no new frame is ready.
     */
    PreviewFrame readNextFrame() throws Exception;

    /**
     * Stops the preview sequence stream.
     */
    void stopPreviewSequence();

    /**
     * Releases all camera and sequence resources.
     */
    void close();

    /**
     * Returns true if this backend's prerequisites are present in the runtime environment.
     */
    boolean isAvailable();
}
