package com.hozinking.notes.chat;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.notes.R;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Matchmaking teman acak via Cloudflare Worker.
 *
 * <p>Alur: POST /api/match -> bila langsung dapat pasangan, buka chat.
 * Bila menunggu, poll GET /api/match?userId= tiap 2 detik. Timeout 60 detik.
 */
public class RandomMatchActivity extends AppCompatActivity {

    private static final long TIMEOUT_MS = 60_000L;
    private static final long POLL_MS = 2_000L;

    private TextView statusView;
    private ProgressBar progressView;
    private Button findButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private String myUid;
    private volatile boolean active;
    private Runnable timeoutTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!ChatIdentity.hasName(this)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        myUid = ChatIdentity.getUserId(this);

        setContentView(R.layout.activity_random_match);
        statusView = findViewById(R.id.status);
        progressView = findViewById(R.id.progress);
        findButton = findViewById(R.id.btn_find);

        findButton.setOnClickListener(v -> startMatching());
    }

    private void startMatching() {
        active = true;
        findButton.setEnabled(false);
        progressView.setVisibility(View.VISIBLE);
        statusView.setText(getString(R.string.chat_searching));

        timeoutTask = () -> stopMatching(getString(R.string.chat_match_timeout));
        handler.postDelayed(timeoutTask, TIMEOUT_MS);

        bg.execute(() -> {
            try {
                // Percobaan pertama: mungkin langsung dapat pasangan.
                WorkerChatClient.MatchResult r =
                        WorkerChatClient.match(this, myUid);
                if (!active) {
                    return;
                }
                if (!r.waiting) {
                    onMatched(r);
                    return;
                }
                // Poll sampai timeout.
                while (active) {
                    Thread.sleep(POLL_MS);
                    if (!active) {
                        return;
                    }
                    WorkerChatClient.MatchResult s =
                            WorkerChatClient.matchStatus(this, myUid);
                    if (!s.waiting) {
                        onMatched(s);
                        return;
                    }
                }
            } catch (WorkerChatClient.ChatException e) {
                if (active) {
                    runOnUiThread(() ->
                            stopMatching(getString(R.string.chat_match_fail)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void onMatched(WorkerChatClient.MatchResult r) {
        active = false;
        runOnUiThread(() -> {
            if (timeoutTask != null) {
                handler.removeCallbacks(timeoutTask);
                timeoutTask = null;
            }
            statusView.setText(getString(R.string.chat_match_found));
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("chatId", r.roomId);
            i.putExtra("peerId", r.peerId);
            i.putExtra("peerName", r.peerName);
            startActivity(i);
            finish();
        });
    }

    private void stopMatching(String message) {
        active = false;
        if (timeoutTask != null) {
            handler.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
        bg.execute(() -> WorkerChatClient.leave(this, myUid));
        progressView.setVisibility(View.GONE);
        findButton.setEnabled(true);
        statusView.setText(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        active = false;
        if (timeoutTask != null) {
            handler.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
        final String uid = myUid;
        if (uid != null) {
            // Best-effort: keluar dari antrean.
            new Thread(() -> WorkerChatClient.leave(this, uid)).start();
        }
        bg.shutdownNow();
        super.onDestroy();
    }
}
