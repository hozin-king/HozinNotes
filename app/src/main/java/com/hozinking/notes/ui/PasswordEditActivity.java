package com.hozinking.notes.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.PasswordEntry;
import com.hozinking.notes.data.repo.PasswordRepository;
import com.hozinking.notes.security.PasswordCrypto;
import com.hozinking.notes.ui.dialog.IosDialog;

/**
 * Tambah / edit satu entri brankas kata sandi.
 * Anti-screenshot (FLAG_SECURE). Secret tidak pernah di-log.
 */
public class PasswordEditActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "entry_id";

    private static final String[] CATEGORIES = {"Login", "API Key", "Bank", "WiFi", "Lainnya"};

    private PasswordRepository repo;
    private long entryId;
    private PasswordEntry editing;

    private EditText etTitle, etUsername, etUrl, etSecret, etNotes;
    private Spinner spCategory;
    private TextView tvStrength;

    public static void open(Context ctx, long id) {
        Intent i = new Intent(ctx, PasswordEditActivity.class);
        i.putExtra(EXTRA_ID, id);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_password_edit);

        repo = PasswordRepository.get(this);
        entryId = getIntent().getLongExtra(EXTRA_ID, 0);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());

        etTitle = findViewById(R.id.et_title);
        etUsername = findViewById(R.id.et_username);
        etUrl = findViewById(R.id.et_url);
        etSecret = findViewById(R.id.et_secret);
        etNotes = findViewById(R.id.et_notes);
        spCategory = findViewById(R.id.sp_category);
        tvStrength = findViewById(R.id.tv_strength);

        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, CATEGORIES);
        spCategory.setAdapter(catAdapter);

        etSecret.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                updateStrength(s.toString());
            }
        });

        findViewById(R.id.btn_generate).setOnClickListener(v -> {
            char[] gen = PasswordCrypto.generatePassword(16, true);
            try {
                etSecret.setText(new String(gen));
                updateStrength(new String(gen));
                Toast.makeText(this, R.string.pw_generated, Toast.LENGTH_SHORT).show();
            } finally {
                PasswordCrypto.wipe(gen);
            }
        });

        Button btnDelete = findViewById(R.id.btn_delete);
        if (entryId > 0) {
            btnDelete.setVisibility(android.view.View.VISIBLE);
            btnDelete.setOnClickListener(v -> confirmDelete());
            loadEntry();
        }

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
    }

    private void updateStrength(String s) {
        char[] c = s.toCharArray();
        try {
            tvStrength.setText(getString(R.string.pw_strength, PasswordCrypto.strengthLabel(c)));
        } finally {
            PasswordCrypto.wipe(c);
        }
    }

    private void loadEntry() {
        repo.getEntry(entryId, entry -> {
            if (entry == null) {
                finish();
                return;
            }
            editing = entry;
            etTitle.setText(entry.title);
            etUsername.setText(entry.username);
            etUrl.setText(entry.url);
            for (int i = 0; i < CATEGORIES.length; i++) {
                if (CATEGORIES[i].equals(entry.category)) {
                    spCategory.setSelection(i);
                    break;
                }
            }
            // Dekripsi via repository (async, aman).
            repo.revealSecret(entry.id, secret -> {
                try {
                    etSecret.setText(new String(secret));
                    updateStrength(new String(secret));
                } finally {
                    PasswordCrypto.wipe(secret);
                }
            });
            repo.revealNotes(entry.id, notes -> {
                try {
                    if (notes.length > 0) {
                        etNotes.setText(new String(notes));
                    }
                } finally {
                    PasswordCrypto.wipe(notes);
                }
            });
        });
    }

    private void save() {
        String title = etTitle.getText().toString().trim();
        String secretStr = etSecret.getText().toString();
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.pw_title_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (secretStr.isEmpty()) {
            Toast.makeText(this, R.string.pw_secret_required, Toast.LENGTH_SHORT).show();
            return;
        }
        PasswordEntry e = editing != null ? editing : new PasswordEntry();
        e.title = title;
        e.username = etUsername.getText().toString().trim();
        e.url = etUrl.getText().toString().trim();
        e.category = (String) spCategory.getSelectedItem();
        char[] secret = secretStr.toCharArray();
        char[] notes = etNotes.getText().toString().toCharArray();
        // repo.save me-wipe kedua char[] setelah dipakai.
        repo.save(e, secret, notes, id -> {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void confirmDelete() {
        new IosDialog(this)
                .setTitle(getString(R.string.pw_delete_title))
                .setMessage(getString(R.string.pw_delete_message))
                .setPositive(getString(R.string.delete), () ->
                        repo.delete(entryId, v -> {
                            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
                            finish();
                        }))
                .setNegative(getString(R.string.cancel), null)
                .show();
    }
}
