package com.casklabs.ghostbuffer.probe;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Bounded, read-only MPEG-TS/H.264 SPS inspection; no native decoder or full-frame allocation. */
public final class AvchdVideoInfo {
    static final int SCAN_LIMIT = 4 * 1024 * 1024;
    public static final class Dimensions {
        public final int width, height;
        public final boolean frameMbsOnly;
        Dimensions(int width, int height, boolean frameMbsOnly) {
            this.width = width; this.height = height; this.frameMbsOnly = frameMbsOnly;
        }
        public boolean fullHd() { return width == 1920 && height == 1080; }
        @Override public String toString() { return width + "x" + height; }
    }

    public static Dimensions inspect(File clip, VideoEncoderProbe.Cancellation cancellation) throws IOException, InterruptedException {
        InputStream input = new BufferedInputStream(new FileInputStream(clip));
        try { return inspect(input, cancellation); }
        finally { input.close(); }
    }

    static Dimensions inspect(InputStream input, VideoEncoderProbe.Cancellation cancellation) throws IOException, InterruptedException {
        byte[] prefix = new byte[768];
        int count = read(input, prefix, 0, prefix.length);
        int start = -1, stride = 0;
        int[] sizes = { 192, 188 };
        for (int size : sizes) {
            for (int i = 0; i < size && i + 2 * size < count; i++) {
                if (prefix[i] == 0x47 && prefix[i + size] == 0x47 && prefix[i + 2 * size] == 0x47) {
                    start = i; stride = size; break;
                }
            }
            if (start >= 0) break;
        }
        if (start < 0) throw new IOException("No 188/192-byte transport-stream sync found");
        // Replay the small detection prefix without requiring mark/reset on the caller's stream.
        InputStream packets = new java.io.SequenceInputStream(
            new java.io.ByteArrayInputStream(prefix, start, count - start), input);
        byte[] packet = new byte[192];
        NalReader nal = new NalReader();
        int videoPid = -1, lastCounter = -1;
        for (int consumed = start; consumed + stride <= SCAN_LIMIT; consumed += stride) {
            if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int length = read(packets, packet, 0, stride);
            if (length < 188) break;
            if (packet[0] != 0x47) throw new IOException("Transport-stream sync lost");
            int pid = ((packet[1] & 31) << 8) | (packet[2] & 255);
            boolean payloadStart = (packet[1] & 64) != 0;
            if ((packet[1] & 128) != 0 || (packet[3] & 192) != 0) {
                if (pid == videoPid) { nal.reset(); lastCounter = -1; }
                continue;
            }
            int control = (packet[3] >> 4) & 3;
            if ((control & 1) == 0) continue;
            int offset = 4;
            if ((control & 2) != 0) offset += 1 + (packet[4] & 255);
            if (offset >= 188) continue;
            if (payloadStart) {
                // Select a video PES stream, excluding audio and programme tables.
                if (offset + 9 > 188 || packet[offset] != 0 || packet[offset + 1] != 0
                    || packet[offset + 2] != 1 || (packet[offset + 3] & 240) != 224) continue;
                if (videoPid < 0) videoPid = pid;
                if (pid != videoPid) continue;
                offset += 9 + (packet[offset + 8] & 255);
                if (offset > 188) { nal.reset(); lastCounter = -1; continue; }
            }
            if (pid != videoPid) continue;
            int counter = packet[3] & 15;
            if (lastCounter == counter) continue; // Repeated transport packet.
            if (lastCounter >= 0 && counter != ((lastCounter + 1) & 15)) nal.reset();
            lastCounter = counter;
            for (int i = offset; i < 188; i++) {
                Dimensions dimensions = nal.feed(packet[i] & 255);
                if (dimensions != null) return dimensions;
            }
            if (length < stride) break;
        }
        Dimensions dimensions = nal.finish();
        if (dimensions != null) return dimensions;
        throw new IOException("No readable H.264 SPS in first 4 MiB of clip");
    }

