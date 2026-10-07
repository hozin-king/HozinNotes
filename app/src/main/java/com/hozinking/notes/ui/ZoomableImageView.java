package com.hozinking.notes.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * ImageView dengan pinch-to-zoom + double-tap zoom (tanpa library).
 * Dipakai di ImageViewerActivity. Saat gambar di-zoom (> skala fit),
 * swipe horizontal tidak diteruskan ke ViewPager2 agar bisa pan.
 */
public class ZoomableImageView extends AppCompatImageView {

    private final Matrix matrix = new Matrix();
    private final float[] tmp = new float[9];
    private float fitScale = 1f;
    private static final float MAX_SCALE_FACTOR = 4f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float factor = detector.getScaleFactor();
                        float cur = getScale();
                        float next = cur * factor;
                        float min = fitScale;
                        float max = fitScale * MAX_SCALE_FACTOR;
                        if (next < min) factor = min / cur;
                        if (next > max) factor = max / cur;
                        matrix.postScale(factor, factor,
                                detector.getFocusX(), detector.getFocusY());
                        fixBounds();
                        setImageMatrix(matrix);
                        return true;
                    }
                });
        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        float target = getScale() > fitScale * 1.1f
                                ? fitScale : fitScale * 2.5f;
                        zoomTo(target, e.getX(), e.getY());
                        return true;
                    }
                });
    }

    private float getScale() {
        matrix.getValues(tmp);
        return tmp[Matrix.MSCALE_X];
    }

    /** Skala fit awal begitu drawable & ukuran view diketahui. */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) fitToScreen();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (getWidth() > 0 && getHeight() > 0) fitToScreen();
    }

    private void fitToScreen() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        float vw = getWidth(), vh = getHeight();
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        fitScale = Math.min(vw / dw, vh / dh);
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate((vw - dw * fitScale) / 2f,
                (vh - dh * fitScale) / 2f);
        setImageMatrix(matrix);
    }

    private void zoomTo(float target, float fx, float fy) {
        float factor = target / getScale();
        matrix.postScale(factor, factor, fx, fy);
        fixBounds();
        setImageMatrix(matrix);
    }

    /** Jaga gambar tidak keluar layar saat di-zoom / pan. */
    private void fixBounds() {
        Drawable d = getDrawable();
        if (d == null) return;
        float vw = getWidth(), vh = getHeight();
        float dw = d.getIntrinsicWidth() * getScale();
        float dh = d.getIntrinsicHeight() * getScale();
        matrix.getValues(tmp);
        float tx = tmp[Matrix.MTRANS_X], ty = tmp[Matrix.MTRANS_Y];
        float dx = 0, dy = 0;
        if (dw <= vw) dx = (vw - dw) / 2f - tx;
        else dx = Math.max(vw - dw - tx, Math.min(0 - tx, 0));
        if (dh <= vh) dy = (vh - dh) / 2f - ty;
        else dy = Math.max(vh - dh - ty, Math.min(0 - ty, 0));
        matrix.postTranslate(dx, dy);
    }

    /** Dipakai ViewPager2: boleh swipe pindah halaman hanya saat belum di-zoom. */
    public boolean isZoomed() {
        return getScale() > fitScale * 1.05f;
    }

    /** Reset ke fit (dipakai saat halaman diganti). */
    public void resetZoom() {
        fitToScreen();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        handled = gestureDetector.onTouchEvent(event) || handled;
        // Saat zoom, tahan intercept parent (ViewPager2) supaya bisa pan.
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(
                    isZoomed() || scaleDetector.isInProgress());
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            fixBounds();
            setImageMatrix(matrix);
        }
        return handled || super.onTouchEvent(event);
    }
}
