package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "tags",
        indices = {@Index(value = "name", unique = true)})
public class TagEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String name;
}
