package com.hozinking.notes.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/** v1.23: akses riwayat revisi catatan. */
@Dao
public interface RevisionDao {
    /** Batas revisi yang disimpan per catatan (yang lebih lama dibuang). */
    int MAX_KEPT = 20;

    @Insert
    long insert(NoteRevision r);

    /** Revisi terbaru satu catatan (untuk deteksi perubahan). */
    @Query("SELECT * FROM note_revisions WHERE noteId=:noteId "
            + "ORDER BY savedAt DESC, id DESC LIMIT 1")
    NoteRevision latestForNote(long noteId);

    /** Semua revisi satu catatan, terbaru dulu (untuk daftar riwayat). */
    @Query("SELECT * FROM note_revisions WHERE noteId=:noteId "
            + "ORDER BY savedAt DESC, id DESC")
    List<NoteRevision> forNote(long noteId);

    /** Buang revisi lama, sisakan N terbaru. */
    @Query("DELETE FROM note_revisions WHERE noteId=:noteId AND id NOT IN "
            + "(SELECT id FROM note_revisions WHERE noteId=:noteId "
            + "ORDER BY savedAt DESC, id DESC LIMIT :keep)")
    void prune(long noteId, int keep);

    /** Hapus seluruh revisi satu catatan (dipakai saat hapus permanen). */
    @Query("DELETE FROM note_revisions WHERE noteId=:noteId")
    void deleteForNote(long noteId);

    /** Kosongkan tabel (untuk import timpa). */
    @Query("DELETE FROM note_revisions")
    void clearAll();
}
