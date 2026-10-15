package com.casklabs.ghostbuffer.probe;

import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class NativeCallRunnerTest {
    private ExecutorService owner;
    private NativeCallRunner calls;
    private volatile boolean cancelled;
    private final VideoEncoderProbe.Cancellation cancellation = new VideoEncoderProbe.Cancellation() {
        @Override public boolean isCancelled() { return cancelled; }
    };
    @Before public void setup() {
        owner = Executors.newSingleThreadExecutor(); calls = new NativeCallRunner(owner, cancellation);
    }
    @After public void cleanup() throws Exception { owner.shutdownNow(); assertTrue(owner.awaitTermination(2, TimeUnit.SECONDS)); }
    @Test public void allNativeCallsUseSameOwnerAndNeverTheCoordinator() throws Exception {
        Callable<Thread> operation = new Callable<Thread>() { @Override public Thread call() { return Thread.currentThread(); } };
        Thread first = calls.call(operation, true), second = calls.call(operation, true);
        assertSame(first, second); assertNotSame(Thread.currentThread(), first);
    }
    @Test public void ownerCanDeliverCallbacksBetweenCoordinatedCalls() throws Exception {
        final CountDownLatch callback = new CountDownLatch(1);
        calls.call(new Callable<Void>() {
            @Override public Void call() {
                owner.execute(new Runnable() { @Override public void run() { callback.countDown(); } });
                return null;
            }
        }, true);
        assertTrue(callback.await(1, TimeUnit.SECONDS));
    }
    @Test public void cancellationBeforeExecutionPreventsNativeOpenButAllowsCleanup() throws Exception {
        final AtomicBoolean opened = new AtomicBoolean(), cleaned = new AtomicBoolean();
        NativeCallRunner queued = new NativeCallRunner(new Executor() {
            @Override public void execute(Runnable operation) { cancelled = true; owner.execute(operation); }
        }, cancellation);
        try {
            queued.call(new Callable<Void>() { @Override public Void call() { opened.set(true); return null; } }, true);
            fail("Expected cancellation");
        } catch (InterruptedException expected) {}
        calls.call(new Callable<Void>() { @Override public Void call() { cleaned.set(true); return null; } }, false);
        assertFalse(opened.get()); assertTrue(cleaned.get());
    }
    @Test public void interruptedCoordinatorDoesNotAbandonResourceCreationInProgress() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
        final AtomicBoolean resourceAssigned = new AtomicBoolean();
        final AtomicReference<Throwable> result = new AtomicReference<Throwable>();
        Thread coordinator = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    calls.call(new Callable<Void>() {
                        @Override public Void call() throws Exception {
                            entered.countDown(); release.await(); resourceAssigned.set(true); return null;
                        }
                    }, true);
                } catch (Throwable e) { result.set(e); }
                finally { completed.countDown(); }
            }
        });
        coordinator.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS)); coordinator.interrupt();
            assertFalse(completed.await(50, TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(resourceAssigned.get()); assertTrue(result.get() instanceof InterruptedException);
    }
    @Test public void nativeFailureIsPreservedWithoutExecutionExceptionWrapper() throws Exception {
        final IOException failure = new IOException("Native state error");
        try {
            calls.call(new Callable<Void>() { @Override public Void call() throws Exception { throw failure; } }, true);
            fail("Expected failure");
        } catch (IOException actual) { assertSame(failure, actual); }
    }
    @Test public void cleanupCompletesEvenWhenCoordinatorIsAlreadyInterrupted() throws Exception {
        Thread.currentThread().interrupt();
        try {
            assertTrue(calls.call(new Callable<Boolean>() { @Override public Boolean call() { return true; } }, false));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
