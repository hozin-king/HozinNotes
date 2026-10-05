package com.hozinking.notes;

import android.app.Application;

import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.SettingsActivity;

import net.sqlcipher.database.SQLiteDatabase;

/** Inisialisasi global: SQLCipher, database utama, dan repository. */
public class HozinNotesApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SettingsActivity.applySavedTheme(this);
        SQLiteDatabase.loadLibs(this); // wajib sebelum VaultDatabase (SQLCipher) dibuka
        AppDatabase.get(this);
        NoteRepository.get(this);
    }
}
