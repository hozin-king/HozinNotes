package com.hozinking.notes.security;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Utilitas SHA-256 untuk verifikasi integritas file/bytes. */
public final class HashUtils {
    private HashUtils() {
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 tidak tersedia", e);
        }
    }

    public static String sha256Hex(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("sha256Hex(File) gagal: " + f, e);
        }
    }

    /** true bila hash SHA-256 file sama dengan hex (perbandingan case-insensitive). */
    public static boolean verify(File f, String hex) {
        if (f == null || hex == null || !f.exists()) {
            return false;
        }
        try {
            return sha256Hex(f).equalsIgnoreCase(hex);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
