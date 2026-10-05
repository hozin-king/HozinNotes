package com.hozinking.notes.ui;

import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.EditText;

import java.util.ArrayDeque;

/**
 * Undo/redo untuk EditText berbasis snapshot.
 *
 * - Ketikan dikelompokkan per jeda (GROUP_MS) atau perubahan multi-karakter
 *   (paste/hapus blok) — bukan per karakter.
 * - Snapshot menyimpan SpannableStringBuilder (teks + span ikut tersalin),
 *   jadi format tebal/miring/stabilo tidak rusak saat undo/redo.
 * - Stack dibatasi MAX_STEPS langkah (hemat memori).
 * - Posisi kursor ikut disimpan & dikembalikan (dibatasi panjang teks).
 */
public class UndoRedoManager {

    public interface Listener {
        void onStateChanged(boolean canUndo, boolean canRedo);
    }

    private static final int MAX_STEPS = 50;
    private static final long GROUP_MS = 1200;

    private final EditText edit;
    private final ArrayDeque<State> undoStack = new ArrayDeque<>();
    private final ArrayDeque<State> redoStack = new ArrayDeque<>();

    private boolean tracking = false;
    private boolean applying = false;
    private long lastEditTime = 0;
    private Listener listener;

    private static class State {
        final SpannableStringBuilder text;
        final int cursor;

        State(CharSequence t, int c) {
            text = new SpannableStringBuilder(t);
            cursor = c;
        }
    }

    public UndoRedoManager(EditText edit) {
        this.edit = edit;
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!tracking || applying) return;
                long now = SystemClock.uptimeMillis();
                boolean newGroup = (now - lastEditTime > GROUP_MS)
                        || count > 1 || after > 1;
                if (!newGroup) return;
                State before = new State(s, Math.max(edit.getSelectionStart(), 0));
                State top = undoStack.peek();
                if (top == null || !TextUtils.equals(top.text, before.text)) {
                    undoStack.push(before);
                    while (undoStack.size() > MAX_STEPS) undoStack.removeLast();
                }
                redoStack.clear();
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (!tracking || applying) return;
                lastEditTime = SystemClock.uptimeMillis();
                notifyChanged();
            }
        });
    }

    public void setTracking(boolean tracking) {
        this.tracking = tracking;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Hapus semua history (dipakai setelah konten dimuat). */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        lastEditTime = 0;
        notifyChanged();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        if (!canUndo()) return;
        redoStack.push(captureCurrent());
        apply(undoStack.pop());
    }

    public void redo() {
        if (!canRedo()) return;
        undoStack.push(captureCurrent());
        apply(redoStack.pop());
    }

    private State captureCurrent() {
        return new State(edit.getText(), Math.max(edit.getSelectionStart(), 0));
    }

    private void apply(State st) {
        applying = true;
        try {
            // Salinan baru agar tidak ada aliasing objek dengan stack.
            edit.setText(new SpannableStringBuilder(st.text));
            int len = edit.length();
            edit.setSelection(Math.max(0, Math.min(st.cursor, len)));
        } finally {
            applying = false;
        }
        // Ketikan berikutnya mulai grup baru.
        lastEditTime = 0;
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) listener.onStateChanged(canUndo(), canRedo());
    }
}
