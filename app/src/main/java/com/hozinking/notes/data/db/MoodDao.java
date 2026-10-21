package com.hozinking.notes.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MoodDao {
    /** Simpan/timpa mood satu hari (kunci = tanggal). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MoodEntry e);

    /** Mood pada tanggal tertentu, atau null bila belum dicatat. */
    @Query("SELECT * FROM mood_entries WHERE date=:date LIMIT 1")
    MoodEntry forDate(String date);

    /** Entri dalam rentang tanggal (inklusif), urut menaik. */
    @Query("SELECT * FROM mood_entries WHERE date BETWEEN :from AND :to ORDER BY date ASC")
    List<MoodEntry> range(String from, String to);

    /** N entri terbaru, urut menurun. */
    @Query("SELECT * FROM mood_entries ORDER BY date DESC LIMIT :n")
    List<MoodEntry> latest(int n);

    /** Jumlah hari yang tercatat (untuk statistik). */
    @Query("SELECT COUNT(*) FROM mood_entries")
    int countAll();
}
