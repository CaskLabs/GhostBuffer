package com.casklabs.ghostbuffer.probe;

import com.casklabs.ghostbuffer.storage.GhostStorage;
import com.casklabs.ghostbuffer.time.GhostTime;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** A staged native recording test. Profile acceptance is never reported as verified image data. */
public final class VideoEncoderProbe {
    public interface Cancellation { boolean isCancelled(); }
    public interface Backend {
        Capabilities inspect(Writer report) throws Exception;
        Profile configure(Profile profile) throws Exception;
        void prepare() throws Exception;
        void start() throws Exception;
        void stop() throws Exception;
        void checkError() throws Exception;
        void close() throws Exception;
    }

    public static final class Profile {
        public final String format, size, rate, bitrate, aspect;
        public Profile(String format, String size, String rate, String bitrate, String aspect) {
            this.format = format; this.size = size; this.rate = rate; this.bitrate = bitrate; this.aspect = aspect;
        }
        public boolean fullHd() {
            if ("FHD_1080".equals(size)) return true;
            // Scalar API 3 uses HD_1080 for both AVCHD 1920x1080 and MPEG4 1440x1080.
            // Sony's ILCE-5000 movie settings document these AVCHD FX/FH profiles as Full HD.
            return "HD_1080".equals(size) && "AVCHD".equals(format) && "16_9".equals(aspect)
                && ("FX".equals(bitrate) || "FH".equals(bitrate))
                && ("24p".equals(rate) || "25p".equals(rate) || "50i".equals(rate) || "60i".equals(rate));
        }
        @Override public String toString() { return format + " | " + size + " | " + rate + " | " + bitrate + " | " + aspect; }
    }

    public static final class Capabilities {
        public boolean recorderAvailable;
        public boolean streamBufferAvailable;
        public final List<Profile> profiles = new ArrayList<Profile>();
    }

    public static final class Result {
        public String status = "ERROR";
        public String message;
        public File reportFile;
        public boolean recordingStarted;
        public boolean recordingStopped;
        public boolean streamBufferAvailable;
        public int videoWidth, videoHeight;
    }

    private final GhostStorage storage;
    private final long durationMs;
    public VideoEncoderProbe(GhostStorage storage) { this(storage, 2000L); }
    public VideoEncoderProbe(GhostStorage storage, long durationMs) { this.storage = storage; this.durationMs = durationMs; }

