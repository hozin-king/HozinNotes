package com.hozinking.notes.reminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.ui.EditorActivity;

import java.util.concurrent.Executors;

/**
 * Menerima alarm pengingat -> tampilkan notifikasi -> tap membuka catatan.
 * Pengingat sekali pakai: reminderTime di-reset. Pengingat berulang (harian/
 * mingguan): dijadwalkan ulang ke kemunculan berikutnya.
 */
public class ReminderReceiver extends BroadcastReceiver {
    public static final String CHANNEL_ID = "reminder_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ReminderScheduler.ACTION_REMIND.equals(intent.getAction())) return;
        long noteId = intent.getLongExtra(ReminderScheduler.EXTRA_NOTE_ID, -1);
        if (noteId < 0) return;
        final PendingResult pr = goAsync();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                NoteEntity n = AppDatabase.get(context).noteDao().getById(noteId);
                // Hanya bunyikan bila note masih ada, tidak di sampah, dan waktunya tiba.
                if (n != null && !n.trashed && n.reminderTime > 0
                        && n.reminderTime <= System.currentTimeMillis() + 60_000) {
                    showNotification(context, n);
                    long now = System.currentTimeMillis();
                    if (n.reminderRepeat == ReminderScheduler.REPEAT_DAILY
                            || n.reminderRepeat == ReminderScheduler.REPEAT_WEEKLY
                            || n.reminderRepeat == ReminderScheduler.REPEAT_MONTHLY) {
                        // Berulang: jadwalkan kemunculan berikutnya.
                        long next = ReminderScheduler.nextRepeatTime(n.reminderTime,
                                n.reminderRepeat);
                        AppDatabase.get(context).noteDao().setReminder(noteId, next, now);
                        ReminderScheduler.schedule(context.getApplicationContext(),
                                noteId, next);
                    } else {
                        // Sekali pakai: reset agar tidak bunyi lagi.
                        AppDatabase.get(context).noteDao().setReminder(noteId, 0, now);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        });
    }

    private void showNotification(Context ctx, NoteEntity n) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(ctx.getString(R.string.reminder_channel_desc));
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, EditorActivity.class);
        open.putExtra("noteId", n.id);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) n.id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Tombol "Tunda 10 menit" (snooze).
        Intent snooze = new Intent(ctx, SnoozeReceiver.class);
        snooze.setAction(SnoozeReceiver.ACTION_SNOOZE);
        snooze.putExtra(ReminderScheduler.EXTRA_NOTE_ID, n.id);
        PendingIntent snoozePi = PendingIntent.getBroadcast(ctx, (int) n.id + 0x100000,
                snooze, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = (n.title == null || n.title.trim().isEmpty())
                ? ctx.getString(R.string.notes_no_title) : n.title.trim();
        String text = n.content == null ? "" : n.content.trim();
        if (text.length() > 120) text = text.substring(0, 120) + "…";

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("\u23F0 " + title)
                .setContentText(text.isEmpty() ? null : text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_recent_history,
                        ctx.getString(R.string.reminder_snooze), snoozePi);
        nm.notify((int) n.id, b.build());
    }
}
