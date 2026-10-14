package com.casklabs.ghostbuffer.capture;

import com.casklabs.ghostbuffer.buffer.FrameSlot;
import com.casklabs.ghostbuffer.storage.GhostStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.Locale;

/**
 * Persists frozen frame snapshots and manifests to storage safely.
 */
public class CaptureWriter {
    public static final String COMPLETE_MARKER_FILE = "COMPLETE";
    public static final String MANIFEST_FILE = "manifest.json";

    public interface Listener {
        void onSaveProgress(int current, int total);
        void onSaveComplete(File sessionDir, CaptureManifest manifest);
        void onSaveError(Throwable error);
    }

    private final GhostStorage storage;

    public CaptureWriter(GhostStorage storage) {
        this.storage = storage;
    }

    /**
     * Synchronously writes a snapshot to storage.
     */
    public File writeSnapshot(
        List<FrameSlot> snapshot,
        long triggerUptimeMs,
        long triggerWallClockMs,
        long bufferDurationMs,
        double targetFps,
        double measuredFps,
        int frameWidth,
        int frameHeight,
        String cameraModel,
        String appVersion,
        Listener listener
    ) throws Exception {
        if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalArgumentException("Snapshot is empty");
        }

        if (!storage.isStorageWritable()) {
            throw new IOException("Storage is not writable or SD card missing");
        }

        String sessionId = GhostStorage.generateSessionId(triggerWallClockMs);
        File capturesDir = storage.getCapturesDir();
        File sessionDir = new File(capturesDir, sessionId);

        // Ensure unique directory name
        int suffix = 1;
        while (sessionDir.exists()) {
            sessionDir = new File(capturesDir, sessionId + "_" + suffix);
            suffix++;
        }

        if (!sessionDir.mkdirs()) {
            throw new IOException("Failed creating capture session directory: " + sessionDir.getAbsolutePath());
        }

        CaptureManifest manifest = new CaptureManifest();
        manifest.setSessionId(sessionDir.getName());
        manifest.setCreatedAt(GhostStorage.formatIso8601(triggerWallClockMs));
        manifest.setTriggerTimeMs(triggerUptimeMs);
        manifest.setBufferDurationMs(bufferDurationMs);
        manifest.setTargetFps(targetFps);
        manifest.setMeasuredFps(measuredFps);
        manifest.setFrameWidth(frameWidth);
        manifest.setFrameHeight(frameHeight);
        manifest.setCameraModel(cameraModel);
        manifest.setAppVersion(appVersion);

        long actualDuration = 0;
        if (snapshot.size() > 1) {
            actualDuration = snapshot.get(snapshot.size() - 1).getReadEndMs() - snapshot.get(0).getReadEndMs();
        }
        manifest.setActualDurationMs(actualDuration);

        int total = snapshot.size();
        for (int i = 0; i < total; i++) {
            FrameSlot slot = snapshot.get(i);
            String filename = String.format(Locale.US, "frame_%06d.jpg", i + 1);
            File finalFile = new File(sessionDir, filename);
            File tmpFile = new File(sessionDir, filename + ".tmp");

            // Write to temporary file first
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(tmpFile);
                fos.write(slot.getData(), 0, slot.getLength());
                fos.flush();
                fos.getFD().sync();
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (IOException ignored) {}
                }
            }

            // Rename atomically
            if (!tmpFile.renameTo(finalFile)) {
                throw new IOException("Failed renaming temporary frame file to " + finalFile.getAbsolutePath());
            }

            long offsetFromTriggerMs = slot.getReadEndMs() - triggerUptimeMs;
            manifest.addFrame(new CaptureManifest.FrameInfo(
                filename,
                slot.getSequence(),
                slot.getLength(),
                offsetFromTriggerMs,
                slot.getReadDurationMs()
            ));

            if (listener != null) {
                listener.onSaveProgress(i + 1, total);
            }
        }

        // Write manifest.json
        File manifestFile = new File(sessionDir, MANIFEST_FILE);
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(manifestFile), "UTF-8");
            writer.write(manifest.toJsonString());
            writer.flush();
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) {}
            }
        }

        // Write COMPLETE marker
        File completeFile = new File(sessionDir, COMPLETE_MARKER_FILE);
        if (!completeFile.createNewFile()) {
            throw new IOException("Failed creating COMPLETE marker file");
        }

        if (listener != null) {
            listener.onSaveComplete(sessionDir, manifest);
        }

        return sessionDir;
    }

    /**
     * Executes snapshot saving on an asynchronous background thread.
     */
    public void writeSnapshotAsync(
        final List<FrameSlot> snapshot,
        final long triggerUptimeMs,
        final long triggerWallClockMs,
        final long bufferDurationMs,
        final double targetFps,
        final double measuredFps,
        final int frameWidth,
        final int frameHeight,
        final String cameraModel,
        final String appVersion,
        final Listener listener
    ) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    writeSnapshot(
                        snapshot,
                        triggerUptimeMs,
                        triggerWallClockMs,
                        bufferDurationMs,
                        targetFps,
                        measuredFps,
                        frameWidth,
                        frameHeight,
                        cameraModel,
                        appVersion,
                        listener
                    );
                } catch (Throwable t) {
                    if (listener != null) {
                        listener.onSaveError(t);
                    }
                }
            }
        }, "GhostBuffer-CaptureWriter").start();
    }
}
