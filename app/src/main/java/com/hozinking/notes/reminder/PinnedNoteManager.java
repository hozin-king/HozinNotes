package com.hozinking.notes.reminder;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.Html;

import androidx.core.app.NotificationCompat;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.ui.EditorActivity;

import java.util.concurrent.Executors;

/**
 * Sematkan satu catatan sebagai notifikasi persisten (ongoing) di bilah
 * notifikasi: ketuk untuk membuka catatan, tombol "Lepas" untuk menurunkan.
 * Terinspirasi pola nproth/pin (pin-as-notification); implementasi sendiri
 * memakai NotificationCompat + SharedPreferences, tanpa migrasi DB.
 *
 * Hanya satu catatan yang bisa disematkan dalam satu waktu; id disimpan di
 * SharedPreferences sehingga sematan dipulihkan setelah HP restart.
 */
public final class PinnedNoteManager {
    private PinnedNoteManager() {
    }

    public static final String CHANNEL_ID = "pinned_note_channel";
    /** Id notifikasi tetap untuk sematan (satu slot). */
    private static final int NOTIF_ID = 9001;
    private static final String PREFS = "pinned_note_prefs";
    private static final String KEY_PINNED_ID = "pinned_note_id";

    public static boolean isPinned(Context ctx, long noteId) {
        return prefs(ctx).getLong(KEY_PINNED_ID, -1) == noteId;
    }

    public static boolean hasPinned(Context ctx) {
        return prefs(ctx).getLong(KEY_PINNED_ID, -1) > 0;
    }

    /** Sematkan catatan: baca dari DB di thread IO lalu tampilkan notifikasi. */
    public static void pin(Context ctx, long noteId) {
        final Context app = ctx.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                NoteEntity n = AppDatabase.get(app).noteDao().getById(noteId);
                if (n == null || n.trashed) {
                    clearPref(app);
                    return;
                }
                prefs(app).edit().putLong(KEY_PINNED_ID, noteId).apply();
                showNotification(app, n);
            } catch (Exception ignored) {
                clearPref(app);
            }
        });
    }

    /** Lepas sematan (tombol "Lepas" / menu) dan hapus notifikasi. */
    public static void unpin(Context ctx) {
        final Context app = ctx.getApplicationContext();
        clearPref(app);
        NotificationManager nm =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_ID);
    }

    /** Dipanggil saat catatan dibuang/dihapus: lepas sematan bila menunjuk id ini. */
    public static void unpinIfPinned(Context ctx, long noteId) {
        if (isPinned(ctx, noteId)) unpin(ctx);
    }

    /** Pulihkan sematan setelah boot; lepas bila catatan sudah tidak valid. */
    public static void restoreIfNeeded(Context ctx) {
        final Context app = ctx.getApplicationContext();
        long id = prefs(app).getLong(KEY_PINNED_ID, -1);
        if (id <= 0) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                NoteEntity n = AppDatabase.get(app).noteDao().getById(id);
                if (n == null || n.trashed) {
                    unpin(app);
                } else {
                    showNotification(app, n);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static void showNotification(Context ctx, NoteEntity n) {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    ctx.getString(R.string.pinned_notif_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(ctx.getString(R.string.pinned_notif_channel_desc));
            nm.createNotificationChannel(ch);
        }

        String title = (n.title == null || n.title.trim().isEmpty())
                ? ctx.getString(R.string.notes_no_title) : n.title.trim();
        String body = n.content == null ? "" : n.content;
        if (n.contentHtml) {
            body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
        }
        body = body.trim();
        if (body.length() > 500) body = body.substring(0, 500) + "…";

        Intent open = new Intent(ctx, EditorActivity.class);
        open.putExtra("noteId", n.id);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(ctx, NOTIF_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent unpin = new Intent(ctx, PinnedNoteReceiver.class);
        unpin.setAction(PinnedNoteReceiver.ACTION_UNPIN);
        PendingIntent unpinPi = PendingIntent.getBroadcast(ctx, NOTIF_ID + 1, unpin,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("\uD83D\uDCCC " + title)
                .setContentText(body.isEmpty() ? null : body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        ctx.getString(R.string.pinned_notif_unpin), unpinPi);
        nm.notify(NOTIF_ID, b.build());
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void clearPref(Context ctx) {
        prefs(ctx).edit().remove(KEY_PINNED_ID).apply();
    }
}
