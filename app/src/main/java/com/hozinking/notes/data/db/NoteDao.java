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
            + "reminderTime,locked,contentHtml";

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingActive();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, updatedAt ASC")
    PagingSource<Integer, NoteEntity> pagingActiveOldest();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, title COLLATE NOCASE ASC")
    PagingSource<Integer, NoteEntity> pagingActiveTitle();

    @Query("SELECT " + PROJECTION + " FROM notes WHERE trashed=0 AND archived=0 "
            + "ORDER BY pinned DESC, color ASC, updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingActiveColor();

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
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.locked,n.contentHtml "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingByTag(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.locked,n.contentHtml "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.updatedAt ASC")
    PagingSource<Integer, NoteEntity> pagingByTagOldest(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.locked,n.contentHtml "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.title COLLATE NOCASE ASC")
    PagingSource<Integer, NoteEntity> pagingByTagTitle(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.locked,n.contentHtml "
            + "FROM notes n INNER JOIN note_tags nt ON nt.noteId=n.id "
            + "WHERE nt.tagId=:tagId AND n.trashed=0 AND n.archived=0 "
            + "ORDER BY n.pinned DESC, n.color ASC, n.updatedAt DESC")
    PagingSource<Integer, NoteEntity> pagingByTagColor(long tagId);

    @Query("SELECT n.id,n.title,substr(n.content,1,180) AS content,"
            + "n.folderId,n.color,n.pinned,n.archived,n.trashed,"
            + "n.isChecklist,n.imageCount,n.createdAt,n.updatedAt,n.reminderTime,n.locked,n.contentHtml "
            + "FROM notes n JOIN notes_fts f ON n.id=f.rowid "
            + "WHERE notes_fts MATCH :q AND n.trashed=0 "
            + "ORDER BY n.updatedAt DESC")
    PagingSource<Integer, NoteEntity> searchFts(String q);

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
}
