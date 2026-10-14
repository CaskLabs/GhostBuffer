package com.casklabs.ghostbuffer.capture;

import org.junit.Test;

import static org.junit.Assert.*;

public class CaptureManifestTest {

    @Test
    public void testManifestSerializationAndDeserialization() throws Exception {
        CaptureManifest original = new CaptureManifest();
        original.setVersion(1);
        original.setAppVersion("1.0.0");
        original.setCameraModel("ILCE-5000");
        original.setCameraFirmware("1.10");
        original.setSessionId("20261014_153000_123");
        original.setCreatedAt("2026-10-14T15:30:00.123Z");
        original.setTriggerTimeMs(50000L);
        original.setBufferDurationMs(2000L);
        original.setActualDurationMs(1950L);
        original.setTargetFps(10.0);
        original.setMeasuredFps(9.8);
        original.setFrameWidth(640);
        original.setFrameHeight(480);

        original.addFrame(new CaptureManifest.FrameInfo("frame_000001.jpg", 101, 45120, -1950, 12));
        original.addFrame(new CaptureManifest.FrameInfo("frame_000002.jpg", 102, 45890, -1850, 11));
        original.addFrame(new CaptureManifest.FrameInfo("frame_000003.jpg", 103, 44900, -1750, 10));

        String json = original.toJsonString();
        assertNotNull(json);
        assertTrue(json.contains("20261014_153000_123"));
        assertTrue(json.contains("frame_000001.jpg"));
        assertTrue(json.contains("1.10"));

        CaptureManifest parsed = CaptureManifest.fromJsonString(json);
        assertEquals(original.getVersion(), parsed.getVersion());
        assertEquals(original.getAppVersion(), parsed.getAppVersion());
        assertEquals(original.getCameraModel(), parsed.getCameraModel());
        assertEquals(original.getCameraFirmware(), parsed.getCameraFirmware());
        assertEquals(original.getSessionId(), parsed.getSessionId());
        assertEquals(original.getCreatedAt(), parsed.getCreatedAt());
        assertEquals(original.getTriggerTimeMs(), parsed.getTriggerTimeMs());
        assertEquals(original.getBufferDurationMs(), parsed.getBufferDurationMs());
        assertEquals(original.getActualDurationMs(), parsed.getActualDurationMs());
        assertEquals(original.getTargetFps(), parsed.getTargetFps(), 0.001);
        assertEquals(original.getMeasuredFps(), parsed.getMeasuredFps(), 0.001);
        assertEquals(original.getFrameWidth(), parsed.getFrameWidth());
        assertEquals(original.getFrameHeight(), parsed.getFrameHeight());
        assertEquals(3, parsed.getFrameCount());

        CaptureManifest.FrameInfo frame0 = parsed.getFrames().get(0);
        assertEquals("frame_000001.jpg", frame0.filename);
        assertEquals(101, frame0.sequence);
        assertEquals(45120, frame0.sizeBytes);
        assertEquals(-1950, frame0.offsetFromTriggerMs);
        assertEquals(12, frame0.readDurationMs);
    }
}