    public Result run(Backend backend, Cancellation cancellation, String deviceDetails) {
        Result result = new Result();
        Writer report = null;
        FileOutputStream output = null;
        boolean startAttempted = false;
        File cardRoot = null;
        Set<String> before = null;
        String stage = "storage";
        try {
            File directory = storage.prepareProbeDir();
            cardRoot = storage.getBaseDir().getParentFile();
            before = movieFiles(cardRoot);
            long now = GhostTime.currentTimeMillis();
            for (int i = 0; i < 1000; i++) {
                File candidate = new File(directory, GhostStorage.generateSessionId(now + 1000L * i) + ".TXT");
                if (candidate.createNewFile()) { result.reportFile = candidate; break; }
            }
            if (result.reportFile == null) throw new java.io.IOException("Cannot reserve encoder report");
            output = new FileOutputStream(result.reportFile);
            report = new OutputStreamWriter(output, "UTF-8");
            line(report, "GhostBuffer internal video encoder test");
            line(report, "Probe revision: 3 (fresh preview surface and recorded H.264 SPS inspection)");
            line(report, "Timestamp: " + GhostStorage.formatIso8601(now));
            line(report, deviceDetails);
            line(report, "Test duration: " + durationMs + " ms; output: SDK default recording media");
            line(report, "Full-HD image data verification: NOT PERFORMED by this recording probe");
            line(report, "Clip metadata verification: inspect recorded AVCHD H.264 SPS; no frame decoding");
            line(report, "RAM stream capture: NOT TESTED; only interface availability is checked");
            checkCancelled(cancellation);
            stage = "inspect native recorder and supported profiles";
            line(report, "BEGIN: " + stage);
            Capabilities caps = backend.inspect(report);
            result.streamBufferAvailable = caps.streamBufferAvailable;
            line(report, "Recorder available: " + caps.recorderAvailable);
            line(report, "StreamBuffer API available: " + caps.streamBufferAvailable);
            for (Profile profile : caps.profiles) {
                line(report, "Supported profile: " + profile);
                if (profile != null && profile.fullHd()) line(report, "Profile interpretation: nominal 1920x1080; decoded dimensions unverified");
            }
            if (!caps.recorderAvailable) {
                result.status = "UNAVAILABLE";
                result.message = "Sony video recorder API unavailable on this firmware";
            } else {
                Profile chosen = chooseFullHd(caps.profiles);
                if (chosen == null) {
                    result.status = "NO_FHD_PROFILE";
                    result.message = "Recorder found; no recognized Full-HD profile (FHD_1080 or AVCHD HD_1080 FX/FH)";
                } else {
                    checkCancelled(cancellation);
                    stage = "configure Full-HD profile";
                    line(report, "BEGIN: " + stage + ": " + chosen);
                    line(report, "Selected profile interpretation: nominal 1920x1080; decoded dimensions unverified");
                    Profile active = backend.configure(chosen);
                    line(report, "Profile read back: " + active);
                    if (active == null || !active.fullHd()) {
                        result.status = "PROFILE_REJECTED";
                        result.message = "Firmware did not retain the Full-HD setting";
                    } else {
                        checkCancelled(cancellation);
                        stage = "prepare encoder";
                        line(report, "BEGIN: " + stage);
                        backend.prepare();
                        backend.checkError();
                        line(report, "PREPARE returned successfully");
                        checkCancelled(cancellation);
                        stage = "start encoder";
                        line(report, "BEGIN: " + stage);
                        startAttempted = true;
                        backend.start();
                        result.recordingStarted = true;
                        line(report, "START returned successfully");
                        long deadline = GhostTime.uptimeMillis() + durationMs;
                        while (GhostTime.uptimeMillis() < deadline) {
                            checkCancelled(cancellation);
                            backend.checkError();
                            Thread.sleep(20);
                        }
                        checkCancelled(cancellation);
                        stage = "stop encoder";
                        line(report, "BEGIN: " + stage);
                        backend.stop();
                        result.recordingStopped = true;
                        Thread.sleep(40); // Let pending native error notifications reach the UI looper.
                        backend.checkError();
                        line(report, "STOP returned successfully");
                        result.status = "RECORDER_RAN";
                        result.message = "Native recorder started and stopped with an FHD profile. Verify the saved clip.";
                    }
                }
            }
        } catch (InterruptedException e) {
            result.status = "CANCELLED";
            result.message = "Test cancelled at " + stage;
            writeQuietly(report, result.message);
        } catch (Throwable e) {
            result.message = "Failed at " + stage + ": " + describe(e);
            writeQuietly(report, result.message);
        } finally {
            if (startAttempted && !result.recordingStopped) {
                try { backend.stop(); result.recordingStopped = true; }
                catch (Throwable e) { writeQuietly(report, "Cleanup STOP: " + describe(e)); }
            }
            try { backend.close(); }
            catch (Throwable e) {
                writeQuietly(report, "Cleanup/recovery error: " + describe(e));
                result.status = "ERROR";
                result.message = result.message + "; native resource cleanup failed: " + describe(e);
            }
            if (cardRoot != null && before != null) {
                int found = 0;
                for (String path : movieFiles(cardRoot)) if (!before.contains(path)) {
                    writeQuietly(report, "New native clip: " + path + " (" + new File(path).length() + " bytes)");
                    found++;
                    if (found <= 4 && !cancellation.isCancelled()
                        && (path.toUpperCase(java.util.Locale.US).endsWith(".MTS") || path.toUpperCase(java.util.Locale.US).endsWith(".M2TS"))) {
                        try {
                            AvchdVideoInfo.Dimensions dimensions = AvchdVideoInfo.inspect(new File(path), cancellation);
                            result.videoWidth = dimensions.width; result.videoHeight = dimensions.height;
                            writeQuietly(report, "Recorded H.264 SPS dimensions: " + dimensions);
                            writeQuietly(report, "SPS frame_mbs_only_flag: " + dimensions.frameMbsOnly + "; decoded frame verification: NOT PERFORMED");
                            if ("RECORDER_RAN".equals(result.status) || "FHD_CLIP_HEADER".equals(result.status)) {
                                result.status = dimensions.fullHd() ? "FHD_CLIP_HEADER" : "CLIP_DIMENSIONS_MISMATCH";
                                result.message = "Recorded clip SPS declares " + dimensions + ". Decoded frames and RAM streaming remain unverified.";
                            }
                        } catch (InterruptedException e) { writeQuietly(report, "Clip metadata inspection cancelled"); }
                        catch (Exception e) { writeQuietly(report, "Clip metadata unverified: " + describe(e)); }
                    }
                }
                writeQuietly(report, "New native clips found in standard Sony movie folders: " + found);
            }
            writeQuietly(report, "Recording started: " + result.recordingStarted);
            writeQuietly(report, "Recording stopped: " + result.recordingStopped);
            writeQuietly(report, "Status: " + result.status);
            writeQuietly(report, "Result: " + result.message);
            if (report != null) {
                try { report.flush(); storage.sync(output.getFD()); report.close(); }
                catch (Throwable e) { result.status = "ERROR"; result.message = "Cannot finish report: " + describe(e); }
            }
            if (output != null) try { output.close(); } catch (java.io.IOException ignored) {}
        }
        return result;
    }

