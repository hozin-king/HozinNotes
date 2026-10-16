package com.hozinking.notes.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.paging.LoadState;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingDataKt;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosActionSheet;
import com.hozinking.notes.ui.dialog.IosDialog;

import kotlin.Unit;

/**
 * Sampah. Tiap item: pulihkan / hapus permanen.
 * Tombol atas = hapus permanen catatan sampah berumur >30 hari (purgeTrash).
 */
public class TrashActivity extends AppCompatActivity {

    private NoteAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_trash);

        RecyclerView recycler = findViewById(R.id.recycler);
        TextView emptyView = findViewById(R.id.empty_view);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        adapter = new NoteAdapter(this, new NoteAdapter.Listener() {
            @Override
            public void onClick(NoteEntity note) {
                showActions(note);
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
                () -> NoteRepository.get(this).pagingTrashed());
        PagingLiveData.getLiveData(pager).observe(this,
                pagingData -> adapter.submitData(getLifecycle(),
                        PagingDataKt.map(pagingData, NoteListItem::ofNote)));

        findViewById(R.id.btn_purge).setOnClickListener(v -> confirmPurge());
    }

    private void showActions(NoteEntity n) {
        String title = (n.title == null || n.title.trim().isEmpty())
                ? getString(R.string.notes_no_title) : n.title.trim();
        new IosActionSheet(this)
                .setTitle(title)
                .addItem(getString(R.string.restore), () ->
                        NoteRepository.get(this)
                                .restoreNote(n.id, v -> main.post(adapter::refresh)))
                .addDestructive(getString(R.string.delete_forever), () ->
                        new IosDialog(this)
                                .setTitle(getString(R.string.notes_delete_forever_title))
                                .setMessage(getString(R.string.notes_delete_forever_msg))
                                .setPositive(getString(R.string.delete), () ->
                                        NoteRepository.get(this)
                                                .deleteForever(n.id, v -> main.post(adapter::refresh)))
                                .setNegative(getString(R.string.cancel), () -> {
                                })
                                .show())
                .show();
    }

    private void confirmPurge() {
        new IosDialog(this)
                .setTitle(getString(R.string.notes_purge_title))
                .setMessage(getString(R.string.notes_purge_msg))
                .setPositive(getString(R.string.delete), () ->
                        NoteRepository.get(this).purgeTrash(count -> main.post(() -> {
                            adapter.refresh();
                            Toast.makeText(this,
                                    getString(R.string.notes_purged, count),
                                    Toast.LENGTH_SHORT).show();
                        })))
                .setNegative(getString(R.string.cancel), () -> {
                })
                .show();
    }
}
