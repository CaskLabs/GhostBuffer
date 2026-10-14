package com.casklabs.ghostbuffer.storage;

import android.os.Environment;
import android.os.StatFs;
import com.github.ma1co.openmemories.framework.DeviceInfo;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Storage manager for external SD card directories and path helpers.
 */
public class GhostStorage {
    // Sony's libInfraFuFsys SD bridge accepts uppercase DOS 8.3 names only.
    public static final String ROOT_DIR_NAME = "GHOSTBUF";
    public static final String CAPTURES_DIR_NAME = "CAPTURES";
    public static final String PROBE_DIR_NAME = "PROBE";
    public static final String DIAGNOSTICS_DIR_NAME = "DIAG";
    private static final AtomicInteger probeSequence = new AtomicInteger();

    private File baseDir;
    private final File[] storageRoots;
    private boolean rootSelected;
    private boolean supportsSync = true;
    private String storageFailure;

    public GhostStorage() {
        this(new File[] {
            Environment.getExternalStorageDirectory(),
            new File("/storage/sdcard0"),
            new File("/mnt/sdcard"),
            new File("/sdcard")
        });
        try {
            supportsSync = !DeviceInfo.getInstance().isCamera();
        } catch (LinkageError e) {
            supportsSync = true;
        }
    }

    GhostStorage(File[] storageRoots) {
        this.storageRoots = storageRoots.clone();
        this.baseDir = new File(storageRoots[0], ROOT_DIR_NAME);
    }

    public GhostStorage(File customBaseDir) {
        this(customBaseDir, true);
    }

    GhostStorage(File customBaseDir, boolean supportsSync) {
        this.baseDir = customBaseDir;
        this.storageRoots = null;
        this.rootSelected = true;
        this.supportsSync = supportsSync;
    }

    public synchronized File getBaseDir() {
        if (!rootSelected) {
            storageFailure = null;
            for (File root : storageRoots) {
                // Never create a missing mount point in the camera's internal filesystem.
                if (root == null || !root.isDirectory()) continue;
                File candidate = new File(root, ROOT_DIR_NAME);
                try {
                    if (!ensureDirectory(candidate)) throw new IOException("Cannot create " + candidate);
                    checkFileWrite(candidate);
                    baseDir = candidate;
                    rootSelected = true;
                    break;
                } catch (IOException e) {
                    if (storageFailure == null) storageFailure = e.getMessage();
                }
            }
        }
        return baseDir;
    }

    public File getCapturesDir() {
        return getDirectory(CAPTURES_DIR_NAME);
    }

    public File getProbeDir() {
        return getDirectory(PROBE_DIR_NAME);
    }

    public File getDiagnosticsDir() {
        return getDirectory(DIAGNOSTICS_DIR_NAME);
    }

    private File getDirectory(String name) {
        File dir = new File(getBaseDir(), name);
        // A missing card must not cause getters used by the gallery to throw.
        if (storageRoots == null || rootSelected) ensureDirectory(dir);
        return dir;
    }

    public File prepareCapturesDir() throws IOException {
        return prepareDirectory(CAPTURES_DIR_NAME);
    }

    public File prepareProbeDir() throws IOException {
        return prepareDirectory(PROBE_DIR_NAME);
    }

    private File prepareDirectory(String name) throws IOException {
        File dir = getDirectory(name);
        if (storageRoots != null && !rootSelected) {
            throw new IOException("SD card unavailable or not writable: " + dir.getAbsolutePath()
                + ". " + (storageFailure != null ? storageFailure : "No SD mount found."));
        }
        try {
            checkFileWrite(dir);
        } catch (IOException e) {
            throw new IOException("SD card unavailable or not writable: " + dir.getAbsolutePath()
                + ". " + e.getMessage(), e);
        }
        return dir;
    }

    /** Create each component directly, without mkdirs() canonicalizing Sony mount aliases. */
    private static boolean ensureDirectory(File dir) {
        if (dir.isDirectory()) return true;
        if (dir.exists()) return false;
        File parent = dir.getParentFile();
        return parent != null && ensureDirectory(parent) && (dir.mkdir() || dir.isDirectory());
    }

    private static void checkFileWrite(File dir) throws IOException {
        File testFile = null;
        try {
            for (int attempt = 0; attempt < 32; attempt++) {
                File candidate = new File(dir, String.format(Locale.US, "W%07X.TMP",
                    probeSequence.getAndIncrement() & 0x0FFFFFFF));
                if (candidate.createNewFile()) {
                    testFile = candidate;
                    break;
                }
            }
            if (testFile == null) throw new IOException("Cannot reserve SD write-test file");
            FileOutputStream stream = new FileOutputStream(testFile);
            try {
                stream.write(1);
            } finally {
                stream.close();
            }
        } catch (SecurityException e) {
            throw new IOException("Storage permission denied", e);
        } finally {
            if (testFile != null) testFile.delete();
        }
    }

    /** Sony's SD FUSE bridge has no fsync. Keep sync failures visible on other filesystems. */
    public void sync(FileDescriptor descriptor) throws IOException {
        if (supportsSync) descriptor.sync();
    }

    public boolean isStorageWritable() {
        try {
            prepareCapturesDir();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public long getAvailableSpaceBytes() {
        try {
            File dir = getBaseDir();
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
        // Eight hex digits sort chronologically; the full timestamp stays in the manifest.
        return String.format(Locale.US, "%08X", (timestampMs / 1000L) & 0xFFFFFFFFL);
    }

    public static String formatIso8601(long timestampMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(timestampMs));
    }
}
