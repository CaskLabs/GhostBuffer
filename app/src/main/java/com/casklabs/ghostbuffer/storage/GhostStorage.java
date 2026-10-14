package com.casklabs.ghostbuffer.storage;

import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manages filesystem locations and storage validation for GhostBuffer.
 */
public class GhostStorage {
    public static final String GHOSTBUFFER_DIR_NAME = "GHOSTBUFFER";
    public static final String CAPTURES_DIR_NAME = "captures";
    public static final String PROBE_DIR_NAME = "probe";
    public static final String DIAGNOSTICS_DIR_NAME = "diagnostics";

    private final File baseDir;

    public GhostStorage() {
        this(new File(Environment.getExternalStorageDirectory(), GHOSTBUFFER_DIR_NAME));
    }

    public GhostStorage(File baseDir) {
        this.baseDir = baseDir;
    }

    public File getBaseDir() {
        return baseDir;
    }

    public File getCapturesDir() {
        File dir = new File(baseDir, CAPTURES_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File getProbeDir() {
        File dir = new File(baseDir, PROBE_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File getDiagnosticsDir() {
        File dir = new File(baseDir, DIAGNOSTICS_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public boolean isStorageWritable() {
        try {
            File dir = getCapturesDir();
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File testFile = new File(dir, ".writable_test_" + System.currentTimeMillis());
            if (testFile.createNewFile()) {
                testFile.delete();
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public long getAvailableSpaceBytes() {
        try {
            File dir = baseDir;
            if (!dir.exists()) {
                dir = baseDir.getParentFile();
            }
            if (dir == null || !dir.exists()) {
                return 0;
            }
            StatFs stat = new StatFs(dir.getAbsolutePath());
            return (long) stat.getAvailableBlocks() * (long) stat.getBlockSize();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String generateSessionId(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
        return sdf.format(new Date(timestampMs));
    }

    public static String formatIso8601(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        return sdf.format(new Date(timestampMs));
    }
}
