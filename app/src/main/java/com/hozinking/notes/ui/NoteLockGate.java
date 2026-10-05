package com.hozinking.notes.ui;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.ui.dialog.IosDialog;

/**
 * Gerbang buka catatan terkunci: minta PIN dulu bila note.locked.
 * Dipakai NoteListFragment, SearchActivity, ArchiveActivity.
 */
public class NoteLockGate {

    public interface Opener {
        void open(long noteId);
    }

    public static void openMaybeLocked(final Context ctx, final NoteEntity note,
                                       final Opener opener) {
        if (note == null) return;
        if (!note.locked) {
            opener.open(note.id);
            return;
        }
        if (!VaultPinActivity.isPinSet(ctx)) {
            Toast.makeText(ctx, R.string.lock_need_pin, Toast.LENGTH_LONG).show();
            return;
        }
        new IosDialog(ctx)
                .setTitle(ctx.getString(R.string.lock_pin_title))
                .setInput(ctx.getString(R.string.lock_pin_hint), pin -> {
                    if (VaultPinActivity.checkPin(ctx, pin)) {
                        opener.open(note.id);
                    } else {
                        Toast.makeText(ctx, R.string.lock_pin_wrong,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setInputNumeric(true)
                .setPositive(ctx.getString(R.string.lock_open), null)
                .setNegative(ctx.getString(R.string.cancel), null)
                .show();
    }

    /** Buka VaultPinActivity mode buat PIN (dipakai saat mengunci tapi PIN belum ada). */
    public static void requestPinSetup(android.app.Activity activity, int requestCode) {
        Intent i = new Intent(activity, VaultPinActivity.class);
        i.putExtra(VaultPinActivity.EXTRA_MODE_SET, true);
        activity.startActivityForResult(i, requestCode);
    }
}
