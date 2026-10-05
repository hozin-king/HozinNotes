package com.hozinking.notes.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.hozinking.notes.security.CryptoManager;

import net.sqlcipher.database.SupportFactory;

@Database(entities = {
        VaultNoteEntity.class,
        VaultImageEntity.class
}, version = 1, exportSchema = false)
public abstract class VaultDatabase extends RoomDatabase {
    private static volatile VaultDatabase instance;

    public abstract VaultDao vaultDao();

    public static VaultDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (VaultDatabase.class) {
                if (instance == null) {
                    Context app = ctx.getApplicationContext();
                    byte[] passphrase = CryptoManager.get(app).getVaultPassphrase();
                    SupportFactory factory = new SupportFactory(passphrase);
                    instance = Room.databaseBuilder(app, VaultDatabase.class, "vault.db")
                            .openHelperFactory(factory)
                            .build();
                }
            }
        }
        return instance;
    }

    /** Lokasi file database vault (SQLCipher — tetap terenkripsi di disk). */
    public static java.io.File getDbFile(Context ctx) {
        return ctx.getApplicationContext().getDatabasePath("vault.db");
    }

    /**
     * Tutup koneksi vault yang aktif dan buang singleton-nya.
     * Dipanggil SEBELUM file vault.db ditimpa saat restore backup.
     * Setelah file diganti, panggil {@code get(ctx)} lagi untuk koneksi baru.
     */
    public static void closeAndReset(Context ctx) {
        synchronized (VaultDatabase.class) {
            if (instance != null) {
                try {
                    instance.close();
                } catch (Exception ignored) {
                    // koneksi sudah tertutup / rusak — lanjutkan reset
                }
                instance = null;
            }
        }
    }
}
