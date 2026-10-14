package com.casklabs.ghostbuffer.exposure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exposure choices use values and limits supplied by the attached camera and lens. */
public class ExposureController {
    public static final String PROGRAM = "program-auto";
    public static final String APERTURE_PRIORITY = "aperture-priority";
    public static final String SHUTTER_PRIORITY = "shutter-speed";
    public static final String MANUAL = "manual-exposure";
    public enum Field { MODE, SHUTTER, APERTURE, ISO, COMPENSATION, WHITE_BALANCE }

    public static class Snapshot {
        public String mode;
        public List<String> modes = new ArrayList<String>();
        public int shutterNumerator;
        public int shutterDenominator;
        public boolean shutterAvailable;
        public int aperture;
        public boolean apertureAvailable;
        public int iso;
        public List<Integer> isoValues = new ArrayList<Integer>();
        public int compensation;
        public int minCompensation;
        public int maxCompensation;
        public float compensationStep;
        public String whiteBalance;
        public List<String> whiteBalances = new ArrayList<String>();
    }

    public interface Backend {
        Snapshot read();
        void setMode(String mode);
        void stepShutter(int direction);
        void stepAperture(int direction);
        void setIso(int iso);
        void setCompensation(int compensation);
        void setWhiteBalance(String whiteBalance);
    }

    private final Backend backend;
    private Snapshot snapshot;
    public ExposureController(Backend backend) { this.backend = backend; }
    public void refresh() { snapshot = backend.read(); }
    public Snapshot getSnapshot() { return snapshot; }

    public boolean shutterAuto() {
        return !MANUAL.equals(snapshot.mode) && !SHUTTER_PRIORITY.equals(snapshot.mode);
    }
    public boolean apertureAuto() {
        return !MANUAL.equals(snapshot.mode) && !APERTURE_PRIORITY.equals(snapshot.mode);
    }
    public boolean compensationAvailable() {
        return snapshot.compensationStep > 0 && snapshot.maxCompensation > snapshot.minCompensation
            && (!MANUAL.equals(snapshot.mode) || snapshot.iso == 0);
    }
    public boolean available(Field field) {
        if (snapshot == null) return false;
        switch (field) {
            case MODE: return !snapshot.modes.isEmpty();
            case SHUTTER: return snapshot.shutterAvailable;
            case APERTURE: return snapshot.apertureAvailable;
            case ISO: return !snapshot.isoValues.isEmpty();
            case COMPENSATION: return compensationAvailable();
            case WHITE_BALANCE: return !snapshot.whiteBalances.isEmpty();
            default: return false;
        }
    }
    private String autoMode() {
        return snapshot.modes.contains(PROGRAM) ? PROGRAM : "auto";
    }
    private void setAutomatic(boolean shutter, boolean aperture) {
        String mode = shutter ? (aperture ? autoMode() : APERTURE_PRIORITY)
            : (aperture ? SHUTTER_PRIORITY : MANUAL);
        if (!snapshot.modes.contains(mode)) throw new IllegalStateException("This exposure mode is not available on the camera");
        backend.setMode(mode);
    }

    /** Left/right change values; changing an automatic value first selects the matching P/A/S/M mode. */
    public void change(Field field, int direction) {
        if (snapshot == null || direction == 0) return;
        direction = direction > 0 ? 1 : -1;
        switch (field) {
            case MODE:
                if (snapshot.modes.isEmpty()) throw new IllegalStateException("Exposure modes unavailable");
                backend.setMode(next(snapshot.modes, snapshot.mode, direction));
                break;
            case SHUTTER:
                if (!snapshot.shutterAvailable) throw new IllegalStateException("Shutter control unavailable");
                if (shutterAuto()) setAutomatic(false, apertureAuto());
                backend.stepShutter(direction);
                break;
            case APERTURE:
                if (!snapshot.apertureAvailable) throw new IllegalStateException("Set the aperture on the lens ring");
                if (apertureAuto()) setAutomatic(shutterAuto(), false);
                backend.stepAperture(direction);
                break;
            case ISO:
                if (snapshot.isoValues.isEmpty()) throw new IllegalStateException("ISO control unavailable in this mode");
                backend.setIso(next(snapshot.isoValues, snapshot.iso, direction));
                break;
            case COMPENSATION:
                if (!compensationAvailable()) throw new IllegalStateException("EV unavailable here; use Auto ISO or P/A/S mode");
                backend.setCompensation(Math.max(snapshot.minCompensation,
                    Math.min(snapshot.maxCompensation, snapshot.compensation + direction)));
                break;
            case WHITE_BALANCE:
                if (snapshot.whiteBalances.isEmpty()) throw new IllegalStateException("White balance unavailable");
                backend.setWhiteBalance(next(snapshot.whiteBalances, snapshot.whiteBalance, direction));
                break;
        }
        refresh();
    }

