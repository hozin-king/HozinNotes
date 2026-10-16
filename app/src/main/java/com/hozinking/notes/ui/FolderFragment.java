package com.hozinking.notes.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import android.util.TypedValue;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.FolderEntity;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.db.TagEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab "Folder": chip folder + chip tag + daftar catatan yang bisa
 * difilter (semua / per folder / per tag). Long-press folder = hapus.
 */
public class FolderFragment extends Fragment {

    private LinearLayout folderChips;
    private LinearLayout tagChips;
    private View tagChipRow;
    private NoteAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Long selectedFolderId = null;
    private Long selectedTagId = null;
    private LiveData<PagingData<NoteEntity>> liveData;

    private final List<Button> folderButtons = new ArrayList<>();
    private final List<Button> tagButtons = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_folder, container, false);
        folderChips = v.findViewById(R.id.folder_chips);
        tagChips = v.findViewById(R.id.tag_chips);
        tagChipRow = v.findViewById(R.id.tag_chip_row);
        RecyclerView recycler = v.findViewById(R.id.recycler);
        if (NoteListFragment.isGridModeStatic(requireContext())) {
            androidx.recyclerview.widget.StaggeredGridLayoutManager sglm =
                    new androidx.recyclerview.widget.StaggeredGridLayoutManager(
                            2, androidx.recyclerview.widget.StaggeredGridLayoutManager.VERTICAL);
            sglm.setGapStrategy(androidx.recyclerview.widget.StaggeredGridLayoutManager
                    .GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
            recycler.setLayoutManager(sglm);
        } else {
            recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        }

        adapter = new NoteAdapter(requireContext(), new NoteAdapter.Listener() {
            @Override
            public void onClick(NoteEntity note) {
                openEditor(note.id);
            }

            @Override
            public void onLongClick(NoteEntity note, View anchor) {
                openEditor(note.id);
            }
        });
        recycler.setAdapter(adapter);

        loadFolders();
        loadTags();
        applyFilter();
        return v;
    }

    private void openEditor(long id) {
        Intent i = new Intent(requireContext(), EditorActivity.class);
        i.putExtra("noteId", id);
        startActivity(i);
    }

    private int dp(int n) {
        return (int) (n * requireContext().getResources().getDisplayMetrics().density);
    }

    private int resolveAttr(int attr) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private Button makeChip(String label) {
        Button b = new Button(requireContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = dp(4);
        lp.setMargins(m, m, m, m);
        b.setLayoutParams(lp);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        return b;
    }

    private void styleChip(Button b, boolean selected) {
        if (selected) {
            b.setBackgroundTintList(ColorStateList.valueOf(
                    resolveAttr(androidx.appcompat.R.attr.colorPrimary)));
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackgroundTintList(null);
            b.setTextColor(resolveAttr(android.R.attr.textColorPrimary));
        }
    }

    private void refreshChipStyles() {
        for (Button b : folderButtons) {
            Object tag = b.getTag();
            boolean sel = ("ALL".equals(tag) && selectedFolderId == null && selectedTagId == null)
                    || (tag instanceof Long && tag.equals(selectedFolderId));
            styleChip(b, sel);
        }
        for (Button b : tagButtons) {
            Object tag = b.getTag();
            styleChip(b, tag instanceof Long && tag.equals(selectedTagId));
        }
    }

    // ---------- folder ----------

    private void loadFolders() {
        NoteRepository.get(requireContext()).getFolders(folders -> main.post(() -> {
            if (!isAdded()) return;
            folderChips.removeAllViews();
            folderButtons.clear();

            Button all = makeChip(getString(R.string.notes_all));
            all.setTag("ALL");
            all.setOnClickListener(v -> {
                selectedFolderId = null;
                selectedTagId = null;
                refreshChipStyles();
                applyFilter();
            });
            folderChips.addView(all);
            folderButtons.add(all);

            if (folders != null) {
                for (FolderEntity f : folders) {
                    if (f.name == null || f.name.trim().isEmpty()) continue;
                    Button b = makeChip(f.name);
                    b.setTag(f.id);
                    b.setOnClickListener(v -> {
                        selectedFolderId = f.id;
                        selectedTagId = null;
                        refreshChipStyles();
                        applyFilter();
                    });
                    b.setOnLongClickListener(v -> {
                        confirmDeleteFolder(f);
                        return true;
                    });
                    folderChips.addView(b);
                    folderButtons.add(b);
                }
            }

            Button add = makeChip(getString(R.string.notes_add_folder));
            add.setOnClickListener(v -> showAddFolderDialog());
            folderChips.addView(add);

            refreshChipStyles();
        }));
    }

    private void showAddFolderDialog() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.notes_folder_name_hint);
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.notes_add_folder)
                .setView(input)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) showFolderColorDialog(name);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showFolderColorDialog(String name) {
        String[] names = {
                getString(R.string.notes_color_none),
                getString(R.string.notes_color_yellow),
                getString(R.string.notes_color_green),
                getString(R.string.notes_color_blue),
                getString(R.string.notes_color_pink),
                getString(R.string.notes_color_purple)};
        int[] colors = {
                0,
                requireContext().getColor(R.color.note_yellow),
                requireContext().getColor(R.color.note_green),
                requireContext().getColor(R.color.note_blue),
                requireContext().getColor(R.color.note_pink),
                requireContext().getColor(R.color.note_purple)};
        int[] sel = {0};
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.notes_color_title)
                .setSingleChoiceItems(names, 0, (d, w) -> sel[0] = w)
                .setPositiveButton(R.string.save, (d, w) ->
                        NoteRepository.get(requireContext()).insertFolder(
                                name, colors[sel[0]], id -> main.post(this::loadFolders)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmDeleteFolder(FolderEntity f) {
        new IosDialog(requireContext())
                .setTitle(getString(R.string.notes_delete_folder_title))
                .setMessage(getString(R.string.notes_delete_folder_msg))
                .setPositive(getString(R.string.delete), () ->
                        NoteRepository.get(requireContext()).deleteFolder(f.id, v -> main.post(() -> {
                            if (selectedFolderId != null && f.id == selectedFolderId) {
                                selectedFolderId = null;
                            }
                            loadFolders();
                            applyFilter();
                        })))
                .setNegative(getString(R.string.cancel), () -> {
                })
                .show();
    }

    // ---------- tag ----------

    private void loadTags() {
        NoteRepository.get(requireContext()).getTags(tags -> main.post(() -> {
            if (!isAdded()) return;
            tagChips.removeAllViews();
            tagButtons.clear();
            boolean hasTag = false;
            if (tags != null) {
                for (TagEntity t : tags) {
                    if (t.name == null || t.name.trim().isEmpty()) continue;
                    hasTag = true;
                    Button b = makeChip("#" + t.name);
                    b.setTag(t.id);
                    b.setOnClickListener(v -> {
                        if (selectedTagId != null && t.id == selectedTagId) {
                            selectedTagId = null;
                        } else {
                            selectedTagId = t.id;
                            selectedFolderId = null;
                        }
                        refreshChipStyles();
                        applyFilter();
                    });
                    tagChips.addView(b);
                    tagButtons.add(b);
                }
            }
            if (tagChipRow != null) {
                tagChipRow.setVisibility(hasTag ? View.VISIBLE : View.GONE);
            }
            refreshChipStyles();
        }));
    }

    // ---------- filter ----------

    private void applyFilter() {
        if (liveData != null) liveData.removeObservers(getViewLifecycleOwner());
        Pager<Integer, NoteEntity> pager;
        if (selectedTagId != null) {
            long id = selectedTagId;
            pager = new Pager<>(new PagingConfig(60),
                    () -> NoteRepository.get(requireContext()).pagingByTag(id));
        } else if (selectedFolderId != null) {
            long id = selectedFolderId;
            pager = new Pager<>(new PagingConfig(60),
                    () -> NoteRepository.get(requireContext()).pagingByFolder(id));
        } else {
            pager = new Pager<>(new PagingConfig(60),
                    () -> NoteRepository.get(requireContext()).pagingActive());
        }
        liveData = PagingLiveData.getLiveData(pager);
        liveData.observe(getViewLifecycleOwner(),
                pagingData -> adapter.submitData(getViewLifecycleOwner().getLifecycle(),
                        pagingData.map(NoteListItem::ofNote)));
    }
}
