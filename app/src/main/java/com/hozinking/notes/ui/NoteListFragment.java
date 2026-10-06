package com.hozinking.notes.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.paging.LoadState;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ImageButton;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.hozinking.notes.R;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosActionSheet;
import com.hozinking.notes.ui.dialog.IosDialog;

import kotlin.Unit;

/** Tab "Catatan": daftar paging + search/vault di toolbar + FAB + swipe-to-trash. */
public class NoteListFragment extends Fragment {

    private NoteAdapter adapter;
    private TextView emptyView;
    private TextView tagFilterChip;
    private RecyclerView recycler;
    private ImageButton viewToggle;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Filter tag aktif (null = semua). */
    private Long filterTagId = null;
    private String filterTagName = null;

    // Preferensi tampilan ala Keep.
    private static final String PREFS = "notes_view_prefs";
    private static final String KEY_VIEW_MODE = "view_mode"; // grid | list
    private static final String KEY_SORT = "sort"; // newest | oldest | title | color

    /** PIN vault -> bila OK, buka Ruang Privat. Bila PIN belum ada, arahkan ke mode buat PIN. */
    private final ActivityResultLauncher<Intent> vaultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            startActivity(new Intent(requireContext(), VaultActivity.class));
                        }
                    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_note_list, container, false);
        recycler = v.findViewById(R.id.recycler);
        emptyView = v.findViewById(R.id.empty_view);
        applyLayoutManager();

        adapter = new NoteAdapter(requireContext(), new NoteAdapter.Listener() {
            @Override
            public void onClick(NoteEntity note) {
                NoteLockGate.openMaybeLocked(requireContext(), note,
                        id -> openEditor(id));
            }

            @Override
            public void onLongClick(NoteEntity note, View anchor) {
                showActions(note);
            }
        });
        recycler.setAdapter(adapter);
        adapter.addLoadStateListener(states -> {
            boolean empty = adapter.getItemCount() == 0
                    && states.getRefresh() instanceof LoadState.NotLoading;
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            return Unit.INSTANCE;
        });

        tagFilterChip = v.findViewById(R.id.tag_filter_chip);
        tagFilterChip.setOnClickListener(x -> clearTagFilter());
        refreshList();

        v.findViewById(R.id.search_bar).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), SearchActivity.class)));
        viewToggle = v.findViewById(R.id.btn_view_toggle);
        viewToggle.setOnClickListener(x -> toggleViewMode());
        updateViewToggleIcon();
        v.findViewById(R.id.btn_vault).setOnClickListener(x -> {
            Intent i = new Intent(requireContext(), VaultPinActivity.class);
            if (!VaultPinActivity.isPinSet(requireContext())) {
                // Belum ada PIN: langsung ke mode buat PIN, bukan verifikasi.
                i.putExtra(VaultPinActivity.EXTRA_MODE_SET, true);
            }
            vaultLauncher.launch(i);
        });
        v.findViewById(R.id.btn_passwords).setOnClickListener(x ->
                startActivity(new Intent(requireContext(), PasswordVaultActivity.class)));
        v.findViewById(R.id.btn_more).setOnClickListener(x ->
                new IosActionSheet(requireContext())
                        .addItem(getString(R.string.archive_title), () ->
                                startActivity(new Intent(requireContext(), ArchiveActivity.class)))
                        .addItem(getString(R.string.trash_title), () ->
                                startActivity(new Intent(requireContext(), TrashActivity.class)))
                        .addItem(getString(R.string.tag_filter), this::showTagFilterSheet)
                        .addItem(getString(R.string.notes_sort_title), this::showSortSheet)
                        .show());
        FloatingActionButton fab = v.findViewById(R.id.fab);
        fab.setOnClickListener(x -> openEditor(-1));

        // Geser kanan = hapus (ke sampah), geser kiri = arsipkan. Keduanya bisa di-undo.
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                                  @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                NoteEntity n = adapter.peek(vh.getBindingAdapterPosition());
                if (n == null) {
                    adapter.refresh();
                    return;
                }
                if (direction == ItemTouchHelper.LEFT) {
                    archiveWithUndo(n);
                    return;
                }
                NoteRepository.get(requireContext()).trashNote(n.id, r ->
                        main.post(() -> {
                            adapter.refresh();
                            Snackbar.make(recycler, R.string.deleted_undo, Snackbar.LENGTH_LONG)
                                    .setAction(R.string.undo, a ->
                                            NoteRepository.get(requireContext())
                                                    .restoreNote(n.id, r2 -> main.post(adapter::refresh)))
                                    .show();
                        }));
            }
        }).attachToRecyclerView(recycler);

        return v;
    }

    private void openEditor(long id) {
        Intent i = new Intent(requireContext(), EditorActivity.class);
        i.putExtra("noteId", id);
        startActivity(i);
    }

    /** Bangun ulang pager sesuai filter tag + urutan aktif. */
    private void refreshList() {
        final String sort = getSort();
        Pager<Integer, NoteEntity> pager;
        if (filterTagId != null) {
            final long tid = filterTagId;
            pager = new Pager<>(new PagingConfig(60), () -> {
                NoteRepository r = NoteRepository.get(requireContext());
                switch (sort) {
                    case "oldest": return r.pagingByTagOldest(tid);
                    case "title": return r.pagingByTagTitle(tid);
                    case "color": return r.pagingByTagColor(tid);
                    default: return r.pagingByTag(tid);
                }
            });
            tagFilterChip.setText(getString(R.string.tag_filter_active, filterTagName));
            tagFilterChip.setVisibility(View.VISIBLE);
        } else {
            pager = new Pager<>(new PagingConfig(60), () -> {
                NoteRepository r = NoteRepository.get(requireContext());
                switch (sort) {
                    case "oldest": return r.pagingActiveOldest();
                    case "title": return r.pagingActiveTitle();
                    case "color": return r.pagingActiveColor();
                    default: return r.pagingActive();
                }
            });
            tagFilterChip.setVisibility(View.GONE);
        }
        PagingLiveData.getLiveData(pager).observe(getViewLifecycleOwner(),
                pagingData -> adapter.submitData(getViewLifecycleOwner().getLifecycle(),
                        pagingData));
    }

    // ---------- tampilan ala Keep: grid/list + urutan ----------

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String getSort() {
        return prefs().getString(KEY_SORT, "newest");
    }

    private boolean isGridMode() {
        return !"list".equals(prefs().getString(KEY_VIEW_MODE, "grid"));
    }

    private void applyLayoutManager() {
        RecyclerView.LayoutManager lm;
        if (isGridMode()) {
            StaggeredGridLayoutManager sglm =
                    new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
            sglm.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
            lm = sglm;
        } else {
            lm = new LinearLayoutManager(requireContext());
        }
        recycler.setLayoutManager(lm);
    }

    private void toggleViewMode() {
        prefs().edit().putString(KEY_VIEW_MODE, isGridMode() ? "list" : "grid").apply();
        applyLayoutManager();
        updateViewToggleIcon();
        adapter.refresh();
    }

    private void updateViewToggleIcon() {
        if (viewToggle == null) return;
        viewToggle.setImageResource(isGridMode()
                ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
    }

    private void showSortSheet() {
        final String cur = getSort();
        IosActionSheet s = new IosActionSheet(requireContext())
                .setTitle(getString(R.string.notes_sort_title));
        s.addItem((cur.equals("newest") ? "✓ " : "") + getString(R.string.notes_sort_newest),
                () -> setSort("newest"));
        s.addItem((cur.equals("oldest") ? "✓ " : "") + getString(R.string.notes_sort_oldest),
                () -> setSort("oldest"));
        s.addItem((cur.equals("title") ? "✓ " : "") + getString(R.string.notes_sort_title_az),
                () -> setSort("title"));
        s.addItem((cur.equals("color") ? "✓ " : "") + getString(R.string.notes_sort_color),
                () -> setSort("color"));
        s.show();
    }

    private void setSort(String sort) {
        prefs().edit().putString(KEY_SORT, sort).apply();
        refreshList();
    }

    /** Dipakai FolderFragment agar mode grid/list konsisten di semua daftar. */
    public static boolean isGridModeStatic(Context ctx) {
        return !"list".equals(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VIEW_MODE, "grid"));
    }

    private void clearTagFilter() {
        filterTagId = null;
        filterTagName = null;
        refreshList();
    }

    private void showTagFilterSheet() {
        NoteRepository.get(requireContext()).getTags(tags -> main.post(() -> {
            IosActionSheet s = new IosActionSheet(requireContext())
                    .setTitle(getString(R.string.tag_filter));
            s.addItem(getString(R.string.tag_all), this::clearTagFilter);
            if (tags != null) {
                for (com.hozinking.notes.data.db.TagEntity t : tags) {
                    final long tid = t.id;
                    final String tname = t.name;
                    s.addItem("#" + tname, () -> {
                        filterTagId = tid;
                        filterTagName = tname;
                        refreshList();
                    });
                }
            }
            s.show();
        }));
    }

    private void persist(NoteEntity n) {
        n.updatedAt = System.currentTimeMillis();
        NoteRepository.get(requireContext()).updateNote(n, v -> main.post(adapter::refresh));
    }

    /** Arsipkan via swipe kiri, dengan opsi urungkan. */
    private void archiveWithUndo(NoteEntity n) {
        n.archived = true;
        persist(n);
        Snackbar.make(recycler, R.string.archived_undo, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, a -> {
                    n.archived = false;
                    persist(n);
                })
                .show();
    }

    /** Bagikan catatan dari daftar: ambil isi penuh dulu (list hanya bawa 180 karakter). */
    private void shareNoteFull(NoteEntity n) {
        NoteRepository.get(requireContext()).getNote(n.id, full -> main.post(() -> {
            if (full == null) return;
            String title = (full.title == null || full.title.trim().isEmpty())
                    ? getString(R.string.notes_no_title) : full.title.trim();
            String body = full.content == null ? "" : full.content;
            if (full.contentHtml) {
                body = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString();
            }
            body = body.trim();
            String text = body.isEmpty() ? title : title + "\n\n" + body;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, text);
            try {
                startActivity(Intent.createChooser(i, getString(R.string.share)));
            } catch (Exception ignored) {
            }
        }));
    }

    /** Duplikat catatan dari daftar (teks + tag + warna + folder). */
    private void duplicateNote(NoteEntity n) {
        NoteRepository.get(requireContext()).duplicateNote(n.id, id -> main.post(() -> {
            if (id != null && id > 0) {
                Toast.makeText(requireContext(),
                        R.string.note_duplicated, Toast.LENGTH_SHORT).show();
                adapter.refresh();
            } else {
                Toast.makeText(requireContext(),
                        R.string.duplicate_failed, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    private void showActions(NoteEntity n) {
        String title = (n.title == null || n.title.trim().isEmpty())
                ? getString(R.string.notes_no_title) : n.title.trim();
        new IosActionSheet(requireContext())
                .setTitle(title)
                .addItem(getString(n.pinned ? R.string.unpin : R.string.pin),
                        () -> { n.pinned = !n.pinned; persist(n); })
                .addItem(getString(R.string.archive),
                        () -> { n.archived = true; persist(n); })
                .addItem(getString(R.string.share), () -> shareNoteFull(n))
                .addItem(getString(R.string.duplicate), () -> duplicateNote(n))
                .addItem(getString(R.string.notes_color_title), () -> showColorSheet(n))
                .addDestructive(getString(R.string.delete), () -> confirmDelete(n))
                .show();
    }

    private void showColorSheet(NoteEntity n) {
        IosActionSheet s = new IosActionSheet(requireContext())
                .setTitle(getString(R.string.notes_color_title));
        s.addItem(getString(R.string.notes_color_none), () -> setColor(n, 0));
        s.addItem(getString(R.string.notes_color_yellow),
                () -> setColor(n, requireContext().getColor(R.color.note_yellow)));
        s.addItem(getString(R.string.notes_color_green),
                () -> setColor(n, requireContext().getColor(R.color.note_green)));
        s.addItem(getString(R.string.notes_color_blue),
                () -> setColor(n, requireContext().getColor(R.color.note_blue)));
        s.addItem(getString(R.string.notes_color_pink),
                () -> setColor(n, requireContext().getColor(R.color.note_pink)));
        s.addItem(getString(R.string.notes_color_purple),
                () -> setColor(n, requireContext().getColor(R.color.note_purple)));
        s.show();
    }

    private void setColor(NoteEntity n, int color) {
        n.color = color;
        persist(n);
    }

    private void confirmDelete(NoteEntity n) {
        new IosDialog(requireContext())
                .setTitle(getString(R.string.notes_delete_title))
                .setMessage(getString(R.string.notes_delete_msg))
                .setPositive(getString(R.string.delete), () ->
                        NoteRepository.get(requireContext())
                                .trashNote(n.id, v -> main.post(adapter::refresh)))
                .setNegative(getString(R.string.cancel), () -> {})
                .show();
    }
}
