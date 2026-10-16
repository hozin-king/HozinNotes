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
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.ImageRefEntity;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.repo.NoteRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Adapter paging untuk daftar catatan. Mendukung dua jenis baris:
 * kartu catatan biasa dan header seksi waktu ("Hari ini", "Kemarin",
 * ...). Di mode grid, header otomatis full-span satu baris.
 * Thumbnail tajam 512px dimuat async dari repo (getImages ->
 * loadThumbPlain, fallback loadImagePlain) dengan proteksi recycling
 * ViewHolder.
 */
public class NoteAdapter extends PagingDataAdapter<NoteListItem, RecyclerView.ViewHolder> {

    public interface Listener {
        void onClick(NoteEntity note);
        void onLongClick(NoteEntity note, View anchor);
    }

    private static final int TYPE_NOTE = 0;
    private static final int TYPE_HEADER = 1;

    private static final DiffUtil.ItemCallback<NoteListItem> DIFF =
            new DiffUtil.ItemCallback<NoteListItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull NoteListItem a,
                                              @NonNull NoteListItem b) {
                    if (a.isHeader() != b.isHeader()) return false;
                    if (a.isHeader()) return a.header.equals(b.header);
                    if (a.note == null || b.note == null) return a == b;
                    return a.note.id == b.note.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull NoteListItem a,
                                                  @NonNull NoteListItem b) {
                    if (a.isHeader() != b.isHeader()) return false;
                    if (a.isHeader()) return a.header.equals(b.header);
                    if (a.note == null || b.note == null) return a.note == b.note;
                    NoteEntity x = a.note, y = b.note;
                    return x.updatedAt == y.updatedAt
                            && strEq(x.title, y.title)
                            && strEq(x.content, y.content)
                            && x.pinned == y.pinned
                            && x.color == y.color
                            && x.imageCount == y.imageCount
                            && x.reminderTime == y.reminderTime;
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

    @Override
    public int getItemViewType(int position) {
        NoteListItem it = getItem(position);
        return (it != null && it.isHeader()) ? TYPE_HEADER : TYPE_NOTE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_note_header, parent, false);
            return new HeaderVH(v);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_note, parent, false);
        return new NoteVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        NoteListItem it = getItem(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).bind(it == null ? null : it.header);
        } else if (holder instanceof NoteVH) {
            ((NoteVH) holder).bind(it == null ? null : it.note);
        }
    }

    @Override
    public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        // Header seksi selalu selebar layar walau daftar sedang mode grid.
        if (holder instanceof HeaderVH) {
            ViewGroup.LayoutParams lp = holder.itemView.getLayoutParams();
            if (lp instanceof StaggeredGridLayoutManager.LayoutParams) {
                ((StaggeredGridLayoutManager.LayoutParams) lp).setFullSpan(true);
            }
        }
    }

    /** Header seksi waktu: label kecil di atas kelompok catatan. */
    class HeaderVH extends RecyclerView.ViewHolder {
        private final TextView label;

        HeaderVH(@NonNull View v) {
            super(v);
            label = v.findViewById(R.id.header_label);
        }

        void bind(String text) {
            label.setText(text == null ? "" : text);
        }
    }

    class NoteVH extends RecyclerView.ViewHolder {
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

        NoteVH(@NonNull View v) {
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
                NoteListItem it = getItem(getBindingAdapterPosition());
                if (it != null && it.note != null) listener.onClick(it.note);
            });
            v.setOnLongClickListener(x -> {
                NoteListItem it = getItem(getBindingAdapterPosition());
                if (it != null && it.note != null) {
                    listener.onLongClick(it.note, x);
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
                    // Thumbnail tajam 512px dulu; fallback ke gambar penuh
                    // untuk data lama yang belum punya thumbnail.
                    NoteRepository.get(appCtx).loadThumbPlain(first, tbytes -> {
                        Bitmap tb = null;
                        try {
                            if (tbytes != null) {
                                tb = BitmapFactory.decodeByteArray(
                                        tbytes, 0, tbytes.length);
                            }
                        } catch (Exception ignored) {
                        }
                        if (tb != null) {
                            Bitmap f = tb;
                            main.post(() -> {
                                if (boundId != id) return;
                                thumb.setImageBitmap(f);
                            });
                            return;
                        }
                        NoteRepository.get(appCtx).loadImagePlain(first, bytes -> {
                            Bitmap bmp = null;
                            try {
                                if (bytes != null) {
                                    bmp = BitmapFactory.decodeByteArray(
                                            bytes, 0, bytes.length);
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
