package com.casklabs.ghostbuffer.probe;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/** Bounded callback timeline. The boolean supplied by onStarted is logged without guessing its meaning. */
public final class NativeRecorderEvents {
    public boolean registered;
    private final List<String> events = new ArrayList<String>();
    private long startAt = -1, stopRequestedAt = -1;
    private boolean stopped, unexpectedStop;
    private long startedCallbackAt = -1;

    public synchronized void startRequested(long now) {
        startAt = now; stopRequestedAt = -1; stopped = unexpectedStop = false;
        startedCallbackAt = -1;
        add("START requested at 0 ms");
    }
    public synchronized void stopRequested(long now) {
        stopRequestedAt = now;
        add("STOP requested at " + elapsed(now) + " ms");
    }
    public synchronized void onStarted(boolean value, long now) {
        startedCallbackAt = now;
        add("onStarted argument=" + value + " at " + elapsed(now) + " ms; thread=" + Thread.currentThread().getName());
    }
    public synchronized void onStopped(long now) {
        stopped = true;
        if (startAt >= 0 && stopRequestedAt < 0) unexpectedStop = true;
        add("onStopped at " + elapsed(now) + " ms; STOP already requested=" + (stopRequestedAt >= 0)
            + "; thread=" + Thread.currentThread().getName());
    }
    public synchronized boolean unexpectedStop() { return unexpectedStop; }
    public synchronized boolean stopped() { return stopped; }
    public synchronized boolean hasStartCallback() { return startedCallbackAt >= 0; }
    public synchronized boolean readyForLiveRead(long now, long settleMs) {
        return registered && startedCallbackAt >= 0 && now - startedCallbackAt >= settleMs
            && !stopped && stopRequestedAt < 0;
    }
    public synchronized void writeSummary(Writer report) throws IOException {
        report.write("Record-state listener registered: " + registered + "\n");
        report.write("Native START request uptime ms: " + startAt + "\n");
        for (String event : events) report.write("Recorder event: " + event + "\n");
        report.write("Native stopped before planned STOP: " + unexpectedStop + "\n");
        report.flush();
    }
    private long elapsed(long now) { return startAt < 0 ? -1 : Math.max(0, now - startAt); }
    private void add(String event) { if (events.size() < 32) events.add(event); }
}
