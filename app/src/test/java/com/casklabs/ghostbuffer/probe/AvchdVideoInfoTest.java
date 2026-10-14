package com.casklabs.ghostbuffer.probe;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AvchdVideoInfoTest {
    private static final VideoEncoderProbe.Cancellation NEVER = new VideoEncoderProbe.Cancellation() {
        @Override public boolean isCancelled() { return false; }
    };
    // Real SPS headers from FFmpeg's public AVCHD samples (no picture payloads):
    // samples.ffmpeg.org/V-codecs/h264/sony-hdr-cx6-avchd-interlaced-decoding-problem/
    // samples.ffmpeg.org/V-codecs/h264/avchd_lite_14s_playback_speed_problems.MTS
    private static final byte[] SONY_SPS = hex("274d0028f602d0227efff000400031000012c00003a980e86000f424000112a8aef2e3430007a1200008954577970e5844228b");
    private static final byte[] LITE_SPS = hex("67640028acb402802dff80d42000007d20003a981d18001f9a00035c37fffc68c000fcd0001ae1bfffe140");
    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }
    private static final class BitWriter {
        final StringBuilder bits = new StringBuilder();
        void bit(int value) { bits.append(value == 0 ? '0' : '1'); }
        void ue(int value) {
            String code = Integer.toBinaryString(value + 1);
            for (int i = 1; i < code.length(); i++) bit(0);
            bits.append(code);
        }
        byte[] finish() {
            bit(1);
            while (bits.length() % 8 != 0) bit(0);
            byte[] bytes = new byte[bits.length() / 8 + 4];
            bytes[0] = 0x67; bytes[1] = 66; bytes[2] = 0; bytes[3] = 40;
            for (int i = 4; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(bits.substring((i - 4) * 8, (i - 3) * 8), 2);
            return bytes;
        }
    }
    static byte[] fullHdSps(boolean frameOnly) {
        BitWriter bits = new BitWriter();
        bits.ue(0); // SPS id
        bits.ue(0); bits.ue(0); bits.ue(0); // frame number and POC lengths
        bits.ue(1); bits.bit(0); // reference frames, gap flag
        bits.ue(119); bits.ue(frameOnly ? 67 : 33); // coded 1920x1088
        bits.bit(frameOnly ? 1 : 0);
        if (!frameOnly) bits.bit(0);
        bits.bit(1); bits.bit(1); // direct inference, cropping
        bits.ue(0); bits.ue(0); bits.ue(0); bits.ue(frameOnly ? 4 : 2); // crop to 1080
        bits.bit(0); // no VUI
        return bits.finish();
    }
    static byte[] transport(byte[] sps, int stride, boolean split, boolean gap, boolean audio) throws IOException {
        ByteArrayOutputStream es = new ByteArrayOutputStream();
        if (split) for (int i = 0; i < 163; i++) es.write(0x55);
        es.write(new byte[] { 0, 0, 1 }); es.write(sps); es.write(new byte[] { 0, 0, 1, 0x68, (byte) 0x80 });
        byte[] payload = es.toByteArray();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int offset = 0, sequence = 0;
        while (offset < payload.length) {
            if (audio && sequence == 1) {
                byte[] other = packet(0x1100, 0, true, new byte[] { 0, 0, 1, (byte) 0xC0, 0, 0, (byte) 0x80, 0, 0, 0, 0, 1, 0x67 });
                if (stride == 192) stream.write(new byte[4]);
                stream.write(other);
            }
            int capacity = sequence == 0 ? 175 : 184;
            int length = Math.min(capacity, payload.length - offset);
            ByteArrayOutputStream pes = new ByteArrayOutputStream();
            if (sequence == 0) pes.write(new byte[] { 0, 0, 1, (byte) 0xE0, 0, 0, (byte) 0x80, 0, 0 });
            pes.write(payload, offset, length);
            if (stride == 192) stream.write(new byte[4]);
            stream.write(packet(0x1011, sequence + (gap && sequence > 0 ? 1 : 0), sequence == 0, pes.toByteArray()));
            offset += length; sequence++;
        }
        // Three sync points are required even for a short SPS-only fixture.
        while (stream.size() < stride * 3) {
            if (stride == 192) stream.write(new byte[4]);
            stream.write(packet(0x1FFF, 0, false, new byte[0]));
        }
        return stream.toByteArray();
    }
    private static byte[] packet(int pid, int counter, boolean start, byte[] payload) {
        byte[] ts = new byte[188]; Arrays.fill(ts, (byte) 0xFF);
        ts[0] = 0x47; ts[1] = (byte) ((pid >> 8) | (start ? 64 : 0)); ts[2] = (byte) pid;
        ts[3] = (byte) (0x30 | (counter & 15));
        int adaptation = 183 - payload.length;
        if (adaptation < 0) ts[3] = (byte) (0x10 | (counter & 15));
        else {
            ts[4] = (byte) adaptation;
            if (adaptation > 0) ts[5] = 0;
        }
        System.arraycopy(payload, 0, ts, 5 + adaptation, payload.length);
        return ts;
    }
    private static AvchdVideoInfo.Dimensions inspect(byte[] bytes) throws Exception {
        return AvchdVideoInfo.inspect(new ByteArrayInputStream(bytes), NEVER);
    }
    @Test public void progressiveSpsCroppingReports1080RatherThan1088() throws Exception {
        AvchdVideoInfo.Dimensions dims = AvchdVideoInfo.parseSps(fullHdSps(true), fullHdSps(true).length);
        assertEquals(1920, dims.width); assertEquals(1080, dims.height); assertTrue(dims.fullHd()); assertTrue(dims.frameMbsOnly);
    }
    @Test public void interlacedCropUsesFieldHeightUnits() throws Exception {
        AvchdVideoInfo.Dimensions dims = AvchdVideoInfo.parseSps(fullHdSps(false), fullHdSps(false).length);
        assertEquals("1920x1080", dims.toString()); assertFalse(dims.frameMbsOnly);
    }
    @Test public void realHighProfileAvchdLiteSpsIsNotMislabelledFullHd() throws Exception {
        AvchdVideoInfo.Dimensions dims = AvchdVideoInfo.parseSps(LITE_SPS, LITE_SPS.length);
        assertEquals("1280x720", dims.toString()); assertFalse(dims.fullHd());
    }
    @Test public void realSonyMainProfileSpsKeepsItsAnamorphicPixelWidth() throws Exception {
        AvchdVideoInfo.Dimensions dims = AvchdVideoInfo.parseSps(SONY_SPS, SONY_SPS.length);
        assertEquals("1440x1080", dims.toString()); assertFalse(dims.fullHd()); assertFalse(dims.frameMbsOnly);
    }
    @Test public void readsMtsWithSpsSplitAcrossPacketsAndInterleavedAudio() throws Exception {
        assertEquals("1920x1080", inspect(transport(fullHdSps(true), 192, true, false, true)).toString());
    }
    @Test public void alsoReadsPlain188ByteTransportStreams() throws Exception {
        assertEquals("1280x720", inspect(transport(LITE_SPS, 188, false, false, false)).toString());
    }
    @Test(expected = IOException.class) public void missingContinuationCannotProduceFalseVerifiedDimensions() throws Exception {
        inspect(transport(fullHdSps(true), 192, true, true, false));
    }
    @Test(expected = IOException.class) public void rejectsTruncatedSps() throws Exception {
        AvchdVideoInfo.parseSps(new byte[] { 0x67, 100, 0, 40 }, 4);
    }
    @Test(expected = IOException.class) public void rejectsUnboundedExpGolombValues() throws Exception {
        byte[] nal = new byte[32]; nal[0] = 0x67; nal[1] = 66; nal[3] = 40;
        AvchdVideoInfo.parseSps(nal, nal.length);
    }
    @Test(expected = IOException.class) public void invalidClipIsUnverified() throws Exception { inspect(new byte[1000]); }
    @Test(expected = InterruptedException.class) public void cancellationStopsInspection() throws Exception {
        AvchdVideoInfo.inspect(new ByteArrayInputStream(transport(fullHdSps(true), 192, false, false, false)),
            new VideoEncoderProbe.Cancellation() { @Override public boolean isCancelled() { return true; } });
    }
    @Test public void missingSpsHasBoundedIoEvenForEndlessInput() throws Exception {
        final byte[] empty = packet(0x1FFF, 0, false, new byte[0]);
        final int[] read = { 0 };
        InputStream endless = new InputStream() {
            @Override public int read() { return empty[read[0]++ % empty.length] & 255; }
        };
        try { AvchdVideoInfo.inspect(endless, NEVER); fail("Expected missing SPS"); }
        catch (IOException expected) { assertTrue(read[0] <= AvchdVideoInfo.SCAN_LIMIT + 192); }
    }
}
