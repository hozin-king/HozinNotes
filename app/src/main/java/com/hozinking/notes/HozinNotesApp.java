package com.hozinking.notes;

import android.app.Application;

import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.SettingsActivity;
import com.hozinking.notes.util.CrashReporter;

import net.sqlcipher.database.SQLiteDatabase;

/** Inisialisasi global: SQLCipher, database utama, dan repository. */
public class HozinNotesApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SettingsActivity.applySavedTheme(this);
        CrashReporter.install(this); // v1.17: pelapor crash dalam aplikasi
        SQLiteDatabase.loadLibs(this); // wajib sebelum VaultDatabase (SQLCipher) dibuka
        AppDatabase.get(this);
        NoteRepository.get(this);
    }
}
