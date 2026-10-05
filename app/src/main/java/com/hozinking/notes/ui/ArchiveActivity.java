package com.hozinking.notes.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.paging.LoadState;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosActionSheet;
import com.hozinking.notes.ui.dialog.IosDialog;

import kotlin.Unit;

/** Daftar catatan yang diarsipkan. Long-press: keluarkan / hapus. */
public class ArchiveActivity extends AppCompatActivity {

    private NoteAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_archive);

        RecyclerView recycler = findViewById(R.id.recycler);
        TextView emptyView = findViewById(R.id.empty_view);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        adapter = new NoteAdapter(this, new NoteAdapter.Listener() {
            @Override
            public void onClick(NoteEntity note) {
                NoteLockGate.openMaybeLocked(ArchiveActivity.this, note,
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

        Pager<Integer, NoteEntity> pager = new Pager<>(
                new PagingConfig(60),
                () -> NoteRepository.get(this).pagingArchived());
        PagingLiveData.getLiveData(pager).observe(this,
                pagingData -> adapter.submitData(getLifecycle(), pagingData));
    }

    private void openEditor(long id) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra("noteId", id);
        startActivity(i);
    }

    private void showActions(NoteEntity n) {
        String title = (n.title == null || n.title.trim().isEmpty())
                ? getString(R.string.notes_no_title) : n.title.trim();
        new IosActionSheet(this)
                .setTitle(title)
                .addItem(getString(R.string.unarchive), () -> {
                    n.archived = false;
                    n.updatedAt = System.currentTimeMillis();
                    NoteRepository.get(this).updateNote(n, v -> main.post(adapter::refresh));
                })
                .addDestructive(getString(R.string.delete), () ->
                        new IosDialog(this)
                                .setTitle(getString(R.string.notes_delete_title))
                                .setMessage(getString(R.string.notes_delete_msg))
                                .setPositive(getString(R.string.delete), () ->
                                        NoteRepository.get(this)
                                                .trashNote(n.id, v -> main.post(adapter::refresh)))
                                .setNegative(getString(R.string.cancel), () -> {
                                })
                                .show())
                .show();
    }
}
