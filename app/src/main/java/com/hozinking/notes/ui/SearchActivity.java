package com.hozinking.notes.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.LiveData;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.data.repo.NoteRepository;

/** Pencarian full-text (FTS) dengan debounce 300ms, reuse NoteAdapter. */
public class SearchActivity extends AppCompatActivity {

    private NoteAdapter adapter;
    private TextView hintView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;
    private LiveData<PagingData<NoteEntity>> liveData;
    /** Kata kunci terakhir — diteruskan ke editor agar cocok langsung tersorot. */
    private String lastQuery = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        RecyclerView recycler = findViewById(R.id.recycler);
        hintView = findViewById(R.id.hint_view);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        adapter = new NoteAdapter(this, new NoteAdapter.Listener() {
            @Override
            public void onClick(NoteEntity note) {
                NoteLockGate.openMaybeLocked(SearchActivity.this, note,
                        id -> openEditor(id));
            }

            @Override
            public void onLongClick(NoteEntity note, View anchor) {
                NoteLockGate.openMaybeLocked(SearchActivity.this, note,
                        id -> openEditor(id));
            }
        });
        recycler.setAdapter(adapter);

        EditText searchInput = findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new SimpleTextWatcher(s -> {
            handler.removeCallbacks(pending);
            String q = s.toString();
            pending = () -> doSearch(q);
            handler.postDelayed(pending, 300);
        }));
    }

    private void doSearch(String query) {
        String q = query.trim();
        lastQuery = q;
        if (liveData != null) liveData.removeObservers(this);
        if (q.isEmpty()) {
            liveData = null;
            adapter.submitData(getLifecycle(), PagingData.<NoteListItem>empty());
            hintView.setVisibility(View.VISIBLE);
            return;
        }
        hintView.setVisibility(View.GONE);
        Pager<Integer, NoteEntity> pager = new Pager<>(
                new PagingConfig(60),
                () -> NoteRepository.get(this).searchFts(q));
        liveData = PagingLiveData.getLiveData(pager);
        liveData.observe(this,
                pagingData -> adapter.submitData(getLifecycle(),
                        pagingData.map(NoteListItem::ofNote)));
    }

    private void openEditor(long id) {
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra("noteId", id);
        // v1.14: editor membuka bar cari berisi kata kunci hasil pencarian.
        if (!lastQuery.isEmpty()) i.putExtra("findQuery", lastQuery);
        startActivity(i);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(pending);
        super.onDestroy();
    }
}
