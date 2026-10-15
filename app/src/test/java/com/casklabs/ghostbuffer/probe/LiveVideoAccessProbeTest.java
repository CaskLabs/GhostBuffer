package com.casklabs.ghostbuffer.probe;

import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.CRC32;
import static org.junit.Assert.*;

public class LiveVideoAccessProbeTest {
    private static final String PATH = "/card/PRIVATE/AVCHD/BDMV/STREAM/00011.MTS";
    private final StringWriter report = new StringWriter();
    private final FakeSource source = new FakeSource();
    private boolean cancelled;
    private LiveVideoAccessProbe probe;
    private static final class FakeSource implements LiveVideoAccessProbe.Source {
        final Set<String> paths = new LinkedHashSet<String>();
        byte[] data = new byte[0];
        int position, opens, closes, reads, discoveries;
        boolean failOpen, failRead, missingOpen;
        String missingPath;
        @Override public Set<String> movies() { discoveries++; return paths; }
        @Override public LiveVideoAccessProbe.ReadHandle open(String path) throws IOException {
            if (missingOpen || path.equals(missingPath)) throw new FileNotFoundException("Candidate not available");
            if (failOpen) throw new IOException("File temporarily locked");
            opens++;
            return new LiveVideoAccessProbe.ReadHandle() {
                @Override public long length() { return data.length; }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    reads++;
                    if (failRead) throw new IOException("Read failed");
                    int count = Math.min(length, data.length - position);
                    if (count <= 0) return -1;
                    System.arraycopy(data, position, bytes, offset, count); position += count;
                    return count;
                }
                @Override public void close() { closes++; }
            };
        }
    }
    @Before public void setup() {
        probe = new LiveVideoAccessProbe(source, Collections.<String>emptySet(), new VideoEncoderProbe.Cancellation() {
            @Override public boolean isCancelled() { return cancelled; }
        }, report);
    }
    @Test public void fullHdBytesAreParsedFromRamBeforeObservationEnds() throws Exception {
        source.paths.add(PATH);
        source.data = AvchdVideoInfoTest.transport(AvchdVideoInfoTest.fullHdSps(true), 192, true, false, true);
        probe.sample(100);
        assertTrue(report.toString().contains("H.264 SPS parsed from RAM while recording, before STOP at 100 ms: 1920x1080"));
        LiveVideoAccessProbe.Result result = probe.finish(); probe.writeSummary();
        assertEquals("LIVE_SD_FHD_HEADER", result.status);
        assertEquals(source.data.length, result.bytesRead);
        assertEquals(1920, result.dimensions.width);
        assertEquals(1, source.closes);
        assertTrue(report.toString().contains("NOT direct encoder RAM access"));
        assertTrue(report.toString().contains("Decodable pre-trigger clip and sustained real-time throughput: NOT VERIFIED"));
    }
    @Test public void existingMoviesCannotBeMistakenForNewLiveData() throws Exception {
        source.paths.add(PATH); source.data = new byte[1000];
        LiveVideoAccessProbe existing = new LiveVideoAccessProbe(source, new HashSet<String>(source.paths),
            new VideoEncoderProbe.Cancellation() { @Override public boolean isCancelled() { return false; } }, report);
        existing.sample(0);
        assertEquals("NO_LIVE_FILE", existing.finish().status);
        assertEquals(0, source.opens);
    }
    @Test public void fileCreatedOnlyAfterRecordingCannotCountAsLiveAccess() throws Exception {
        probe.sample(0);
        LiveVideoAccessProbe.Result result = probe.finish();
        source.paths.add(PATH); source.data = new byte[1024];
        try { probe.sample(100); fail("Sampling after STOP must be forbidden"); }
        catch (IllegalStateException expected) {}
        assertEquals("NO_LIVE_FILE", result.status);
        assertEquals(0, result.bytesRead); assertEquals(0, source.reads);
    }
    @Test public void emptyFileIsVisibleWithoutClaimingReadableStream() throws Exception {
        source.paths.add(PATH); probe.sample(0);
        assertEquals("LIVE_FILE_VISIBLE", probe.finish().status);
        assertEquals(0, source.reads);
    }
    @Test public void readingResumesWhenAFileGrowsPastPreviousEof() throws Exception {
        byte[] stream = AvchdVideoInfoTest.transport(AvchdVideoInfoTest.fullHdSps(true), 192, true, false, true);
        source.paths.add(PATH); source.data = Arrays.copyOf(stream, 200);
        probe.sample(0);
        source.data = stream;
        probe.sample(100);
        LiveVideoAccessProbe.Result result = probe.finish();
        assertEquals(stream.length, result.bytesRead);
        assertEquals("LIVE_SD_FHD", result.status);
        assertEquals(2, result.readSamples);
        assertEquals(200, result.firstVisibleLength);
        assertEquals(stream.length, result.maxVisibleLength);
        assertEquals(0, result.firstReadMs); assertEquals(100, result.lastReadMs);
        assertEquals(1, source.opens);
    }
    @Test public void ringRetainsOnlyNewestCompressedBytesWithBoundedMemoryAndMatchingCrc() throws Exception {
        source.paths.add(PATH); source.data = new byte[LiveVideoAccessProbe.RAM_LIMIT + 70000];
        for (int i = 0; i < source.data.length; i++) source.data[i] = (byte) (i * 31 + i / 997);
        probe.sample(0); probe.sample(100); probe.sample(200);
        LiveVideoAccessProbe.Result result = probe.finish();
        CRC32 expected = new CRC32();
        expected.update(source.data, source.data.length - LiveVideoAccessProbe.RAM_LIMIT, LiveVideoAccessProbe.RAM_LIMIT);
        assertEquals(source.data.length, result.bytesRead);
        assertEquals(LiveVideoAccessProbe.RAM_LIMIT, result.retainedBytes);
        assertEquals(expected.getValue(), result.retainedCrc32);
        assertEquals("LIVE_SD_BYTES", result.status);
        assertNull(result.dimensions);
    }
    @Test public void pollingAndPerSampleReadsHaveBounds() throws Exception {
        source.paths.add(PATH); source.data = new byte[2 * LiveVideoAccessProbe.RAM_LIMIT];
        probe.sample(0);
        assertEquals(256 * 1024, source.position);
        int reads = source.reads;
        probe.sample(10); probe.sample(99);
        assertEquals(reads, source.reads);
        probe.sample(100);
        assertEquals(512 * 1024, source.position);
        probe.finish();
    }
    @Test public void arbitraryReadableBytesAreNotProofOfFullHd() throws Exception {
        source.paths.add(PATH); source.data = new byte[2000]; probe.sample(0);
        assertEquals("LIVE_SD_BYTES", probe.finish().status);
    }
    @Test public void temporaryOpenErrorIsExplicitAndCanRecoverDuringRecording() throws Exception {
        source.paths.add(PATH); source.failOpen = true; probe.sample(0);
        source.failOpen = false; source.data = new byte[123]; probe.sample(100);
        LiveVideoAccessProbe.Result result = probe.finish();
        assertEquals("LIVE_SD_BYTES", result.status);
        assertTrue(result.lastReadError.contains("temporarily locked"));
    }
    @Test public void deniedOpenDoesNotLookLikeAnEmptySuccessfulCapture() throws Exception {
        source.paths.add(PATH); source.failOpen = true; probe.sample(0);
        assertEquals("LIVE_SD_READ_ERROR", probe.finish().status);
    }
    @Test public void failedReadIsReportedAndHandleStillClosesOnlyOnce() throws Exception {
        source.paths.add(PATH); source.data = new byte[123]; source.failRead = true; probe.sample(0);
        LiveVideoAccessProbe.Result result = probe.finish(); probe.finish(); probe.writeSummary();
        assertEquals(0, result.bytesRead); assertTrue(result.lastReadError.contains("Read failed"));
        assertEquals(1, source.closes);
        assertTrue(report.toString().contains("Last live read error:"));
    }
    @Test public void cancelledObservationDoesNotTouchSd() throws Exception {
        cancelled = true;
        try { probe.sample(0); fail("Expected cancellation"); } catch (InterruptedException expected) {}
        probe.finish();
        assertEquals(0, source.discoveries); assertEquals(0, source.opens);
    }
    @Test public void predictionUsesOnlyExistingAvchdNamesAndHasFourTargets() {
        File root = new File("/card");
        Set<String> previous = new HashSet<String>(Arrays.asList(PATH,
            "/card/PRIVATE/AVCHD/BDMV/STREAM/00014.MTS", "/card/DCIM/99999.MTS",
            "/card/PRIVATE/AVCHD/BDMV/STREAM/99999.MP4"));
        Set<String> predicted = LiveVideoAccessProbe.predictedAvchdPaths(root, previous);
        assertEquals(4, predicted.size());
        assertTrue(predicted.contains("/card/PRIVATE/AVCHD/BDMV/STREAM/00015.MTS"));
        assertTrue(predicted.contains("/card/PRIVATE/AVCHD/BDMV/STREAM/00018.MTS"));
        assertTrue(Collections.disjoint(previous, predicted));
    }
    @Test public void predictionWrapsAtFiveDigitsAndExcludesAlreadyExistingClips() {
        Set<String> previous = new HashSet<String>(Arrays.asList(
            "/card/PRIVATE/AVCHD/BDMV/STREAM/99999.MTS", "/card/PRIVATE/AVCHD/BDMV/STREAM/00000.MTS"));
        Set<String> predicted = LiveVideoAccessProbe.predictedAvchdPaths(new File("/card"), previous);
        assertEquals(3, predicted.size()); assertTrue(Collections.disjoint(previous, predicted));
        assertTrue(predicted.contains("/card/PRIVATE/AVCHD/BDMV/STREAM/00001.MTS"));
    }
    @Test public void cardSourceKeepsPrecomputedCandidatesWithoutRediscoveringMovies() {
        Set<String> previous = new HashSet<String>(Collections.singleton(PATH));
        LiveVideoAccessProbe.Source card = LiveVideoAccessProbe.cardSource(new File("/card"), previous);
        Set<String> candidates = card.movies();
        previous.add("/card/PRIVATE/AVCHD/BDMV/STREAM/00500.MTS");
        assertSame(candidates, card.movies());
        assertTrue(candidates.contains("/card/PRIVATE/AVCHD/BDMV/STREAM/00012.MTS"));
        try { candidates.clear(); fail("Frozen candidate set expected"); } catch (UnsupportedOperationException expected) {}
    }
    @Test public void unavailablePredictedFilesDoNotCountAsVisibleOrSuccessfulReads() throws Exception {
        source.paths.add(PATH); source.missingOpen = true; probe.sample(0);
        LiveVideoAccessProbe.Result result = probe.finish();
        assertEquals("NO_LIVE_FILE", result.status); assertNull(result.path);
        assertNotNull(result.lastOpenFailure); assertEquals(1, result.openAttempts);
        assertEquals(0, result.bytesRead);
    }
    @Test public void missingFirstCandidateDoesNotPreventOpeningAnotherCandidate() throws Exception {
        source.paths.add(PATH); source.paths.add(PATH + "X.MTS"); source.missingPath = PATH;
        source.data = new byte[200]; probe.sample(0);
        assertEquals("LIVE_SD_BYTES", probe.finish().status);
        assertEquals(200, source.position);
    }
}
