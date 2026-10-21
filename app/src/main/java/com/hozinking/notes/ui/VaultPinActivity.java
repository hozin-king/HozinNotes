package com.hozinking.notes.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.hozinking.notes.R;
import com.hozinking.notes.security.HashUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Layar PIN 6 digit untuk Ruang Privat / kunci aplikasi.
 *
 * <p>Extra: "mode_set" (boolean) — true = buat PIN baru (2 langkah:
 * buat lalu konfirmasi), false = verifikasi PIN yang sudah ada.
 * Extra "mode_set_decoy" (boolean) — true = buat PIN KAMUFLASE (kedua)
 * untuk plausible deniability: bila PIN kamuflase dimasukkan saat verifikasi,
 * hasil RESULT_OK disertai extra "decoy_used"=true agar pemanggil membuka
 * vault yang tampak kosong.
 * PIN disimpan sebagai SHA-256(salt + pin) di EncryptedSharedPreferences.
 */
public class VaultPinActivity extends AppCompatActivity {

    public static final String EXTRA_MODE_SET = "mode_set";
    /** True = atur PIN kamuflase (bukan PIN utama). */
    public static final String EXTRA_MODE_SET_DECOY = "mode_set_decoy";
    /** True pada result intent bila yang dimasukkan adalah PIN kamuflase. */
    public static final String EXTRA_DECOY_USED = "decoy_used";
    public static final int RC_PIN = 101;

    private static final String PREFS = "vault_prefs";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_HASH = "pin_hash";
    private static final String KEY_DECOY_SALT = "pin_decoy_salt";
    private static final String KEY_DECOY_HASH = "pin_decoy_hash";

