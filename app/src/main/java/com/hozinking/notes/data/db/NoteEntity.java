package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "notes",
        indices = {
                @Index("folderId"),
                @Index("updatedAt"),
                @Index("trashed"),
                @Index("archived")
        })
public class NoteEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String title;
    public String content;
    /** Nullable: null = tanpa folder. */
    public Long folderId;
    /** Warna ARGB, 0 = tidak ada. */
    public int color;
    public boolean pinned;
    public boolean archived;
    public boolean trashed;
    public boolean isChecklist;
    public int imageCount;
    public long createdAt;
    public long updatedAt;
    /** Waktu pengingat (epoch ms). 0 = tidak ada pengingat. */
    public long reminderTime;
    /** Dikunci pakai PIN (Ruang Privat). */
    public boolean locked;
    /** true bila content disimpan sebagai HTML (teks berformat/tabel). */
    public boolean contentHtml;
}
