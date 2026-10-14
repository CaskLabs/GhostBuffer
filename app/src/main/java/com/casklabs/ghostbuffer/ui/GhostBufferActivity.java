package com.casklabs.ghostbuffer.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import com.github.ma1co.pmcademo.app.BuildConfig;
import com.github.ma1co.pmcademo.app.MainActivity;
import com.github.ma1co.pmcademo.app.R;
import com.casklabs.ghostbuffer.buffer.FrameRingBuffer;
import com.casklabs.ghostbuffer.capture.CaptureController;
import com.casklabs.ghostbuffer.capture.CaptureManifest;
import com.casklabs.ghostbuffer.preview.FakePreviewBackend;
import com.casklabs.ghostbuffer.preview.PreviewBackend;
import com.casklabs.ghostbuffer.preview.SonyPreviewBackend;
import com.casklabs.ghostbuffer.probe.GhostProbeManager;
import com.casklabs.ghostbuffer.storage.GhostStorage;
import com.casklabs.ghostbuffer.time.GhostTime;
import com.casklabs.ghostbuffer.exposure.ExposureController;
import com.casklabs.ghostbuffer.exposure.SonyExposureBackend;
import com.casklabs.ghostbuffer.exposure.FakeExposureBackend;
import com.github.ma1co.pmcademo.app.BaseActivity;
import com.github.ma1co.pmcademo.app.Logger;
import com.sony.scalar.hardware.CameraEx;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Main camera live view activity running the rolling ring buffer.
 * Synchronizes with the SurfaceView lifecycle, avoids silent fallback on hardware,
 * serializes probes, provides explicit error recovery paths, and routes Menu key to the camera/gallery menu.
 */
public class GhostBufferActivity extends BaseActivity implements SurfaceHolder.Callback, CaptureController.StateListener {
    public static final String EXTRA_OPEN_EXPOSURE = "ghostbuffer.openExposure";
    private SurfaceHolder surfaceHolder;
    private CameraEx camera;
    private boolean hasSurface = false;
    private boolean isForeground = false;
    private boolean isSimulationMode = false;
    private volatile boolean isProbing = false;
    private boolean previewStarted = false;
    private boolean navigationPending = false;

    private PreviewBackend previewBackend;
    private FrameRingBuffer ringBuffer;
    private GhostStorage storage;
    private CaptureController controller;
    private GhostProbeManager probeManager;

    private TextView tvStatus;
    private TextView tvStats;
    private View centerMessageContainer;
    private TextView tvCenterMessage;
    private Handler uiHandler;
    private ExposureController exposure;
    private View exposurePanel;
    private TextView exposureHint;
    private final View[] exposureRows = new View[6];
    private final TextView[] exposureValues = new TextView[6];
    private int exposureSelected;
    private boolean exposureVisible;
    private boolean exposureBusy;
    private boolean openExposureOnStart;
    private boolean fnHeld;
    private long fnPressedAt;
    private volatile int exposureGeneration;
    private final ThreadPoolExecutor exposureWorker = new ThreadPoolExecutor(1, 1, 0L,
        TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghost_buffer);

