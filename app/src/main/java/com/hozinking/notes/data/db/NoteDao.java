package com.hozinking.notes.data.db;

import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface NoteDao {
    String PROJECTION = "id,title,substr(content,1,180) AS content,"
            + "folderId,color,pinned,archived,trashed,isChecklist,imageCount,createdAt,updatedAt,"
            + "reminderTime,reminderRepeat,locked,contentHtml,manualOrder";

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingActive();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, updatedAt ASC")
    PagingSource<Integer, NoteEntity> pagingActiveOldest();

    /** v1.16: urut berdasarkan TANGGAL DIBUAT (bukan diubah). */
    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, createdAt DESC")
    PagingSource<Integer, NoteEntity> pagingActiveCreated();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, createdAt ASC")
    PagingSource<Integer, NoteEntity> pagingActiveCreatedOld();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, title COLLATE NOCASE ASC")
    PagingSource<Integer, NoteEntity> pagingActiveTitle();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, color ASC, updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingActiveColor();

    /**
     * v1.17: urutan MANUAL (konsep "drag notes to reorder" ala another-notes-app
     * maltaisn/another-notes-app, Apache-2.0 — ide dipinjam, implementasi sendiri).
     * Nilai manualOrder lebih kecil tampil lebih atas; id sebagai tie-break stabil.
     */
    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, manualOrder ASC, id DESC")
    PagingSource<Integer, NoteEntity> pagingActiveManual();

    /** Tetangga tepat di atas dalam urutan manual (grup pinned yang sama). */
    @Query("SELECT id, manualOrder FROM notes WHERE trashed=0 AND archived=0 "
            + "AND pinned=:pinned AND manualOrder < :order "
            + "ORDER BY manualOrder DESC, id DESC LIMIT 1")
    ManualNeighbor manualAbove(boolean pinned, double order);

    /** Tetangga tepat di bawah dalam urutan manual (grup pinned yang sama). */
    @Query("SELECT id, manualOrder FROM notes WHERE trashed=0 AND archived=0 "
            + "AND pinned=:pinned AND manualOrder > :order "
            + "ORDER BY manualOrder ASC, id DESC LIMIT 1")
    ManualNeighbor manualBelow(boolean pinned, double order);

    /** Nilai manualOrder terkecil di grup pinned — untuk "pindah ke paling atas". */
    @Query("SELECT MIN(manualOrder) FROM notes WHERE trashed=0 AND archived=0 "
            + "AND pinned=:pinned")
    Double minManualOrder(boolean pinned);

    @Query("UPDATE notes SET manualOrder=:order WHERE id=:id")
    void setManualOrder(long id, double order);

    @Query("SELECT " + PROJECTION + " FROM notes WHERE archived=1 AND trashed=0 "
            + "ORDER BY pinned DESC, updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingArchived();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=1 ORDER BY updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingTrashed();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE folderId=:folderId "
            + "AND trashed=0 AND archived=0 ORDER BY pinned DESC, updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingByFolder(long folderId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.reminderRepeat,n.locked,n.contentHtml,n.manualOrder "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingByTag(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.reminderRepeat,n.locked,n.contentHtml,n.manualOrder "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.updatedAt ASC")
    PagingSource<Integer, NoteEntity> pagingByTagOldest(long tagId);

    /** v1.16: filter tag + urut tanggal dibuat. */
    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.reminderRepeat,n.locked,n.contentHtml,n.manualOrder "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.createdAt DESC")
    PagingSource<Integer, NoteEntity> pagingByTagCreated(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.reminderRepeat,n.locked,n.contentHtml,n.manualOrder "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.createdAt ASC")
    PagingSource<Integer, NoteEntity> pagingByTagCreatedOld(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.reminderRepeat,n.locked,n.contentHtml,n.manualOrder "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.title COLLATE NOCASE ASC")
    PagingSource<Integer, NoteEntity> pagingByTagTitle(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.reminderRepeat,n.locked,n.contentHtml,n.manualOrder "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.color ASC, n.updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingByTagColor(long tagId);

    /** v1.17: filter tag + urutan manual. */
    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.reminderRepeat,n.locked,n.contentHtml,n.manualOrder "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.manualOrder ASC, n.id DESC")
    PagingSource<Integer, NoteEntity> pagingByTagManual(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.reminderRepeat,n.locked,n.contentHtml,n.manualOrder "
            + "FROM notes n JOIN notes_fts f ON n.id=f.rowid "
            + "WHERE notes_fts MATCH :q AND n.trashed=0 "
            + "ORDER BY n.updatedAt DESC")
    PagingSource<Integer, NoteEntity> searchFts(String q);

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, updatedAt DESC LIMIT 1")
    NoteEntity getLatestActive();

    /** Kandidat tujuan gabung catatan: catatan aktif lain, terbaru dulu (dibatasi). */
    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 AND id<>:excludeId "
            + "ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    List<NoteEntity> mergeTargets(long excludeId, int limit);

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    List<NoteEntity> getAllForBackup();

    @Query("SELECT * FROM notes WHERE id=:id LIMIT 1")
    NoteEntity getById(long id);

    @Insert
    long insert(NoteEntity n);

    @Update
    void update(NoteEntity n);

    @Query("UPDATE notes SET trashed=1, updatedAt=:now WHERE id=:id")
    void trash(long id, long now);

    @Query("UPDATE notes SET trashed=0, updatedAt=:now WHERE id=:id")
    void restore(long id, long now);

    @Query("DELETE FROM notes WHERE id=:id")
    void deleteForever(long id);

    @Query("DELETE FROM notes WHERE trashed=1 AND updatedAt < :cutoff")
    int purgeTrash(long cutoff);

    @Query("SELECT COUNT(*) FROM notes WHERE trashed=0 AND archived=0")
    int countActive();

    /** Semua pengingat yang masih aktif (waktu di masa depan, note tidak di sampah). */
    @Query("SELECT * FROM notes WHERE reminderTime > :now AND trashed=0")
    List<NoteEntity> getActiveReminders(long now);

    @Query("UPDATE notes SET reminderTime=:time, updatedAt=:updatedAt WHERE id=:id")
    void setReminder(long id, long time, long updatedAt);

    @Query("DELETE FROM notes")
    void clearAll();

    // ---------------- v1.22: statistik pemakaian (read-only, tanpa migrasi) ---
    @Query("SELECT COUNT(*) FROM notes WHERE trashed=0 AND archived=1")
    int countArchived();

    @Query("SELECT COUNT(*) FROM notes WHERE trashed=1")
    int countTrashed();

    @Query("SELECT COUNT(*) FROM notes WHERE trashed=0 AND archived=0 AND reminderTime>0")
    int countWithReminder();

    @Query("SELECT COUNT(*) FROM notes WHERE trashed=0 AND archived=0 AND locked=1")
    int countLocked();

    @Query("SELECT COUNT(*) FROM notes WHERE trashed=0 AND archived=0 AND isChecklist=1")
    int countChecklist();

    @Query("SELECT COUNT(*) FROM notes WHERE trashed=0 AND archived=0 AND imageCount>0")
    int countWithImages();

    @Query("SELECT COALESCE(SUM(LENGTH(IFNULL(title,''))),0)"
            + "+COALESCE(SUM(LENGTH(IFNULL(content,''))),0)"
            + " FROM notes WHERE trashed=0 AND archived=0")
    long sumTextLength();

    @Query("SELECT id, title, LENGTH(IFNULL(title,'')||IFNULL(content,'')) AS len"
            + " FROM notes WHERE trashed=0 AND archived=0 ORDER BY len DESC LIMIT 1")
    LongestNote longestNote();

    @Query("SELECT COUNT(*) FROM notes WHERE trashed=0 AND createdAt>=:since")
    int countCreatedSince(long since);

    /**
     * Hari paling produktif: "yyyy-MM-dd|jumlah". createdAt dalam milidetik.
     */
    @Query("SELECT date(createdAt/1000,'unixepoch','localtime')||'|'||COUNT(*)"
            + " FROM notes WHERE trashed=0"
            + " GROUP BY date(createdAt/1000,'unixepoch','localtime')"
            + " ORDER BY COUNT(*) DESC LIMIT 1")
    String topDay();

    @Query("SELECT folderId, COUNT(*) AS cnt FROM notes"
            + " WHERE trashed=0 AND archived=0 GROUP BY folderId ORDER BY cnt DESC")
    List<FolderCount> countByFolder();

    // ---------------- v1.25: kenangan hari ini & pengingat terdekat (read-only) ---
    /**
     * v1.25: catatan aktif yang DIBUAT pada tanggal-bulan yang sama dengan hari
     * ini, tapi di tahun sebelumnya (konsep "that year today" ala jotsy —
     * ide dipinjam, implementasi sendiri). createdAt dalam milidetik.
     */
    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "AND strftime('%m-%d', createdAt/1000,'unixepoch','localtime')=:monthDay "
            + "AND strftime('%Y', createdAt/1000,'unixepoch','localtime')<:year "
            + "ORDER BY createdAt DESC LIMIT 10")
    List<NoteEntity> onThisDay(String monthDay, String year);

    /** v1.25: pengingat aktif terdekat (waktu di masa depan, note tidak di sampah). */
    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 "
            + "AND reminderTime > :now ORDER BY reminderTime ASC LIMIT 1")
    NoteEntity nextReminder(long now);
}
