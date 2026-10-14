package com.casklabs.ghostbuffer.probe;

import com.casklabs.ghostbuffer.preview.PreviewBackend;
import com.casklabs.ghostbuffer.preview.PreviewFrame;
import com.casklabs.ghostbuffer.storage.GhostStorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Locale;

/**
 * Executes a single-frame probe verification and exports the JPEG and diagnostic report
 * to <SD>/GHOSTBUFFER/probe/ as specified in Etappe 1 of the development plan.
 */
public class GhostProbeManager {
    public static class ProbeResult {
        public final boolean success;
        public final File jpegFile;
        public final File reportFile;
        public final int frameSize;
        public final long durationMs;
        public final String message;

        public ProbeResult(boolean success, File jpegFile, File reportFile, int frameSize, long durationMs, String message) {
            this.success = success;
            this.jpegFile = jpegFile;
            this.reportFile = reportFile;
            this.frameSize = frameSize;
            this.durationMs = durationMs;
            this.message = message;
        }
    }

    private final GhostStorage storage;

    public GhostProbeManager(GhostStorage storage) {
        this.storage = storage;
    }

    public ProbeResult runSingleProbe(PreviewBackend backend, String cameraModel, String firmware) {
        File probeDir = storage.getProbeDir();
        long now = System.currentTimeMillis();
        String timestampStr = GhostStorage.generateSessionId(now);

        File jpegFile = new File(probeDir, "probe_" + timestampStr + ".jpg");
        File reportFile = new File(probeDir, "probe_" + timestampStr + "_report.txt");

        try {
            long startTime = System.currentTimeMillis();
            PreviewFrame frame = backend.readNextFrame();
            long duration = System.currentTimeMillis() - startTime;

            if (frame == null || frame.getLength() <= 0) {
                return new ProbeResult(false, null, null, 0, duration, "Backend returned empty or null frame");
            }

            // Verify SOI marker (0xFF, 0xD8)
            byte[] data = frame.getData();
            if (data == null || frame.getLength() < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
                return new ProbeResult(false, null, null, frame.getLength(), duration, "Frame data missing JPEG SOI marker");
            }

            // Write JPEG file
            FileOutputStream fos = new FileOutputStream(jpegFile);
            try {
                fos.write(data, 0, frame.getLength());
                fos.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
            }

            // Write diagnostic report
            Writer writer = new OutputStreamWriter(new FileOutputStream(reportFile), "UTF-8");
            try {
                writer.write("=== GhostBuffer Probe Report ===\n");
                writer.write("Timestamp: " + GhostStorage.formatIso8601(now) + "\n");
                writer.write("Camera Model: " + cameraModel + "\n");
                writer.write("Firmware: " + firmware + "\n");
                writer.write("Frame Size: " + frame.getLength() + " bytes\n");
                writer.write("Read Latency: " + duration + " ms\n");
                writer.write(String.format(Locale.US, "JPEG SOI: 0x%02X 0x%02X\n", data[0] & 0xFF, data[1] & 0xFF));
                int len = frame.getLength();
                writer.write(String.format(Locale.US, "JPEG EOI Tail: 0x%02X 0x%02X\n", data[len - 2] & 0xFF, data[len - 1] & 0xFF));
                writer.write("Status: PASSED\n");
                writer.flush();
            } finally {
                writer.close();
            }

            return new ProbeResult(true, jpegFile, reportFile, frame.getLength(), duration, "Probe erfolgreich gesichert");
        } catch (Throwable t) {
            return new ProbeResult(false, null, null, 0, 0, "Probe fehlgeschlagen: " + t.getMessage());
        }
    }
}
