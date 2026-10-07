package com.hozinking.notes.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.ImageRefEntity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dialog "Rincian" gambar: waktu ambil, info berkas (ukuran + dimensi),
 * jalur lokal. Layout ScrollView + baris vertikal — tidak ada teks
 * bertumpuk seperti dialog lama.
 */
public class ImageDetailDialog extends Dialog {

    private final ImageRefEntity ref;
    private final byte[] plainBytes; // boleh null (data lama)

    public ImageDetailDialog(@NonNull Context context,
                             @NonNull ImageRefEntity ref,
                             @Nullable byte[] plainBytes) {
        super(context);
        this.ref = ref;
        this.plainBytes = plainBytes;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_image_detail);

        findViewById(R.id.btn_close).setOnClickListener(v -> dismiss());

        // Waktu ambil.
        String taken;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat(
                    "d MMMM yyyy, HH:mm", new Locale("id", "ID"));
            taken = fmt.format(new Date(ref.createdAt));
        } catch (Exception e) {
            taken = "-";
        }
        ((TextView) findViewById(R.id.tv_taken)).setText(taken);

        // Info berkas: ukuran + dimensi.
        StringBuilder info = new StringBuilder();
        if (plainBytes != null) {
            info.append(formatSize(plainBytes.length));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try {
                BitmapFactory.decodeByteArray(
                        plainBytes, 0, plainBytes.length, o);
                if (o.outWidth > 0 && o.outHeight > 0) {
                    info.append("  •  ")
                            .append(o.outWidth).append("×")
                            .append(o.outHeight).append("px");
                }
            } catch (Exception ignored) {
            }
        } else {
            info.append("-");
        }
        ((TextView) findViewById(R.id.tv_file)).setText(info.toString());

        // Jalur lokal (nama file saja agar ringkas, path penuh di tooltip).
        String path = ref.encPath != null ? ref.encPath : "-";
        try {
            String name = new File(path).getName();
            if (!name.isEmpty()) path = name + "\n" + path;
        } catch (Exception ignored) {
        }
        ((TextView) findViewById(R.id.tv_path)).setText(path);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0fKB", kb);
        return String.format(Locale.US, "%.1fMB", kb / 1024.0);
    }
}