    private boolean modeSet;
    private boolean modeSetDecoy;
    private boolean confirmStep;
    private String firstPin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_vault_pin);

        modeSet = getIntent().getBooleanExtra(EXTRA_MODE_SET, false);
        modeSetDecoy = getIntent().getBooleanExtra(EXTRA_MODE_SET_DECOY, false);
        boolean setMode = modeSet || modeSetDecoy;

        TextView tvTitle = findViewById(R.id.pin_title);
        EditText etPin = findViewById(R.id.pin_input);
        Button btnOk = findViewById(R.id.pin_ok);
        Button btnBio = findViewById(R.id.pin_biometric);

        updateTitle(tvTitle);

        // Auto-submit saat 6 digit terisi.
        etPin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 6) {
                    onPinEntered(tvTitle, etPin);
                }
            }
        });
        btnOk.setOnClickListener(v -> onPinEntered(tvTitle, etPin));

        // Tombol sidik jari hanya bila perangkat mendukung & PIN sudah ada
        // (tidak untuk mode buat PIN / PIN kamuflase).
        if (!setMode && canUseBiometric(this)) {
            btnBio.setVisibility(View.VISIBLE);
            btnBio.setOnClickListener(v -> authenticateBiometric());
        } else {
            btnBio.setVisibility(View.GONE);
        }
    }

    private void updateTitle(TextView tvTitle) {
        if (modeSetDecoy) {
            tvTitle.setText(confirmStep
                    ? getString(R.string.vault_decoy_confirm_title)
                    : getString(R.string.vault_decoy_set_title));
        } else if (modeSet) {
            tvTitle.setText(confirmStep
                    ? getString(R.string.vault_confirm_pin)
                    : getString(R.string.vault_set_pin));
        } else {
            tvTitle.setText(getString(R.string.vault_locked));
        }
    }

    private void onPinEntered(TextView tvTitle, EditText etPin) {
        String pin = etPin.getText().toString().trim();
        if (pin.length() != 6) {
            Toast.makeText(this, getString(R.string.vault_pin_length), Toast.LENGTH_SHORT).show();
            return;
        }
        etPin.setText("");

        if (modeSet || modeSetDecoy) {
            if (!confirmStep) {
                firstPin = pin;
                confirmStep = true;
                updateTitle(tvTitle);
            } else {
                if (firstPin.equals(pin)) {
                    if (modeSetDecoy) {
                        // PIN kamuflase tidak boleh sama dengan PIN asli.
                        if (verifyPin(this, pin)) {
                            firstPin = null;
                            confirmStep = false;
                            updateTitle(tvTitle);
                            Toast.makeText(this, getString(R.string.vault_decoy_same_pin),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        savePin(this, pin, KEY_DECOY_SALT, KEY_DECOY_HASH);
                        Toast.makeText(this, getString(R.string.vault_decoy_saved),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        savePin(this, pin, KEY_SALT, KEY_HASH);
                    }
                    setResult(RESULT_OK);
                    finish();
                } else {
                    firstPin = null;
                    confirmStep = false;
                    updateTitle(tvTitle);
                    Toast.makeText(this, getString(R.string.vault_pin_mismatch), Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            if (verifyPin(this, pin)) {
                setResult(RESULT_OK);
                finish();
            } else if (isDecoyPinSet(this) && verifyDecoyPin(this, pin)) {
                // PIN kamuflase: buka vault yang tampak kosong (plausible deniability).
                Intent data = new Intent();
                data.putExtra(EXTRA_DECOY_USED, true);
                setResult(RESULT_OK, data);
                finish();
            } else {
                Toast.makeText(this, getString(R.string.vault_wrong_pin), Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------- Biometrik ----------

    /** v1.20: statis agar bisa dipakai NoteLockGate (biometrik catatan terkunci). */
    public static boolean canUseBiometric(android.content.Context ctx) {
        try {
            BiometricManager manager = BiometricManager.from(ctx);
            return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private void authenticateBiometric() {
        try {
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.vault))
                    .setSubtitle(getString(R.string.vault_locked))
                    .setNegativeButtonText(getString(R.string.cancel))
                    .build();
            BiometricPrompt prompt = new BiometricPrompt(this,
                    ContextCompat.getMainExecutor(this),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                @NonNull BiometricPrompt.AuthenticationResult result) {
                            setResult(RESULT_OK);
                            finish();
                        }

                        @Override
                        public void onAuthenticationError(int errorCode,
                                                         @NonNull CharSequence errString) {
                            // Biarkan user memakai PIN.
                        }
                    });
            prompt.authenticate(info);
        } catch (Exception e) {
            // Abaikan; user tetap bisa memakai PIN.
        }
    }

    // ---------- Penyimpanan PIN ----------

    private static android.content.SharedPreferences encryptedPrefs(Context ctx) {
        try {
            MasterKey masterKey = new MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    ctx, PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Fallback: prefs biasa (PIN tetap berupa hash SHA-256 bersalt).
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    public static boolean isPinSet(Context ctx) {
        try {
            return encryptedPrefs(ctx).contains(KEY_HASH);
        } catch (Exception e) {
            return false;
        }
    }

    public static void clearPin(Context ctx) {
        try {
            encryptedPrefs(ctx).edit()
                    .remove(KEY_HASH).remove(KEY_SALT)
                    .remove(KEY_DECOY_HASH).remove(KEY_DECOY_SALT)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    /** PIN kamuflase sudah diatur? */
    public static boolean isDecoyPinSet(Context ctx) {
        try {
            return encryptedPrefs(ctx).contains(KEY_DECOY_HASH);
        } catch (Exception e) {
            return false;
        }
    }

    /** Hapus PIN kamuflase saja (PIN utama tetap). */
    public static void clearDecoyPin(Context ctx) {
        try {
            encryptedPrefs(ctx).edit()
                    .remove(KEY_DECOY_HASH).remove(KEY_DECOY_SALT)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private static boolean verifyDecoyPin(Context ctx, String pin) {
        try {
            android.content.SharedPreferences prefs = encryptedPrefs(ctx);
            String saltHex = prefs.getString(KEY_DECOY_SALT, null);
            String stored = prefs.getString(KEY_DECOY_HASH, null);
            if (saltHex == null || stored == null) {
                return false;
            }
            String hash = HashUtils.sha256Hex((saltHex + pin).getBytes(StandardCharsets.UTF_8));
            return stored.equalsIgnoreCase(hash);
        } catch (Exception e) {
            return false;
        }
    }

    private static void savePin(Context ctx, String pin) {
        savePin(ctx, pin, KEY_SALT, KEY_HASH);
    }

    private static void savePin(Context ctx, String pin, String saltKey, String hashKey) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        StringBuilder sb = new StringBuilder(salt.length * 2);
        for (byte b : salt) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        String saltHex = sb.toString();
        String hash = HashUtils.sha256Hex((saltHex + pin).getBytes(StandardCharsets.UTF_8));
        encryptedPrefs(ctx).edit()
                .putString(saltKey, saltHex)
                .putString(hashKey, hash)
                .apply();
    }

    /** Verifikasi PIN untuk fitur lain (mis. kunci catatan). */
    public static boolean checkPin(Context ctx, String pin) {
        return verifyPin(ctx, pin);
    }

    private static boolean verifyPin(Context ctx, String pin) {
        try {
            android.content.SharedPreferences prefs = encryptedPrefs(ctx);
            String saltHex = prefs.getString(KEY_SALT, null);
            String stored = prefs.getString(KEY_HASH, null);
            if (saltHex == null || stored == null) {
                return false;
            }
            String hash = HashUtils.sha256Hex((saltHex + pin).getBytes(StandardCharsets.UTF_8));
            return stored.equalsIgnoreCase(hash);
        } catch (Exception e) {
            return false;
        }
    }

    /** Jalankan Runnable di main thread (callback repo jalan di background). */
    static void onMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
