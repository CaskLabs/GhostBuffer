package com.casklabs.ghostbuffer.capture;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Model representing an existing capture session stored on disk.
 */
public class CaptureSession {
    private final File directory;
    private final CaptureManifest manifest;
    private final boolean complete;

    public CaptureSession(File directory, CaptureManifest manifest, boolean complete) {
        this.directory = directory;
        this.manifest = manifest;
        this.complete = complete;
    }

    public File getDirectory() {
        return directory;
    }

    public CaptureManifest getManifest() {
        return manifest;
    }

    public boolean isComplete() {
        return complete;
    }

    public String getSessionId() {
        return directory.getName();
    }

    public int getFrameCount() {
        return manifest != null ? manifest.getFrameCount() : 0;
    }

    public File getFrameFile(int index) {
        if (manifest == null || index < 0 || index >= manifest.getFrames().size()) {
            return null;
        }
        return new File(directory, manifest.getFrames().get(index).filename);
    }

    /**
     * Scans the captures directory and loads all valid complete and incomplete sessions.
     */
    public static List<CaptureSession> loadAllSessions(File capturesDir) {
        List<CaptureSession> sessions = new ArrayList<CaptureSession>();
        if (capturesDir == null || !capturesDir.exists() || !capturesDir.isDirectory()) {
            return sessions;
        }

        File[] files = capturesDir.listFiles();
        if (files == null) {
            return sessions;
        }

        // Sort descending by directory name (newest first)
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f2.getName().compareTo(f1.getName());
            }
        });

        for (File dir : files) {
            if (dir.isDirectory()) {
                File manifestFile = new File(dir, CaptureWriter.MANIFEST_FILE);
                File completeMarker = new File(dir, CaptureWriter.COMPLETE_MARKER_FILE);
                boolean isComplete = completeMarker.exists();

                CaptureManifest manifest = null;
                if (manifestFile.exists()) {
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(manifestFile), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        manifest = CaptureManifest.fromJsonString(sb.toString());
                    } catch (Throwable ignored) {}
                }

                if (manifest != null) {
                    sessions.add(new CaptureSession(dir, manifest, isComplete));
                }
            }
        }

        return sessions;
    }
}
