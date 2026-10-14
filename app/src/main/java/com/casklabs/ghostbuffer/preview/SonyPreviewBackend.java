package com.casklabs.ghostbuffer.preview;

import com.casklabs.ghostbuffer.time.GhostTime;
import com.github.ma1co.pmcademo.app.Logger;
import com.sony.scalar.hardware.CameraEx;
import com.sony.scalar.hardware.CameraSequence;
import com.sony.scalar.hardware.DeviceBuffer;
import com.sony.scalar.hardware.DeviceMemory;

import java.nio.ByteBuffer;

/**
 * Hardware preview sequence backend using Sony Scalar CameraSequence API.
 */
public class SonyPreviewBackend implements PreviewBackend {
    private static final int BUFFER_CAPACITY = 256 * 1024; // 256 KiB direct buffer

    private CameraEx cameraEx;
    private CameraSequence cameraSequence;
    private ByteBuffer directBuffer;
    private boolean sequenceStarted = false;

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.sony.scalar.hardware.CameraSequence");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void open(Object cameraContext) throws Exception {
        if (!(cameraContext instanceof CameraEx)) {
            throw new IllegalArgumentException("Expected CameraEx instance");
        }
        this.cameraEx = (CameraEx) cameraContext;
        Logger.info("Opening Sony CameraSequence...");
        this.cameraSequence = CameraSequence.open(cameraEx);
        this.directBuffer = ByteBuffer.allocateDirect(BUFFER_CAPACITY);
    }

    @Override
    public void startPreviewSequence(int width, int height, int fps) throws Exception {
        if (cameraSequence == null) {
            throw new IllegalStateException("CameraSequence not open");
        }

        Logger.info("Configuring CameraSequence: " + width + "x" + height);
        CameraSequence.Options options = new CameraSequence.Options();
        options.setOption(CameraSequence.Options.PREVIEW_FRAME_WIDTH, width);
        options.setOption(CameraSequence.Options.PREVIEW_FRAME_HEIGHT, height);
        options.setOption(CameraSequence.Options.PREVIEW_FRAME_FORMAT, 256); // JPEG format
        options.setOption(CameraSequence.Options.PREVIEW_FRAME_MAX_NUM, 1);
        options.setOption(CameraSequence.Options.PREVIEW_FRAME_RATE, 30000);

        cameraSequence.startPreviewSequence(options);
        sequenceStarted = true;
        Logger.info("CameraSequence preview started");
    }

    @Override
    public PreviewFrame readNextFrame() throws Exception {
        if (cameraSequence == null || !sequenceStarted) {
            return null;
        }

        long startMs = GhostTime.uptimeMillis();
        DeviceMemory[] frames = null;
        try {
            frames = cameraSequence.getPreviewSequenceFrames(1);
            if (frames == null || frames.length == 0) {
                return null;
            }

            DeviceMemory mem = frames[0];
            if (mem instanceof DeviceBuffer) {
                DeviceBuffer devBuf = (DeviceBuffer) mem;
                int size = devBuf.getSize();
                if (size <= 0 || size > BUFFER_CAPACITY) {
                    Logger.info("Frame size out of bounds: " + size);
                    return null;
                }

                directBuffer.clear();
                int bytesRead = devBuf.read(directBuffer);
                long endMs = GhostTime.uptimeMillis();

                if (bytesRead > 0) {
                    byte[] data = new byte[bytesRead];
                    directBuffer.position(0);
                    directBuffer.get(data, 0, bytesRead);
                    return new PreviewFrame(data, bytesRead, startMs, endMs);
                }
            }
        } finally {
            if (frames != null) {
                for (int i = 0; i < frames.length; i++) {
                    if (frames[i] != null) {
                        try {
                            frames[i].release();
                        } catch (Throwable t) {
                            Logger.error("Failed releasing DeviceMemory: " + t.getMessage());
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void stopPreviewSequence() {
        if (sequenceStarted && cameraSequence != null) {
            try {
                Logger.info("Stopping CameraSequence preview...");
                cameraSequence.stopPreviewSequence();
            } catch (Throwable t) {
                Logger.error("Error stopping preview sequence: " + t.getMessage());
            }
            sequenceStarted = false;
        }
    }

    @Override
    public void close() {
        stopPreviewSequence();
        if (cameraSequence != null) {
            try {
                Logger.info("Releasing CameraSequence...");
                cameraSequence.release();
            } catch (Throwable t) {
                Logger.error("Error releasing CameraSequence: " + t.getMessage());
            }
            cameraSequence = null;
        }
        cameraEx = null;
        directBuffer = null;
    }
}