    /** Center restores automatic control (or neutral 0 EV); it never applies guessed values. */
    public void automatic(Field field) {
        if (snapshot == null) return;
        switch (field) {
            case MODE:
                setAutomatic(true, true);
                refresh();
                if (snapshot.isoValues.contains(0)) backend.setIso(0);
                break;
            case SHUTTER:
                if (!snapshot.shutterAvailable) throw new IllegalStateException("Shutter control unavailable");
                setAutomatic(true, apertureAuto());
                break;
            case APERTURE:
                if (!snapshot.apertureAvailable) throw new IllegalStateException("Set the aperture on the lens ring");
                setAutomatic(shutterAuto(), true);
                break;
            case ISO:
                if (!snapshot.isoValues.contains(0)) throw new IllegalStateException("Auto ISO unavailable in this mode");
                backend.setIso(0);
                break;
            case COMPENSATION:
                if (!compensationAvailable()) throw new IllegalStateException("EV unavailable in this mode");
                backend.setCompensation(Math.max(snapshot.minCompensation, Math.min(snapshot.maxCompensation, 0)));
                break;
            case WHITE_BALANCE:
                if (!snapshot.whiteBalances.contains("auto")) throw new IllegalStateException("Auto white balance unavailable");
                backend.setWhiteBalance("auto");
                break;
        }
        refresh();
    }

    private static <T> T next(List<T> values, T current, int direction) {
        int index = values.indexOf(current);
        if (index < 0) return values.get(direction > 0 ? 0 : values.size() - 1);
        return values.get((index + direction + values.size()) % values.size());
    }

    public String value(Field field) {
        if (snapshot == null) return "Loading...";
        switch (field) {
            case MODE: return modeLabel(snapshot.mode);
            case SHUTTER: return !snapshot.shutterAvailable ? "Unavailable"
                : (shutterAuto() ? "Auto · " : "") + formatShutter(snapshot.shutterNumerator, snapshot.shutterDenominator);
            case APERTURE: return !snapshot.apertureAvailable ? "Lens ring"
                : (apertureAuto() ? "Auto · " : "") + String.format(Locale.US, "f/%.1f", snapshot.aperture / 100.0);
            case ISO: return snapshot.isoValues.isEmpty() ? "Unavailable" : snapshot.iso == 0 ? "Auto" : String.valueOf(snapshot.iso);
            case COMPENSATION: return !compensationAvailable() ? "Unavailable in this mode"
                : String.format(Locale.US, "%+.1f EV", snapshot.compensation * snapshot.compensationStep);
            case WHITE_BALANCE: return snapshot.whiteBalances.isEmpty() ? "Unavailable" : modeLabel(snapshot.whiteBalance);
            default: return "";
        }
    }

    public static String modeLabel(String mode) {
        if (PROGRAM.equals(mode)) return "P · Auto";
        if (APERTURE_PRIORITY.equals(mode)) return "A · Aperture priority";
        if (SHUTTER_PRIORITY.equals(mode)) return "S · Shutter priority";
        if (MANUAL.equals(mode)) return "M · Manual";
        if (mode == null) return "Camera setting";
        if ("auto".equals(mode)) return "Auto";
        return mode.replace('-', ' ').replace('_', ' ');
    }

    public static String formatShutter(int numerator, int denominator) {
        if (numerator <= 0 || denominator <= 0) return "--";
        if (numerator == 1 && denominator > 1) return "1/" + denominator + " s";
        double seconds = (double) numerator / denominator;
        return seconds >= 1 && seconds == Math.rint(seconds)
            ? String.format(Locale.US, "%.0f s", seconds) : String.format(Locale.US, "%.2f s", seconds);
    }
}
