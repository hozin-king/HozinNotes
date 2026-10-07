package com.hozinking.notes.data.repo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.paging.PagingSource;

import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.db.FolderDao;
import com.hozinking.notes.data.db.FolderEntity;
import com.hozinking.notes.data.db.ImageDao;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.db.NoteDao;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.db.NoteTagCrossRef;
import com.hozinking.notes.data.db.TagDao;
import com.hozinking.notes.data.db.TagEntity;
import com.hozinking.notes.data.db.VaultDao;
import com.hozinking.notes.data.db.VaultDatabase;
import com.hozinking.notes.data.db.VaultImageEntity;
import com.hozinking.notes.data.db.VaultNoteEntity;
import com.hozinking.notes.security.CryptoManager;
import com.hozinking.notes.security.HashUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Satu pintu akses data. Semua operasi tulis/baca (kecuali PagingSource mentah)
 * berjalan di thread pool; callback dipanggil di thread executor yang sama
 * (lapisan UI yang memindahkan ke main thread bila perlu).
 */
public class NoteRepository {
    private static final long MAX_IMAGE_BYTES = 12L * 1024 * 1024; // 12 MB
    private static final long MAX_VIDEO_BYTES = 25L * 1024 * 1024; // 25 MB (live ~3 dtk)
    private static final long TRASH_RETENTION_MS = 30L * 24 * 3600 * 1000; // 30 hari
    private static final int THUMB_MAX_PX = 256;
    private static final int THUMB_JPEG_QUALITY = 80;

    private static volatile NoteRepository instance;

    /** Callback sederhana; dipanggil di thread executor. */
    public interface Cb<T> {
        void onResult(T v);
    }

    private final Context appCtx;
    private final NoteDao noteDao;
    private final ImageDao imageDao;
    private final FolderDao folderDao;
    private final TagDao tagDao;
    private VaultDao vaultDao;
    private final ExecutorService io = Executors.newFixedThreadPool(4);

    private NoteRepository(Context ctx) {
        appCtx = ctx.getApplicationContext();
        AppDatabase db = AppDatabase.get(appCtx);
        noteDao = db.noteDao();
        imageDao = db.imageDao();
        folderDao = db.folderDao();
        tagDao = db.tagDao();
        vaultDao = VaultDatabase.get(appCtx).vaultDao();
    }

