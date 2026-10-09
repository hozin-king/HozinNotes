package com.hozinking.notes.chat;

/**
 * Konfigurasi backend chat (Cloudflare Worker + D1).
 *
 * <p>URL default menunjuk ke worker milik sendiri. Bila worker di-deploy
 * ulang dengan nama/domain berbeda, cukup ubah DEFAULT_BASE_URL di sini
 * (atau timpa via prefs "chat_worker_url").
 */
public final class ChatConfig {

    private ChatConfig() {
    }

    /**
     * URL dasar backend chat. Sejak 2026-10-09 backend chat acak digabung ke
     * worker hozinwate-web di hozinking.my.id (satu server).
     * Path API: {baseUrl}/api/identify, /api/match, /api/send, dst.
     */
    public static final String DEFAULT_BASE_URL =
            "https://hozinking.my.id/chat";

    public static String baseUrl(android.content.Context ctx) {
        String custom = ctx.getSharedPreferences("chat", android.content.Context.MODE_PRIVATE)
                .getString("chat_worker_url", null);
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim().replaceAll("/+$", "");
        }
        return DEFAULT_BASE_URL;
    }
}
