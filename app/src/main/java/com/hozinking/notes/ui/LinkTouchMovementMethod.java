package com.hozinking.notes.ui;

import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.widget.TextView;

/**
 * MovementMethod untuk EditText: link tetap bisa di-tap, tetapi sentuhan di
 * luar link diteruskan ke penanganan default EditText sehingga penempatan
 * kursor & seleksi teks via sentuhan tetap normal.
 *
 * <p>LinkMovementMethod bawaan menggantikan movement method EditText
 * (ArrowKeyMovementMethod) sehingga tap untuk pindah kursor / seleksi
 * menjadi rusak. Kelas ini hanya meng-intercept ACTION_UP yang tepat
 * mengenai URLSpan.
 */
public class LinkTouchMovementMethod extends LinkMovementMethod {

    private static LinkTouchMovementMethod instance;

    public static LinkTouchMovementMethod getInstance() {
        if (instance == null) {
            instance = new LinkTouchMovementMethod();
        }
        return instance;
    }

    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && isOnLink(widget, buffer, event)) {
            return super.onTouchEvent(widget, buffer, event);
        }
        // Bukan di atas link: jangan intercept — biarkan EditText menaruh kursor.
        return false;
    }

    private boolean isOnLink(TextView widget, Spannable buffer, MotionEvent event) {
        try {
            int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
            int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
            Layout layout = widget.getLayout();
            if (layout == null) return false;
            int line = layout.getLineForVertical(y);
            int off = layout.getOffsetForHorizontal(line, x);
            return buffer.getSpans(off, off, URLSpan.class).length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
