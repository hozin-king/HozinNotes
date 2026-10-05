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

    /** Ganti dengan URL worker hasil `wrangler deploy`. */
    public static final String DEFAULT_BASE_URL =
            "https://hozin-chat.muchammadhozin.workers.dev";

    public static String baseUrl(android.content.Context ctx) {
        String custom = ctx.getSharedPreferences("chat", android.content.Context.MODE_PRIVATE)
                .getString("chat_worker_url", null);
        if (custom != null && !custom.trim().isEmpty()) {
            return custom.trim().replaceAll("/+$", "");
        }
        return DEFAULT_BASE_URL;
    }
}
