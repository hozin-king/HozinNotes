package com.hozinking.notes.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * v1.21: satu entri mood per hari kalender. Kunci = tanggal "yyyy-MM-dd",
 * sehingga satu hari hanya punya satu mood (upsert menimpa).
 * Tabel BARU — data lama tidak tersentuh, migrasi aman.
 */
@Entity(tableName = "mood_entries")
public class MoodEntry {
    /** Tanggal "yyyy-MM-dd" (zona waktu perangkat). */
    @PrimaryKey
    @NonNull
    public String date = "";

    /** Nilai mood 1..5 (1=Buruk, 2=Kurang, 3=Biasa, 4=Baik, 5=Luar biasa). */
    public int mood;

    /** Waktu terakhir diubah (epoch ms). */
    public long updatedAt;
}
