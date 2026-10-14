package com.casklabs.ghostbuffer.probe;

import com.casklabs.ghostbuffer.preview.FakePreviewBackend;
import com.casklabs.ghostbuffer.storage.GhostStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import static org.junit.Assert.*;

public class GhostProbeManagerTest {
    private File tempRootDir;
    private GhostStorage storage;
    private FakePreviewBackend backend;
    private GhostProbeManager probeManager;

    @Before
    public void setUp() throws Exception {
        tempRootDir = new File(System.getProperty("java.io.tmpdir"), "ghostprobe_test_" + System.currentTimeMillis());
        tempRootDir.mkdirs();
        storage = new GhostStorage(tempRootDir);
        backend = new FakePreviewBackend();
        backend.open(null);
        backend.startPreviewSequence(640, 480, 10);
        probeManager = new GhostProbeManager(storage);
    }

    @After
    public void tearDown() {
        backend.close();
        deleteRecursive(tempRootDir);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) {
                for (File child : files) {
                    deleteRecursive(child);
                }
            }
        }
        f.delete();
    }

    @Test
    public void testRunSingleProbeSuccess() throws Exception {
        GhostProbeManager.ProbeResult result = probeManager.runSingleProbe(backend, "ILCE-5000", "1.0.0");

        assertTrue("Probe must succeed", result.success);
        assertNotNull(result.jpegFile);
        assertNotNull(result.reportFile);

        assertTrue("Probe JPEG file must exist", result.jpegFile.exists());
        assertTrue("Probe JPEG must not be empty", result.jpegFile.length() > 0);
        assertTrue("Probe report file must exist", result.reportFile.exists());

        // Verify report content
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(result.reportFile), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();

        String report = sb.toString();
        assertTrue(report.contains("GhostBuffer Probe Report"));
        assertTrue(report.contains("Camera Model: ILCE-5000"));
        assertTrue(report.contains("Status: PASSED"));
    }

    @Test
    public void testRunSingleProbeFailureOnNullFrame() {
        backend.setSimulateNullFrames(true);
        GhostProbeManager.ProbeResult result = probeManager.runSingleProbe(backend, "ILCE-5000", "1.0.0");

        assertFalse("Probe must report failure when backend gives null frame", result.success);
    }
}
