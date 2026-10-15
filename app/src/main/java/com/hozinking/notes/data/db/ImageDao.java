package com.hozinking.notes.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ImageDao {
    @Insert
    long insert(ImageRefEntity e);

    @Query("SELECT * FROM images WHERE noteId=:noteId ORDER BY createdAt ASC")
    List<ImageRefEntity> forNote(long noteId);

    @Query("SELECT * FROM images WHERE noteId=:noteId ORDER BY createdAt ASC LIMIT 1")
    ImageRefEntity firstForNote(long noteId);

    @Query("DELETE FROM images WHERE id=:id")
    void delete(long id);

    @Query("DELETE FROM images WHERE noteId=:noteId")
    void deleteForNote(long noteId);

    /** Pindahkan semua referensi gambar dari satu note ke note lain (untuk gabung catatan). */
    @Query("UPDATE images SET noteId=:toId WHERE noteId=:fromId")
    void reassignNote(long fromId, long toId);

    @Query("DELETE FROM images")
    void clearAll();

    @Query("SELECT COUNT(*) FROM images WHERE liveVideoPath IS NOT NULL AND liveVideoPath != ''")
    int countLivePhotos();

    @Query("SELECT COUNT(*) FROM images")
    int countAll();
}
