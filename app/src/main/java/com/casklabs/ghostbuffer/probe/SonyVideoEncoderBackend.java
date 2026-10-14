package com.casklabs.ghostbuffer.probe;

import android.view.SurfaceHolder;
import android.os.Handler;
import com.sony.scalar.hardware.CameraEx;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

/** Reflective access keeps newer Sony recorder classes optional on older camera firmware. */
public final class SonyVideoEncoderBackend implements VideoEncoderProbe.Backend {
    private final SurfaceHolder surface;
    private final VideoEncoderProbe.Cancellation cancellation;
    private final Handler ui;
    private volatile String nativeError;
    private volatile boolean closed;
    private CameraEx camera;
    private Object recorder;
    private Object originalParameters;
    private boolean previewAttempted;
    private boolean parametersChanged;

    public SonyVideoEncoderBackend(SurfaceHolder surface, VideoEncoderProbe.Cancellation cancellation, Handler ui) {
        this.surface = surface; this.cancellation = cancellation; this.ui = ui;
    }

    @Override public VideoEncoderProbe.Capabilities inspect(Writer report) throws Exception {
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
        recorder = createRecorderOnUi(recorderType);
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

    @Override public VideoEncoderProbe.Profile configure(VideoEncoderProbe.Profile profile) throws Exception {
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

    @Override public void prepare() throws Exception { checkCancelled(); call("prepare"); }
    @Override public void start() throws Exception { checkCancelled(); call("start"); }
    @Override public void stop() throws Exception { if (recorder != null) call("stop"); }
    @Override public void checkError() throws Exception {
        if (nativeError != null) throw new java.io.IOException(nativeError);
    }

    @Override public void close() throws Exception {
        closed = true;
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

    private Object createRecorderOnUi(final Class<?> type) throws Exception {
        // Native recorder notifications must have a looper that remains responsive during the timed test.
        final Object[] value = { null };
        final Throwable[] failure = { null };
        final CountDownLatch created = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    if (closed || cancellation.isCancelled()) throw new InterruptedException("Test closed");
                    value[0] = type.newInstance();
                    if (closed || cancellation.isCancelled()) {
                        try { SonyVideoEncoderBackend.invoke(value[0], "release"); } finally { value[0] = null; }
                    }
                } catch (Throwable e) { failure[0] = e; }
                finally { created.countDown(); }
            }
        });
        while (!created.await(20, TimeUnit.MILLISECONDS)) checkCancelled();
        if (failure[0] != null) throw new Exception(VideoEncoderProbe.describe(failure[0]), failure[0]);
        return value[0];
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
