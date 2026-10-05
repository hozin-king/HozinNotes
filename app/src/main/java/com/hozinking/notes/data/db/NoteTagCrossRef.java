package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.ForeignKey;

/** Relasi many-to-many note <-> tag. */
@Entity(tableName = "note_tags",
        primaryKeys = {"noteId", "tagId"},
        foreignKeys = {
                @ForeignKey(entity = NoteEntity.class,
                        parentColumns = "id", childColumns = "noteId",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = TagEntity.class,
                        parentColumns = "id", childColumns = "tagId",
                        onDelete = ForeignKey.CASCADE)
        })
public class NoteTagCrossRef {
    public long noteId;
    public long tagId;

    public NoteTagCrossRef() {
    }

    public NoteTagCrossRef(long noteId, long tagId) {
        this.noteId = noteId;
        this.tagId = tagId;
    }
}
