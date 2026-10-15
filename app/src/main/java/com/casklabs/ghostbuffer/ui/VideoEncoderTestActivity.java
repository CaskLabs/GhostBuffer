package com.casklabs.ghostbuffer.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;
import com.casklabs.ghostbuffer.probe.SonyVideoEncoderBackend;
import com.casklabs.ghostbuffer.probe.VideoEncoderProbe;
import com.casklabs.ghostbuffer.storage.GhostStorage;
import com.casklabs.ghostbuffer.time.GhostTime;
import com.github.ma1co.pmcademo.app.BaseActivity;
import com.github.ma1co.pmcademo.app.BuildConfig;
import com.github.ma1co.pmcademo.app.Logger;
import com.github.ma1co.pmcademo.app.R;
import com.sony.scalar.sysutil.ScalarInput;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/** Separate camera ownership: the parent live-view activity has already paused and released its camera. */
public final class VideoEncoderTestActivity extends BaseActivity implements SurfaceHolder.Callback {
    private final Object cameraGuard = new Object();
    private volatile boolean foreground, hasSurface, busy;
    private volatile int generation;
    private volatile String lastLifecycleEvent;
    private volatile long lastLifecycleAt;
    private boolean runPending;
    private boolean liveMode;
    private Handler ui, worker;
    private HandlerThread workerThread;
    private SurfaceHolder surface;
    private FrameLayout previewContainer;
    private SonyVideoEncoderBackend backend;
    private TextView status, details;
    private ScrollView scroll;
    private Button[] actions;
    private int selectedAction;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_encoder_test);
        ui = new Handler();
        status = (TextView) findViewById(R.id.encoder_test_status);
        details = (TextView) findViewById(R.id.encoder_test_details);
        scroll = (ScrollView) findViewById(R.id.encoder_test_scroll);
        actions = new Button[] { (Button) findViewById(R.id.encoder_test_run),
            (Button) findViewById(R.id.encoder_test_live), (Button) findViewById(R.id.encoder_test_back) };
        previewContainer = (FrameLayout) findViewById(R.id.encoder_test_preview_container);
        workerThread = new HandlerThread("GhostBuffer-EncoderCoordinator");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        findViewById(R.id.encoder_test_run).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { runTest(false); }
        });
        findViewById(R.id.encoder_test_live).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { runTest(true); }
        });
        findViewById(R.id.encoder_test_back).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        View.OnFocusChangeListener actionFocus = new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean focused) {
                if (!focused) return;
                for (int i = 0; i < actions.length; i++) if (actions[i] == view) selectedAction = i;
                for (int i = 0; i < actions.length; i++) actions[i].setSelected(i == selectedAction);
            }
        };
        for (Button action : actions) action.setOnFocusChangeListener(actionFocus);
        selectAction(0);
    }

    @Override protected void onResume() {
        super.onResume();
        lastLifecycleEvent = "onResume";
        lastLifecycleAt = GhostTime.uptimeMillis();
        setColorDepth(false);
        foreground = true;
        updateActionButtons();
    }

    private void cancelAndRelease() {
        boolean wasBusy = busy;
        generation++;
        runPending = false;
        worker.removeCallbacksAndMessages(null);
        synchronized (cameraGuard) {
            if (backend != null) {
                try { backend.close(); } catch (Throwable e) { Logger.error("Encoder cleanup: " + e); }
                backend = null;
            }
        }
        busy = false;
        updateActionButtons();
        if (wasBusy) status.setText(R.string.encoder_test_interrupted);
    }

    @Override protected void onPause() {
        lastLifecycleEvent = "onPause";
        lastLifecycleAt = GhostTime.uptimeMillis();
        foreground = false;
        cancelAndRelease(); // Native resources must close before BaseActivity releases the display.
        super.onPause();
    }
    @Override protected void onDestroy() {
        if (surface != null) surface.removeCallback(this);
        ui.removeCallbacksAndMessages(null);
        workerThread.quit();
        super.onDestroy();
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (holder != surface) return;
        hasSurface = true;
        if (foreground && runPending) startTestWithSurface();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (holder != surface) return;
        lastLifecycleEvent = "surfaceDestroyed";
        lastLifecycleAt = GhostTime.uptimeMillis();
        hasSurface = false; cancelAndRelease();
    }

    private void runTest(boolean live) {
        if (!foreground || busy) return;
        liveMode = live;
        busy = true;
        updateActionButtons();
        runPending = true;
        status.setText("Preparing fresh preview surface...");
        // Camera.release() alone does not recreate the SurfaceView's native buffer queue.
        // A completed test must not reuse its preview target for a new camera connection.
        hasSurface = false;
        if (surface != null) surface.removeCallback(this);
        previewContainer.removeAllViews();
        SurfaceView preview = new SurfaceView(this);
        surface = preview.getHolder();
        surface.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        surface.addCallback(this);
        previewContainer.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    }

    private void startTestWithSurface() {
        runPending = false;
        final boolean live = liveMode;
        status.setText(live ? "Testing Full-HD live access..." : "Testing 6-second baseline...");
        details.setText("Checking recorder APIs and reported Full-HD profiles.\n\n"
            + (live ? "Waits for recorder start, then probes predicted movie files.\nSD-to-RAM access; no live directory scans.\n"
                : "6-second recording without live file reads.\nAfter completion, select Live-Test for the comparison.\n")
            + "Both modes log recorder state; stream-write listener disabled.\n"
            + "Report: GHOSTBUF/PROBE\n\nAbbrechen / MENU returns and cancels.");
        final int request = ++generation;
        final String device = deviceDetails();
        final VideoEncoderProbe.Cancellation cancel = new VideoEncoderProbe.DiagnosticCancellation() {
            @Override public boolean isCancelled() { return !foreground || !hasSurface || request != generation; }
            @Override public String describeState() {
                return "foreground=" + foreground + ", surface=" + hasSurface + ", generation=" + generation
                    + ", request=" + request + ", lastLifecycleEvent=" + lastLifecycleEvent
                    + ", lifecycleUptimeMs=" + lastLifecycleAt;
            }
        };
        worker.post(new Runnable() {
            @Override public void run() {
                final VideoEncoderProbe.Result result;
                final String report;
                synchronized (cameraGuard) {
                    if (cancel.isCancelled()) return;
                    backend = new SonyVideoEncoderBackend(surface, cancel);
                    result = new VideoEncoderProbe(new GhostStorage(), 6000L, live).run(backend, cancel, device);
                    backend = null; // The probe closes its resources even after a failed native call.
                    report = readReport(result);
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (cancel.isCancelled()) return;
                        busy = false;
                        updateActionButtons();
                        selectAction(live ? 1 : 0);
                        status.setText(result.status);
                        details.setText(result.message + "\n\n" + (result.reportFile == null ? "No report saved."
                            : "Report: " + result.reportFile.getAbsolutePath()) + "\n\n" + report);
                        scroll.scrollTo(0, 0);
                    }
                });
            }
        });
    }

    private void updateActionButtons() {
        if (actions == null) return;
        actions[0].setEnabled(foreground && !busy);
        actions[1].setEnabled(foreground && !busy);
        actions[2].setText(busy ? R.string.encoder_test_cancel : R.string.encoder_test_back);
        if (!actions[selectedAction].isEnabled()) selectAction(2);
    }

    private void selectAction(int index) {
        selectedAction = index;
        for (int i = 0; i < actions.length; i++) actions[i].setSelected(i == index);
        actions[index].requestFocus();
    }

    private void moveAction(int direction) {
        for (int offset = 1; offset <= actions.length; offset++) {
            int index = (selectedAction + direction * offset + actions.length) % actions.length;
            if (actions[index].isEnabled()) { selectAction(index); return; }
        }
    }

    private void activateAction() {
        if (actions[selectedAction].isEnabled()) actions[selectedAction].performClick();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (actions == null) return super.dispatchKeyEvent(event);
        int scan = event.getScanCode(), key = event.getKeyCode();
        boolean left = scan == ScalarInput.ISV_KEY_LEFT || key == KeyEvent.KEYCODE_DPAD_LEFT;
        boolean right = scan == ScalarInput.ISV_KEY_RIGHT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean center = scan == ScalarInput.ISV_KEY_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        boolean up = scan == ScalarInput.ISV_KEY_UP || key == KeyEvent.KEYCODE_DPAD_UP;
        boolean down = scan == ScalarInput.ISV_KEY_DOWN || key == KeyEvent.KEYCODE_DPAD_DOWN;
        if (!(left || right || center || up || down)) return super.dispatchKeyEvent(event);
        // Handle navigation once, before Button's default key dispatch can move focus or click too.
        if (event.getAction() == KeyEvent.ACTION_DOWN && center) actions[selectedAction].setPressed(true);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            for (Button action : actions) action.setPressed(false);
            if (!event.isCanceled()) {
                if (left) moveAction(-1);
                else if (right) moveAction(1);
                else if (center) activateAction();
                else scroll.smoothScrollBy(0, up ? -80 : 80);
            }
        }
        return true;
    }

    private String deviceDetails() {
        try {
            return "App: " + BuildConfig.VERSION_NAME + "\nCamera: " + getDeviceInfo().getModel()
                + "\nFirmware: " + getDeviceInfo().getFirmwareVersion() + "\nScalar API: " + getDeviceInfo().getApiVersion()
                + "\nAndroid SDK: " + getDeviceInfo().getAndroidSdkVersion();
        } catch (Throwable e) { return "App: " + BuildConfig.VERSION_NAME + "\nDevice details: " + e; }
    }
    private String readReport(VideoEncoderProbe.Result result) {
        if (result.reportFile == null) return "";
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(new FileInputStream(result.reportFile), "UTF-8"));
            try {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = input.readLine()) != null && text.length() < 32768) text.append(line).append('\n');
                return text.toString();
            } finally { input.close(); }
        } catch (Exception e) { return "Cannot display report: " + e.getMessage(); }
    }

    @Override protected boolean onEnterKeyDown() { return true; }
    @Override protected boolean onEnterKeyUp() { activateAction(); return true; }
    @Override protected boolean onRightKeyDown() { return true; }
    @Override protected boolean onRightKeyUp() { moveAction(1); return true; }
    @Override protected boolean onLeftKeyDown() { return true; }
    @Override protected boolean onLeftKeyUp() { moveAction(-1); return true; }
    @Override protected boolean onMenuKeyDown() { return true; }
    @Override protected boolean onMenuKeyUp() { finish(); return true; }
    @Override protected boolean onDeleteKeyDown() { return true; }
    @Override protected boolean onDeleteKeyUp() { finish(); return true; }
    @Override protected boolean onPlayKeyDown() { return true; }
    @Override protected boolean onPlayKeyUp() { finish(); return true; }
    @Override protected boolean onFocusKeyDown() { return true; }
    @Override protected boolean onFocusKeyUp() { return true; }
    @Override protected boolean onShutterKeyDown() { return true; }
    @Override protected boolean onShutterKeyUp() { return true; }
    @Override protected boolean onMovieKeyDown() { return true; }
    @Override protected boolean onMovieKeyUp() { return true; }
    @Override protected boolean onFnKeyDown() { return true; }
    @Override protected boolean onFnKeyUp() { return true; }
    @Override protected boolean onUpKeyDown() { return true; }
    @Override protected boolean onDownKeyDown() { return true; }
    @Override protected boolean onUpKeyUp() { scroll.smoothScrollBy(0, -80); return true; }
    @Override protected boolean onDownKeyUp() { scroll.smoothScrollBy(0, 80); return true; }
}
