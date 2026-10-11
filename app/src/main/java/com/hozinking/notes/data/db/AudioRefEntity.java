package com.hozinking.notes.data.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Referensi file rekaman suara terenkripsi milik sebuah note.
 * File disimpan di filesDir/audio/*.bin (AAC .m4a yang dienkripsi CryptoManager).
 */
@Entity(tableName = "audio_refs",
        indices = {@Index("noteId")})
public class AudioRefEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long noteId;
    /** Path absolut file audio terenkripsi (filesDir/audio/aud_*.bin). */
    public String encPath;
    /** Durasi rekaman dalam milidetik. */
    public long durationMs;
    public long createdAt;
}
