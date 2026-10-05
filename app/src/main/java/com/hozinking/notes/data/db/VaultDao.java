package com.hozinking.notes.data.db;

import androidx.paging.PagingSource;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface VaultDao {
    @Query("SELECT * FROM vnotes ORDER BY updatedAt DESC")
    PagingSource<Integer, VaultNoteEntity> paging();

    @Query("SELECT * FROM vnotes WHERE id=:id LIMIT 1")
    VaultNoteEntity getById(long id);

    @Insert
    long insert(VaultNoteEntity n);

    @Update
    void update(VaultNoteEntity n);

    @Query("DELETE FROM vnotes WHERE id=:id")
    void delete(long id);

    @Query("SELECT * FROM vimages WHERE noteId=:noteId ORDER BY createdAt ASC")
    List<VaultImageEntity> imagesFor(long noteId);

    @Query("SELECT * FROM vimages ORDER BY createdAt ASC")
    List<VaultImageEntity> allImages();

    @Insert
    long insertImage(VaultImageEntity e);

    @Query("DELETE FROM vimages WHERE id=:id")
    void deleteImage(long id);

    @Query("DELETE FROM vimages WHERE noteId=:noteId")
    void deleteImagesFor(long noteId);

    @Query("DELETE FROM vnotes")
    void clearAll();

    @Query("DELETE FROM vimages")
    void clearImages();
}
