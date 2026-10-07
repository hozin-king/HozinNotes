package com.hozinking.notes.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.print.PrintAttributes;
import android.print.pdf.PrintedPdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.hozinking.notes.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Ekspor satu catatan menjadi PDF lalu bagikan.
 * Terinspirasi fitur PDF export Markor/Notally; implementasi sendiri memakai
 * framework android.print bawaan (tanpa dependensi tambahan).
 */
public final class PdfExporter {
    private PdfExporter() {
    }

    /** Render judul+isi menjadi PDF di cache lalu kirim via chooser. */
    public static void exportAndShare(Activity activity, String title, String body) {
        Executors.newSingleThreadExecutor().execute(() -> {
            File pdf = null;
            try {
                pdf = render(activity, title, body);
            } catch (Exception e) {
                pdf = null;
            }
            final File out = pdf;
            activity.runOnUiThread(() -> {
                if (out == null || !out.exists()) {
                    Toast.makeText(activity, R.string.pdf_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", out);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("application/pdf");
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    activity.startActivity(
                            Intent.createChooser(i, activity.getString(R.string.export_pdf)));
                } catch (Exception e) {
                    Toast.makeText(activity, R.string.pdf_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private static File render(Activity activity, String title, String body)
            throws Exception {
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();
        PdfDocument doc = new PrintedPdfDocument(activity, attrs);
        try {
            int pageW = 595;  // A4 @72dpi
            int pageH = 842;
            int margin = 48;
            int contentW = pageW - margin * 2;

            TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(0xFF151A2B);
            titlePaint.setTextSize(18 * 2); // ~18sp @~144dpi efektif
            titlePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            bodyPaint.setColor(0xFF2B3245);
            bodyPaint.setTextSize(12 * 2);
            bodyPaint.setTypeface(Typeface.DEFAULT);

            TextPaint metaPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            metaPaint.setColor(0xFF8A93A8);
            metaPaint.setTextSize(10 * 2);

            String[] lines = body.split("\n", -1);
            int lineIdx = 0;
            boolean firstPage = true;
            int pageNo = 1;
            while (firstPage || lineIdx < lines.length) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(
                        pageW, pageH, pageNo).create();
                PdfDocument.Page page = doc.startPage(info);
                int y = margin;
                if (firstPage) {
                    StaticLayout tl = layout(title, titlePaint, contentW,
                            Layout.Alignment.ALIGN_NORMAL);
                    page.getCanvas().save();
                    page.getCanvas().translate(margin, y);
                    tl.draw(page.getCanvas());
                    page.getCanvas().restore();
                    y += tl.getHeight() + 12;
                    String meta = new SimpleDateFormat("dd MMM yyyy, HH:mm",
                            new Locale("id", "ID")).format(new Date())
                            + " · Hozin Notes";
                    StaticLayout ml = layout(meta, metaPaint, contentW,
                            Layout.Alignment.ALIGN_NORMAL);
                    page.getCanvas().save();
                    page.getCanvas().translate(margin, y);
                    ml.draw(page.getCanvas());
                    page.getCanvas().restore();
                    y += ml.getHeight() + 28;
                    firstPage = false;
                }
                // Isi halaman dengan paragraf per paragraf.
                while (lineIdx < lines.length) {
                    String para = lines[lineIdx];
                    if (para.trim().isEmpty()) {
                        lineIdx++;
                        y += (int) (bodyPaint.getTextSize() * 0.7f);
                        continue;
                    }
                    StaticLayout sl = layout(para, bodyPaint, contentW,
                            Layout.Alignment.ALIGN_NORMAL);
                    if (y + sl.getHeight() > pageH - margin) break; // lanjut halaman berikut
                    page.getCanvas().save();
                    page.getCanvas().translate(margin, y);
                    sl.draw(page.getCanvas());
                    page.getCanvas().restore();
                    y += sl.getHeight() + (int) (bodyPaint.getTextSize() * 0.35f);
                    lineIdx++;
                }
                // Nomor halaman.
                String num = String.valueOf(pageNo);
                float nw = metaPaint.measureText(num);
                page.getCanvas().drawText(num, pageW - margin - nw, pageH - 24, metaPaint);
                doc.finishPage(page);
                pageNo++;
                // Berhenti bila semua baris sudah tergambar (hindari halaman kosong).
                if (lineIdx >= lines.length) break;
            }
            File dir = new File(activity.getCacheDir(), "pdf");
            if (!dir.exists()) dir.mkdirs();
            String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (safe.isEmpty()) safe = "catatan";
            if (safe.length() > 40) safe = safe.substring(0, 40);
            File out = new File(dir, safe + "-" + System.currentTimeMillis() + ".pdf");
            try (OutputStream os = new FileOutputStream(out)) {
                doc.writeTo(os);
            }
            return out;
        } finally {
            doc.close();
        }
    }

    private static StaticLayout layout(String text, TextPaint paint, int width,
                                       Layout.Alignment align) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(align)
                    .setLineSpacing(4f, 1f)
                    .setIncludePad(true)
                    .build();
        }
        //noinspection deprecation
        return new StaticLayout(text, paint, width, align, 1f, 4f, true);
    }
}
