package com.hozinking.notes.ui;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.hozinking.notes.BuildConfig;
import com.hozinking.notes.R;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Layar sketsa: gambar bebas dengan jari, lalu simpan sebagai gambar
 * catatan lewat pipeline gambar terenkripsi yang sudah ada
 * (repo.addImageFromUri). Extra "noteId" (long).
 */
public class SketchActivity extends AppCompatActivity {

    private static final int[] PALETTE = {
            0xFF1A1A1A, 0xFFEF4444, 0xFFF97316, 0xFFEAB308,
            0xFF22C55E, 0xFF06B6D4, 0xFF3B82F6, 0xFF8B5CF6,
            0xFFEC4899, 0xFF6B7280,
    };

    private SketchView sketchView;
    private LinearLayout paletteRow;
    private long noteId = -1;
    private int selectedColor = PALETTE[0];
    private boolean saving = false;

    private TextView sizeSmall, sizeMedium, sizeLarge;
    private TextView toolPen, toolEraser;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sketch);

        noteId = getIntent().getLongExtra("noteId", -1);

        sketchView = findViewById(R.id.sketch_view);
        paletteRow = findViewById(R.id.palette_row);
        sizeSmall = findViewById(R.id.size_small);
        sizeMedium = findViewById(R.id.size_medium);
        sizeLarge = findViewById(R.id.size_large);
        toolPen = findViewById(R.id.tool_pen);
        toolEraser = findViewById(R.id.tool_eraser);

        buildPalette();
        sketchView.setPenColor(selectedColor);
        selectSize(sizeMedium, dp(10));

        sizeSmall.setOnClickListener(v -> selectSize(sizeSmall, dp(5)));
        sizeMedium.setOnClickListener(v -> selectSize(sizeMedium, dp(10)));
        sizeLarge.setOnClickListener(v -> selectSize(sizeLarge, dp(18)));

        toolPen.setOnClickListener(v -> {
            sketchView.setEraser(false);
            refreshToolState();
        });
        toolEraser.setOnClickListener(v -> {
            sketchView.setEraser(true);
            refreshToolState();
        });
        refreshToolState();

        findViewById(R.id.sketch_undo).setOnClickListener(v -> sketchView.undo());
        findViewById(R.id.sketch_clear).setOnClickListener(v -> confirmClear());
        findViewById(R.id.sketch_cancel).setOnClickListener(v -> confirmCancel());
        findViewById(R.id.sketch_save).setOnClickListener(v -> saveSketch());
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }

    private void buildPalette() {
        paletteRow.removeAllViews();
        int dot = dp(34);
        for (int color : PALETTE) {
            View dotView = new View(this);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(dot, dot);
            int m = dp(6);
            lp.setMargins(m, m, m, m);
            dotView.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            dotView.setBackground(bg);
            final int c = color;
            dotView.setOnClickListener(v -> {
                selectedColor = c;
                sketchView.setPenColor(c);
                refreshPalette();
                refreshToolState();
            });
            dotView.setTag(color);
            paletteRow.addView(dotView);
        }
        refreshPalette();
    }

    private void refreshPalette() {
        for (int i = 0; i < paletteRow.getChildCount(); i++) {
            View dot = paletteRow.getChildAt(i);
            int color = (int) dot.getTag();
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(color);
            if (color == selectedColor && !sketchView.isEraser()) {
                bg.setStroke(dp(3), 0xFF111111);
            } else {
                bg.setStroke(dp(1), 0x33000000);
            }
            dot.setBackground(bg);
        }
    }

    private void selectSize(TextView active, int widthPx) {
        sketchView.setPenWidth(widthPx);
        int accent = 0xFF7C6FF0;
        int normal = 0xFF6B7280;
        sizeSmall.setTextColor(active == sizeSmall ? accent : normal);
        sizeMedium.setTextColor(active == sizeMedium ? accent : normal);
        sizeLarge.setTextColor(active == sizeLarge ? accent : normal);
        sizeSmall.setTypeface(null, active == sizeSmall
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        sizeMedium.setTypeface(null, active == sizeMedium
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        sizeLarge.setTypeface(null, active == sizeLarge
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void refreshToolState() {
        boolean eraser = sketchView.isEraser();
        int accent = 0xFF7C6FF0;
        int normal = 0xFF6B7280;
        toolPen.setTextColor(eraser ? normal : accent);
        toolEraser.setTextColor(eraser ? accent : normal);
        toolPen.setTypeface(null, eraser
                ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        toolEraser.setTypeface(null, eraser
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        refreshPalette();
    }

    private void confirmClear() {
        if (!sketchView.hasContent()) return;
        new IosDialog(this)
                .setTitle(getString(R.string.sketch_clear_title))
                .setMessage(getString(R.string.sketch_clear_msg))
                .setPositive(getString(R.string.sketch_clear_yes), () -> sketchView.clear())
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    private void confirmCancel() {
        if (!sketchView.hasContent() || saving) {
            finish();
            return;
        }
        new IosDialog(this)
                .setTitle(getString(R.string.sketch_discard_title))
                .setMessage(getString(R.string.sketch_discard_msg))
                .setPositive(getString(R.string.sketch_discard_yes), this::finish)
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    private void saveSketch() {
        if (saving) return;
        if (!sketchView.hasContent()) {
            Toast.makeText(this, R.string.sketch_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (noteId == -1) {
            Toast.makeText(this, R.string.sketch_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        saving = true;
        Bitmap bmp = sketchView.getBitmap();
        if (bmp == null) {
            saving = false;
            Toast.makeText(this, R.string.sketch_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            File tmp = null;
            try {
                File dir = new File(getCacheDir(), "sketch");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                tmp = new File(dir, "sketch_" + System.currentTimeMillis() + ".png");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                Uri uri = FileProvider.getUriForFile(this,
                        BuildConfig.APPLICATION_ID + ".fileprovider", tmp);
                File f = tmp;
                NoteRepository.get(this).addImageFromUri(this, noteId, uri, ref ->
                        runOnUiThread(() -> {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                            saving = false;
                            if (ref != null) {
                                setResult(RESULT_OK);
                                finish();
                            } else {
                                Toast.makeText(this, R.string.sketch_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        }));
            } catch (Exception e) {
                if (tmp != null) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
                runOnUiThread(() -> {
                    saving = false;
                    Toast.makeText(this, R.string.sketch_failed, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        confirmCancel();
    }
}
