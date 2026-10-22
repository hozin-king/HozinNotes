package com.hozinking.notes.data.db;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.22: agregat statistik pemakaian catatan, dihitung sekali jalan di
 * NoteRepository.noteStats(). Semua query read-only; tanpa migrasi DB.
 */
public class NoteStats {
    public int active;
    public int archived;
    public int trashed;
    public int createdLast7d;
    /** Tanggal "yyyy-MM-dd" hari paling produktif, atau null bila kosong. */
    public String topDayDate;
    public int topDayCount;
    public long totalChars;
    public long avgChars;
    public String longestTitle;
    public int longestLen;
    public int withReminder;
    public int locked;
    public int checklist;
    public int withImages;

    /** Baris "nama folder — jumlah catatan". */
    public static class FolderRow {
        public final String name;
        public final int count;

        public FolderRow(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    public final List<FolderRow> folders = new ArrayList<>();
}