    public static NoteRepository get(Context ctx) {
        if (instance == null) {
            synchronized (NoteRepository.class) {
                if (instance == null) {
                    instance = new NoteRepository(ctx);
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------------ notes

    public void insertNote(NoteEntity n, Cb<Long> cb) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            if (n.createdAt == 0) n.createdAt = now;
            if (n.updatedAt == 0) n.updatedAt = now;
            cb.onResult(noteDao.insert(n));
        });
    }

    public void updateNote(NoteEntity n, Cb<Void> cb) {
        io.execute(() -> {
            n.updatedAt = System.currentTimeMillis();
            noteDao.update(n);
            cb.onResult(null);
        });
    }

    public void getNote(long id, Cb<NoteEntity> cb) {
        io.execute(() -> cb.onResult(noteDao.getById(id)));
    }

    /** Semua catatan (isi penuh) untuk kebutuhan backup — bukan paging. */
    public void getAllNotesForBackup(Cb<List<NoteEntity>> cb) {
        io.execute(() -> cb.onResult(noteDao.getAllForBackup()));
    }

    public void trashNote(long id, Cb<Void> cb) {
        io.execute(() -> {
            noteDao.trash(id, System.currentTimeMillis());
            cb.onResult(null);
        });
    }

    public void restoreNote(long id, Cb<Void> cb) {
        io.execute(() -> {
            noteDao.restore(id, System.currentTimeMillis());
            cb.onResult(null);
        });
    }

    /**
     * Duplikat catatan: salin judul (" (salinan)"), isi, format, folder,
     * warna &amp; tag. Gambar, pengingat, status arsip/semat/kunci tidak ikut.
     */
    public void duplicateNote(long id, Cb<Long> cb) {
        io.execute(() -> {
            try {
                NoteEntity src = noteDao.getById(id);
                if (src == null || src.trashed) {
                    cb.onResult(-1L);
                    return;
                }
                NoteEntity n = new NoteEntity();
                String t = src.title == null ? "" : src.title.trim();
                n.title = t.isEmpty() ? "(salinan)" : t + " (salinan)";
                n.content = src.content;
                n.contentHtml = src.contentHtml;
                n.isChecklist = src.isChecklist;
                n.folderId = src.folderId;
                n.color = src.color;
                n.pinned = false;
                n.archived = false;
                n.trashed = false;
                n.locked = false;
                n.reminderTime = 0;
                n.imageCount = 0;
                long now = System.currentTimeMillis();
                n.createdAt = now;
                n.updatedAt = now;
                long newId = noteDao.insert(n);
                List<TagEntity> tags = tagDao.forNote(id);
                if (tags != null) {
                    for (TagEntity te : tags) {
                        if (te == null || te.name == null || te.name.trim().isEmpty()) continue;
                        TagEntity tag = tagDao.byName(te.name);
                        long tagId;
                        if (tag != null) {
                            tagId = tag.id;
                        } else {
                            TagEntity fresh = new TagEntity();
                            fresh.name = te.name;
                            tagId = tagDao.insert(fresh);
                            if (tagId == -1) continue;
                        }
                        tagDao.insertCrossRef(new NoteTagCrossRef(newId, tagId));
                    }
                }
                cb.onResult(newId);
            } catch (Exception e) {
                cb.onResult(-1L);
            }
        });
    }

    /** Hapus permanen: file gambar dulu, lalu baris images, lalu note. */
    public void deleteForever(long id, Cb<Void> cb) {
        io.execute(() -> {
            List<ImageRefEntity> refs = imageDao.forNote(id);
            for (ImageRefEntity r : refs) {
                deleteFileQuiet(r.encPath);
                deleteFileQuiet(r.thumbPath);
            }
            imageDao.deleteForNote(id);
            noteDao.deleteForever(id);
            cb.onResult(null);
        });
    }

    /** Hapus permanen semua note di trash yang lebih tua dari 30 hari. */
    public void purgeTrash(Cb<Integer> cb) {
        io.execute(() -> {
            long cutoff = System.currentTimeMillis() - TRASH_RETENTION_MS;
            cb.onResult(noteDao.purgeTrash(cutoff));
        });
    }

    /** Kosongkan semua tabel (untuk import timpa). */
    public void resetAll(Cb<Void> cb) {
        io.execute(() -> {
            tagDao.clearCrossRefs();
            noteDao.clearAll();
            imageDao.clearAll();
            folderDao.clearAll();
            tagDao.clearAll();
            cb.onResult(null);
        });
    }

    // -------------------------------------------------------------- paging raw

    public PagingSource<Integer, NoteEntity> pagingActive() {
        return noteDao.pagingActive();
    }

    public PagingSource<Integer, NoteEntity> pagingActiveOldest() {
        return noteDao.pagingActiveOldest();
    }

    public PagingSource<Integer, NoteEntity> pagingActiveTitle() {
        return noteDao.pagingActiveTitle();
    }

    public PagingSource<Integer, NoteEntity> pagingActiveColor() {
        return noteDao.pagingActiveColor();
    }

    public PagingSource<Integer, NoteEntity> pagingArchived() {
        return noteDao.pagingArchived();
    }

    public PagingSource<Integer, NoteEntity> pagingTrashed() {
        return noteDao.pagingTrashed();
    }

    public PagingSource<Integer, NoteEntity> pagingByFolder(long folderId) {
        return noteDao.pagingByFolder(folderId);
    }

    public PagingSource<Integer, NoteEntity> pagingByTag(long tagId) {
        return noteDao.pagingByTag(tagId);
    }

    public PagingSource<Integer, NoteEntity> pagingByTagOldest(long tagId) {
        return noteDao.pagingByTagOldest(tagId);
    }

    public PagingSource<Integer, NoteEntity> pagingByTagTitle(long tagId) {
        return noteDao.pagingByTagTitle(tagId);
    }

    public PagingSource<Integer, NoteEntity> pagingByTagColor(long tagId) {
        return noteDao.pagingByTagColor(tagId);
    }

    /** Sanitasi input user untuk query FTS: buang kutip, tambah wildcard prefix. */
    public PagingSource<Integer, NoteEntity> searchFts(String raw) {
        String q = (raw == null ? "" : raw.trim()).replace("\"", "") + "*";
        return noteDao.searchFts(q);
    }

    // ----------------------------------------------------------------- images

    /**
     * Impor gambar dari Uri: baca (maks 12MB), hash, enkripsi ke filesDir/images,
     * buat thumbnail 256px terenkripsi di filesDir/thumbs, catat ImageRefEntity,
     * dan naikkan imageCount note.
     */
    public void addImageFromUri(Context ctx, long noteId, Uri uri, Cb<ImageRefEntity> cb) {
        io.execute(() -> {
            InputStream in = null;
            try {
                in = ctx.getContentResolver().openInputStream(uri);
                if (in == null) {
                    cb.onResult(null);
                    return;
                }
                byte[] plain = readBounded(in, MAX_IMAGE_BYTES);
                if (plain == null) { // > 12MB
                    cb.onResult(null);
                    return;
                }
                saveImageBytes(noteId, plain, null, cb);
            } catch (Exception e) {
                cb.onResult(null);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }

    /**
     * Simpan foto live: video ~3 detik dienkripsi ke filesDir/videos/*_live.bin,
     * 1 frame tengah jadi foto still (disimpan via {@link #saveImageBytes}).
     * Suffix "_live" membedakan file video dari foto biasa di file system.
     */
    public void addLivePhotoFromVideo(Context ctx, long noteId, Uri videoUri,
                                      Cb<ImageRefEntity> cb) {
        io.execute(() -> {
            InputStream in = null;
            try {
                in = ctx.getContentResolver().openInputStream(videoUri);
                if (in == null) {
                    cb.onResult(null);
                    return;
                }
                byte[] videoPlain = readBounded(in, MAX_VIDEO_BYTES);
                if (videoPlain == null) { // > 25MB
                    cb.onResult(null);
                    return;
                }

                CryptoManager crypto = CryptoManager.get(appCtx);
                long ts = System.currentTimeMillis();
                File vidDir = new File(appCtx.getFilesDir(), "videos");
                //noinspection ResultOfMethodCallIgnored
                vidDir.mkdirs();
                File encVideo = new File(vidDir,
                        "img_" + noteId + "_" + ts + "_live.bin");
                writeBytes(encVideo, crypto.encrypt(videoPlain));

                byte[] still = com.hozinking.notes.ui.LivePhotoUtils
                        .extractStillFrame(ctx, videoUri);
                if (still == null) {
                    deleteFileQuiet(encVideo.getAbsolutePath());
                    cb.onResult(null);
                    return;
                }
                saveImageBytes(noteId, still, encVideo.getAbsolutePath(), cb);
            } catch (Exception e) {
                cb.onResult(null);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        });
    }

    /**
     * Dekripsi video live ke bytes plain (untuk diputar via file cache temp).
     */
    public void loadLiveVideoPlain(ImageRefEntity ref, Cb<byte[]> cb) {
        io.execute(() -> {
            try {
                if (ref == null || ref.liveVideoPath == null
                        || ref.liveVideoPath.isEmpty()) {
                    cb.onResult(null);
                    return;
                }
                byte[] plain = CryptoManager.get(appCtx)
                        .decryptFileToBytes(new File(ref.liveVideoPath));
                cb.onResult(plain);
            } catch (Exception e) {
                cb.onResult(null);
            }
        });
    }

    /** Hitung total foto live di SEMUA note. */
    public void countLivePhotos(Cb<Integer> cb) {
        io.execute(() -> {
            try {
                cb.onResult(imageDao.countLivePhotos());
            } catch (Exception e) {
                cb.onResult(0);
            }
        });
    }

    /**
     * Inti penyimpanan gambar: enkripsi gambar penuh + thumbnail,
     * catat ImageRefEntity, naikkan imageCount note.
     * BERJALAN di thread executor (jangan panggil dari main thread).
     */
    private void saveImageBytes(long noteId, byte[] plain, String liveVideoPath,
                                Cb<ImageRefEntity> cb) {
        try {
            CryptoManager crypto = CryptoManager.get(appCtx);
                String sha = HashUtils.sha256Hex(plain);
                long ts = System.currentTimeMillis();

                File imgDir = new File(appCtx.getFilesDir(), "images");
                File thDir = new File(appCtx.getFilesDir(), "thumbs");
                //noinspection ResultOfMethodCallIgnored
                imgDir.mkdirs();
                //noinspection ResultOfMethodCallIgnored
                thDir.mkdirs();

                // 1) Gambar penuh terenkripsi.
                File encFile = new File(imgDir, "img_" + noteId + "_" + ts + ".bin");
                writeBytes(encFile, crypto.encrypt(plain));

                // 2) Thumbnail: dekripsi di memori -> Bitmap -> scale max 256px ->
                //    JPEG 80 -> enkripsi ke disk.
                byte[] encThumb;
                Bitmap full = BitmapFactory.decodeByteArray(plain, 0, plain.length);
                if (full == null) {
                    deleteFileQuiet(encFile.getAbsolutePath());
                    cb.onResult(null);
                    return;
                }
                try {
                    Bitmap thumb = scaleDown(full, THUMB_MAX_PX);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, bos);
                    if (thumb != full) thumb.recycle();
                    encThumb = crypto.encrypt(bos.toByteArray());
                } finally {
                    full.recycle();
                }
                File thFile = new File(thDir, "th_" + noteId + "_" + ts + ".bin");
                writeBytes(thFile, encThumb);

                // 3) Catat + naikkan imageCount.
                ImageRefEntity ref = new ImageRefEntity();
                ref.noteId = noteId;
                ref.encPath = encFile.getAbsolutePath();
                ref.thumbPath = thFile.getAbsolutePath();
                ref.sha256 = sha;
                ref.createdAt = ts;
                ref.liveVideoPath = liveVideoPath; // null = foto biasa
                ref.id = imageDao.insert(ref);

                NoteEntity note = noteDao.getById(noteId);
                if (note != null) {
                    note.imageCount = note.imageCount + 1;
                    noteDao.update(note);
                }
                cb.onResult(ref);
        } catch (Exception e) {
            cb.onResult(null);
        }
    }

    public void getImages(long noteId, Cb<List<ImageRefEntity>> cb) {
        io.execute(() -> cb.onResult(imageDao.forNote(noteId)));
    }

    public void getFirstImage(long noteId, Cb<ImageRefEntity> cb) {
        io.execute(() -> cb.onResult(imageDao.firstForNote(noteId)));
    }

    /** Registrasi ulang referensi gambar (dipakai import backup). */
    public void insertImageRef(ImageRefEntity ref, Cb<Long> cb) {
        io.execute(() -> {
            ref.createdAt = System.currentTimeMillis();
            cb.onResult(imageDao.insert(ref));
        });
    }

    /**
     * Dekripsi file gambar lalu verifikasi SHA-256 terhadap bytes plain.
     * Kembalikan null bila file hilang, dekripsi gagal, atau hash mismatch.
     */
    public void loadImagePlain(ImageRefEntity ref, Cb<byte[]> cb) {
        io.execute(() -> {
            try {
                if (ref == null || ref.encPath == null) {
                    cb.onResult(null);
                    return;
                }
                byte[] plain = CryptoManager.get(appCtx).decryptFileToBytes(new File(ref.encPath));
                if (ref.sha256 != null && !HashUtils.sha256Hex(plain).equalsIgnoreCase(ref.sha256)) {
                    cb.onResult(null); // integritas gagal
                    return;
                }
                cb.onResult(plain);
            } catch (Exception e) {
                cb.onResult(null);
            }
        });
    }

    /** Hapus file + baris image, dan turunkan imageCount note (min 0). */
    public void deleteImage(ImageRefEntity ref, Cb<Void> cb) {
        io.execute(() -> {
            if (ref != null) {
                deleteFileQuiet(ref.encPath);
                deleteFileQuiet(ref.thumbPath);
                deleteFileQuiet(ref.liveVideoPath); // null-safe: foto biasa tak punya
                imageDao.delete(ref.id);
                NoteEntity note = noteDao.getById(ref.noteId);
                if (note != null) {
                    note.imageCount = Math.max(0, note.imageCount - 1);
                    noteDao.update(note);
                }
            }
            cb.onResult(null);
        });
    }

    // ---------------------------------------------------------------- folders

    public void getFolders(Cb<List<FolderEntity>> cb) {
        io.execute(() -> cb.onResult(folderDao.getAll()));
    }

    public void insertFolder(String name, int color, Cb<Long> cb) {
        io.execute(() -> {
            FolderEntity f = new FolderEntity();
            f.name = name;
            f.color = color;
            f.createdAt = System.currentTimeMillis();
            cb.onResult(folderDao.insert(f));
        });
    }

    /** Hapus folder; semua note di dalamnya dilepas (folderId = null). */
    public void deleteFolder(long id, Cb<Void> cb) {
        io.execute(() -> {
            folderDao.nullifyNotes(id);
            folderDao.delete(id);
            cb.onResult(null);
        });
    }

    // ------------------------------------------------------------------- tags

    public void getTags(Cb<List<TagEntity>> cb) {
        io.execute(() -> cb.onResult(tagDao.getAll()));
    }

    public void getTagsForNote(long noteId, Cb<List<TagEntity>> cb) {
        io.execute(() -> cb.onResult(tagDao.forNote(noteId)));
    }

    /** Ganti seluruh tag sebuah note dari daftar nama (trim, abaikan yang kosong). */
    public void setTagsForNote(long noteId, List<String> names, Cb<Void> cb) {
        io.execute(() -> {
            tagDao.clearForNote(noteId);
            if (names != null) {
                List<String> seen = new ArrayList<>();
                for (String raw : names) {
                    if (raw == null) continue;
                    String name = raw.trim();
                    if (name.isEmpty() || seen.contains(name)) continue;
                    seen.add(name);
                    TagEntity tag = tagDao.byName(name);
                    long tagId;
                    if (tag != null) {
                        tagId = tag.id;
                    } else {
                        TagEntity t = new TagEntity();
                        t.name = name;
                        tagId = tagDao.insert(t);
                        if (tagId == -1) { // balapan insert: baca ulang
                            TagEntity again = tagDao.byName(name);
                            if (again == null) continue;
                            tagId = again.id;
                        }
                    }
                    tagDao.insertCrossRef(new NoteTagCrossRef(noteId, tagId));
                }
            }
            cb.onResult(null);
        });
    }

    // ------------------------------------------------------------------ vault

    /**
     * Ambil ulang DAO vault dari koneksi baru. Dipanggil setelah file vault.db
     * diganti (restore backup) karena koneksi lama sudah ditutup.
     */
    public void refreshVault() {
        vaultDao = VaultDatabase.get(appCtx).vaultDao();
    }

    public PagingSource<Integer, VaultNoteEntity> vaultPaging() {
        return vaultDao.paging();
    }

    public void vaultGet(long id, Cb<VaultNoteEntity> cb) {
        io.execute(() -> cb.onResult(vaultDao.getById(id)));
    }

    public void vaultInsert(VaultNoteEntity n, Cb<Long> cb) {
        io.execute(() -> {
            long now = System.currentTimeMillis();
            if (n.createdAt == 0) n.createdAt = now;
            if (n.updatedAt == 0) n.updatedAt = now;
            cb.onResult(vaultDao.insert(n));
        });
    }

    public void vaultUpdate(VaultNoteEntity n, Cb<Void> cb) {
        io.execute(() -> {
            n.updatedAt = System.currentTimeMillis();
            vaultDao.update(n);
            cb.onResult(null);
        });
    }

    /** Hapus note vault beserta file & baris gambarnya. */
    public void vaultDelete(long id, Cb<Void> cb) {
        io.execute(() -> {
            for (VaultImageEntity e : vaultDao.imagesFor(id)) {
                deleteFileQuiet(e.encPath);
                deleteFileQuiet(e.thumbPath);
            }
            vaultDao.deleteImagesFor(id);
            vaultDao.delete(id);
            cb.onResult(null);
        });
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] readBounded(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void writeBytes(File dst, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(dst);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static void deleteFileQuiet(String path) {
        if (path == null) return;
        try {
            File f = new File(path);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static Bitmap scaleDown(Bitmap src, int maxPx) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int longest = Math.max(w, h);
        if (longest <= maxPx) return src;
        float scale = (float) maxPx / longest;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }
}
