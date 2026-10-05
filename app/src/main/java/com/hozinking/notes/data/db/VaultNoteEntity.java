package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "vnotes")
public class VaultNoteEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String title;
    public String content;
    public long createdAt;
    public long updatedAt;
}
