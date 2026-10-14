package com.casklabs.ghostbuffer.exposure;

import java.util.Arrays;

/** Demonstrates the controls alongside the explicitly marked simulation preview. */
public class FakeExposureBackend implements ExposureController.Backend {
    private final ExposureController.Snapshot state = new ExposureController.Snapshot();
    public FakeExposureBackend() {
        state.mode = ExposureController.PROGRAM;
        state.modes.addAll(Arrays.asList(ExposureController.PROGRAM, ExposureController.APERTURE_PRIORITY,
            ExposureController.SHUTTER_PRIORITY, ExposureController.MANUAL));
        state.shutterAvailable = true;
        state.shutterNumerator = 1; state.shutterDenominator = 125;
        state.apertureAvailable = true; state.aperture = 280;
        state.isoValues.addAll(Arrays.asList(0, 100, 200, 400, 800, 1600, 3200));
        state.minCompensation = -9; state.maxCompensation = 9; state.compensationStep = 1.0f / 3;
        state.whiteBalance = "auto";
        state.whiteBalances.addAll(Arrays.asList("auto", "daylight", "cloudy-daylight", "incandescent"));
    }
    @Override public ExposureController.Snapshot read() { return state; }
    @Override public void setMode(String mode) { state.mode = mode; }
    @Override public void stepShutter(int direction) {
        state.shutterDenominator = Math.max(1, Math.min(4000, state.shutterDenominator + direction * 25));
    }
    @Override public void stepAperture(int direction) { state.aperture = Math.max(140, Math.min(2200, state.aperture + direction * 20)); }
    @Override public void setIso(int iso) { state.iso = iso; }
    @Override public void setCompensation(int compensation) { state.compensation = compensation; }
    @Override public void setWhiteBalance(String whiteBalance) { state.whiteBalance = whiteBalance; }
}
