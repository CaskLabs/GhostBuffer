package com.casklabs.ghostbuffer.probe;

import org.junit.Test;
import java.io.StringWriter;
import static org.junit.Assert.*;

public class NativeRecorderEventsTest {
    @Test public void normalStopCallbackAfterRequestedStopIsNotAnEarlyEnd() throws Exception {
        NativeRecorderEvents events = new NativeRecorderEvents();
        events.startRequested(100); events.onStarted(true, 120);
        events.stopRequested(6100); events.onStopped(6110);
        assertTrue(events.stopped()); assertFalse(events.unexpectedStop());
        StringWriter report = new StringWriter(); events.writeSummary(report);
        assertTrue(report.toString().contains("onStopped at 6010 ms; STOP already requested=true"));
    }
    @Test public void nativeStopBeforeRequestRemainsAnEarlyEndAfterCleanup() throws Exception {
        NativeRecorderEvents events = new NativeRecorderEvents();
        events.startRequested(1000); events.onStopped(1500); events.stopRequested(7000);
        assertTrue(events.unexpectedStop());
        StringWriter report = new StringWriter(); events.writeSummary(report);
        assertTrue(report.toString().contains("onStopped at 500 ms; STOP already requested=false"));
    }
    @Test public void startedBooleanIsRecordedWithoutInventingItsMeaning() throws Exception {
        NativeRecorderEvents events = new NativeRecorderEvents();
        events.startRequested(100); events.onStarted(false, 110);
        assertFalse(events.unexpectedStop()); assertFalse(events.stopped());
        StringWriter report = new StringWriter(); events.writeSummary(report);
        assertTrue(report.toString().contains("onStarted argument=false at 10 ms"));
    }
    @Test public void callbackTimelineHasAFixedBound() throws Exception {
        NativeRecorderEvents events = new NativeRecorderEvents(); events.startRequested(0);
        for (int i = 0; i < 1000; i++) events.onStarted(true, i);
        StringWriter report = new StringWriter(); events.writeSummary(report);
        assertEquals(32, report.toString().split("Recorder event:").length - 1);
    }
    @Test public void newStartResetsAStaleStoppedState() {
        NativeRecorderEvents events = new NativeRecorderEvents();
        events.onStopped(100); events.startRequested(200);
        assertFalse(events.stopped()); assertFalse(events.unexpectedStop());
    }
}
