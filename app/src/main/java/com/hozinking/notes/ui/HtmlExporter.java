package com.hozinking.notes.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.hozinking.notes.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Ekspor satu catatan menjadi berkas HTML (.html) mandiri lalu bagikan.
 * Terinspirasi fitur ekspor HTML aplikasi catatan populer (mis. Notally);
 * implementasi sendiri: isi teks polos dirender ringan (heading, tebal,
 * miring, coret, checklist), isi HTML internal (dari editor berformat)
 * disematkan langsung dengan CSS aman. Tanpa dependensi tambahan.
 */
public final class HtmlExporter {
    private HtmlExporter() {
    }

    /** Tulis judul+isi sebagai .html di cache lalu kirim via chooser. */
    public static void exportAndShare(Activity activity, String title,
                                      String body, boolean isHtml) {
        Executors.newSingleThreadExecutor().execute(() -> {
            File html = null;
            try {
                html = render(activity, title, body, isHtml);
            } catch (Exception e) {
                html = null;
            }
            final File out = html;
            activity.runOnUiThread(() -> {
                if (out == null || !out.exists()) {
                    Toast.makeText(activity, R.string.html_error,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", out);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/html");
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    activity.startActivity(Intent.createChooser(i,
                            activity.getString(R.string.export_html)));
                } catch (Exception e) {
                    Toast.makeText(activity, R.string.html_error,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private static File render(Activity activity, String title,
                               String body, boolean isHtml) throws Exception {
        String inner = isHtml ? sanitizeInternalHtml(body)
                : plainToHtml(body);
        String doc = "<!DOCTYPE html>\n<html lang=\"id\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + esc(title) + "</title>\n"
                + "<style>\n"
                + "body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;"
                + "max-width:42rem;margin:0 auto;padding:1.5rem;line-height:1.6;"
                + "color:#1c1c1e;background:#ffffff}\n"
                + "h1{font-size:1.6rem;margin:0 0 .5rem}\n"
                + "h2{font-size:1.3rem}h3{font-size:1.1rem}\n"
                + "table{border-collapse:collapse;margin:1em 0}\n"
                + "td,th{border:1px solid #ccc;padding:.35em .6em}\n"
                + "@media(prefers-color-scheme:dark){"
                + "body{color:#f2f2f7;background:#1c1c1e}"
                + "td,th{border-color:#555}}\n"
                + "</style>\n</head>\n<body>\n"
                + "<h1>" + esc(title) + "</h1>\n"
                + inner + "\n</body>\n</html>\n";

        File dir = new File(activity.getCacheDir(), "export");
        if (!dir.exists()) dir.mkdirs();
        String base = (title == null ? "" : title.trim())
                .replaceAll("[^\\p{L}\\p{N} _-]+", "_").trim();
        if (base.length() > 40) base = base.substring(0, 40).trim();
        if (base.isEmpty()) base = "catatan";
        File out = new File(dir, base + ".html");
        int dup = 1;
        while (out.exists()) {
            out = new File(dir, base + "-" + (++dup) + ".html");
        }
        OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8);
        try {
            w.write(doc);
        } finally {
            w.close();
        }
        return out;
    }

    /** Escape teks polos -> HTML + render ringan sintaks umum. */
    static String plainToHtml(String body) {
        if (body == null) body = "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("### ")) {
                sb.append("<h3>").append(inline(esc(t.substring(4)))).append("</h3>\n");
            } else if (t.startsWith("## ")) {
                sb.append("<h2>").append(inline(esc(t.substring(3)))).append("</h2>\n");
            } else if (t.startsWith("# ")) {
                sb.append("<h2>").append(inline(esc(t.substring(2)))).append("</h2>\n");
            } else if (t.matches("(?i)^\\s*- \\[[ xX]\\] .*")) {
                boolean checked = t.matches("(?i)^\\s*- \\[[xX]\\] .*");
                String item = t.replaceFirst("(?i)^\\s*- \\[[ xX]\\] ", "");
                sb.append("<p>").append(checked ? "&#9745;" : "&#9744;")
                        .append(" ").append(inline(esc(item))).append("</p>\n");
            } else {
                sb.append("<p>").append(inline(esc(line))).append("</p>\n");
            }
        }
        return sb.toString();
    }

    /** Render ringan **tebal**, *miring*, ~~coret~~ pada teks yang sudah di-escape. */
    private static String inline(String s) {
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        s = s.replaceAll("(?<!\\w)\\*(.+?)\\*(?!\\w)", "<i>$1</i>");
        s = s.replaceAll("~~(.+?)~~", "<del>$1</del>");
        return s;
    }

    /**
     * HTML internal dari editor: pertahankan struktur, buang yang berbahaya
     * (script/style/iframe + atribut event-handler) agar aman dibuka di browser.
     */
    static String sanitizeInternalHtml(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<script.*?</script>", "");
        s = s.replaceAll("(?is)<style.*?</style>", "");
        s = s.replaceAll("(?is)<iframe.*?</iframe>", "");
        s = s.replaceAll("(?i)\\s+on\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", "");
        s = s.replaceAll("(?i)javascript\\s*:", "");
        return s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