        uiHandler = new Handler();

        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvStats = (TextView) findViewById(R.id.tv_stats);
        centerMessageContainer = findViewById(R.id.center_message_container);
        tvCenterMessage = (TextView) findViewById(R.id.tv_center_message);
        exposurePanel = findViewById(R.id.exposure_panel);
        exposureHint = (TextView) findViewById(R.id.tv_exposure_hint);
        int[] rows = { R.id.exposure_mode, R.id.exposure_shutter, R.id.exposure_aperture,
            R.id.exposure_iso, R.id.exposure_ev, R.id.exposure_wb };
        int[] values = { R.id.exposure_mode_value, R.id.exposure_shutter_value, R.id.exposure_aperture_value,
            R.id.exposure_iso_value, R.id.exposure_ev_value, R.id.exposure_wb_value };
        for (int i = 0; i < rows.length; i++) {
            final int selection = i;
            exposureRows[i] = findViewById(rows[i]);
            exposureValues[i] = (TextView) findViewById(values[i]);
            exposureRows[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (exposureBusy) return;
                    exposureSelected = selection;
                    renderExposure();
                }
            });
        }
        findViewById(R.id.exposure_less).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { changeExposure(-1, false); }
        });
        findViewById(R.id.exposure_more).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { changeExposure(1, false); }
        });
        findViewById(R.id.exposure_auto).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { changeExposure(0, true); }
        });
        findViewById(R.id.exposure_close).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { closeExposure(); }
        });
        openExposureOnStart = getIntent().getBooleanExtra(EXTRA_OPEN_EXPOSURE, false);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        storage = new GhostStorage();
        ringBuffer = new FrameRingBuffer();
        probeManager = new GhostProbeManager(storage);

        // Detect Sony CameraSequence hardware
        SonyPreviewBackend sonyBackend = new SonyPreviewBackend();
        if (sonyBackend.isAvailable()) {
            Logger.info("Sony CameraSequence hardware detected: using SonyPreviewBackend");
            previewBackend = sonyBackend;
            isSimulationMode = false;
        } else {
            Logger.info("Sony CameraSequence not found: using FakePreviewBackend in simulation mode");
            previewBackend = new FakePreviewBackend();
            isSimulationMode = true;
        }

        controller = new CaptureController(previewBackend, ringBuffer, storage, this);
        controller.setAppVersion(BuildConfig.VERSION_NAME);

        try {
            controller.setCameraModel(getDeviceInfo().getModel());
            controller.setCameraFirmware(getDeviceInfo().getFirmwareVersion());
        } catch (Throwable t) {
            controller.setCameraModel("ILCE-5000");
            controller.setCameraFirmware("1.00");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sony live view requires the low color-depth display mode.
        setColorDepth(false);
        isForeground = true;
        navigationPending = false;
        surfaceHolder.addCallback(this);

        if (!isSimulationMode) {
            try {
                camera = CameraEx.open(0, null);
            } catch (Throwable t) {
                Logger.error("CameraEx.open failed on hardware: " + t.getMessage());
                camera = null;
                showCenterMessage("Camera initialization failed:\n" + t.getMessage() + "\nCheck camera lens / mode", "#F28C82");
                tvStatus.setText("CAMERA ERROR");
                tvStatus.setTextColor(Color.parseColor("#F28C82"));
                return;
            }
        }
        try {
            exposure = new ExposureController(isSimulationMode ? new FakeExposureBackend() : new SonyExposureBackend(camera));
        } catch (RuntimeException e) {
            exposure = null;
            Logger.error("Exposure controls unavailable: " + e);
        } catch (LinkageError e) {
            exposure = null;
            Logger.error("Exposure API unavailable: " + e);
        }

        if (hasSurface) {
            startCameraPreviewAndController();
        }
    }

    @Override
    protected void onPause() {
        isForeground = false;
        exposureGeneration++;
        exposureBusy = false;
        fnHeld = false;
        exposureWorker.getQueue().clear();
        closeExposure();
        previewStarted = false;
        uiHandler.removeCallbacksAndMessages(null);

        if (controller != null) {
            controller.pause();
        }

        stopNormalPreview();
        if (camera != null) {
            try {
                camera.release();
            } catch (Throwable t) {
                Logger.error("Failed releasing CameraEx: " + t.getMessage());
            }
            camera = null;
        }
        surfaceHolder.removeCallback(this);
        super.onPause();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        hasSurface = true;
        this.surfaceHolder = holder;
        if (isForeground) {
            startCameraPreviewAndController();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hasSurface = false;
        previewStarted = false;
        if (controller != null) {
            controller.pause();
        }
        stopNormalPreview();
    }

    private void startCameraPreviewAndController() {
        if (!isForeground || !hasSurface || previewStarted) {
            return;
        }
        previewStarted = true;

        if (camera != null && surfaceHolder != null) {
            try {
                camera.getNormalCamera().setPreviewDisplay(surfaceHolder);
                camera.getNormalCamera().startPreview();
            } catch (IOException e) {
                Logger.error("Error setting preview display: " + e.getMessage());
            }
        }

        if (camera != null) {
            controller.start(camera);
        } else if (isSimulationMode) {
            controller.start(null);
        }
        if (openExposureOnStart && controller.getState() == CaptureController.State.BUFFERING) {
            openExposureOnStart = false;
            openExposure();
        }
    }

    private void stopNormalPreview() {
        if (camera != null) {
            try {
                camera.getNormalCamera().stopPreview();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected boolean onFocusKeyDown() {
        if (exposureBusy) return true;
        if (camera != null) {
            try {
                camera.getNormalCamera().autoFocus(null);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    @Override
    protected boolean onFocusKeyUp() {
        if (exposureBusy) return true;
        if (camera != null) {
            try {
                camera.getNormalCamera().cancelAutoFocus();
            } catch (Throwable ignored) {}
        }
        return true;
    }

    @Override
    protected boolean onShutterKeyDown() {
        if (exposureBusy) return true;
        if (exposureVisible) closeExposure();
        if (controller != null) {
            if (controller.getState() == CaptureController.State.ERROR) {
                // Recovery: retry save
                controller.retrySave();
                return true;
            }
            controller.onShutterKeyDown();
        }
        return true;
    }

    @Override
    protected boolean onShutterKeyUp() {
        if (controller != null) {
            return controller.onShutterKeyUp();
        }
        return true;
    }

    @Override
    protected boolean onPlayKeyDown() {
        return true;
    }

    @Override
    protected boolean onPlayKeyUp() {
        if (exposureBusy) return true;
        if (exposureVisible) { closeExposure(); return true; }
        if (!navigationPending && !isProbing && controller.getState() != CaptureController.State.SAVING) openGallery();
        return true;
    }

    @Override
    protected boolean onMenuKeyDown() { return true; }

    @Override
    protected boolean onMenuKeyUp() {
        if (exposureBusy) return true;
        if (exposureVisible) { closeExposure(); return true; }
        if (navigationPending || isProbing || controller.getState() == CaptureController.State.SAVING) return true;
        navigationPending = true;
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        return true;
    }

    @Override
    protected boolean onFnKeyDown() {
        if (!fnHeld) { fnPressedAt = GhostTime.uptimeMillis(); fnHeld = true; }
        return true;
    }

    @Override
    protected boolean onFnKeyUp() {
        if (!fnHeld) return true;
        fnHeld = false;
        if (exposureBusy) return true;
        if (exposureVisible) closeExposure();
        else if (!isProbing && controller.getState() == CaptureController.State.BUFFERING) {
            if (GhostTime.uptimeMillis() - fnPressedAt >= 800) runProbe(); else openExposure();
        }
        return true;
    }

    @Override
    protected boolean onDeleteKeyDown() {
        return true;
    }

    @Override
    protected boolean onDeleteKeyUp() {
        if (exposureBusy) return true;
        if (exposureVisible) { closeExposure(); return true; }
        if (controller != null && controller.getState() == CaptureController.State.ERROR) {
            controller.discardSnapshotAndResume();
            return true;
        }
        if (isProbing || controller.getState() == CaptureController.State.SAVING) return true;
        finish();
        return true;
    }

    private void openGallery() {
        navigationPending = true;
        Intent intent = new Intent(this, GhostGalleryActivity.class);
        intent.putExtra(GhostGalleryActivity.EXTRA_STORAGE_ROOT, storage.getBaseDir().getAbsolutePath());
        startActivity(intent);
    }

    private void runProbe() {
        if (isProbing) {
            return;
        }
        isProbing = true;
        showCenterMessage("Running serialized probe...", "#72DDD0");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final GhostProbeManager.ProbeResult result = controller.runProbe(probeManager);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            isProbing = false;
                            if (result.success) {
                                String resStr = result.imageWidth > 0 ? (" (" + result.imageWidth + "x" + result.imageHeight + ")") : "";
                                showCenterMessage("Probe PASSED: " + result.frameSize + " B" + resStr + "\n" + result.jpegFile.getName(), "#72DDD0");
                            } else {
                                showCenterMessage("Probe FAILED:\n" + result.message, "#F28C82");
                            }
                            uiHandler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (isForeground) {
                                        hideCenterMessage();
                                    }
                                }
                            }, 3500);
                        }
                    });
                } catch (final Throwable t) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            isProbing = false;
                            showCenterMessage("Probe error: " + t.getMessage(), "#F28C82");
                        }
                    });
                }
            }
        }, "GhostBuffer-ProbeWorker").start();
    }

    @Override
    public void onStateChanged(final CaptureController.State newState, final String message) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isForeground) return;
                String simPrefix = isSimulationMode ? "[SIM] " : "";
                switch (newState) {
                    case BUFFERING:
                        tvStatus.setText(simPrefix + getString(R.string.status_buffering));
                        tvStatus.setTextColor(Color.parseColor("#72DDD0"));
                        hideCenterMessage();
                        break;
                    case SAVING:
                        tvStatus.setText(simPrefix + getString(R.string.status_saving));
                        tvStatus.setTextColor(Color.parseColor("#E7B75A"));
                        showCenterMessage("Buffer frozen!\nSaving to SD card...", "#E7B75A");
                        break;
                    case REVIEW:
                        tvStatus.setText(simPrefix + getString(R.string.status_review));
                        tvStatus.setTextColor(Color.parseColor("#72DDD0"));
                        break;
                    case ERROR:
                        closeExposure();
                        tvStatus.setText(simPrefix + getString(R.string.status_error));
                        tvStatus.setTextColor(Color.parseColor("#F28C82"));
                        showCenterMessage((message != null ? message : "An error occurred") +
                            "\n\n[Shutter] Retry Save   [Delete] Discard", "#F28C82");
                        break;
                    case INITIALIZING:
                        tvStatus.setText(simPrefix + getString(R.string.status_initializing));
                        tvStatus.setTextColor(Color.parseColor("#72DDD0"));
                        break;
                    case PAUSED:
                        tvStatus.setText(simPrefix + getString(R.string.status_paused));
                        tvStatus.setTextColor(Color.parseColor("#AAAAAA"));
                        break;
                    case IDLE:
                    default:
                        tvStatus.setText("INACTIVE");
                        tvStatus.setTextColor(Color.parseColor("#888888"));
                        break;
                }
            }
        });
    }

    @Override
    public void onStatsUpdated(final float fps, final int frameCount, final long coveredDurationMs) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isForeground) return;
                float sec = coveredDurationMs / 1000.0f;
                tvStats.setText(String.format(Locale.US, "%.1f s | %d frames | %.1f FPS", sec, frameCount, fps));
            }
        });
    }

    @Override
    public void onSaveProgress(final int current, final int total) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                showCenterMessage(String.format(Locale.US, "Saving frame %d / %d ...", current, total), "#E7B75A");
            }
        });
    }

    @Override
    public void onSaveComplete(final File sessionDir, final CaptureManifest manifest) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isForeground) {
                    return;
                }

                showCenterMessage(
                    String.format(Locale.US, "Saved!\n%d frames in %s\n[Play] Gallery", manifest.getFrameCount(), sessionDir.getName()),
                    "#72DDD0"
                );

                // Auto-resume buffering after 2.5 seconds
                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isForeground && controller != null && controller.getState() == CaptureController.State.REVIEW) {
                            hideCenterMessage();
                            controller.resumeBuffering();
                        }
                    }
                }, 2500);
            }
        });
    }

    @Override
    public void onError(final Throwable error) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                showCenterMessage("Error:\n" + error.getMessage() + "\n\n[Shutter] Retry Save   [Delete] Discard", "#F28C82");
            }
        });
    }

    private void showCenterMessage(String text, String colorHex) {
        if (!isForeground) return;
        centerMessageContainer.setVisibility(View.VISIBLE);
        tvCenterMessage.setText(text);
        tvCenterMessage.setTextColor(Color.parseColor(colorHex));
    }

    private void hideCenterMessage() {
        centerMessageContainer.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (exposureBusy) return;
        if (exposureVisible) { closeExposure(); return; }
        if (isProbing || controller.getState() == CaptureController.State.SAVING) return;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        isForeground = false;
        exposureGeneration++;
        exposureWorker.shutdownNow();
        uiHandler.removeCallbacksAndMessages(null);
        if (controller != null) controller.stop();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openExposureOnStart = intent.getBooleanExtra(EXTRA_OPEN_EXPOSURE, false);
    }

    private void openExposure() {
        if (exposure == null) {
            showCenterMessage("Exposure controls are unavailable on this camera.", "#E7B75A");
            return;
        }
        exposureVisible = true;
        exposurePanel.setVisibility(View.VISIBLE);
        hideCenterMessage();
        changeExposure(0, false);
    }

    private void closeExposure() {
        exposureVisible = false;
        if (exposurePanel != null) exposurePanel.setVisibility(View.GONE);
    }

    private void changeExposure(final int direction, final boolean automatic) {
        if (exposure == null || exposureBusy || !isForeground) return;
        exposureBusy = true;
        exposureHint.setText("Reading camera settings...");
        final int request = ++exposureGeneration;
        final ExposureController target = exposure;
        final ExposureController.Field field = ExposureController.Field.values()[exposureSelected];
        exposureWorker.execute(new Runnable() {
            @Override public void run() {
                String message = null;
                try {
                    controller.runCameraAdjustment(new CaptureController.CameraAdjustment() {
                        @Override public void run() {
                            if (request != exposureGeneration) throw new IllegalStateException("Camera paused");
                            try {
                                if (automatic) target.automatic(field);
                                else if (direction != 0) target.change(field, direction);
                                else target.refresh();
                            } catch (RuntimeException e) {
                                // A mode change may succeed before a subsequent native step fails.
                                try { target.refresh(); } catch (RuntimeException ignored) {} catch (LinkageError ignored) {}
                                throw e;
                            }
                        }
                    }, automatic || direction != 0);
                } catch (RuntimeException e) {
                    message = e.getMessage();
                    Logger.error("Exposure adjustment failed: " + e);
                } catch (LinkageError e) {
                    message = "This control is not supported by the camera firmware";
                    Logger.error("Exposure API failed: " + e);
                }
                final String error = message;
                uiHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!isForeground || request != exposureGeneration) return;
                        exposureBusy = false;
                        renderExposure();
                        if (error != null) exposureHint.setText(error);
                        // Native shutter/aperture steps may finish asynchronously.
                        if (error == null && (automatic || direction != 0)) {
                            uiHandler.postDelayed(new Runnable() {
                                @Override public void run() {
                                    if (exposureVisible && !exposureBusy && request == exposureGeneration) changeExposure(0, false);
                                }
                            }, 250);
                        }
                    }
                });
            }
        });
    }

    private void renderExposure() {
        for (int i = 0; i < exposureRows.length; i++) {
            exposureRows[i].setSelected(i == exposureSelected);
            exposureValues[i].setText(exposure.value(ExposureController.Field.values()[i]));
            exposureValues[i].setTextColor(Color.parseColor(exposure.available(ExposureController.Field.values()[i])
                ? "#72DDD0" : "#74848E"));
        }
        String[] help = { "Center: Auto exposure + Auto ISO", "Center: Auto shutter",
            "Center: Auto aperture; manual lenses use the lens ring", "Center: Auto ISO",
            "Center: Neutral 0 EV", "Center: Auto white balance" };
        exposureHint.setText(help[exposureSelected]);
        exposurePanel.post(new Runnable() {
            @Override public void run() {
                if (!exposureVisible) return;
                View row = exposureRows[exposureSelected];
                Rect bounds = new Rect();
                row.getDrawingRect(bounds);
                row.requestRectangleOnScreen(bounds, true);
            }
        });
    }

    private boolean selectExposure(int direction) {
        if (exposureVisible && !exposureBusy) {
            exposureSelected = (exposureSelected + direction + exposureRows.length) % exposureRows.length;
            renderExposure();
        }
        return true;
    }

    @Override protected boolean onUpKeyDown() { return true; }
    @Override protected boolean onDownKeyDown() { return true; }
    @Override protected boolean onLeftKeyDown() { return true; }
    @Override protected boolean onRightKeyDown() { return true; }
    @Override protected boolean onEnterKeyDown() { return true; }
    @Override protected boolean onUpKeyUp() { return selectExposure(-1); }
    @Override protected boolean onDownKeyUp() { return selectExposure(1); }
    @Override protected boolean onLeftKeyUp() { if (exposureVisible) changeExposure(-1, false); return true; }
    @Override protected boolean onRightKeyUp() { if (exposureVisible) changeExposure(1, false); return true; }
    @Override protected boolean onEnterKeyUp() {
        if (exposureVisible) changeExposure(0, true);
        else if (!exposureBusy && !isProbing && controller.getState() == CaptureController.State.BUFFERING) openExposure();
        return true;
    }
}
