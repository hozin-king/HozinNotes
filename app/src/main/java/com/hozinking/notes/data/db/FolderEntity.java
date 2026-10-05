package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "folders")
public class FolderEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;
    /** Warna ARGB, 0 = tidak ada. */
    public int color;
    public long createdAt;
}
