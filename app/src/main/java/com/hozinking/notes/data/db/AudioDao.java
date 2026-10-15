package com.hozinking.notes.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AudioDao {
    @Insert
    long insert(AudioRefEntity e);

    @Query("SELECT * FROM audio_refs WHERE noteId=:noteId ORDER BY createdAt ASC")
    List<AudioRefEntity> forNote(long noteId);

    @Query("DELETE FROM audio_refs WHERE id=:id")
    void delete(long id);

    @Query("DELETE FROM audio_refs WHERE noteId=:noteId")
    void deleteForNote(long noteId);

    /** Pindahkan semua rekaman suara dari satu note ke note lain (untuk gabung catatan). */
    @Query("UPDATE audio_refs SET noteId=:toId WHERE noteId=:fromId")
    void reassignNote(long fromId, long toId);

    @Query("DELETE FROM audio_refs")
    void clearAll();

    @Query("SELECT COUNT(*) FROM audio_refs")
    int countAll();
}
