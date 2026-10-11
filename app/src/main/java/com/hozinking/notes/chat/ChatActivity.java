package com.hozinking.notes.chat;

import android.graphics.drawable.GradientDrawable;
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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ruang chat via Cloudflare Worker (polling tiap 2 detik).
 *
 * <p>Dua mode:
 * <ul>
 *   <li>Ruang global ("global"): satu ruang untuk semua user, tanpa matchmaking.
 *       Pesan masuk menampilkan nama + avatar inisial pengirim, ada pemisah hari,
 *       dan jumlah user online di subtitle.</li>
 *   <li>1 lawan 1 (legacy): perilaku lama untuk room matchmaking.</li>
 * </ul>
 * Pesan disensor saat kirim dan saat render (defense in depth).
 */
public class ChatActivity extends AppCompatActivity {

    /** Room publik untuk semua user. */
    public static final String GLOBAL_ROOM = "global";

    private static final int TYPE_OUT = 0;
    private static final int TYPE_IN = 1;
    private static final int TYPE_DAY = 2;
    private static final long POLL_MS = 2_000L;
    private static final long PRESENCE_MS = 30_000L;

    /** Palet warna avatar (material-ish, cukup kontras di light/dark). */
    private static final int[] AVATAR_COLORS = {
            0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF9575D6,
            0xFF7986CB, 0xFF64B5F6, 0xFF4FC3F7, 0xFF4DD0E1,
            0xFF4DB6AC, 0xFF81C784, 0xFFAED581, 0xFFDCE775,
            0xFFFFD54F, 0xFFFFB74D, 0xFFFF8A65, 0xFFA1887F,
    };

    private String chatId;
    private String peerId;
    private String myUid;
    private boolean isGlobal;

    /** Baris tampilan: Message atau Long (awal hari, millis) sebagai pemisah hari. */
    private final List<Object> rows = new ArrayList<>();
    private long lastDayKey = -1;
    private ChatAdapter adapter;
    private RecyclerView recycler;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private volatile boolean polling;
    private volatile boolean presenceActive;
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
        isGlobal = GLOBAL_ROOM.equals(chatId);

        setContentView(R.layout.activity_chat);
        if (getSupportActionBar() != null) {
            if (isGlobal) {
                getSupportActionBar().setTitle(getString(R.string.chat_room_title));
            } else if (peerName != null) {
                getSupportActionBar().setTitle(peerName);
            }
        }

        recycler = findViewById(R.id.recycler);
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
        View btnBlock = findViewById(R.id.btn_block);
        if (isGlobal) {
            // Di ruang publik tidak ada konsep blokir 1-on-1.
            btnBlock.setVisibility(View.GONE);
        } else {
            btnBlock.setOnClickListener(v -> block());
        }

