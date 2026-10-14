package com.github.ma1co.pmcademo.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import com.casklabs.ghostbuffer.ui.GhostBufferActivity;
import com.casklabs.ghostbuffer.ui.GhostGalleryActivity;

/** Small GhostBuffer menu controlled by the camera's direction and enter keys. */
public class MainActivity extends BaseActivity {
    private View cameraItem;
    private View galleryItem;
    private int selected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ghost_menu);
        cameraItem = findViewById(R.id.menu_camera);
        galleryItem = findViewById(R.id.menu_gallery);
        cameraItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openCamera(); }
        });
        galleryItem.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { openGallery(); }
        });
        updateSelection();
    }

    private void updateSelection() {
        cameraItem.setSelected(selected == 0);
        galleryItem.setSelected(selected == 1);
    }

    private void openCamera() {
        if (isTaskRoot()) startActivity(new Intent(this, GhostBufferActivity.class));
        finish();
    }

    private void openGallery() {
        startActivity(new Intent(this, GhostGalleryActivity.class));
        finish();
    }

    @Override protected boolean onUpKeyDown() { return true; }
    @Override protected boolean onDownKeyDown() { return true; }
    @Override protected boolean onEnterKeyDown() { return true; }
    @Override protected boolean onPlayKeyDown() { return true; }
    @Override protected boolean onMenuKeyDown() { return true; }
    @Override protected boolean onUpKeyUp() { selected = 0; updateSelection(); return true; }
    @Override protected boolean onDownKeyUp() { selected = 1; updateSelection(); return true; }
    @Override protected boolean onEnterKeyUp() {
        if (selected == 0) openCamera(); else openGallery();
        return true;
    }
    @Override protected boolean onPlayKeyUp() { openGallery(); return true; }
    @Override protected boolean onMenuKeyUp() { openCamera(); return true; }
}
