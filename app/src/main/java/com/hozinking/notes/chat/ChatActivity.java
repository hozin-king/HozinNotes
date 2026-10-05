package com.hozinking.notes.chat;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.notes.R;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ruang chat 1 lawan 1 via Cloudflare Worker (polling tiap 2 detik).
 * Pesan disensor saat kirim dan saat render (defense in depth).
 */
public class ChatActivity extends AppCompatActivity {

    private static final int TYPE_OUT = 0;
    private static final int TYPE_IN = 1;
    private static final long POLL_MS = 2_000L;

    private String chatId;
    private String peerId;
    private String myUid;

    private final List<Message> messages = new ArrayList<>();
    private ChatAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private volatile boolean polling;
    private long lastId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        chatId = getIntent().getStringExtra("chatId");
        peerId = getIntent().getStringExtra("peerId");
        String peerName = getIntent().getStringExtra("peerName");
        if (chatId == null || !ChatIdentity.hasName(this)) {
            finish();
            return;
        }
        myUid = ChatIdentity.getUserId(this);

        setContentView(R.layout.activity_chat);
        if (getSupportActionBar() != null && peerName != null) {
            getSupportActionBar().setTitle(peerName);
        }

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatAdapter();
        recycler.setAdapter(adapter);

        EditText input = findViewById(R.id.input);
        findViewById(R.id.btn_send).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return; // Pesan kosong diabaikan.
            }
            input.setText("");
            sendMessage(text);
        });
        findViewById(R.id.btn_report).setOnClickListener(v -> report());
        findViewById(R.id.btn_block).setOnClickListener(v -> block());

        startPolling();
    }

    private void startPolling() {
        polling = true;
        pollOnce();
    }

    private void pollOnce() {
        if (!polling) {
            return;
        }
        bg.execute(() -> {
            try {
                List<WorkerChatClient.ChatMessage> fresh =
                        WorkerChatClient.messages(this, chatId, myUid, lastId);
                if (!fresh.isEmpty() && polling) {
                    runOnUiThread(() -> {
                        for (WorkerChatClient.ChatMessage m : fresh) {
                            messages.add(new Message(m.fromId, m.text, m.ts));
                            if (m.id > lastId) {
                                lastId = m.id;
                            }
                        }
                        adapter.notifyDataSetChanged();
                    });
                }
            } catch (WorkerChatClient.ChatException ignored) {
                // Poll berikutnya mencoba lagi; jangan ganggu user tiap gagal.
            }
            handler.postDelayed(this::pollOnce, POLL_MS);
        });
    }

    private void sendMessage(String raw) {
        final String censored = BadWordFilter.censor(raw); // Sensor saat kirim…
        bg.execute(() -> {
            try {
                WorkerChatClient.send(this, chatId, myUid, censored);
                // Ambil langsung agar terasa instan.
                List<WorkerChatClient.ChatMessage> fresh =
                        WorkerChatClient.messages(this, chatId, myUid, lastId);
                if (!fresh.isEmpty()) {
                    runOnUiThread(() -> {
                        for (WorkerChatClient.ChatMessage m : fresh) {
                            messages.add(new Message(m.fromId, m.text, m.ts));
                            if (m.id > lastId) {
                                lastId = m.id;
                            }
                        }
                        adapter.notifyDataSetChanged();
                    });
                }
            } catch (WorkerChatClient.ChatException e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void report() {
        bg.execute(() -> WorkerChatClient.report(this, chatId, myUid));
        Toast.makeText(this, getString(R.string.chat_reported),
                Toast.LENGTH_SHORT).show();
    }

    private void block() {
        final String pid = peerId;
        final String uid = myUid;
        bg.execute(() -> {
            if (pid != null && !pid.isEmpty()) {
                WorkerChatClient.block(this, uid, pid);
            }
        });
        Toast.makeText(this, getString(R.string.chat_blocked),
                Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
        bg.shutdownNow();
        super.onDestroy();
    }

    // ---------- Model & adapter ----------

    private static class Message {
        final String from;
        final String text;
        final long ts;

        Message(String from, String text, long ts) {
            this.from = from;
            this.text = text;
            this.ts = ts;
        }
    }

    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

        @Override
        public int getItemViewType(int position) {
            return messages.get(position).from.equals(myUid) ? TYPE_OUT : TYPE_IN;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_OUT ? R.layout.item_chat_out : R.layout.item_chat_in;
            View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Message m = messages.get(position);
            // Defense in depth: sensor lagi saat render.
            holder.msg.setText(BadWordFilter.censor(m.text));
            if (m.ts > 0) {
                holder.time.setText(DateFormat.getTimeFormat(holder.itemView.getContext())
                        .format(new Date(m.ts)));
            } else {
                holder.time.setText("");
            }
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView msg;
            final TextView time;

            VH(@NonNull View itemView) {
                super(itemView);
                msg = itemView.findViewById(R.id.msg);
                time = itemView.findViewById(R.id.time);
            }
        }
    }
}
