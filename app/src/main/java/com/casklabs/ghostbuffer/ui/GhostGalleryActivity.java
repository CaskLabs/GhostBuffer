package com.casklabs.ghostbuffer.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghost_gallery);

        storage = new GhostStorage();
        imageView = (ImageView) findViewById(R.id.gallery_image_view);
        tvSession = (TextView) findViewById(R.id.tv_gallery_session);
        tvFrameInfo = (TextView) findViewById(R.id.tv_gallery_frame_info);
        tvEmpty = (TextView) findViewById(R.id.tv_gallery_empty);

        loadSessions();
    }

    private void loadSessions() {
        sessions = CaptureSession.loadAllSessions(storage.getCapturesDir());
        if (sessions.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            imageView.setVisibility(View.GONE);
            tvSession.setText("No captures");
            tvFrameInfo.setText("");
        } else {
            tvEmpty.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            currentSessionIndex = 0;
            currentFrameIndex = 0;
            displayCurrentFrame();
        }
    }

    private void displayCurrentFrame() {
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        if (currentSessionIndex < 0) currentSessionIndex = 0;
        if (currentSessionIndex >= sessions.size()) currentSessionIndex = sessions.size() - 1;

        CaptureSession session = sessions.get(currentSessionIndex);
        int frameCount = session.getFrameCount();
        if (frameCount == 0) {
            tvSession.setText(session.getSessionId() + " (Empty)");
            tvFrameInfo.setText("");
            imageView.setImageDrawable(null);
            return;
        }

        if (currentFrameIndex < 0) currentFrameIndex = 0;
        if (currentFrameIndex >= frameCount) currentFrameIndex = frameCount - 1;

        File frameFile = session.getFrameFile(currentFrameIndex);
        if (frameFile != null && frameFile.exists()) {
            // Recycle prior bitmap to protect Dalvik heap
            if (currentBitmap != null && !currentBitmap.isRecycled()) {
                currentBitmap.recycle();
                currentBitmap = null;
            }

            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                currentBitmap = BitmapFactory.decodeFile(frameFile.getAbsolutePath(), opts);
                imageView.setImageBitmap(currentBitmap);
            } catch (Throwable t) {
                imageView.setImageDrawable(null);
            }
        }

        CaptureManifest manifest = session.getManifest();
        String sessionLabel = String.format(Locale.US, "Session %s (%d/%d)",
            session.getSessionId(), currentSessionIndex + 1, sessions.size());
        tvSession.setText(sessionLabel);

        if (manifest != null && currentFrameIndex < manifest.getFrames().size()) {
            CaptureManifest.FrameInfo info = manifest.getFrames().get(currentFrameIndex);
            float offsetSec = info.offsetFromTriggerMs / 1000.0f;
            String offsetSign = offsetSec >= 0 ? "+" : "";
            tvFrameInfo.setText(String.format(Locale.US, "Frame %d/%d (%s%.2f s)",
                currentFrameIndex + 1, frameCount, offsetSign, offsetSec));
        } else {
            tvFrameInfo.setText(String.format(Locale.US, "Frame %d/%d", currentFrameIndex + 1, frameCount));
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
        // Delete current session
        if (sessions != null && !sessions.isEmpty()) {
            CaptureSession session = sessions.get(currentSessionIndex);
            deleteRecursive(session.getDirectory());
            loadSessions();
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
    }
}
