package com.hozinking.notes.backup;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Xml;

import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.db.NoteDao;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.db.NoteTagCrossRef;
import com.hozinking.notes.data.db.TagDao;
import com.hozinking.notes.data.db.TagEntity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Impor catatan dari aplikasi lain — ditulis sendiri, terinspirasi konsep
 * impor multi-sumber ala Joplin (laurent22/joplin, AGPL-3.0):
 * <ul>
 *   <li>Evernote: file ekspor <b>.enex</b> (ENML → teks polos; daftar tugas
 *       {@code <en-todo>} jadi checklist, lampiran jadi penanda teks).</li>
 *   <li>Google Keep: satu file <b>.json</b> per catatan, atau <b>.zip</b>
 *       hasil Google Takeout berisi banyak {@code Takeout/Keep/*.json}.</li>
 * </ul>
 * Judul, isi, tanggal asli, status arsip/sampah, dan label/tag dipertahankan.
 * Tidak ada migrasi DB — catatan dimasukkan lewat DAO yang sudah ada
 * (FTS Room tersinkron otomatis).
 */
public class NoteImporter {

    public static class Result {
        public int imported;
        public int skipped;
        public String error;
    }

    public interface Cb {
        void onResult(Result r);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long MAX_BYTES = 64L * 1024 * 1024; // 64 MB
    private static final int MAX_NOTES = 10000;

    public static void importFile(Context context, Uri uri, Cb cb) {
        IO.execute(() -> {
            Result r = new Result();
            try {
                String name = displayName(context.getContentResolver(), uri).toLowerCase(Locale.US);
                if (name.endsWith(".enex")) {
                    parseEnex(context, uri, r);
                } else if (name.endsWith(".zip")) {
                    parseKeepZip(context, uri, r);
                } else if (name.endsWith(".json")) {
                    parseKeepSingle(context, uri, r);
                } else {
                    // Tebak dari isi: ENML / JSON / ZIP (magic "PK").
                    byte[] head = readHead(context, uri);
                    String s = new String(head, "UTF-8").trim();
                    if (s.startsWith("PK")) {
                        parseKeepZip(context, uri, r);
                    } else if (s.startsWith("{")) {
                        KeepNote k = parseKeepJson(new String(readAll(context, uri, MAX_BYTES), "UTF-8"));
                        insertKeep(context, k, r);
                    } else if (s.contains("<en-export")) {
                        parseEnex(context, uri, r);
                    } else {
                        r.error = "format tak dikenal";
                    }
                }
            } catch (Exception e) {
                r.error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            cb.onResult(r);
        });
    }

    // ---------------------------------------------------------------- deteksi

    private static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p != null ? p : "";
    }

    private static byte[] readHead(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            if (n > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static byte[] readAll(Context ctx, Uri uri, long max) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) throw new Exception("file terlalu besar");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- Evernote

    private static void parseEnex(Context ctx, Uri uri, Result r) throws Exception {
        NoteDao noteDao = AppDatabase.get(ctx.getApplicationContext()).noteDao();
        TagDao tagDao = AppDatabase.get(ctx.getApplicationContext()).tagDao();
        SimpleDateFormat enDate = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        enDate.setTimeZone(TimeZone.getTimeZone("UTC"));

        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(in, "UTF-8");
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT && r.imported + r.skipped < MAX_NOTES) {
                if (ev == XmlPullParser.START_TAG && "note".equals(p.getName())) {
                    try {
                        importEvernoteNote(p, noteDao, tagDao, enDate);
                        r.imported++;
                    } catch (Exception e) {
                        r.skipped++;
                    }
                }
                ev = p.next();
            }
        }
    }

    /** Parser diposisikan di START_TAG "note"; selesai tepat setelah END_TAG-nya. */
    private static void importEvernoteNote(XmlPullParser p, NoteDao noteDao, TagDao tagDao,
                                           SimpleDateFormat enDate) throws Exception {
        String title = "", contentXml = "", created = null, updated = null;
        List<String> tags = new ArrayList<>();
        // readText/readInnerXml/skipTag mengonsumsi satu elemen utuh
        // (START_TAG s.d. END_TAG-nya), jadi depth loop hanya untuk </note>.
        int depth = 1;
        int ev = p.next();
        while (!(ev == XmlPullParser.END_TAG && "note".equals(p.getName()) && depth == 1)) {
            if (ev == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("title".equals(name)) title = readText(p);
                else if ("content".equals(name)) contentXml = readInnerXml(p);
                else if ("created".equals(name)) created = readText(p);
                else if ("updated".equals(name)) updated = readText(p);
                else if ("tag".equals(name)) {
                    String t = readText(p).trim();
                    if (!t.isEmpty()) tags.add(t);
                } else skipTag(p);
            } else if (ev == XmlPullParser.END_TAG) {
                depth--;
            }
            ev = p.next();
        }
        EnmlText t = enmlToText(contentXml);
        String body = t.text.trim();
        if (title.trim().isEmpty() && body.isEmpty()) throw new Exception("catatan kosong");

        NoteEntity n = new NoteEntity();
        n.title = title.trim();
        n.content = body;
        n.isChecklist = t.hasTodo;
        n.createdAt = parseEnDate(enDate, created);
        n.updatedAt = parseEnDate(enDate, updated);
        if (n.updatedAt == 0) n.updatedAt = n.createdAt;
        long id = noteDao.insert(n);
        insertTags(tagDao, id, tags);
    }

    private static long parseEnDate(SimpleDateFormat f, String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            return f.parse(s.trim()).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readText(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        int ev = p.next();
        while (!(ev == XmlPullParser.END_TAG && depth == 1)) {
            if (ev == XmlPullParser.TEXT) sb.append(p.getText());
            else if (ev == XmlPullParser.START_TAG) {
                depth++;
                skipTag(p);
                depth--;
            } else if (ev == XmlPullParser.END_TAG) depth--;
            ev = p.next();
        }
        return sb.toString();
    }

    /** Lewati seluruh subtree tag saat ini (parser di START_TAG). */
    private static void skipTag(XmlPullParser p) throws Exception {
        int depth = 1;
        int ev = p.next();
        while (depth > 0 && ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
            ev = p.next();
        }
    }

    /**
     * Ambil XML mentah di dalam tag saat ini (parser di START_TAG);
     * selesai tepat setelah END_TAG penutupnya.
     */
    private static String readInnerXml(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        int ev = p.next();
        boolean pendingStart = false;
        String pendingName = null;
        StringBuilder pendingAttrs = new StringBuilder();
        while (!(ev == XmlPullParser.END_TAG && depth == 1)) {
            if (ev == XmlPullParser.START_TAG) {
                if (pendingStart) sb.append('<').append(pendingName).append(pendingAttrs).append('>');
                pendingStart = true;
                pendingName = p.getName();
                pendingAttrs.setLength(0);
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    pendingAttrs.append(' ').append(p.getAttributeName(i))
                            .append("=\"").append(p.getAttributeValue(i)).append('"');
                }
                depth++;
            } else if (ev == XmlPullParser.END_TAG) {
                if (pendingStart) {
                    sb.append('<').append(pendingName).append(pendingAttrs).append("/>");
                    pendingStart = false;
                } else {
                    sb.append("</").append(p.getName()).append('>');
                }
                depth--;
            } else if (ev == XmlPullParser.TEXT) {
                if (pendingStart) {
                    sb.append('<').append(pendingName).append(pendingAttrs).append('>');
                    pendingStart = false;
                }
                sb.append(escapeXmlText(p.getText()));
            } else if (ev == XmlPullParser.CDSECT) {
                if (pendingStart) {
                    sb.append('<').append(pendingName).append(pendingAttrs).append('>');
                    pendingStart = false;
                }
                sb.append(escapeXmlText(p.getText()));
            }
            ev = p.next();
        }
        if (pendingStart) sb.append('<').append(pendingName).append(pendingAttrs).append("/>");
        return sb.toString();
    }

    private static String escapeXmlText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static class EnmlText {
        String text = "";
        boolean hasTodo;
    }

    /** ENML → teks polos. <en-todo> jadi "- [ ] "/ "- [x] ", <en-media> jadi penanda. */
    private static EnmlText enmlToText(String enml) {
        EnmlText out = new EnmlText();
        if (enml == null || enml.isEmpty()) return out;
        String cleaned = enml
                .replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&mdash;", "\u2014").replace("&ndash;", "\u2013")
                .replace("&hellip;", "\u2026").replace("&middot;", "\u00b7")
                .replace("&bull;", "\u2022").replace("&laquo;", "\u00ab")
                .replace("&raquo;", "\u00bb").replace("&copy;", "\u00a9")
                .replace("&reg;", "\u00ae").replace("&trade;", "\u2122");
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new java.io.StringReader(cleaned));
            StringBuilder sb = new StringBuilder();
            String linkHref = null;
            StringBuilder linkText = null;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = p.getName();
                    switch (name) {
                        case "br":
                        case "hr":
                            newline(sb);
                            break;
                        case "div":
                        case "p":
                        case "li":
                        case "tr":
                        case "blockquote":
                        case "h1": case "h2": case "h3":
                        case "h4": case "h5": case "h6":
                            newline(sb);
                            break;
                        case "en-todo": {
                            out.hasTodo = true;
                            newline(sb);
                            String checked = p.getAttributeValue(null, "checked");
                            sb.append("true".equalsIgnoreCase(checked) ? "- [x] " : "- [ ] ");
                            break;
                        }
                        case "en-media":
                            newline(sb);
                            sb.append("\uD83D\uDDBC [lampiran]");
                            newline(sb);
                            break;
                        case "a":
                            linkHref = p.getAttributeValue(null, "href");
                            linkText = new StringBuilder();
                            break;
                        default:
                            break;
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    String t = p.getText();
                    if (linkText != null) linkText.append(t);
                    else sb.append(t);
                } else if (ev == XmlPullParser.CDSECT) {
                    sb.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = p.getName();
                    if ("div".equals(name) || "p".equals(name) || "li".equals(name)
                            || "en-todo".equals(name) || "tr".equals(name)
                            || name.startsWith("h")) {
                        newline(sb);
                    } else if ("a".equals(name) && linkText != null) {
                        String lt = linkText.toString();
                        sb.append(lt);
                        if (linkHref != null && !linkHref.isEmpty()
                                && !linkHref.equals(lt.trim())) {
                            sb.append(" (").append(linkHref).append(')');
                        }
                        linkText = null;
                        linkHref = null;
                    }
                }
                ev = p.next();
            }
            out.text = collapseBlankLines(sb.toString());
        } catch (Exception e) {
            // Fallback: buang tag mentah-mentah.
            out.text = collapseBlankLines(cleaned.replaceAll("<[^>]+>", " "));
        }
        return out;
    }

    private static void newline(StringBuilder sb) {
        int n = sb.length();
        if (n > 0 && sb.charAt(n - 1) != '\n') sb.append('\n');
    }

    private static String collapseBlankLines(String s) {
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n");
    }

    // ---------------------------------------------------------------- Google Keep

    private static class KeepNote {
        String title = "";
        String text = "";
        boolean checklist;
        boolean archived;
        boolean trashed;
        long createdAt;
        long updatedAt;
        List<String> labels = new ArrayList<>();
    }

    private static void parseKeepSingle(Context ctx, Uri uri, Result r) throws Exception {
        KeepNote k = parseKeepJson(new String(readAll(ctx, uri, MAX_BYTES), "UTF-8"));
        insertKeep(ctx, k, r);
    }

    private static void parseKeepZip(Context ctx, Uri uri, Result r) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            while ((e = zin.getNextEntry()) != null
                    && r.imported + r.skipped < MAX_NOTES) {
                if (e.isDirectory() || !e.getName().toLowerCase(Locale.US).endsWith(".json")) {
                    zin.closeEntry();
                    continue;
                }
                buf.reset();
                long total = 0;
                int n;
                while ((n = zin.read(tmp)) != -1) {
                    total += n;
                    if (total > MAX_BYTES) break;
                    buf.write(tmp, 0, n);
                }
                zin.closeEntry();
                if (total > MAX_BYTES) {
                    r.skipped++;
                    continue;
                }
                try {
                    KeepNote k = parseKeepJson(buf.toString("UTF-8"));
                    insertKeep(ctx, k, r);
                } catch (Exception ex) {
                    r.skipped++;
                }
            }
        }
    }

    /** Format ekspor Google Takeout Keep (satu .json per catatan). */
    private static KeepNote parseKeepJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        // Validasi longgar: abaikan file JSON yang jelas bukan Keep.
        if (!o.has("textContent") && !o.has("textContentHtml")
                && !o.has("listContent") && !o.has("title")) {
            throw new Exception("bukan format Google Keep");
        }
        KeepNote k = new KeepNote();
        k.title = o.optString("title", "");
        k.archived = o.optBoolean("isArchived", false);
        k.trashed = o.optBoolean("isTrashed", false);
        k.createdAt = o.optLong("createdTimestampUsec", 0) / 1000;
        long editedUsec = o.optLong("userEditedTimestampUsec", 0);
        k.updatedAt = editedUsec != 0 ? editedUsec / 1000 : k.createdAt;

        JSONArray list = o.optJSONArray("listContent");
        if (list != null && list.length() > 0) {
            k.checklist = true;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                sb.append(item.optBoolean("isChecked", false) ? "- [x] " : "- [ ] ");
                sb.append(item.optString("text", "")).append('\n');
            }
            k.text = sb.toString().trim();
        } else {
            k.text = o.optString("textContent", "");
            if (k.text.isEmpty()) {
                // Fallback: kupas tag dari HTML.
                k.text = o.optString("textContentHtml", "")
                        .replaceAll("(?i)<br\\s*/?>", "\n")
                        .replaceAll("(?i)</(div|p|li)>", "\n")
                        .replaceAll("<[^>]+>", "")
                        .replace("&nbsp;", " ").trim();
            }
        }
        JSONArray labels = o.optJSONArray("labels");
        if (labels != null) {
            for (int i = 0; i < labels.length(); i++) {
                String name = labels.getJSONObject(i).optString("name", "").trim();
                if (!name.isEmpty()) k.labels.add(name);
            }
        }
        if (k.title.trim().isEmpty() && k.text.trim().isEmpty()) {
            throw new Exception("catatan kosong");
        }
        return k;
    }

    private static void insertKeep(Context ctx, KeepNote k, Result r) {
        try {
            NoteDao noteDao = AppDatabase.get(ctx.getApplicationContext()).noteDao();
            TagDao tagDao = AppDatabase.get(ctx.getApplicationContext()).tagDao();
            NoteEntity n = new NoteEntity();
            n.title = k.title.trim();
            n.content = k.text.trim();
            n.isChecklist = k.checklist;
            n.archived = k.archived;
            n.trashed = k.trashed;
            n.createdAt = k.createdAt;
            n.updatedAt = k.updatedAt != 0 ? k.updatedAt : k.createdAt;
            long id = noteDao.insert(n);
            insertTags(tagDao, id, k.labels);
            r.imported++;
        } catch (Exception e) {
            r.skipped++;
        }
    }

    private static void insertTags(TagDao tagDao, long noteId, List<String> names) {
        if (names == null) return;
        List<String> seen = new ArrayList<>();
        for (String raw : names) {
            if (raw == null) continue;
            String name = raw.trim();
            if (name.isEmpty() || seen.contains(name)) continue;
            seen.add(name);
            TagEntity t = tagDao.byName(name);
            long tagId;
            if (t != null) tagId = t.id;
            else {
                TagEntity nt = new TagEntity();
                nt.name = name;
                tagId = tagDao.insert(nt);
                if (tagId == -1) {
                    TagEntity again = tagDao.byName(name);
                    if (again == null) continue;
                    tagId = again.id;
                }
            }
            tagDao.insertCrossRef(new NoteTagCrossRef(noteId, tagId));
        }
    }
}
