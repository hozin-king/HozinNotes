package com.hozinking.notes.ui;

import android.text.Editable;
import android.text.TextWatcher;

/** TextWatcher ringkas: hanya afterTextChanged. */
public class SimpleTextWatcher implements TextWatcher {

    public interface After {
        void after(Editable s);
    }

    private final After after;

    public SimpleTextWatcher(After after) {
        this.after = after;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        after.after(s);
    }
}
