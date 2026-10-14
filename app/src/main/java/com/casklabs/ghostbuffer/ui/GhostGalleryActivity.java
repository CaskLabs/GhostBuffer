package com.casklabs.ghostbuffer.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import com.github.ma1co.pmcademo.app.R;
import com.casklabs.ghostbuffer.capture.CaptureManifest;
import com.casklabs.ghostbuffer.capture.CaptureSession;
import com.casklabs.ghostbuffer.storage.GhostStorage;
import com.github.ma1co.pmcademo.app.BaseActivity;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Gallery viewer for browsing captured GhostBuffer sessions and inspecting individual frames.
 * Offloads filesystem I/O to background threads, clears views before decode, and flags incomplete sessions.
 */
public class GhostGalleryActivity extends BaseActivity {
    private GhostStorage storage;
    private List<CaptureSession> sessions;
    private int currentSessionIndex = 0;
    private int currentFrameIndex = 0;

    private ImageView imageView;
    private TextView tvSession;
    private TextView tvFrameInfo;
    private TextView tvEmpty;
    private Bitmap currentBitmap;
    private Handler uiHandler;
    private boolean isLoading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghost_gallery);

        uiHandler = new Handler();
        storage = new GhostStorage();
        imageView = (ImageView) findViewById(R.id.gallery_image_view);
        tvSession = (TextView) findViewById(R.id.tv_gallery_session);
        tvFrameInfo = (TextView) findViewById(R.id.tv_gallery_frame_info);
        tvEmpty = (TextView) findViewById(R.id.tv_gallery_empty);

        loadSessionsAsync();
    }

    private void loadSessionsAsync() {
        if (isLoading) return;
        isLoading = true;
        tvSession.setText("Loading sessions...");
        tvFrameInfo.setText("");
        tvSession.setTextColor(Color.WHITE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<CaptureSession> loaded = CaptureSession.loadAllSessions(storage.getCapturesDir());
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        isLoading = false;
                        sessions = loaded;
                        if (sessions == null || sessions.isEmpty()) {
                            tvEmpty.setVisibility(View.VISIBLE);
                            imageView.setVisibility(View.GONE);
                            tvSession.setText("No captures found");
                            tvFrameInfo.setText("");
                        } else {
                            tvEmpty.setVisibility(View.GONE);
                            imageView.setVisibility(View.VISIBLE);
                            currentSessionIndex = 0;
                            currentFrameIndex = 0;
                            displayCurrentFrame();
                        }
                    }
                });
            }
        }, "GhostBuffer-GalleryLoader").start();
    }

    private void displayCurrentFrame() {
        // 1. Always recycle and clear image view first
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
        imageView.setImageDrawable(null);

        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        if (currentSessionIndex < 0) currentSessionIndex = 0;
        if (currentSessionIndex >= sessions.size()) currentSessionIndex = sessions.size() - 1;

        CaptureSession session = sessions.get(currentSessionIndex);
        int frameCount = session.getFrameCount();

        // Session title with incomplete badge
        String incompleteBadge = session.isComplete() ? "" : "[INCOMPLETE] ";
        String sessionLabel = String.format(Locale.US, "%sSession %s (%d/%d)",
            incompleteBadge, session.getSessionId(), currentSessionIndex + 1, sessions.size());
        tvSession.setText(sessionLabel);
        tvSession.setTextColor(session.isComplete() ? Color.WHITE : Color.parseColor("#FFAA00"));

        if (frameCount == 0) {
            tvFrameInfo.setText("No frames found in session folder");
            tvFrameInfo.setTextColor(Color.parseColor("#FF6666"));
            return;
        }

        if (currentFrameIndex < 0) currentFrameIndex = 0;
        if (currentFrameIndex >= frameCount) currentFrameIndex = frameCount - 1;

        File frameFile = session.getFrameFile(currentFrameIndex);
        boolean frameLoaded = false;
        if (frameFile != null && frameFile.exists() && frameFile.length() > 0) {
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                currentBitmap = BitmapFactory.decodeFile(frameFile.getAbsolutePath(), opts);
                if (currentBitmap != null) {
                    imageView.setImageBitmap(currentBitmap);
                    frameLoaded = true;
                }
            } catch (Throwable ignored) {}
        }

        CaptureManifest manifest = session.getManifest();
        if (frameLoaded) {
            if (manifest != null && currentFrameIndex < manifest.getFrames().size()) {
                CaptureManifest.FrameInfo info = manifest.getFrames().get(currentFrameIndex);
                float offsetSec = info.offsetFromTriggerMs / 1000.0f;
                String offsetSign = offsetSec >= 0 ? "+" : "";
                tvFrameInfo.setText(String.format(Locale.US, "Frame %d/%d (%s%.2f s | %d B)",
                    currentFrameIndex + 1, frameCount, offsetSign, offsetSec, info.sizeBytes));
            } else {
                tvFrameInfo.setText(String.format(Locale.US, "Frame %d/%d", currentFrameIndex + 1, frameCount));
            }
            tvFrameInfo.setTextColor(Color.parseColor("#CCCCCC"));
        } else {
            tvFrameInfo.setText(String.format(Locale.US, "Frame %d/%d [FILE MISSING OR DAMAGED]", currentFrameIndex + 1, frameCount));
            tvFrameInfo.setTextColor(Color.parseColor("#FF4444"));
        }
    }

    @Override
    protected boolean onLeftKeyUp() {
        if (sessions != null && !sessions.isEmpty()) {
            CaptureSession session = sessions.get(currentSessionIndex);
            if (currentFrameIndex > 0) {
                currentFrameIndex--;
            } else {
                currentFrameIndex = session.getFrameCount() - 1;
            }
            displayCurrentFrame();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onRightKeyUp() {
        if (sessions != null && !sessions.isEmpty()) {
            CaptureSession session = sessions.get(currentSessionIndex);
            if (currentFrameIndex < session.getFrameCount() - 1) {
                currentFrameIndex++;
            } else {
                currentFrameIndex = 0;
            }
            displayCurrentFrame();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onUpKeyUp() {
        if (sessions != null && sessions.size() > 1) {
            if (currentSessionIndex > 0) {
                currentSessionIndex--;
            } else {
                currentSessionIndex = sessions.size() - 1;
            }
            currentFrameIndex = 0;
            displayCurrentFrame();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onDownKeyUp() {
        if (sessions != null && sessions.size() > 1) {
            if (currentSessionIndex < sessions.size() - 1) {
                currentSessionIndex++;
            } else {
                currentSessionIndex = 0;
            }
            currentFrameIndex = 0;
            displayCurrentFrame();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onDeleteKeyUp() {
        // Delete current session in background
        if (sessions != null && !sessions.isEmpty() && !isLoading) {
            final CaptureSession session = sessions.get(currentSessionIndex);
            isLoading = true;
            tvSession.setText("Deleting session...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    deleteRecursive(session.getDirectory());
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            isLoading = false;
                            loadSessionsAsync();
                        }
                    });
                }
            }, "GhostBuffer-GalleryDeleter").start();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onMenuKeyUp() {
        finish();
        return true;
    }

    @Override
    protected boolean onPlayKeyUp() {
        finish();
        return true;
    }

    private void deleteRecursive(File fileOrDir) {
        if (fileOrDir == null || !fileOrDir.exists()) return;
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
        uiHandler.removeCallbacksAndMessages(null);
    }
}
