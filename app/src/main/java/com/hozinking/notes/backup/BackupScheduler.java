package com.hozinking.notes.backup;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.hozinking.notes.worker.AutoBackupWorker;

import java.util.concurrent.TimeUnit;

/**
 * Penjadwalan auto-backup via WorkManager (ala WhatsApp: harian/mingguan, opsi WiFi).
 */
public class BackupScheduler {

    public static final String TAG = "hozin_auto_backup";

    /**
     * @param intervalDays tiap berapa hari (min 1)
     * @param wifiOnly     true = hanya saat WiFi (UNMETERED)
     */
    public static void schedule(Context ctx, int intervalDays, boolean wifiOnly) {
        int days = Math.max(1, intervalDays);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                AutoBackupWorker.class, days, TimeUnit.DAYS)
                .setConstraints(constraints)
                .addTag(TAG)
                .build();
        WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(TAG);
    }

    /** Jalankan backup sekali sekarang (di luar jadwal). */
    public static void backupNow(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(AutoBackupWorker.class)
                .addTag(TAG)
                .build();
        WorkManager.getInstance(ctx).enqueue(req);
    }
}
