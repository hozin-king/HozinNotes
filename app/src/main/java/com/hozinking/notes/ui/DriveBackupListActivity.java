package com.hozinking.notes.ui;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.notes.R;
import com.hozinking.notes.backup.DriveBackupManager;
import com.hozinking.notes.backup.LocalBackupManager;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Daftar cadangan Google Drive (appDataFolder, folder tersembunyi khusus aplikasi).
 * Tiap baris: tanggal + ukuran + tombol "Pulihkan".
 * Restore = download zip dari Drive -> importZip lokal (overwrite), dengan konfirmasi dulu.
 */
public class DriveBackupListActivity extends AppCompatActivity {

    private final Executor bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DriveBackupManager mgr = new DriveBackupManager();

    private ProgressBar progress;
    private TextView tvState;
    private Button btnRetry;
    private LinearLayout listBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(getString(R.string.drive_backups_title));
        buildUi();
        if (!DriveBackupManager.isAvailable() || !DriveBackupManager.isSignedIn(this)) {
            showError(getString(R.string.drive_login_first));
            return;
        }
        load();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(48), dp(48));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(32);
        root.addView(progress, pp);

        tvState = new TextView(this);
        tvState.setGravity(Gravity.CENTER);
        tvState.setTextSize(15);
        tvState.setPadding(0, dp(16), 0, 0);
        tvState.setVisibility(View.GONE);
        root.addView(tvState, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        btnRetry = new Button(this);
        btnRetry.setText(getString(R.string.drive_backups_retry));
        btnRetry.setAllCaps(false);
        btnRetry.setVisibility(View.GONE);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.gravity = Gravity.CENTER_HORIZONTAL;
        rp.topMargin = dp(12);
        btnRetry.setOnClickListener(v -> load());
        root.addView(btnRetry, rp);

        ScrollView scroll = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, sp);

        setContentView(root);
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        tvState.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
        listBox.removeAllViews();
        tvState.setText(getString(R.string.drive_backups_loading));
        tvState.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            try {
                List<DriveBackupManager.DriveFile> files = mgr.listBlocking(this);
                main.post(() -> showList(files));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> showError(getString(R.string.drive_backups_error, msg)));
            }
        });
    }

    private void showList(List<DriveBackupManager.DriveFile> files) {
        progress.setVisibility(View.GONE);
        btnRetry.setVisibility(View.GONE);
        listBox.removeAllViews();
        if (files == null || files.isEmpty()) {
            tvState.setText(getString(R.string.drive_backups_empty));
            tvState.setVisibility(View.VISIBLE);
            return;
        }
        tvState.setVisibility(View.GONE);
        SimpleDateFormat fmt = new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("id", "ID"));
        for (DriveBackupManager.DriveFile f : files) {
            listBox.addView(makeRow(f, fmt));
        }
    }

    private View makeRow(DriveBackupManager.DriveFile f, SimpleDateFormat fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);

        TextView tvDate = new TextView(this);
        tvDate.setTextSize(16);
        String date = f.modifiedTime > 0 ? fmt.format(new Date(f.modifiedTime)) : f.name;
        tvDate.setText(date);

        TextView tvSize = new TextView(this);
        tvSize.setTextSize(13);
        tvSize.setText(humanSize(f.size) + "  •  " + f.name);

        info.addView(tvDate);
        info.addView(tvSize);

        Button btn = new Button(this);
        btn.setText(getString(R.string.drive_restore));
        btn.setAllCaps(false);
        btn.setOnClickListener(v -> askRestore(f));

        row.addView(info, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(btn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void showError(String msg) {
        progress.setVisibility(View.GONE);
        listBox.removeAllViews();
        tvState.setText(msg);
        tvState.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.VISIBLE);
    }

    private void askRestore(DriveBackupManager.DriveFile f) {
        new IosDialog(this)
                .setTitle(getString(R.string.drive_restore_title))
                .setMessage(getString(R.string.drive_restore_confirm))
                .setDestructive(true)
                .setPositive(getString(R.string.yes), () -> doRestore(f))
                .setNegative(getString(R.string.no), null)
                .show();
    }

    private void doRestore(DriveBackupManager.DriveFile f) {
        progress.setVisibility(View.VISIBLE);
        tvState.setText(getString(R.string.drive_downloading));
        tvState.setVisibility(View.VISIBLE);
        btnRetry.setVisibility(View.GONE);
        listBox.removeAllViews();
        bg.execute(() -> {
            File tmp = new File(getCacheDir(), "drive_restore_" + System.currentTimeMillis() + ".zip");
            try {
                boolean ok = mgr.downloadBlocking(this, f.id, tmp);
                if (!ok) throw new Exception(getString(R.string.drive_restore_fail));
                main.post(() -> tvState.setText(getString(R.string.backup_restoring)));
                // Restore = overwrite: catatan saat ini diganti (sesuai konfirmasi).
                LocalBackupManager.importZip(this, Uri.fromFile(tmp), true,
                        null,
                        result -> main.post(() -> {
                            tmp.delete();
                            progress.setVisibility(View.GONE);
                            if (result != null && result.ok) {
                                Toast.makeText(this,
                                        getString(R.string.drive_restore_ok, result.notes, result.images),
                                        Toast.LENGTH_LONG).show();
                                load();
                            } else {
                                String err = (result != null && result.error != null)
                                        ? result.error : getString(R.string.drive_restore_fail);
                                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                                showError(err);
                            }
                        }));
            } catch (Exception e) {
                tmp.delete();
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                main.post(() -> {
                    Toast.makeText(this, getString(R.string.drive_restore_fail),
                            Toast.LENGTH_LONG).show();
                    showError(msg);
                });
            }
        });
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
