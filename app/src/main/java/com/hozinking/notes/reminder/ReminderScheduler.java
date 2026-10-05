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
