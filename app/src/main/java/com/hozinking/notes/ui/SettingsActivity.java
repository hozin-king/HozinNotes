package com.hozinking.notes.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.hozinking.notes.R;
import com.hozinking.notes.backup.BackupScheduler;
import com.hozinking.notes.backup.LocalBackupManager;
import com.hozinking.notes.ui.dialog.IosActionSheet;
import com.hozinking.notes.ui.dialog.IosDialog;

/** Pengaturan: tema, kunci aplikasi, backup lokal, backup Drive, tentang. */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS = "settings";
    private static final String KEY_THEME = "theme";       // light | dark | system
    private static final String KEY_LOCK = "lock_enabled";
    private static final String KEY_BACKUP_DAYS = "backup_days";
    private static final String KEY_WIFI_ONLY = "wifi_only";

    private SharedPreferences prefs;
    private Dialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;

    private final java.util.concurrent.Executor bgDrive =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler mainH = new Handler(Looper.getMainLooper());
    private volatile boolean uploading = false;

    private final ActivityResultLauncher<Intent> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                doExport(uri);
                            }
                        }
                    });

    private final ActivityResultLauncher<Intent> importLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                askImportMode(uri);
                            }
                        }
                    });

    private final ActivityResultLauncher<Intent> googleSignInLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            try {
                                com.google.android.gms.auth.api.signin.GoogleSignIn
                                        .getSignedInAccountFromIntent(result.getData())
                                        .getResult(com.google.android.gms.common.api.ApiException.class);
                            } catch (com.google.android.gms.common.api.ApiException e) {
                                showSignInError(e.getStatusCode());
                            } catch (Exception e) {
                                android.widget.Toast.makeText(this,
                                        getString(R.string.drive_signin_failed),
                                        android.widget.Toast.LENGTH_LONG).show();
                            }
                        } else {
                            // Batal/ditolak user: tidak perlu toast, status tetap "belum login".
                        }
                        updateDriveStatus();
                    });

    /** Tampilkan pesan error login Google yang bisa ditindaklanjuti user. */
    private void showSignInError(int code) {
        String msg;
        if (code == 10) { // DEVELOPER_ERROR: SHA-1 / package belum terdaftar
            msg = getString(R.string.drive_signin_error10);
        } else if (code == 7) { // NETWORK_ERROR
            msg = getString(R.string.drive_signin_error7);
        } else if (code == 12501) { // SIGN_IN_CANCELLED
            return;
        } else {
            msg = getString(R.string.drive_signin_error_other, code);
        }
        new com.hozinking.notes.ui.dialog.IosDialog(this)
                .setTitle(getString(R.string.drive_signin_title))
                .setMessage(msg)
                .setPositive(getString(R.string.ok), () -> {})
                .show();
    }

    private Switch switchLock;

    /** Buat PIN kunci aplikasi: KEY_LOCK=true hanya bila PIN berhasil dibuat. */
    private final ActivityResultLauncher<Intent> lockPinLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            prefs.edit().putBoolean(KEY_LOCK, true).apply();
                            if (switchLock != null) switchLock.setChecked(true);
                        } else {
                            // Batal: jangan kunci aplikasi; kembalikan switch ke mati.
                            prefs.edit().putBoolean(KEY_LOCK, false).apply();
                            if (switchLock != null) switchLock.setChecked(false);
                            Toast.makeText(this, R.string.lock_pin_cancelled,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // ---------- Tampilan ----------
        TextView btnTheme = findViewById(R.id.btn_theme);
        btnTheme.setText(themeLabel(prefs.getString(KEY_THEME, "system")));
        btnTheme.setOnClickListener(v -> new IosActionSheet(this)
                .setTitle(getString(R.string.settings_theme))
                .addItem(getString(R.string.theme_light), () -> applyTheme("light", btnTheme))
                .addItem(getString(R.string.theme_dark), () -> applyTheme("dark", btnTheme))
                .addItem(getString(R.string.theme_system), () -> applyTheme("system", btnTheme))
                .show());

        // ---------- Kunci aplikasi ----------
        switchLock = findViewById(R.id.switch_lock);
        switchLock.setChecked(prefs.getBoolean(KEY_LOCK, false));
        switchLock.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Kunci app memakai PIN vault yang sama; buat dulu bila belum ada.
                // KEY_LOCK=true HANYA setelah PIN berhasil dibuat (anti-brick).
                if (!VaultPinActivity.isPinSet(this)) {
                    Intent i = new Intent(this, VaultPinActivity.class);
                    i.putExtra(VaultPinActivity.EXTRA_MODE_SET, true);
                    lockPinLauncher.launch(i);
                } else {
                    prefs.edit().putBoolean(KEY_LOCK, true).apply();
                }
            } else {
                prefs.edit().putBoolean(KEY_LOCK, false).apply();
            }
        });

        // ---------- Backup lokal ----------
        findViewById(R.id.btn_export_local).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "hozin_notes_backup.zip");
            exportLauncher.launch(i);
        });
        findViewById(R.id.btn_import_local).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            importLauncher.launch(i);
        });

        // ---------- Backup Drive ----------
        updateDriveStatus();
        findViewById(R.id.btn_drive_login).setOnClickListener(v -> driveLogin());
        findViewById(R.id.btn_drive_backups).setOnClickListener(v -> {
            if (!com.hozinking.notes.backup.DriveBackupManager.isSignedIn(this)) {
                Toast.makeText(this, getString(R.string.drive_login_first),
                        Toast.LENGTH_LONG).show();
                return;
            }
            startActivity(new Intent(this, DriveBackupListActivity.class));
        });
        findViewById(R.id.btn_backup_now).setOnClickListener(v -> backupNowWithProgress());
        TextView btnSchedule = findViewById(R.id.btn_backup_schedule);
        btnSchedule.setText(scheduleLabel());
        btnSchedule.setOnClickListener(v -> new IosActionSheet(this)
                .setTitle(getString(R.string.backup_schedule))
                .addItem(getString(R.string.schedule_off), () -> {
                    BackupScheduler.cancel(this);
                    prefs.edit().putInt(KEY_BACKUP_DAYS, 0).apply();
                    btnSchedule.setText(scheduleLabel());
                })
                .addItem(getString(R.string.schedule_daily), () -> setSchedule(1, btnSchedule))
                .addItem(getString(R.string.schedule_weekly), () -> setSchedule(7, btnSchedule))
                .addItem(getString(R.string.schedule_custom), () ->
                        new IosDialog(this)
                                .setTitle(getString(R.string.schedule_custom))
                                .setInput(getString(R.string.schedule_days_hint), text -> {
                                    try {
                                        int days = Integer.parseInt(text);
                                        if (days > 0) {
                                            setSchedule(days, btnSchedule);
                                        }
                                    } catch (NumberFormatException ignored) {
                                    }
                                })
                                .setPositive(getString(R.string.save), null)
                                .setNegative(getString(R.string.cancel), null)
                                .show())
                .show());
        CheckBox cbWifi = findViewById(R.id.cb_wifi_only);
        cbWifi.setChecked(prefs.getBoolean(KEY_WIFI_ONLY, true));
        cbWifi.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, checked).apply();
            rescheduleIfNeeded();
        });

        // ---------- Chat: URL Worker ----------
        android.widget.EditText etWorkerUrl = findViewById(R.id.chat_worker_url);
        String activeUrl = com.hozinking.notes.chat.ChatConfig.baseUrl(this);
        String customUrl = getSharedPreferences("chat", MODE_PRIVATE)
                .getString("chat_worker_url", null);
        if (customUrl != null && !customUrl.trim().isEmpty()) {
            etWorkerUrl.setText(customUrl.trim());
        } else {
            etWorkerUrl.setHint(activeUrl);
        }
        findViewById(R.id.chat_worker_save).setOnClickListener(v -> {
            String val = etWorkerUrl.getText().toString().trim().replaceAll("/+$", "");
            android.content.SharedPreferences chatPrefs =
                    getSharedPreferences("chat", MODE_PRIVATE);
            if (val.isEmpty()) {
                chatPrefs.edit().remove("chat_worker_url").apply();
                etWorkerUrl.setHint(com.hozinking.notes.chat.ChatConfig.DEFAULT_BASE_URL);
                android.widget.Toast.makeText(this,
                        R.string.chat_worker_url_cleared, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            if (!val.startsWith("http://") && !val.startsWith("https://")) {
                android.widget.Toast.makeText(this,
                        R.string.chat_worker_url_invalid, android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            chatPrefs.edit().putString("chat_worker_url", val).apply();
            android.widget.Toast.makeText(this,
                    R.string.chat_worker_url_saved, android.widget.Toast.LENGTH_SHORT).show();
        });

        // ---------- Tentang ----------
        TextView about = findViewById(R.id.about_version);
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            about.setText(getString(R.string.about_version, version));
        } catch (Exception e) {
            about.setText(getString(R.string.about_version, "1.0"));
        }
        TextView btnUpdate = findViewById(R.id.btn_check_update);
        if (btnUpdate != null) {
            btnUpdate.setOnClickListener(v ->
                    com.hozinking.notes.update.UpdateChecker.checkManual(this));
        }

        // ---------- Foto Live: hitungan global ----------
        TextView liveCounter = findViewById(R.id.live_counter);
        if (liveCounter != null) {
            com.hozinking.notes.data.repo.NoteRepository.get(this)
                    .countLivePhotos(n -> mainH.post(() ->
                            liveCounter.setText(
                                    getString(R.string.live_count_global, n))));
        }
    }

    // ---------- Tema ----------

    private String themeLabel(String mode) {
        switch (mode) {
            case "light":
                return getString(R.string.theme_light);
            case "dark":
                return getString(R.string.theme_dark);
            default:
                return getString(R.string.theme_system);
        }
    }

    private void applyTheme(String mode, TextView btnTheme) {
        prefs.edit().putString(KEY_THEME, mode).apply();
        int nightMode;
        switch (mode) {
            case "light":
                nightMode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case "dark":
                nightMode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(nightMode);
        btnTheme.setText(themeLabel(mode));
    }

    /** Dipanggil dari Application.onCreate agar tema tersimpan diterapkan saat start. */
    public static void applySavedTheme(Context ctx) {
        String mode = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, "system");
        switch (mode) {
            case "light":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "dark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    public static boolean isAppLockEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCK, false);
    }

    // ---------- Backup lokal ----------

    private void doExport(Uri uri) {
        showProgress(getString(R.string.backup_exporting));
        try {
            LocalBackupManager.exportZip(this, uri,
                    (done, total) -> onMain(() -> updateProgress(done, total)),
                    ok -> onMain(() -> {
                        hideProgress();
                        Toast.makeText(this,
                                ok ? getString(R.string.backup_export_ok)
                                        : getString(R.string.backup_export_fail),
                                Toast.LENGTH_LONG).show();
                    }));
        } catch (Exception e) {
            hideProgress();
            Toast.makeText(this, getString(R.string.backup_export_fail), Toast.LENGTH_LONG).show();
        }
    }

    private void askImportMode(Uri uri) {
        // Peringatkan dulu: ruang privat selalu di-REPLACE, bukan merge.
        new IosDialog(this)
                .setTitle(getString(R.string.backup_import))
                .setMessage(getString(R.string.backup_vault_replace_warn))
                .setDestructive(true)
                .setPositive(getString(R.string.yes), () -> showImportModeSheet(uri))
                .setNegative(getString(R.string.no), null)
                .show();
    }

    private void showImportModeSheet(Uri uri) {
        new IosActionSheet(this)
                .setTitle(getString(R.string.backup_import_mode))
                .addItem(getString(R.string.backup_merge), () -> doImport(uri, false))
                .addDestructive(getString(R.string.backup_overwrite), () ->
                        new IosDialog(this)
                                .setTitle(getString(R.string.backup_overwrite))
                                .setMessage(getString(R.string.backup_overwrite_confirm))
                                .setDestructive(true)
                                .setPositive(getString(R.string.yes),
                                        () -> doImport(uri, true))
                                .setNegative(getString(R.string.no), null)
                                .show())
                .show();
    }

    private void doImport(Uri uri, boolean overwrite) {
        showProgress(getString(R.string.backup_restoring));
        try {
            LocalBackupManager.importZip(this, uri, overwrite,
                    (done, total) -> onMain(() -> updateProgress(done, total)),
                    result -> onMain(() -> {
                        hideProgress();
                        if (result != null && result.ok) {
                            Toast.makeText(this,
                                    getString(R.string.backup_import_ok, result.notes, result.images),
                                    Toast.LENGTH_LONG).show();
                        } else {
                            String err = (result != null && result.error != null)
                                    ? result.error : getString(R.string.backup_import_fail);
                            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                        }
                    }));
        } catch (Exception e) {
            hideProgress();
            Toast.makeText(this, getString(R.string.backup_import_fail), Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Drive ----------

    private void updateDriveStatus() {
        TextView status = findViewById(R.id.drive_status);
        View loginBtn = findViewById(R.id.btn_drive_login);
        View profileBox = findViewById(R.id.drive_profile_box);
        try {
            com.google.android.gms.auth.api.signin.GoogleSignInAccount account =
                    com.google.android.gms.auth.api.signin.GoogleSignIn
                            .getLastSignedInAccount(this);
            if (account != null) {
                status.setText(getString(R.string.drive_logged_in, account.getEmail()));
                loginBtn.setVisibility(View.GONE);
                profileBox.setVisibility(View.VISIBLE);
                String name = account.getDisplayName();
                ((TextView) findViewById(R.id.drive_name))
                        .setText(name != null ? name : "-");
                ((TextView) findViewById(R.id.drive_email))
                        .setText(account.getEmail() != null ? account.getEmail() : "");
                loadDrivePhoto(account.getPhotoUrl());
                loadDriveQuota();
            } else {
                status.setText(getString(R.string.drive_logged_out));
                loginBtn.setVisibility(View.VISIBLE);
                profileBox.setVisibility(View.GONE);
                findViewById(R.id.drive_quota_text).setVisibility(View.GONE);
                findViewById(R.id.drive_quota_bar).setVisibility(View.GONE);
            }
        } catch (Exception e) {
            status.setText(getString(R.string.drive_unavailable));
            loginBtn.setVisibility(View.VISIBLE);
            profileBox.setVisibility(View.GONE);
        }
    }

    /** Foto profil Google (background thread, crop lingkaran). */
    private void loadDrivePhoto(final android.net.Uri photoUrl) {
        final android.widget.ImageView iv = findViewById(R.id.drive_photo);
        iv.setImageDrawable(null);
        if (photoUrl == null) return;
        bgDrive.execute(() -> {
            try {
                java.net.URL url = new java.net.URL(photoUrl.toString());
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeStream(c.getInputStream());
                c.disconnect();
                if (bmp == null) return;
                int s = Math.min(bmp.getWidth(), bmp.getHeight());
                android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(bmp,
                        (bmp.getWidth() - s) / 2, (bmp.getHeight() - s) / 2, s, s);
                android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                        s, s, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas cv = new android.graphics.Canvas(out);
                android.graphics.Paint p = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                cv.drawCircle(s / 2f, s / 2f, s / 2f, p);
                p.setXfermode(new android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.SRC_IN));
                cv.drawBitmap(sq, 0, 0, p);
                mainH.post(() -> iv.setImageBitmap(out));
            } catch (Exception ignored) {
            }
        });
    }

    /** Sisa storage Drive: "terpakai / total" + progress bar. */
    private void loadDriveQuota() {
        final TextView tv = findViewById(R.id.drive_quota_text);
        final ProgressBar bar = findViewById(R.id.drive_quota_bar);
        bgDrive.execute(() -> {
            try {
                com.hozinking.notes.backup.DriveBackupManager.Quota q =
                        new com.hozinking.notes.backup.DriveBackupManager()
                                .getQuotaBlocking(this);
                mainH.post(() -> {
                    tv.setVisibility(View.VISIBLE);
                    if (q.limit > 0) {
                        tv.setText(getString(R.string.drive_quota,
                                humanSize(q.used), humanSize(q.limit)));
                        bar.setVisibility(View.VISIBLE);
                        bar.setProgress((int) Math.min(100, q.used * 100 / q.limit));
                    } else {
                        tv.setText(getString(R.string.drive_quota_used, humanSize(q.used)));
                        bar.setVisibility(View.GONE);
                    }
                });
            } catch (Exception ignored) {
                mainH.post(() -> {
                    tv.setVisibility(View.GONE);
                    bar.setVisibility(View.GONE);
                });
            }
        });
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** Backup manual dengan progress bar upload (ala WhatsApp). */
    private void backupNowWithProgress() {
        if (uploading) return;
        if (!com.hozinking.notes.backup.DriveBackupManager.isSignedIn(this)) {
            Toast.makeText(this, R.string.drive_login_first, Toast.LENGTH_LONG).show();
            return;
        }
        uploading = true;
        final TextView upText = findViewById(R.id.drive_upload_text);
        final ProgressBar upBar = findViewById(R.id.drive_upload_bar);
        upText.setVisibility(View.VISIBLE);
        upBar.setVisibility(View.VISIBLE);
        upBar.setProgress(0);
        bgDrive.execute(() -> {
            java.io.File zip = null;
            try {
                mainH.post(() -> upText.setText(getString(R.string.drive_preparing)));
                zip = LocalBackupManager.buildBackupZip(this);
                final java.io.File fzip = zip;
                mainH.post(() -> upText.setText(getString(R.string.drive_uploading,
                        humanSize(fzip.length()))));
                new com.hozinking.notes.backup.DriveBackupManager()
                        .uploadBlocking(this, zip, fraction -> mainH.post(() ->
                                upBar.setProgress((int) Math.round(fraction * 100))));
                new com.hozinking.notes.backup.DriveBackupManager()
                        .pruneBlocking(this, 3);
                mainH.post(() -> {
                    Toast.makeText(this, R.string.backup_ok, Toast.LENGTH_SHORT).show();
                    loadDriveQuota();
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage()
                        : getString(R.string.drive_unavailable);
                mainH.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            } finally {
                if (zip != null) //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                uploading = false;
                mainH.post(() -> {
                    upText.setVisibility(View.GONE);
                    upBar.setVisibility(View.GONE);
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDriveStatus();
    }

    private void driveLogin() {
        try {
            com.google.android.gms.auth.api.signin.GoogleSignInOptions gso =
                    new com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestEmail()
                            .requestScopes(new com.google.android.gms.common.api.Scope(
                                    com.google.api.services.drive.DriveScopes.DRIVE_APPDATA))
                            .build();
            com.google.android.gms.auth.api.signin.GoogleSignInClient client =
                    com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(this, gso);
            googleSignInLauncher.launch(client.getSignInIntent());
        } catch (Exception | NoClassDefFoundError e) {
            Toast.makeText(this, getString(R.string.drive_unavailable), Toast.LENGTH_SHORT).show();
        }
    }

    private String scheduleLabel() {
        int days = prefs.getInt(KEY_BACKUP_DAYS, 0);
        if (days <= 0) {
            return getString(R.string.schedule_off);
        }
        if (days == 1) {
            return getString(R.string.schedule_daily);
        }
        if (days == 7) {
            return getString(R.string.schedule_weekly);
        }
        return getString(R.string.schedule_every_days, days);
    }

    private void setSchedule(int days, TextView btnSchedule) {
        boolean wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, true);
        BackupScheduler.schedule(this, days, wifiOnly);
        prefs.edit().putInt(KEY_BACKUP_DAYS, days).apply();
        btnSchedule.setText(scheduleLabel());
    }

    private void rescheduleIfNeeded() {
        int days = prefs.getInt(KEY_BACKUP_DAYS, 0);
        if (days > 0) {
            try {
                BackupScheduler.schedule(this, days,
                        prefs.getBoolean(KEY_WIFI_ONLY, true));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- Progress ----------

    private void showProgress(String message) {
        hideProgress();
        progressDialog = new Dialog(this);
        progressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        progressDialog.setContentView(R.layout.dialog_progress);
        progressDialog.setCancelable(false);
        progressBar = progressDialog.findViewById(R.id.progress_bar);
        progressText = progressDialog.findViewById(R.id.progress_text);
        progressText.setText(message);
        progressBar.setProgress(0);
        progressDialog.show();
    }

    private void updateProgress(int done, int total) {
        if (progressBar != null) {
            progressBar.setMax(Math.max(total, 1));
            progressBar.setProgress(done);
        }
        if (progressText != null) {
            progressText.setText(done + " / " + total);
        }
    }

    private void hideProgress() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }

    private void onMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    @Override
    protected void onDestroy() {
        hideProgress();
        super.onDestroy();
    }
}
