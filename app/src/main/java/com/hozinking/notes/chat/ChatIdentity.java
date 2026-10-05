package com.hozinking.notes.chat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * Identitas chat: userId acak (UUID, dibuat sekali) + nama tampilan.
 * Tanpa password — cocok untuk chat acak anonim ala Omegle.
 */
public final class ChatIdentity {

    private static final String PREFS = "chat";
    private static final String KEY_UID = "uid";
    private static final String KEY_NAME = "name";

    private ChatIdentity() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** UUID stabil per perangkat; dibuat sekali saat pertama dipakai. */
    public static String getUserId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String uid = p.getString(KEY_UID, null);
        if (uid == null || uid.isEmpty()) {
            uid = UUID.randomUUID().toString();
            p.edit().putString(KEY_UID, uid).apply();
        }
        return uid;
    }

    public static String getName(Context ctx) {
        return prefs(ctx).getString(KEY_NAME, "");
    }

    public static void setName(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_NAME,
                name == null ? "" : name.trim()).apply();
    }

    public static boolean hasName(Context ctx) {
        String n = getName(ctx);
        return n != null && !n.trim().isEmpty();
    }
}
