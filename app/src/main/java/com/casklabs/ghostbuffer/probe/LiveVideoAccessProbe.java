package com.casklabs.ghostbuffer.probe;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.util.Set;
import java.util.zip.CRC32;

/** Read-only SD-mediated access test. Samples must stop before the native recorder stops. */
public final class LiveVideoAccessProbe {
    public interface ReadHandle {
        long length() throws IOException;
        int read(byte[] bytes, int offset, int length) throws IOException;
        void close() throws IOException;
    }
    public interface Source {
        Set<String> movies();
        ReadHandle open(String path) throws IOException;
    }
    public static final class Result {
        public String status = "NO_LIVE_FILE";
        public String path, lastReadError;
        public long bytesRead, firstReadMs = -1, lastReadMs = -1, maxVisibleLength;
        public long firstVisibleLength = -1;
        public int retainedBytes, readSamples;
        public long retainedCrc32;
        public AvchdVideoInfo.Dimensions dimensions;
    }
    static final int RAM_LIMIT = 512 * 1024;
    private static final int PREFIX_LIMIT = 64 * 1024;
    private static final int READ_PER_SAMPLE = 256 * 1024;
    private final Source source;
    private final Set<String> before;
    private final VideoEncoderProbe.Cancellation cancellation;
    private final Writer report;
    private final byte[] ring = new byte[RAM_LIMIT], prefix = new byte[PREFIX_LIMIT], chunk = new byte[32 * 1024];
    private final Result result = new Result();
    private ReadHandle input;
    private int writeOffset, prefixLength;
    private long nextSample;
    private boolean finished;

    public LiveVideoAccessProbe(Source source, Set<String> before, VideoEncoderProbe.Cancellation cancellation, Writer report) {
        this.source = source; this.before = before; this.cancellation = cancellation; this.report = report;
    }
    public static Source cardSource(final File cardRoot) {
        return new Source() {
            @Override public Set<String> movies() { return VideoEncoderProbe.movieFiles(cardRoot); }
            @Override public ReadHandle open(String path) throws IOException {
                final RandomAccessFile file = new RandomAccessFile(path, "r");
                return new ReadHandle() {
                    @Override public long length() throws IOException { return file.length(); }
                    @Override public int read(byte[] bytes, int offset, int length) throws IOException { return file.read(bytes, offset, length); }
                    @Override public void close() throws IOException { file.close(); }
                };
            }
        };
    }

