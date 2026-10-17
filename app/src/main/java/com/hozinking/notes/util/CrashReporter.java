package com.hozinking.notes.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.hozinking.notes.R;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.net.URLEncoder;

/**
 * v1.17: pelapor crash dalam aplikasi.
 * Konsep dialog crash-report dipinjam dari NotallyX (Crustack/NotallyX, GPL-3.0)
 * — ide dipinjam, implementasi ditulis sendiri.
 * <p>
 * UncaughtExceptionHandler menulis stack trace ke filesDir/crash/last_crash.txt,
 * lalu saat aplikasi dibuka berikutnya dialog menawarkan: laporkan via GitHub
 * issues (pre-filled) atau hapus. Tanpa izin, tanpa database.
 */
public final class CrashReporter {

    private static final String DIR = "crash";
    private static final String FILE = "last_crash.txt";
    private static final int MAX_BODY = 6000;
    private static final String ISSUES_URL =
            "https://github.com/hozin-king/HozinNotes/issues/new";

    private CrashReporter() {
    }

    /** Pasang seawal mungkin (Application.onCreate). */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File dir = new File(app.getFilesDir(), DIR);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (PrintWriter pw = new PrintWriter(new FileWriter(
                        new File(dir, FILE)))) {
                    pw.println("time=" + System.currentTimeMillis());
                    pw.println("model=" + Build.MANUFACTURER + " " + Build.MODEL);
                    pw.println("android=" + Build.VERSION.RELEASE
                            + " (SDK " + Build.VERSION.SDK_INT + ")");
                    pw.println("thread=" + (t == null ? "?" : t.getName()));
                    if (e != null) e.printStackTrace(pw);
                }
            } catch (Exception ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /**
     * Panggil sekali dari MainActivity.onCreate: bila ada crash tersimpan,
     * tampilkan dialog penawaran pelaporan.
     */
    public static void checkAndOffer(Activity activity) {
        final File f = crashFile(activity);
        if (!f.exists()) return;
        String body;
        try {
            body = readAll(f);
        } catch (Exception e) {
            return;
        }
        if (body == null || body.trim().isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return;
        }
        final String report = body;
        new IosDialog(activity)
                .setTitle(activity.getString(R.string.crash_title))
                .setMessage(activity.getString(R.string.crash_message))
                .setPositive(activity.getString(R.string.crash_report), () -> {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    openIssue(activity, report);
                })
                .setNegative(activity.getString(R.string.crash_dismiss), () -> {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                })
                .show();
    }

    private static File crashFile(Context ctx) {
        return new File(new File(ctx.getFilesDir(), DIR), FILE);
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > MAX_BODY + 500) break;
            }
        }
        return sb.toString();
    }

    private static void openIssue(Activity activity, String body) {
        try {
            String trimmed = body.length() > MAX_BODY
                    ? body.substring(0, MAX_BODY) + "\n...(dipotong)"
                    : body;
            String text = "Laporan crash otomatis dari aplikasi:\n\n```\n"
                    + trimmed + "\n```";
            String url = ISSUES_URL
                    + "?title=" + URLEncoder.encode("Crash report", "UTF-8")
                    + "&body=" + URLEncoder.encode(text, "UTF-8");
            activity.startActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }
}
