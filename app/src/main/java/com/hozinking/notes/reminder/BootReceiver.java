package com.hozinking.notes.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Jadwal ulang semua pengingat aktif setelah HP restart. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ReminderScheduler.rescheduleAll(context);
        }
    }
}
