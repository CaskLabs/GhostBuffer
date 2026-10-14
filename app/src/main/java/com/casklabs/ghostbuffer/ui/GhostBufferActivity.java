package com.casklabs.ghostbuffer.ui;

import android.content.Intent;
import android.graphics.Color;
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
import com.github.ma1co.pmcademo.app.BaseActivity;
import com.github.ma1co.pmcademo.app.Logger;
import com.sony.scalar.hardware.CameraEx;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Main camera live view activity running the rolling ring buffer.
 * Synchronizes with the SurfaceView lifecycle, avoids silent fallback on hardware,
 * serializes probes, provides explicit error recovery paths, and routes Menu key to the camera/gallery menu.
 */
public class GhostBufferActivity extends BaseActivity implements SurfaceHolder.Callback, CaptureController.StateListener {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghost_buffer);

        uiHandler = new Handler();

        tvStatus = (TextView) findViewById(R.id.tv_status);
        tvStats = (TextView) findViewById(R.id.tv_stats);
        centerMessageContainer = findViewById(R.id.center_message_container);
        tvCenterMessage = (TextView) findViewById(R.id.tv_center_message);

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

        if (hasSurface) {
            startCameraPreviewAndController();
        }
    }

    @Override
    protected void onPause() {
        isForeground = false;
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
        if (camera != null) {
            try {
                camera.getNormalCamera().autoFocus(null);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    @Override
    protected boolean onFocusKeyUp() {
        if (camera != null) {
            try {
                camera.getNormalCamera().cancelAutoFocus();
            } catch (Throwable ignored) {}
        }
        return true;
    }

    @Override
    protected boolean onShutterKeyDown() {
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
        if (!navigationPending && !isProbing && controller.getState() != CaptureController.State.SAVING) openGallery();
        return true;
    }

    @Override
    protected boolean onMenuKeyDown() { return true; }

    @Override
    protected boolean onMenuKeyUp() {
        if (navigationPending || isProbing || controller.getState() == CaptureController.State.SAVING) return true;
        navigationPending = true;
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        return true;
    }

    @Override
    protected boolean onFnKeyDown() {
        return true;
    }

    @Override
    protected boolean onFnKeyUp() {
        if (controller.getState() == CaptureController.State.BUFFERING) runProbe();
        return true;
    }

    @Override
    protected boolean onDeleteKeyDown() {
        return true;
    }

    @Override
    protected boolean onDeleteKeyUp() {
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
        if (isProbing || controller.getState() == CaptureController.State.SAVING) return;
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        isForeground = false;
        uiHandler.removeCallbacksAndMessages(null);
        if (controller != null) controller.stop();
        super.onDestroy();
    }
}
