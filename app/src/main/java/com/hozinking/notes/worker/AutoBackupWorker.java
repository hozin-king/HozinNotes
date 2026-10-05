package com.hozinking.notes.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hozinking.notes.backup.DriveBackupManager;
import com.hozinking.notes.backup.LocalBackupManager;

import java.io.File;

/**
 * Auto-backup berkala: bangun ZIP lokal -> upload ke Drive appDataFolder ->
 * sisakan 3 versi terbaru. Dijadwalkan via BackupScheduler.
 */
public class AutoBackupWorker extends Worker {

    public AutoBackupWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            if (!DriveBackupManager.isAvailable()) return Result.failure();
            Context ctx = getApplicationContext();
            // Belum login Google: gagal permanen, JANGAN retry tanpa batas.
            if (!DriveBackupManager.isSignedIn(ctx)) return Result.failure();
            File zip = LocalBackupManager.buildBackupZip(ctx);
            try {
                DriveBackupManager dm = new DriveBackupManager();
                dm.uploadBlocking(ctx, zip);
                dm.pruneBlocking(ctx, 3);
            } finally {
                zip.delete();
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
