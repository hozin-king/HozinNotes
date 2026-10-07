package com.hozinking.notes.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.hozinking.notes.data.db.ImageRefEntity;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * Utilitas Foto Live (ala iOS Live Photos: 1 foto still + klip video ~3 detik).
 *
 * <p>Aturan penamaan file: video live selalu bersuffix {@code _live}
 * (misal {@code img_12_1699999999_live.bin}) sehingga mudah dibedakan
 * dari foto biasa di file system.
 */
public final class LivePhotoUtils {

    /** Suffix pembeda file video live. */
    public static final String LIVE_SUFFIX = "_live";

    private LivePhotoUtils() {
    }

    /**
     * True bila attachment ini adalah foto live: path video terisi
     * DAN file-nya benar-benar ada di disk.
     */
    public static boolean isLivePhoto(ImageRefEntity ref) {
        if (ref == null || ref.liveVideoPath == null || ref.liveVideoPath.isEmpty()) {
            return false;
        }
        return new File(ref.liveVideoPath).exists();
    }

    /**
     * Ambil 1 frame tengah video sebagai JPEG bytes (untuk still foto live).
     * Kembalikan null bila gagal.
     */
    public static byte[] extractStillFrame(Context ctx, Uri videoUri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, videoUri);
            String durStr = r.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = 1500;
            try {
                if (durStr != null) durMs = Long.parseLong(durStr) / 2;
            } catch (NumberFormatException ignored) {
            }
            Bitmap frame = r.getFrameAtTime(durMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = r.getFrameAtTime(0);
            }
            if (frame == null) return null;
            try {
                // Batasi sisi terpanjang 1600px agar hemat memori.
                int w = frame.getWidth();
                int h = frame.getHeight();
                int maxSide = 1600;
                if (Math.max(w, h) > maxSide) {
                    float s = (float) maxSide / Math.max(w, h);
                    Bitmap scaled = Bitmap.createScaledBitmap(frame,
                            Math.round(w * s), Math.round(h * s), true);
                    if (scaled != frame) frame.recycle();
                    frame = scaled;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                return bos.toByteArray();
            } finally {
                frame.recycle();
            }
        } catch (Exception e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }
}
