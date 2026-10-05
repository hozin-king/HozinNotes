package com.hozinking.notes.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.paging.Pager;
import androidx.paging.PagingLiveData;
import androidx.paging.PagingConfig;
import androidx.paging.PagingDataAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.hozinking.notes.R;
import com.hozinking.notes.data.db.VaultNoteEntity;
import com.hozinking.notes.data.repo.NoteRepository;
import com.hozinking.notes.ui.dialog.IosActionSheet;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.util.Date;

/**
 * Ruang Privat: daftar catatan terenkripsi (database SQLCipher terpisah).
 * ANTI-SCREENSHOT (FLAG_SECURE). Minta auth PIN setiap kali dibuka.
 * Vault TIDAK ikut FTS/search utama.
 */
public class VaultActivity extends AppCompatActivity {

    private NoteRepository repo;
    private VaultAdapter adapter;
    private TextView emptyView;

    private final ActivityResultLauncher<Intent> authLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK) {
                            finish(); // Auth gagal/dibatalkan -> tutup.
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ANTI-SCREENSHOT: blokir screenshot & screen recording.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_vault);

        repo = NoteRepository.get(this);

        RecyclerView recycler = findViewById(R.id.recycler);
        emptyView = findViewById(R.id.empty_view);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VaultAdapter();
        recycler.setAdapter(adapter);
        adapter.addLoadStateListener(loadStates -> {
            boolean empty = adapter.getItemCount() == 0
                    && loadStates.getRefresh() instanceof androidx.paging.LoadState.NotLoading;
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            return null;
        });

        Pager<Integer, VaultNoteEntity> pager =
                new Pager<>(new PagingConfig(60), () -> repo.vaultPaging());
        PagingLiveData.getLiveData(pager).observe(this, pagingData -> adapter.submitData(getLifecycle(), pagingData));

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> promptNewNote());

        // Selalu minta auth tiap dibuka; buat PIN dulu bila belum ada.
        Intent auth = new Intent(this, VaultPinActivity.class);
        auth.putExtra(VaultPinActivity.EXTRA_MODE_SET, !VaultPinActivity.isPinSet(this));
        authLauncher.launch(auth);
    }

    private void promptNewNote() {
        new IosDialog(this)
                .setTitle(getString(R.string.vault_new_title))
                .setInput(getString(R.string.note_title_hint), title ->
                        promptContent(title, "", null))
                .setPositive(getString(R.string.ok), null)
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    private void promptContent(String title, String content, VaultNoteEntity existing) {
        IosDialog dialog = new IosDialog(this)
                .setTitle(getString(R.string.vault_new_content))
                .setNegative(getString(R.string.cancel), null);
        if (title == null || title.isEmpty()) {
            Toast.makeText(this, getString(R.string.vault_title_required), Toast.LENGTH_SHORT).show();
            return;
        }
        dialog.setInput(getString(R.string.note_content_hint), text -> {
            VaultNoteEntity entity = existing != null ? existing : new VaultNoteEntity();
            entity.title = title;
            entity.content = text;
            long now = System.currentTimeMillis();
            if (existing == null) {
                entity.createdAt = now;
            }
            entity.updatedAt = now;
            if (existing == null) {
                repo.vaultInsert(entity, id -> VaultPinActivity.onMain(() -> adapter.refresh()));
            } else {
                repo.vaultUpdate(entity, v -> VaultPinActivity.onMain(() -> adapter.refresh()));
            }
        });
        dialog.setPositive(getString(R.string.save), null);
        dialog.show();
    }

    private void showNoteMenu(VaultNoteEntity note) {
        new IosActionSheet(this)
                .setTitle(note.title)
                .addItem(getString(R.string.vault_view), () ->
                        new IosDialog(this)
                                .setTitle(note.title)
                                .setMessage(note.content)
                                .setPositive(getString(R.string.close), null)
                                .show())
                .addItem(getString(R.string.edit), () ->
                        repo.vaultGet(note.id, fresh ->
                                VaultPinActivity.onMain(() ->
                                        promptContent(fresh.title, fresh.content, fresh))))
                .addDestructive(getString(R.string.delete), () ->
                        new IosDialog(this)
                                .setTitle(getString(R.string.delete))
                                .setMessage(getString(R.string.vault_delete_confirm))
                                .setDestructive(true)
                                .setPositive(getString(R.string.delete), () ->
                                        repo.vaultDelete(note.id, v ->
                                                VaultPinActivity.onMain(() -> adapter.refresh())))
                                .setNegative(getString(R.string.cancel), null)
                                .show())
                .show();
    }

    // ---------- Adapter ----------

    private class VaultAdapter extends PagingDataAdapter<VaultNoteEntity, VaultAdapter.VH> {

        VaultAdapter() {
            super(new DiffUtil.ItemCallback<VaultNoteEntity>() {
                @Override
                public boolean areItemsTheSame(@NonNull VaultNoteEntity a,
                                               @NonNull VaultNoteEntity b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull VaultNoteEntity a,
                                                 @NonNull VaultNoteEntity b) {
                    return a.updatedAt == b.updatedAt
                            && String.valueOf(a.title).equals(String.valueOf(b.title))
                            && String.valueOf(a.content).equals(String.valueOf(b.content));
                }
            });
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_vault_note, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            VaultNoteEntity note = getItem(position);
            if (note == null) {
                return;
            }
            holder.title.setText(note.title != null ? note.title : "");
            String preview = note.content != null ? note.content : "";
            if (preview.length() > 120) {
                preview = preview.substring(0, 120) + "…";
            }
            holder.preview.setText(preview);
            holder.date.setText(DateFormat.getDateFormat(holder.itemView.getContext())
                    .format(new Date(note.updatedAt)));
            holder.itemView.setOnClickListener(v -> showNoteMenu(note));
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView preview;
            final TextView date;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.title);
                preview = itemView.findViewById(R.id.preview);
                date = itemView.findViewById(R.id.date);
            }
        }
    }

    private static void onMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
