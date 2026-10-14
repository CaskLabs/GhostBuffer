package com.casklabs.ghostbuffer.probe;

import com.casklabs.ghostbuffer.storage.GhostStorage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.util.Arrays;
import static org.junit.Assert.*;

public class VideoEncoderProbeTest {
    private File root;
    private GhostStorage storage;
    private FakeEncoder encoder;
    private boolean cancelled;
    private final VideoEncoderProbe.Cancellation cancellation = new VideoEncoderProbe.Cancellation() {
        @Override public boolean isCancelled() { return cancelled; }
    };
    private static VideoEncoderProbe.Profile profile(String size, String rate) {
        return new VideoEncoderProbe.Profile("AVCHD", size, rate, "FH", "16_9");
    }
    private static class FakeEncoder implements VideoEncoderProbe.Backend {
        VideoEncoderProbe.Capabilities caps = new VideoEncoderProbe.Capabilities();
        boolean configured, prepared, started, stopped, closed, failPrepare, failStart, failClose, coerce, asyncError;
        VideoEncoderProbe.Profile readBack;
        FakeEncoder() { caps.recorderAvailable = true; caps.streamBufferAvailable = true; caps.profiles.add(profile("FHD_1080", "25p")); }
        @Override public VideoEncoderProbe.Capabilities inspect(Writer report) { return caps; }
        @Override public VideoEncoderProbe.Profile configure(VideoEncoderProbe.Profile chosen) {
            configured = true; return readBack != null ? readBack : coerce ? profile("HD_720", "25p") : chosen;
        }
        @Override public void prepare() throws IOException { if (failPrepare) throw new IOException("Preparation rejected"); prepared = true; }
        @Override public void start() throws IOException { if (failStart) throw new IOException("Partial start failure"); started = true; }
        @Override public void stop() { stopped = true; }
        @Override public void checkError() throws IOException { if (asyncError) throw new IOException("Asynchronous native encoder error"); }
        @Override public void close() throws IOException { closed = true; if (failClose) throw new IOException("Restore failed"); }
    }
    @Before public void setUp() throws Exception {
        root = File.createTempFile("encoder-probe-", "test");
        assertTrue(root.delete()); assertTrue(root.mkdir());
        storage = new GhostStorage(new File(root, "GHOSTBUF"));
        encoder = new FakeEncoder();
    }
    @After public void tearDown() { delete(root); }
    private void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
    private VideoEncoderProbe.Result run() { return new VideoEncoderProbe(storage, 0).run(encoder, cancellation, "Camera: test"); }
    private String report(VideoEncoderProbe.Result result) throws Exception {
        Reader input = new InputStreamReader(new FileInputStream(result.reportFile), "UTF-8");
        try {
            StringBuilder text = new StringBuilder(); char[] buffer = new char[1024]; int count;
            while ((count = input.read(buffer)) != -1) text.append(buffer, 0, count);
            return text.toString();
        } finally { input.close(); }
    }
    @Test public void recordsOnlyAfterSupportedProfileIsAcceptedAndKeepsVerificationLimitsExplicit() throws Exception {
        VideoEncoderProbe.Result result = run();
        assertEquals("RECORDER_RAN", result.status);
        assertTrue(encoder.configured && encoder.prepared && encoder.started && encoder.stopped && encoder.closed);
        assertTrue(result.recordingStarted && result.recordingStopped);
        assertTrue(report(result).contains("Full-HD image data verification: NOT PERFORMED"));
        assertTrue(report(result).contains("RAM stream capture: NOT TESTED"));
    }
    @Test public void missingRecorderDoesNotAttemptConfigurationOrRecording() {
        encoder.caps.recorderAvailable = false;
        VideoEncoderProbe.Result result = run();
        assertEquals("UNAVAILABLE", result.status);
        assertFalse(encoder.configured || encoder.prepared || encoder.started);
        assertTrue(encoder.closed);
    }
    @Test public void prefersProgressiveFullHdOverInterlacedAndMpeg4Hd1080() {
        VideoEncoderProbe.Profile interlaced = profile("FHD_1080", "50i");
        VideoEncoderProbe.Profile progressive = profile("FHD_1080", "25p");
        VideoEncoderProbe.Profile mpeg4 = new VideoEncoderProbe.Profile("MPEG4", "HD_1080", "25p", "HQ", "16_9");
        assertSame(progressive, VideoEncoderProbe.chooseFullHd(Arrays.asList(mpeg4, interlaced, progressive)));
    }
    @Test public void scalarApi3ProfilesFromIlce5000ReportReachNativeRecording() throws Exception {
        encoder.caps.streamBufferAvailable = false;
        encoder.caps.profiles.clear();
        encoder.caps.profiles.addAll(Arrays.asList(
            new VideoEncoderProbe.Profile("AVCHD", "HD_1080", "50i", "FX", "16_9"),
            profile("HD_1080", "50i"),
            new VideoEncoderProbe.Profile("AVCHD", "HD_1080", "25p", "FX", "16_9"),
            profile("HD_1080", "25p"),
            new VideoEncoderProbe.Profile("MPEG4", "HD_1080", "25p", "HQ", "16_9"),
            new VideoEncoderProbe.Profile("MPEG4", "VGA", "25p", "SP", "4_3")));
        assertSame(encoder.caps.profiles.get(3), VideoEncoderProbe.chooseFullHd(encoder.caps.profiles));
        VideoEncoderProbe.Result result = run();
        assertEquals("RECORDER_RAN", result.status);
        assertTrue(encoder.prepared && encoder.started && encoder.stopped && encoder.closed);
        assertFalse(result.streamBufferAvailable);
        String text = report(result);
        assertTrue(text.contains("Probe revision: 3"));
        assertTrue(text.contains("BEGIN: configure Full-HD profile: AVCHD | HD_1080 | 25p | FH | 16_9"));
        assertTrue(text.contains("Selected profile interpretation: nominal 1920x1080; decoded dimensions unverified"));
    }
    @Test public void mpeg4Hd1080DoesNotCountAsFullHdOrStartRecording() {
        encoder.caps.profiles.clear();
        encoder.caps.profiles.add(new VideoEncoderProbe.Profile("MPEG4", "HD_1080", "25p", "HQ", "16_9"));
        assertEquals("NO_FHD_PROFILE", run().status);
        assertFalse(encoder.configured || encoder.started);
    }
    @Test public void ambiguousHd1080ProfilesAreNotAssumedToBeFullHd() {
        assertTrue(profile("HD_1080", "24p").fullHd());
        assertTrue(profile("HD_1080", "60i").fullHd());
        assertFalse(new VideoEncoderProbe.Profile("AVCHD", "HD_1080", "25p", "LP", "16_9").fullHd());
        assertFalse(new VideoEncoderProbe.Profile("AVCHD", "HD_1080", "25p", "FH", "4_3").fullHd());
        assertFalse(profile("HD_1080", "unknown").fullHd());
        assertFalse(new VideoEncoderProbe.Profile(null, "HD_1080", "25p", "FH", "16_9").fullHd());
    }
    @Test public void formatCoercedToMpeg4Hd1080RejectsRecordingEvenWhenSizeIsUnchanged() {
        encoder.caps.profiles.clear(); encoder.caps.profiles.add(profile("HD_1080", "25p"));
        encoder.readBack = new VideoEncoderProbe.Profile("MPEG4", "HD_1080", "25p", "HQ", "16_9");
        assertEquals("PROFILE_REJECTED", run().status);
        assertFalse(encoder.prepared || encoder.started);
        assertTrue(encoder.closed);
    }
    @Test public void silentlyCoercedProfileDoesNotCountAsFullHdOrStartRecording() {
        encoder.coerce = true;
        assertEquals("PROFILE_REJECTED", run().status);
        assertFalse(encoder.prepared || encoder.started);
        assertTrue(encoder.closed);
    }
    @Test public void prepareFailureRetainsTheAttemptedStageAndReleasesResources() throws Exception {
        encoder.failPrepare = true;
        VideoEncoderProbe.Result result = run();
        assertEquals("ERROR", result.status);
        assertTrue(report(result).contains("Failed at prepare encoder: IOException: Preparation rejected"));
        assertFalse(encoder.started);
        assertTrue(encoder.closed);
    }
    @Test public void partialStartFailureStillAttemptsToStopAndRelease() {
        encoder.failStart = true;
        VideoEncoderProbe.Result result = run();
        assertEquals("ERROR", result.status);
        assertFalse(result.recordingStarted);
        assertTrue(encoder.stopped && encoder.closed);
    }
    @Test public void nativeErrorNotificationDuringRecordingCannotBeReportedAsSuccess() {
        encoder = new FakeEncoder() {
            @Override public void start() throws IOException { super.start(); asyncError = true; }
        };
        VideoEncoderProbe.Result result = new VideoEncoderProbe(storage, 500).run(encoder, cancellation, "test");
        assertEquals("ERROR", result.status);
        assertTrue(encoder.stopped && encoder.closed);
        assertTrue(result.message.contains("Asynchronous native encoder error"));
    }
    @Test public void cancellationBeforeNativeAccessDoesNotOpenOrStartEncoder() {
        cancelled = true;
        assertEquals("CANCELLED", run().status);
        assertFalse(encoder.configured || encoder.started);
        assertTrue(encoder.closed);
    }
    @Test public void cancellationDuringRecordingStopsBeforeReturning() {
        encoder = new FakeEncoder() {
            @Override public void start() throws IOException { super.start(); cancelled = true; }
        };
        VideoEncoderProbe.Result result = new VideoEncoderProbe(storage, 2000).run(encoder, cancellation, "test");
        assertEquals("CANCELLED", result.status);
        assertTrue(result.recordingStarted && result.recordingStopped);
        assertTrue(encoder.closed);
    }
    @Test public void cleanupFailureDoesNotHideOriginalPrepareError() throws Exception {
        encoder.failPrepare = true; encoder.failClose = true;
        VideoEncoderProbe.Result result = run();
        String text = report(result);
        assertEquals("ERROR", result.status);
        assertTrue(text.contains("Preparation rejected"));
        assertTrue(text.contains("Restore failed"));
    }
    private void recordClip(final byte[] bytes) throws Exception {
        final File movieFolder = new File(root, "PRIVATE/AVCHD/BDMV/STREAM");
        assertTrue(movieFolder.mkdirs());
        encoder = new FakeEncoder() {
            @Override public void stop() {
                super.stop();
                try {
                    java.io.FileOutputStream output = new java.io.FileOutputStream(new File(movieFolder, "00007.MTS"));
                    try { output.write(bytes); } finally { output.close(); }
                } catch (IOException e) { throw new RuntimeException(e); }
            }
        };
    }
    @Test public void savedFullHdSpsGetsDistinctMetadataVerifiedResult() throws Exception {
        recordClip(AvchdVideoInfoTest.transport(AvchdVideoInfoTest.fullHdSps(true), 192, true, false, true));
        VideoEncoderProbe.Result result = run();
        assertEquals("FHD_CLIP_HEADER", result.status);
        assertEquals(1920, result.videoWidth); assertEquals(1080, result.videoHeight);
        assertTrue(report(result).contains("Recorded H.264 SPS dimensions: 1920x1080"));
        assertTrue(result.message.contains("Decoded frames and RAM streaming remain unverified"));
        assertTrue(encoder.closed);
    }
    @Test public void nonFullHdSpsCannotBeReportedAsFullHdDespiteAcceptedProfile() throws Exception {
        byte[] sps720 = { 0x67, 0x64, 0, 0x28, (byte) 0xAC, (byte) 0xB4, 2, (byte) 0x80, 0x2D, (byte) 0xFF,
            (byte) 0x80, (byte) 0xD4, 0x20, 0, 0, 7, (byte) 0xD2, 0, 3, (byte) 0xA9, (byte) 0x81,
            (byte) 0xD1, (byte) 0x80, 1, (byte) 0xF9, (byte) 0xA0, 0, 0x35, (byte) 0xC3, 0x7F,
            (byte) 0xFF, (byte) 0xC6, (byte) 0x8C, 0, 0x0F, (byte) 0xCD, 0, 1, (byte) 0xAE, 0x1B, (byte) 0xFF, (byte) 0xFE, 0x14, 0 };
        recordClip(AvchdVideoInfoTest.transport(sps720, 192, false, false, false));
        VideoEncoderProbe.Result result = run();
        assertEquals("CLIP_DIMENSIONS_MISMATCH", result.status);
        assertEquals(1280, result.videoWidth); assertEquals(720, result.videoHeight);
    }
    @Test public void unreadableClipDoesNotTurnRecorderSuccessIntoVerifiedFullHd() throws Exception {
        recordClip(new byte[1024]);
        VideoEncoderProbe.Result result = run();
        assertEquals("RECORDER_RAN", result.status);
        assertEquals(0, result.videoWidth);
        assertTrue(report(result).contains("Clip metadata unverified:"));
    }
    @Test public void reportsUseUniqueUppercaseEightDotThreeNamesAndFindNewNativeMovie() throws Exception {
        final File movieFolder = new File(root, "PRIVATE/AVCHD/BDMV/STREAM");
        assertTrue(movieFolder.mkdirs());
        encoder = new FakeEncoder() {
            @Override public void stop() {
                super.stop();
                try { new File(movieFolder, "00001.MTS").createNewFile(); }
                catch (IOException e) { throw new RuntimeException(e); }
            }
        };
        VideoEncoderProbe.Result first = run();
        VideoEncoderProbe.Result second = run();
        assertNotEquals(first.reportFile, second.reportFile);
        assertTrue(first.reportFile.getName().matches("[0-9A-F]{8}\\.TXT"));
        assertTrue(report(first).contains("New native clip: " + new File(movieFolder, "00001.MTS").getAbsolutePath()));
        assertTrue(report(second).contains("New native clips found in standard Sony movie folders: 0"));
    }
}
