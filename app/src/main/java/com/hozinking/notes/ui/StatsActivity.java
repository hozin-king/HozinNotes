package com.hozinking.notes.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.NoteStats;
import com.hozinking.notes.data.repo.NoteRepository;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * v1.22: Statistik pemakaian catatan — ringkasan jumlah, aktivitas 7 hari,
 * isi (karakter & catatan terpanjang), jenis catatan, dan sebaran per folder.
 * Semua angka dihitung read-only dari database lokal.
 */
public class StatsActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Locale id = Locale.forLanguageTag("id-ID");

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);
        NoteRepository.get(this).noteStats(s -> main.post(() -> render(s)));
    }

    private String num(long n) {
        return NumberFormat.getNumberInstance(id).format(n);
    }

    private void setRow(int rowId, int labelId, int valueId, String label, String value) {
        View row = findViewById(rowId);
        if (row == null) return;
        ((TextView) row.findViewById(labelId)).setText(label);
        ((TextView) row.findViewById(valueId)).setText(value);
    }

    private void render(NoteStats s) {
        // Ringkasan
        setRow(R.id.stats_row_active, R.id.row_label, R.id.row_value,
                getString(R.string.usage_active), num(s.active));
        setRow(R.id.stats_row_archived, R.id.row_label, R.id.row_value,
                getString(R.string.usage_archived), num(s.archived));
        setRow(R.id.stats_row_trashed, R.id.row_label, R.id.row_value,
                getString(R.string.usage_trashed), num(s.trashed));

        // Aktivitas
        setRow(R.id.stats_row_week, R.id.row_label, R.id.row_value,
                getString(R.string.usage_week), num(s.createdLast7d));
        String topDay = s.topDayDate == null
                ? getString(R.string.usage_none)
                : getString(R.string.usage_topday_value, s.topDayDate, s.topDayCount);
        setRow(R.id.stats_row_topday, R.id.row_label, R.id.row_value,
                getString(R.string.usage_topday), topDay);

        // Isi
        setRow(R.id.stats_row_chars, R.id.row_label, R.id.row_value,
                getString(R.string.usage_chars), num(s.totalChars));
        setRow(R.id.stats_row_avgchars, R.id.row_label, R.id.row_value,
                getString(R.string.usage_avgchars), num(s.avgChars));
        String longest = s.longestTitle == null
                ? getString(R.string.usage_none)
                : getString(R.string.usage_longest_value, s.longestTitle, num(s.longestLen));
        setRow(R.id.stats_row_longest, R.id.row_label, R.id.row_value,
                getString(R.string.usage_longest), longest);

        // Jenis
        setRow(R.id.stats_row_reminder, R.id.row_label, R.id.row_value,
                getString(R.string.usage_reminder), num(s.withReminder));
        setRow(R.id.stats_row_locked, R.id.row_label, R.id.row_value,
                getString(R.string.usage_locked), num(s.locked));
        setRow(R.id.stats_row_checklist, R.id.row_label, R.id.row_value,
                getString(R.string.usage_checklist), num(s.checklist));
        setRow(R.id.stats_row_images, R.id.row_label, R.id.row_value,
                getString(R.string.usage_images), num(s.withImages));

        // Per folder
        LinearLayout folderBox = findViewById(R.id.stats_folders);
        folderBox.removeAllViews();
        if (s.folders.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.usage_none));
            empty.setTextColor(0xFF757575);
            folderBox.addView(empty);
        } else {
            for (NoteStats.FolderRow fr : s.folders) {
                View row = getLayoutInflater().inflate(
                        R.layout.item_stat_row, folderBox, false);
                ((TextView) row.findViewById(R.id.row_label)).setText(fr.name);
                ((TextView) row.findViewById(R.id.row_value))
                        .setText(getString(R.string.usage_notes_count, num(fr.count)));
                folderBox.addView(row);
            }
        }
    }
}
