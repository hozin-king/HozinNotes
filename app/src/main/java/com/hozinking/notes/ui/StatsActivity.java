package com.hozinking.notes.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.db.NoteStats;
import com.hozinking.notes.data.repo.NoteRepository;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * v1.22: Statistik pemakaian catatan — ringkasan jumlah, aktivitas 7 hari,
 * isi (karakter & catatan terpanjang), jenis catatan, dan sebaran per folder.
 * Semua angka dihitung read-only dari database lokal.
 *
 * v1.25: + pengingat terdekat & "Kenangan hari ini" (catatan yang dibuat
 * pada tanggal-bulan hari ini di tahun sebelumnya; konsep "that year today"
 * ala jotsy — ide dipinjam, implementasi sendiri). Ketuk baris kenangan /
 * pengingat untuk membuka catatan (catatan terkunci tetap lewat gerbang PIN /
 * biometrik yang sama seperti dari daftar).
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

        // v1.25: pengingat terdekat
        View nextRow = findViewById(R.id.stats_row_next_reminder);
        if (nextRow != null) {
            if (s.nextReminder != null) {
                final NoteEntity n = s.nextReminder;
                setRow(R.id.stats_row_next_reminder, R.id.row_label, R.id.row_value,
                        getString(R.string.usage_next_reminder),
                        getString(R.string.usage_next_reminder_value,
                                noteTitle(n), reminderWhen(n.reminderTime)));
                nextRow.setOnClickListener(v -> openNote(n));
            } else {
                setRow(R.id.stats_row_next_reminder, R.id.row_label, R.id.row_value,
                        getString(R.string.usage_next_reminder),
                        getString(R.string.usage_none));
                nextRow.setOnClickListener(null);
            }
        }

        // v1.25: kenangan hari ini (dibuat tanggal-bulan ini, tahun sebelumnya)
        LinearLayout memBox = findViewById(R.id.stats_memories);
        if (memBox != null) {
            memBox.removeAllViews();
            if (s.onThisDay.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText(getString(R.string.usage_memories_empty));
                empty.setTextColor(0xFF757575);
                memBox.addView(empty);
            } else {
                for (NoteEntity n : s.onThisDay) {
                    View row = getLayoutInflater().inflate(
                            R.layout.item_stat_row, memBox, false);
                    ((TextView) row.findViewById(R.id.row_label))
                            .setText(noteTitle(n));
                    ((TextView) row.findViewById(R.id.row_value))
                            .setText(memoryLabel(n.createdAt));
                    row.setOnClickListener(v -> openNote(n));
                    memBox.addView(row);
                }
            }
        }
    }

    private String noteTitle(NoteEntity n) {
        if (n.title == null || n.title.trim().isEmpty()) return "(tanpa judul)";
        return n.title.trim();
    }

    private void openNote(NoteEntity n) {
        NoteLockGate.openMaybeLocked(this, n, noteId -> {
            Intent i = new Intent(this, EditorActivity.class);
            i.putExtra("noteId", noteId);
            startActivity(i);
        });
    }

    /** "besok (26 Okt 2026)" / "hari ini" / "dalam 5 hari (30 Okt 2026)". */
    private String reminderWhen(long timeMs) {
        String date = new SimpleDateFormat("d MMM yyyy", id).format(new Date(timeMs));
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timeMs);
        target.set(Calendar.HOUR_OF_DAY, 0);
        target.set(Calendar.MINUTE, 0);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        long days = (target.getTimeInMillis() - today.getTimeInMillis())
                / (24L * 3600 * 1000);
        String rel = days <= 0
                ? getString(R.string.usage_reminder_today)
                : days == 1
                ? getString(R.string.usage_reminder_tomorrow)
                : getString(R.string.usage_reminder_in_days, days);
        return rel + " (" + date + ")";
    }

    /** "25 Okt 2023 • 2 tahun lalu" (tahun yang sama tak mungkin muncul di sini). */
    private String memoryLabel(long createdAtMs) {
        String date = new SimpleDateFormat("d MMM yyyy", id).format(new Date(createdAtMs));
        Calendar now = Calendar.getInstance();
        Calendar created = Calendar.getInstance();
        created.setTimeInMillis(createdAtMs);
        int years = now.get(Calendar.YEAR) - created.get(Calendar.YEAR);
        String ago = years <= 1
                ? getString(R.string.usage_memory_year_ago)
                : getString(R.string.usage_memory_years_ago, years);
        return date + " • " + ago;
    }
}
