package com.hozinking.notes.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Satu entri brankas kata sandi / API key.
 *
 * <p>Pertahanan berlapis:
 * <ul>
 *   <li>Lapis 1: seluruh database ini terenkripsi SQLCipher (AES-256) di disk.</li>
 *   <li>Lapis 2: kolom {@code encSecret} dan {@code encNotes} terenkripsi lagi
 *       per-field dengan AES-256-GCM memakai kunci AndroidKeyStore
 *       (lihat {@code PasswordCrypto}). Format: IV(12) || ciphertext+tag.</li>
 * </ul>
 * Nilai plaintext TIDAK PERNAH disimpan — hanya di memori saat dipakai,
 * lalu di-wipe.
 */
@Entity(tableName = "password_entries",
        indices = {@Index("title"), @Index("category"), @Index("updatedAt")})
public class PasswordEntry {
    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Nama entri, mis. "Gmail", "API Key OpenAI". Tidak dienkripsi (untuk pencarian). */
    public String title = "";

    /** Username / email / key-id. Tidak dienkripsi (untuk pencarian). */
    @ColumnInfo(defaultValue = "")
    public String username = "";

    /** URL layanan terkait, opsional. */
    @ColumnInfo(defaultValue = "")
    public String url = "";

    /** Kategori: "Login", "API Key", "Bank", "WiFi", "Lainnya". */
    @ColumnInfo(defaultValue = "Login")
    public String category = "Login";

    /** Password / API key / secret — TERENKRIPSI AES-256-GCM (Keystore). */
    public byte[] encSecret;

    /** Catatan tambahan rahasia — TERENKRIPSI AES-256-GCM (Keystore), boleh null. */
    public byte[] encNotes;

    public boolean favorite;

    public long createdAt;
    public long updatedAt;

    /** Terakhir dipakai (salin/lihat) — untuk info & audit sederhana. */
    public long lastUsedAt;
}
