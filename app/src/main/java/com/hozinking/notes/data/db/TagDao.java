package com.hozinking.notes.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    List<TagEntity> getAll();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(TagEntity t);

    @Query("SELECT * FROM tags WHERE name=:name LIMIT 1")
    TagEntity byName(String name);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertCrossRef(NoteTagCrossRef r);

    @Query("DELETE FROM note_tags WHERE noteId=:noteId")
    void clearForNote(long noteId);

    @Query("SELECT t.* FROM tags t INNER JOIN note_tags nt ON nt.tagId=t.id "
            + "WHERE nt.noteId=:noteId ORDER BY t.name ASC")
    List<TagEntity> forNote(long noteId);

    @Query("DELETE FROM tags")
    void clearAll();

    @Query("DELETE FROM note_tags")
    void clearCrossRefs();
}
