package com.hozinking.notes.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name ASC")
    List<FolderEntity> getAll();

    @Insert
    long insert(FolderEntity f);

    @Update
    void update(FolderEntity f);

    @Query("DELETE FROM folders WHERE id=:id")
    void delete(long id);

    @Query("SELECT * FROM folders WHERE id=:id LIMIT 1")
    FolderEntity getById(long id);

    @Query("DELETE FROM folders")
    void clearAll();

    /** Melepas semua note dari folder (dipakai sebelum menghapus folder). */
    @Query("UPDATE notes SET folderId=NULL WHERE folderId=:folderId")
    void nullifyNotes(long folderId);
}
