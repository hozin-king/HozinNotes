package com.hozinking.notes.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.hozinking.notes.R;

/**
 * Dialog gaya iOS: kartu putih membulat 14dp di tengah layar, tombol bertumpuk
 * vertikal (positive bold biru, negative opsional merah bila destructive).
 */
public class IosDialog {

    public interface InputCallback {
        void onInput(String text);
    }

    private final Context ctx;
    private String title;
    private String message;
    private String positiveLabel;
    private Runnable positiveAction;
    private String negativeLabel;
    private Runnable negativeAction;
    private boolean destructive;
    private String inputHint;
    private InputCallback inputCallback;
    private boolean inputNumeric;
    private DialogInterface.OnDismissListener dismissListener;

    public IosDialog(Context ctx) {
        this.ctx = ctx;
    }

    public IosDialog setTitle(String t) {
        this.title = t;
        return this;
    }

    public IosDialog setMessage(String m) {
        this.message = m;
        return this;
    }

    public IosDialog setPositive(String label, Runnable r) {
        this.positiveLabel = label;
        this.positiveAction = r;
        return this;
    }

    public IosDialog setNegative(String label, Runnable r) {
        this.negativeLabel = label;
        this.negativeAction = r;
        return this;
    }

    /** Bila true, tombol negative tampil merah (untuk aksi destruktif). */
    public IosDialog setDestructive(boolean destructive) {
        this.destructive = destructive;
        return this;
    }

    /**
     * Aktifkan mode input teks (dipakai mis. tambah folder). Bila dipakai,
     * tombol positive mengembalikan teks yang diketik ke callback.
     */
    public IosDialog setInput(String hint, InputCallback cb) {
        this.inputHint = hint;
        this.inputCallback = cb;
        return this;
    }

    /** Input jadi keypad angka (untuk PIN). */
    public IosDialog setInputNumeric(boolean b) {
        this.inputNumeric = b;
        return this;
    }

    /** Listener dismiss dialog (mis. untuk wipe rahasia saat dialog ditutup). */
    public IosDialog setOnDismissListener(DialogInterface.OnDismissListener l) {
        this.dismissListener = l;
        return this;
    }

    public void show() {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_ios);
        if (dismissListener != null) {
            dialog.setOnDismissListener(dismissListener);
        }
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.CENTER;
            window.setAttributes(params);
        }

        TextView tvTitle = dialog.findViewById(R.id.dialog_title);
        TextView tvMessage = dialog.findViewById(R.id.dialog_message);
        EditText etInput = dialog.findViewById(R.id.dialog_input);
        Button btnNegative = dialog.findViewById(R.id.dialog_negative);
        Button btnPositive = dialog.findViewById(R.id.dialog_positive);
        View divider = dialog.findViewById(R.id.dialog_divider);

        if (title != null && !title.isEmpty()) {
            tvTitle.setVisibility(View.VISIBLE);
            tvTitle.setText(title);
        } else {
            tvTitle.setVisibility(View.GONE);
        }

        if (message != null && !message.isEmpty()) {
            tvMessage.setVisibility(View.VISIBLE);
            tvMessage.setText(message);
        } else {
            tvMessage.setVisibility(View.GONE);
        }

        final boolean inputMode = inputCallback != null;
        if (inputMode) {
            etInput.setVisibility(View.VISIBLE);
            if (inputHint != null) {
                etInput.setHint(inputHint);
            }
            etInput.setInputType(inputNumeric
                    ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                    : InputType.TYPE_CLASS_TEXT);
        } else {
            etInput.setVisibility(View.GONE);
        }

        if (negativeLabel != null) {
            btnNegative.setVisibility(View.VISIBLE);
            divider.setVisibility(View.VISIBLE);
            btnNegative.setText(negativeLabel);
            if (destructive) {
                btnNegative.setTextColor(ContextCompat.getColor(ctx, R.color.danger));
            }
            btnNegative.setOnClickListener(v -> {
                dialog.dismiss();
                if (negativeAction != null) {
                    negativeAction.run();
                }
            });
        } else {
            btnNegative.setVisibility(View.GONE);
            divider.setVisibility(View.GONE);
        }

        String posLabel = positiveLabel != null ? positiveLabel : ctx.getString(R.string.ok);
        btnPositive.setText(posLabel);
        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            if (inputMode) {
                inputCallback.onInput(etInput.getText().toString().trim());
            } else if (positiveAction != null) {
                positiveAction.run();
            }
        });

        dialog.show();
    }
}
