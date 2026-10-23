package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * v1.23: satu snapshot isi catatan pada satu titik waktu (riwayat revisi).
 * Disimpan setiap kali isi/judul berubah saat penyimpanan otomatis;
 * maksimal {@link RevisionDao#MAX_KEPT} revisi terbaru per catatan.
 * Terinspirasi fitur version history ala Joplin; implementasi sendiri
 * memakai tabel Room biasa tanpa migrasi merusak.
 */
@Entity(tableName = "note_revisions",
        indices = {@Index("noteId")})
public class NoteRevision {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long noteId;

    public String title;

    public String content;

    /** true bila {@link #content} berupa HTML (catatan berformat/tabel). */
    public boolean contentHtml;

    /** Waktu snapshot (epoch millis). */
    public long savedAt;
}
