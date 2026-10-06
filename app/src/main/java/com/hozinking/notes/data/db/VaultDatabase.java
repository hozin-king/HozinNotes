package com.hozinking.notes.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.hozinking.notes.security.CryptoManager;

import net.sqlcipher.database.SupportFactory;

@Database(entities = {
        VaultNoteEntity.class,
        VaultImageEntity.class,
        PasswordEntry.class
}, version = 2, exportSchema = false)
public abstract class VaultDatabase extends RoomDatabase {
    private static volatile VaultDatabase instance;

    public abstract VaultDao vaultDao();

    public abstract PasswordDao passwordDao();

    /** Migrasi 1 -> 2: tambah tabel brankas kata sandi (tanpa menghapus data vault lama). */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `password_entries` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT, " +
                    "`username` TEXT DEFAULT '', " +
                    "`url` TEXT DEFAULT '', " +
                    "`category` TEXT DEFAULT 'Login', " +
                    "`encSecret` BLOB, " +
                    "`encNotes` BLOB, " +
                    "`favorite` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`lastUsedAt` INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_password_entries_title` ON `password_entries` (`title`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_password_entries_category` ON `password_entries` (`category`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_password_entries_updatedAt` ON `password_entries` (`updatedAt`)");
        }
    };

    public static VaultDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (VaultDatabase.class) {
                if (instance == null) {
                    Context app = ctx.getApplicationContext();
                    byte[] passphrase = CryptoManager.get(app).getVaultPassphrase();
                    SupportFactory factory = new SupportFactory(passphrase);
                    instance = Room.databaseBuilder(app, VaultDatabase.class, "vault.db")
                            .openHelperFactory(factory)
                            .addMigrations(MIGRATION_1_2)
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
