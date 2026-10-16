package com.hozinking.notes.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Kanvas gambar jari sederhana untuk fitur Sketsa.
 * Coretan disimpan sebagai daftar Stroke (path + paint) sehingga
 * undo cukup menghapus stroke terakhir lalu menggambar ulang.
 * Implementasi ditulis sendiri (konsep umum aplikasi sketsa).
 */
public class SketchView extends View {

    /** Satu coretan: path + properti paint-nya. */
    private static class Stroke {
        final Path path;
        final Paint paint;

        Stroke(Path path, Paint paint) {
            this.path = path;
            this.paint = paint;
        }
    }

    private static final int BG_COLOR = 0xFFFFFFFF;
    private static final float TOUCH_TOLERANCE = 4f;

    private Bitmap bitmap;
    private Canvas offscreen;

    private final List<Stroke> strokes = new ArrayList<>();
    private Path currentPath;
    private float lastX, lastY;
    private int activePointerId = -1;

    private int penColor = 0xFF1A1A1A;
    private float penWidth = 10f;
    private boolean eraser = false;

    public SketchView(Context context) {
        super(context);
        init();
    }

    public SketchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setBackgroundColor(BG_COLOR);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        Bitmap nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas nc = new Canvas(nb);
        nc.drawColor(BG_COLOR);
        if (bitmap != null) {
            nc.drawBitmap(bitmap, 0, 0, null);
            bitmap.recycle();
        }
        bitmap = nb;
        offscreen = nc;
    }

    private Paint newPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setDither(true);
        if (eraser) {
            p.setColor(BG_COLOR);
            p.setStrokeWidth(penWidth * 3f);
        } else {
            p.setColor(penColor);
            p.setStrokeWidth(penWidth);
        }
        return p;
    }

    public void setPenColor(int color) {
        penColor = color;
        eraser = false;
    }

    public void setPenWidth(float widthPx) {
        penWidth = widthPx;
    }

    public void setEraser(boolean on) {
        eraser = on;
    }

    public boolean isEraser() {
        return eraser;
    }

    public boolean hasContent() {
        return !strokes.isEmpty();
    }

    public boolean canUndo() {
        return !strokes.isEmpty();
    }

    /** Hapus stroke terakhir, gambar ulang kanvas dari nol. */
    public void undo() {
        if (strokes.isEmpty() || offscreen == null) return;
        strokes.remove(strokes.size() - 1);
        redrawAll();
    }

    public void clear() {
        strokes.clear();
        redrawAll();
    }

    private void redrawAll() {
        if (offscreen == null) return;
        offscreen.drawColor(BG_COLOR);
        for (Stroke s : strokes) {
            offscreen.drawPath(s.path, s.paint);
        }
        invalidate();
    }

    /** Bitmap hasil sketsa (jangan dimodifikasi pemanggil). */
    public Bitmap getBitmap() {
        return bitmap;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                activePointerId = e.getPointerId(0);
                currentPath = new Path();
                lastX = e.getX();
                lastY = e.getY();
                currentPath.moveTo(lastX, lastY);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = e.findPointerIndex(activePointerId);
                if (idx < 0 || currentPath == null) break;
                float x = e.getX(idx);
                float y = e.getY(idx);
                float dx = Math.abs(x - lastX);
                float dy = Math.abs(y - lastY);
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    currentPath.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f);
                    lastX = x;
                    lastY = y;
                    if (offscreen != null) {
                        offscreen.drawPath(currentPath, newPaint());
                    }
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = e.findPointerIndex(activePointerId);
                if (currentPath != null && idx >= 0) {
                    currentPath.lineTo(e.getX(idx), e.getY(idx));
                }
                if (currentPath != null) {
                    strokes.add(new Stroke(currentPath, newPaint()));
                    currentPath = null;
                }
                activePointerId = -1;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                if (e.getPointerId(idx) == activePointerId) {
                    // Pointer aktif diangkat: akhiri stroke ini.
                    if (currentPath != null) {
                        strokes.add(new Stroke(currentPath, newPaint()));
                        currentPath = null;
                    }
                    activePointerId = -1;
                }
                return true;
            }
            default:
                break;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0, 0, null);
        }
        if (currentPath != null) {
            canvas.drawPath(currentPath, newPaint());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
            bitmap = null;
        }
    }
}
