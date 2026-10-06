package com.hozinking.notes.security;

import android.content.Context;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Kriptografi level-field untuk brankas kata sandi.
 *
 * <p>Setiap secret (password / API key / catatan rahasia) dienkripsi dengan
 * AES-256-GCM memakai kunci AndroidKeyStore (via {@link CryptoManager}),
 * di atas database yang sendiri sudah terenkripsi SQLCipher.
 * Jadi ada dua lapis enkripsi independen sebelum data menyentuh disk.
 *
 * <p>Aturan memori: secret dipegang sebagai {@code char[]} dan di-wipe
 * ({@link #wipe(char[])}) segera setelah dipakai. Jangan pernah menyimpan
 * secret di String field / log.
 */
public final class PasswordCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] LOWER = "abcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final char[] UPPER = "ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?".toCharArray();

    private PasswordCrypto() {}

    /** Enkripsi secret -> blob IV(12) || ciphertext+tag. Input di-wipe setelah dipakai. */
    public static byte[] encryptSecret(Context ctx, char[] secret) {
        if (secret == null || secret.length == 0) {
            return null;
        }
        byte[] plain = charsToBytes(secret);
        try {
            return CryptoManager.get(ctx).encrypt(plain);
        } finally {
            wipe(plain);
        }
    }

    /** Dekripsi blob -> char[]. Penelepon WAJIB memanggil {@link #wipe(char[])} setelah selesai. */
    public static char[] decryptSecret(Context ctx, byte[] enc) {
        if (enc == null || enc.length == 0) {
            return new char[0];
        }
        byte[] plain = CryptoManager.get(ctx).decrypt(enc);
        try {
            CharBuffer cb = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(plain));
            char[] out = new char[cb.remaining()];
            cb.get(out);
            return out;
        } finally {
            wipe(plain);
        }
    }

    /** Generator kata sandi kuat memakai SecureRandom. */
    public static char[] generatePassword(int length, boolean withSymbols) {
        int len = Math.max(8, Math.min(64, length));
        StringBuilder pool = new StringBuilder();
        pool.append(LOWER).append(UPPER).append(DIGITS);
        if (withSymbols) {
            pool.append(SYMBOLS);
        }
        char[] poolArr = pool.toString().toCharArray();
        char[] out = new char[len];
        // Pastikan minimal satu dari tiap kelas terwakili.
        out[0] = LOWER[RANDOM.nextInt(LOWER.length)];
        out[1] = UPPER[RANDOM.nextInt(UPPER.length)];
        out[2] = DIGITS[RANDOM.nextInt(DIGITS.length)];
        int start = 3;
        if (withSymbols) {
            out[3] = SYMBOLS[RANDOM.nextInt(SYMBOLS.length)];
            start = 4;
        }
        for (int i = start; i < len; i++) {
            out[i] = poolArr[RANDOM.nextInt(poolArr.length)];
        }
        // Acak posisi agar pola kelas tidak bisa ditebak.
        for (int i = len - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        wipe(poolArr);
        return out;
    }

    /** Estimasi kekuatan: "Lemah" / "Sedang" / "Kuat" berdasarkan panjang & variasi. */
    public static String strengthLabel(char[] secret) {
        if (secret == null || secret.length < 8) {
            return "Lemah";
        }
        boolean lower = false, upper = false, digit = false, sym = false;
        for (char c : secret) {
            if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= '0' && c <= '9') digit = true;
            else sym = true;
        }
        int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (sym ? 1 : 0);
        if (secret.length >= 16 && classes >= 3) {
            return "Kuat";
        }
        if (secret.length >= 10 && classes >= 2) {
            return "Sedang";
        }
        return "Lemah";
    }

    public static void wipe(char[] a) {
        if (a != null) {
            Arrays.fill(a, '\0');
        }
    }

    public static void wipe(byte[] a) {
        if (a != null) {
            Arrays.fill(a, (byte) 0);
        }
    }

    private static byte[] charsToBytes(char[] chars) {
        CharBuffer cb = CharBuffer.wrap(chars);
        java.nio.ByteBuffer bb = StandardCharsets.UTF_8.encode(cb);
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        return out;
    }
}
