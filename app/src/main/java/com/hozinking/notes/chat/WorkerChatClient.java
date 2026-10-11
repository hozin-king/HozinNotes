package com.hozinking.notes.chat;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client HTTP untuk backend chat Cloudflare Worker.
 * Semua method BLOCKING — panggil dari background thread.
 */
public final class WorkerChatClient {

    private static final int TIMEOUT_MS = 15_000;

    private WorkerChatClient() {
    }

    // ---------- Model ----------

    public static class MatchResult {
        public final boolean waiting;
        public final String roomId;
        public final String peerName;
        public final String peerId;

        private MatchResult(boolean waiting, String roomId,
                            String peerName, String peerId) {
            this.waiting = waiting;
            this.roomId = roomId;
            this.peerName = peerName;
            this.peerId = peerId;
        }

        public static MatchResult waiting() {
            return new MatchResult(true, null, null, null);
        }

        public static MatchResult matched(String roomId, String peerName,
                                          String peerId) {
            return new MatchResult(false, roomId, peerName, peerId);
        }
    }

    public static class ChatMessage {
        public final long id;
        public final String fromId;
        public final String fromName;
        public final String text;
        public final long ts;

        public ChatMessage(long id, String fromId, String fromName,
                           String text, long ts) {
            this.id = id;
            this.fromId = fromId;
            this.fromName = fromName;
            this.text = text;
            this.ts = ts;
        }
    }

    public static class ChatException extends Exception {
        public ChatException(String msg) {
            super(msg);
        }
    }

    // ---------- HTTP ----------

    private static JSONObject request(Context ctx, String method, String path,
                                      JSONObject body) throws ChatException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ChatConfig.baseUrl(ctx) + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                }
            }
            if (code < 200 || code >= 300) {
                throw new ChatException("HTTP " + code);
            }
            return new JSONObject(sb.toString());
        } catch (ChatException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatException("Jaringan bermasalah");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------- API ----------

    public static void identify(Context ctx, String userId, String name)
            throws ChatException {
        try {
            JSONObject body = new JSONObject();
            body.put("userId", userId);
            body.put("name", name);
            request(ctx, "POST", "/api/identify", body);
        } catch (ChatException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatException("Jaringan bermasalah");
        }
    }

    /** Tandai user sedang online (ruang global). Best-effort: gagal diabaikan. */
    public static void ping(Context ctx, String userId) {
        try {
            JSONObject body = new JSONObject();
            body.put("userId", userId);
            request(ctx, "POST", "/api/ping", body);
        } catch (Exception ignored) {
        }
    }

    /** Jumlah user yang online dalam 2 menit terakhir. -1 bila gagal. */
    public static int onlineCount(Context ctx) {
        try {
            JSONObject res = request(ctx, "GET", "/api/online", null);
            return res.optInt("online", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    public static MatchResult match(Context ctx, String userId) throws ChatException {
        try {
            JSONObject body = new JSONObject();
            body.put("userId", userId);
            JSONObject res = request(ctx, "POST", "/api/match", body);
            if (res.optBoolean("waiting", false)) {
                return MatchResult.waiting();
            }
            return MatchResult.matched(res.getString("roomId"),
                    res.optString("peerName", "Orang asing"),
                    res.optString("peerId", ""));
        } catch (ChatException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatException("Jaringan bermasalah");
        }
    }

    public static MatchResult matchStatus(Context ctx, String userId)
            throws ChatException {
        JSONObject res = request(ctx, "GET", "/api/match?userId=" + enc(userId), null);
        if (res.optBoolean("waiting", false)) {
            return MatchResult.waiting();
        }
        try {
            return MatchResult.matched(res.getString("roomId"),
                    res.optString("peerName", "Orang asing"),
                    res.optString("peerId", ""));
        } catch (Exception e) {
            throw new ChatException("Jaringan bermasalah");
        }
    }

    public static void leave(Context ctx, String userId) {
        try {
            JSONObject body = new JSONObject();
            body.put("userId", userId);
            request(ctx, "POST", "/api/leave", body);
        } catch (Exception ignored) {
        }
    }

    public static List<ChatMessage> messages(Context ctx, String roomId,
                                             String userId, long afterId)
            throws ChatException {
        JSONObject res = request(ctx, "GET",
                "/api/messages?room=" + enc(roomId)
                        + "&after=" + afterId
                        + "&userId=" + enc(userId),
                null);
        List<ChatMessage> out = new ArrayList<>();
        try {
            JSONArray arr = res.optJSONArray("messages");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    out.add(new ChatMessage(
                            m.optLong("id", 0),
                            m.optString("fromId", ""),
                            m.optString("fromName", ""),
                            m.optString("text", ""),
                            m.optLong("ts", 0)));
                }
            }
            return out;
        } catch (Exception e) {
            throw new ChatException("Jaringan bermasalah");
        }
    }

    public static void send(Context ctx, String roomId, String fromId, String text)
            throws ChatException {
        try {
            JSONObject body = new JSONObject();
            body.put("room", roomId);
            body.put("from", fromId);
            body.put("text", text);
            request(ctx, "POST", "/api/send", body);
        } catch (ChatException e) {
            throw e;
        } catch (Exception e) {
            throw new ChatException("Jaringan bermasalah");
        }
    }

    public static void report(Context ctx, String roomId, String reporterId) {
        try {
            JSONObject body = new JSONObject();
            body.put("room", roomId);
            body.put("reporter", reporterId);
            request(ctx, "POST", "/api/report", body);
        } catch (Exception ignored) {
        }
    }

    public static void block(Context ctx, String userId, String peerId) {
        try {
            JSONObject body = new JSONObject();
            body.put("userId", userId);
            body.put("peerId", peerId);
            request(ctx, "POST", "/api/block", body);
        } catch (Exception ignored) {
        }
    }
}
