package com.hozinking.notes.data.repo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;

import androidx.paging.PagingSource;

import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.db.AudioDao;
import com.hozinking.notes.data.db.AudioRefEntity;
import com.hozinking.notes.data.db.FolderCount;
import com.hozinking.notes.data.db.FolderDao;
import com.hozinking.notes.data.db.FolderEntity;
import com.hozinking.notes.data.db.LongestNote;
import com.hozinking.notes.data.db.ImageDao;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.db.ManualNeighbor;
import com.hozinking.notes.data.db.MoodDao;
import com.hozinking.notes.data.db.MoodEntry;
import com.hozinking.notes.data.db.NoteDao;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.db.NoteStats;
import com.hozinking.notes.data.db.NoteTagCrossRef;
import com.hozinking.notes.data.db.TagDao;
import com.hozinking.notes.data.db.TagEntity;
import com.hozinking.notes.data.db.VaultDao;
import com.hozinking.notes.data.db.VaultDatabase;
import com.hozinking.notes.data.db.VaultImageEntity;
import com.hozinking.notes.data.db.VaultNoteEntity;
import com.hozinking.notes.reminder.PinnedNoteManager;
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
    private static final int THUMB_MAX_PX = 512;
    private static final int THUMB_JPEG_QUALITY = 85;
    // Gambar penuh: batasi sisi terpanjang 2048px, JPEG quality 92.
    // Bila gambar asli sudah JPEG dan <= 2048px, disimpan apa adanya
    // (tanpa recompress) agar kualitas maksimal.
    private static final int FULL_MAX_PX = 2048;
    private static final int FULL_JPEG_QUALITY = 92;

    private static volatile NoteRepository instance;

    /** Callback sederhana; dipanggil di thread executor. */
    public interface Cb<T> {
        void onResult(T v);
    }

    private final Context appCtx;
    private final AppDatabase db;
    private final NoteDao noteDao;
    private final ImageDao imageDao;
    private final AudioDao audioDao;
    private final FolderDao folderDao;
    private final TagDao tagDao;
    private final MoodDao moodDao;
    private VaultDao vaultDao;
    private final ExecutorService io = Executors.newFixedThreadPool(4);

    private NoteRepository(Context ctx) {
        appCtx = ctx.getApplicationContext();
        AppDatabase db = AppDatabase.get(appCtx);
        this.db = db;
        noteDao = db.noteDao();
        imageDao = db.imageDao();
        audioDao = db.audioDao();
        folderDao = db.folderDao();
        tagDao = db.tagDao();
        moodDao = db.moodDao();
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
            // v1.17: catatan baru mulai di posisi teratas mode Manual.
            if (n.manualOrder == 0) n.manualOrder = -(double) n.createdAt;
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
            PinnedNoteManager.unpinIfPinned(appCtx, id);
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
                n.manualOrder = -(double) now; // v1.17: salinan mulai di teratas mode Manual
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

    /** Kandidat tujuan gabung catatan (semua catatan aktif kecuali sumber). */
    public void getMergeTargets(long excludeId, Cb<List<NoteEntity>> cb) {
        io.execute(() -> cb.onResult(noteDao.mergeTargets(excludeId, 50)));
    }

    /**
     * Gabungkan catatan: pindahkan isi, gambar, rekaman suara, dan tag dari
     * sumber ke tujuan. Catatan sumber lalu dipindah ke Sampah (bisa dipulihkan,
     * bukan hapus permanen). Terinspirasi fitur "merge notes" Omni-Notes
     * (federicoiosue/Omni-Notes, GPL-3.0); implementasi sendiri.
     *
     * Aturan: judul, folder, warna, dan status tujuan dipertahankan. Pengingat
     * sumber ikut pindah bila tujuan belum punya pengingat.
     */
    public void mergeNote(long srcId, long targetId, Cb<Boolean> cb) {
        io.execute(() -> {
            try {
                if (srcId == targetId || srcId <= 0 || targetId <= 0) {
                    cb.onResult(false);
                    return;
                }
                NoteEntity src = noteDao.getById(srcId);
                NoteEntity tgt = noteDao.getById(targetId);
                if (src == null || tgt == null || src.trashed || tgt.trashed) {
                    cb.onResult(false);
                    return;
                }
                String srcTitle = src.title == null ? "" : src.title.trim();
                String srcBody = src.content == null ? "" : src.content;
                String tgtBody = tgt.content == null ? "" : tgt.content;
                // Bila format berbeda, turunkan konten sumber ke teks polos
                // agar tidak merusak render tujuan.
                if (src.contentHtml && !tgt.contentHtml) {
                    srcBody = android.text.Html.fromHtml(srcBody,
                            android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
                }
                if (tgt.contentHtml) {
                    String sep = "<hr><p><b>&#9472;&#9472;&#9472; "
                            + escapeHtml(srcTitle) + " &#9472;&#9472;&#9472;</b></p>";
                    String srcHtml = src.contentHtml ? srcBody
                            : "<p>" + escapeHtml(srcBody).replace("\n", "<br>") + "</p>";
                    tgt.content = tgtBody + sep + srcHtml;
                } else {
                    String head = srcTitle.isEmpty() ? "" : srcTitle;
                    tgt.content = tgtBody + "\n\n─── " + head + " ───\n\n" + srcBody;
                }
                // Pindahkan gambar + rekaman suara, lalu sinkronkan jumlah.
                imageDao.reassignNote(srcId, targetId);
                audioDao.reassignNote(srcId, targetId);
                List<ImageRefEntity> imgs = imageDao.forNote(targetId);
                tgt.imageCount = imgs == null ? 0 : imgs.size();
                // Tag: gabungan (insertCrossRef sudah IGNORE bila duplikat).
                List<TagEntity> srcTags = tagDao.forNote(srcId);
                if (srcTags != null) {
                    for (TagEntity te : srcTags) {
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
                        tagDao.insertCrossRef(new NoteTagCrossRef(targetId, tagId));
                    }
                }
                // Pengingat sumber ikut pindah bila tujuan belum punya.
                if (tgt.reminderTime == 0 && src.reminderTime > 0) {
                    tgt.reminderTime = src.reminderTime;
                    tgt.reminderRepeat = src.reminderRepeat;
                }
                tgt.updatedAt = System.currentTimeMillis();
                noteDao.update(tgt);
                noteDao.trash(srcId, System.currentTimeMillis());
                PinnedNoteManager.unpinIfPinned(appCtx, srcId);
                cb.onResult(true);
            } catch (Exception e) {
                cb.onResult(false);
            }
        });
    }

    /** Escape minimal untuk judul yang disisipkan ke HTML. */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Hapus permanen: file gambar + rekaman suara dulu, lalu baris DB, lalu note. */
    public void deleteForever(long id, Cb<Void> cb) {
        io.execute(() -> {
            List<ImageRefEntity> refs = imageDao.forNote(id);
            for (ImageRefEntity r : refs) {
                deleteFileQuiet(r.encPath);
                deleteFileQuiet(r.thumbPath);
            }
            imageDao.deleteForNote(id);
            // Rekaman suara: file + baris audio_refs.
            List<AudioRefEntity> auds = audioDao.forNote(id);
            for (AudioRefEntity a : auds) {
                deleteFileQuiet(a.encPath);
            }
            audioDao.deleteForNote(id);
            noteDao.deleteForever(id);
            PinnedNoteManager.unpinIfPinned(appCtx, id);
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
            audioDao.clearAll();
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

    /** v1.16: urut berdasarkan tanggal dibuat. */
    public PagingSource<Integer, NoteEntity> pagingActiveCreated() {
        return noteDao.pagingActiveCreated();
    }

    public PagingSource<Integer, NoteEntity> pagingActiveCreatedOld() {
        return noteDao.pagingActiveCreatedOld();
    }

    public PagingSource<Integer, NoteEntity> pagingActiveTitle() {
        return noteDao.pagingActiveTitle();
    }

    public PagingSource<Integer, NoteEntity> pagingActiveColor() {
        return noteDao.pagingActiveColor();
    }

    /** v1.17: urutan manual. */
    public PagingSource<Integer, NoteEntity> pagingActiveManual() {
        return noteDao.pagingActiveManual();
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

    /** v1.16: filter tag + urut tanggal dibuat. */
    public PagingSource<Integer, NoteEntity> pagingByTagCreated(long tagId) {
        return noteDao.pagingByTagCreated(tagId);
    }

    public PagingSource<Integer, NoteEntity> pagingByTagCreatedOld(long tagId) {
        return noteDao.pagingByTagCreatedOld(tagId);
    }

    public PagingSource<Integer, NoteEntity> pagingByTagTitle(long tagId) {
        return noteDao.pagingByTagTitle(tagId);
    }

    public PagingSource<Integer, NoteEntity> pagingByTagColor(long tagId) {
        return noteDao.pagingByTagColor(tagId);
    }

    /** v1.17: filter tag + urutan manual. */
    public PagingSource<Integer, NoteEntity> pagingByTagManual(long tagId) {
        return noteDao.pagingByTagManual(tagId);
    }

    /**
     * v1.17: pindah posisi catatan dalam urutan manual.
     * dir &lt; 0 = ke atas satu langkah, dir &gt; 0 = ke bawah satu langkah.
     * Tukar nilai manualOrder dengan tetangga dalam grup pinned yang sama,
     * di dalam satu transaksi. Callback true bila posisi berubah.
     */
    public void moveManual(long id, int dir, Cb<Boolean> cb) {
        io.execute(() -> {
            boolean moved = false;
            try {
                final boolean[] out = new boolean[1];
                db.runInTransaction(() -> {
                    NoteEntity n = noteDao.getById(id);
                    if (n == null || n.trashed || n.archived) return;
                    ManualNeighbor nb = dir < 0
                            ? noteDao.manualAbove(n.pinned, n.manualOrder)
                            : noteDao.manualBelow(n.pinned, n.manualOrder);
                    if (nb == null) return;
                    noteDao.setManualOrder(id, nb.manualOrder);
                    noteDao.setManualOrder(nb.id, n.manualOrder);
                    out[0] = true;
                });
                moved = out[0];
            } catch (Exception ignored) {
            }
            cb.onResult(moved);
        });
    }

    /** v1.17: pindah catatan ke posisi paling atas urutan manual (grup pinned sama). */
    public void moveManualToTop(long id, Cb<Boolean> cb) {
        io.execute(() -> {
            boolean moved = false;
            try {
                final boolean[] out = new boolean[1];
                db.runInTransaction(() -> {
                    NoteEntity n = noteDao.getById(id);
                    if (n == null || n.trashed || n.archived) return;
                    Double min = noteDao.minManualOrder(n.pinned);
                    if (min == null) return;
                    if (n.manualOrder <= min) return; // sudah paling atas
                    noteDao.setManualOrder(id, min - 1);
                    out[0] = true;
                });
                moved = out[0];
            } catch (Exception ignored) {
            }
            cb.onResult(moved);
        });
    }

    /** Sanitasi input user untuk query FTS: buang kutip, tambah wildcard prefix. */
    public PagingSource<Integer, NoteEntity> searchFts(String raw) {
        String q = (raw == null ? "" : raw.trim()).replace("\"", "") + "*";
        return noteDao.searchFts(q);
    }

    // ----------------------------------------------------------------- images

    /**
     * Impor gambar dari Uri: baca (maks 12MB), normalisasi (maks 2048px,
     * JPEG 92), hash, enkripsi ke filesDir/images, buat thumbnail 512px
     * terenkripsi di filesDir/thumbs, catat ImageRefEntity,
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
     * Simpan bytes gambar mentah sebagai lampiran terenkripsi catatan
     * (dipakai impor .enex; bytes sudah tervalidasi pengirim).
     * Berjalan di thread executor internal; callback di thread yang sama.
     */
    public void addImageFromBytes(long noteId, byte[] plain, Cb<ImageRefEntity> cb) {
        io.execute(() -> {
            if (plain == null || plain.length == 0 || plain.length > MAX_IMAGE_BYTES) {
                cb.onResult(null);
                return;
            }
            saveImageBytes(noteId, plain, null, cb);
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
     * Normalisasi gambar penuh sebelum enkripsi: batasi sisi terpanjang ke
     * FULL_MAX_PX dengan JPEG quality FULL_JPEG_QUALITY. Gambar yang sudah
     * JPEG dan tidak melebihi batas dikembalikan apa adanya (tanpa
     * recompress agar tidak ada penurunan kualitas). Decode memakai
     * inSampleSize agar hemat memori untuk foto resolusi besar.
     */
    private static byte[] normalizeFullImage(byte[] plain) {
        if (plain == null || plain.length < 4) return plain;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(plain, 0, plain.length, bounds);
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return plain; // bukan gambar yang dikenali
        boolean isJpeg = (plain[0] & 0xFF) == 0xFF
                && (plain[1] & 0xFF) == 0xD8
                && (plain[2] & 0xFF) == 0xFF;
        if (isJpeg && Math.max(w, h) <= FULL_MAX_PX) return plain;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (Math.max(w, h) / (o.inSampleSize * 2) > FULL_MAX_PX) {
            o.inSampleSize *= 2;
        }
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeByteArray(plain, 0, plain.length, o);
        } catch (OutOfMemoryError e) {
            return plain;
        }
        if (bmp == null) return plain;
        try {
            Bitmap scaled = scaleDown(bmp, FULL_MAX_PX);
            // JPEG tak mendukung alpha: flatten ke background putih.
            Bitmap rgb = Bitmap.createBitmap(scaled.getWidth(),
                    scaled.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(rgb);
            c.drawColor(0xFFFFFFFF);
            c.drawBitmap(scaled, 0, 0, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            rgb.compress(Bitmap.CompressFormat.JPEG, FULL_JPEG_QUALITY, bos);
            if (scaled != bmp) scaled.recycle();
            rgb.recycle();
            return bos.toByteArray();
        } finally {
            bmp.recycle();
        }
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
                // Normalisasi dulu (2048px / JPEG 92), lalu hash hasil akhirnya
                // agar verifikasi integritas konsisten.
                plain = normalizeFullImage(plain);
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

                // 2) Thumbnail: dekripsi di memori -> Bitmap -> scale max 512px ->
                //    JPEG 85 -> enkripsi ke disk. Dipakai strip editor & daftar
                //    note agar tajam tanpa decode gambar penuh.
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
     * Dekripsi file thumbnail (512px, JPEG 85). Kembalikan null bila tak ada
     * atau gagal — panggil loadImagePlain sebagai fallback (data lama).
     */
    public void loadThumbPlain(ImageRefEntity ref, Cb<byte[]> cb) {
        io.execute(() -> {
            try {
                if (ref == null || ref.thumbPath == null) {
                    cb.onResult(null);
                    return;
                }
                File f = new File(ref.thumbPath);
                if (!f.exists()) {
                    cb.onResult(null);
                    return;
                }
                cb.onResult(CryptoManager.get(appCtx).decryptFileToBytes(f));
            } catch (Exception e) {
                cb.onResult(null);
            }
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

    // ------------------------------------------------------------------ audio
    // Rekaman suara: file AAC (.m4a) direkam di editor, dienkripsi ke
    // filesDir/audio/*.bin, dicatat di tabel audio_refs (ide ala
    // "quick audio notes" NotallyX, GPL-3.0 — implementasi sendiri).

    private static final long MAX_AUDIO_BYTES = 10L * 1024 * 1024; // 10 MB

    /**
     * Simpan hasil rekaman: validasi ukuran, enkripsi ke filesDir/audio,
     * catat AudioRefEntity. File plain dihapus setelah terenkripsi.
     */
    public void saveAudioRecording(long noteId, File plainSrc, long durationMs,
                                   Cb<AudioRefEntity> cb) {
        io.execute(() -> {
            try {
                if (plainSrc == null || !plainSrc.isFile()
                        || plainSrc.length() > MAX_AUDIO_BYTES) {
                    if (plainSrc != null) plainSrc.delete();
                    cb.onResult(null);
                    return;
                }
                File dir = new File(appCtx.getFilesDir(), "audio");
                dir.mkdirs();
                File dst = new File(dir, "aud_" + System.currentTimeMillis() + ".bin");
                CryptoManager.get(appCtx).encryptFile(plainSrc, dst);
                plainSrc.delete();
                AudioRefEntity ref = new AudioRefEntity();
                ref.noteId = noteId;
                ref.encPath = dst.getAbsolutePath();
                ref.durationMs = durationMs;
                ref.createdAt = System.currentTimeMillis();
                ref.id = audioDao.insert(ref);
                cb.onResult(ref);
            } catch (Exception e) {
                cb.onResult(null);
            }
        });
    }

    public void getAudioRefs(long noteId, Cb<List<AudioRefEntity>> cb) {
        io.execute(() -> cb.onResult(audioDao.forNote(noteId)));
    }

    /** Registrasi ulang referensi audio (dipakai import backup). */
    public void insertAudioRef(AudioRefEntity ref, Cb<Long> cb) {
        io.execute(() -> {
            ref.createdAt = System.currentTimeMillis();
            cb.onResult(audioDao.insert(ref));
        });
    }

    /** Dekripsi file rekaman ke bytes plain (untuk pemutaran). Null bila gagal. */
    public void loadAudioPlain(AudioRefEntity ref, Cb<byte[]> cb) {
        io.execute(() -> {
            try {
                if (ref == null || ref.encPath == null) {
                    cb.onResult(null);
                    return;
                }
                File f = new File(ref.encPath);
                if (!f.exists()) {
                    cb.onResult(null);
                    return;
                }
                cb.onResult(CryptoManager.get(appCtx).decryptFileToBytes(f));
            } catch (Exception e) {
                cb.onResult(null);
            }
        });
    }

    /** Hapus satu rekaman: file terenkripsi + baris audio_refs. */
    public void deleteAudio(AudioRefEntity ref, Cb<Boolean> cb) {
        io.execute(() -> {
            try {
                if (ref != null) {
                    deleteFileQuiet(ref.encPath);
                    audioDao.delete(ref.id);
                }
                cb.onResult(true);
            } catch (Exception e) {
                cb.onResult(false);
            }
        });
    }

    /** Jumlah seluruh rekaman suara (untuk statistik). */
    public void countAudio(Cb<Integer> cb) {
        io.execute(() -> cb.onResult(audioDao.countAll()));
    }

    /** Jumlah catatan aktif (untuk statistik). */
    public void countNotes(Cb<Integer> cb) {
        io.execute(() -> cb.onResult(noteDao.countActive()));
    }

    /** Jumlah seluruh gambar (untuk statistik). */
    public void countImages(Cb<Integer> cb) {
        io.execute(() -> cb.onResult(imageDao.countAll()));
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

    // ------------------------------------------------------------- mood harian

    /** v1.21: simpan/timpa mood satu hari (nilai 1..5). */
    public void saveMood(String date, int mood, Cb<Void> cb) {
        io.execute(() -> {
            MoodEntry e = new MoodEntry();
            e.date = date;
            e.mood = Math.max(1, Math.min(5, mood));
            e.updatedAt = System.currentTimeMillis();
            moodDao.upsert(e);
            cb.onResult(null);
        });
    }

    /** v1.21: nilai mood pada tanggal tertentu; 0 bila belum dicatat. */
    public void moodForDate(String date, Cb<Integer> cb) {
        io.execute(() -> {
            MoodEntry e = moodDao.forDate(date);
            cb.onResult(e == null ? 0 : e.mood);
        });
    }

    /** v1.21: entri mood dalam rentang tanggal (inklusif), urut menaik. */
    public void moodRange(String from, String to, Cb<List<MoodEntry>> cb) {
        io.execute(() -> cb.onResult(moodDao.range(from, to)));
    }

    /**
     * v1.22: hitung statistik pemakaian catatan sekaligus (read-only).
     * Konsep layar analitik catatan dipinjam dari pola aplikasi seperti
     * Shkiper/LittleGooseOffice — implementasi sendiri.
     */
    public void noteStats(Cb<NoteStats> cb) {
        io.execute(() -> {
            NoteStats s = new NoteStats();
            s.active = noteDao.countActive();
            s.archived = noteDao.countArchived();
            s.trashed = noteDao.countTrashed();
            s.createdLast7d = noteDao.countCreatedSince(
                    System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
            String top = noteDao.topDay();
            if (top != null && top.contains("|")) {
                String[] parts = top.split("\\|", 2);
                s.topDayDate = parts[0];
                try {
                    s.topDayCount = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }
            s.totalChars = noteDao.sumTextLength();
            s.avgChars = s.active == 0 ? 0 : s.totalChars / s.active;
            LongestNote ln = noteDao.longestNote();
            if (ln != null) {
                s.longestTitle = (ln.title == null || ln.title.trim().isEmpty())
                        ? "(tanpa judul)" : ln.title.trim();
                s.longestLen = ln.len;
            }
            s.withReminder = noteDao.countWithReminder();
            s.locked = noteDao.countLocked();
            s.checklist = noteDao.countChecklist();
            s.withImages = noteDao.countWithImages();
            try {
                List<FolderEntity> all = folderDao.getAll();
                java.util.Map<Long, String> names = new java.util.HashMap<>();
                for (FolderEntity f : all) names.put(f.id, f.name);
                for (FolderCount fc : noteDao.countByFolder()) {
                    String name = names.get(fc.folderId);
                    if (name == null || name.trim().isEmpty()) name = "Tanpa folder";
                    s.folders.add(new NoteStats.FolderRow(name, fc.cnt));
                }
            } catch (Exception ignored) {
            }
            cb.onResult(s);
        });
    }
}
