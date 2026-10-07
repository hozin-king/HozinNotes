package com.hozinking.notes.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import com.hozinking.notes.data.db.ImageRefEntity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;

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
                // Batasi sisi terpanjang 2048px agar hemat memori,
                // kualitas JPEG 92 supaya still tetap tajam.
                int w = frame.getWidth();
                int h = frame.getHeight();
                int maxSide = 2048;
                if (Math.max(w, h) > maxSide) {
                    float s = (float) maxSide / Math.max(w, h);
                    Bitmap scaled = Bitmap.createScaledBitmap(frame,
                            Math.round(w * s), Math.round(h * s), true);
                    if (scaled != frame) frame.recycle();
                    frame = scaled;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.JPEG, 92, bos);
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

    /**
     * Durasi video dalam milidetik, atau 0 bila tidak bisa dibaca.
     */
    public static long getVideoDurationMs(Context ctx, Uri videoUri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, videoUri);
            String d = r.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Potong klip video TANPA re-encode: salin sampel track video saja
     * dalam rentang [startMs, endMs] memakai MediaExtractor + MediaMuxer.
     *
     * @return bytes MP4 hasil potongan, atau null bila gagal
     *         (pemanggil sebaiknya fallback ke file video asli).
     */
    public static byte[] trimVideoClip(Context ctx, Uri videoUri,
                                       long startMs, long endMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        File tmp = null;
        try {
            extractor.setDataSource(ctx, videoUri, null);
            int videoTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    format = f;
                    break;
                }
            }
            if (videoTrack < 0 || format == null) return null;

            tmp = File.createTempFile("livetrim", ".mp4", ctx.getCacheDir());
            muxer = new MediaMuxer(tmp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = muxer.addTrack(format);
            muxer.start();

            extractor.selectTrack(videoTrack);
            extractor.seekTo(startMs * 1000L,
                    MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long endUs = endMs * 1000L;
            for (;;) {
                int n = extractor.readSampleData(buf, 0);
                if (n < 0) break;
                long pts = extractor.getSampleTime();
                if (pts > endUs) break;
                info.offset = 0;
                info.size = n;
                info.presentationTimeUs = pts;
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(outTrack, buf, info);
                if (!extractor.advance()) break;
            }
            muxer.stop();

            FileInputStream fis = new FileInputStream(tmp);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int r;
                while ((r = fis.read(chunk)) > 0) bos.write(chunk, 0, r);
                byte[] out = bos.toByteArray();
                return out.length > 0 ? out : null;
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }
}