    public static Profile chooseFullHd(List<Profile> profiles) {
        Profile chosen = null;
        for (Profile profile : profiles) {
            if (profile == null || !profile.fullHd() || profile.format == null || profile.rate == null
                || profile.bitrate == null || profile.aspect == null) continue;
            if (chosen == null || score(profile) > score(chosen)) chosen = profile;
        }
        return chosen;
    }

    private static int score(Profile profile) {
        return ("25p".equals(profile.rate) || "24p".equals(profile.rate) ? 10 : 0)
            + ("AVCHD".equals(profile.format) ? 2 : 0) + ("FH".equals(profile.bitrate) ? 1 : 0);
    }
    private static Set<String> movieFiles(File root) {
        Set<String> files = new HashSet<String>();
        if (root != null) {
            String[] folders = { "PRIVATE/AVCHD/BDMV/STREAM", "MP_ROOT", "PRIVATE/M4ROOT/CLIP", "DCIM" };
            int[] remaining = { 4096 };
            for (String folder : folders) collectMovies(new File(root, folder), 2, remaining, files);
        }
        return files;
    }
    private static void collectMovies(File directory, int depth, int[] remaining, Set<String> files) {
        if (depth < 0 || remaining[0] <= 0) return;
        File[] entries;
        try { entries = directory.listFiles(); } catch (SecurityException e) { return; }
        if (entries == null) return;
        for (File file : entries) {
            if (--remaining[0] < 0) return;
            if (file.isDirectory()) collectMovies(file, depth - 1, remaining, files);
            else {
                String name = file.getName().toUpperCase(java.util.Locale.US);
                if (name.endsWith(".MTS") || name.endsWith(".MP4") || name.endsWith(".M2TS")) files.add(file.getAbsolutePath());
            }
        }
    }
    private static void checkCancelled(Cancellation cancellation) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || cancellation.isCancelled()) throw new InterruptedException();
    }
    public static String describe(Throwable error) {
        while (error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
    private static void line(Writer writer, String text) throws java.io.IOException {
        writer.write(text + "\n"); writer.flush(); // Persist the last attempted stage even if native code crashes.
    }
    private static void writeQuietly(Writer writer, String text) {
        if (writer != null) try { line(writer, text); } catch (java.io.IOException ignored) {}
    }
}
