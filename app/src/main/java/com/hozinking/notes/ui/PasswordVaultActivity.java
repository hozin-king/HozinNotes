package com.hozinking.notes.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.PasswordEntry;
import com.hozinking.notes.data.repo.PasswordRepository;
import com.hozinking.notes.security.PasswordCrypto;
import com.hozinking.notes.ui.dialog.IosDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Brankas kata sandi / API key.
 *
 * <p>Keamanan:
 * <ul>
 *   <li>Auth PIN/biometrik (VaultPinActivity) tiap dibuka & tiap kembali setelah &gt;60 detik.</li>
 *   <li>FLAG_SECURE: anti-screenshot & anti-screen-record.</li>
 *   <li>Secret didekripsi hanya saat dibutuhkan, di-wipe setelah dipakai.</li>
 *   <li>Salin ke clipboard otomatis dibersihkan setelah 30 detik.</li>
 * </ul>
 */
public class PasswordVaultActivity extends AppCompatActivity {

    private static final long AUTOLOCK_MS = 60_000;
    private static final long CLIPBOARD_CLEAR_MS = 30_000;

    private PasswordRepository repo;
    private PwAdapter adapter;
    private TextView emptyView;
    private LiveData<List<PasswordEntry>> currentLive;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long pausedAt;

    private final ActivityResultLauncher<Intent> authLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK) {
                            finish();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_password_vault);

        repo = PasswordRepository.get(this);

        Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        RecyclerView recycler = findViewById(R.id.recycler);
        emptyView = findViewById(R.id.empty_view);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PwAdapter();
        recycler.setAdapter(adapter);

        EditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                observe(s.toString().trim());
            }
        });

        findViewById(R.id.fab).setOnClickListener(v ->
                PasswordEditActivity.open(this, 0));

        observe("");
        requestAuth();
    }

    private void requestAuth() {
        Intent auth = new Intent(this, VaultPinActivity.class);
        auth.putExtra(VaultPinActivity.EXTRA_MODE_SET, !VaultPinActivity.isPinSet(this));
        authLauncher.launch(auth);
    }

    private void observe(String q) {
        if (currentLive != null) {
            currentLive.removeObservers(this);
        }
        currentLive = q.isEmpty() ? repo.observeAll() : repo.search(q);
        currentLive.observe(this, list -> {
            adapter.setItems(list != null ? list : new ArrayList<>());
            emptyView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
        // Bersihkan clipboard bila masih berisi secret kita saat ditinggalkan.
        clearClipboardIfOurs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > AUTOLOCK_MS) {
            requestAuth();
        }
        pausedAt = 0;
    }

    /** Salin secret ke clipboard; otomatis dibersihkan setelah 30 detik. */
    private void copySecret(PasswordEntry e) {
        repo.revealSecret(e.id, secret -> {
            try {
                if (secret.length == 0) {
                    Toast.makeText(this, R.string.pw_empty_secret, Toast.LENGTH_SHORT).show();
                    return;
                }
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("hozин", new String(secret));
                cm.setPrimaryClip(clip);
                Toast.makeText(this, R.string.pw_copied, Toast.LENGTH_SHORT).show();
                // Tandai agar bisa dibersihkan nanti.
                getPreferences(MODE_PRIVATE).edit()
                        .putLong("clip_time", System.currentTimeMillis()).apply();
                handler.postDelayed(this::clearClipboardIfOurs, CLIPBOARD_CLEAR_MS);
            } finally {
                PasswordCrypto.wipe(secret);
            }
        });
    }

    private void clearClipboardIfOurs() {
        long t = getPreferences(MODE_PRIVATE).getLong("clip_time", 0);
        if (t == 0 || System.currentTimeMillis() - t < CLIPBOARD_CLEAR_MS) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (Exception ignored) {
        }
        getPreferences(MODE_PRIVATE).edit().remove("clip_time").apply();
    }

    /** Tampilkan secret dalam dialog sekali lihat (di-wipe saat dialog ditutup). */
    private void revealSecret(PasswordEntry e) {
        repo.revealSecret(e.id, secret -> {
            try {
                if (secret.length == 0) {
                    Toast.makeText(this, R.string.pw_empty_secret, Toast.LENGTH_SHORT).show();
                    return;
                }
                String shown = new String(secret);
                IosDialog dialog = new IosDialog(this)
                        .setTitle(e.title)
                        .setMessage(shown)
                        .setPositive(getString(R.string.pw_copy), () -> copySecret(e))
                        .setNegative(getString(R.string.close), null);
                dialog.setOnDismissListener(d -> PasswordCrypto.wipe(shown.toCharArray()));
                dialog.show();
            } finally {
                PasswordCrypto.wipe(secret);
            }
        });
    }

    private void confirmDelete(PasswordEntry e) {
        new IosDialog(this)
                .setTitle(getString(R.string.pw_delete_title))
                .setMessage(getString(R.string.pw_delete_message))
                .setPositive(getString(R.string.delete), () ->
                        repo.delete(e.id, v ->
                                Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()))
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    private class PwAdapter extends RecyclerView.Adapter<PwAdapter.VH> {
        private final List<PasswordEntry> items = new ArrayList<>();

        void setItems(List<PasswordEntry> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_password, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            PasswordEntry e = items.get(position);
            h.title.setText(e.title);
            String sub = e.username.isEmpty() ? e.category : e.username + " · " + e.category;
            h.subtitle.setText(sub);
            h.btnFav.setAlpha(e.favorite ? 1f : 0.35f);
            h.btnFav.setOnClickListener(v ->
                    repo.setFavorite(e.id, !e.favorite, x -> {}));
            h.btnCopy.setOnClickListener(v -> copySecret(e));
            h.btnReveal.setOnClickListener(v -> revealSecret(e));
            h.itemView.setOnClickListener(v -> PasswordEditActivity.open(
                    PasswordVaultActivity.this, e.id));
            h.itemView.setOnLongClickListener(v -> {
                confirmDelete(e);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView title, subtitle;
            final ImageButton btnFav, btnCopy, btnReveal;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.title);
                subtitle = itemView.findViewById(R.id.subtitle);
                btnFav = itemView.findViewById(R.id.btn_fav);
                btnCopy = itemView.findViewById(R.id.btn_copy);
                btnReveal = itemView.findViewById(R.id.btn_reveal);
            }
        }
    }
}
