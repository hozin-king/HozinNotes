package com.hozinking.notes.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.repo.NoteRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Adapter paging untuk daftar catatan. Thumbnail dimuat async dari repo
 * (getImages -> loadImagePlain) dengan proteksi recycling ViewHolder.
 */
public class NoteAdapter extends PagingDataAdapter<NoteEntity, NoteAdapter.VH> {

    public interface Listener {
        void onClick(NoteEntity note);
        void onLongClick(NoteEntity note, View anchor);
    }

    private static final DiffUtil.ItemCallback<NoteEntity> DIFF =
            new DiffUtil.ItemCallback<NoteEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull NoteEntity a, @NonNull NoteEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull NoteEntity a, @NonNull NoteEntity b) {
                    return a.updatedAt == b.updatedAt
                            && strEq(a.title, b.title)
                            && strEq(a.content, b.content)
                            && a.pinned == b.pinned
                            && a.color == b.color
                            && a.imageCount == b.imageCount
                            && a.reminderTime == b.reminderTime;
                }

                private boolean strEq(String x, String y) {
                    return x == null ? y == null : x.equals(y);
                }
            };

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));

    public NoteAdapter(Context ctx, Listener listener) {
        super(DIFF);
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_note, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        holder.bind(getItem(position));
    }

    class VH extends RecyclerView.ViewHolder {
        private final com.google.android.material.card.MaterialCardView card;
        private final TextView title;
        private final TextView preview;
        private final TextView date;
        private final ImageView thumb;
        private final ImageView pin;
        private final View colorStrip;
        private long boundId = -1;
        private final int glassCardBg;
        private final int defaultTitleColor;
        private final int defaultSubColor;

        VH(@NonNull View v) {
            super(v);
            card = v.findViewById(R.id.card);
            title = v.findViewById(R.id.title);
            preview = v.findViewById(R.id.preview);
            date = v.findViewById(R.id.date);
            thumb = v.findViewById(R.id.thumb);
            pin = v.findViewById(R.id.pin_badge);
            colorStrip = v.findViewById(R.id.color_strip);
            glassCardBg = androidx.core.content.ContextCompat.getColor(
                    v.getContext(), R.color.glass_fill);
            android.content.res.TypedArray ta = v.getContext().obtainStyledAttributes(
                    new int[]{android.R.attr.textColorPrimary,
                            android.R.attr.textColorSecondary});
            defaultTitleColor = ta.getColor(0, 0xFFFFFFFF);
            defaultSubColor = ta.getColor(1, 0xFFB0B0B0);
            ta.recycle();
            v.setOnClickListener(x -> {
                NoteEntity n = getItem(getBindingAdapterPosition());
                if (n != null) listener.onClick(n);
            });
            v.setOnLongClickListener(x -> {
                NoteEntity n = getItem(getBindingAdapterPosition());
                if (n != null) {
                    listener.onLongClick(n, x);
                    return true;
                }
                return false;
            });
        }

        void bind(NoteEntity n) {
            if (n == null) return;
            boundId = n.id;

            String t = n.title == null ? "" : n.title.trim();
            if (t.isEmpty()) {
                t = firstLine(n.content, n.contentHtml);
                if (t.isEmpty()) t = appCtx.getString(R.string.notes_no_title);
            }
            if (n.locked) t = "🔒 " + t;
            title.setText(t);

            String c = n.content == null ? "" : n.content;
            if (n.locked) {
                // Privasi: isi catatan terkunci jangan tampil di daftar.
                preview.setVisibility(View.GONE);
            } else {
                if (n.contentHtml) c = stripHtml(c);
                preview.setText(c);
                preview.setVisibility(c.trim().isEmpty() ? View.GONE : View.VISIBLE);
            }

            date.setText(dateFmt.format(new Date(n.updatedAt)));
            if (n.reminderTime > System.currentTimeMillis()) {
                java.text.SimpleDateFormat rf = new java.text.SimpleDateFormat(
                        "dd MMM, HH:mm", new Locale("id", "ID"));
                date.setText("\u23F0 " + rf.format(new Date(n.reminderTime)));
            }
            pin.setVisibility(n.pinned ? View.VISIBLE : View.GONE);

            // Warna note = strip aksen tipis di tepi kiri kartu; body kartu
            // SELALU frosted glass agar teks tetap kontras terbaca.
            if (n.color != 0) {
                colorStrip.setVisibility(View.VISIBLE);
                colorStrip.setBackgroundTintList(ColorStateList.valueOf(n.color));
            } else {
                colorStrip.setVisibility(View.GONE);
            }
            card.setCardBackgroundColor(glassCardBg);
            title.setTextColor(defaultTitleColor);
            preview.setTextColor(defaultSubColor);
            date.setTextColor(defaultSubColor);
            pin.setImageTintList(null);

            thumb.setImageDrawable(null);
            if (n.imageCount > 0) {
                thumb.setVisibility(View.VISIBLE);
                long id = n.id;
                NoteRepository.get(appCtx).getImages(id, list -> {
                    if (list == null || list.isEmpty()) {
                        main.post(() -> {
                            if (boundId == id) thumb.setVisibility(View.GONE);
                        });
                        return;
                    }
                    ImageRefEntity first = list.get(0);
                    NoteRepository.get(appCtx).loadImagePlain(first, bytes -> {
                        Bitmap bmp = null;
                        try {
                            if (bytes != null) {
                                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                            }
                        } catch (Exception ignored) {
                        }
                        Bitmap f = bmp;
                        main.post(() -> {
                            if (boundId != id) return;
                            if (f != null) thumb.setImageBitmap(f);
                            else thumb.setVisibility(View.GONE);
                        });
                    });
                });
            } else {
                thumb.setVisibility(View.GONE);
            }
        }

        private String firstLine(String s, boolean isHtml) {
            if (s == null) return "";
            String t = isHtml ? stripHtml(s) : s;
            t = t.trim();
            if (t.isEmpty()) return "";
            int nl = t.indexOf('\n');
            String line = (nl < 0 ? t : t.substring(0, nl)).trim();
            return line.length() > 60 ? line.substring(0, 60) + "…" : line;
        }

        private String stripHtml(String html) {
            if (html == null) return "";
            // Tabel -> label singkat; tag lain dibuang; entity di-decode.
            String t = html.replaceAll("(?s)<table.*?</table>", " [Tabel] ");
            t = t.replaceAll("<[^>]*>", " ");
            t = t.replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&amp;", "&").replace("&nbsp;", " ");
            return t.replaceAll("\\s+", " ");
        }
    }
}
