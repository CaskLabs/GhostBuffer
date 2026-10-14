package com.casklabs.ghostbuffer.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
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
 */
public class GhostBufferActivity extends BaseActivity implements SurfaceHolder.Callback, CaptureController.StateListener {
    private SurfaceHolder surfaceHolder;
    private CameraEx camera;

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

        // Select hardware backend if Sony CameraSequence is available, else fallback to Fake
        SonyPreviewBackend sonyBackend = new SonyPreviewBackend();
        if (sonyBackend.isAvailable()) {
            Logger.info("Sony CameraSequence hardware detected: using SonyPreviewBackend");
            previewBackend = sonyBackend;
        } else {
            Logger.info("Sony CameraSequence not found: using FakePreviewBackend");
            previewBackend = new FakePreviewBackend();
        }

        controller = new CaptureController(previewBackend, ringBuffer, storage, this);
        try {
            controller.setCameraModel(getDeviceInfo().getModel());
            controller.setAppVersion(getDeviceInfo().getFirmwareVersion());
        } catch (Throwable t) {
            controller.setCameraModel("ILCE-5000");
            controller.setAppVersion("1.0.0");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        surfaceHolder.addCallback(this);

        try {
            camera = CameraEx.open(0, null);
        } catch (Throwable t) {
            Logger.info("CameraEx.open() failed or running in emulator: " + t.getMessage());
            camera = null;
        }

        if (camera == null && !(previewBackend instanceof FakePreviewBackend)) {
            previewBackend = new FakePreviewBackend();
            controller = new CaptureController(previewBackend, ringBuffer, storage, this);
        }

        if (camera != null) {
            controller.start(camera);
        } else {
            // Emulated/test start
            controller.start(null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (controller != null) {
            controller.pause();
        }

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
        if (camera != null) {
            try {
                camera.getNormalCamera().setPreviewDisplay(holder);
                camera.getNormalCamera().startPreview();
            } catch (IOException e) {
                Logger.error("Error setting preview display: " + e.getMessage());
            }
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
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
        openGallery();
        return true;
    }

    @Override
    protected boolean onFnKeyDown() {
        runProbe();
        return true;
    }

    @Override
    protected boolean onDeleteKeyDown() {
        return true;
    }

    @Override
    protected boolean onDeleteKeyUp() {
        finish();
        return true;
    }

    private void openGallery() {
        Intent intent = new Intent(this, GhostGalleryActivity.class);
        startActivity(intent);
    }

    private void runProbe() {
        showCenterMessage("Führe Einzelprobe aus...", "#00FFFF");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String model = "ILCE-5000";
                String fw = "1.0.0";
                try {
                    model = getDeviceInfo().getModel();
                    fw = getDeviceInfo().getFirmwareVersion();
                } catch (Throwable ignored) {}

                final GhostProbeManager.ProbeResult result = probeManager.runSingleProbe(previewBackend, model, fw);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result.success) {
                            showCenterMessage("Probe OK: " + result.frameSize + " B\n" + result.jpegFile.getName(), "#00FF88");
                        } else {
                            showCenterMessage("Probe FEHLER:\n" + result.message, "#FF4444");
                        }
                        uiHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                hideCenterMessage();
                            }
                        }, 3000);
                    }
                });
            }
        }).start();
    }

    @Override
    public void onStateChanged(final CaptureController.State newState, final String message) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                switch (newState) {
                    case BUFFERING:
                        tvStatus.setText(R.string.status_buffering);
                        tvStatus.setTextColor(Color.parseColor("#00FFCC"));
                        hideCenterMessage();
                        break;
                    case SAVING:
                        tvStatus.setText(R.string.status_saving);
                        tvStatus.setTextColor(Color.parseColor("#FFFF00"));
                        showCenterMessage("Puffer eingefroren!\nSpeichere auf SD-Karte...", "#FFFF00");
                        break;
                    case REVIEW:
                        tvStatus.setText(R.string.status_review);
                        tvStatus.setTextColor(Color.parseColor("#00FF88"));
                        break;
                    case ERROR:
                        tvStatus.setText(R.string.status_error);
                        tvStatus.setTextColor(Color.parseColor("#FF4444"));
                        showCenterMessage(message != null ? message : "Fehler aufgetreten", "#FF4444");
                        break;
                    case INITIALIZING:
                        tvStatus.setText(R.string.status_initializing);
                        tvStatus.setTextColor(Color.parseColor("#00CCFF"));
                        break;
                    case PAUSED:
                        tvStatus.setText(R.string.status_paused);
                        tvStatus.setTextColor(Color.parseColor("#AAAAAA"));
                        break;
                    case IDLE:
                    default:
                        tvStatus.setText("INAKTIV");
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
                tvStats.setText(String.format(Locale.US, "%.1f s | %d Bilder | %.1f FPS", sec, frameCount, fps));
            }
        });
    }

    @Override
    public void onSaveProgress(final int current, final int total) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                showCenterMessage(String.format(Locale.US, "Speichere Bild %d / %d ...", current, total), "#FFFF00");
            }
        });
    }

    @Override
    public void onSaveComplete(final File sessionDir, final CaptureManifest manifest) {
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                showCenterMessage(
                    String.format(Locale.US, "Gespeichert!\n%d Bilder in %s\n[Play] Galerie", manifest.getFrameCount(), sessionDir.getName()),
                    "#00FF88"
                );

                // Auto-resume buffering after 2.5 seconds
                uiHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (controller != null && controller.getState() == CaptureController.State.REVIEW) {
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
                showCenterMessage("Fehler:\n" + error.getMessage(), "#FF4444");
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

    @Override
    protected void setColorDepth(boolean highQuality) {
        super.setColorDepth(false);
    }
}
