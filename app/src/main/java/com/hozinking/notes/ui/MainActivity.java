package com.hozinking.notes.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.hozinking.notes.R;
import com.hozinking.notes.chat.ChatActivity;
import com.hozinking.notes.chat.ChatIdentity;
import com.hozinking.notes.chat.LoginActivity;
import com.hozinking.notes.ui.mindmap.MindmapActivity;

/**
 * Host utama: 5 tab bawah. Catatan & Folder = fragment, sisanya = activity.
 * Tab terakhir disimpan di SharedPreferences.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "hozin_notes_prefs";
    private static final String KEY_TAB = "last_tab";

    private boolean unlockedForSession = false;
    private final ActivityResultLauncher<Intent> lockLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK) {
                            unlockedForSession = true;
                        } else {
                            finish(); // kunci app aktif dan auth gagal -> tutup
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_notes) {
                showTab(new NoteListFragment(), id);
                return true;
            }
            if (id == R.id.nav_folders) {
                showTab(new FolderFragment(), id);
                return true;
            }
            if (id == R.id.nav_mindmap) {
                startActivity(new Intent(this, MindmapActivity.class));
                return false;
            }
            if (id == R.id.nav_chat) {
                openChat();
                return false;
            }
            if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return false;
            }
            return false;
        });

        int saved = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_TAB, R.id.nav_notes);
        if (saved == R.id.nav_notes || saved == R.id.nav_folders) {
            nav.setSelectedItemId(saved);
        } else {
            nav.setSelectedItemId(R.id.nav_notes);
        }

        // Cek pembaruan otomatis (max 1x/24 jam, diam bila tidak ada/gagal).
        com.hozinking.notes.update.UpdateChecker.maybeAutoCheck(this);
        // v1.17: tawarkan pelaporan bila aplikasi crash di sesi sebelumnya.
        com.hozinking.notes.util.CrashReporter.checkAndOffer(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Kunci aplikasi: minta PIN/biometrik sekali per sesi bila diaktifkan.
        if (!unlockedForSession && SettingsActivity.isAppLockEnabled(this)) {
            Intent i = new Intent(this, VaultPinActivity.class);
            i.putExtra(VaultPinActivity.EXTRA_MODE_SET, false);
            lockLauncher.launch(i);
        }
    }

    private void showTab(Fragment f, int tabId) {        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, f)
                .commit();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_TAB, tabId).apply();
    }

    private void openChat() {
        if (!ChatIdentity.hasName(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            return;
        }
        // Satu ruang ngobrol global untuk semua user (tanpa matchmaking).
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("chatId", ChatActivity.GLOBAL_ROOM);
        startActivity(i);
    }
}
