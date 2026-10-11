package com.hozinking.notes.backup;

import android.content.Context;
import android.net.Uri;

import com.hozinking.notes.data.db.FolderEntity;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.db.TagEntity;
import com.hozinking.notes.data.db.AudioRefEntity;
import com.hozinking.notes.data.db.VaultDatabase;
import com.hozinking.notes.data.db.VaultImageEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.security.CryptoManager;
import com.hozinking.notes.security.HashUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup lokal: satu file ZIP berisi:
 *   notes.json   {version, exportedAt, notes[], folders[], tags[],
 *                 noteTags[{noteId,tagId}], images[{id,noteId,sha256,createdAt,file,thumb}]}
 *   images/      file gambar TERENKRIPSI apa adanya (img_*.bin, th_*.bin)
 *   vault/       ruang privat: vault.db (file SQLCipher, TETAP terenkripsi —
 *                TIDAK PERNAH didekripsi, passphrase tidak ikut dibackup),
 *                vault_files.json (peta file gambar vault), vault/files/ (gambar
 *                vault terenkripsi)
 *   manifest.json {files:[{path, sha256}]} — untuk verifikasi integritas saat impor
 *
 * Semua method berat BLOCKING dan jalan di background thread. Repository yang
 * async disinkronkan via CountDownLatch (tidak pernah dipanggil dari main thread).
 */
public class LocalBackupManager {

    public interface Progress { void onProgress(int done, int total); }
    public interface Cb<T> { void onResult(T v); }

    public static class ImportResult {
        public boolean ok;
        public int notes;
        public int images;
        public int audio;
        /** true bila backup ini memulihkan ruang privat (vault). */
        public boolean vault;
        public String error;
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final int BACKUP_VERSION = 1;

    private interface Getter<T> { void get(NoteRepository.Cb<T> cb); }

    private static class ImageEntry {
        final String path; final byte[] data;
        ImageEntry(String p, byte[] d) { path = p; data = d; }
    }

    /** Tunggu callback repository secara sinkron (maks 60 dtk). */
    private static <T> T await(Getter<T> g) throws Exception {
        final Object[] box = new Object[1];
        CountDownLatch latch = new CountDownLatch(1);
        g.get(v -> { box[0] = v; latch.countDown(); });
        if (!latch.await(60, TimeUnit.SECONDS)) throw new Exception("Timeout menunggu database");
        @SuppressWarnings("unchecked") T t = (T) box[0];
        return t;
    }

    private static File resolveImageFile(Context ctx, String path) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        return f.isAbsolute() ? f : new File(ctx.getFilesDir(), path);
    }

    /** Ambil byte TERENKRIPSI gambar; fallback: dekripsi via repo lalu enkripsi ulang. */
    private static byte[] readEncryptedImage(Context ctx, NoteRepository repo, ImageRefEntity img) throws Exception {
        File f = resolveImageFile(ctx, img.encPath);
        if (f != null && f.isFile()) return readBytes(new FileInputStream(f));
        byte[] plain = await(cb -> repo.loadImagePlain(img, cb));
        if (plain == null) return null;
        return CryptoManager.get(ctx).encrypt(plain);
    }

    private static byte[] readThumbBytes(Context ctx, ImageRefEntity img) throws Exception {
        File f = resolveImageFile(ctx, img.thumbPath);
        if (f != null && f.isFile()) return readBytes(new FileInputStream(f));
        return null;
    }

