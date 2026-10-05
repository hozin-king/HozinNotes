package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Referensi file gambar terenkripsi milik sebuah note. */
@Entity(tableName = "images",
        indices = {@Index("noteId")})
public class ImageRefEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long noteId;
    /** Path absolut file gambar terenkripsi (filesDir/images/...). */
    public String encPath;
    /** Path absolut file thumbnail terenkripsi (filesDir/thumbs/...). */
    public String thumbPath;
    /** SHA-256 hex dari BYTES PLAIN gambar asli (untuk verifikasi integritas setelah dekripsi). */
    public String sha256;
    public long createdAt;
}
