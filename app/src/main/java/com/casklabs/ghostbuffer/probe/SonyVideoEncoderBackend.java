package com.casklabs.ghostbuffer.probe;

import android.view.SurfaceHolder;
import android.os.Handler;
import android.os.HandlerThread;
import com.sony.scalar.hardware.CameraEx;
import com.casklabs.ghostbuffer.time.GhostTime;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.List;

/** Reflective access keeps newer Sony recorder classes optional on older camera firmware. */
public final class SonyVideoEncoderBackend implements VideoEncoderProbe.Backend {
    private final SurfaceHolder surface;
    private final VideoEncoderProbe.Cancellation cancellation;
    private final HandlerThread nativeThread;
    private final NativeCallRunner nativeCalls;
    private volatile String nativeError;
    private volatile boolean closed;
    private CameraEx camera;
    private Object recorder;
    private Object originalParameters;
    private boolean previewAttempted;
    private boolean parametersChanged;
    private NativeRecorderEvents recordEvents;

    public SonyVideoEncoderBackend(SurfaceHolder surface, VideoEncoderProbe.Cancellation cancellation) {
        this.surface = surface; this.cancellation = cancellation;
        nativeThread = new HandlerThread("GhostBuffer-EncoderNative");
        nativeThread.start();
        final Handler owner = new Handler(nativeThread.getLooper());
        nativeCalls = new NativeCallRunner(new Executor() {
            @Override public void execute(Runnable operation) {
                if (!owner.post(operation)) throw new RejectedExecutionException("Native event thread closed");
            }
        }, cancellation);
    }

    @Override public VideoEncoderProbe.Capabilities inspect(final Writer report) throws Exception {
        return nativeCalls.call(new Callable<VideoEncoderProbe.Capabilities>() {
            @Override public VideoEncoderProbe.Capabilities call() throws Exception { return inspectOnNative(report); }
        }, true);
    }

