package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "vimages",
        indices = {@Index("noteId")})
public class VaultImageEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long noteId;
    public String encPath;
    public String thumbPath;
    public String sha256;
    public long createdAt;
}
