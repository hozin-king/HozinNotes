package com.hozinking.notes.ui;

import com.hozinking.notes.data.db.NoteEntity;

/**
 * Satu baris daftar catatan: catatan biasa ATAU header seksi waktu
 * ("Hari ini", "Kemarin", "Minggu ini", ...). Dipakai NoteAdapter
 * bersama PagingData.map + insertSeparators agar daftar panjang
 * tetap mudah dipindai tanpa mengubah query database.
 */
public final class NoteListItem {
    public final NoteEntity note;
    public final String header;

    private NoteListItem(NoteEntity note, String header) {
        this.note = note;
        this.header = header;
    }

    public static NoteListItem ofNote(NoteEntity note) {
        return new NoteListItem(note, null);
    }

    public static NoteListItem ofHeader(String header) {
        return new NoteListItem(null, header);
    }

    public boolean isHeader() {
        return header != null;
    }
}
