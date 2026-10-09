package com.hozinking.notes.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.hozinking.notes.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ekspor satu catatan menjadi berkas Markdown (.md) lalu bagikan.
 * Terinspirasi alur ekspor MarkNote; implementasi sendiri: konversi HTML
 * internal (hasil Html.toHtml + TableSpan) menjadi sintaks Markdown umum
 * (heading, bold/italic/strikethrough, checklist, tabel pipa), tanpa
 * dependensi tambahan.
 */
public final class MarkdownExporter {
    private MarkdownExporter() {
    }

    /** Tulis judul+isi sebagai .md di cache lalu kirim via chooser. */
    public static void exportAndShare(Activity activity, String title,
                                      String body, boolean isHtml) {
        Executors.newSingleThreadExecutor().execute(() -> {
            File md = null;
            try {
                md = render(activity, title, body, isHtml);
            } catch (Exception e) {
                md = null;
            }
            final File out = md;
            activity.runOnUiThread(() -> {
                if (out == null || !out.exists()) {
                    Toast.makeText(activity, R.string.md_error, Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", out);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/markdown");
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    activity.startActivity(Intent.createChooser(i,
                            activity.getString(R.string.export_md)));
                } catch (Exception e) {
                    Toast.makeText(activity, R.string.md_error, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private static File render(Activity activity, String title,
                               String body, boolean isHtml) throws Exception {
        String mdBody = isHtml ? htmlToMarkdown(body) : body.trim();
        mdBody = mdBody.replaceAll("\\n{3,}", "\n\n").trim();
        String md = "# " + (title == null ? "" : title.trim()) + "\n\n" + mdBody + "\n";

        File dir = new File(activity.getCacheDir(), "export");
        if (!dir.exists()) dir.mkdirs();
        String base = (title == null ? "" : title.trim())
                .replaceAll("[^\\p{L}\\p{N} _-]+", "_").trim();
        if (base.length() > 40) base = base.substring(0, 40).trim();
        if (base.isEmpty()) base = "catatan";
        File out = new File(dir, base + ".md");
        int dup = 1;
        while (out.exists()) {
            out = new File(dir, base + "-" + (++dup) + ".md");
        }
        OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8);
        try {
            w.write(md);
        } finally {
            w.close();
        }
        return out;
    }

    /** Konversi HTML internal menjadi Markdown (best-effort, aman). */
    static String htmlToMarkdown(String html) {
        if (html == null) return "";
        String s = html;
        // Tabel kustom -> tabel pipa Markdown.
        s = convertTables(s);
        // Format inline.
        s = s.replaceAll("(?i)<(b|strong)>", "**").replaceAll("(?i)</(b|strong)>", "**");
        s = s.replaceAll("(?i)<(i|em|cite)>", "*").replaceAll("(?i)</(i|em|cite)>", "*");
        s = s.replaceAll("(?i)<(strike|s|del)>", "~~").replaceAll("(?i)</(strike|s|del)>", "~~");
        // Underline & highlight: Markdown umum tak mendukung -> teks polos.
        s = s.replaceAll("(?i)</?u>", "");
        s = s.replaceAll("(?i)</?span[^>]*>", "");
        // Paragraf & baris.
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</p>", "\n\n").replaceAll("(?i)<p[^>]*>", "");
        s = s.replaceAll("(?i)</?div[^>]*>", "\n");
        s = s.replaceAll("(?i)</?li[^>]*>", "\n").replaceAll("(?i)</?[uo]l[^>]*>", "\n");
        // Sisa tag dibuang, entitas di-decode.
        s = s.replaceAll("<[^>]+>", "");
        String plain;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            plain = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
        } else {
            plain = Html.fromHtml(s).toString();
        }
        return plain.trim();
    }

    /** Ubah <table>…</table> menjadi tabel pipa Markdown; gagal -> teks polos. */
    private static String convertTables(String html) {
        try {
            Pattern tablePat = Pattern.compile("(?is)<table[^>]*>(.*?)</table>");
            Matcher tm = tablePat.matcher(html);
            StringBuffer sb = new StringBuffer();
            while (tm.find()) {
                String tableHtml = tm.group(1);
                List<List<String>> rows = new ArrayList<>();
                Matcher rm = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(tableHtml);
                while (rm.find()) {
                    List<String> cells = new ArrayList<>();
                    Matcher cm = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>")
                            .matcher(rm.group(1));
                    while (cm.find()) {
                        String cell = cm.group(1).replaceAll("<[^>]+>", " ")
                                .replaceAll("\\s+", " ").trim();
                        cells.add(cell.replace("|", "\\|"));
                    }
                    if (!cells.isEmpty()) rows.add(cells);
                }
                String mdTable;
                if (rows.isEmpty()) {
                    mdTable = "";
                } else {
                    StringBuilder t = new StringBuilder("\n");
                    int cols = 0;
                    for (List<String> r : rows) cols = Math.max(cols, r.size());
                    for (int i = 0; i < rows.size(); i++) {
                        List<String> r = rows.get(i);
                        t.append("|");
                        for (int c = 0; c < cols; c++) {
                            t.append(" ").append(c < r.size() ? r.get(c) : "").append(" |");
                        }
                        t.append("\n");
                        if (i == 0) {
                            t.append("|");
                            for (int c = 0; c < cols; c++) t.append(" --- |");
                            t.append("\n");
                        }
                    }
                    mdTable = t.toString();
                }
                tm.appendReplacement(sb, Matcher.quoteReplacement(mdTable));
            }
            tm.appendTail(sb);
            return sb.toString();
        } catch (Exception e) {
            return html;
        }
    }
}
