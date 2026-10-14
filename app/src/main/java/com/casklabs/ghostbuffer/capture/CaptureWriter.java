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
 * Persists an immutable snapshot of buffered frames to disk in a dedicated session folder.
 * Writes frames atomically via .tmp files, generates manifest.json with sync, and marks
 * completion via COMPLETE file.
 */
public class CaptureWriter {
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String COMPLETE_MARKER_FILE = "COMPLETE";

    public interface Listener {
        void onSaveProgress(int current, int total);
        void onSaveComplete(File sessionDir, CaptureManifest manifest);
        void onSaveError(Throwable error);
    }

    private final GhostStorage storage;

    public CaptureWriter(GhostStorage storage) {
        this.storage = storage;
    }

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
        return writeSnapshot(
            snapshot,
            triggerUptimeMs,
            triggerWallClockMs,
            bufferDurationMs,
            targetFps,
            measuredFps,
            frameWidth,
            frameHeight,
            cameraModel,
            "",
            appVersion,
            listener
        );
    }

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
        String cameraFirmware,
        String appVersion,
        Listener listener
    ) throws Exception {
        if (snapshot == null || snapshot.isEmpty()) {
            throw new IllegalArgumentException("Snapshot cannot be null or empty");
        }

        File capturesDir = storage.getCapturesDir();
        String baseSessionId = GhostStorage.generateSessionId(triggerWallClockMs);
        String sessionId = baseSessionId;
        File sessionDir = new File(capturesDir, sessionId);
        int suffix = 1;
        while (sessionDir.exists()) {
            sessionId = baseSessionId + "_" + suffix++;
            sessionDir = new File(capturesDir, sessionId);
        }

        if (!sessionDir.mkdirs()) {
            throw new IOException("Failed to create session directory: " + sessionDir.getAbsolutePath());
        }

        CaptureManifest manifest = new CaptureManifest();
        manifest.setSessionId(sessionId);
        manifest.setCreatedAt(GhostStorage.formatIso8601(triggerWallClockMs));
        manifest.setTriggerTimeMs(triggerWallClockMs);
        manifest.setBufferDurationMs(bufferDurationMs);
        manifest.setTargetFps(targetFps);
        manifest.setMeasuredFps(measuredFps);
        manifest.setFrameWidth(frameWidth);
        manifest.setFrameHeight(frameHeight);
        manifest.setCameraModel(cameraModel);
        manifest.setCameraFirmware(cameraFirmware);
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

        // Write manifest.json atomically via .tmp and fsync
        File manifestFile = new File(sessionDir, MANIFEST_FILE);
        File tmpManifestFile = new File(sessionDir, MANIFEST_FILE + ".tmp");
        FileOutputStream manifestFos = new FileOutputStream(tmpManifestFile);
        try {
            Writer writer = new OutputStreamWriter(manifestFos, "UTF-8");
            writer.write(manifest.toJsonString());
            writer.flush();
            manifestFos.getFD().sync();
        } finally {
            manifestFos.close();
        }

        if (!tmpManifestFile.renameTo(manifestFile)) {
            throw new IOException("Failed renaming temporary manifest file");
        }

        // Write COMPLETE marker with fsync
        File completeMarker = new File(sessionDir, COMPLETE_MARKER_FILE);
        FileOutputStream markerFos = new FileOutputStream(completeMarker);
        try {
            markerFos.write("COMPLETE\n".getBytes("UTF-8"));
            markerFos.flush();
            markerFos.getFD().sync();
        } finally {
            markerFos.close();
        }

        if (listener != null) {
            listener.onSaveComplete(sessionDir, manifest);
        }

        return sessionDir;
    }

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
        writeSnapshotAsync(
            snapshot,
            triggerUptimeMs,
            triggerWallClockMs,
            bufferDurationMs,
            targetFps,
            measuredFps,
            frameWidth,
            frameHeight,
            cameraModel,
            "",
            appVersion,
            listener
        );
    }

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
        final String cameraFirmware,
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
                        cameraFirmware,
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
