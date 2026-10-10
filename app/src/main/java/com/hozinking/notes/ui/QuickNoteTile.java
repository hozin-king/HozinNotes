package com.hozinking.notes.ui;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

/**
 * Quick Settings Tile "Catatan cepat": buka editor catatan baru dari mana saja
 * tanpa membuka aplikasi dulu.
 *
 * Terinspirasi pola quick-capture aplikasi catatan populer (NotallyX dkk) —
 * implementasi sendiri memakai TileService bawaan Android (API 24+; minSdk
 * aplikasi ini 26 sehingga aman tanpa cabang versi).
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class QuickNoteTile extends TileService {

    @Override
    public void onClick() {
        super.onClick();
        unlockAndRun(() -> {
            Intent i = new Intent(this, EditorActivity.class);
            i.putExtra("noteId", -1L);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(i);
        });
    }
}
