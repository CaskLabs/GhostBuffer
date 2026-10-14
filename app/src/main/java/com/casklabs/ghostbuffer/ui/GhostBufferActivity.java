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
 * serializes probes, provides explicit error recovery paths, and routes Menu key to diagnostics.
 */
public class GhostBufferActivity extends BaseActivity implements SurfaceHolder.Callback, CaptureController.StateListener {
    private SurfaceHolder surfaceHolder;
    private CameraEx camera;
    private boolean hasSurface = false;
    private boolean isForeground = false;
    private boolean isSimulationMode = false;
    private volatile boolean isProbing = false;

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
        isForeground = true;
        surfaceHolder.addCallback(this);

        if (!isSimulationMode) {
            try {
                camera = CameraEx.open(0, null);
            } catch (Throwable t) {
                Logger.error("CameraEx.open failed on hardware: " + t.getMessage());
                camera = null;
                showCenterMessage("Camera initialization failed:\n" + t.getMessage() + "\nCheck camera lens / mode", "#FF4444");
                tvStatus.setText("CAMERA ERROR");
                tvStatus.setTextColor(Color.parseColor("#FF4444"));
                return;
            }
        }

        if (hasSurface) {
            startCameraPreviewAndController();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isForeground = false;
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
        if (controller != null) {
            controller.pause();
        }
        stopNormalPreview();
    }

    private void startCameraPreviewAndController() {
        if (!isForeground || !hasSurface) {
            return;
        }

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
            return controller.onShutterKeyDown();
        }
        return false;
    }

    @Override
    protected boolean onShutterKeyUp() {
        if (controller != null) {
            return controller.onShutterKeyUp();
        }
        return false;
    }

    @Override
    protected boolean onPlayKeyDown() {
        openGallery();
        return true;
    }

    @Override
    protected boolean onMenuKeyDown() {
        // Open PMCADemo diagnostics menu
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        return true;
    }

    @Override
    protected boolean onFnKeyDown() {
        runProbe();
        return true;
    }

    @Override
    protected boolean onDeleteKeyDown() {
        if (controller != null && controller.getState() == CaptureController.State.ERROR) {
            // Recovery: discard failed snapshot and return to live buffer
            controller.discardSnapshotAndResume();
            return true;
        }
        return true;
    }

    @Override
    protected boolean onDeleteKeyUp() {
        if (controller != null && controller.getState() == CaptureController.State.ERROR) {
            return true;
        }
        finish();
        return true;
    }

    private void openGallery() {
        Intent intent = new Intent(this, GhostGalleryActivity.class);
        startActivity(intent);
    }

    private void runProbe() {
        if (isProbing) {
            return;
        }
        isProbing = true;
        showCenterMessage("Running serialized probe...", "#00FFFF");

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
                                showCenterMessage("Probe PASSED: " + result.frameSize + " B" + resStr + "\n" + result.jpegFile.getName(), "#00FF88");
                            } else {
                                showCenterMessage("Probe FAILED:\n" + result.message, "#FF4444");
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
                            showCenterMessage("Probe error: " + t.getMessage(), "#FF4444");
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
                String simPrefix = isSimulationMode ? "[SIM] " : "";
                switch (newState) {
                    case BUFFERING:
                        tvStatus.setText(simPrefix + getString(R.string.status_buffering));
                        tvStatus.setTextColor(Color.parseColor("#00FFCC"));
                        hideCenterMessage();
                        break;
                    case SAVING:
                        tvStatus.setText(simPrefix + getString(R.string.status_saving));
                        tvStatus.setTextColor(Color.parseColor("#FFFF00"));
                        showCenterMessage("Buffer frozen!\nSaving to SD card...", "#FFFF00");
                        break;
                    case REVIEW:
                        tvStatus.setText(simPrefix + getString(R.string.status_review));
                        tvStatus.setTextColor(Color.parseColor("#00FF88"));
                        break;
                    case ERROR:
                        tvStatus.setText(simPrefix + getString(R.string.status_error));
                        tvStatus.setTextColor(Color.parseColor("#FF4444"));
                        showCenterMessage((message != null ? message : "An error occurred") +
                            "\n\n[Shutter] Retry Save   [Delete] Discard", "#FF4444");
                        break;
                    case INITIALIZING:
                        tvStatus.setText(simPrefix + getString(R.string.status_initializing));
                        tvStatus.setTextColor(Color.parseColor("#00CCFF"));
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
                showCenterMessage(String.format(Locale.US, "Saving frame %d / %d ...", current, total), "#FFFF00");
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
                    "#00FF88"
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
                showCenterMessage("Error:\n" + error.getMessage() + "\n\n[Shutter] Retry Save   [Delete] Discard", "#FF4444");
            }
        });
    }

    private void showCenterMessage(String text, String colorHex) {
        centerMessageContainer.setVisibility(View.VISIBLE);
        tvCenterMessage.setText(text);
        tvCenterMessage.setTextColor(Color.parseColor(colorHex));
    }

    private void hideCenterMessage() {
        centerMessageContainer.setVisibility(View.GONE);
    }
}
