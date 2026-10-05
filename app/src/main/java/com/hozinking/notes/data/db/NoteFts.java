package com.hozinking.notes.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Fts4;
import androidx.room.PrimaryKey;

/** Tabel Full-Text Search (disinkron otomatis dari NoteEntity). */
@Fts4(contentEntity = NoteEntity.class)
@Entity(tableName = "notes_fts")
public class NoteFts {
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    public long rowId;

    public String title;
    public String content;
}
