package com.hozinking.notes.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.repo.NoteRepository;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Viewer Foto Live: menampilkan still + memutar klip video ~3 detik.
 * Video didekripsi ke file temp di cache, diputar via VideoView,
 * file temp dihapus saat dialog ditutup.
 */
public class LivePhotoViewerDialog {

    private final Context ctx;
    private final ImageRefEntity ref;
    private final byte[] stillBytes;

    private Dialog dialog;
    private VideoView videoView;
    private ImageButton btnPlay;
    private File tempVideo;

    public LivePhotoViewerDialog(@NonNull Context ctx, @NonNull ImageRefEntity ref,
                                 byte[] stillBytes) {
        this.ctx = ctx;
        this.ref = ref;
        this.stillBytes = stillBytes;
    }

    public void show() {
        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_live_photo);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new ColorDrawable(android.graphics.Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        videoView = dialog.findViewById(R.id.video);
        btnPlay = dialog.findViewById(R.id.btn_play);
        // Autoplay langsung — tombol play besar tidak dipakai lagi.
        btnPlay.setVisibility(android.view.View.GONE);
        TextView status = dialog.findViewById(R.id.live_status);

        dialog.findViewById(R.id.btn_close).setOnClickListener(v -> dismiss());

        // Tampilkan still sebagai poster awal (di bawah VideoView via Z-order aman:
        // set background VideoView dari bitmap).
        if (stillBytes != null) {
            Bitmap bmp = BitmapFactory.decodeByteArray(stillBytes, 0, stillBytes.length);
            if (bmp != null) {
                videoView.setBackground(
                        new android.graphics.drawable.BitmapDrawable(
                                ctx.getResources(), bmp));
            }
        }

        // Autoplay langsung saat video siap: loop + bisu, transisi mulus
        // dari still (background) ke frame pertama video.
        videoView.setOnPreparedListener(mp -> {
            try {
                mp.setLooping(true);
                mp.setVolume(0f, 0f);
            } catch (Exception ignored) {
            }
            videoView.setBackground(null);
            btnPlay.setVisibility(android.view.View.GONE);
            status.setText("");
            videoView.start();
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            status.setText(ctx.getString(R.string.live_error));
            return true;
        });
        videoView.setOnClickListener(v -> {
            if (videoView.isPlaying()) videoView.pause();
            else videoView.start();
        });

        dialog.setOnDismissListener(d -> cleanup());
        dialog.show();

        // Dekripsi video di background, lalu siapkan playback.
        status.setText(ctx.getString(R.string.live_loading));
        NoteRepository.get(ctx).loadLiveVideoPlain(ref, bytes -> {
            if (!(ctx instanceof android.app.Activity)) return;
            ((android.app.Activity) ctx).runOnUiThread(() -> {
                if (bytes == null) {
                    status.setText(ctx.getString(R.string.live_error));
                    return;
                }
                try {
                    File dir = new File(ctx.getCacheDir(), "liveplay");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    tempVideo = new File(dir, "play_" + ref.id + ".mp4");
                    FileOutputStream out = new FileOutputStream(tempVideo);
                    out.write(bytes);
                    out.close();
                    videoView.setVideoPath(tempVideo.getAbsolutePath());
                    // Playback dimulai otomatis di onPrepared (loop + bisu).
                } catch (Exception e) {
                    status.setText(ctx.getString(R.string.live_error));
                }
            });
        });
    }

    private void dismiss() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void cleanup() {
        try {
            if (videoView != null) videoView.stopPlayback();
        } catch (Exception ignored) {
        }
        if (tempVideo != null) {
            //noinspection ResultOfMethodCallIgnored
            tempVideo.delete();
            tempVideo = null;
        }
    }

}