    public void sample(long elapsedMs) throws InterruptedException {
        if (finished) throw new IllegalStateException("Cannot sample after recording observation ended");
        checkCancelled();
        if (elapsedMs < nextSample) return;
        nextSample = elapsedMs + 100;
        try {
            if (input == null) {
                for (String path : source.movies()) {
                    String upper = path.toUpperCase(java.util.Locale.US);
                    if (before.contains(path) || (!upper.endsWith(".MTS") && !upper.endsWith(".M2TS"))) continue;
                    note("Attempt live SD read-only open before STOP at " + elapsedMs + " ms: " + path);
                    input = source.open(path);
                    result.path = path;
                    result.status = "LIVE_FILE_VISIBLE";
                    note("Live SD file visible before STOP at " + elapsedMs + " ms: " + path);
                    break;
                }
            }
            if (input == null) return;
            long length = input.length();
            if (result.firstVisibleLength < 0) result.firstVisibleLength = length;
            result.maxVisibleLength = Math.max(result.maxVisibleLength, length);
            int budget = READ_PER_SAMPLE;
            long previousBytes = result.bytesRead;
            while (length > result.bytesRead && budget > 0) {
                checkCancelled();
                int request = (int) Math.min(Math.min(length - result.bytesRead, chunk.length), budget);
                int count = input.read(chunk, 0, request);
                if (count <= 0) break;
                if (count > request) throw new IOException("Invalid live read length");
                int first = Math.min(count, ring.length - writeOffset);
                System.arraycopy(chunk, 0, ring, writeOffset, first);
                if (count > first) System.arraycopy(chunk, first, ring, 0, count - first);
                writeOffset = (writeOffset + count) % ring.length;
                int prefixBytes = Math.min(count, prefix.length - prefixLength);
                System.arraycopy(chunk, 0, prefix, prefixLength, prefixBytes);
                prefixLength += prefixBytes;
                result.bytesRead += count;
                result.retainedBytes = (int) Math.min(result.bytesRead, ring.length);
                result.lastReadMs = elapsedMs;
                result.status = result.dimensions == null ? "LIVE_SD_BYTES" : result.status;
                if (result.firstReadMs < 0) {
                    result.firstReadMs = elapsedMs;
                    note("First compressed bytes copied from SD to RAM before STOP at " + elapsedMs + " ms");
                }
                budget -= count;
            }
            if (result.bytesRead > previousBytes) result.readSamples++;
            if (result.dimensions == null && prefixLength >= 576) {
                try {
                    result.dimensions = AvchdVideoInfo.inspect(new ByteArrayInputStream(prefix, 0, prefixLength), cancellation);
                    note("H.264 SPS parsed from RAM while recording, before STOP at " + elapsedMs + " ms: " + result.dimensions);
                } catch (IOException ignored) { /* A growing file can end partway through a packet or SPS. */ }
            }
            if (result.dimensions != null) {
                result.status = result.dimensions.fullHd()
                    ? (result.maxVisibleLength > result.firstVisibleLength && result.readSamples > 1 ? "LIVE_SD_FHD" : "LIVE_SD_FHD_HEADER")
                    : "LIVE_SD_OTHER_SIZE";
            }
        } catch (IOException e) {
            result.lastReadError = VideoEncoderProbe.describe(e);
        } catch (SecurityException e) {
            result.lastReadError = VideoEncoderProbe.describe(e);
        }
    }

    /** Closes the live reader without reading any post-stop data. Idempotent. */
    public Result finish() {
        if (finished) return result;
        finished = true;
        if (input != null) {
            try { input.close(); } catch (IOException e) { result.lastReadError = VideoEncoderProbe.describe(e); }
            input = null;
        }
        CRC32 crc = new CRC32();
        int oldest = result.retainedBytes == ring.length ? writeOffset : 0;
        int first = Math.min(result.retainedBytes, ring.length - oldest);
        crc.update(ring, oldest, first);
        crc.update(ring, 0, result.retainedBytes - first);
        result.retainedCrc32 = crc.getValue();
        if (result.path == null && result.lastReadError != null) result.status = "LIVE_SD_READ_ERROR";
        return result;
    }

    public void writeSummary() throws IOException {
        if (!finished) throw new IllegalStateException("Finish live observation before reporting");
        report.write("Live SD access status: " + result.status + "\n");
        report.write("Live access route: SD file -> RAM; NOT direct encoder RAM access\n");
        report.write("Compressed bytes read before STOP: " + result.bytesRead + "\n");
        report.write("Retained rolling RAM bytes: " + result.retainedBytes + " / " + RAM_LIMIT + "\n");
        report.write("Retained RAM CRC32: " + Long.toHexString(result.retainedCrc32) + "\n");
        report.write("First/last live read ms after START: " + result.firstReadMs + " / " + result.lastReadMs + "\n");
        report.write("Maximum file length visible during recording: " + result.maxVisibleLength + "\n");
        report.write("Initial visible file length: " + result.firstVisibleLength + "\n");
        report.write("Live read samples with new bytes: " + result.readSamples + "\n");
        report.write("File growth observed before STOP: " + (result.maxVisibleLength > result.firstVisibleLength && result.firstVisibleLength >= 0) + "\n");
        report.write("Full-HD SPS read before STOP: " + (result.dimensions != null && result.dimensions.fullHd()) + "\n");
        if (result.lastReadError != null) report.write("Last live read error: " + result.lastReadError + "\n");
        report.write("Decodable pre-trigger clip and sustained real-time throughput: NOT VERIFIED\n");
        report.flush();
    }
    private void checkCancelled() throws InterruptedException {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    private void note(String text) throws IOException { report.write(text + "\n"); report.flush(); }
}
