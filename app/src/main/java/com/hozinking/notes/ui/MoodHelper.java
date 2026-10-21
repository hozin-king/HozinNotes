package com.hozinking.notes.ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * v1.21: utilitas fitur Mood Harian (konsep terinspirasi MyBrain).
 * Nilai mood 1..5; tanggal memakai format "yyyy-MM-dd" zona waktu perangkat.
 */
public final class MoodHelper {
    private MoodHelper() {}

    public static final int MIN = 1;
    public static final int MAX = 5;

    /** Emoji per nilai mood. */
    public static String emoji(int mood) {
        switch (mood) {
            case 1: return "\uD83D\uDE1E"; // 😞
            case 2: return "\uD83D\uDE15"; // 😕
            case 3: return "\uD83D\uDE10"; // 😐
            case 4: return "\uD83D\uDE42"; // 🙂
            case 5: return "\uD83D\uDE04"; // 😄
            default: return "•";
        }
    }

    /** Warna (ARGB) per nilai mood: merah -> hijau. */
    public static int color(int mood) {
        switch (mood) {
            case 1: return 0xFFE53935;
            case 2: return 0xFFFB8C00;
            case 3: return 0xFFFDD835;
            case 4: return 0xFF7CB342;
            case 5: return 0xFF1E88E5;
            default: return 0xFFBDBDBD;
        }
    }

    /** Label Bahasa Indonesia per nilai mood. */
    public static String label(int mood) {
        switch (mood) {
            case 1: return "Buruk";
            case 2: return "Kurang";
            case 3: return "Biasa";
            case 4: return "Baik";
            case 5: return "Luar biasa";
            default: return "-";
        }
    }

    /** Tanggal hari ini "yyyy-MM-dd". */
    public static String today() {
        return dayKey(Calendar.getInstance());
    }

    /** Kunci tanggal "yyyy-MM-dd" dari sebuah Calendar. */
    public static String dayKey(Calendar c) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }

    /**
     * Daftar 30 kunci tanggal terakhir (termasuk hari ini), urut menaik.
     */
    public static List<String> last30DayKeys() {
        List<String> out = new ArrayList<>(30);
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -29);
        for (int i = 0; i < 30; i++) {
            out.add(dayKey(c));
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return out;
    }

    /**
     * Hitung streak: jumlah hari beruntun (mundur dari hari ini, atau kemarin
     * bila hari ini belum dicatat) yang memiliki entri mood.
     */
    public static int streakDays(java.util.Set<String> recordedDates) {
        int streak = 0;
        Calendar c = Calendar.getInstance();
        if (!recordedDates.contains(dayKey(c))) {
            c.add(Calendar.DAY_OF_YEAR, -1);
        }
        while (recordedDates.contains(dayKey(c))) {
            streak++;
            c.add(Calendar.DAY_OF_YEAR, -1);
        }
        return streak;
    }
}