        startPolling();
        if (isGlobal) {
            startPresence();
        }
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
                    runOnUiThread(() -> appendMessages(fresh));
                }
            } catch (WorkerChatClient.ChatException ignored) {
                // Poll berikutnya mencoba lagi; jangan ganggu user tiap gagal.
            }
            handler.postDelayed(this::pollOnce, POLL_MS);
        });
    }

    /** Presence ruang global: ping + hitung online tiap 30 detik. */
    private void startPresence() {
        presenceActive = true;
        updatePresence();
    }

    private void updatePresence() {
        if (!presenceActive) {
            return;
        }
        bg.execute(() -> {
            WorkerChatClient.ping(this, myUid);
            final int online = WorkerChatClient.onlineCount(this);
            if (presenceActive && online >= 0) {
                runOnUiThread(() -> {
                    if (getSupportActionBar() != null) {
                        getSupportActionBar().setSubtitle(
                                getString(R.string.chat_online, online));
                    }
                });
            }
            handler.postDelayed(this::updatePresence, PRESENCE_MS);
        });
    }

    private void appendMessages(List<WorkerChatClient.ChatMessage> fresh) {
        boolean wasAtBottom = !recycler.canScrollVertically(1);
        int start = rows.size();
        for (WorkerChatClient.ChatMessage m : fresh) {
            long dayKey = dayKey(m.ts);
            if (dayKey != lastDayKey) {
                rows.add(dayKey);
                lastDayKey = dayKey;
            }
            rows.add(new Message(m.fromId, m.fromName, m.text, m.ts));
            if (m.id > lastId) {
                lastId = m.id;
            }
        }
        adapter.notifyItemRangeInserted(start, rows.size() - start);
        if (wasAtBottom || start == 0) {
            recycler.scrollToPosition(rows.size() - 1);
        }
    }

    private static long dayKey(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts > 0 ? ts : System.currentTimeMillis());
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void sendMessage(String raw) {
        final String censored = BadWordFilter.censor(raw); // Sensor saat kirim…
        final boolean wasAtBottom = !recycler.canScrollVertically(1);
        bg.execute(() -> {
            try {
                WorkerChatClient.send(this, chatId, myUid, censored);
                // Ambil langsung agar terasa instan.
                List<WorkerChatClient.ChatMessage> fresh =
                        WorkerChatClient.messages(this, chatId, myUid, lastId);
                if (!fresh.isEmpty()) {
                    runOnUiThread(() -> {
                        appendMessages(fresh);
                        if (wasAtBottom) {
                            recycler.scrollToPosition(rows.size() - 1);
                        }
                    });
                }
            } catch (WorkerChatClient.ChatException e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("429")) {
                    // Slow mode ruang global: kirim terlalu cepat.
                    msg = getString(R.string.chat_slow);
                }
                final String toastMsg = msg;
                runOnUiThread(() -> Toast.makeText(this, toastMsg,
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
        presenceActive = false;
        handler.removeCallbacksAndMessages(null);
        bg.shutdownNow();
        super.onDestroy();
    }

    // ---------- Model & adapter ----------

    private static class Message {
        final String from;
        final String fromName;
        final String text;
        final long ts;

        Message(String from, String fromName, String text, long ts) {
            this.from = from;
            this.fromName = fromName;
            this.text = text;
            this.ts = ts;
        }
    }

    private class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

        @Override
        public int getItemViewType(int position) {
            Object row = rows.get(position);
            if (row instanceof Long) {
                return TYPE_DAY;
            }
            Message m = (Message) row;
            return m.from.equals(myUid) ? TYPE_OUT : TYPE_IN;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_OUT ? R.layout.item_chat_out
                    : viewType == TYPE_IN ? R.layout.item_chat_in
                    : R.layout.item_chat_day;
            View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new VH(v, viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Object row = rows.get(position);
            if (row instanceof Long) {
                holder.day.setText(dayLabel((Long) row));
                return;
            }
            Message m = (Message) row;
            // Defense in depth: sensor lagi saat render.
            holder.msg.setText(BadWordFilter.censor(m.text));
            if (m.ts > 0) {
                holder.time.setText(DateFormat.getTimeFormat(holder.itemView.getContext())
                        .format(new Date(m.ts)));
            } else {
                holder.time.setText("");
            }
            if (holder.name != null) {
                String name = m.fromName != null && !m.fromName.trim().isEmpty()
                        ? m.fromName.trim() : getString(R.string.chat_anonymous);
                holder.name.setText(name);
            }
            if (holder.avatar != null) {
                String initial = "?";
                String src = m.fromName != null && !m.fromName.trim().isEmpty()
                        ? m.fromName.trim() : m.from;
                if (src != null && !src.isEmpty()) {
                    initial = src.substring(0, 1).toUpperCase();
                }
                holder.avatar.setText(initial);
                GradientDrawable bg =
                        (GradientDrawable) holder.avatar.getBackground().mutate();
                int idx = Math.abs((m.from != null ? m.from : "?").hashCode())
                        % AVATAR_COLORS.length;
                bg.setColor(AVATAR_COLORS[idx]);
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView msg;
            final TextView time;
            final TextView name;
            final TextView avatar;
            final TextView day;

            VH(@NonNull View itemView, int viewType) {
                super(itemView);
                if (viewType == TYPE_DAY) {
                    msg = null;
                    time = null;
                    name = null;
                    avatar = null;
                    day = itemView.findViewById(R.id.day);
                } else {
                    msg = itemView.findViewById(R.id.msg);
                    time = itemView.findViewById(R.id.time);
                    name = itemView.findViewById(R.id.name);
                    avatar = itemView.findViewById(R.id.avatar);
                    day = null;
                }
            }
        }
    }

    /** Label pemisah hari: "Hari ini", "Kemarin", atau tanggal. */
    private String dayLabel(long dayStartMillis) {
        long today = dayKey(System.currentTimeMillis());
        if (dayStartMillis == today) {
            return getString(R.string.chat_today);
        }
        if (dayStartMillis == today - 86_400_000L) {
            return getString(R.string.chat_yesterday);
        }
        return DateFormat.getDateFormat(this).format(new Date(dayStartMillis));
    }
}
