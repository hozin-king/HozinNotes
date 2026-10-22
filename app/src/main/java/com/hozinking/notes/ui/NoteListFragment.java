package com.hozinking.notes.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.paging.LoadState;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
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
import com.hozinking.notes.reminder.PinnedNoteManager;
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
    // v1.16: tambah "created_new" | "created_old" (urut tanggal dibuat).
    private static final String KEY_SORT = "sort"; // newest | oldest | title | color | created_new | created_old | manual

    // Sematkan catatan di notifikasi (izin POST_NOTIFICATIONS API 33+).
    private static final int RC_NOTIF_PIN = 3001;
    private long pendingPinId = -1;

    /** PIN vault -> bila OK, buka Ruang Privat. Teruskan flag PIN kamuflase bila ada. */
    private final ActivityResultLauncher<Intent> vaultLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            Intent open = new Intent(requireContext(), VaultActivity.class);
                            if (result.getData() != null && result.getData().getBooleanExtra(
                                    VaultPinActivity.EXTRA_DECOY_USED, false)) {
                                open.putExtra(VaultActivity.EXTRA_DECOY, true);
                            }
                            startActivity(open);
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
                NoteLockGate.openMaybeLocked(requireActivity(), note,
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
                        .addItem(getString(R.string.mood_today), this::showMoodPicker)
                        .addItem(getString(R.string.mood_stats), () ->
                                startActivity(new Intent(requireContext(), MoodStatsActivity.class)))
                        .addItem(getString(R.string.usage_title), () ->
                                startActivity(new Intent(requireContext(), StatsActivity.class)))
                        .addItem(getString(R.string.tag_filter), this::showTagFilterSheet)
                        .addItem(getString(R.string.notes_sort_title), this::showSortSheet)
                        .show());
        FloatingActionButton fab = v.findViewById(R.id.fab);
        fab.setOnClickListener(x -> openEditor(-1));
        // Tahan tombol + untuk memilih template catatan (pola umum aplikasi catatan).
        fab.setOnLongClickListener(x -> {
            showTemplateSheet();
            return true;
        });

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
            public int getSwipeDirs(@NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh) {
                // v1.16: header seksi tidak bisa di-swipe.
                NoteListItem it = adapter.peek(vh.getBindingAdapterPosition());
                if (it == null || it.note == null) return 0;
                return super.getSwipeDirs(rv, vh);
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                NoteListItem it = adapter.peek(vh.getBindingAdapterPosition());
                NoteEntity n = it == null ? null : it.note;
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

    /** Sheet pilihan template — inspirasi pola quick-capture aplikasi catatan populer. */
    private void showTemplateSheet() {
        String today = new java.text.SimpleDateFormat("d MMM yyyy",
                new java.util.Locale("id")).format(new java.util.Date());
        new IosActionSheet(requireContext())
                .setTitle(getString(R.string.template_title))
                .addItem(getString(R.string.template_blank), () -> openEditor(-1))
                .addItem(getString(R.string.template_shopping), () ->
                        openEditorWithTemplate(getString(R.string.template_shopping),
                                "- [ ] \n- [ ] \n- [ ] "))
                .addItem(getString(R.string.template_meeting), () ->
                        openEditorWithTemplate(getString(R.string.template_meeting),
                                "Tanggal: " + today + "\nPeserta: \n\nAgenda:\n- \n\n"
                                        + "Keputusan:\n- \n\nTindak lanjut:\n- [ ] "))
                .addItem(getString(R.string.template_journal), () ->
                        openEditorWithTemplate("Jurnal \u2014 " + today,
                                today + "\n\n"))
                .show();
    }

    private void openEditorWithTemplate(String title, String content) {
        Intent i = new Intent(requireContext(), EditorActivity.class);
        i.putExtra("noteId", -1L);
        i.putExtra("templateTitle", title);
        i.putExtra("templateContent", content);
        startActivity(i);
    }

    /** Bangun ulang pager sesuai filter tag + urutan aktif. */
    private void refreshList() {
        final String sort = getSort();
        // v1.16: header seksi waktu hanya untuk urutan berbasis tanggal.
        final boolean dateSort = sort.equals("newest") || sort.equals("oldest")
                || sort.equals("created_new") || sort.equals("created_old");
        final boolean useCreated = sort.startsWith("created");
        Pager<Integer, NoteEntity> pager;
        // v1.16: placeholders dimatikan agar header seksi tidak flicker
        // saat halaman baru dimuat (insertSeparators butuh aliran kontinu).
        PagingConfig config = new PagingConfig(60, 60, false);
        if (filterTagId != null) {
            final long tid = filterTagId;
            pager = new Pager<>(config, () -> {
                NoteRepository r = NoteRepository.get(requireContext());
                switch (sort) {
                    case "oldest": return r.pagingByTagOldest(tid);
                    case "created_new": return r.pagingByTagCreated(tid);
                    case "created_old": return r.pagingByTagCreatedOld(tid);
                    case "title": return r.pagingByTagTitle(tid);
                    case "color": return r.pagingByTagColor(tid);
                    case "manual": return r.pagingByTagManual(tid);
                    default: return r.pagingByTag(tid);
                }
            });
            tagFilterChip.setText(getString(R.string.tag_filter_active, filterTagName));
            tagFilterChip.setVisibility(View.VISIBLE);
        } else {
            pager = new Pager<>(config, () -> {
                NoteRepository r = NoteRepository.get(requireContext());
                switch (sort) {
                    case "oldest": return r.pagingActiveOldest();
                    case "created_new": return r.pagingActiveCreated();
                    case "created_old": return r.pagingActiveCreatedOld();
                    case "title": return r.pagingActiveTitle();
                    case "color": return r.pagingActiveColor();
                    case "manual": return r.pagingActiveManual();
                    default: return r.pagingActive();
                }
            });
            tagFilterChip.setVisibility(View.GONE);
        }
        PagingLiveData.getLiveData(pager).observe(getViewLifecycleOwner(),
                pagingData -> {
                    // v1.16: map/insertSeparators adalah extension Kotlin —
                    // dari Java dipanggil via PagingXforms.
                    PagingData<NoteListItem> items =
                            PagingXforms.map(pagingData, NoteListItem::ofNote);
                    if (dateSort) {
                        items = PagingXforms.insertSeparators(items,
                                (before, after) -> separatorFor(before, after, useCreated));
                    }
                    adapter.submitData(getViewLifecycleOwner().getLifecycle(), items);
                });
    }

    /**
     * v1.16: header seksi waktu di antara catatan ("📌 Disematkan",
     * "Hari ini", "Kemarin", ...). Konsep pengelompokan dipinjam dari
     * Noto (alialbaali/Noto, Apache-2.0); implementasi sendiri via
     * Paging 3 insertSeparators.
     */
    private NoteListItem separatorFor(NoteListItem before, NoteListItem after,
                                      boolean useCreated) {
        if (after == null || after.note == null) return null;
        boolean afterPinned = after.note.pinned;
        boolean beforePinned = before != null && before.note != null && before.note.pinned;
        if (afterPinned) {
            return beforePinned ? null
                    : NoteListItem.ofHeader(getString(R.string.section_pinned));
        }
        long ts = useCreated ? after.note.createdAt : after.note.updatedAt;
        int sec = sectionOf(ts);
        Integer bsec = null;
        if (before != null && before.note != null && !before.note.pinned) {
            bsec = sectionOf(useCreated ? before.note.createdAt : before.note.updatedAt);
        }
        if (bsec == null || bsec != sec) {
            return NoteListItem.ofHeader(getString(sectionTitleRes(sec)));
        }
        return null;
    }

    /** 0 = hari ini, 1 = kemarin, 2 = 7 hari terakhir, 3 = 30 hari terakhir, 4 = lebih lama. */
    private static int sectionOf(long ts) {
        java.time.LocalDate d = java.time.Instant.ofEpochMilli(ts)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        java.time.LocalDate today = java.time.LocalDate.now();
        if (d.isEqual(today)) return 0;
        if (d.isEqual(today.minusDays(1))) return 1;
        if (!d.isBefore(today.minusDays(7))) return 2;
        if (!d.isBefore(today.minusMonths(1))) return 3;
        return 4;
    }

    private static int sectionTitleRes(int sec) {
        switch (sec) {
            case 0: return R.string.section_today;
            case 1: return R.string.section_yesterday;
            case 2: return R.string.section_week;
            case 3: return R.string.section_month;
            default: return R.string.section_older;
        }
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

    /** v1.21: pilih mood hari ini (sheet 5 pilihan, tandai yang sudah tersimpan). */
    private void showMoodPicker() {
        final String today = MoodHelper.today();
        NoteRepository.get(requireContext()).moodForDate(today, cur ->
                main.post(() -> {
                    IosActionSheet s = new IosActionSheet(requireContext())
                            .setTitle(getString(R.string.mood_pick_title));
                    for (int m = MoodHelper.MIN; m <= MoodHelper.MAX; m++) {
                        final int mood = m;
                        s.addItem((cur == m ? "✓ " : "") + MoodHelper.emoji(m)
                                        + " " + MoodHelper.label(m),
                                () -> saveMood(today, mood));
                    }
                    s.show();
                }));
    }

    private void saveMood(String date, int mood) {
        NoteRepository.get(requireContext()).saveMood(date, mood, v ->
                main.post(() -> Toast.makeText(requireContext(),
                        R.string.mood_saved, Toast.LENGTH_SHORT).show()));
    }

    private void showSortSheet() {
        final String cur = getSort();
        IosActionSheet s = new IosActionSheet(requireContext())
                .setTitle(getString(R.string.notes_sort_title));
        s.addItem((cur.equals("newest") ? "✓ " : "") + getString(R.string.notes_sort_newest),
                () -> setSort("newest"));
        s.addItem((cur.equals("oldest") ? "✓ " : "") + getString(R.string.notes_sort_oldest),
                () -> setSort("oldest"));
        s.addItem((cur.equals("created_new") ? "✓ " : "") + getString(R.string.notes_sort_created_new),
                () -> setSort("created_new"));
        s.addItem((cur.equals("created_old") ? "✓ " : "") + getString(R.string.notes_sort_created_old),
                () -> setSort("created_old"));
        s.addItem((cur.equals("title") ? "✓ " : "") + getString(R.string.notes_sort_title_az),
                () -> setSort("title"));
        s.addItem((cur.equals("color") ? "✓ " : "") + getString(R.string.notes_sort_color),
                () -> setSort("color"));
        // v1.17: urutan manual — posisi diatur lewat menu tahan-lama tiap catatan.
        s.addItem((cur.equals("manual") ? "✓ " : "") + getString(R.string.notes_sort_manual),
                () -> setSort("manual"));
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

    /** Gabungkan catatan: pilih tujuan -> konfirmasi -> pindah isi+gambar+tag. */
    private void showMergePicker(NoteEntity n) {
        NoteRepository.get(requireContext()).getMergeTargets(n.id, targets -> main.post(() -> {
            if (targets == null || targets.isEmpty()) {
                Toast.makeText(requireContext(),
                        R.string.merge_none, Toast.LENGTH_SHORT).show();
                return;
            }
            final java.util.List<NoteEntity> list = new java.util.ArrayList<>(targets);
            String[] titles = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                NoteEntity t = list.get(i);
                String tt = (t.title == null || t.title.trim().isEmpty())
                        ? getString(R.string.notes_no_title) : t.title.trim();
                titles[i] = tt;
            }
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.merge_pick_target)
                    .setItems(titles, (d, which) -> confirmMerge(n, list.get(which)))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));
    }

    private void confirmMerge(NoteEntity src, NoteEntity target) {
        String srcTitle = (src.title == null || src.title.trim().isEmpty())
                ? getString(R.string.notes_no_title) : src.title.trim();
        String tgtTitle = (target.title == null || target.title.trim().isEmpty())
                ? getString(R.string.notes_no_title) : target.title.trim();
        new IosDialog(requireContext())
                .setTitle(getString(R.string.merge_confirm_title))
                .setMessage(getString(R.string.merge_confirm_msg, srcTitle, tgtTitle))
                .setNegative(getString(R.string.cancel), null)
                .setPositive(getString(R.string.merge_do), () -> doMerge(src, target))
                .show();
    }

    private void doMerge(NoteEntity src, NoteEntity target) {
        NoteRepository.get(requireContext()).mergeNote(src.id, target.id, ok -> main.post(() -> {
            if (Boolean.TRUE.equals(ok)) {
                Toast.makeText(requireContext(),
                        R.string.merge_done, Toast.LENGTH_SHORT).show();
                adapter.refresh();
            } else {
                Toast.makeText(requireContext(),
                        R.string.merge_fail, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    /** Sematkan/lepas catatan sebagai notifikasi persisten (ala nproth/pin). */
    private void togglePinnedNotification(NoteEntity n) {
        if (PinnedNoteManager.isPinned(requireContext(), n.id)) {
            PinnedNoteManager.unpin(requireContext());
            Toast.makeText(requireContext(),
                    R.string.pinned_notif_unpinned, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(requireContext(),
                        Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPinId = n.id;
            Toast.makeText(requireContext(),
                    R.string.pinned_notif_need_permission, Toast.LENGTH_SHORT).show();
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIF_PIN);
            return;
        }
        PinnedNoteManager.pin(requireContext(), n.id);
        Toast.makeText(requireContext(),
                R.string.pinned_notif_pinned, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_NOTIF_PIN) {
            long id = pendingPinId;
            pendingPinId = -1;
            if (id > 0 && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                PinnedNoteManager.pin(requireContext(), id);
                Toast.makeText(requireContext(),
                        R.string.pinned_notif_pinned, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showActions(NoteEntity n) {
        String title = (n.title == null || n.title.trim().isEmpty())
                ? getString(R.string.notes_no_title) : n.title.trim();
        IosActionSheet sheet = new IosActionSheet(requireContext())
                .setTitle(title);
        // v1.17: di mode urut Manual, tawarkan pindah posisi.
        if ("manual".equals(getSort())) {
            sheet.addItem(getString(R.string.manual_move_top),
                    () -> moveManualToTop(n.id));
            sheet.addItem(getString(R.string.manual_move_up),
                    () -> moveManual(n.id, -1));
            sheet.addItem(getString(R.string.manual_move_down),
                    () -> moveManual(n.id, 1));
        }
        sheet.addItem(getString(n.pinned ? R.string.unpin : R.string.pin),
                        () -> { n.pinned = !n.pinned; persist(n); })
                .addItem(getString(R.string.archive),
                        () -> { n.archived = true; persist(n); })
                .addItem(getString(PinnedNoteManager.isPinned(requireContext(), n.id)
                                ? R.string.unpin_notification : R.string.pin_notification),
                        () -> togglePinnedNotification(n))
                .addItem(getString(R.string.share), () -> shareNoteFull(n))
                .addItem(getString(R.string.duplicate), () -> duplicateNote(n))
                .addItem(getString(R.string.merge_note), () -> showMergePicker(n))
                .addItem(getString(R.string.notes_color_title), () -> showColorSheet(n))
                .addDestructive(getString(R.string.delete), () -> confirmDelete(n))
                .show();
    }

    /** v1.17: pindah satu langkah dalam urutan manual, lalu muat ulang daftar. */
    private void moveManual(long id, int dir) {
        NoteRepository.get(requireContext()).moveManual(id, dir, moved ->
                main.post(() -> {
                    if (moved) {
                        adapter.refresh();
                    } else {
                        Toast.makeText(requireContext(),
                                R.string.manual_move_edge, Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    /** v1.17: pindah ke paling atas urutan manual. */
    private void moveManualToTop(long id) {
        NoteRepository.get(requireContext()).moveManualToTop(id, moved ->
                main.post(() -> {
                    if (moved) {
                        adapter.refresh();
                    } else {
                        Toast.makeText(requireContext(),
                                R.string.manual_move_edge, Toast.LENGTH_SHORT).show();
                    }
                }));
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
