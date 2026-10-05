package com.hozinking.notes.chat;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.notes.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Masuk chat: cukup isi nama tampilan (tanpa password).
 * Backend: Cloudflare Worker (lihat ChatConfig).
 */
public class LoginActivity extends AppCompatActivity {

    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (ChatIdentity.hasName(this)) {
            goToMatch();
            return;
        }

        setContentView(R.layout.activity_login);

        EditText etName = findViewById(R.id.et_name);
        etName.setText(ChatIdentity.getName(this));
        Button btnStart = findViewById(R.id.btn_start);
        btnStart.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.chat_name_empty,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            btnStart.setEnabled(false);
            String uid = ChatIdentity.getUserId(this);
            bg.execute(() -> {
                try {
                    WorkerChatClient.identify(this, uid, name);
                    ChatIdentity.setName(this, name);
                    runOnUiThread(this::goToMatch);
                } catch (WorkerChatClient.ChatException e) {
                    runOnUiThread(() -> {
                        btnStart.setEnabled(true);
                        Toast.makeText(this, e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
    }

    private void goToMatch() {
        startActivity(new Intent(this, RandomMatchActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }
}
