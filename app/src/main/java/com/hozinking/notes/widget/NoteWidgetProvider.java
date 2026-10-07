package com.hozinking.notes.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.Html;
import android.widget.RemoteViews;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.AppDatabase;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.ui.EditorActivity;

import java.util.concurrent.Executors;

/**
 * Widget layar utama: menampilkan catatan aktif terbaru (pin didahulukan),
 * ketuk untuk membuka, tombol "+" untuk catatan baru.
 * Terinspirasi kapabilitas widget Omni-Notes; implementasi sendiri.
 */
public class NoteWidgetProvider extends AppWidgetProvider {
    private static final int REQ_ADD = 0x7E57;
    /** Throttle refresh agar penyimpanan cepat beruntun tidak spam query. */
    private static volatile long lastRefreshMs = 0;

    @Override
    public void onUpdate(Context context, AppWidgetManager awm, int[] appWidgetIds) {
        refreshAll(context);
    }

    /** Segarkan semua instance widget (aman dipanggil dari thread mana pun). */
    public static void refreshAll(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < 10_000) return; // throttle 10 detik
        lastRefreshMs = now;
        final Context app = ctx.getApplicationContext();
        Executors.newSingleThreadExecutor().execute(() -> {
            NoteEntity n = null;
            try {
                n = AppDatabase.get(app).noteDao().getLatestActive();
            } catch (Exception ignored) {
            }
            final NoteEntity note = n;
            AppWidgetManager awm = AppWidgetManager.getInstance(app);
            int[] ids = awm.getAppWidgetIds(
                    new ComponentName(app, NoteWidgetProvider.class));
            for (int id : ids) {
                awm.updateAppWidget(id, buildViews(app, note));
            }
        });
    }

    private static RemoteViews buildViews(Context ctx, NoteEntity n) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_note);
        if (n == null) {
            v.setTextViewText(R.id.widget_title, ctx.getString(R.string.widget_label));
            v.setTextViewText(R.id.widget_content, ctx.getString(R.string.widget_empty));
        } else {
            String title = (n.title == null || n.title.trim().isEmpty())
                    ? ctx.getString(R.string.notes_no_title) : n.title.trim();
            v.setTextViewText(R.id.widget_title, (n.pinned ? "📌 " : "") + title);
            if (n.locked) {
                v.setTextViewText(R.id.widget_content, "🔒 " + ctx.getString(R.string.note_lock));
            } else {
                v.setTextViewText(R.id.widget_content, previewText(n));
            }
        }
        // Ketuk kartu -> buka catatan (atau buat baru bila kosong).
        Intent open = new Intent(ctx, EditorActivity.class);
        if (n != null) open.putExtra("noteId", n.id);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int reqOpen = n == null ? REQ_ADD + 1 : (int) n.id;
        v.setOnClickPendingIntent(R.id.widget_root,
                PendingIntent.getActivity(ctx, reqOpen, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // Tombol + -> catatan baru.
        Intent add = new Intent(ctx, EditorActivity.class);
        add.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.widget_add,
                PendingIntent.getActivity(ctx, REQ_ADD, add,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return v;
    }

    /** Pratinjau isi: teks polos, marker checklist disederhanakan. */
    private static String previewText(NoteEntity n) {
        String body = n.content == null ? "" : n.content;
        if (n.contentHtml) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
            } else {
                body = Html.fromHtml(body).toString();
            }
        }
        body = body.replace("- [ ]", "☐").replace("- [x]", "☑").replace("- [X]", "☑");
        body = body.trim();
        if (body.length() > 220) body = body.substring(0, 220) + "…";
        return body;
    }
}
