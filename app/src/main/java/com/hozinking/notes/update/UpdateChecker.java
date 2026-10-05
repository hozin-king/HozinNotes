package com.hozinking.notes.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.hozinking.notes.BuildConfig;
import com.hozinking.notes.R;
import com.hozinking.notes.ui.dialog.IosDialog;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Cek pembaruan aplikasi dari manifest JSON publik.
 * Graceful: offline / 404 / timeout / JSON rusak -> diam (tidak crash, tidak spam).
 */
public class UpdateChecker {
    /** Repo public ini dibuat user; lihat UPDATE_MANIFEST.md. */
    public static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/hozin-king/hozin-chat-worker/main/hozin-notes-update.json";

    private static final String PREFS = "hozinkan_update";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 12000;

    public interface Callback {
        void onResult(UpdateInfo info); // null bila tidak ada update / gagal diam-diam

        void onError(); // hanya dipakai untuk mode manual (tampilkan toast ringan)
    }

    public static class UpdateInfo {
        public int versionCode;
        public String versionName;
        public String changelog;
        public String apkUrl;
        public boolean force;
    }

    /** Cek otomatis max 1x/24 jam. Panggil dari MainActivity.onCreate. */
    public static void maybeAutoCheck(Activity activity) {
        SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong(KEY_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        check(activity, false, new Callback() {
            @Override
            public void onResult(UpdateInfo info) {
                p.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                if (info != null) showDialog(activity, info);
            }

            @Override
            public void onError() {
                // auto-check gagal -> diam total
            }
        });
    }

    /** Cek manual dari tombol "Cek pembaruan". */
    public static void checkManual(Activity activity) {
        check(activity, true, new Callback() {
            @Override
            public void onResult(UpdateInfo info) {
                if (info != null) {
                    showDialog(activity, info);
                } else {
                    android.widget.Toast.makeText(activity,
                            activity.getString(R.string.update_none),
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError() {
                android.widget.Toast.makeText(activity,
                        activity.getString(R.string.update_check_fail),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void check(Context ctx, boolean manual, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            UpdateInfo info = null;
            boolean ok = false;
            try {
                info = fetch();
                ok = true;
            } catch (Exception ignored) {
            }
            final UpdateInfo f = (ok && info != null
                    && info.versionCode > BuildConfig.VERSION_CODE) ? info : null;
            final boolean fok = ok;
            main.post(() -> {
                if (fok) cb.onResult(f);
                else cb.onError();
            });
        }).start();
    }

    private static UpdateInfo fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "HozinNotes/" + BuildConfig.VERSION_NAME);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        JSONObject j = new JSONObject(sb.toString());
        UpdateInfo i = new UpdateInfo();
        i.versionCode = j.getInt("versionCode");
        i.versionName = j.optString("versionName", "?");
        i.changelog = j.optString("changelog", "");
        i.apkUrl = j.optString("apkUrl", "");
        i.force = j.optBoolean("force", false);
        return i;
    }

    private static void showDialog(Activity activity, UpdateInfo info) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String msg = activity.getString(R.string.update_available_msg,
                info.versionName, info.changelog);
        IosDialog d = new IosDialog(activity)
                .setTitle(activity.getString(R.string.update_available_title))
                .setMessage(msg)
                .setPositive(activity.getString(R.string.update_download), () -> {
                    try {
                        Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl));
                        activity.startActivity(it);
                    } catch (Exception ignored) {
                    }
                });
        if (!info.force) {
            d.setNegative(activity.getString(R.string.update_later), () -> {});
        }
        d.show();
    }
}
