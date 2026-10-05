package com.hozinking.notes.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Enkripsi AES-256-GCM dengan kunci di AndroidKeyStore.
 * Format output: IV(12 byte) || ciphertext+tag(16 byte).
 */
public final class CryptoManager {
    private static final String KEY_ALIAS = "hozin_notes_master";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final String VAULT_PREFS = "vault_prefs";
    private static final String VAULT_PASS_KEY = "vault_pass_b64";

    private static volatile CryptoManager instance;
    private final Context appCtx;

    private CryptoManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static CryptoManager get(Context ctx) {
        if (instance == null) {
            synchronized (CryptoManager.class) {
                if (instance == null) {
                    instance = new CryptoManager(ctx);
                }
            }
        }
        return instance;
    }

    private SecretKey getOrCreateKey() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            if (!ks.containsAlias(KEY_ALIAS)) {
                KeyGenerator kg = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build();
                kg.init(spec);
                kg.generateKey();
            }
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memuat kunci KeyStore", e);
        }
    }

    /** Enkripsi bytes -> IV(12) || ciphertext. */
    public byte[] encrypt(byte[] plain) {
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_LEN) {
                throw new GeneralSecurityException("IV GCM tidak valid");
            }
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Enkripsi gagal", e);
        }
    }

    /** Dekripsi bytes berformat IV(12) || ciphertext. */
    public byte[] decrypt(byte[] enc) {
        try {
            if (enc == null || enc.length < IV_LEN + 1) {
                throw new GeneralSecurityException("Data terenkripsi terlalu pendek");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(enc, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[enc.length - IV_LEN];
            System.arraycopy(enc, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ct);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Dekripsi gagal (data rusak / kunci berubah)", e);
        }
    }

    /** Baca seluruh src, enkripsi, tulis ke dst. */
    public void encryptFile(File src, File dst) {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] plain = readAll(in);
            out.write(encrypt(plain));
        } catch (Exception e) {
            throw new RuntimeException("encryptFile gagal: " + src, e);
        }
    }

    /** Baca seluruh src lalu dekripsi, kembalikan bytes plain. */
    public byte[] decryptFileToBytes(File src) {
        try (FileInputStream in = new FileInputStream(src)) {
            return decrypt(readAll(in));
        } catch (Exception e) {
            throw new RuntimeException("decryptFileToBytes gagal: " + src, e);
        }
    }

    private static byte[] readAll(FileInputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Passphrase SQLCipher untuk vault.db: 32 byte random yang disimpan terenkripsi
     * di EncryptedSharedPreferences. Dibuat sekali, dipakai selamanya.
     */
    public byte[] getVaultPassphrase() {
        try {
            MasterKey masterKey = new MasterKey.Builder(appCtx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            SharedPreferences prefs = EncryptedSharedPreferences.create(
                    appCtx,
                    VAULT_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
            String stored = prefs.getString(VAULT_PASS_KEY, null);
            if (stored != null) {
                return Base64.decode(stored, Base64.NO_WRAP);
            }
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            prefs.edit()
                    .putString(VAULT_PASS_KEY, Base64.encodeToString(fresh, Base64.NO_WRAP))
                    .apply();
            return fresh;
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new RuntimeException("Gagal membaca/membuat vault passphrase", e);
        }
    }
}
