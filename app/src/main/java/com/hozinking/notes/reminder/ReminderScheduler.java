package com.hozinking.notes.reminder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.db.NoteEntity;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * Jadwal/batal pengingat catatan via AlarmManager exact alarm.
 * Hemat baterai: satu exact alarm per pengingat aktif, tanpa polling.
 */
public class ReminderScheduler {
    public static final String ACTION_REMIND = "com.hozinking.notes.ACTION_REMIND";
    public static final String EXTRA_NOTE_ID = "note_id";

    /** Pola ulang pengingat. */
    public static final int REPEAT_NONE = 0;
    public static final int REPEAT_DAILY = 1;
    public static final int REPEAT_WEEKLY = 2;
    /** v1.14: tiap bulan (tanggal yang sama; akhir bulan menyesuaikan kalender). */
    public static final int REPEAT_MONTHLY = 3;

    /** Tunda (snooze) default: 10 menit. */
    public static final long SNOOZE_DELAY_MS = 10L * 60 * 1000;

    /** true bila aplikasi boleh memakai exact alarm (Android 12+ bisa dicabut user). */
    public static boolean canScheduleExact(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /** Intent untuk meminta izin exact alarm (buka Settings sistem). */
    public static Intent exactAlarmSettingsIntent() {
        return new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
    }

    public static void schedule(Context ctx, long noteId, long timeMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx, noteId, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMs, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, timeMs, pi);
            }
        } catch (SecurityException e) {
            // Izin exact alarm dicabut: fallback ke inexact agar tetap ada notifikasi.
            am.set(AlarmManager.RTC_WAKEUP, timeMs, pi);
        }
    }

    public static void cancel(Context ctx, long noteId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(ctx, noteId, PendingIntent.FLAG_NO_CREATE));
    }

    /**
     * Tunda pengingat: bunyikan lagi setelah delayMs dari sekarang.
     * reminderRepeat TIDAK diubah (pola ulang tetap berlaku).
     */
    public static void snooze(Context ctx, long noteId, long delayMs) {
        long when = System.currentTimeMillis() + delayMs;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                AppDatabase.get(ctx).noteDao()
                        .setReminder(noteId, when, System.currentTimeMillis());
            } catch (Exception ignored) {
            }
        });
        schedule(ctx, noteId, when);
    }

    /**
     * Hitung kemunculan berikutnya dari pola ulang, selalu di masa depan.
     * from = waktu bunyi terakhir (atau yang dijadwalkan).
     */
    public static long nextRepeatTime(long from, int repeat) {
        if (repeat == REPEAT_MONTHLY) {
            return nextMonthly(from);
        }
        long step;
        if (repeat == REPEAT_DAILY) {
            step = 24L * 3600 * 1000;
        } else if (repeat == REPEAT_WEEKLY) {
            step = 7L * 24 * 3600 * 1000;
        } else {
            return 0;
        }
        long next = from + step;
        long now = System.currentTimeMillis();
        // Kejar ketertinggalan (mis. HP mati lama): lompat sampai > sekarang.
        while (next <= now) {
            next += step;
        }
        return next;
    }

    /**
     * Kemunculan berikutnya untuk pola bulanan: tambah 1 bulan per langkah
     * via Calendar (menangani Februari/tanggal 29-31 secara alami),
     * kejar ketertinggalan sampai di masa depan.
     */
    private static long nextMonthly(long from) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(from);
        long now = System.currentTimeMillis();
        long next = from;
        int guard = 0;
        while (next <= now && guard++ < 120) { // maks 10 tahun ke depan
            c.add(java.util.Calendar.MONTH, 1);
            next = c.getTimeInMillis();
        }
        return next > now ? next : 0;
    }

    /** Jadwal ulang semua pengingat aktif (dipakai saat BOOT_COMPLETED). */
    public static void rescheduleAll(Context ctx) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<NoteEntity> list = AppDatabase.get(ctx)
                        .noteDao().getActiveReminders(System.currentTimeMillis());
                for (NoteEntity n : list) {
                    schedule(ctx.getApplicationContext(), n.id, n.reminderTime);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static PendingIntent pending(Context ctx, long noteId, int flags) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(ACTION_REMIND);
        i.putExtra(EXTRA_NOTE_ID, noteId);
        int f = flags | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, (int) noteId, i, f);
    }
}
