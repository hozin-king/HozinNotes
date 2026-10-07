package com.hozinking.notes.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.ImageDetailDialog;
import com.hozinking.notes.ui.dialog.IosDialog;
import com.hozinking.notes.ui.dialog.LivePhotoViewerDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Image viewer fullscreen: gambar mengisi layar (fitCenter) di atas
 * background gelap, pinch-to-zoom + double-tap zoom, swipe antar lampiran.
 * Toolbar kaca: kembali / bagikan / rincian / hapus (min 48dp).
 */
public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_NOTE_ID = "note_id";
    public static final String EXTRA_POSITION = "position";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ImageRefEntity> images = new ArrayList<>();
    private final List<byte[]> fullBytes = new ArrayList<>();
    private ViewPager2 pager;
    private View tvCounter;
    private View liveOverlay;
    private long noteId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        noteId = getIntent().getLongExtra(EXTRA_NOTE_ID, -1);
        int startPos = getIntent().getIntExtra(EXTRA_POSITION, 0);
        if (noteId == -1) {
            finish();
            return;
        }

        pager = findViewById(R.id.pager);
        tvCounter = findViewById(R.id.tv_counter);
        liveOverlay = findViewById(R.id.live_overlay);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_share).setOnClickListener(v -> shareCurrent());
        findViewById(R.id.btn_detail).setOnClickListener(v -> showDetail());
        findViewById(R.id.btn_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btn_play_live).setOnClickListener(v -> playLive());

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateChrome(position);
            }
        });

        loadImages(startPos);
    }

    private void loadImages(int startPos) {
        NoteRepository.get(this).getImages(noteId, list -> main.post(() -> {
            images.clear();
            fullBytes.clear();
            if (list != null) {
                images.addAll(list);
                for (int i = 0; i < list.size(); i++) fullBytes.add(null);
            }
            if (images.isEmpty()) {
                finish();
                return;
            }
            pager.setAdapter(new PagerAdapter());
            int pos = Math.max(0, Math.min(startPos, images.size() - 1));
            pager.setCurrentItem(pos, false);
            updateChrome(pos);
        }));
    }

    private void updateChrome(int position) {
        if (position < 0 || position >= images.size()) return;
        ((android.widget.TextView) tvCounter).setText(
                (position + 1) + " / " + images.size());
        boolean live = LivePhotoUtils.isLivePhoto(images.get(position));
        liveOverlay.setVisibility(live ? View.VISIBLE : View.GONE);
        loadFull(position);
    }

    private void loadFull(int position) {
        if (fullBytes.get(position) != null) return;
        ImageRefEntity ref = images.get(position);
        NoteRepository.get(this).loadImagePlain(ref, bytes -> main.post(() -> {
            if (position >= fullBytes.size()) return;
            fullBytes.set(position, bytes);
            RecyclerView.ViewHolder h = pager.findViewHolderForAdapterPosition(position);
            if (h instanceof PageHolder && bytes != null) {
                ((PageHolder) h).bind(bytes);
            }
        }));
    }

    private int current() {
        return pager.getCurrentItem();
    }

    private void shareCurrent() {
        int pos = current();
        if (pos < 0 || pos >= images.size()) return;
        byte[] bytes = fullBytes.get(pos);
        ImageRefEntity ref = images.get(pos);
        if (bytes == null) {
            Toast.makeText(this, R.string.live_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File dir = new File(getCacheDir(), "share");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "img_" + ref.id + ".jpg");
            FileOutputStream out = new FileOutputStream(f);
            out.write(bytes);
            out.close();
            android.net.Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/jpeg");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.backup_export_fail,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showDetail() {
        int pos = current();
        if (pos < 0 || pos >= images.size()) return;
        new ImageDetailDialog(this, images.get(pos),
                fullBytes.get(pos)).show();
    }

    private void playLive() {
        int pos = current();
        if (pos < 0 || pos >= images.size()) return;
        ImageRefEntity ref = images.get(pos);
        byte[] bytes = fullBytes.get(pos);
        if (!LivePhotoUtils.isLivePhoto(ref)) return;
        if (bytes == null) {
            Toast.makeText(this, R.string.live_loading, Toast.LENGTH_SHORT).show();
            return;
        }
        new LivePhotoViewerDialog(this, ref, bytes).show();
    }

    private void confirmDelete() {
        int pos = current();
        if (pos < 0 || pos >= images.size()) return;
        ImageRefEntity ref = images.get(pos);
        new IosDialog(this)
                .setTitle(getString(R.string.notes_remove_image))
                .setPositive(getString(R.string.delete), () ->
                        NoteRepository.get(this).deleteImage(ref, v ->
                                main.post(() -> {
                                    images.remove(pos);
                                    fullBytes.remove(pos);
                                    if (images.isEmpty()) {
                                        setResult(RESULT_OK);
                                        finish();
                                        return;
                                    }
                                    pager.getAdapter().notifyDataSetChanged();
                                    int np = Math.min(pos, images.size() - 1);
                                    pager.setCurrentItem(np, false);
                                    updateChrome(np);
                                })))
                .setNegative(getString(R.string.cancel), () -> {
                })
                .show();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        super.onBackPressed();
    }

    private class PagerAdapter extends RecyclerView.Adapter<PageHolder> {
        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ZoomableImageView iv = new ZoomableImageView(ImageViewerActivity.this);
            iv.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setBackgroundColor(Color.TRANSPARENT);
            FrameLayout frame = new FrameLayout(ImageViewerActivity.this);
            frame.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            frame.addView(iv);
            return new PageHolder(frame, iv);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            byte[] bytes = fullBytes.get(position);
            if (bytes != null) {
                holder.bind(bytes);
            } else {
                holder.clear();
                loadFull(position);
            }
        }

        @Override
        public int getItemCount() {
            return images.size();
        }

        @Override
        public void onViewRecycled(@NonNull PageHolder holder) {
            holder.iv.resetZoom();
        }
    }

    private static class PageHolder extends RecyclerView.ViewHolder {
        final ZoomableImageView iv;

        PageHolder(@NonNull View itemView, ZoomableImageView iv) {
            super(itemView);
            this.iv = iv;
        }

        void bind(byte[] bytes) {
            try {
                Bitmap bmp = BitmapFactory.decodeByteArray(
                        bytes, 0, bytes.length);
                iv.setImageBitmap(bmp);
            } catch (Exception ignored) {
            }
        }

        void clear() {
            iv.setImageDrawable(null);
        }
    }
}
