package com.hozinking.notes.reminder;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.hozinking.notes.R;

import java.util.concurrent.Executors;

/**
 * Menangani tombol "Tunda" pada notifikasi pengingat: bunyikan lagi
 * setelah {@link ReminderScheduler#SNOOZE_DELAY_MS} (10 menit).
 * Pola ulang (harian/mingguan) tidak diubah.
 */
public class SnoozeReceiver extends BroadcastReceiver {
    public static final String ACTION_SNOOZE = "com.hozinking.notes.ACTION_SNOOZE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SNOOZE.equals(intent.getAction())) return;
        long noteId = intent.getLongExtra(ReminderScheduler.EXTRA_NOTE_ID, -1);
        if (noteId < 0) return;
        final PendingResult pr = goAsync();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ReminderScheduler.snooze(context.getApplicationContext(), noteId,
                        ReminderScheduler.SNOOZE_DELAY_MS);
                NotificationManager nm = (NotificationManager) context
                        .getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel((int) noteId);
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        });
        Toast.makeText(context.getApplicationContext(), R.string.reminder_snoozed,
                Toast.LENGTH_SHORT).show();
    }
}
