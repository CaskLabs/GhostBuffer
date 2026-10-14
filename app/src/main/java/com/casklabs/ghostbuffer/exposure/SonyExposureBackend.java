package com.casklabs.ghostbuffer.exposure;

import android.hardware.Camera;
import android.util.Pair;
import com.sony.scalar.hardware.CameraEx;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** CameraEx exposes shutter/aperture in native steps, aperture in hundredths and ISO Auto as zero. */
public class SonyExposureBackend implements ExposureController.Backend {
    private final CameraEx camera;

    public SonyExposureBackend(CameraEx camera) {
        this.camera = camera;
        try { camera.withSupportedParameters(true); } catch (LinkageError ignored) {}
    }

    @Override public ExposureController.Snapshot read() {
        Camera.Parameters current = camera.getNormalCamera().getParameters();
        CameraEx.ParametersModifier modifier = camera.createParametersModifier(current);
        Camera.Parameters supported = current;
        try {
            Camera.Parameters capabilities = camera.getSupportedParameters();
            if (capabilities != null) supported = capabilities;
        } catch (LinkageError ignored) {} catch (RuntimeException ignored) {}
        ExposureController.Snapshot s = new ExposureController.Snapshot();
        s.mode = current.getSceneMode();
        List<String> modes = supported.getSupportedSceneModes();
        if (modes == null || modes.isEmpty()) modes = current.getSupportedSceneModes();
        for (String mode : Arrays.asList(ExposureController.PROGRAM, "auto", ExposureController.APERTURE_PRIORITY,
                ExposureController.SHUTTER_PRIORITY, ExposureController.MANUAL)) {
            if (modes != null && modes.contains(mode)) {
                // Prefer P automation: it also permits ISO and EV changes.
                if ("auto".equals(mode) && s.modes.contains(ExposureController.PROGRAM)) continue;
                s.modes.add(mode);
            }
        }
        try {
            Pair<Integer, Integer> shutter = modifier.getShutterSpeed();
            if (shutter != null && shutter.first != null && shutter.second != null) {
                s.shutterNumerator = shutter.first;
                s.shutterDenominator = shutter.second;
                s.shutterAvailable = s.shutterNumerator > 0 && s.shutterDenominator > 0;
            }
        } catch (LinkageError ignored) {} catch (RuntimeException ignored) {}
        try {
            s.aperture = modifier.getAperture();
            s.apertureAvailable = s.aperture > 0;
        } catch (LinkageError ignored) {} catch (RuntimeException ignored) {}
        try {
            s.iso = modifier.getISOSensitivity();
            List<?> isos = modifier.getSupportedISOSensitivities();
            if (isos == null || isos.isEmpty()) isos = camera.createParametersModifier(supported).getSupportedISOSensitivities();
            if (isos != null) for (Object iso : isos) {
                if (iso instanceof Number && ((Number) iso).intValue() >= 0
                    && !s.isoValues.contains(((Number) iso).intValue())) s.isoValues.add(((Number) iso).intValue());
            }
            Collections.sort(s.isoValues);
        } catch (LinkageError ignored) {} catch (RuntimeException ignored) {}
        s.compensation = current.getExposureCompensation();
        s.minCompensation = current.getMinExposureCompensation();
        s.maxCompensation = current.getMaxExposureCompensation();
        s.compensationStep = current.getExposureCompensationStep();
        // Supported-parameter payloads can carry the bounds absent from the current payload.
        if (s.compensationStep <= 0 && current.get("exposure-compensation-step") == null) {
            s.minCompensation = supported.getMinExposureCompensation();
            s.maxCompensation = supported.getMaxExposureCompensation();
            s.compensationStep = supported.getExposureCompensationStep();
        }
        s.whiteBalance = current.getWhiteBalance();
        List<String> balances = supported.getSupportedWhiteBalance();
        if (balances == null || balances.isEmpty()) balances = current.getSupportedWhiteBalance();
        if (balances != null) s.whiteBalances.addAll(balances);
        return s;
    }

    private Camera.Parameters patch() { return camera.createEmptyParameters(); }
    @Override public void setMode(String mode) {
        Camera.Parameters p = patch(); p.setSceneMode(mode); camera.getNormalCamera().setParameters(p);
    }
    @Override public void stepShutter(int direction) {
        if (direction > 0) camera.incrementShutterSpeed(); else camera.decrementShutterSpeed();
    }
    @Override public void stepAperture(int direction) {
        if (direction > 0) camera.incrementAperture(); else camera.decrementAperture();
    }
    @Override public void setIso(int iso) {
        Camera.Parameters p = patch(); camera.createParametersModifier(p).setISOSensitivity(iso);
        camera.getNormalCamera().setParameters(p);
    }
    @Override public void setCompensation(int compensation) {
        Camera.Parameters p = patch(); p.setExposureCompensation(compensation); camera.getNormalCamera().setParameters(p);
    }
    @Override public void setWhiteBalance(String whiteBalance) {
        Camera.Parameters p = patch(); p.setWhiteBalance(whiteBalance); camera.getNormalCamera().setParameters(p);
    }
}
