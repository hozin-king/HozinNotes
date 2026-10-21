package com.hozinking.notes.ui;

import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.NoteEntity;
import com.hozinking.notes.ui.dialog.IosDialog;

/**
 * Gerbang buka catatan terkunci.
 * v1.20: biometrik dulu bila perangkat mendukung (konsisten dengan Ruang Privat),
 * PIN sebagai cadangan. Dipakai NoteListFragment, SearchActivity, ArchiveActivity.
 */
public class NoteLockGate {

    public interface Opener {
        void open(long noteId);
    }

    public static void openMaybeLocked(final FragmentActivity activity,
                                       final NoteEntity note,
                                       final Opener opener) {
        if (note == null || activity == null) return;
        if (!note.locked) {
            opener.open(note.id);
            return;
        }
        if (!VaultPinActivity.isPinSet(activity)) {
            Toast.makeText(activity, R.string.lock_need_pin, Toast.LENGTH_LONG).show();
            return;
        }
        if (VaultPinActivity.canUseBiometric(activity)) {
            authenticateBiometric(activity, note, opener);
        } else {
            showPinDialog(activity, note, opener);
        }
    }

    private static void authenticateBiometric(final FragmentActivity activity,
                                              final NoteEntity note,
                                              final Opener opener) {
        try {
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.lock_pin_title))
                    .setSubtitle(activity.getString(R.string.lock_biometric_sub))
                    .setNegativeButtonText(activity.getString(R.string.lock_use_pin))
                    .build();
            BiometricPrompt prompt = new BiometricPrompt(activity,
                    ContextCompat.getMainExecutor(activity),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                @NonNull BiometricPrompt.AuthenticationResult result) {
                            opener.open(note.id);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode,
                                                          @NonNull CharSequence errString) {
                            if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                showPinDialog(activity, note, opener);
                            }
                            // Batal oleh user -> diam, catatan tetap terkunci.
                        }
                    });
            prompt.authenticate(info);
        } catch (Exception e) {
            showPinDialog(activity, note, opener);
        }
    }

    private static void showPinDialog(final FragmentActivity activity,
                                      final NoteEntity note,
                                      final Opener opener) {
        new IosDialog(activity)
                .setTitle(activity.getString(R.string.lock_pin_title))
                .setInput(activity.getString(R.string.lock_pin_hint), pin -> {
                    if (VaultPinActivity.checkPin(activity, pin)) {
                        opener.open(note.id);
                    } else {
                        Toast.makeText(activity, R.string.lock_pin_wrong,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setInputNumeric(true)
                .setPositive(activity.getString(R.string.lock_open), null)
                .setNegative(activity.getString(R.string.cancel), null)
                .show();
    }

    /** Buka VaultPinActivity mode buat PIN (dipakai saat mengunci tapi PIN belum ada). */
    public static void requestPinSetup(android.app.Activity activity, int requestCode) {
        android.content.Intent i = new android.content.Intent(activity, VaultPinActivity.class);
        i.putExtra(VaultPinActivity.EXTRA_MODE_SET, true);
        activity.startActivityForResult(i, requestCode);
    }
}