    private VideoEncoderProbe.Capabilities inspectOnNative(Writer report) throws Exception {
        note(report, "Native camera/recorder owner thread: " + Thread.currentThread().getName());
        VideoEncoderProbe.Capabilities caps = new VideoEncoderProbe.Capabilities();
        Class<?> recorderType;
        try { recorderType = Class.forName("com.sony.scalar.media.MediaRecorder"); }
        catch (ClassNotFoundException e) { note(report, "MediaRecorder: class missing"); return caps; }
        catch (LinkageError e) { note(report, "MediaRecorder linkage error: " + e); return caps; }
        caps.recorderAvailable = true;
        for (Method method : recorderType.getMethods()) {
            if (method.getDeclaringClass() == recorderType) note(report, "Recorder method: " + method.toString());
        }
        try {
            Class<?> streamType = Class.forName("com.sony.scalar.media.MediaRecorder$StreamBuffer");
            recorderType.getMethod("setStreamBuffer", streamType);
            Class.forName("com.sony.scalar.media.MediaRecorder$StreamBuffer$StreamMemoryArea");
            caps.streamBufferAvailable = true;
            note(report, "StreamBuffer: class, memory-area class and setter found");
        } catch (Exception e) { note(report, "StreamBuffer: " + VideoEncoderProbe.describe(e)); }
        catch (LinkageError e) { note(report, "StreamBuffer: " + VideoEncoderProbe.describe(e)); }
        checkCancelled();
        recorder = recorderType.newInstance();
        note(report, "Recorder constructor returned successfully");
        final Class<?> errorListener = Class.forName("com.sony.scalar.media.MediaRecorder$OnErrorListener");
        Object callback = Proxy.newProxyInstance(errorListener.getClassLoader(), new Class<?>[] { errorListener }, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                if ("onError".equals(method.getName())) nativeError = "Native recorder error: " + java.util.Arrays.toString(args);
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                if ("toString".equals(method.getName())) return "GhostBuffer encoder error listener";
                return null;
            }
        });
        call("setOnErrorListener", new Class<?>[] { errorListener }, callback);
        // Keep the V4 stream-write listener out of both comparison modes: it never supplies bytes.
        note(report, "Stream-write event listener: DISABLED for baseline/live comparison");
        registerRecordEvents(caps, report);
        checkCancelled();
        CameraEx.OpenOptions options = new CameraEx.OpenOptions();
        options.setInheritSetting(true);
        options.setPreview(true);
        // Recording mode is V3; movie-mode selection was added in V5 and is optional here.
        options.getClass().getMethod("setRecordingMode", int.class).invoke(options, 1);
        try { options.getClass().getMethod("setMovieMode", int.class).invoke(options, 0); }
        catch (NoSuchMethodException ignored) { note(report, "Single movie mode: firmware default"); }
        checkCancelled();
        note(report, "Opening camera in native movie recording mode");
        camera = CameraEx.open(0, options);
        checkCancelled();
        note(report, "BEGIN: attach fresh movie preview surface");
        camera.getNormalCamera().setPreviewDisplay(surface);
        note(report, "BEGIN: start movie preview");
        previewAttempted = true;
        camera.getNormalCamera().startPreview();
        note(report, "Movie preview started successfully");
        call("setCamera", new Class<?>[] { CameraEx.class }, camera);
        call("setVideoSource", new Class<?>[] { int.class }, 1);
        Object current = call("getParameters");
        note(report, "Original parameters: " + invoke(current, "flatten"));
        originalParameters = current.getClass().newInstance();
        invoke(originalParameters, "unflatten", new Class<?>[] { String.class }, invoke(current, "flatten"));
        Object supported = call("getSupportedParameters");
        note(report, "Supported parameters: " + invoke(supported, "flatten"));
        Object profiles = invoke(supported, "getSupportedCamcoderProfiles");
        if (!(profiles instanceof List)) throw new IllegalStateException("Firmware did not return a profile list");
        for (Object profile : (List<?>) profiles) {
            caps.profiles.add(new VideoEncoderProbe.Profile(field(profile, "outputFormat"), field(profile, "videoSize"),
                field(profile, "videoFrameRate"), field(profile, "videoEncodingBitRate"), field(profile, "videoAspectRatio")));
        }
        return caps;
    }

    private void registerRecordEvents(final VideoEncoderProbe.Capabilities caps, Writer report) throws java.io.IOException {
        recordEvents = caps.recordEvents;
        try {
            Class<?> listener = Class.forName("com.sony.scalar.media.MediaRecorder$OnRecordListener");
            for (Method method : listener.getMethods()) note(report, "Record-state listener method: " + method.toString());
            Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] { listener }, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onStarted".equals(method.getName()) && args != null && args.length > 0)
                        caps.recordEvents.onStarted(Boolean.TRUE.equals(args[0]), GhostTime.uptimeMillis());
                    if ("onStopped".equals(method.getName())) caps.recordEvents.onStopped(GhostTime.uptimeMillis());
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                    if ("toString".equals(method.getName())) return "GhostBuffer record-state listener";
                    return null;
                }
            });
            call("setOnRecordListener", new Class<?>[] { listener }, callback);
            caps.recordEvents.registered = true;
            note(report, "Record-state event listener registered");
        } catch (Exception e) { note(report, "Record-state listener unavailable: " + VideoEncoderProbe.describe(e)); }
        catch (LinkageError e) { note(report, "Record-state listener unavailable: " + VideoEncoderProbe.describe(e)); }
    }

    @Override public VideoEncoderProbe.Profile configure(final VideoEncoderProbe.Profile profile) throws Exception {
        return nativeCalls.call(new Callable<VideoEncoderProbe.Profile>() {
            @Override public VideoEncoderProbe.Profile call() throws Exception { return configureOnNative(profile); }
        }, true);
    }

    private VideoEncoderProbe.Profile configureOnNative(VideoEncoderProbe.Profile profile) throws Exception {
        checkCancelled();
        Object parameters = call("getParameters");
        String[] setters = { "setOutputFormat", "setVideoSize", "setVideoFrameRate", "setVideoEncodingBitRate", "setVideoAspectRatio" };
        String[] values = { profile.format, profile.size, profile.rate, profile.bitrate, profile.aspect };
        for (int i = 0; i < setters.length; i++) invoke(parameters, setters[i], new Class<?>[] { String.class }, values[i]);
        parametersChanged = true;
        call("setParameters", new Class<?>[] { parameters.getClass() }, parameters);
        Object active = call("getParameters");
        return new VideoEncoderProbe.Profile((String) invoke(active, "getOutputFormat"), (String) invoke(active, "getVideoSize"),
            (String) invoke(active, "getVideoFrameRate"), (String) invoke(active, "getVideoEncodingBitRate"),
            (String) invoke(active, "getVideoAspectRatio"));
    }

    @Override public void prepare() throws Exception {
        nativeCalls.call(new Callable<Void>() {
            @Override public Void call() throws Exception { checkCancelled(); SonyVideoEncoderBackend.this.call("prepare"); return null; }
        }, true);
    }
    @Override public void start() throws Exception {
        nativeCalls.call(new Callable<Void>() {
            @Override public Void call() throws Exception {
                checkCancelled();
                if (recordEvents != null) recordEvents.startRequested(GhostTime.uptimeMillis());
                SonyVideoEncoderBackend.this.call("start"); return null;
            }
        }, true);
    }
    @Override public void stop() throws Exception {
        nativeCalls.call(new Callable<Void>() {
            @Override public Void call() throws Exception {
                if (recorder != null && (recordEvents == null || !recordEvents.stopped())) {
                    if (recordEvents != null) recordEvents.stopRequested(GhostTime.uptimeMillis());
                    SonyVideoEncoderBackend.this.call("stop");
                }
                return null;
            }
        }, false);
    }
    @Override public void checkError() throws Exception {
        if (nativeError != null) throw new java.io.IOException(nativeError);
        if (recordEvents != null && recordEvents.unexpectedStop())
            throw new java.io.IOException("Native onStopped arrived before planned STOP; recording ended early");
    }

    @Override public void close() throws Exception {
        if (closed) return;
        closed = true;
        try {
            nativeCalls.call(new Callable<Void>() {
                @Override public Void call() throws Exception { closeOnNative(); return null; }
            }, false);
        } finally { nativeThread.quit(); }
    }

    private void closeOnNative() throws Exception {
        Exception failure = null;
        if (recorder != null) {
            try {
                if (parametersChanged && originalParameters != null) call("setParameters", new Class<?>[] { originalParameters.getClass() }, originalParameters);
            } catch (Throwable e) { failure = new Exception(VideoEncoderProbe.describe(e), e); }
            try { call("release"); } catch (Throwable e) { if (failure == null) failure = new Exception(VideoEncoderProbe.describe(e), e); }
            recorder = null; originalParameters = null; parametersChanged = false;
        }
        if (camera != null) {
            try { if (previewAttempted) camera.getNormalCamera().stopPreview(); }
            catch (Throwable e) { if (failure == null) failure = new Exception(VideoEncoderProbe.describe(e), e); }
            try { camera.getNormalCamera().setPreviewDisplay(null); }
            catch (Throwable e) { if (failure == null) failure = new Exception(VideoEncoderProbe.describe(e), e); }
            try { camera.release(); } catch (Throwable e) { if (failure == null) failure = new Exception(VideoEncoderProbe.describe(e), e); }
            camera = null; previewAttempted = false;
        }
        if (failure != null) throw failure;
    }

    private void checkCancelled() throws InterruptedException {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Camera view closed");
    }
    private Object call(String name) throws Exception { return invoke(recorder, name); }
    private Object call(String name, Class<?>[] types, Object... args) throws Exception { return invoke(recorder, name, types, args); }
    private static Object invoke(Object target, String name) throws Exception { return invoke(target, name, new Class<?>[0]); }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    private static String field(Object target, String name) throws Exception {
        return (String) target.getClass().getField(name).get(target);
    }
    private static void note(Writer report, String text) throws java.io.IOException { report.write(text + "\n"); report.flush(); }
}
