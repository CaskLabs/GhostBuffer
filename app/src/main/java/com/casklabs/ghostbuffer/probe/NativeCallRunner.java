package com.casklabs.ghostbuffer.probe;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Waits on the coordinator, never on the native event loop. Running calls are not abandoned. */
public final class NativeCallRunner {
    private final Executor owner;
    private final VideoEncoderProbe.Cancellation cancellation;
    public NativeCallRunner(Executor owner, VideoEncoderProbe.Cancellation cancellation) {
        this.owner = owner; this.cancellation = cancellation;
    }
    public <T> T call(final Callable<T> operation, final boolean cancellable) throws Exception {
        FutureTask<T> task = new FutureTask<T>(new Callable<T>() {
            @Override public T call() throws Exception {
                if (cancellable && cancellation.isCancelled()) throw new InterruptedException("Test closed before native call");
                return operation.call();
            }
        });
        owner.execute(task);
        boolean interrupted = false;
        T result = null;
        Throwable failure = null;
        try {
            for (;;) {
                try { result = task.get(); break; }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException e) { failure = e.getCause(); break; }
            }
            if (failure instanceof Exception) throw (Exception) failure;
            if (failure instanceof Error) throw (Error) failure;
            if (failure != null) throw new Exception("Native operation failed", failure);
            if (interrupted && cancellable) throw new InterruptedException("Interrupted; native call finished before cleanup");
            return result;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