    private static int read(InputStream input, byte[] bytes, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int count = input.read(bytes, offset + total, length - total);
            if (count < 0) break;
            if (count == 0) throw new IOException("Input made no progress");
            total += count;
        }
        return total;
    }

    private static final class NalReader {
        private final byte[] sps = new byte[4096];
        private int zeros, count;
        private boolean header, collecting;
        void reset() { zeros = count = 0; header = collecting = false; }
        Dimensions feed(int value) {
            if (value == 0) { zeros = Math.min(zeros + 1, 4096); return null; }
            if (value == 1 && zeros >= 2) {
                Dimensions dimensions = finish();
                zeros = count = 0; collecting = false; header = true;
                return dimensions;
            }
            if (header) {
                header = false; collecting = (value & 159) == 7;
            } else if (collecting) {
                while (zeros-- > 0) append(0);
            }
            zeros = 0;
            if (collecting) append(value);
            return null;
        }
        void append(int value) {
            if (count == sps.length) { collecting = false; count = 0; }
            else sps[count++] = (byte) value;
        }
        Dimensions finish() {
            if (!collecting || count < 4) return null;
            try { return parseSps(sps, count); }
            catch (IOException ignored) { return null; }
        }
    }

    static Dimensions parseSps(byte[] nal, int length) throws IOException {
        if (length < 4 || length > nal.length || (nal[0] & 159) != 7) throw new IOException("Invalid SPS NAL");
        byte[] rbsp = new byte[length - 1];
        int count = 0, zeros = 0;
        for (int i = 1; i < length; i++) {
            int value = nal[i] & 255;
            if (zeros >= 2 && value == 3) {
                if (i + 1 >= length || (nal[i + 1] & 255) > 3) throw new IOException("Invalid emulation prevention");
                zeros = 0; continue;
            }
            rbsp[count++] = nal[i]; zeros = value == 0 ? zeros + 1 : 0;
        }
        Bits bits = new Bits(rbsp, count);
        int profile = bits.read(8);
        bits.read(8); bits.read(8);
        if (bits.ue() > 31) throw new IOException("Invalid SPS id");
        int chroma = 1;
        boolean separateColourPlane = false;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
            || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
            || profile == 139 || profile == 134 || profile == 135 || profile == 144) {
            chroma = bits.ue();
            if (chroma > 3) throw new IOException("Invalid chroma format");
            if (chroma == 3) separateColourPlane = bits.read(1) != 0;
            if (bits.ue() > 6 || bits.ue() > 6) throw new IOException("Invalid bit depth");
            bits.read(1);
            if (bits.read(1) != 0) {
                for (int i = 0; i < (chroma == 3 ? 12 : 8); i++) {
                    if (bits.read(1) == 0) continue;
                    int last = 8, next = 8;
                    for (int j = 0; j < (i < 6 ? 16 : 64); j++) {
                        if (next != 0) next = (last + bits.se()) & 255;
                        if (next != 0) last = next;
                    }
                }
            }
        } else if (profile != 66 && profile != 77 && profile != 88) throw new IOException("Unsupported H.264 profile");
        if (bits.ue() > 12) throw new IOException("Invalid frame number length");
        int poc = bits.ue();
        if (poc == 0) { if (bits.ue() > 12) throw new IOException("Invalid POC length"); }
        else if (poc == 1) {
            bits.read(1); bits.se(); bits.se();
            int cycle = bits.ue();
            if (cycle > 255) throw new IOException("Invalid POC cycle");
            for (int i = 0; i < cycle; i++) bits.se();
        } else if (poc != 2) throw new IOException("Invalid POC type");
        bits.ue(); bits.read(1);
        int widthMbs = bits.ue(), heightMaps = bits.ue();
        if (widthMbs > 1023 || heightMaps > 1023) throw new IOException("Oversized coded dimensions");
        boolean frameOnly = bits.read(1) != 0;
        int frameFactor = frameOnly ? 1 : 2;
        if (!frameOnly) bits.read(1);
        bits.read(1);
        int left = 0, right = 0, top = 0, bottom = 0;
        if (bits.read(1) != 0) {
            left = bits.ue(); right = bits.ue(); top = bits.ue(); bottom = bits.ue();
            if (left > 16384 || right > 16384 || top > 16384 || bottom > 16384) throw new IOException("Oversized crop");
        }
        bits.read(1); // vui_parameters_present_flag; dimensions do not require VUI parsing.
        int chromaArray = separateColourPlane ? 0 : chroma;
        int cropX = chromaArray == 1 || chromaArray == 2 ? 2 : 1;
        int cropY = frameFactor * (chromaArray == 1 ? 2 : 1);
        int width = 16 * (widthMbs + 1) - cropX * (left + right);
        int height = 16 * (heightMaps + 1) * frameFactor - cropY * (top + bottom);
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384) throw new IOException("Invalid cropped dimensions");
        return new Dimensions(width, height, frameOnly);
    }

    private static final class Bits {
        final byte[] bytes;
        final int limit;
        int position;
        Bits(byte[] bytes, int length) { this.bytes = bytes; limit = length * 8; }
        int read(int count) throws IOException {
            if (position + count > limit) throw new IOException("Truncated SPS");
            int value = 0;
            for (int i = 0; i < count; i++, position++) value = (value << 1) | ((bytes[position >> 3] >> (7 - (position & 7))) & 1);
            return value;
        }
        int ue() throws IOException {
            int zeros = 0;
            while (read(1) == 0) if (++zeros > 29) throw new IOException("Oversized Exp-Golomb value");
            return (1 << zeros) - 1 + read(zeros);
        }
        int se() throws IOException { int value = ue(); return (value & 1) == 0 ? -(value / 2) : (value + 1) / 2; }
    }
    private AvchdVideoInfo() {}
}
