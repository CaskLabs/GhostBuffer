package com.casklabs.ghostbuffer.capture;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Model representing an existing capture session stored on disk.
 * Discovers complete and incomplete sessions, verifying frame file integrity.
 */
public class CaptureSession {
    private final File directory;
    private final CaptureManifest manifest;
    private final boolean complete;
    private final boolean missingFrames;

    public CaptureSession(File directory, CaptureManifest manifest, boolean complete, boolean missingFrames) {
        this.directory = directory;
        this.manifest = manifest;
        this.complete = complete;
        this.missingFrames = missingFrames;
    }

    public File getDirectory() {
        return directory;
    }

    public CaptureManifest getManifest() {
        return manifest;
    }

    public boolean isComplete() {
        return complete && !missingFrames;
    }

    public boolean hasMissingFrames() {
        return missingFrames;
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
                if (!manifestFile.exists()) manifestFile = new File(dir, CaptureWriter.LEGACY_MANIFEST_FILE);
                File completeMarker = new File(dir, CaptureWriter.COMPLETE_MARKER_FILE);
                boolean hasMarker = completeMarker.exists();

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

                // If manifest is missing or damaged, reconstruct sessions from short JPEG names or legacy frame_*.jpg files
                if (manifest == null) {
                    File[] frameFiles = dir.listFiles(new FilenameFilter() {
                        @Override
                        public boolean accept(File d, String name) {
                            return name.matches("F[0-9]{7}\\.JPG")
                                || (name.startsWith("frame_") && name.endsWith(".jpg"));
                        }
                    });

                    if (frameFiles != null && frameFiles.length > 0) {
                        Arrays.sort(frameFiles, new Comparator<File>() {
                            @Override
                            public int compare(File a, File b) {
                                return a.getName().compareTo(b.getName());
                            }
                        });

                        manifest = new CaptureManifest();
                        manifest.setSessionId(dir.getName());
                        manifest.setCreatedAt("Incomplete / Recovered");
                        for (int i = 0; i < frameFiles.length; i++) {
                            manifest.addFrame(new CaptureManifest.FrameInfo(
                                frameFiles[i].getName(),
                                i + 1,
                                (int) frameFiles[i].length(),
                                0,
                                0
                            ));
                        }
                        sessions.add(new CaptureSession(dir, manifest, false, false));
                    }
                    continue;
                }

                // Verify that referenced frames actually exist and are non-empty
                boolean missing = false;
                for (CaptureManifest.FrameInfo fi : manifest.getFrames()) {
                    File f = new File(dir, fi.filename);
                    if (!f.exists() || f.length() == 0) {
                        missing = true;
                        break;
                    }
                }

                boolean sessionComplete = hasMarker && !missing;
                sessions.add(new CaptureSession(dir, manifest, sessionComplete, missing));
            }
        }

        return sessions;
    }
}
