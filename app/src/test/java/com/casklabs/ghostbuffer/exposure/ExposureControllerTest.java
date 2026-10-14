package com.casklabs.ghostbuffer.exposure;

import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;
import static com.casklabs.ghostbuffer.exposure.ExposureController.*;

public class ExposureControllerTest {
    private SpyBackend backend;
    private ExposureController controls;
    private static class SpyBackend extends FakeExposureBackend {
        final List<String> writes = new ArrayList<String>();
        @Override public void setMode(String mode) { writes.add("mode:" + mode); super.setMode(mode); }
        @Override public void stepShutter(int direction) { writes.add("shutter:" + direction); super.stepShutter(direction); }
        @Override public void stepAperture(int direction) { writes.add("aperture:" + direction); super.stepAperture(direction); }
        @Override public void setIso(int iso) { writes.add("iso:" + iso); super.setIso(iso); }
        @Override public void setCompensation(int ev) { writes.add("ev:" + ev); super.setCompensation(ev); }
        @Override public void setWhiteBalance(String wb) { writes.add("wb:" + wb); super.setWhiteBalance(wb); }
    }
    @Before public void setup() {
        backend = new SpyBackend();
        controls = new ExposureController(backend);
        controls.refresh();
    }
    @Test public void testReadingPreservesInheritedCameraSettings() {
        backend.read().mode = MANUAL;
        backend.read().iso = 800;
        controls.refresh();
        assertTrue(backend.writes.isEmpty());
        assertFalse(controls.shutterAuto());
        assertFalse(controls.apertureAuto());
        assertEquals("800", controls.value(Field.ISO));
    }
    @Test public void testManualShutterFromProgramRetainsAutomaticAperture() {
        controls.change(Field.SHUTTER, 1);
        assertEquals(Arrays.asList("mode:" + SHUTTER_PRIORITY, "shutter:1"), backend.writes);
        assertFalse(controls.shutterAuto());
        assertTrue(controls.apertureAuto());
    }
    @Test public void testManualApertureFromShutterPriorityKeepsManualShutter() {
        backend.read().mode = SHUTTER_PRIORITY;
        controls.change(Field.APERTURE, -1);
        assertEquals(Arrays.asList("mode:" + MANUAL, "aperture:-1"), backend.writes);
        assertFalse(controls.shutterAuto());
        assertFalse(controls.apertureAuto());
    }
    @Test public void testBothControlsCanReturnToAutomaticIndependently() {
        backend.read().mode = MANUAL;
        controls.automatic(Field.SHUTTER);
        assertEquals(APERTURE_PRIORITY, backend.read().mode);
        assertTrue(controls.shutterAuto());
        assertFalse(controls.apertureAuto());
        controls.automatic(Field.APERTURE);
        assertEquals(PROGRAM, backend.read().mode);
        assertTrue(controls.shutterAuto());
        assertTrue(controls.apertureAuto());
    }
    @Test public void testAutomaticApertureKeepsManualShutter() {
        backend.read().mode = MANUAL;
        controls.automatic(Field.APERTURE);
        assertEquals(SHUTTER_PRIORITY, backend.read().mode);
    }
    @Test public void testManualLensNeverReceivesApertureWritesOrModeChanges() {
        backend.read().apertureAvailable = false;
        backend.read().aperture = 0;
        try { controls.change(Field.APERTURE, 1); fail(); }
        catch (IllegalStateException expected) {}
        try { controls.automatic(Field.APERTURE); fail(); }
        catch (IllegalStateException expected) {}
        assertTrue(backend.writes.isEmpty());
        assertEquals("Lens ring", controls.value(Field.APERTURE));
    }
    @Test public void testUnsupportedModeIsRejectedBeforeChangingShutter() {
        backend.read().modes.remove(SHUTTER_PRIORITY);
        try { controls.change(Field.SHUTTER, 1); fail(); }
        catch (IllegalStateException expected) {}
        assertTrue(backend.writes.isEmpty());
    }
    @Test public void testIsoUsesCameraValuesAndCanReturnToAuto() {
        backend.read().isoValues.clear();
        backend.read().isoValues.addAll(Arrays.asList(0, 160, 320, 640));
        controls.change(Field.ISO, 1);
        assertEquals(160, backend.read().iso);
        controls.change(Field.ISO, 1);
        assertEquals(320, backend.read().iso);
        controls.automatic(Field.ISO);
        assertEquals(0, backend.read().iso);
        assertEquals("Auto", controls.value(Field.ISO));
    }
    @Test public void testUnsupportedAutoIsoDoesNotSendZero() {
        backend.read().isoValues.remove(Integer.valueOf(0));
        try { controls.automatic(Field.ISO); fail(); }
        catch (IllegalStateException expected) {}
        assertTrue(backend.writes.isEmpty());
    }
    @Test public void testCompensationClampsToActualCameraLimitsAndResetsNeutral() {
        backend.read().compensation = 9;
        controls.change(Field.COMPENSATION, 1);
        assertEquals(9, backend.read().compensation);
        assertEquals("+3.0 EV", controls.value(Field.COMPENSATION));
        controls.automatic(Field.COMPENSATION);
        assertEquals(0, backend.read().compensation);
        backend.read().compensation = -9;
        controls.change(Field.COMPENSATION, -1);
        assertEquals(-9, backend.read().compensation);
    }
    @Test public void testManualExposureRequiresAutoIsoForCompensation() {
        backend.read().mode = MANUAL;
        backend.read().iso = 100;
        assertFalse(controls.compensationAvailable());
        try { controls.change(Field.COMPENSATION, 1); fail(); }
        catch (IllegalStateException expected) {}
        assertTrue(backend.writes.isEmpty());
        backend.read().iso = 0;
        assertTrue(controls.compensationAvailable());
    }
    @Test public void testModeAutoRestoresAutomaticShutterApertureAndIso() {
        backend.read().mode = MANUAL;
        backend.read().iso = 1600;
        controls.automatic(Field.MODE);
        assertEquals(PROGRAM, backend.read().mode);
        assertEquals(0, backend.read().iso);
        assertTrue(controls.shutterAuto());
        assertTrue(controls.apertureAuto());
    }
    @Test public void testWhiteBalanceCanReturnToAuto() {
        controls.change(Field.WHITE_BALANCE, 1);
        assertEquals("daylight", backend.read().whiteBalance);
        controls.automatic(Field.WHITE_BALANCE);
        assertEquals("auto", backend.read().whiteBalance);
    }
    @Test public void testShutterAndApertureFormatting() {
        assertEquals("1/125 s", formatShutter(1, 125));
        assertEquals("2 s", formatShutter(2, 1));
        assertEquals("0.40 s", formatShutter(10, 25));
        assertEquals("--", formatShutter(1, 0));
        assertEquals("Auto · f/2.8", controls.value(Field.APERTURE));
    }
}
