package com.casklabs.ghostbuffer.ui;

import android.content.Intent;
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
import com.github.ma1co.pmcademo.app.Logger;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Gallery with serialized disk access, bounded image decoding and lifecycle-safe callbacks. */
public class GhostGalleryActivity extends BaseActivity {
    public static final String EXTRA_STORAGE_ROOT = "ghostbuffer.storageRoot";
    private GhostStorage storage;
    private List<CaptureSession> sessions;
    private int currentSessionIndex;
    private int currentFrameIndex;
    private ImageView imageView;
    private TextView tvSession;
    private TextView tvFrameInfo;
    private TextView tvEmpty;
    private Bitmap currentBitmap;
    private Handler uiHandler;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
        TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
    private boolean active;
    private boolean isLoading;
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghost_gallery);
        uiHandler = new Handler();
        String root = getIntent().getStringExtra(EXTRA_STORAGE_ROOT);
        storage = root == null ? new GhostStorage() : new GhostStorage(new File(root));
        imageView = (ImageView) findViewById(R.id.gallery_image_view);
        tvSession = (TextView) findViewById(R.id.tv_gallery_session);
        tvFrameInfo = (TextView) findViewById(R.id.tv_gallery_frame_info);
        tvEmpty = (TextView) findViewById(R.id.tv_gallery_empty);
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        loadSessionsAsync();
    }

    @Override
    protected void onPause() {
        active = false;
        generation++;
        isLoading = false;
        worker.getQueue().clear();
        releaseBitmap();
        super.onPause();
    }

    private void releaseBitmap() {
        // Detach first: ImageView must never draw an already recycled bitmap.
        imageView.setImageDrawable(null);
        if (currentBitmap != null && !currentBitmap.isRecycled()) currentBitmap.recycle();
        currentBitmap = null;
    }

    private void loadSessionsAsync() {
        if (!active) return;
        isLoading = true;
        final int request = ++generation;
        tvSession.setText("Loading captures...");
        tvFrameInfo.setText("");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<CaptureSession> loaded = CaptureSession.loadAllSessions(storage.getCapturesDir());
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!active || request != generation) return;
                            isLoading = false;
                            sessions = loaded;
                            if (sessions.isEmpty()) {
                                releaseBitmap();
                                tvEmpty.setText(R.string.gallery_empty);
                                tvEmpty.setVisibility(View.VISIBLE);
                                tvSession.setText("No captures yet");
                                tvFrameInfo.setText("");
                            } else {
                                tvEmpty.setVisibility(View.GONE);
                                currentSessionIndex = Math.min(currentSessionIndex, sessions.size() - 1);
                                currentFrameIndex = 0;
                                displayCurrentFrame();
                            }
                        }
                    });
                } catch (final RuntimeException e) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!active || request != generation) return;
                            isLoading = false;
                            tvSession.setText("Unable to read SD card");
                            tvEmpty.setText("Check the card, then reopen the gallery.\nPlay or Menu returns to the camera.");
                            tvEmpty.setVisibility(View.VISIBLE);
                            Logger.error("Gallery load failed: " + e);
                        }
                    });
                }
            }
        });
    }

    private void displayCurrentFrame() {
        if (!active || isLoading || sessions == null || sessions.isEmpty()) return;
        final CaptureSession session = sessions.get(currentSessionIndex);
        final int count = session.getFrameCount();
        tvSession.setText(String.format(Locale.US, "%sCapture %d / %d",
            session.isComplete() ? "" : "INCOMPLETE · ", currentSessionIndex + 1, sessions.size()));
        tvSession.setTextColor(Color.parseColor(session.isComplete() ? "#F2F5F7" : "#E7B75A"));
        if (count == 0) {
            releaseBitmap();
            tvFrameInfo.setText("No frames in this capture");
            return;
        }
        currentFrameIndex = Math.max(0, Math.min(currentFrameIndex, count - 1));
        final int index = currentFrameIndex;
        final File file = session.getFrameFile(index);
        final int request = ++generation;
        final int maxWidth = imageView.getWidth() > 0 ? imageView.getWidth() : 640;
        final int maxHeight = imageView.getHeight() > 0 ? imageView.getHeight() : 480;
        tvFrameInfo.setText(String.format(Locale.US, "Frame %d / %d · Loading", index + 1, count));
        // Retain only the latest queued frame when the user scrolls quickly.
        worker.getQueue().clear();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap decoded = null;
                try {
                    if (file != null && file.isFile() && file.length() > 0) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            options.inSampleSize = GalleryImageSize.sampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight);
                            options.inJustDecodeBounds = false;
                            options.inPreferredConfig = Bitmap.Config.RGB_565;
                            decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                        }
                    }
                } catch (RuntimeException e) {
                    Logger.error("Gallery decode failed: " + e);
                } catch (OutOfMemoryError e) {
                    Logger.error("Gallery image exceeds available memory");
                }
                final Bitmap result = decoded;
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!active || request != generation) {
                            if (result != null) result.recycle();
                            return;
                        }
                        releaseBitmap();
                        currentBitmap = result;
                        if (result == null) {
                            tvFrameInfo.setText("Frame missing or damaged");
                            tvFrameInfo.setTextColor(Color.parseColor("#E7B75A"));
                            return;
                        }
                        imageView.setImageBitmap(result);
                        CaptureManifest.FrameInfo info = session.getManifest().getFrames().get(index);
                        tvFrameInfo.setText(String.format(Locale.US, "Frame %d / %d · %+.2f s",
                            index + 1, count, info.offsetFromTriggerMs / 1000.0));
                        tvFrameInfo.setTextColor(Color.parseColor("#BCC6CC"));
                    }
                });
            }
        });
    }

    @Override protected boolean onLeftKeyDown() { return true; }
    @Override protected boolean onRightKeyDown() { return true; }
    @Override protected boolean onUpKeyDown() { return true; }
    @Override protected boolean onDownKeyDown() { return true; }
    @Override protected boolean onPlayKeyDown() { return true; }
    @Override protected boolean onMenuKeyDown() { return true; }
    @Override protected boolean onDeleteKeyDown() { return true; }
    @Override protected boolean onFocusKeyDown() { return true; }
    @Override protected boolean onFocusKeyUp() { return true; }
    @Override protected boolean onShutterKeyDown() { return true; }
    @Override protected boolean onShutterKeyUp() { return true; }

    @Override
    protected boolean onLeftKeyUp() { return moveFrame(-1); }
    @Override
    protected boolean onRightKeyUp() { return moveFrame(1); }
    @Override
    protected boolean onUpKeyUp() { return moveSession(-1); }
    @Override
    protected boolean onDownKeyUp() { return moveSession(1); }

    private boolean moveFrame(int direction) {
        if (!isLoading && sessions != null && !sessions.isEmpty()) {
            int count = sessions.get(currentSessionIndex).getFrameCount();
            if (count > 0) {
                currentFrameIndex = (currentFrameIndex + direction + count) % count;
                displayCurrentFrame();
            }
        }
        return true;
    }

    private boolean moveSession(int direction) {
        if (!isLoading && sessions != null && !sessions.isEmpty()) {
            currentSessionIndex = (currentSessionIndex + direction + sessions.size()) % sessions.size();
            currentFrameIndex = 0;
            displayCurrentFrame();
        }
        return true;
    }

    @Override
    protected boolean onDeleteKeyUp() {
        if (sessions == null || sessions.isEmpty() || isLoading) return true;
        final CaptureSession session = sessions.get(currentSessionIndex);
        final int request = ++generation;
        isLoading = true;
        worker.getQueue().clear();
        releaseBitmap();
        tvSession.setText("Deleting capture...");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final boolean deleted = deleteRecursive(session.getDirectory());
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!active || request != generation) return;
                        isLoading = false;
                        if (deleted) {
                            currentFrameIndex = 0;
                            loadSessionsAsync();
                        } else {
                            tvSession.setText("Could not delete capture");
                            tvFrameInfo.setText("Check the SD card. Play returns to the camera.");
                        }
                    }
                });
            }
        });
        return true;
    }

    @Override protected boolean onMenuKeyUp() { returnToCamera(); return true; }
    @Override protected boolean onPlayKeyUp() { returnToCamera(); return true; }

    private void returnToCamera() {
        if (isTaskRoot()) startActivity(new Intent(this, GhostBufferActivity.class));
        finish();
    }

    @Override public void onBackPressed() { returnToCamera(); }

    private boolean deleteRecursive(File file) {
        if (file == null || !file.exists()) return true;
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children == null) return false;
                for (File child : children) {
                    if (!deleteRecursive(child)) return false;
                }
            }
            return file.delete();
        } catch (SecurityException e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        active = false;
        generation++;
        worker.shutdownNow();
        releaseBitmap();
        // Keep posted result callbacks: their generation check must recycle decoded images.
        super.onDestroy();
    }
}
