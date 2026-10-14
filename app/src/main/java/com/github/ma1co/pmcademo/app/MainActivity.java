package com.github.ma1co.pmcademo.app;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import com.casklabs.ghostbuffer.ui.GhostBufferActivity;
import com.casklabs.ghostbuffer.ui.GhostGalleryActivity;

/** Small GhostBuffer menu controlled by the camera's direction and enter keys. */
public class MainActivity extends BaseActivity {
    private View cameraItem;
    private View galleryItem;
    private View exposureItem;
    private int selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghost_menu);
        cameraItem = findViewById(R.id.menu_camera);
        galleryItem = findViewById(R.id.menu_gallery);
        exposureItem = findViewById(R.id.menu_exposure);
        cameraItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openCamera(); }
        });
        galleryItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openGallery(); }
        });
        exposureItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openExposure(); }
        });
        updateSelection();
    }

    private void updateSelection() {
        cameraItem.setSelected(selected == 0);
        galleryItem.setSelected(selected == 1);
        exposureItem.setSelected(selected == 2);
        // Selection uses camera keys rather than Android focus, so reveal it explicitly.
        cameraItem.post(new Runnable() {
            @Override public void run() {
                View item = selected == 0 ? cameraItem : selected == 1 ? galleryItem : exposureItem;
                Rect bounds = new Rect();
                item.getDrawingRect(bounds);
                item.requestRectangleOnScreen(bounds, true);
            }
        });
    }

    private void openCamera() {
        if (isTaskRoot()) startActivity(new Intent(this, GhostBufferActivity.class));
        finish();
    }

    private void openGallery() {
        startActivity(new Intent(this, GhostGalleryActivity.class));
        finish();
    }

    private void openExposure() {
        Intent intent = new Intent(this, GhostBufferActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(GhostBufferActivity.EXTRA_OPEN_EXPOSURE, true);
        startActivity(intent);
        finish();
    }

    @Override protected boolean onUpKeyDown() { return true; }
    @Override protected boolean onDownKeyDown() { return true; }
    @Override protected boolean onEnterKeyDown() { return true; }
    @Override protected boolean onPlayKeyDown() { return true; }
    @Override protected boolean onMenuKeyDown() { return true; }
    @Override protected boolean onUpKeyUp() { selected = (selected + 2) % 3; updateSelection(); return true; }
    @Override protected boolean onDownKeyUp() { selected = (selected + 1) % 3; updateSelection(); return true; }
    @Override protected boolean onEnterKeyUp() {
        if (selected == 0) openCamera(); else if (selected == 1) openGallery(); else openExposure();
        return true;
    }
    @Override protected boolean onPlayKeyUp() { openGallery(); return true; }
    @Override protected boolean onMenuKeyUp() { openCamera(); return true; }
}