    /**
     * Bangun file ZIP backup di cache dir. BLOCKING — panggil dari Worker/executor.
     */
    public static File buildBackupZip(Context ctx) throws Exception {
        Context app = ctx.getApplicationContext();
        NoteRepository repo = NoteRepository.get(ctx);

        List<FolderEntity> folders = await(cb -> repo.getFolders(cb));
        List<TagEntity> tags = await(cb -> repo.getTags(cb));

        Map<Long, NoteEntity> notes = new LinkedHashMap<>();
        List<NoteEntity> allNotes = await(cb -> repo.getAllNotesForBackup(cb));
        if (allNotes != null) for (NoteEntity n : allNotes) notes.put(n.id, n);

        JSONObject root = new JSONObject();
        root.put("version", BACKUP_VERSION);
        root.put("exportedAt", System.currentTimeMillis());

        JSONArray jFolders = new JSONArray();
        if (folders != null) for (FolderEntity f : folders) {
            jFolders.put(new JSONObject()
                    .put("id", f.id).put("name", f.name)
                    .put("color", f.color).put("createdAt", f.createdAt));
        }
        root.put("folders", jFolders);

        JSONArray jTags = new JSONArray();
        if (tags != null) for (TagEntity t : tags) {
            jTags.put(new JSONObject().put("id", t.id).put("name", t.name));
        }
        root.put("tags", jTags);

        JSONArray jNotes = new JSONArray();
        JSONArray jNoteTags = new JSONArray();
        JSONArray jImages = new JSONArray();
        JSONArray jAudio = new JSONArray();
        List<ImageEntry> imageEntries = new ArrayList<>();

        int imgIdx = 0;
        int audIdx = 0;
        for (NoteEntity n : notes.values()) {
            JSONObject o = new JSONObject();
            o.put("id", n.id);
            o.put("title", n.title == null ? "" : n.title);
            o.put("content", n.content == null ? "" : n.content);
            if (n.folderId == null) o.put("folderId", JSONObject.NULL);
            else o.put("folderId", n.folderId.longValue());
            o.put("color", n.color);
            o.put("pinned", n.pinned);
            o.put("archived", n.archived);
            o.put("trashed", n.trashed);
            o.put("isChecklist", n.isChecklist);
            o.put("imageCount", n.imageCount);
            o.put("createdAt", n.createdAt);
            o.put("updatedAt", n.updatedAt);
            jNotes.put(o);

            List<TagEntity> nt = await(cb -> repo.getTagsForNote(n.id, cb));
            if (nt != null) for (TagEntity t : nt) {
                jNoteTags.put(new JSONObject().put("noteId", n.id).put("tagId", t.id));
            }

            List<ImageRefEntity> imgs = await(cb -> repo.getImages(n.id, cb));
            if (imgs != null) for (ImageRefEntity img : imgs) {
                byte[] data = readEncryptedImage(app, repo, img);
                if (data == null) continue; // file hilang: lewati, jangan gagalkan backup
                String rel = "images/img_" + imgIdx + ".bin";
                byte[] thumbData = readThumbBytes(app, img);
                String thumbRel = thumbData != null ? "images/th_" + imgIdx + ".bin" : null;
                imgIdx++;
                imageEntries.add(new ImageEntry(rel, data));
                if (thumbRel != null) imageEntries.add(new ImageEntry(thumbRel, thumbData));
                JSONObject io = new JSONObject();
                io.put("id", img.id);
                io.put("noteId", img.noteId);
                io.put("sha256", HashUtils.sha256Hex(data));
                io.put("createdAt", img.createdAt);
                io.put("file", rel);
                if (thumbRel == null) io.put("thumb", JSONObject.NULL);
                else io.put("thumb", thumbRel);
                jImages.put(io);
            }

            // Rekaman suara: file terenkripsi disalin apa adanya ke audio/aud_N.bin.
            List<AudioRefEntity> auds = await(cb -> repo.getAudioRefs(n.id, cb));
            if (auds != null) for (AudioRefEntity a : auds) {
                File af = (a.encPath == null || a.encPath.isEmpty())
                        ? null : new File(a.encPath);
                if (af == null || !af.isFile()) continue; // file hilang: lewati
                byte[] data = readBytes(new FileInputStream(af));
                String rel = "audio/aud_" + audIdx + ".bin";
                audIdx++;
                imageEntries.add(new ImageEntry(rel, data));
                JSONObject ao = new JSONObject();
                ao.put("id", a.id);
                ao.put("noteId", a.noteId);
                ao.put("durationMs", a.durationMs);
                ao.put("createdAt", a.createdAt);
                ao.put("file", rel);
                jAudio.put(ao);
            }
        }
        root.put("notes", jNotes);
        root.put("noteTags", jNoteTags);
        root.put("images", jImages);
        root.put("audio", jAudio);

        File zip = new File(app.getCacheDir(), "hozin_notes_" + System.currentTimeMillis() + ".zip");
        JSONArray manifestFiles = new JSONArray();
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
            byte[] notesBytes = root.toString().getBytes("UTF-8");
            addZipEntry(zos, "notes.json", notesBytes);
            manifestFiles.put(new JSONObject()
                    .put("path", "notes.json").put("sha256", HashUtils.sha256Hex(notesBytes)));
            for (ImageEntry ie : imageEntries) {
                addZipEntry(zos, ie.path, ie.data);
                manifestFiles.put(new JSONObject()
                        .put("path", ie.path).put("sha256", HashUtils.sha256Hex(ie.data)));
            }
            // ---------- Ruang privat: file SQLCipher apa adanya, TETAP terenkripsi ----------
            addVaultToZip(app, zos, manifestFiles);
            JSONObject manifest = new JSONObject().put("files", manifestFiles);
            addZipEntry(zos, "manifest.json", manifest.toString().getBytes("UTF-8"));
        }
        return zip;
    }

    /**
     * Sertakan ruang privat ke dalam ZIP. File vault.db disalin APA ADANYA
     * (SQLCipher — tetap terenkripsi, tidak pernah didekripsi); passphrase
     * TIDAK ikut dibackup (tetap di Android Keystore perangkat ini).
     * File gambar vault (bila ada) ikut disertakan tetap terenkripsi.
     */
    private static void addVaultToZip(Context app, ZipOutputStream zos,
                                     JSONArray manifestFiles) throws Exception {
        File dbFile = VaultDatabase.getDbFile(app);
        if (dbFile == null || !dbFile.isFile()) return; // vault belum pernah dipakai
        byte[] dbBytes = readBytes(new FileInputStream(dbFile));
        addZipEntry(zos, "vault/vault.db", dbBytes);
        manifestFiles.put(new JSONObject()
                .put("path", "vault/vault.db").put("sha256", HashUtils.sha256Hex(dbBytes)));

        List<VaultImageEntity> vImgs;
        try {
            vImgs = VaultDatabase.get(app).vaultDao().allImages();
        } catch (Exception e) {
            vImgs = null; // vault bermasalah: vault.db sudah dibackup, gambar dilewati
        }
        if (vImgs == null || vImgs.isEmpty()) return;

        String filesDir = app.getFilesDir().getCanonicalPath() + File.separator;
        JSONArray jv = new JSONArray();
        int idx = 0;
        for (VaultImageEntity e : vImgs) {
            for (String srcPath : new String[]{e.encPath, e.thumbPath}) {
                if (srcPath == null || srcPath.isEmpty()) continue;
                File src = new File(srcPath);
                if (!src.isFile()) continue;
                // cegah path traversal: hanya file di dalam filesDir aplikasi
                if (!src.getCanonicalPath().startsWith(filesDir)) continue;
                String name = "vault/files/vf_" + idx + "_" + src.getName();
                idx++;
                byte[] data = readBytes(new FileInputStream(src));
                addZipEntry(zos, name, data);
                manifestFiles.put(new JSONObject()
                        .put("path", name).put("sha256", HashUtils.sha256Hex(data)));
                jv.put(new JSONObject().put("src", srcPath).put("zip", name));
            }
        }
        byte[] mapBytes = new JSONObject().put("files", jv).toString().getBytes("UTF-8");
        addZipEntry(zos, "vault/vault_files.json", mapBytes);
        manifestFiles.put(new JSONObject()
                .put("path", "vault/vault_files.json")
                .put("sha256", HashUtils.sha256Hex(mapBytes)));
    }

    /** Ekspor backup ke Uri tujuan (mis. dari system picker). Async via executor. */
    public static void exportZip(Context ctx, Uri destUri, Progress p, Cb<Boolean> cb) {
        EXEC.execute(() -> {
            File zip = null;
            try {
                if (p != null) p.onProgress(0, 100);
                zip = buildBackupZip(ctx);
                if (p != null) p.onProgress(70, 100);
                try (InputStream in = new FileInputStream(zip);
                     OutputStream out = ctx.getContentResolver().openOutputStream(destUri)) {
                    if (out == null) throw new Exception("Tidak bisa membuka tujuan");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (p != null) p.onProgress(100, 100);
                cb.onResult(true);
            } catch (Exception e) {
                cb.onResult(false);
            } finally {
                if (zip != null) zip.delete();
            }
        });
    }

    /**
     * Impor backup dari Uri zip. overwrite=true: reset DB dulu lalu pakai id asli;
     * overwrite=false (merge): semua id baru, relasi di-remap. Async via executor.
     */
    public static void importZip(Context ctx, Uri zipUri, boolean overwrite, Progress p, Cb<ImportResult> cb) {
        EXEC.execute(() -> {
            ImportResult r = new ImportResult();
            File dir = new File(ctx.getCacheDir(), "nb_import_" + System.currentTimeMillis());
            try {
                if (p != null) p.onProgress(0, 100);
                unzipTo(ctx, zipUri, dir);
                if (p != null) p.onProgress(20, 100);
                verifyManifest(dir);
                JSONObject root = new JSONObject(readUtf8(new File(dir, "notes.json")));
                if (root.optInt("version", -1) != BACKUP_VERSION)
                    throw new Exception("Versi backup tidak didukung: " + root.optInt("version", -1));
                if (p != null) p.onProgress(30, 100);

                // ---------- Ruang privat: REPLACE penuh dari backup (bukan merge) ----------
                // Dilakukan SEBELUM impor catatan biasa: bila gagal, catatan tidak tersentuh.
                if (new File(dir, "vault/vault.db").isFile()) {
                    restoreVault(ctx, dir);
                    r.vault = true;
                }
                if (p != null) p.onProgress(32, 100);

                NoteRepository repo = NoteRepository.get(ctx);
                if (overwrite) LocalBackupManager.<Void>await(cb2 -> repo.resetAll(cb2));

                // folders: samakan by name, petakan id lama -> id baru
                Map<String, Long> nameToId = new HashMap<>();
                List<FolderEntity> existing = await(cb2 -> repo.getFolders(cb2));
                if (existing != null) for (FolderEntity f : existing) nameToId.put(f.name, f.id);
                Map<Long, Long> folderMap = new HashMap<>();
                JSONArray jFolders = root.optJSONArray("folders");
                if (jFolders != null) for (int i = 0; i < jFolders.length(); i++) {
                    JSONObject o = jFolders.getJSONObject(i);
                    long oldId = o.getLong("id");
                    String name = o.getString("name");
                    int color = o.optInt("color", 0);
                    Long nid = nameToId.get(name);
                    if (nid == null) {
                        final String fn = name; final int fc = color;
                        nid = await(cb2 -> repo.insertFolder(fn, fc, cb2));
                        nameToId.put(name, nid);
                    }
                    folderMap.put(oldId, nid);
                }
                if (p != null) p.onProgress(40, 100);

                // tagId lama -> nama
                Map<Long, String> tagNames = new HashMap<>();
                JSONArray jTags = root.optJSONArray("tags");
                if (jTags != null) for (int i = 0; i < jTags.length(); i++) {
                    JSONObject o = jTags.getJSONObject(i);
                    tagNames.put(o.getLong("id"), o.getString("name"));
                }
                // noteId lama -> daftar nama tag
                Map<Long, List<String>> noteTagNames = new HashMap<>();
                JSONArray jNoteTags = root.optJSONArray("noteTags");
                if (jNoteTags != null) for (int i = 0; i < jNoteTags.length(); i++) {
                    JSONObject o = jNoteTags.getJSONObject(i);
                    String tn = tagNames.get(o.getLong("tagId"));
                    if (tn == null) continue;
                    long nidOld = o.getLong("noteId");
                    List<String> l = noteTagNames.get(nidOld);
                    if (l == null) { l = new ArrayList<>(); noteTagNames.put(nidOld, l); }
                    if (!l.contains(tn)) l.add(tn);
                }

                // notes
                Map<Long, Long> noteMap = new HashMap<>();
                JSONArray jNotes = root.optJSONArray("notes");
                int total = jNotes == null ? 0 : jNotes.length();
                for (int i = 0; i < total; i++) {
                    JSONObject o = jNotes.getJSONObject(i);
                    long oldId = o.getLong("id");
                    NoteEntity ne = new NoteEntity();
                    ne.id = overwrite ? oldId : 0;
                    ne.title = o.optString("title", "");
                    ne.content = o.optString("content", "");
                    ne.folderId = o.isNull("folderId") ? null : folderMap.get(o.getLong("folderId"));
                    ne.color = o.optInt("color", 0);
                    ne.pinned = o.optBoolean("pinned", false);
                    ne.archived = o.optBoolean("archived", false);
                    ne.trashed = o.optBoolean("trashed", false);
                    ne.isChecklist = o.optBoolean("isChecklist", false);
                    ne.imageCount = 0;
                    long now = System.currentTimeMillis();
                    ne.createdAt = o.optLong("createdAt", now);
                    ne.updatedAt = o.optLong("updatedAt", now);
                    final NoteEntity neF = ne;
                    Long newId = await(cb2 -> repo.insertNote(neF, cb2));
                    if (newId == null || newId <= 0)
                        throw new Exception("insertNote gagal (id lama " + oldId + ")");
                    noteMap.put(oldId, newId);
                    r.notes++;
                    List<String> tnames = noteTagNames.get(oldId);
                    if (tnames != null && !tnames.isEmpty()) {
                        final long nidF = newId;
                        final List<String> tnF = tnames;
                        LocalBackupManager.<Void>await(cb2 -> repo.setTagsForNote(nidF, tnF, cb2));
                    }
                    if (p != null && total > 0) p.onProgress(40 + (i + 1) * 40 / total, 100);
                }

                // images: salin file terenkripsi ke filesDir/images/ nama unik, remap noteId
                File imgDir = new File(ctx.getFilesDir(), "images");
                imgDir.mkdirs();
                Map<Long, Integer> imgCountPerNote = new HashMap<>();
                JSONArray jImages = root.optJSONArray("images");
                if (jImages != null) for (int i = 0; i < jImages.length(); i++) {
                    JSONObject o = jImages.getJSONObject(i);
                    Long newNoteId = noteMap.get(o.getLong("noteId"));
                    if (newNoteId == null) continue;
                    String rel = o.optString("file", "");
                    if (rel.isEmpty()) continue;
                    File src = new File(dir, rel);
                    if (!src.isFile()) continue;
                    File dst = new File(imgDir, "img_" + System.currentTimeMillis() + "_" + i + ".bin");
                    copy(new FileInputStream(src), new FileOutputStream(dst));
                    String thumbPath = "";
                    String thumbRel = o.optString("thumb", "");
                    if (!thumbRel.isEmpty()) {
                        File tsrc = new File(dir, thumbRel);
                        if (tsrc.isFile()) {
                            File tdst = new File(imgDir,
                                    "th_" + System.currentTimeMillis() + "_" + i + ".bin");
                            copy(new FileInputStream(tsrc), new FileOutputStream(tdst));
                            thumbPath = tdst.getAbsolutePath();
                        }
                    }
                    ImageRefEntity ref = new ImageRefEntity();
                    ref.noteId = newNoteId;
                    ref.encPath = dst.getAbsolutePath();
                    ref.thumbPath = thumbPath;
                    ref.sha256 = HashUtils.sha256Hex(dst);
                    ref.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                    insertImageRef(repo, ref); // throws bila kontrak belum menyediakan
                    imgCountPerNote.put(newNoteId, imgCountPerNote.getOrDefault(newNoteId, 0) + 1);
                    r.images++;
                }
                for (Map.Entry<Long, Integer> e : imgCountPerNote.entrySet()) {
                    final long nidF = e.getKey();
                    NoteEntity cur = await(cb2 -> repo.getNote(nidF, cb2));
                    if (cur != null) {
                        cur.imageCount = e.getValue();
                        final NoteEntity curF = cur;
                        LocalBackupManager.<Void>await(cb2 -> repo.updateNote(curF, cb2));
                    }
                }

                // audio: salin file terenkripsi ke filesDir/audio/ nama unik, remap noteId
                File audDir = new File(ctx.getFilesDir(), "audio");
                audDir.mkdirs();
                JSONArray jAudio = root.optJSONArray("audio");
                if (jAudio != null) for (int i = 0; i < jAudio.length(); i++) {
                    JSONObject o = jAudio.getJSONObject(i);
                    Long newNoteId = noteMap.get(o.getLong("noteId"));
                    if (newNoteId == null) continue;
                    String rel = o.optString("file", "");
                    if (rel.isEmpty()) continue;
                    File src = new File(dir, rel);
                    if (!src.isFile()) continue;
                    File dst = new File(audDir,
                            "aud_" + System.currentTimeMillis() + "_" + i + ".bin");
                    copy(new FileInputStream(src), new FileOutputStream(dst));
                    AudioRefEntity ref = new AudioRefEntity();
                    ref.noteId = newNoteId;
                    ref.encPath = dst.getAbsolutePath();
                    ref.durationMs = o.optLong("durationMs", 0);
                    ref.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                    final AudioRefEntity refF = ref;
                    LocalBackupManager.<Long>await(cb2 -> repo.insertAudioRef(refF, cb2));
                    r.audio++;
                }

                if (p != null) p.onProgress(100, 100);
                r.ok = true;
            } catch (Exception e) {
                r.ok = false;
                r.error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                deleteRecursive(dir);
            }
            cb.onResult(r);
        });
    }

    /**
     * Registrasi ImageRefEntity hasil impor.
     * GAP KONTRAK (2026-10-05): NoteRepository belum punya method insert untuk
     * ImageRefEntity. Dicoba via refleksi ke nama konvensional agar tetap kompilasi
     * dan langsung jalan begitu data-layer menambahkannya,
     * mis. {@code insertImageRef(ImageRefEntity, Cb<Long>)}.
     */
    private static void insertImageRef(NoteRepository repo, ImageRefEntity ref) throws Exception {
        String[] names = {"insertImageRef", "addImageRef", "insertImage"};
        for (String name : names) {
            try {
                Method m = repo.getClass().getMethod(name, ImageRefEntity.class, NoteRepository.Cb.class);
                CountDownLatch latch = new CountDownLatch(1);
                NoteRepository.Cb<Object> cb = v -> latch.countDown();
                m.invoke(repo, ref, cb);
                if (!latch.await(30, TimeUnit.SECONDS)) throw new Exception("Timeout insert image");
                return;
            } catch (NoSuchMethodException ignored) {
                // coba nama berikutnya
            }
        }
        throw new Exception("GAP KONTRAK: NoteRepository tidak punya method insert ImageRefEntity "
                + "(tambahkan mis. insertImageRef(ImageRefEntity, Cb<Long>)). "
                + "File gambar sudah disalin ke filesDir/images/, tapi referensinya belum masuk DB.");
    }

    private static void unzipTo(Context ctx, Uri uri, File dir) throws Exception {
        dir.mkdirs();
        String canonicalDir = dir.getCanonicalPath() + File.separator;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("Tidak bisa membuka file zip");
            try (ZipInputStream zin = new ZipInputStream(in)) {
                ZipEntry e;
                byte[] buf = new byte[8192];
                while ((e = zin.getNextEntry()) != null) {
                    File out = new File(dir, e.getName());
                    if (!out.getCanonicalPath().startsWith(canonicalDir))
                        throw new Exception("Entri zip tidak valid: " + e.getName());
                    if (e.isDirectory()) {
                        out.mkdirs();
                    } else {
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (OutputStream o = new FileOutputStream(out)) {
                            int n;
                            while ((n = zin.read(buf)) > 0) o.write(buf, 0, n);
                        }
                    }
                    zin.closeEntry();
                }
            }
        }
    }

    /**
     * Pulihkan ruang privat dengan semantik REPLACE: file vault.db dari backup
     * menimpa vault.db aktif. Koneksi vault ditutup dulu; sha256 dari manifest
     * (sudah diverifikasi SEBELUM pemakaian) dicek ulang pada hasil salinan —
     * tidak cocok = restore DIBATALKAN. Passphrase tidak pernah ditulis ke
     * backup/log; kunci tetap di Android Keystore.
     */
    private static void restoreVault(Context ctx, File dir) throws Exception {
        Context app = ctx.getApplicationContext();
        // 1. tutup koneksi vault yang sedang aktif
        VaultDatabase.closeAndReset(app);
        // 2. timpa file vault.db (integritas sudah lolos verifyManifest)
        File src = new File(dir, "vault/vault.db");
        File dst = VaultDatabase.getDbFile(app);
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        // buang file jurnal sisa agar tidak bentrok dengan file baru
        new File(dst.getPath() + "-wal").delete();
        new File(dst.getPath() + "-shm").delete();
        new File(dst.getPath() + "-journal").delete();
        copy(new FileInputStream(src), new FileOutputStream(dst));
        // 3. verifikasi ulang hasil salinan sebelum dipakai
        String expected = manifestSha(dir, "vault/vault.db");
        if (expected == null || !HashUtils.verify(dst, expected)) {
            dst.delete();
            throw new Exception("Checksum vault.db tidak cocok — restore ruang privat dibatalkan");
        }
        // 4. sambungkan ulang DAO vault ke file baru
        NoteRepository.get(ctx).refreshVault();
        // 5. kembalikan file gambar vault (best-effort: dilewati bila bermasalah)
        File map = new File(dir, "vault/vault_files.json");
        if (map.isFile()) {
            String filesDir = app.getFilesDir().getCanonicalPath() + File.separator;
            JSONArray files = new JSONObject(readUtf8(map)).optJSONArray("files");
            if (files != null) for (int i = 0; i < files.length(); i++) {
                try {
                    JSONObject f = files.getJSONObject(i);
                    String srcPath = f.optString("src", "");
                    String zipPath = f.optString("zip", "");
                    if (srcPath.isEmpty() || zipPath.isEmpty()) continue;
                    File zf = new File(dir, zipPath);
                    if (!zf.isFile()) continue;
                    File target = new File(srcPath);
                    // cegah path traversal: hanya di dalam filesDir aplikasi
                    if (!target.getCanonicalPath().startsWith(filesDir)) continue;
                    File tp = target.getParentFile();
                    if (tp != null) tp.mkdirs();
                    copy(new FileInputStream(zf), new FileOutputStream(target));
                } catch (Exception ignored) {
                    // satu file gambar gagal: lanjutkan yang lain
                }
            }
        }
    }

    /** Ambil sha256 satu entri dari manifest.json. */
    private static String manifestSha(File dir, String path) throws Exception {
        JSONObject m = new JSONObject(readUtf8(new File(dir, "manifest.json")));
        JSONArray files = m.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            if (path.equals(f.optString("path", ""))) return f.getString("sha256");
        }
        return null;
    }

    /** Verifikasi sha256 tiap file di manifest — MISMATCH = gagal total. */
    private static void verifyManifest(File dir) throws Exception {
        File mf = new File(dir, "manifest.json");
        if (!mf.isFile()) throw new Exception("manifest.json tidak ditemukan — bukan backup Hozin Notes");
        JSONObject m = new JSONObject(readUtf8(mf));
        JSONArray files = m.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            String path = f.getString("path");
            String sha = f.getString("sha256");
            File target = new File(dir, path);
            if (!target.isFile()) throw new Exception("File hilang di dalam zip: " + path);
            if (!HashUtils.verify(target, sha))
                throw new Exception("Checksum tidak cocok (rusak/dimodifikasi): " + path);
        }
    }

    private static void addZipEntry(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] readBytes(InputStream in) throws Exception {
        try (InputStream i = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static String readUtf8(File f) throws Exception {
        return new String(readBytes(new FileInputStream(f)), "UTF-8");
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }
}
