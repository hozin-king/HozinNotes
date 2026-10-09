package com.hozinking.notes.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Menangani tombol "Lepas" pada notifikasi catatan yang disematkan. */
public class PinnedNoteReceiver extends BroadcastReceiver {
    public static final String ACTION_UNPIN = "com.hozinking.notes.ACTION_UNPIN_NOTE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_UNPIN.equals(intent.getAction())) {
            PinnedNoteManager.unpin(context);
        }
    }
}
