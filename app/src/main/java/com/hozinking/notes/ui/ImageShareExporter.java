package com.hozinking.notes.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.hozinking.notes.R;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Bagikan satu catatan sebagai gambar kartu (PNG) lalu kirim via chooser.
 * Konsep "share note as an image" ala Shkiper (Efimj/Shkiper); implementasi
 * sendiri memakai Canvas framework bawaan (tanpa dependensi tambahan).
 *
 * Desain: latar luar memakai warna catatan, kartu putih membulat di tengah
 * berisi judul tebal + isi terbungkus rapi + strip tanggal/"Hozin Notes".
 */
public final class ImageShareExporter {
    private ImageShareExporter() {
    }

    private static final int CARD_W = 1080;
    private static final int OUTER_PAD = 56;
    private static final int CARD_PAD = 88;
    private static final float CORNER = 64f;
    private static final int MAX_BODY_CHARS = 1600;
    private static final int MAX_CARD_H = 6200;

    /** Render kartu catatan ke PNG di cache lalu kirim via chooser. */
    public static void exportAndShare(Activity activity, String title,
                                      String body, int noteColor) {
        Executors.newSingleThreadExecutor().execute(() -> {
            File png = null;
            try {
                png = render(activity, title, body, noteColor);
            } catch (Exception e) {
                png = null;
            }
            final File out = png;
            activity.runOnUiThread(() -> {
                if (out == null || !out.exists()) {
                    Toast.makeText(activity, R.string.share_as_image_error,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", out);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("image/png");
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    activity.startActivity(Intent.createChooser(i,
                            activity.getString(R.string.share_as_image)));
                } catch (Exception e) {
                    Toast.makeText(activity, R.string.share_as_image_error,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private static File render(Activity activity, String title,
                               String body, int noteColor) throws Exception {
        String t = title == null ? "" : title.trim();
        String b = body == null ? "" : body.trim();
        if (b.length() > MAX_BODY_CHARS) {
            b = b.substring(0, MAX_BODY_CHARS).trim() + "…";
        }
        if (t.isEmpty() && b.isEmpty()) {
            t = activity.getString(R.string.notes_no_title);
        }

        int contentW = CARD_W - (OUTER_PAD + CARD_PAD) * 2;

        TextPaint titlePaint = textPaint(66, Typeface.BOLD, 0xFF1C1C1E);
        TextPaint bodyPaint = textPaint(46, Typeface.NORMAL, 0xFF3A3A3C);
        TextPaint footerPaint = textPaint(34, Typeface.NORMAL, 0xFF8E8E93);

        StaticLayout titleLayout = layout(t, titlePaint, contentW);
        StaticLayout bodyLayout = layout(b, bodyPaint, contentW);
        String footer = new SimpleDateFormat("dd MMM yyyy",
                new Locale("id", "ID")).format(new Date()) + " · Hozin Notes";
        StaticLayout footerLayout = layout(footer, footerPaint, contentW);

        int innerH = CARD_PAD
                + titleLayout.getHeight() + 28
                + bodyLayout.getHeight() + 44
                + footerLayout.getHeight()
                + CARD_PAD;
        int cardH = OUTER_PAD * 2 + innerH;
        if (cardH > MAX_CARD_H) {
            // Pengaman: seharusnya tidak terjadi karena body sudah dibatasi.
            cardH = MAX_CARD_H;
        }

        Bitmap bmp = Bitmap.createBitmap(CARD_W, cardH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        int outerBg = noteColor != 0 ? darken(noteColor, 0.88f) : 0xFFE2E2E6;
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(outerBg);
        c.drawRect(0, 0, CARD_W, cardH, bgPaint);

        Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardPaint.setColor(Color.WHITE);
        RectF card = new RectF(OUTER_PAD, OUTER_PAD,
                CARD_W - OUTER_PAD, cardH - OUTER_PAD);
        c.drawRoundRect(card, CORNER, CORNER, cardPaint);

        // Aksen warna catatan: strip kiri kartu.
        if (noteColor != 0) {
            Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
            accent.setColor(noteColor);
            RectF strip = new RectF(OUTER_PAD, OUTER_PAD + CORNER,
                    OUTER_PAD + 18, cardH - OUTER_PAD - CORNER);
            c.drawRoundRect(strip, 9f, 9f, accent);
        }

        c.save();
        c.translate(OUTER_PAD + CARD_PAD, OUTER_PAD + CARD_PAD);
        titleLayout.draw(c);
        c.translate(0, titleLayout.getHeight() + 28);
        bodyLayout.draw(c);
        c.translate(0, bodyLayout.getHeight() + 44);
        footerLayout.draw(c);
        c.restore();

        File dir = new File(activity.getCacheDir(), "share");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cache/share tidak bisa dibuat");
        }
        // Bersihkan file share lama agar cache tidak menumpuk.
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (f.getName().startsWith("note-") && f.getName().endsWith(".png")) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        File out = new File(dir, "note-" + System.currentTimeMillis() + ".png");
        FileOutputStream fos = new FileOutputStream(out);
        try {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new IllegalStateException("gagal kompres PNG");
            }
        } finally {
            fos.close();
        }
        bmp.recycle();
        return out;
    }

    private static TextPaint textPaint(int px, int style, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(px);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        p.setColor(color);
        return p;
    }

    private static StaticLayout layout(String text, TextPaint paint, int width) {
        if (text == null) text = "";
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8f, 1f)
                .setIncludePad(true)
                .build();
    }

    /** Gelapkan warna latar luar agar kartu putih tetap kontras. */
    private static int darken(int color, float factor) {
        int a = Color.alpha(color);
        int r = Math.round(Color.red(color) * factor);
        int g = Math.round(Color.green(color) * factor);
        int b = Math.round(Color.blue(color) * factor);
        return Color.argb(a, r, g, b);
    }
}
