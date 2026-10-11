package com.hozinking.notes.data.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {
        NoteEntity.class,
        ImageRefEntity.class,
        AudioRefEntity.class,
        FolderEntity.class,
        TagEntity.class,
        NoteTagCrossRef.class,
        NoteFts.class
}, version = 6, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;

    /** v1 -> v2: tambah kolom reminderTime (0 = tanpa pengingat). */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE notes ADD COLUMN reminderTime INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v2 -> v3: kunci PIN per catatan + flag konten HTML (teks berformat). */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE notes ADD COLUMN locked INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE notes ADD COLUMN contentHtml INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v3 -> v4: pola ulang pengingat (0=sekali, 1=harian, 2=mingguan). */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE notes ADD COLUMN reminderRepeat INTEGER NOT NULL DEFAULT 0");
        }
    };

    /** v4 -> v5: path video live untuk Foto Live (null = foto biasa). */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE images ADD COLUMN liveVideoPath TEXT");
        }
    };

    /**
     * v5 -> v6: tabel rekaman suara (audio_refs) untuk fitur Rekaman Suara.
     * Tabel BARU — data lama tidak tersentuh, migrasi aman.
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `audio_refs` ("
                    + "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                    + "`noteId` INTEGER NOT NULL, "
                    + "`encPath` TEXT, "
                    + "`durationMs` INTEGER NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_audio_refs_noteId` "
                    + "ON `audio_refs` (`noteId`)");
        }
    };

    public abstract NoteDao noteDao();

    public abstract ImageDao imageDao();

    public abstract AudioDao audioDao();

    public abstract FolderDao folderDao();

    public abstract TagDao tagDao();

    public static AppDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    Context app = ctx.getApplicationContext();
                    instance = Room.databaseBuilder(app, AppDatabase.class, "hozin_notes.db")
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                                    MIGRATION_4_5, MIGRATION_5_6)
                            .build();
                }
            }
        }
        return instance;
    }
}
