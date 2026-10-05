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
        FolderEntity.class,
        TagEntity.class,
        NoteTagCrossRef.class,
        NoteFts.class
}, version = 3, exportSchema = false)
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

    public abstract NoteDao noteDao();

    public abstract ImageDao imageDao();

    public abstract FolderDao folderDao();

    public abstract TagDao tagDao();

    public static AppDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    Context app = ctx.getApplicationContext();
                    instance = Room.databaseBuilder(app, AppDatabase.class, "hozin_notes.db")
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                            .build();
                }
            }
        }
        return instance;
    }
}
